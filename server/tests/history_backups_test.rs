use std::net::SocketAddr;

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use serde_json::{json, Value};
use tower::ServiceExt;
use uuid::Uuid;

use messenger_server::config::{Config, S3Config};
use messenger_server::state::AppState;

#[tokio::test]
async fn download_retries_cannot_starve_confirmation_or_cancellation() {
    use base64::{engine::general_purpose::STANDARD, Engine};
    use sha2::{Digest, Sha256};
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _, _) = register_user(&app, ip, "budget_").await;
    let bytes = vec![8u8; 64];
    let hash = hex::encode(Sha256::digest(&bytes));
    let id = Uuid::new_v4();
    let staged = app.clone().oneshot(post_json_request("/history-backup/stage", ip, &token,
        json!({"expected_revision":0,"backup_id":id,"format_version":1,"ciphertext":STANDARD.encode(&bytes),"sha256":hash})))
        .await.unwrap();
    assert_eq!(staged.status(), StatusCode::OK);
    let url = format!("/history-backup/objects/{id}");
    // Simulate a client repeatedly losing the response after a completed download.
    let mut last = StatusCode::OK;
    for _ in 0..31 {
        last = app
            .clone()
            .oneshot(get_request(&url, ip, Some(&token)))
            .await
            .unwrap()
            .status();
    }
    assert_eq!(last, StatusCode::TOO_MANY_REQUESTS);
    let confirmed = app
        .clone()
        .oneshot(post_json_request(
            "/history-backup/confirm",
            ip,
            &token,
            json!({"expected_revision":1,"backup_id":id,"sha256":hash}),
        ))
        .await
        .unwrap();
    assert_eq!(
        confirmed.status(),
        StatusCode::OK,
        "download retries must not block the small commit request"
    );
    let deleted = app
        .clone()
        .oneshot(post_json_request(
            "/history-backup/delete",
            ip,
            &token,
            json!({"expected_revision":2}),
        ))
        .await
        .unwrap();
    assert_eq!(deleted.status(), StatusCode::OK);
    assert!(body_json(deleted).await["active"].is_null());
}

fn test_s3_config() -> S3Config {
    let endpoint =
        std::env::var("S3_ENDPOINT_URL").unwrap_or_else(|_| "http://localhost:9000".to_string());
    S3Config {
        max_upload_duration_seconds: std::env::var("S3_MAX_UPLOAD_DURATION_SECONDS").ok().and_then(|v|v.parse().ok()).unwrap_or(0),
        upload_access_key_id: std::env::var("S3_UPLOAD_ACCESS_KEY_ID").unwrap_or_default(), upload_secret_access_key: std::env::var("S3_UPLOAD_SECRET_ACCESS_KEY").unwrap_or_default(),

        endpoint_url: endpoint.clone(),
        public_endpoint_url: endpoint,
        region: "us-east-1".to_string(),
        bucket: "messenger-attachments-test".to_string(),
        access_key_id: "messenger_minio".to_string(),
        secret_access_key: "messenger_minio_secret".to_string(),
        presign_ttl_seconds: 600,
    }
}

#[tokio::test]
async fn late_confirm_and_lost_delete_response_cannot_resurrect_an_old_backup() {
    use base64::{engine::general_purpose::STANDARD, Engine};
    use sha2::{Digest, Sha256};
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _, _) = register_user(&app, ip, "late_").await;
    let bytes = vec![19u8; 64];
    let hash = hex::encode(Sha256::digest(&bytes));
    let a = Uuid::new_v4();
    let b = Uuid::new_v4();
    let upload = |id, rev| json!({"expected_revision":rev,"backup_id":id,"format_version":1,"ciphertext":STANDARD.encode(&bytes),"sha256":hash});
    let confirm = |id, rev| json!({"expected_revision":rev,"backup_id":id,"sha256":hash});
    for (uri, body, expected) in [
        ("stage", upload(a, 0), 200),
        ("confirm", confirm(a, 1), 200),
        ("stage", upload(b, 2), 200),
        // Another device turns backups off before B is verified.
        (
            "preference",
            json!({"expected_revision":3,"desired_enabled":false}),
            200,
        ),
        ("confirm", confirm(b, 3), 409),
        ("confirm", confirm(b, 4), 409),
    ] {
        let response = app
            .clone()
            .oneshot(post_json_request(
                &format!("/history-backup/{uri}"),
                ip,
                &token,
                body,
            ))
            .await
            .unwrap();
        assert_eq!(response.status().as_u16(), expected, "{uri}");
    }
    // Retrying an old successful confirmation reports current state, without
    // enabling uploads or resurrecting the cancelled staged object.
    let retry = body_json(
        app.clone()
            .oneshot(post_json_request(
                "/history-backup/confirm",
                ip,
                &token,
                confirm(a, 1),
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(retry["desired_enabled"], false);
    assert_eq!(retry["revision"], 4);
    assert_eq!(retry["active"]["backup_id"], json!(a));
    assert!(retry["staged"].is_null());
    let deletion = json!({"expected_revision":4});
    let deleted = app
        .clone()
        .oneshot(post_json_request(
            "/history-backup/delete",
            ip,
            &token,
            deletion.clone(),
        ))
        .await
        .unwrap();
    assert_eq!(deleted.status(), StatusCode::OK);
    // Lost delete response: clients must refresh on 409, not blindly adopt a
    // new revision and delete a newer backup uploaded by another device.
    let duplicate = app
        .clone()
        .oneshot(post_json_request(
            "/history-backup/delete",
            ip,
            &token,
            deletion.clone(),
        ))
        .await
        .unwrap();
    assert_eq!(duplicate.status(), StatusCode::CONFLICT);
    let snapshot = body_json(
        app.clone()
            .oneshot(get_request("/history-backup", ip, Some(&token)))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(snapshot["revision"], 5);
    assert!(snapshot["active"].is_null());
    assert_eq!(snapshot["desired_enabled"], false);
    let enabled = app
        .clone()
        .oneshot(post_json_request(
            "/history-backup/preference",
            ip,
            &token,
            json!({"expected_revision":5,"desired_enabled":true}),
        ))
        .await
        .unwrap();
    assert_eq!(enabled.status(), StatusCode::OK);
    let fresh = Uuid::new_v4();
    for (uri, body, expected) in [
        ("stage", upload(fresh, 6), 200),
        ("confirm", confirm(fresh, 7), 200),
        ("delete", deletion, 409),
        ("confirm", confirm(a, 1), 409),
        ("confirm", confirm(b, 3), 409),
    ] {
        let response = app
            .clone()
            .oneshot(post_json_request(
                &format!("/history-backup/{uri}"),
                ip,
                &token,
                body,
            ))
            .await
            .unwrap();
        assert_eq!(response.status().as_u16(), expected, "{uri}");
    }
    let snapshot = body_json(
        app.clone()
            .oneshot(get_request("/history-backup", ip, Some(&token)))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(snapshot["revision"], 8);
    assert_eq!(snapshot["active"]["backup_id"], json!(fresh));
    assert!(snapshot["staged"].is_null());
}

fn test_turn_config() -> messenger_server::config::TurnConfig {
    messenger_server::config::TurnConfig {
        stun_urls: vec!["stun:127.0.0.1:3478".to_string()],
        turn_urls: vec!["turn:127.0.0.1:3478?transport=udp".to_string()],
        shared_secret: "test-turn-secret".to_string(),
        credential_ttl_seconds: 3600,
    }
}

async fn spawn_state() -> AppState {
    dotenvy::dotenv().ok();

    let database_url = std::env::var("TEST_DATABASE_URL").unwrap_or_else(|_| {
        "postgres://messenger:messenger@localhost:5432/coco_history_backups_20260926".to_string()
    });
    let redis_url =
        std::env::var("TEST_REDIS_URL").unwrap_or_else(|_| "redis://localhost:6379/1".to_string());

    assert!(
        database_url.ends_with("/coco_history_backups_20260926"),
        "Use the isolated history backup test database only"
    );
    let db = sqlx::postgres::PgPoolOptions::new()
        .max_connections(5)
        .connect(&database_url)
        .await
        .expect("connect to test database");
    sqlx::migrate!()
        .run(&db)
        .await
        .expect("run migrations on test database");

    let redis_client = redis::Client::open(redis_url).expect("build redis client");
    let redis = redis_client
        .get_connection_manager()
        .await
        .expect("connect to test redis");

    let config = Config {
        host: "127.0.0.1".to_string(),
        port: 0,
        database_url,
        database_max_connections: 5,
        redis_url: String::new(),
        jwt_secret: "test-only-secret-do-not-use-in-production".to_string(),
        trusted_proxies: Vec::new(),
        jwt_issuer: "messenger-server-test".to_string(),
        jwt_audience: "messenger-clients-test".to_string(),
        access_token_ttl_seconds: 900,
        refresh_token_ttl_seconds: 2_592_000,
        s3: test_s3_config(),
        turn: test_turn_config(),
        fcm: Default::default(),
    };

    let s3 = std::sync::Arc::new(messenger_server::storage::S3Client::new(config.s3.clone()));
    // Media tests use only this separate bucket, never production attachment keys.

    AppState { resources: Default::default(),
        db,
        redis,
        config,
        ws_registry: Default::default(),
        s3,
        push: messenger_server::push::disabled(),
    }
}

#[tokio::test]
async fn media_is_immutable_owned_verified_and_survives_live_attachment_cleanup() {
    use base64::{engine::general_purpose::STANDARD, Engine};
    use sha2::{Digest,Sha256};
    let state=spawn_state().await;
    state.s3.ensure_bucket().await.unwrap();
    let app=messenger_server::app(state.clone());
    let ip=connect_info();
    let (token,user,_)=register_user(&app,ip,"media_").await;
    let (other,_,_)=register_user(&app,ip,"mediaother_").await;
    let user=Uuid::parse_str(&user).unwrap();
    let bytes=vec![37u8;65536];
    let hash=hex::encode(Sha256::digest(&bytes));
    let blob=Uuid::new_v4();
    let start=json!({"expected_revision":0,"blob_id":blob,"bytes":bytes.len(),"sha256":hash});
    let response=app.clone().oneshot(post_json_request("/history-backup/media/start",ip,&token,start.clone())).await.unwrap();
    assert_eq!(response.status(),StatusCode::OK);
    let upload=body_json(response).await;
    let url=upload["upload_url"].as_str().unwrap();
    let client=reqwest::Client::new();
    client.put(url).body(bytes.clone()).send().await.unwrap().error_for_status().unwrap();
    assert_eq!(app.clone().oneshot(get_request(&format!("/history-backup/media/{blob}"),ip,Some(&token))).await.unwrap().status(),StatusCode::NOT_FOUND);
    let seal=app.clone().oneshot(post_json_request(&format!("/history-backup/media/{blob}/seal"),ip,&token,json!({"expected_revision":0}))).await.unwrap();
    assert_eq!(seal.status(),StatusCode::OK);
    let sealed=body_json(seal).await;
    // The previously issued PUT is still valid, but can only mutate staging.
    client.put(url).body(vec![99u8;bytes.len()]).send().await.unwrap().error_for_status().unwrap();
    let restored=client.get(sealed["download_url"].as_str().unwrap()).send().await.unwrap().error_for_status().unwrap().bytes().await.unwrap();
    assert_eq!(restored.as_ref(),bytes.as_slice());
    assert_eq!(app.clone().oneshot(post_json_request("/history-backup/media/start",ip,&token,start)).await.unwrap().status(),StatusCode::CONFLICT);
    assert_eq!(app.clone().oneshot(get_request(&format!("/history-backup/media/{blob}"),ip,Some(&other))).await.unwrap().status(),StatusCode::NOT_FOUND);
    let manifest=vec![9u8;128]; let manifest_hash=hex::encode(Sha256::digest(&manifest));let backup=Uuid::new_v4();
    let upload_manifest=json!({"expected_revision":0,"backup_id":backup,"format_version":2,"ciphertext":STANDARD.encode(&manifest),"sha256":manifest_hash,"media_ids":[blob]});
    assert_eq!(app.clone().oneshot(post_json_request("/history-backup/stage",ip,&token,upload_manifest.clone())).await.unwrap().status(),StatusCode::OK);
    // Same ciphertext with another media set is not an idempotent retry.
    let mut changed=upload_manifest.clone(); changed["media_ids"]=json!([]);
    assert_eq!(app.clone().oneshot(post_json_request("/history-backup/stage",ip,&token,changed)).await.unwrap().status(),StatusCode::CONFLICT);
    assert_eq!(app.clone().oneshot(post_json_request("/history-backup/confirm",ip,&token,json!({"expected_revision":1,"backup_id":backup,"sha256":manifest_hash}))).await.unwrap().status(),StatusCode::OK);
    // Sweeping old objects must preserve active media, and source deletion has
    // no effect on the independent immutable copy.
    sqlx::query("UPDATE history_backup_media SET created_at=now()-interval '3 days',upload_lease_until=now()-interval '1 day' WHERE user_id=$1").bind(user).execute(&state.db).await.unwrap();
    sqlx::query("UPDATE history_backup_storage SET delete_after=now()-interval '1 second' WHERE owner_id=$1 AND delete_after IS NOT NULL").bind(user).execute(&state.db).await.unwrap();
    messenger_server::history_backups::media::cleanup_once(&state).await.unwrap();
    assert_eq!(app.clone().oneshot(get_request(&format!("/history-backup/media/{blob}"),ip,Some(&token))).await.unwrap().status(),StatusCode::OK);
    let mut downgrade=upload_manifest; downgrade["backup_id"]=json!(Uuid::new_v4());downgrade["format_version"]=json!(1);downgrade["media_ids"]=json!([]);downgrade["expected_revision"]=json!(2);
    assert_eq!(app.clone().oneshot(post_json_request("/history-backup/stage",ip,&token,downgrade)).await.unwrap().status(),StatusCode::CONFLICT);
    assert_eq!(app.clone().oneshot(post_json_request("/history-backup/delete",ip,&token,json!({"expected_revision":2}))).await.unwrap().status(),StatusCode::OK);
    assert_eq!(app.clone().oneshot(get_request(&format!("/history-backup/media/{blob}"),ip,Some(&token))).await.unwrap().status(),StatusCode::NOT_FOUND);
    // Simulate lease passage on this test account only, then finish physical GC.
    sqlx::query("UPDATE history_backup_storage SET delete_after=now()-interval '1 second' WHERE owner_id=$1").bind(user).execute(&state.db).await.unwrap();
    messenger_server::history_backups::media::cleanup_once(&state).await.unwrap();
    let remaining:i64=sqlx::query_scalar("SELECT count(*) FROM history_backup_storage WHERE owner_id=$1").bind(user).fetch_one(&state.db).await.unwrap();assert_eq!(remaining,0);
}

#[tokio::test]
async fn failed_seal_pause_and_account_deletion_keep_durable_cleanup_records() {
    use sha2::{Digest,Sha256};
    let state=spawn_state().await;state.s3.ensure_bucket().await.unwrap();
    let app=messenger_server::app(state.clone());let ip=connect_info();
    let (token,user,_)=register_user(&app,ip,"mediacancel_").await;
    let user=Uuid::parse_str(&user).unwrap();let blob=Uuid::new_v4();
    let bytes=vec![11u8;4096];let hash=hex::encode(Sha256::digest(&bytes));
    let response=app.clone().oneshot(post_json_request("/history-backup/media/start",ip,&token,json!({"expected_revision":0,"blob_id":blob,"bytes":bytes.len(),"sha256":hash}))).await.unwrap();
    assert_eq!(response.status(),StatusCode::OK);let upload=body_json(response).await;
    let client=reqwest::Client::new();client.put(upload["upload_url"].as_str().unwrap()).body(vec![22u8;bytes.len()]).send().await.unwrap().error_for_status().unwrap();
    assert_eq!(app.clone().oneshot(post_json_request(&format!("/history-backup/media/{blob}/seal"),ip,&token,json!({"expected_revision":0}))).await.unwrap().status(),StatusCode::BAD_REQUEST);
    let records:i64=sqlx::query_scalar("SELECT count(*) FROM history_backup_storage WHERE owner_id=$1").bind(user).fetch_one(&state.db).await.unwrap();assert_eq!(records,2,"failed COPY hash still has durable cleanup records");
    assert_eq!(app.clone().oneshot(post_json_request("/history-backup/preference",ip,&token,json!({"expected_revision":0,"desired_enabled":false}))).await.unwrap().status(),StatusCode::OK);
    assert_eq!(app.clone().oneshot(post_json_request(&format!("/history-backup/media/{blob}/seal"),ip,&token,json!({"expected_revision":0}))).await.unwrap().status(),StatusCode::CONFLICT);
    let queued:i64=sqlx::query_scalar("SELECT count(*) FROM history_backup_storage WHERE owner_id=$1 AND delete_after>now()").bind(user).fetch_one(&state.db).await.unwrap();assert_eq!(queued,2);
    // Physical account cascade must retain the deletion ledger.
    sqlx::query("DELETE FROM users WHERE id=$1").bind(user).execute(&state.db).await.unwrap();
    let retained:i64=sqlx::query_scalar("SELECT count(*) FROM history_backup_storage WHERE owner_id=$1").bind(user).fetch_one(&state.db).await.unwrap();assert_eq!(retained,2);
    sqlx::query("UPDATE history_backup_storage SET delete_after=now()-interval '1 second' WHERE owner_id=$1").bind(user).execute(&state.db).await.unwrap();
    messenger_server::history_backups::media::cleanup_once(&state).await.unwrap();
}

fn unique_username(prefix: &str) -> String {
    format!("{prefix}{}", Uuid::new_v4().simple())
        .chars()
        .take(32)
        .collect()
}

async fn transfer_device(app:&axum::Router,state:&AppState,ip:ConnectInfo<SocketAddr>,user:Uuid)->(String,Uuid) {
    let name:String=sqlx::query_scalar("SELECT username FROM users WHERE id=$1").bind(user).fetch_one(&state.db).await.unwrap();
    let response=app.clone().oneshot(post_json_request("/auth/login",ip,"",json!({"username":name,"password":"correct-horse-battery","installation_id":Uuid::new_v4(),"device_platform":"ios","device_name":"Transfer test"}))).await.unwrap();
    assert_eq!(response.status(),StatusCode::OK);let value=body_json(response).await;
    (value["access_token"].as_str().unwrap().to_owned(),Uuid::parse_str(value["device_id"].as_str().unwrap()).unwrap())
}
async fn transfer_backup(app:&axum::Router,ip:ConnectInfo<SocketAddr>,token:&str)->Uuid {
    use base64::{engine::general_purpose::STANDARD,Engine};use sha2::{Digest,Sha256};
    let bytes=vec![9u8;64];let hash=hex::encode(Sha256::digest(&bytes));let id=Uuid::new_v4();
    let response=app.clone().oneshot(post_json_request("/history-backup/stage",ip,token,json!({"expected_revision":0,"backup_id":id,"format_version":1,"sha256":hash,"ciphertext":STANDARD.encode(bytes)}))).await.unwrap();assert_eq!(response.status(),StatusCode::OK);
    let response=app.clone().oneshot(post_json_request("/history-backup/confirm",ip,token,json!({"expected_revision":1,"backup_id":id,"sha256":hash}))).await.unwrap();assert_eq!(response.status(),StatusCode::OK);id
}

#[tokio::test]
async fn first_transfer_can_resume_the_same_qr_after_preparing_its_missing_backup() {
    use base64::{engine::general_purpose::STANDARD, Engine};
    use sha2::{Digest, Sha256};

    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (sender, user, _) = register_user(&app, ip, "first_relay_").await;
    let user = Uuid::parse_str(&user).unwrap();
    let (recipient, _) = transfer_device(&app, &state, ip, user).await;

    // This is the actual first-use state observed in the user's database:
    // a receiving phone can create a QR before any source has confirmed a copy.
    let initial = body_json(app.clone().oneshot(get_request("/history-backup", ip, Some(&sender))).await.unwrap()).await;
    assert_eq!(initial["revision"], 0);
    assert_eq!(initial["desired_enabled"], true);
    assert!(initial["active"].is_null());
    let created = app.clone().oneshot(post_json_request("/history-transfer", ip, &recipient,
        json!({"recipient_public_key":STANDARD.encode([21u8;32])}))).await.unwrap();
    assert_eq!(created.status(), StatusCode::OK);
    let request = body_json(created).await;
    let path = format!("/history-transfer/{}", request["transfer_id"].as_str().unwrap());
    let mut publication = json!({"expected_revision":0,"backup_id":Uuid::new_v4(),
        "sender_public_key":STANDARD.encode([22u8;32]),"ciphertext":STANDARD.encode([23u8;60])});
    let missing = app.clone().oneshot(post_json_request(&format!("{path}/publish"), ip, &sender, publication.clone())).await.unwrap();
    assert_eq!(missing.status(), StatusCode::CONFLICT);
    let failure = body_json(missing).await;
    assert_eq!(failure["error"]["code"], "conflict");
    assert_eq!(failure["error"]["message"], "Refresh the confirmed backup before publishing the transfer.");
    let still_waiting = body_json(app.clone().oneshot(get_request(&path, ip, Some(&recipient))).await.unwrap()).await;
    assert_eq!(still_waiting["status"], "waiting");
    assert!(still_waiting["backup_id"].is_null());
    assert!(still_waiting["ciphertext"].is_null());

    // Preparing and verifying the source copy lets both existing device
    // sessions continue the SAME request. No login, QR or key binding changes.
    let backup = transfer_backup(&app, ip, &sender).await;
    publication["expected_revision"] = json!(2);
    publication["backup_id"] = json!(backup);
    for _ in 0..2 {
        let published = app.clone().oneshot(post_json_request(&format!("{path}/publish"), ip, &sender, publication.clone())).await.unwrap();
        assert_eq!(published.status(), StatusCode::OK);
        assert_eq!(body_json(published).await["status"], "ready");
    }
    let ready = body_json(app.clone().oneshot(get_request(&path, ip, Some(&recipient))).await.unwrap()).await;
    assert_eq!(ready["backup_id"], json!(backup));
    assert_eq!(ready["ciphertext"], publication["ciphertext"]);
    let downloaded = app.clone().oneshot(get_request(&format!("/history-backup/objects/{backup}"), ip, Some(&recipient))).await.unwrap();
    assert_eq!(downloaded.status(), StatusCode::OK);
    let object = body_json(downloaded).await;
    let bytes = STANDARD.decode(object["ciphertext"].as_str().unwrap()).unwrap();
    assert_eq!(bytes, vec![9u8;64], "The receiving device must get the exact confirmed opaque archive");
    assert_eq!(object["sha256"], hex::encode(Sha256::digest(&bytes)));
    for _ in 0..2 {
        let acknowledged = app.clone().oneshot(post_json_request(&format!("{path}/ack"), ip, &recipient, json!({"backup_id":backup}))).await.unwrap();
        assert_eq!(acknowledged.status(), StatusCode::OK);
        let value = body_json(acknowledged).await;
        assert_eq!(value["status"], "consumed");
        assert!(value["ciphertext"].is_null());
    }
}

#[tokio::test]
async fn transfer_relay_is_owner_scoped_immutable_and_recipient_acknowledged() {
    use base64::{engine::general_purpose::STANDARD,Engine};
    let state=spawn_state().await;let app=messenger_server::app(state.clone());let ip=connect_info();
    let (sender,user,_)=register_user(&app,ip,"relay_").await;let user=Uuid::parse_str(&user).unwrap();
    let (recipient,_)=transfer_device(&app,&state,ip,user).await;
    let (third,_)=transfer_device(&app,&state,ip,user).await;
    let (other,_,_)=register_user(&app,ip,"relayout_").await;
    let backup=transfer_backup(&app,ip,&sender).await;
    let response=app.clone().oneshot(post_json_request("/history-transfer",ip,&recipient,json!({"recipient_public_key":STANDARD.encode([4u8;32])}))).await.unwrap();
    assert_eq!(response.status(),StatusCode::OK);assert_eq!(response.headers()["cache-control"],"no-store");let record=body_json(response).await;
    let id=record["transfer_id"].as_str().unwrap();let path=format!("/history-transfer/{id}");
    assert_eq!(record["status"],"waiting");assert!(record["ciphertext"].is_null());
    assert_eq!(app.clone().oneshot(get_request(&path,ip,Some(&other))).await.unwrap().status(),StatusCode::NOT_FOUND);
    let publication=json!({"expected_revision":2,"backup_id":backup,"sender_public_key":STANDARD.encode([5u8;32]),"ciphertext":STANDARD.encode([6u8;60])});
    assert_eq!(app.clone().oneshot(post_json_request(&format!("{path}/publish"),ip,&recipient,publication.clone())).await.unwrap().status(),StatusCode::CONFLICT);
    for _ in 0..2 {
        let response=app.clone().oneshot(post_json_request(&format!("{path}/publish"),ip,&sender,publication.clone())).await.unwrap();assert_eq!(response.status(),StatusCode::OK);
        assert_eq!(body_json(response).await["ciphertext"],publication["ciphertext"]);
    }
    let outsider=body_json(app.clone().oneshot(get_request(&path,ip,Some(&third))).await.unwrap()).await;
    assert!(outsider["ciphertext"].is_null());assert!(outsider["sender_public_key"].is_null());
    assert_eq!(app.clone().oneshot(post_json_request(&format!("{path}/publish"),ip,&third,publication.clone())).await.unwrap().status(),StatusCode::CONFLICT);
    let mut changed=publication.clone();changed["ciphertext"]=json!(STANDARD.encode([7u8;60]));
    assert_eq!(app.clone().oneshot(post_json_request(&format!("{path}/publish"),ip,&sender,changed)).await.unwrap().status(),StatusCode::CONFLICT);
    assert_eq!(app.clone().oneshot(post_json_request(&format!("{path}/ack"),ip,&sender,json!({"backup_id":backup}))).await.unwrap().status(),StatusCode::NOT_FOUND);
    assert_eq!(app.clone().oneshot(post_json_request(&format!("{path}/ack"),ip,&recipient,json!({"backup_id":Uuid::new_v4()}))).await.unwrap().status(),StatusCode::CONFLICT);
    for _ in 0..2 {
        let response=app.clone().oneshot(post_json_request(&format!("{path}/ack"),ip,&recipient,json!({"backup_id":backup}))).await.unwrap();assert_eq!(response.status(),StatusCode::OK);
        let value=body_json(response).await;assert_eq!(value["status"],"consumed");assert!(value["ciphertext"].is_null());assert!(value["sender_public_key"].is_null());
    }
    let replay=body_json(app.clone().oneshot(post_json_request(&format!("{path}/publish"),ip,&sender,publication)).await.unwrap()).await;
    assert_eq!(replay["status"],"consumed");assert!(replay["ciphertext"].is_null());
    let cancelled=body_json(app.clone().oneshot(post_json_request(&format!("{path}/cancel"),ip,&recipient,json!({}))).await.unwrap()).await;
    assert_eq!(cancelled["status"],"consumed");
}

#[tokio::test]
async fn transfer_relay_bounds_expiry_cancellation_and_revoked_recipient() {
    use base64::{engine::general_purpose::STANDARD,Engine};
    let state=spawn_state().await;let app=messenger_server::app(state.clone());let ip=connect_info();
    let (sender,user,_)=register_user(&app,ip,"relayexp_").await;let user=Uuid::parse_str(&user).unwrap();
    let (recipient,recipient_device)=transfer_device(&app,&state,ip,user).await;
    let backup=transfer_backup(&app,ip,&sender).await;
    for key in [STANDARD.encode([0u8;32]),STANDARD.encode([1u8;31]),"not base64".into()] {
        assert_eq!(app.clone().oneshot(post_json_request("/history-transfer",ip,&recipient,json!({"recipient_public_key":key}))).await.unwrap().status(),StatusCode::BAD_REQUEST);
    }
    let mut ids=Vec::new();
    for _ in 0..4 {
        let response=app.clone().oneshot(post_json_request("/history-transfer",ip,&recipient,json!({"recipient_public_key":STANDARD.encode([2u8;32])}))).await.unwrap();assert_eq!(response.status(),StatusCode::OK);
        ids.push(Uuid::parse_str(body_json(response).await["transfer_id"].as_str().unwrap()).unwrap());
    }
    assert_eq!(app.clone().oneshot(post_json_request("/history-transfer",ip,&recipient,json!({"recipient_public_key":STANDARD.encode([2u8;32])}))).await.unwrap().status(),StatusCode::CONFLICT);
    let publication=json!({"expected_revision":2,"backup_id":backup,"sender_public_key":STANDARD.encode([3u8;32]),"ciphertext":STANDARD.encode([4u8;60])});
    let cancelled=format!("/history-transfer/{}",ids[0]);
    assert_eq!(app.clone().oneshot(post_json_request(&format!("{cancelled}/cancel"),ip,&sender,json!({}))).await.unwrap().status(),StatusCode::NOT_FOUND);
    for _ in 0..2 {assert_eq!(app.clone().oneshot(post_json_request(&format!("{cancelled}/cancel"),ip,&recipient,json!({}))).await.unwrap().status(),StatusCode::OK);}
    assert_eq!(app.clone().oneshot(post_json_request(&format!("{cancelled}/publish"),ip,&sender,publication.clone())).await.unwrap().status(),StatusCode::CONFLICT);
    let expired=format!("/history-transfer/{}",ids[1]);
    assert_eq!(app.clone().oneshot(post_json_request(&format!("{expired}/publish"),ip,&sender,publication.clone())).await.unwrap().status(),StatusCode::OK);
    sqlx::query("UPDATE history_transfers SET expires_at=now()-interval '1 second' WHERE transfer_id=$1").bind(ids[1]).execute(&state.db).await.unwrap();
    assert_eq!(app.clone().oneshot(get_request(&expired,ip,Some(&recipient))).await.unwrap().status(),StatusCode::GONE);
    assert_eq!(app.clone().oneshot(post_json_request(&format!("{expired}/publish"),ip,&sender,publication.clone())).await.unwrap().status(),StatusCode::GONE);
    assert_eq!(app.clone().oneshot(post_json_request(&format!("{expired}/ack"),ip,&recipient,json!({"backup_id":backup}))).await.unwrap().status(),StatusCode::GONE);
    messenger_server::history_transfer::cleanup_once(&state).await.unwrap();
    let ciphertext:Option<String>=sqlx::query_scalar("SELECT ciphertext FROM history_transfers WHERE transfer_id=$1").bind(ids[1]).fetch_one(&state.db).await.unwrap();assert!(ciphertext.is_none());
    let stale=format!("/history-transfer/{}/publish",ids[2]);let mut bad=publication.clone();bad["expected_revision"]=json!(1);
    assert_eq!(app.clone().oneshot(post_json_request(&stale,ip,&sender,bad)).await.unwrap().status(),StatusCode::CONFLICT);
    sqlx::query("UPDATE devices SET revoked_at=now() WHERE id=$1").bind(recipient_device).execute(&state.db).await.unwrap();
    assert_eq!(app.clone().oneshot(post_json_request(&stale,ip,&sender,publication)).await.unwrap().status(),StatusCode::CONFLICT);
    assert_eq!(app.clone().oneshot(get_request(&format!("/history-transfer/{}",ids[2]),ip,Some(&recipient))).await.unwrap().status(),StatusCode::UNAUTHORIZED);
    sqlx::query("UPDATE history_transfers SET expires_at=now()-interval '25 hours' WHERE transfer_id=$1").bind(ids[1]).execute(&state.db).await.unwrap();
    messenger_server::history_transfer::cleanup_once(&state).await.unwrap();
    assert_eq!(app.clone().oneshot(get_request(&expired,ip,Some(&sender))).await.unwrap().status(),StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn transfer_publication_race_has_exactly_one_immutable_sender() {
    use base64::{engine::general_purpose::STANDARD,Engine};
    let state=spawn_state().await;let app=messenger_server::app(state.clone());let ip=connect_info();
    let (a,user,_)=register_user(&app,ip,"relayrace_").await;let user=Uuid::parse_str(&user).unwrap();
    let (recipient,_)=transfer_device(&app,&state,ip,user).await;let (b,_)=transfer_device(&app,&state,ip,user).await;
    let backup=transfer_backup(&app,ip,&a).await;
    let response=app.clone().oneshot(post_json_request("/history-transfer",ip,&recipient,json!({"recipient_public_key":STANDARD.encode([1u8;32])}))).await.unwrap();assert_eq!(response.status(),StatusCode::OK);
    let record=body_json(response).await;let path=format!("/history-transfer/{}/publish",record["transfer_id"].as_str().unwrap());
    let body=json!({"expected_revision":2,"backup_id":backup,"sender_public_key":STANDARD.encode([2u8;32]),"ciphertext":STANDARD.encode([3u8;60])});
    let (first,second)=tokio::join!(app.clone().oneshot(post_json_request(&path,ip,&a,body.clone())),app.clone().oneshot(post_json_request(&path,ip,&b,body)));
    let statuses=[first.unwrap().status(),second.unwrap().status()];assert_eq!(statuses.iter().filter(|&&v|v==StatusCode::OK).count(),1);assert_eq!(statuses.iter().filter(|&&v|v==StatusCode::CONFLICT).count(),1);
}

#[tokio::test]
async fn ready_transfer_retains_exact_archive_and_media_until_terminal_or_expired() {
    use base64::{engine::general_purpose::STANDARD, Engine};
    use sha2::{Digest, Sha256};
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    for ending in ["consumed", "cancelled", "expired"] {
        let (sender, user, _) = register_user(&app, ip, "pinned_").await;
        let user = Uuid::parse_str(&user).unwrap();
        let (recipient, _) = transfer_device(&app, &state, ip, user).await;
        let old = transfer_backup(&app, ip, &sender).await;
        let blob = Uuid::new_v4();
        let upload_key = format!("history-media/{}", Uuid::new_v4());
        let sealed_key = format!("history-media/{}", Uuid::new_v4());
        // The relay must retain media links as well as archive bytes. Physical
        // copy immutability is covered separately against the S3 fixture.
        sqlx::query("INSERT INTO history_backup_media(user_id,blob_id,bytes,sha256,upload_key,sealed_key,sealed,upload_lease_until) VALUES($1,$2,64,$3,$4,$5,true,now())")
            .bind(user).bind(blob).bind("a".repeat(64)).bind(&upload_key).bind(&sealed_key).execute(&state.db).await.unwrap();
        sqlx::query("INSERT INTO history_backup_media_links(user_id,backup_id,blob_id) VALUES($1,$2,$3)")
            .bind(user).bind(old).bind(blob).execute(&state.db).await.unwrap();
        let old_download = body_json(app.clone().oneshot(get_request(&format!("/history-backup/objects/{old}"), ip, Some(&sender))).await.unwrap()).await;
        let bytes = vec![7u8; 64];
        let hash = hex::encode(Sha256::digest(&bytes));
        let next = Uuid::new_v4();
        let response = app.clone().oneshot(post_json_request("/history-backup/stage", ip, &sender,
            json!({"expected_revision":2,"backup_id":next,"format_version":1,"sha256":hash,"ciphertext":STANDARD.encode(&bytes)}))).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let created = body_json(app.clone().oneshot(post_json_request("/history-transfer", ip, &recipient,
            json!({"recipient_public_key":STANDARD.encode([2u8;32])}))).await.unwrap()).await;
        let id = Uuid::parse_str(created["transfer_id"].as_str().unwrap()).unwrap();
        let path = format!("/history-transfer/{id}");
        let response = app.clone().oneshot(post_json_request(&format!("{path}/publish"), ip, &sender,
            json!({"expected_revision":3,"backup_id":old,"sender_public_key":STANDARD.encode([3u8;32]),"ciphertext":STANDARD.encode([4u8;60])}))).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let confirmation = json!({"expected_revision":3,"backup_id":next,"sha256":hash});
        let response = app.clone().oneshot(post_json_request("/history-backup/confirm", ip, &sender, confirmation.clone())).await.unwrap();
        assert_eq!(response.status(), StatusCode::CONFLICT, "{ending}: do not replace a published restore source");
        let still_old = body_json(app.clone().oneshot(get_request(&format!("/history-backup/objects/{old}"), ip, Some(&recipient))).await.unwrap()).await;
        assert_eq!(still_old, old_download);
        let linked: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM history_backup_media_links WHERE user_id=$1 AND backup_id=$2 AND blob_id=$3)")
            .bind(user).bind(old).bind(blob).fetch_one(&state.db).await.unwrap();
        assert!(linked, "a blocked confirmation preserves the old media links");
        let response = app.clone().oneshot(post_json_request("/history-backup/confirm", ip, &sender,
            json!({"expected_revision":1,"backup_id":old,"sha256":old_download["sha256"]}))).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK, "same archive confirmation remains idempotent");
        let status = body_json(response).await;
        assert_eq!(status["revision"], 3);
        assert_eq!(status["active"]["backup_id"], json!(old));
        assert_eq!(status["staged"]["backup_id"], json!(next));
        match ending {
            "consumed" => {
                assert_eq!(app.clone().oneshot(post_json_request(&format!("{path}/ack"), ip, &recipient, json!({"backup_id":old}))).await.unwrap().status(), StatusCode::OK);
            }
            "cancelled" => {
                assert_eq!(app.clone().oneshot(post_json_request(&format!("{path}/cancel"), ip, &sender, json!({}))).await.unwrap().status(), StatusCode::OK);
            }
            _ => {
                sqlx::query("UPDATE history_transfers SET expires_at=now()-interval '1 second' WHERE transfer_id=$1").bind(id).execute(&state.db).await.unwrap();
            }
        }
        let response = app.clone().oneshot(post_json_request("/history-backup/confirm", ip, &sender, confirmation)).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK, "{ending}: terminal transfer releases its source");
        assert_eq!(body_json(response).await["active"]["backup_id"], json!(next));
    }
}

#[tokio::test]
async fn publication_and_replacement_confirmation_have_one_serialized_winner() {
    use base64::{engine::general_purpose::STANDARD, Engine};
    use sha2::{Digest, Sha256};
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (sender, user, _) = register_user(&app, ip, "pinrace_").await;
    let user = Uuid::parse_str(&user).unwrap();
    let (recipient, _) = transfer_device(&app, &state, ip, user).await;
    let old = transfer_backup(&app, ip, &sender).await;
    let bytes = vec![15u8; 64];
    let hash = hex::encode(Sha256::digest(&bytes));
    let next = Uuid::new_v4();
    assert_eq!(app.clone().oneshot(post_json_request("/history-backup/stage", ip, &sender,
        json!({"expected_revision":2,"backup_id":next,"format_version":1,"sha256":hash,"ciphertext":STANDARD.encode(bytes)}))).await.unwrap().status(), StatusCode::OK);
    let record = body_json(app.clone().oneshot(post_json_request("/history-transfer", ip, &recipient,
        json!({"recipient_public_key":STANDARD.encode([2u8;32])}))).await.unwrap()).await;
    let publish_path = format!("/history-transfer/{}/publish",record["transfer_id"].as_str().unwrap());
    let publication = json!({"expected_revision":3,"backup_id":old,"sender_public_key":STANDARD.encode([3u8;32]),"ciphertext":STANDARD.encode([4u8;60])});
    let confirmation = json!({"expected_revision":3,"backup_id":next,"sha256":hash});
    let (published, confirmed) = tokio::join!(
        app.clone().oneshot(post_json_request(&publish_path, ip, &sender, publication)),
        app.clone().oneshot(post_json_request("/history-backup/confirm", ip, &sender, confirmation)),
    );
    let statuses = [published.unwrap().status(), confirmed.unwrap().status()];
    assert_eq!(statuses.iter().filter(|&&s| s==StatusCode::OK).count(), 1);
    assert_eq!(statuses.iter().filter(|&&s| s==StatusCode::CONFLICT).count(), 1);
    let snapshot = body_json(app.clone().oneshot(get_request("/history-backup", ip, Some(&recipient))).await.unwrap()).await;
    assert_eq!(snapshot["active"]["backup_id"], json!(if statuses[0]==StatusCode::OK {old} else {next}));
}

#[tokio::test]
async fn source_check_requires_only_the_current_private_history_window_and_keeps_it_after_refresh() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (old_token, owner, old_device) = register_user(&app, ip, "window_owner_").await;
    let (_, peer, _) = register_user(&app, ip, "window_peer_").await;
    let owner = Uuid::parse_str(&owner).unwrap();
    let peer = Uuid::parse_str(&peer).unwrap();
    let old_session: Uuid = sqlx::query_scalar("SELECT id FROM sessions WHERE user_id=$1 AND device_id=$2 AND revoked_at IS NULL ORDER BY created_at DESC LIMIT 1")
        .bind(owner).bind(old_device).fetch_one(&state.db).await.unwrap();
    sqlx::query("UPDATE sessions SET created_at=now()-interval '3 hours' WHERE id=$1")
        .bind(old_session).execute(&state.db).await.unwrap();
    let chat: Uuid = sqlx::query_scalar("INSERT INTO chats(user_a_id,user_b_id) VALUES(LEAST($1::uuid,$2::uuid),GREATEST($1::uuid,$2::uuid)) RETURNING id")
        .bind(owner).bind(peer).fetch_one(&state.db).await.unwrap();
    sqlx::query("INSERT INTO chat_members(chat_id,user_id) VALUES($1,$2),($1,$3)")
        .bind(chat).bind(owner).bind(peer).execute(&state.db).await.unwrap();
    let old_message: Uuid = sqlx::query_scalar("INSERT INTO messages(chat_id,sender_id,sender_device_id,client_message_id,ciphertext,created_at) VALUES($1,$2,$3,$4,$5,now()-interval '2 hours') RETURNING id")
        .bind(chat).bind(owner).bind(old_device).bind(Uuid::new_v4()).bind(vec![31u8;32]).fetch_one(&state.db).await.unwrap();
    let old_call: Uuid = sqlx::query_scalar("INSERT INTO calls(caller_user_id,callee_user_id,media_kind,state,end_reason,created_at,ended_at) VALUES($1,$2,'audio','ended','missed',now()-interval '2 hours',now()-interval '2 hours') RETURNING id")
        .bind(owner).bind(peer).fetch_one(&state.db).await.unwrap();

    let name: String = sqlx::query_scalar("SELECT username FROM users WHERE id=$1").bind(owner).fetch_one(&state.db).await.unwrap();
    let login = app.clone().oneshot(post_json_request("/auth/login", ip, "", json!({"username":name,"password":"correct-horse-battery","installation_id":Uuid::new_v4(),"device_platform":"android","device_name":"Fresh private workspace"}))).await.unwrap();
    assert_eq!(login.status(), StatusCode::OK);
    let login = body_json(login).await;
    let fresh = login["access_token"].as_str().unwrap();
    let fresh_device = Uuid::parse_str(login["device_id"].as_str().unwrap()).unwrap();
    assert!(login["history_before_sequence"].as_i64().unwrap() > 0);
    let calls = body_json(app.clone().oneshot(get_request("/calls/history", ip, Some(fresh))).await.unwrap()).await;
    assert!(calls["items"].as_array().unwrap().is_empty(), "A password login must not fetch the pre-login call");
    let sync = body_json(app.clone().oneshot(get_request("/sync?after_sequence=0", ip, Some(fresh))).await.unwrap()).await;
    assert!(sync["messages"].as_array().unwrap().is_empty());
    let empty = body_json(app.clone().oneshot(post_json_request("/history-backup/source-check", ip, fresh,
        json!({"message_ids":[],"call_ids":[]}))).await.unwrap()).await;
    assert_eq!(empty, json!({"complete":true}), "A deliberately empty fresh workspace must not require hidden pre-login rows");
    let old_incomplete = body_json(app.clone().oneshot(post_json_request("/history-backup/source-check", ip, &old_token,
        json!({"message_ids":[],"call_ids":[]}))).await.unwrap()).await;
    assert_eq!(old_incomplete, json!({"complete":false}), "The original session still owns its earlier visible window");

    let new_message: Uuid = sqlx::query_scalar("INSERT INTO messages(chat_id,sender_id,sender_device_id,client_message_id,ciphertext) VALUES($1,$2,$3,$4,$5) RETURNING id")
        .bind(chat).bind(owner).bind(old_device).bind(Uuid::new_v4()).bind(vec![32u8;32]).fetch_one(&state.db).await.unwrap();
    sqlx::query("INSERT INTO message_envelopes(message_id,recipient_device_id,message_type,ciphertext) VALUES($1,$2,2,$3)")
        .bind(new_message).bind(fresh_device).bind(vec![33u8;32]).execute(&state.db).await.unwrap();
    let new_call: Uuid = sqlx::query_scalar("INSERT INTO calls(caller_user_id,callee_user_id,media_kind,state,end_reason,ended_at) VALUES($1,$2,'audio','ended','missed',now()) RETURNING id")
        .bind(owner).bind(peer).fetch_one(&state.db).await.unwrap();
    for (messages, calls, complete) in [
        (vec![], vec![], false),
        (vec![new_message], vec![], false),
        (vec![], vec![new_call], false),
        (vec![old_message], vec![old_call], false),
        (vec![new_message], vec![new_call], true),
    ] {
        let response = app.clone().oneshot(post_json_request("/history-backup/source-check", ip, fresh,
            json!({"message_ids":messages,"call_ids":calls}))).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(body_json(response).await, json!({"complete":complete}));
    }
    let refresh = app.clone().oneshot(post_json_request("/auth/refresh", ip, "", json!({"refresh_token":login["refresh_token"]}))).await.unwrap();
    assert_eq!(refresh.status(), StatusCode::OK);
    let refreshed = body_json(refresh).await;
    let refreshed_token = refreshed["access_token"].as_str().unwrap();
    let covered = body_json(app.clone().oneshot(post_json_request("/history-backup/source-check", ip, refreshed_token,
        json!({"message_ids":[new_message],"call_ids":[new_call]}))).await.unwrap()).await;
    assert_eq!(covered, json!({"complete":true}), "Refresh follows its original login boundary");
    let calls = body_json(app.clone().oneshot(get_request("/calls/history", ip, Some(refreshed_token))).await.unwrap()).await;
    assert_eq!(calls["items"].as_array().unwrap().len(), 1);
    assert_eq!(calls["items"][0]["id"], json!(new_call));
    let blocked = app.clone().oneshot(post_json_request(&format!("/users/{peer}/block"), ip, refreshed_token, json!({}))).await.unwrap();
    assert_eq!(blocked.status(), StatusCode::OK);
    let hidden = body_json(app.clone().oneshot(post_json_request("/history-backup/source-check", ip, refreshed_token,
        json!({"message_ids":[],"call_ids":[]}))).await.unwrap()).await;
    assert_eq!(hidden, json!({"complete":true}), "Rows hidden by the same device privacy policy as history are not an inaccessible backup prerequisite");
    let calls = body_json(app.clone().oneshot(get_request("/calls/history", ip, Some(refreshed_token))).await.unwrap()).await;
    assert!(calls["items"].as_array().unwrap().is_empty());
    let mut unblock = post_json_request(&format!("/users/{peer}/block"), ip, refreshed_token, json!({}));
    *unblock.method_mut() = axum::http::Method::DELETE;
    assert_eq!(app.clone().oneshot(unblock).await.unwrap().status(), StatusCode::OK);
    let missing_visible_again = body_json(app.clone().oneshot(post_json_request("/history-backup/source-check", ip, refreshed_token,
        json!({"message_ids":[],"call_ids":[]}))).await.unwrap()).await;
    assert_eq!(missing_visible_again, json!({"complete":false}), "Unblocking restores the requirement to include visible rows");
    let old_covered = body_json(app.clone().oneshot(post_json_request("/history-backup/source-check", ip, &old_token,
        json!({"message_ids":[old_message,new_message],"call_ids":[old_call,new_call]}))).await.unwrap()).await;
    assert_eq!(old_covered, json!({"complete":true}));
}

#[tokio::test]
async fn source_check_cannot_mistake_empty_device_envelopes_for_complete_account_history() {
    let state=spawn_state().await;let app=messenger_server::app(state.clone());let ip=connect_info();
    let (a,aid,ad)=register_user(&app,ip,"sourcea_").await;
    let (b,bid,_)=register_user(&app,ip,"sourceb_").await;
    let aid=Uuid::parse_str(&aid).unwrap();let bid=Uuid::parse_str(&bid).unwrap();
    let chat:Uuid=sqlx::query_scalar("INSERT INTO chats(user_a_id,user_b_id) VALUES(LEAST($1::uuid,$2::uuid),GREATEST($1::uuid,$2::uuid)) RETURNING id")
        .bind(aid).bind(bid).fetch_one(&state.db).await.unwrap();
    sqlx::query("INSERT INTO chat_members(chat_id,user_id) VALUES($1,$2),($1,$3)").bind(chat).bind(aid).bind(bid).execute(&state.db).await.unwrap();
    let (id,sequence):(Uuid,i64)=sqlx::query_as("INSERT INTO messages(chat_id,sender_id,sender_device_id,client_message_id,ciphertext) VALUES($1,$2,$3,$4,$5) RETURNING id,sequence")
        .bind(chat).bind(aid).bind(ad).bind(Uuid::new_v4()).bind(vec![1u8;32]).fetch_one(&state.db).await.unwrap();
    // No envelope exists for B, just like a phone added after this message.
    for ids in [vec![],vec![Uuid::new_v4()]] {
        let response=app.clone().oneshot(post_json_request("/history-backup/source-check",ip,&b,json!({"message_ids":ids}))).await.unwrap();
        assert_eq!(response.status(),StatusCode::OK);assert_eq!(body_json(response).await,json!({"complete":false}));
    }
    let response=app.clone().oneshot(post_json_request("/history-backup/source-check",ip,&b,json!({"message_ids":[id]}))).await.unwrap();
    assert_eq!(body_json(response).await,json!({"complete":true}));
    sqlx::query("INSERT INTO personal_chat_clears(user_id,chat_id,through_sequence) VALUES($1,$2,$3)").bind(bid).bind(chat).bind(sequence).execute(&state.db).await.unwrap();
    let response=app.clone().oneshot(post_json_request("/history-backup/source-check",ip,&b,json!({"message_ids":[]}))).await.unwrap();
    assert_eq!(body_json(response).await,json!({"complete":true}));
    // B's clear cannot suppress A's required history.
    let response=app.clone().oneshot(post_json_request("/history-backup/source-check",ip,&a,json!({"message_ids":[]}))).await.unwrap();
    assert_eq!(body_json(response).await,json!({"complete":false}));
    sqlx::query("INSERT INTO deleted_chats(user_id,chat_id) VALUES($1,$2)").bind(aid).bind(chat).execute(&state.db).await.unwrap();
    let response=app.clone().oneshot(post_json_request("/history-backup/source-check",ip,&a,json!({"message_ids":[]}))).await.unwrap();
    assert_eq!(body_json(response).await,json!({"complete":true}));
    sqlx::query("DELETE FROM deleted_chats WHERE user_id=$1 AND chat_id=$2").bind(aid).bind(chat).execute(&state.db).await.unwrap();
    sqlx::query("UPDATE messages SET deleted_at=now() WHERE id=$1").bind(id).execute(&state.db).await.unwrap();
    let response=app.clone().oneshot(post_json_request("/history-backup/source-check",ip,&a,json!({"message_ids":[]}))).await.unwrap();
    assert_eq!(body_json(response).await,json!({"complete":true}));
    for ids in [vec![id,id],vec![Uuid::nil()],(0..50001).map(|_|Uuid::new_v4()).collect()] {
        let response=app.clone().oneshot(post_json_request("/history-backup/source-check",ip,&a,json!({"message_ids":ids}))).await.unwrap();assert_eq!(response.status(),StatusCode::BAD_REQUEST);
    }
}

#[tokio::test]
async fn restore_check_projects_only_authorized_canonical_sender_deletions() {
    let state=spawn_state().await;let app=messenger_server::app(state.clone());let ip=connect_info();
    let (a,aid,ad)=register_user(&app,ip,"restorea_").await;
    let (b,bid,bd)=register_user(&app,ip,"restoreb_").await;
    let (outsider,outsider_id,_)=register_user(&app,ip,"restorec_").await;
    let aid=Uuid::parse_str(&aid).unwrap();let bid=Uuid::parse_str(&bid).unwrap();
    let chat:Uuid=sqlx::query_scalar("INSERT INTO chats(user_a_id,user_b_id) VALUES(LEAST($1::uuid,$2::uuid),GREATEST($1::uuid,$2::uuid)) RETURNING id")
        .bind(aid).bind(bid).fetch_one(&state.db).await.unwrap();
    sqlx::query("INSERT INTO chat_members(chat_id,user_id) VALUES($1,$2),($1,$3)").bind(chat).bind(aid).bind(bid).execute(&state.db).await.unwrap();
    let (id,original):(Uuid,i64)=sqlx::query_as("INSERT INTO messages(chat_id,sender_id,sender_device_id,client_message_id,ciphertext) VALUES($1,$2,$3,$4,$5) RETURNING id,sequence")
        .bind(chat).bind(aid).bind(ad).bind(Uuid::new_v4()).bind(vec![1u8;32]).fetch_one(&state.db).await.unwrap();
    // Synthetic malformed event cannot become an authoritative tombstone.
    sqlx::query("INSERT INTO messages(chat_id,sender_id,sender_device_id,client_message_id,ciphertext,kind,target_message_id) VALUES($1,$2,$3,$4,$5,'delete',$6)")
        .bind(chat).bind(bid).bind(bd).bind(Uuid::new_v4()).bind(vec![2u8;32]).bind(id).execute(&state.db).await.unwrap();
    let response=app.clone().oneshot(post_json_request("/history-backup/restore-check",ip,&a,json!({"message_ids":[id]}))).await.unwrap();
    assert_eq!(response.status(),StatusCode::OK);assert_eq!(body_json(response).await,json!({"deleted":[],"deleted_call_ids":[]}));
    sqlx::query("UPDATE messages SET deleted_at=now() WHERE id=$1").bind(id).execute(&state.db).await.unwrap();
    let response=app.clone().oneshot(post_json_request("/history-backup/restore-check",ip,&a,json!({"message_ids":[id]}))).await.unwrap();
    assert_eq!(response.status(),StatusCode::CONFLICT,"known deletion with missing authentic event must fail closed");
    let deleted:i64=sqlx::query_scalar("INSERT INTO messages(chat_id,sender_id,sender_device_id,client_message_id,ciphertext,kind,target_message_id) VALUES($1,$2,$3,$4,$5,'delete',$6) RETURNING sequence")
        .bind(chat).bind(aid).bind(ad).bind(Uuid::new_v4()).bind(vec![3u8;32]).bind(id).fetch_one(&state.db).await.unwrap();
    assert!(deleted>original);
    for token in [&a,&b] {
        let response=app.clone().oneshot(post_json_request("/history-backup/restore-check",ip,token,json!({"message_ids":[id,Uuid::new_v4()]}))).await.unwrap();
        assert_eq!(response.status(),StatusCode::OK);
        assert_eq!(response.headers().get("cache-control").unwrap(),"no-store");
        assert_eq!(body_json(response).await,json!({"deleted":[{"id":id,"sequence":deleted}],"deleted_call_ids":[]}));
    }
    let response=app.clone().oneshot(post_json_request("/history-backup/restore-check",ip,&outsider,json!({"message_ids":[id]}))).await.unwrap();
    assert_eq!(body_json(response).await,json!({"deleted":[],"deleted_call_ids":[]}));
    sqlx::query("UPDATE chat_members SET left_at=now() WHERE chat_id=$1 AND user_id=$2").bind(chat).bind(bid).execute(&state.db).await.unwrap();
    let response=app.clone().oneshot(post_json_request("/history-backup/restore-check",ip,&b,json!({"message_ids":[id]}))).await.unwrap();
    assert_eq!(body_json(response).await,json!({"deleted":[{"id":id,"sequence":deleted}],"deleted_call_ids":[]}));

    // Call restore projection: only supplied IDs may be echoed, never an
    // enumeration. A visible, non-deleted call of the requester is kept; a
    // nonexistent/foreign supplied ID is projected as removed (the client
    // already supplied it and must not resurrect it).
    let own_call: Uuid = sqlx::query_scalar(
        "INSERT INTO calls(caller_user_id,callee_user_id,media_kind,state,end_reason,ended_at) \
         VALUES($1,$2,'audio','ended','missed',now()) RETURNING id",
    ).bind(aid).bind(Uuid::parse_str(&outsider_id).unwrap()).fetch_one(&state.db).await.unwrap();
    let foreign_call: Uuid = sqlx::query_scalar(
        "INSERT INTO calls(caller_user_id,callee_user_id,media_kind,state,end_reason,ended_at) \
         VALUES($1,$2,'audio','ended','missed',now()) RETURNING id",
    ).bind(Uuid::parse_str(&outsider_id).unwrap()).bind(bid).fetch_one(&state.db).await.unwrap();
    let unknown_call = Uuid::new_v4();
    let response=app.clone().oneshot(post_json_request("/history-backup/restore-check",ip,&a,json!({"message_ids":[],"call_ids":[own_call,foreign_call,unknown_call]}))).await.unwrap();
    assert_eq!(response.status(),StatusCode::OK);
    assert_eq!(response.headers().get("cache-control").unwrap(),"no-store");
    let mut expected_calls=vec![foreign_call,unknown_call];
    expected_calls.sort();
    assert_eq!(body_json(response).await,json!({"deleted":[],"deleted_call_ids":expected_calls}));
    assert!(!expected_calls.contains(&own_call), "saved, visible call is not marked deleted");

    sqlx::query("INSERT INTO call_history_deletions(user_id,call_id) VALUES($1,$2)")
        .bind(aid).bind(own_call).execute(&state.db).await.unwrap();
    let response=app.clone().oneshot(post_json_request("/history-backup/restore-check",ip,&a,json!({"message_ids":[],"call_ids":[own_call,foreign_call,unknown_call]}))).await.unwrap();
    let mut expected_calls=vec![own_call,foreign_call,unknown_call];
    expected_calls.sort();
    assert_eq!(body_json(response).await,json!({"deleted":[],"deleted_call_ids":expected_calls}));

    // Duplicate/nil/over-limit call IDs use the same strict 400 contract.
    for ids in [vec![own_call,own_call],vec![Uuid::nil()],(0..501).map(|_|Uuid::new_v4()).collect()] {
        let response=app.clone().oneshot(post_json_request("/history-backup/restore-check",ip,&a,json!({"message_ids":[],"call_ids":ids}))).await.unwrap();
        assert_eq!(response.status(),StatusCode::BAD_REQUEST);
    }

    for ids in [vec![id,id],vec![Uuid::nil()],(0..501).map(|_|Uuid::new_v4()).collect()] {
        let response=app.clone().oneshot(post_json_request("/history-backup/restore-check",ip,&a,json!({"message_ids":ids}))).await.unwrap();assert_eq!(response.status(),StatusCode::BAD_REQUEST);
    }
    sqlx::query("UPDATE sessions SET revoked_at=now() WHERE user_id=$1").bind(aid).execute(&state.db).await.unwrap();
    assert_eq!(app.clone().oneshot(post_json_request("/history-backup/restore-check",ip,&a,json!({"message_ids":[id]}))).await.unwrap().status(),StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn delayed_copy_cannot_overwrite_a_later_seal_or_resurrect_paused_media() {
    use std::sync::{Arc,atomic::{AtomicUsize,Ordering}};
    use sha2::{Digest,Sha256};
    #[derive(Clone)]
    struct SlowStorage {
        copies:Arc<AtomicUsize>, started:Arc<tokio::sync::Notify>, release:Arc<tokio::sync::Notify>,
        objects:Arc<tokio::sync::Mutex<std::collections::HashMap<String,Vec<u8>>>>,
    }
    async fn storage_handler(axum::extract::State(s):axum::extract::State<SlowStorage>, request:Request<Body>)->axum::response::Response {
        let key=request.uri().path().to_owned();
        // Model the same unversioned bucket preflight used by the real server.
        if key=="/messenger-attachments-test" {
            if request.method()==axum::http::Method::HEAD {
                return axum::response::Response::new(Body::empty());
            }
            if request.method()==axum::http::Method::GET && request.uri().query().is_some_and(|q|q.split('&').any(|part|part=="versioning" || part.starts_with("versioning="))) {
                return axum::response::Response::new(Body::from("<VersioningConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"/>"));
            }
        }
        if request.method()==axum::http::Method::PUT {
            if let Some(source)=request.headers().get("x-amz-copy-source") {
                let source=source.to_str().unwrap().to_owned();
                if s.copies.fetch_add(1,Ordering::SeqCst)==0 { s.started.notify_one();s.release.notified().await; }
                let value=s.objects.lock().await.get(&source).unwrap().clone();
                s.objects.lock().await.insert(key,value);
                return axum::response::Response::new(Body::from("<CopyObjectResult><ETag>test</ETag></CopyObjectResult>"));
            }
            let bytes=request.into_body().collect().await.unwrap().to_bytes().to_vec();
            s.objects.lock().await.insert(key,bytes);
            return axum::response::Response::new(Body::empty());
        }
        if let Some(bytes)=s.objects.lock().await.get(&key).cloned() {
            return axum::response::Response::builder().header("content-length",bytes.len()).body(
                if request.method()==axum::http::Method::HEAD {Body::empty()} else {Body::from(bytes)}).unwrap();
        }
        axum::response::Response::builder().status(404).body(Body::empty()).unwrap()
    }
    for cancel in [false,true] {
        let slow=SlowStorage { copies:Arc::new(AtomicUsize::new(0)),started:Arc::new(tokio::sync::Notify::new()),release:Arc::new(tokio::sync::Notify::new()),objects:Default::default() };
        let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let endpoint=format!("http://{}",listener.local_addr().unwrap());
        let router=axum::Router::new().fallback(storage_handler).with_state(slow.clone());
        let service=tokio::spawn(async move {axum::serve(listener,router).await.unwrap();});
        let mut state=spawn_state().await;
        state.config.s3.endpoint_url=endpoint.clone();state.config.s3.public_endpoint_url=endpoint;
        state.s3=Arc::new(messenger_server::storage::S3Client::new(state.config.s3.clone()));
        state.s3.ensure_bucket().await.unwrap();
        let app=messenger_server::app(state.clone());let ip=connect_info();
        let (token,user,_)=register_user(&app,ip,"racecopy_").await;
        let user=Uuid::parse_str(&user).unwrap();let blob=Uuid::new_v4();let bytes=vec![43u8;8192];
        let response=app.clone().oneshot(post_json_request("/history-backup/media/start",ip,&token,json!({"expected_revision":0,"blob_id":blob,"bytes":bytes.len(),"sha256":hex::encode(Sha256::digest(&bytes))}))).await.unwrap();
        assert_eq!(response.status(),StatusCode::OK);let upload=body_json(response).await;
        reqwest::Client::new().put(upload["upload_url"].as_str().unwrap()).body(bytes.clone()).send().await.unwrap().error_for_status().unwrap();
        let first_app=app.clone();let request=post_json_request(&format!("/history-backup/media/{blob}/seal"),ip,&token,json!({"expected_revision":0}));
        let first=tokio::spawn(async move {first_app.oneshot(request).await.unwrap()});
        tokio::time::timeout(std::time::Duration::from_secs(10),slow.started.notified()).await.unwrap();
        let initial_key:String=sqlx::query_scalar("SELECT sealed_key FROM history_backup_media WHERE user_id=$1 AND blob_id=$2").bind(user).bind(blob).fetch_one(&state.db).await.unwrap();
        if cancel {
            let response=tokio::time::timeout(std::time::Duration::from_secs(2),app.clone().oneshot(post_json_request("/history-backup/preference",ip,&token,json!({"expected_revision":0,"desired_enabled":false})))).await.expect("pause must not wait for S3").unwrap();
            assert_eq!(response.status(),StatusCode::OK);
        } else {
            let response=app.clone().oneshot(post_json_request(&format!("/history-backup/media/{blob}/seal"),ip,&token,json!({"expected_revision":0}))).await.unwrap();
            assert_eq!(response.status(),StatusCode::OK);
            let second_key:String=sqlx::query_scalar("SELECT sealed_key FROM history_backup_media WHERE user_id=$1 AND blob_id=$2").bind(user).bind(blob).fetch_one(&state.db).await.unwrap();
            assert_ne!(initial_key,second_key,"each remote COPY has a unique destination");
        }
        slow.release.notify_one();
        assert_eq!(first.await.unwrap().status(),StatusCode::CONFLICT);
        let response=app.clone().oneshot(get_request(&format!("/history-backup/media/{blob}"),ip,Some(&token))).await.unwrap();
        assert_eq!(response.status(),if cancel {StatusCode::NOT_FOUND}else{StatusCode::OK});
        let queued:bool=sqlx::query_scalar("SELECT delete_after>now()+interval '23 hours' FROM history_backup_storage WHERE object_key=$1").bind(initial_key).fetch_one(&state.db).await.unwrap();assert!(queued);
        // Exercise account cascade through active backup links too (FK order).
        if !cancel {
            use base64::{engine::general_purpose::STANDARD,Engine};
            let archive=vec![4u8;64];let archive_hash=hex::encode(Sha256::digest(&archive));let backup=Uuid::new_v4();
            let response=app.clone().oneshot(post_json_request("/history-backup/stage",ip,&token,json!({"expected_revision":0,"backup_id":backup,"format_version":2,"ciphertext":STANDARD.encode(archive),"sha256":archive_hash,"media_ids":[blob]}))).await.unwrap();
            assert_eq!(response.status(),StatusCode::OK);
            let response=app.clone().oneshot(post_json_request("/history-backup/confirm",ip,&token,json!({"expected_revision":1,"backup_id":backup,"sha256":archive_hash}))).await.unwrap();
            assert_eq!(response.status(),StatusCode::OK);
        }
        sqlx::query("DELETE FROM users WHERE id=$1").bind(user).execute(&state.db).await.unwrap();
        let unqueued:i64=sqlx::query_scalar("SELECT count(*) FROM history_backup_storage WHERE owner_id=$1 AND delete_after IS NULL").bind(user).fetch_one(&state.db).await.unwrap();assert_eq!(unqueued,0);
        service.abort();
    }
}

/// Each test gets its own fake client IP so the shared Redis-backed rate
/// limiter (keyed by IP) can't cause cross-test interference when the suite
/// runs in parallel.
fn connect_info() -> ConnectInfo<SocketAddr> {
    let octets: [u8; 3] = std::array::from_fn(|_| rand::random());
    ConnectInfo(SocketAddr::from((
        [10, octets[0], octets[1], octets[2]],
        12345,
    )))
}

fn register_request(
    ip: ConnectInfo<SocketAddr>,
    username: &str,
    display_name: &str,
) -> Request<Body> {
    let body = json!({
        "username": username,
        "password": "correct-horse-battery",
        "display_name": display_name,
        "installation_id": Uuid::new_v4(),
        "device_platform": "android",
        "device_name": "Test Device",
    });
    Request::builder()
        .method("POST")
        .uri("/auth/register")
        .header("content-type", "application/json")
        .extension(ip)
        .body(Body::from(body.to_string()))
        .unwrap()
}

fn get_request(uri: &str, ip: ConnectInfo<SocketAddr>, token: Option<&str>) -> Request<Body> {
    let mut builder = Request::builder().method("GET").uri(uri).extension(ip);
    if let Some(token) = token {
        builder = builder.header("authorization", format!("Bearer {token}"));
    }
    builder.body(Body::empty()).unwrap()
}

fn post_json_request(
    uri: &str,
    ip: ConnectInfo<SocketAddr>,
    token: &str,
    body: Value,
) -> Request<Body> {
    Request::builder()
        .method("POST")
        .uri(uri)
        .header("content-type", "application/json")
        .header("authorization", format!("Bearer {token}"))
        .extension(ip)
        .body(Body::from(body.to_string()))
        .unwrap()
}

async fn body_json(response: axum::response::Response) -> Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap()
}

/// Registers a fresh user and returns (access_token, user_id, device_id).
async fn register_user(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    prefix: &str,
) -> (String, String, Uuid) {
    let username = unique_username(prefix);
    let response = app
        .clone()
        .oneshot(register_request(ip, &username, "Test User"))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
        Uuid::parse_str(body["device_id"].as_str().unwrap()).unwrap(),
    )
}

#[tokio::test]
async fn opaque_backup_lifecycle_is_account_scoped_atomic_and_revision_guarded() {
    use base64::{engine::general_purpose::STANDARD, Engine};
    use sha2::{Digest, Sha256};
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token, user, _) = register_user(&app, ip, "backup_").await;
    let (other, _, _) = register_user(&app, ip, "other_").await;
    let status = app
        .clone()
        .oneshot(get_request("/history-backup", ip, Some(&token)))
        .await
        .unwrap();
    assert_eq!(status.status(), StatusCode::OK);
    let initial = body_json(status).await;
    assert_eq!(initial["desired_enabled"], true);
    assert!(initial["active"].is_null());
    assert_eq!(initial["revision"], 0);
    assert_eq!(
        app.clone()
            .oneshot(get_request("/history-backup", ip, None))
            .await
            .unwrap()
            .status(),
        StatusCode::UNAUTHORIZED
    );
    let bytes = vec![42u8; 256];
    let hash = hex::encode(Sha256::digest(&bytes));
    let id = Uuid::new_v4();
    let upload = json!({"expected_revision":0,"backup_id":id,"format_version":1,"ciphertext":STANDARD.encode(&bytes),"sha256":hash});
    let first = app
        .clone()
        .oneshot(post_json_request(
            "/history-backup/stage",
            ip,
            &token,
            upload.clone(),
        ))
        .await
        .unwrap();
    assert_eq!(first.status(), StatusCode::OK);
    let staged = body_json(first).await;
    assert_eq!(staged["revision"], 1);
    assert!(staged["active"].is_null());
    let retry = body_json(
        app.clone()
            .oneshot(post_json_request(
                "/history-backup/stage",
                ip,
                &token,
                upload.clone(),
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(retry["revision"], 1);
    let url = format!("/history-backup/objects/{id}");
    assert_eq!(
        app.clone()
            .oneshot(get_request(&url, ip, Some(&other)))
            .await
            .unwrap()
            .status(),
        StatusCode::NOT_FOUND
    );
    let download = app
        .clone()
        .oneshot(get_request(&url, ip, Some(&token)))
        .await
        .unwrap();
    assert_eq!(download.headers()["cache-control"], "no-store");
    assert_eq!(
        body_json(download).await["ciphertext"],
        upload["ciphertext"]
    );
    let wrong = json!({"expected_revision":1,"backup_id":id,"sha256":"wrong"});
    assert_eq!(
        app.clone()
            .oneshot(post_json_request(
                "/history-backup/confirm",
                ip,
                &token,
                wrong
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::CONFLICT
    );
    let confirm = json!({"expected_revision":1,"backup_id":id,"sha256":hash});
    let active = body_json(
        app.clone()
            .oneshot(post_json_request(
                "/history-backup/confirm",
                ip,
                &token,
                confirm.clone(),
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(active["revision"], 2);
    assert!(!active["active"]["verified_at"].is_null());
    assert_eq!(
        body_json(
            app.clone()
                .oneshot(post_json_request(
                    "/history-backup/confirm",
                    ip,
                    &token,
                    confirm
                ))
                .await
                .unwrap()
        )
        .await["revision"],
        2
    );
    let mut replacement = upload.clone();
    replacement["backup_id"] = json!(Uuid::new_v4());
    assert_eq!(
        app.clone()
            .oneshot(post_json_request(
                "/history-backup/stage",
                ip,
                &token,
                replacement.clone()
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::CONFLICT
    );
    replacement["expected_revision"] = json!(2);
    let pending = body_json(
        app.clone()
            .oneshot(post_json_request(
                "/history-backup/stage",
                ip,
                &token,
                replacement,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(pending["active"]["backup_id"], json!(id));
    assert!(!pending["staged"].is_null());
    assert_eq!(
        body_json(
            app.clone()
                .oneshot(post_json_request(
                    "/history-backup/stage",
                    ip,
                    &token,
                    upload.clone()
                ))
                .await
                .unwrap()
        )
        .await["revision"],
        3
    );
    let disable = json!({"expected_revision":3,"desired_enabled":false});
    let disabled = body_json(
        app.clone()
            .oneshot(post_json_request(
                "/history-backup/preference",
                ip,
                &token,
                disable,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(disabled["revision"], 4);
    assert_eq!(disabled["active"]["backup_id"], json!(id));
    assert!(disabled["staged"].is_null());
    assert_eq!(
        app.clone()
            .oneshot(post_json_request(
                "/history-backup/stage",
                ip,
                &token,
                upload.clone()
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::CONFLICT
    );
    assert_eq!(
        app.clone()
            .oneshot(post_json_request(
                "/history-backup/delete",
                ip,
                &token,
                json!({"expected_revision":2})
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::CONFLICT
    );
    let cleared = body_json(
        app.clone()
            .oneshot(post_json_request(
                "/history-backup/delete",
                ip,
                &token,
                json!({"expected_revision":4}),
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(cleared["revision"], 5);
    assert!(cleared["active"].is_null());
    assert_eq!(cleared["desired_enabled"], false);
    assert_eq!(
        app.clone()
            .oneshot(get_request(&url, ip, Some(&token)))
            .await
            .unwrap()
            .status(),
        StatusCode::NOT_FOUND
    );
    let enabled = body_json(
        app.clone()
            .oneshot(post_json_request(
                "/history-backup/preference",
                ip,
                &token,
                json!({"expected_revision":5,"desired_enabled":true}),
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(enabled["revision"], 6);
    assert_eq!(
        app.clone()
            .oneshot(post_json_request(
                "/history-backup/stage",
                ip,
                &token,
                upload
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::CONFLICT
    );
    sqlx::query("UPDATE sessions SET revoked_at=now() WHERE user_id=$1")
        .bind(Uuid::parse_str(&user).unwrap())
        .execute(&state.db)
        .await
        .unwrap();
    assert_eq!(
        app.clone()
            .oneshot(get_request("/history-backup", ip, Some(&token)))
            .await
            .unwrap()
            .status(),
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn rejects_bad_archives_and_unknown_secret_fields_without_mutating_state() {
    use base64::{engine::general_purpose::STANDARD, Engine};
    use sha2::{Digest, Sha256};
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _, _) = register_user(&app, ip, "invalid_").await;
    let bytes = vec![13u8; 64];
    let valid = json!({"expected_revision":0,"backup_id":Uuid::new_v4(),"format_version":1,"ciphertext":STANDARD.encode(&bytes),"sha256":hex::encode(Sha256::digest(&bytes))});
    for (field, value) in [
        ("format_version", json!(3)),
        ("sha256", json!("bad")),
        ("ciphertext", json!("!")),
        ("backup_id", json!(Uuid::nil())),
    ] {
        let mut request = valid.clone();
        request[field] = value;
        assert_eq!(
            app.clone()
                .oneshot(post_json_request(
                    "/history-backup/stage",
                    ip,
                    &token,
                    request
                ))
                .await
                .unwrap()
                .status(),
            StatusCode::BAD_REQUEST
        );
    }
    let mut secret = valid;
    secret["recovery_key"] = json!("must-not-be-accepted");
    assert_eq!(
        app.clone()
            .oneshot(post_json_request(
                "/history-backup/stage",
                ip,
                &token,
                secret
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::UNPROCESSABLE_ENTITY
    );
    let status = body_json(
        app.clone()
            .oneshot(get_request("/history-backup", ip, Some(&token)))
            .await
            .unwrap(),
    )
    .await;
    assert!(status["active"].is_null());
    assert!(status["staged"].is_null());
    assert_eq!(status["revision"], 0);
}

#[tokio::test]
async fn size_boundaries_and_reused_id_preserve_the_committed_copy() {
    use base64::{engine::general_purpose::STANDARD, Engine};
    use sha2::{Digest, Sha256};
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _, _) = register_user(&app, ip, "size_").await;
    let id = Uuid::new_v4();
    let bytes = vec![4u8; 29];
    let request = |id, rev, data: &[u8]| json!({"expected_revision":rev,"backup_id":id,"format_version":1,"ciphertext":STANDARD.encode(data),"sha256":hex::encode(Sha256::digest(data))});
    let minimum = app
        .clone()
        .oneshot(post_json_request(
            "/history-backup/stage",
            ip,
            &token,
            request(id, 0, &bytes),
        ))
        .await
        .unwrap();
    assert_eq!(minimum.status(), StatusCode::OK);
    let confirm = app.clone().oneshot(post_json_request("/history-backup/confirm", ip, &token,
        json!({"expected_revision":1,"backup_id":id,"sha256":hex::encode(Sha256::digest(&bytes))}))).await.unwrap();
    assert_eq!(confirm.status(), StatusCode::OK);
    let reused = app
        .clone()
        .oneshot(post_json_request(
            "/history-backup/stage",
            ip,
            &token,
            request(id, 2, &[5u8; 29]),
        ))
        .await
        .unwrap();
    assert_eq!(reused.status(), StatusCode::CONFLICT);
    for size in [28, 8 * 1024 * 1024 + 1] {
        let invalid = vec![6u8; size];
        let response = app
            .clone()
            .oneshot(post_json_request(
                "/history-backup/stage",
                ip,
                &token,
                request(Uuid::new_v4(), 2, &invalid),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::BAD_REQUEST, "size {size}");
    }
    let max = vec![6u8; 8 * 1024 * 1024];
    let response = app
        .clone()
        .oneshot(post_json_request(
            "/history-backup/stage",
            ip,
            &token,
            request(Uuid::new_v4(), 2, &max),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let snapshot = body_json(response).await;
    assert_eq!(snapshot["revision"], 3);
    assert_eq!(snapshot["active"]["backup_id"], json!(id));
    assert_eq!(snapshot["staged"]["bytes"], 8 * 1024 * 1024);
}

#[tokio::test]
async fn concurrent_writers_have_one_winner_and_revoked_admitted_session_is_rechecked() {
    use base64::{engine::general_purpose::STANDARD, Engine};
    use sha2::{Digest, Sha256};
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token, user, _) = register_user(&app, ip, "race_").await;
    let bytes = vec![7u8; 64];
    let request = |id| json!({"expected_revision":0,"backup_id":id,"format_version":1,"ciphertext":STANDARD.encode(&bytes),"sha256":hex::encode(Sha256::digest(&bytes))});
    let (a, b) = tokio::join!(
        app.clone().oneshot(post_json_request(
            "/history-backup/stage",
            ip,
            &token,
            request(Uuid::new_v4())
        )),
        app.clone().oneshot(post_json_request(
            "/history-backup/stage",
            ip,
            &token,
            request(Uuid::new_v4())
        ))
    );
    let mut statuses = vec![a.unwrap().status().as_u16(), b.unwrap().status().as_u16()];
    statuses.sort();
    assert_eq!(statuses, vec![200, 409]);
    let claims = messenger_server::auth::jwt::decode_access_token(&state.config, &token).unwrap();
    let admitted = messenger_server::auth::middleware::AuthUser {
        user_id: claims.sub,
        device_id: claims.device_id,
        session_id: claims.session_id,
    };
    sqlx::query("UPDATE sessions SET revoked_at=now() WHERE user_id=$1")
        .bind(Uuid::parse_str(&user).unwrap())
        .execute(&state.db)
        .await
        .unwrap();
    let result =
        messenger_server::history_backups::status(axum::extract::State(state), admitted).await;
    assert!(matches!(
        result,
        Err(messenger_server::error::AppError::SessionRevoked)
    ));
}

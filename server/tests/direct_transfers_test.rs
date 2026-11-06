//! Real router + PostgreSQL authorization/race tests. Never uses a production DB.
//! Opaque fixture ciphertext here tests mailbox transport, not Signal cryptography.
#[allow(dead_code)]
mod push_common;
use axum::{
    body::Body,
    http::{Request, StatusCode},
    Router,
};
use base64::{engine::general_purpose::STANDARD, Engine as _};
use http_body_util::BodyExt;
use messenger_server::{config::Config, state::AppState};
use push_common as h;
use serde_json::{json, Value};
use std::{sync::Arc, time::Duration};
use tower::ServiceExt;
use uuid::Uuid;

async fn state() -> AppState {
    let url = std::env::var("DIRECT_QA_DATABASE_URL").expect("dedicated QA database required");
    let dbname = url.rsplit('/').next().unwrap_or("");
    assert!(
        dbname.starts_with("coco_direct_qa_"),
        "Refuse non-isolated database"
    );
    let redis_url = std::env::var("DIRECT_QA_REDIS_URL").expect("dedicated QA Redis required");
    let db = sqlx::postgres::PgPoolOptions::new()
        .max_connections(8)
        .connect(&url)
        .await
        .unwrap();
    sqlx::migrate!().run(&db).await.unwrap();
    let redis = redis::Client::open(redis_url)
        .unwrap()
        .get_connection_manager()
        .await
        .unwrap();
    let config = Config {
        host: "127.0.0.1".into(),
        port: 0,
        database_url: url,
        database_max_connections: 8,
        redis_url: String::new(),
        jwt_secret: "direct-transfer-isolated-test-only-not-a-production-key".into(),
        trusted_proxies: vec![],
        jwt_issuer: "direct-qa".into(),
        jwt_audience: "direct-qa-clients".into(),
        access_token_ttl_seconds: 900,
        refresh_token_ttl_seconds: 2_592_000,
        s3: h::test_s3_config(),
        turn: h::test_turn_config(),
        fcm: Default::default(),
    };
    let s3 = Arc::new(messenger_server::storage::S3Client::new(config.s3.clone()));
    AppState {
        resources: Default::default(),
        db,
        redis,
        config,
        ws_registry: Default::default(),
        s3,
        push: messenger_server::push::disabled(),
    }
}
fn req(method: &str, path: &str, token: &str, body: Value) -> Request<Body> {
    Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {token}"))
        .header("content-type", "application/json")
        .extension(h::connect_info())
        .body(if method == "GET" {
            Body::empty()
        } else {
            Body::from(body.to_string())
        })
        .unwrap()
}
async fn http(
    app: &Router,
    method: &str,
    path: &str,
    token: &str,
    body: Value,
) -> (StatusCode, Value) {
    let response = tokio::time::timeout(
        Duration::from_secs(10),
        app.clone().oneshot(req(method, path, token, body)),
    )
    .await
    .expect("request did not deadlock")
    .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (
        status,
        serde_json::from_slice(&bytes)
            .unwrap_or_else(|_| json!({"response":String::from_utf8_lossy(&bytes)})),
    )
}
async fn expect(
    app: &Router,
    method: &str,
    path: &str,
    token: &str,
    body: Value,
    status: StatusCode,
) -> Value {
    let (s, b) = http(app, method, path, token, body).await;
    assert_eq!(s, status, "{method} {path}: {b}");
    b
}

#[tokio::test]
async fn mailbox_preserves_device_consent_identity_idempotency_and_terminal_state() {
    let state = state().await;
    let app = messenger_server::app(state.clone());
    let (a, au, ad, an) = h::register_user_full(&app, h::connect_info(), "dfa").await;
    let (b, bu, bd, bn) = h::register_user_full(&app, h::connect_info(), "dfb").await;
    let (c, _, _) = h::register_user(&app, h::connect_info(), "dfc").await;
    let a2 = h::login_second_device(&app, h::connect_info(), &an).await;
    let b2 = h::login_second_device(&app, h::connect_info(), &bn).await;
    let bd2:Uuid=sqlx::query_scalar("SELECT id FROM devices WHERE user_id=$1 AND id<>$2 AND revoked_at IS NULL ORDER BY created_at DESC LIMIT 1")
        .bind(Uuid::parse_str(&bu).unwrap()).bind(Uuid::parse_str(&bd).unwrap()).fetch_one(&state.db).await.unwrap();
    let bd2 = bd2.to_string();
    let chat = h::create_direct_chat(&app, h::connect_info(), &a, &bu).await;
    let capability = expect(
        &app,
        "GET",
        "/direct-transfers/capabilities",
        &a,
        json!({}),
        StatusCode::OK,
    )
    .await;
    assert_eq!(
        capability,
        json!({"version":1,"max_file_bytes":68_719_476_736i64,"direct_only":true})
    );
    let offer = Uuid::new_v4();
    let offered = expect(
        &app,
        "POST",
        &format!("/chats/{chat}/messages"),
        &a,
        json!({
            "client_message_id":offer,"envelopes":[
                {"recipient_device_id":bd,"message_type":2,"ciphertext":"AQ=="},
                {"recipient_device_id":bd2,"message_type":2,"ciphertext":"AQ=="}
            ]
        }),
        StatusCode::OK,
    )
    .await;
    let transfer = Uuid::new_v4();
    let path = format!("/direct-transfers/{transfer}");
    let registration = json!({"chat_id":chat,"offer_client_message_id":offer,"recipient_user_id":bu,"expires_at":chrono::Utc::now().timestamp()+3600});
    expect(
        &app,
        "PUT",
        &path,
        &a2,
        registration.clone(),
        StatusCode::NOT_FOUND,
    )
    .await;
    expect(&app, "PUT", &path, &a, registration.clone(), StatusCode::OK).await;
    expect(&app, "PUT", &path, &a, registration.clone(), StatusCode::OK).await;
    let mut alias = registration.clone();
    alias["expires_at"] = json!(chrono::Utc::now().timestamp() + 7200);
    expect(&app, "PUT", &path, &a, alias, StatusCode::CONFLICT).await;
    for (method, suffix) in [
        ("GET", ""),
        ("POST", "/claim"),
        ("GET", "/signals"),
        ("POST", "/close"),
    ] {
        expect(
            &app,
            method,
            &format!("{path}{suffix}"),
            &c,
            json!({}),
            StatusCode::NOT_FOUND,
        )
        .await;
    }
    expect(&app, "GET", &path, &a2, json!({}), StatusCode::NOT_FOUND).await;
    let b_late = h::login_second_device(&app, h::connect_info(), &bn).await;
    expect(
        &app,
        "GET",
        &path,
        &b_late,
        json!({}),
        StatusCode::NOT_FOUND,
    )
    .await;
    expect(
        &app,
        "POST",
        &format!("{path}/claim"),
        &b_late,
        json!({}),
        StatusCode::NOT_FOUND,
    )
    .await;
    let initial = expect(&app, "GET", &path, &b, json!({}), StatusCode::OK).await;
    assert!(
        initial["recipient_device_id"].is_null(),
        "Viewing must not accept"
    );
    expect(
        &app,
        "GET",
        &format!("{path}/signals"),
        &b,
        json!({}),
        StatusCode::CONFLICT,
    )
    .await;
    let signal_id = Uuid::new_v4();
    let signal = json!({"client_signal_id":signal_id,"recipient_device_id":bd,"message_type":2,"ciphertext":"AQ=="});
    expect(
        &app,
        "POST",
        &format!("{path}/signals"),
        &a,
        signal.clone(),
        StatusCode::CONFLICT,
    )
    .await;
    // Simultaneous acceptance: precisely one receiving device wins.
    let claim_path = format!("{path}/claim");
    let (r1, r2) = tokio::join!(
        http(&app, "POST", &claim_path, &b, json!({})),
        http(&app, "POST", &claim_path, &b2, json!({}))
    );
    assert!(matches!(
        (r1.0, r2.0),
        (StatusCode::OK, StatusCode::CONFLICT) | (StatusCode::CONFLICT, StatusCode::OK)
    ));
    let (winner, winning_device, loser) = if r1.0 == StatusCode::OK {
        (&b, &bd, &b2)
    } else {
        (&b2, &bd2, &b)
    };
    expect(&app, "POST", &claim_path, winner, json!({}), StatusCode::OK).await;
    expect(
        &app,
        "POST",
        &format!("{path}/close"),
        loser,
        json!({}),
        StatusCode::NOT_FOUND,
    )
    .await;
    let mut actual_signal = signal.clone();
    actual_signal["recipient_device_id"] = json!(winning_device);
    let mut wrong_target = actual_signal.clone();
    wrong_target["recipient_device_id"] = json!(ad);
    expect(
        &app,
        "POST",
        &format!("{path}/signals"),
        &a,
        wrong_target,
        StatusCode::NOT_FOUND,
    )
    .await;
    let mut wrong_type = actual_signal.clone();
    wrong_type["message_type"] = json!(3);
    expect(
        &app,
        "POST",
        &format!("{path}/signals"),
        &a,
        wrong_type,
        StatusCode::BAD_REQUEST,
    )
    .await;
    let mut oversized = actual_signal.clone();
    oversized["ciphertext"] = json!("A".repeat(100 * 1024));
    expect(
        &app,
        "POST",
        &format!("{path}/signals"),
        &a,
        oversized,
        StatusCode::PAYLOAD_TOO_LARGE,
    )
    .await;
    let receipt = expect(
        &app,
        "POST",
        &format!("{path}/signals"),
        &a,
        actual_signal.clone(),
        StatusCode::OK,
    )
    .await;
    let repeated = expect(
        &app,
        "POST",
        &format!("{path}/signals"),
        &a,
        actual_signal.clone(),
        StatusCode::OK,
    )
    .await;
    assert_eq!(receipt, repeated);
    let mut another = actual_signal.clone();
    another["client_signal_id"] = json!(Uuid::new_v4());
    expect(
        &app,
        "POST",
        &format!("{path}/signals"),
        &a,
        another,
        StatusCode::OK,
    )
    .await;
    let mut changed = actual_signal.clone();
    changed["ciphertext"] = json!("Ag==");
    expect(
        &app,
        "POST",
        &format!("{path}/signals"),
        &a,
        changed,
        StatusCode::CONFLICT,
    )
    .await;
    let page = expect(
        &app,
        "GET",
        &format!("{path}/signals?after=0&limit=1"),
        winner,
        json!({}),
        StatusCode::OK,
    )
    .await;
    assert_eq!(page["has_more"], true);
    assert_eq!(page["signals"].as_array().unwrap().len(), 1);
    assert_eq!(page["signals"][0]["ciphertext"], "AQ==");
    assert_eq!(page["signals"][0]["sender_device_id"], ad);
    let sender_page = expect(
        &app,
        "GET",
        &format!("{path}/signals"),
        &a,
        json!({}),
        StatusCode::OK,
    )
    .await;
    assert!(sender_page["signals"].as_array().unwrap().is_empty());
    expect(
        &app,
        "GET",
        &format!("{path}/signals?after=-1"),
        winner,
        json!({}),
        StatusCode::BAD_REQUEST,
    )
    .await;
    expect(
        &app,
        "GET",
        &format!("{path}/signals?limit=33"),
        winner,
        json!({}),
        StatusCode::BAD_REQUEST,
    )
    .await;
    let mut empty = actual_signal.clone();
    empty["client_signal_id"] = json!(Uuid::new_v4());
    empty["ciphertext"] = json!("");
    expect(
        &app,
        "POST",
        &format!("{path}/signals"),
        &a,
        empty,
        StatusCode::BAD_REQUEST,
    )
    .await;
    let mut big = actual_signal.clone();
    big["client_signal_id"] = json!(Uuid::new_v4());
    big["ciphertext"] = json!(STANDARD.encode(vec![0; 65_537]));
    expect(
        &app,
        "POST",
        &format!("{path}/signals"),
        &a,
        big,
        StatusCode::BAD_REQUEST,
    )
    .await;
    // Closing retains delivery of the already queued encrypted terminal event.
    expect(
        &app,
        "POST",
        &format!("{path}/close"),
        &a,
        json!({}),
        StatusCode::OK,
    )
    .await;
    expect(
        &app,
        "POST",
        &claim_path,
        winner,
        json!({}),
        StatusCode::GONE,
    )
    .await;
    expect(
        &app,
        "POST",
        &format!("{path}/close"),
        &a,
        json!({}),
        StatusCode::OK,
    )
    .await;
    expect(
        &app,
        "POST",
        &format!("{path}/signals"),
        &a,
        actual_signal.clone(),
        StatusCode::OK,
    )
    .await;
    let mut fresh = actual_signal.clone();
    fresh["client_signal_id"] = json!(Uuid::new_v4());
    expect(
        &app,
        "POST",
        &format!("{path}/signals"),
        &a,
        fresh,
        StatusCode::GONE,
    )
    .await;
    expect(
        &app,
        "GET",
        &format!("{path}/signals"),
        winner,
        json!({}),
        StatusCode::OK,
    )
    .await;
    let no_uploads: i64 = sqlx::query_scalar("SELECT count(*) FROM attachments")
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(
        no_uploads, 0,
        "Signaling must never stage file bytes in attachments"
    );
    // Block after admission, then revoke peer, then remove original offer.
    expect(
        &app,
        "POST",
        &format!("/chats/{chat}/block"),
        &b,
        json!({"blocked_user_id":au}),
        StatusCode::OK,
    )
    .await;
    expect(&app, "GET", &path, &a, json!({}), StatusCode::CONFLICT).await;
    expect(
        &app,
        "POST",
        &format!("{path}/signals"),
        &a,
        actual_signal.clone(),
        StatusCode::CONFLICT,
    )
    .await;
    expect(
        &app,
        "POST",
        &format!("{path}/close"),
        &a,
        json!({}),
        StatusCode::CONFLICT,
    )
    .await;
    expect(
        &app,
        "POST",
        &format!("/chats/{chat}/unblock"),
        &b,
        json!({}),
        StatusCode::OK,
    )
    .await;
    sqlx::query("UPDATE devices SET revoked_at=now() WHERE id=$1")
        .bind(Uuid::parse_str(winning_device).unwrap())
        .execute(&state.db)
        .await
        .unwrap();
    expect(&app, "GET", &path, &a, json!({}), StatusCode::NOT_FOUND).await;
    expect(
        &app,
        "POST",
        &format!("{path}/signals"),
        &a,
        actual_signal.clone(),
        StatusCode::NOT_FOUND,
    )
    .await;
    sqlx::query("UPDATE devices SET revoked_at=NULL WHERE id=$1")
        .bind(Uuid::parse_str(winning_device).unwrap())
        .execute(&state.db)
        .await
        .unwrap();
    sqlx::query("UPDATE messages SET deleted_at=now() WHERE id=$1")
        .bind(Uuid::parse_str(offered["id"].as_str().unwrap()).unwrap())
        .execute(&state.db)
        .await
        .unwrap();
    expect(&app, "GET", &path, &a, json!({}), StatusCode::NOT_FOUND).await;
    sqlx::query(
        "UPDATE direct_file_transfers SET expires_at=now()-interval '1 second' WHERE id=$1",
    )
    .bind(transfer)
    .execute(&state.db)
    .await
    .unwrap();
    expect(&app, "GET", &path, &a, json!({}), StatusCode::GONE).await;
    // Keep an explicit guard against accidental file content/logging schema additions.
    let columns:Vec<String>=sqlx::query_scalar("SELECT column_name FROM information_schema.columns WHERE table_name='direct_file_transfers'").fetch_all(&state.db).await.unwrap();
    for forbidden in [
        "filename",
        "mime_type",
        "sha256",
        "file_bytes",
        "object_key",
    ] {
        assert!(!columns.iter().any(|c| c == forbidden))
    }
    // Stable device limit must reject requests even across changing client IPs.
    let mut redis = state.redis.clone();
    let key = format!("ratelimit:direct-file-write:{ad}:0.0.0.0");
    let _: () = redis::cmd("SET")
        .arg(key)
        .arg(120)
        .arg("EX")
        .arg(60)
        .query_async(&mut redis)
        .await
        .unwrap();
    for _ in 0..2 {
        expect(
            &app,
            "POST",
            &format!("{path}/close"),
            &a,
            json!({}),
            StatusCode::TOO_MANY_REQUESTS,
        )
        .await;
    }
    let _ = au; // sender identity exercised through real authenticated routes.
}

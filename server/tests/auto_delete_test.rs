use std::net::SocketAddr;
use std::time::Duration;

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, StatusCode};
use base64::{engine::general_purpose::STANDARD, Engine as _};
use futures_util::SinkExt;
use http_body_util::BodyExt;
use serde_json::{json, Value};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::tungstenite::Message as WsMessage;
use tower::ServiceExt;
use uuid::Uuid;

use messenger_server::config::{Config, S3Config};
use messenger_server::state::AppState;

fn test_s3_config() -> S3Config {
    let endpoint =
        std::env::var("S3_ENDPOINT_URL").unwrap_or_else(|_| "http://localhost:9000".to_string());
    S3Config {
        max_upload_duration_seconds: std::env::var("S3_MAX_UPLOAD_DURATION_SECONDS")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(0),
        upload_access_key_id: std::env::var("S3_UPLOAD_ACCESS_KEY_ID").unwrap_or_default(),
        upload_secret_access_key: std::env::var("S3_UPLOAD_SECRET_ACCESS_KEY").unwrap_or_default(),

        endpoint_url: endpoint.clone(),
        public_endpoint_url: endpoint,
        region: "us-east-1".to_string(),
        bucket: "messenger-attachments-test".to_string(),
        access_key_id: "messenger_minio".to_string(),
        secret_access_key: "messenger_minio_secret".to_string(),
        presign_ttl_seconds: 600,
    }
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
    assert_eq!(
        std::env::var("COCO_AUTO_DELETE_TEST_ISOLATED").as_deref(),
        Ok("1"),
        "isolated auto-delete fixture required"
    );

    let database_url =
        std::env::var("TEST_DATABASE_URL").expect("explicit isolated TEST_DATABASE_URL");
    assert!(
        database_url.ends_with("/auto_delete_test"),
        "refuse non-isolated database"
    );
    let redis_url =
        std::env::var("TEST_REDIS_URL").unwrap_or_else(|_| "redis://localhost:6379/1".to_string());

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
    s3.ensure_bucket()
        .await
        .expect("ensure test attachments bucket exists");

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

const PASSWORD: &str = "correct-horse-battery";
fn ip() -> ConnectInfo<SocketAddr> {
    ConnectInfo(SocketAddr::from((
        [10, rand::random(), rand::random(), rand::random()],
        12345,
    )))
}
fn request(method: &str, uri: &str, token: Option<&str>, body: Option<Value>) -> Request<Body> {
    let mut b = Request::builder().method(method).uri(uri).extension(ip());
    if let Some(token) = token {
        b = b.header("authorization", format!("Bearer {token}"));
    }
    if let Some(body) = body {
        b.header("content-type", "application/json")
            .body(Body::from(body.to_string()))
            .unwrap()
    } else {
        b.body(Body::empty()).unwrap()
    }
}
async fn json(response: axum::response::Response) -> Value {
    serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap()
}
async fn register(app: &axum::Router) -> Value {
    let response = app.clone().oneshot(request("POST","/auth/register",None,Some(json!({
        "username":format!("auto_{}",Uuid::new_v4().simple()).chars().take(32).collect::<String>(),
        "password":PASSWORD,"display_name":"Auto delete fixture","installation_id":Uuid::new_v4(),
        "device_platform":"android","device_name":"Foreground fixture"
    })))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    json(response).await
}
fn token(auth: &Value) -> &str {
    auth["access_token"].as_str().unwrap()
}
fn user(auth: &Value) -> Uuid {
    auth["user"]["id"].as_str().unwrap().parse().unwrap()
}
fn device(auth: &Value) -> Uuid {
    auth["device_id"].as_str().unwrap().parse().unwrap()
}

async fn api_json(
    app: &axum::Router,
    auth: &Value,
    method: &str,
    uri: &str,
    body: Option<Value>,
) -> Value {
    let response = app
        .clone()
        .oneshot(request(method, uri, Some(token(auth)), body))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK, "{method} {uri}");
    json(response).await
}

#[tokio::test]
async fn restored_prelogin_call_receives_later_opaque_deletion_without_exposing_old_metadata() {
    use sha2::{Digest, Sha256};
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let owner = register(&app).await;
    let peer = register(&app).await;
    let other = register(&app).await;
    let old = Uuid::new_v4();
    sqlx::query("INSERT INTO calls(id,caller_user_id,callee_user_id,media_kind,state,end_reason,ended_at) VALUES($1,$2,$3,'audio','ended','hangup',now())")
        .bind(old).bind(user(&owner)).bind(user(&peer)).execute(&state.db).await.unwrap();
    let backup = Uuid::new_v4();
    let bytes = vec![7u8; 64];
    let hash = hex::encode(Sha256::digest(&bytes));
    api_json(&app,&peer,"POST","/history-backup/stage",Some(json!({"expected_revision":0,"backup_id":backup,"format_version":2,"ciphertext":STANDARD.encode(&bytes),"sha256":hash}))).await;
    api_json(
        &app,
        &peer,
        "POST",
        "/history-backup/confirm",
        Some(json!({"expected_revision":1,"backup_id":backup,"sha256":hash})),
    )
    .await;
    assert_eq!(
        app.clone()
            .oneshot(request("POST", "/auth/logout", Some(token(&peer)), None))
            .await
            .unwrap()
            .status(),
        StatusCode::NO_CONTENT
    );
    let response=app.clone().oneshot(request("POST","/auth/login",None,Some(json!({"username":peer["user"]["username"],"password":PASSWORD,"installation_id":Uuid::new_v4(),"device_platform":"ios"})))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let fresh = json(response).await;
    assert_eq!(user(&fresh), user(&peer));
    assert_ne!(token(&fresh), token(&peer));
    let hidden = api_json(&app, &fresh, "GET", "/calls/history", None).await;
    assert!(
        hidden["items"].as_array().unwrap().is_empty(),
        "new password login does not expose old call metadata"
    );
    let old_created: chrono::DateTime<chrono::Utc> =
        sqlx::query_scalar("SELECT created_at FROM calls WHERE id=$1")
            .bind(old)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert!(
        old_created
            < chrono::DateTime::parse_from_rfc3339(hidden["visible_since"].as_str().unwrap())
                .unwrap()
    );
    let downloaded = api_json(
        &app,
        &fresh,
        "GET",
        &format!("/history-backup/objects/{backup}"),
        None,
    )
    .await;
    assert_eq!(downloaded["ciphertext"], STANDARD.encode(&bytes));
    let allowed = api_json(
        &app,
        &fresh,
        "POST",
        "/history-backup/restore-check",
        Some(json!({"message_ids":[],"call_ids":[old]})),
    )
    .await;
    assert_eq!(
        allowed["deleted_call_ids"],
        json!([]),
        "archive's old call is still valid at restore time"
    );
    api_json(
        &app,
        &fresh,
        "POST",
        "/history-backup/restore-complete",
        Some(json!({"backup_id":backup})),
    )
    .await;
    assert!(
        api_json(&app, &fresh, "GET", "/calls/history", None).await["items"]
            .as_array()
            .unwrap()
            .is_empty(),
        "restore does not unlock server plaintext metadata"
    );
    let recent = Uuid::new_v4();
    sqlx::query("INSERT INTO calls(id,caller_user_id,callee_user_id,media_kind,state,end_reason,ended_at) VALUES($1,$2,$3,'audio','ended','hangup',now())")
        .bind(recent).bind(user(&fresh)).bind(user(&other)).execute(&state.db).await.unwrap();
    due(&state, &owner).await;
    assert_eq!(sweep(&state).await, 1);
    let deleted = api_json(
        &app,
        &fresh,
        "GET",
        "/calls/history/deletions?after=0",
        None,
    )
    .await;
    let item = deleted["items"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["call_id"] == old.to_string())
        .expect("deletion after restore reaches the new login's offline reconciliation feed");
    let mut keys = item
        .as_object()
        .unwrap()
        .keys()
        .map(String::as_str)
        .collect::<Vec<_>>();
    keys.sort();
    assert_eq!(
        keys,
        vec!["call_id", "sequence"],
        "only opaque recipient-owned ID and sequence are returned"
    );
    let history = api_json(&app, &fresh, "GET", "/calls/history", None).await;
    assert_eq!(history["items"].as_array().unwrap().len(), 1);
    assert_eq!(history["items"][0]["id"], recent.to_string());
    let filtered = api_json(
        &app,
        &fresh,
        "POST",
        "/history-backup/restore-check",
        Some(json!({"message_ids":[],"call_ids":[old,recent]})),
    )
    .await;
    assert_eq!(
        filtered["deleted_call_ids"],
        json!([old]),
        "a repeated archive restore also removes the deleted call"
    );
    let foreign = api_json(
        &app,
        &other,
        "GET",
        "/calls/history/deletions?after=0",
        None,
    )
    .await;
    assert!(
        foreign["items"].as_array().unwrap().is_empty(),
        "opaque deletions stay recipient scoped"
    );
}

#[tokio::test]
async fn account_deletion_preserves_recipient_call_pagination_anchor() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let owner = register(&app).await;
    let peer = register(&app).await;
    let other = register(&app).await;
    let older = Uuid::new_v4();
    let anchor = Uuid::new_v4();
    sqlx::query("INSERT INTO calls(id,caller_user_id,callee_user_id,media_kind,state,end_reason,created_at,ended_at) VALUES($1,$3,$4,'audio','ended','hangup',clock_timestamp(),now()),($2,$3,$5,'audio','ended','hangup',clock_timestamp()+interval '1 millisecond',now())")
        .bind(older).bind(anchor).bind(user(&peer)).bind(user(&other)).bind(user(&owner)).execute(&state.db).await.unwrap();
    let first = api_json(&app, &peer, "GET", "/calls/history?limit=1", None).await;
    assert_eq!(first["items"][0]["id"], anchor.to_string());
    assert_eq!(first["next_cursor"], anchor.to_string());
    due(&state, &owner).await;
    assert_eq!(sweep(&state).await, 1);
    let next = api_json(
        &app,
        &peer,
        "GET",
        &format!("/calls/history?limit=1&before={anchor}"),
        None,
    )
    .await;
    assert_eq!(next["items"].as_array().unwrap().len(), 1);
    assert_eq!(
        next["items"][0]["id"],
        older.to_string(),
        "deleted cursor must not hide older calls with unrelated peers"
    );
}

#[tokio::test]
async fn owned_feedback_and_performance_are_removed_with_durable_retry_and_no_late_ingest() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let owner = register(&app).await;
    let peer = register(&app).await;
    let sample = || json!({"schema_version":1,"recording_id":Uuid::new_v4(),"platform":"ios","app_version":"0.2.0","build":"20261101-fixture","os_version":"26.0","hardware":"iPhone16,2","dropped":0,"events":[{"sequence":1,"at_ms":0,"kind":"frame_gap","screen":"chat","metrics":{"gap_ms":48.0,"budget_ms":8.3}}]});
    for auth in [&owner, &peer] {
        assert_eq!(
            app.clone()
                .oneshot(request(
                    "POST",
                    "/performance",
                    Some(token(auth)),
                    Some(sample())
                ))
                .await
                .unwrap()
                .status(),
            StatusCode::NO_CONTENT
        );
    }
    sqlx::query("INSERT INTO bug_reports(id,user_id,payload) VALUES($1,$2,$3)")
        .bind(Uuid::new_v4()).bind(user(&owner)).bind(json!({"description":"owned fixture feedback","screenshots":[{"data_base64":"opaque fixture"}]})).execute(&state.db).await.unwrap();
    let key = format!("performance:v1:{}", chrono::Utc::now().format("%Y%m%d%H"));
    let mut redis = state.redis.clone();
    let before: i64 = redis::cmd("TTL")
        .arg(&key)
        .query_async(&mut redis)
        .await
        .unwrap();
    let mut tx = state.db.begin().await.unwrap();
    sqlx::query("SELECT pg_advisory_xact_lock(6840210040)")
        .execute(&mut *tx)
        .await
        .unwrap();
    sqlx::query("SELECT id FROM users WHERE id=$1 FOR UPDATE")
        .bind(user(&owner))
        .execute(&mut *tx)
        .await
        .unwrap();
    let late = {
        let app = app.clone();
        let owner = owner.clone();
        tokio::spawn(async move {
            app.oneshot(request(
                "POST",
                "/performance",
                Some(token(&owner)),
                Some(sample()),
            ))
            .await
            .unwrap()
            .status()
        })
    };
    tokio::time::sleep(Duration::from_millis(50)).await;
    let effects =
        messenger_server::users::deletion::delete_account_data(&state, &mut tx, user(&owner))
            .await
            .unwrap();
    tx.commit().await.unwrap();
    drop(effects); // Simulate loss of post-commit notifications.
    assert_eq!(
        late.await.unwrap(),
        StatusCode::UNAUTHORIZED,
        "already-admitted ingestion rechecks account/session under the deletion lock"
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM bug_reports WHERE user_id=$1")
            .bind(user(&owner))
            .fetch_one(&state.db)
            .await
            .unwrap(),
        0
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT count(*) FROM account_performance_deletions WHERE user_id=$1"
        )
        .bind(user(&owner))
        .fetch_one(&state.db)
        .await
        .unwrap(),
        1,
        "crash before post-commit cleanup cannot discard purge ownership"
    );
    assert_eq!(
        messenger_server::users::deletion::sweep_deleted_performance(&state)
            .await
            .unwrap(),
        1
    );
    let rows: Vec<String> = redis::cmd("LRANGE")
        .arg(&key)
        .arg(0)
        .arg(59)
        .query_async(&mut redis)
        .await
        .unwrap();
    assert!(!rows
        .iter()
        .any(|row| row.contains(&user(&owner).to_string())));
    assert!(
        rows.iter()
            .any(|row| row.contains(&user(&peer).to_string())),
        "unrelated account's samples remain"
    );
    let after: i64 = redis::cmd("TTL")
        .arg(&key)
        .query_async(&mut redis)
        .await
        .unwrap();
    assert!(
        after > 0 && after <= before,
        "atomic filtering does not extend retention"
    );
    assert_eq!(
        messenger_server::users::deletion::sweep_deleted_performance(&state)
            .await
            .unwrap(),
        0,
        "retry is idempotent"
    );
}
async fn snapshot(app: &axum::Router, auth: &Value) -> Value {
    let response = app
        .clone()
        .oneshot(request(
            "GET",
            "/users/me/auto-delete",
            Some(token(auth)),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    json(response).await
}
async fn put(app: &axum::Router, auth: &Value, body: Value) -> axum::response::Response {
    app.clone()
        .oneshot(request(
            "PUT",
            "/users/me/auto-delete",
            Some(token(auth)),
            Some(body),
        ))
        .await
        .unwrap()
}
async fn due(state: &AppState, auth: &Value) {
    sqlx::query("UPDATE users SET auto_delete_inactive_days=1,auto_delete_last_active_at=clock_timestamp()-interval '2 days',last_seen_at=NULL WHERE id=$1")
        .bind(user(auth)).execute(&state.db).await.unwrap();
}
async fn exists(state: &AppState, id: Uuid) -> bool {
    sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM users WHERE id=$1)")
        .bind(id)
        .fetch_one(&state.db)
        .await
        .unwrap()
}
async fn sweep(state: &AppState) -> u64 {
    messenger_server::users::auto_delete::sweep_inactive_accounts(state)
        .await
        .unwrap()
}

#[tokio::test]
async fn opt_in_contract_password_arbitrary_periods_revision_and_defaults() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let auth = register(&app).await;
    let initial = snapshot(&app, &auth).await;
    assert!(initial["inactive_days"].is_null());
    assert!(initial["next_deletion_at"].is_null());
    assert_eq!(initial["revision"], 0);
    sqlx::query("UPDATE users SET auto_delete_last_active_at=now()-interval '10 years',last_seen_at=now()-interval '10 years' WHERE id=$1")
        .bind(user(&auth)).execute(&state.db).await.unwrap();
    assert_eq!(
        sweep(&state).await,
        0,
        "default-off account never becomes due"
    );
    for bad in [json!(0), json!(-1), json!(1096), json!(1.5), json!("7")] {
        assert!(put(
            &app,
            &auth,
            json!({"inactive_days":bad,"expected_revision":0,"password":PASSWORD})
        )
        .await
        .status()
        .is_client_error());
    }
    assert!(
        put(&app, &auth, json!({"expected_revision":0}))
            .await
            .status()
            .is_client_error(),
        "missing period is not an implicit disable"
    );
    assert_eq!(
        put(
            &app,
            &auth,
            json!({"inactive_days":1,"expected_revision":0})
        )
        .await
        .status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        put(
            &app,
            &auth,
            json!({"inactive_days":1,"expected_revision":0,"password":"wrong"})
        )
        .await
        .status(),
        StatusCode::BAD_REQUEST
    );
    let mut revision = 0;
    for days in [1, 3, 7, 365, 1095] {
        let before = chrono::Utc::now();
        let response = put(
            &app,
            &auth,
            json!({"inactive_days":days,"expected_revision":revision,"password":PASSWORD}),
        )
        .await;
        assert_eq!(response.status(), StatusCode::OK);
        let saved = json(response).await;
        revision += 1;
        assert_eq!(saved["inactive_days"], days);
        assert_eq!(saved["revision"], revision);
        let active =
            chrono::DateTime::parse_from_rfc3339(saved["last_active_at"].as_str().unwrap())
                .unwrap();
        let deadline =
            chrono::DateTime::parse_from_rfc3339(saved["next_deletion_at"].as_str().unwrap())
                .unwrap();
        assert!(
            active >= before,
            "old account clock starts at configuration"
        );
        assert_eq!((deadline - active).num_seconds(), days * 86400 + 60);
        assert_eq!(
            json(
                put(
                    &app,
                    &auth,
                    json!({"inactive_days":days,"expected_revision":revision})
                )
                .await
            )
            .await,
            saved,
            "identical update does not reset deadline"
        );
    }
    assert_eq!(
        put(
            &app,
            &auth,
            json!({"inactive_days":null,"expected_revision":0})
        )
        .await
        .status(),
        StatusCode::CONFLICT
    );
    let disabled = json(
        put(
            &app,
            &auth,
            json!({"inactive_days":null,"expected_revision":revision}),
        )
        .await,
    )
    .await;
    assert!(disabled["inactive_days"].is_null());
    assert!(disabled["next_deletion_at"].is_null());
    assert_eq!(sweep(&state).await, 0);
}

#[tokio::test]
async fn foreground_any_device_refresh_background_and_coalescing_boundaries() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let auth = register(&app).await;
    let second=app.clone().oneshot(request("POST","/auth/login",None,Some(json!({"username":auth["user"]["username"],"password":PASSWORD,"installation_id":Uuid::new_v4(),"device_platform":"ios"})))).await.unwrap();
    assert_eq!(second.status(), StatusCode::OK);
    let second = json(second).await;
    due(&state, &auth).await;
    let old = snapshot(&app, &auth).await["last_active_at"].clone();
    assert_eq!(
        app.clone()
            .oneshot(request("GET", "/users/me", Some(token(&auth)), None))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    let refresh = app
        .clone()
        .oneshot(request(
            "POST",
            "/auth/refresh",
            None,
            Some(json!({"refresh_token":auth["refresh_token"],"request_id":Uuid::new_v4()})),
        ))
        .await
        .unwrap();
    assert_eq!(refresh.status(), StatusCode::OK);
    assert_eq!(
        snapshot(&app, &second).await["last_active_at"],
        old,
        "background authentication never counts as foreground"
    );
    assert_eq!(
        app.clone()
            .oneshot(request(
                "POST",
                "/users/me/activity",
                Some(token(&second)),
                None
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::NO_CONTENT
    );
    let fresh = snapshot(&app, &second).await;
    assert_ne!(fresh["last_active_at"], old);
    assert_eq!(
        app.clone()
            .oneshot(request(
                "POST",
                "/users/me/activity",
                Some(token(&second)),
                None
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        snapshot(&app, &second).await["last_active_at"],
        fresh["last_active_at"],
        "activity DB writes coalesce"
    );
    assert_eq!(sweep(&state).await, 0);
    assert_eq!(
        app.clone()
            .oneshot(request(
                "POST",
                "/users/me/activity",
                Some(token(&second)),
                Some(json!({"timestamp":"2099-01-01"}))
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::PAYLOAD_TOO_LARGE
    );
    sqlx::query("UPDATE users SET auto_delete_last_active_at=clock_timestamp()-interval '1 day 30 seconds' WHERE id=$1")
        .bind(user(&auth)).execute(&state.db).await.unwrap();
    assert_eq!(
        sweep(&state).await,
        0,
        "a coalesced final visit cannot make deletion up to 59 seconds early"
    );
    due(&state, &auth).await;
    assert_eq!(
        sweep(&state).await,
        1,
        "background session refresh is not a keepalive"
    );
}

#[tokio::test]
async fn existing_foreground_presence_durable_and_local_protect_legacy_clients() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let persisted = register(&app).await;
    due(&state, &persisted).await;
    sqlx::query("UPDATE users SET last_seen_at=clock_timestamp() WHERE id=$1")
        .bind(user(&persisted))
        .execute(&state.db)
        .await
        .unwrap();
    assert_eq!(sweep(&state).await, 0);
    assert!(exists(&state, user(&persisted)).await);
    let local = register(&app).await;
    due(&state, &local).await;
    let (connection, _rx) = state.ws_registry.register(device(&local)).await;
    state
        .ws_registry
        .foreground(user(&local), device(&local), connection, true)
        .await;
    assert_eq!(sweep(&state).await, 0);
    assert!(exists(&state, user(&local)).await);
    state
        .ws_registry
        .unregister(device(&local), connection)
        .await;
}

#[tokio::test]
async fn settings_cas_session_revocation_read_only_and_activity_lock_order() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let auth = register(&app).await;
    sqlx::query(
        "INSERT INTO admin_user_controls(user_id,read_only_reason) VALUES($1,'isolated fixture')",
    )
    .bind(user(&auth))
    .execute(&state.db)
    .await
    .unwrap();
    let (a, b) = tokio::join!(
        put(
            &app,
            &auth,
            json!({"inactive_days":1,"expected_revision":0,"password":PASSWORD})
        ),
        put(
            &app,
            &auth,
            json!({"inactive_days":7,"expected_revision":0,"password":PASSWORD})
        )
    );
    assert_eq!(
        [a.status(), b.status()]
            .iter()
            .filter(|s| **s == StatusCode::OK)
            .count(),
        1
    );
    assert_eq!(
        [a.status(), b.status()]
            .iter()
            .filter(|s| **s == StatusCode::CONFLICT)
            .count(),
        1
    );
    assert_eq!(
        app.clone()
            .oneshot(request(
                "POST",
                "/users/me/activity",
                Some(token(&auth)),
                None
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::NO_CONTENT
    );
    due(&state, &auth).await;
    let mut lock = state.db.begin().await.unwrap();
    sqlx::query("SELECT id FROM users WHERE id=$1 FOR UPDATE")
        .bind(user(&auth))
        .execute(&mut *lock)
        .await
        .unwrap();
    assert_eq!(
        sweep(&state).await,
        0,
        "worker skips account locked by foreground/settings transaction"
    );
    sqlx::query("UPDATE users SET auto_delete_inactive_days=NULL WHERE id=$1")
        .bind(user(&auth))
        .execute(&mut *lock)
        .await
        .unwrap();
    lock.commit().await.unwrap();
    assert_eq!(sweep(&state).await, 0, "worker rechecks committed disable");
    let current = snapshot(&app, &auth).await;
    let mut lock = state.db.begin().await.unwrap();
    sqlx::query("SELECT id FROM users WHERE id=$1 FOR UPDATE")
        .bind(user(&auth))
        .execute(&mut *lock)
        .await
        .unwrap();
    let task = {
        let app = app.clone();
        let auth = auth.clone();
        tokio::spawn(async move {
            put(&app,&auth,json!({"inactive_days":3,"expected_revision":current["revision"],"password":PASSWORD})).await.status()
        })
    };
    tokio::time::sleep(Duration::from_millis(50)).await;
    sqlx::query("UPDATE sessions SET revoked_at=now() WHERE user_id=$1")
        .bind(user(&auth))
        .execute(&mut *lock)
        .await
        .unwrap();
    lock.commit().await.unwrap();
    assert_eq!(
        task.await.unwrap(),
        StatusCode::UNAUTHORIZED,
        "handler rechecks session after waiting for account lock"
    );
}

#[tokio::test]
async fn automatic_deletion_removes_both_sides_and_retains_durable_object_and_call_cleanup() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let owner = register(&app).await;
    let peer = register(&app).await;
    let chat_response = app
        .clone()
        .oneshot(request(
            "POST",
            "/chats/direct",
            Some(token(&owner)),
            Some(json!({"other_user_id":user(&peer)})),
        ))
        .await
        .unwrap();
    assert_eq!(chat_response.status(), StatusCode::OK);
    let chat: Uuid = json(chat_response).await["chat_id"]
        .as_str()
        .unwrap()
        .parse()
        .unwrap();
    let message_response=app.clone().oneshot(request("POST",&format!("/chats/{chat}/messages"),Some(token(&peer)),Some(json!({"client_message_id":Uuid::new_v4(),"envelopes":[{"recipient_device_id":device(&owner),"message_type":2,"ciphertext":STANDARD.encode(b"opaque fixture message") }]})))).await.unwrap();
    assert_eq!(message_response.status(), StatusCode::OK);
    let message: Uuid = json(message_response).await["id"]
        .as_str()
        .unwrap()
        .parse()
        .unwrap();
    let attachment = format!("attachments/{}", Uuid::new_v4());
    let avatar = format!("avatars/{}", Uuid::new_v4());
    sqlx::query("INSERT INTO attachments(client_attachment_id,uploader_user_id,uploader_device_id,object_key,encrypted_size,status,message_id) VALUES($1,$2,$3,$4,64,'attached',$5)")
        .bind(Uuid::new_v4()).bind(user(&peer)).bind(device(&peer)).bind(&attachment).bind(message).execute(&state.db).await.unwrap();
    sqlx::query("INSERT INTO avatar_uploads(object_key,user_id,expected_bytes,reserved_bytes,legacy_reconciled) VALUES($1,$2,64,64,true)")
        .bind(&avatar).bind(user(&owner)).execute(&state.db).await.unwrap();
    sqlx::query("UPDATE users SET avatar_key=$2 WHERE id=$1")
        .bind(user(&owner))
        .bind(&avatar)
        .execute(&state.db)
        .await
        .unwrap();
    let call = Uuid::new_v4();
    sqlx::query("INSERT INTO calls(id,caller_user_id,callee_user_id,media_kind,state,end_reason,ended_at) VALUES($1,$2,$3,'audio','ended','hangup',now())")
        .bind(call).bind(user(&owner)).bind(user(&peer)).execute(&state.db).await.unwrap();
    sqlx::query("INSERT INTO history_backup_accounts(user_id) VALUES($1),($2)")
        .bind(user(&owner))
        .bind(user(&peer))
        .execute(&state.db)
        .await
        .unwrap();
    for auth in [&owner, &peer] {
        sqlx::query("INSERT INTO history_backup_objects(user_id,backup_id,format_version,ciphertext,sha256,state,verified_at) VALUES($1,$2,2,$3,$4,'active',now())")
            .bind(user(auth)).bind(Uuid::new_v4()).bind(vec![1u8;29]).bind("a".repeat(64)).execute(&state.db).await.unwrap();
    }
    let upload = format!("history-media/{}", Uuid::new_v4());
    let sealed = format!("history-media/{}", Uuid::new_v4());
    for key in [&attachment, &avatar, &upload, &sealed] {
        state
            .s3
            .write_avatar(key, vec![7; 64])
            .await
            .expect("write owned isolated S3 fixture");
    }
    sqlx::query(
        "INSERT INTO history_backup_storage(object_key,owner_id,bytes) VALUES($1,$3,64),($2,$3,64)",
    )
    .bind(&upload)
    .bind(&sealed)
    .bind(user(&owner))
    .execute(&state.db)
    .await
    .unwrap();
    sqlx::query("INSERT INTO history_backup_media(user_id,blob_id,bytes,sha256,upload_key,sealed_key,upload_lease_until) VALUES($1,$2,64,$3,$4,$5,now()+interval '10 minutes')")
        .bind(user(&owner)).bind(Uuid::new_v4()).bind("a".repeat(64)).bind(&upload).bind(&sealed).execute(&state.db).await.unwrap();
    due(&state, &owner).await;
    let (a, b) = tokio::join!(
        messenger_server::users::auto_delete::sweep_inactive_accounts(&state),
        messenger_server::users::auto_delete::sweep_inactive_accounts(&state)
    );
    assert_eq!(
        a.unwrap() + b.unwrap(),
        1,
        "cross-worker account deletion is exactly once"
    );
    assert!(!exists(&state, user(&owner)).await);
    assert!(exists(&state, user(&peer)).await);
    for table in [
        "devices",
        "sessions",
        "history_backup_accounts",
        "history_backup_objects",
        "history_backup_media",
    ] {
        let count: i64 =
            sqlx::query_scalar(&format!("SELECT count(*) FROM {table} WHERE user_id=$1"))
                .bind(user(&owner))
                .fetch_one(&state.db)
                .await
                .unwrap();
        assert_eq!(count, 0, "{table}");
    }
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM messages WHERE chat_id=$1")
            .bind(chat)
            .fetch_one(&state.db)
            .await
            .unwrap(),
        0
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM calls WHERE id=$1")
            .bind(call)
            .fetch_one(&state.db)
            .await
            .unwrap(),
        0
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT count(*) FROM history_backup_objects WHERE user_id=$1"
        )
        .bind(user(&peer))
        .fetch_one(&state.db)
        .await
        .unwrap(),
        1,
        "peer's unrelated opaque archive stays intact"
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT count(*) FROM account_object_deletions WHERE object_key=ANY($1)"
        )
        .bind(vec![attachment.as_str(), avatar.as_str()])
        .fetch_one(&state.db)
        .await
        .unwrap(),
        2
    );
    assert_eq!(sqlx::query_scalar::<_,i64>("SELECT count(*) FROM history_backup_storage WHERE object_key=ANY($1) AND delete_after IS NOT NULL").bind(vec![upload.as_str(),sealed.as_str()]).fetch_one(&state.db).await.unwrap(),2,"own encrypted backup media retains physical GC ownership");
    let known = json(
        app.clone()
            .oneshot(request(
                "GET",
                "/sync?after_sequence=0",
                Some(token(&peer)),
                None,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert!(
        known["deleted_chat_ids"]
            .as_array()
            .unwrap()
            .iter()
            .any(|x| x == &json!(chat)),
        "peer receives durable deleted-chat projection"
    );
    let deleted = json(
        app.clone()
            .oneshot(request(
                "GET",
                "/calls/history/deletions?after=0",
                Some(token(&peer)),
                None,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert!(
        deleted["items"]
            .as_array()
            .unwrap()
            .iter()
            .any(|x| x["call_id"] == call.to_string()),
        "peer call tombstone survives physical call deletion"
    );
    let rejected = app
        .clone()
        .oneshot(request(
            "POST",
            "/users/me/activity",
            Some(token(&owner)),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(rejected.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(json(rejected).await["error"]["code"], "session_revoked");
    assert_eq!(
        messenger_server::users::deletion::sweep_deleted_objects(&state)
            .await
            .unwrap(),
        0,
        "capability grace is retained before physical cleanup"
    );
    assert_eq!(state.s3.head_object(&attachment).await.unwrap(), Some(64));
    sqlx::query("UPDATE account_object_deletions SET delete_after=now()-interval '1 second' WHERE object_key=ANY($1)")
        .bind(vec![attachment.as_str(),avatar.as_str()]).execute(&state.db).await.unwrap();
    let mut failing = state.clone();
    let mut bad_config = state.config.s3.clone();
    bad_config.endpoint_url = "http://127.0.0.1:9".into();
    failing.s3 = std::sync::Arc::new(messenger_server::storage::S3Client::new(bad_config));
    assert_eq!(
        messenger_server::users::deletion::sweep_deleted_objects(&failing)
            .await
            .unwrap(),
        0,
        "failed storage deletion does not discard durable ownership"
    );
    assert_eq!(
        messenger_server::users::deletion::sweep_deleted_objects(&state)
            .await
            .unwrap(),
        2
    );
    assert_eq!(state.s3.head_object(&attachment).await.unwrap(), None);
    assert_eq!(state.s3.head_object(&avatar).await.unwrap(), None);
    sqlx::query("UPDATE history_backup_storage SET delete_after=now()-interval '1 second' WHERE object_key=ANY($1)")
        .bind(vec![upload.as_str(),sealed.as_str()]).execute(&state.db).await.unwrap();
    messenger_server::history_backups::media::cleanup_once(&state)
        .await
        .unwrap();
    assert_eq!(state.s3.head_object(&upload).await.unwrap(), None);
    assert_eq!(state.s3.head_object(&sealed).await.unwrap(), None);
}

#[tokio::test]
async fn fresh_background_media_leases_protect_accounts_but_stale_and_closed_media_do_not() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let caller = register(&app).await;
    let peer = register(&app).await;
    let call = Uuid::new_v4();
    sqlx::query("INSERT INTO calls(id,caller_user_id,callee_user_id,media_kind,state,last_activity_at) VALUES($1,$2,$3,'audio','connected',clock_timestamp())")
        .bind(call).bind(user(&caller)).bind(user(&peer)).execute(&state.db).await.unwrap();
    sqlx::query("INSERT INTO call_participants_active(user_id,call_id) VALUES($1,$3),($2,$3)")
        .bind(user(&caller))
        .bind(user(&peer))
        .bind(call)
        .execute(&state.db)
        .await
        .unwrap();
    due(&state, &caller).await;
    assert_eq!(
        sweep(&state).await,
        0,
        "a fresh connected call is real background use"
    );
    due(&state, &caller).await;
    sqlx::query(
        "UPDATE calls SET last_activity_at=clock_timestamp()-interval '41 seconds' WHERE id=$1",
    )
    .bind(call)
    .execute(&state.db)
    .await
    .unwrap();
    assert_eq!(
        sweep(&state).await,
        1,
        "a stale call row is not indefinite activity"
    );
    let member = register(&app).await;
    let room = Uuid::new_v4();
    let session: Uuid =
        sqlx::query_scalar("SELECT id FROM sessions WHERE device_id=$1 AND revoked_at IS NULL")
            .bind(device(&member))
            .fetch_one(&state.db)
            .await
            .unwrap();
    sqlx::query("INSERT INTO conferences(id,owner_user_id,owner_device_id,owner_session_id,title,max_participants,invite_secret_hash,state,first_joined_at,expires_at) VALUES($1,$2,$3,$4,'Isolated lease fixture',2,$5,'open',now(),now()+interval '1 hour')")
        .bind(room).bind(user(&member)).bind(device(&member)).bind(session).bind(vec![7u8;32]).execute(&state.db).await.unwrap();
    sqlx::query("INSERT INTO conference_members(conference_id,user_id,device_id,session_id,admission_id,last_authorized_at) VALUES($1,$2,$3,$4,$5,clock_timestamp())")
        .bind(room).bind(user(&member)).bind(device(&member)).bind(session).bind(Uuid::new_v4()).execute(&state.db).await.unwrap();
    due(&state, &member).await;
    assert_eq!(
        sweep(&state).await,
        0,
        "a fresh authorized room participant is real use"
    );
    due(&state, &member).await;
    sqlx::query("UPDATE conferences SET state='ended',ended_at=now() WHERE id=$1")
        .bind(room)
        .execute(&state.db)
        .await
        .unwrap();
    assert_eq!(
        sweep(&state).await,
        1,
        "closed room with leftover fresh member rows cannot keep an account alive"
    );
    sqlx::query("DELETE FROM conferences WHERE id=$1")
        .bind(room)
        .execute(&state.db)
        .await
        .unwrap();
}

#[tokio::test]
async fn foreground_websocket_counts_explicit_active_only_and_survives_refresh_rotation() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let auth = register(&app).await;
    due(&state, &auth).await;
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let server = {
        let app = app.clone();
        tokio::spawn(async move {
            axum::serve(
                listener,
                app.into_make_service_with_connect_info::<SocketAddr>(),
            )
            .await
            .unwrap()
        })
    };
    let mut req = format!("ws://{addr}/ws").into_client_request().unwrap();
    req.headers_mut().insert(
        "authorization",
        HeaderValue::from_str(&format!("Bearer {}", token(&auth))).unwrap(),
    );
    let (mut ws, _) = tokio_tungstenite::connect_async(req).await.unwrap();
    let old = snapshot(&app, &auth).await["last_active_at"].clone();
    ws.send(WsMessage::Text(
        json!({"type":"presence.heartbeat","active":false}).to_string(),
    ))
    .await
    .unwrap();
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert_eq!(
        snapshot(&app, &auth).await["last_active_at"],
        old,
        "background socket and heartbeat do not keep account alive"
    );
    let response = app
        .clone()
        .oneshot(request(
            "POST",
            "/auth/refresh",
            None,
            Some(json!({"refresh_token":auth["refresh_token"],"request_id":Uuid::new_v4()})),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let refreshed = json(response).await;
    ws.send(WsMessage::Text(
        json!({"type":"presence.heartbeat","active":true}).to_string(),
    ))
    .await
    .unwrap();
    tokio::time::timeout(Duration::from_secs(3), async {
        loop {
            let value = sqlx::query_scalar::<_, chrono::DateTime<chrono::Utc>>(
                "SELECT auto_delete_last_active_at FROM users WHERE id=$1",
            )
            .bind(user(&auth))
            .fetch_one(&state.db)
            .await
            .unwrap();
            if value > chrono::Utc::now() - chrono::Duration::minutes(1) {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .expect("foreground heartbeat persists despite original socket session refresh rotation");
    assert_eq!(sweep(&state).await, 0);
    assert_eq!(
        app.clone()
            .oneshot(request(
                "GET",
                "/users/me/auto-delete",
                refreshed["access_token"].as_str(),
                None
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    ws.close(None).await.unwrap();
    server.abort();
}

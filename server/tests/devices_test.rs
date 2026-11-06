mod push_common;

use std::net::SocketAddr;
use std::sync::Arc;

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, StatusCode};
use axum::Router;
use http_body_util::BodyExt;
use serde_json::{json, Value};
use tower::ServiceExt;
use uuid::Uuid;

use messenger_server::config::{Config, S3Config};
use messenger_server::push::models::PushSendOutcome;
use messenger_server::state::AppState;
use push_common::{connect_info, register_user_full, spawn_state_with_provider, FakePushProvider};

// ---------------------------------------------------------------------------
// Local plumbing (disabled-push state, like auth_test)
// ---------------------------------------------------------------------------

fn test_s3_config() -> S3Config {
    let endpoint = std::env::var("S3_ENDPOINT_URL").unwrap_or_else(|_| "http://localhost:9000".to_string());
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

    let database_url = std::env::var("TEST_DATABASE_URL")
        .unwrap_or_else(|_| "postgres://messenger:messenger@localhost:5432/messenger_test".to_string());
    let redis_url =
        std::env::var("TEST_REDIS_URL").unwrap_or_else(|_| "redis://localhost:6379/1".to_string());

    let db = sqlx::postgres::PgPoolOptions::new()
        .max_connections(5)
        .connect(&database_url)
        .await
        .expect("connect to test database");
    sqlx::migrate!().run(&db).await.expect("run migrations on test database");

    let redis_client = redis::Client::open(redis_url).expect("build redis client");
    let redis = redis_client.get_connection_manager().await.expect("connect to test redis");

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
    s3.ensure_bucket().await.expect("ensure test attachments bucket exists");

    AppState { resources: Default::default(), db, redis, config, ws_registry: Default::default(), s3, push: messenger_server::push::disabled() }
}

fn authed_request(method: &str, uri: &str, ip: ConnectInfo<SocketAddr>, token: &str, body: Option<Value>) -> Request<Body> {
    let mut builder = Request::builder()
        .method(method)
        .uri(uri)
        .header("authorization", format!("Bearer {token}"))
        .extension(ip);
    if let Some(body) = body {
        builder = builder.header("content-type", "application/json");
        builder.body(Body::from(body.to_string())).unwrap()
    } else {
        builder.body(Body::empty()).unwrap()
    }
}

async fn body_json(response: axum::response::Response) -> Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap()
}

/// Returns (devices_json, ids, current_id) for the given token via GET /devices.
async fn list_devices(app: &Router, ip: ConnectInfo<SocketAddr>, token: &str) -> (Value, Vec<String>, String) {
    let response = app
        .clone()
        .oneshot(authed_request("GET", "/devices", ip, token, None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    let ids: Vec<String> = body["devices"].as_array().unwrap().iter().map(|d| d["id"].as_str().unwrap().to_string()).collect();
    let current = body["devices"].as_array().unwrap().iter().find(|d| d["is_current"] == json!(true)).unwrap()["id"].as_str().unwrap().to_string();
    (body, ids, current)
}

/// Logs an existing user in from a brand-new device. Returns (token, device_id).
async fn login_new_device(app: &Router, ip: ConnectInfo<SocketAddr>, username: &str) -> (String, String) {
    let device_id = Uuid::new_v4();
    let body = json!({
        "username": username,
        "password": "correct-horse-battery",
        "installation_id": device_id,
        "device_platform": "android",
        "device_name": "Second Device",
        "os_version": "Android 14",
        "app_version": "0.1.0",
    });
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/auth/login")
                .header("content-type", "application/json")
                .extension(ip)
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (body["access_token"].as_str().unwrap().to_string(), body["device_id"].as_str().unwrap().to_string())
}

// ---------------------------------------------------------------------------
// L.4 / L.5: list + rename
// ---------------------------------------------------------------------------

#[tokio::test]
async fn list_devices_marks_current_and_orders() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();

    let (token_a, _user_id, device_a, username) = register_user_full(&app, ip, "devlist_").await;
    let (token_b, device_b) = login_new_device(&app, ip, &username).await;

    let (_body, ids, current_b) = list_devices(&app, ip, &token_b).await;
    assert_eq!(ids.len(), 2, "both devices should be listed");
    assert!(ids.contains(&device_a));
    assert_eq!(current_b, device_b);

    let (_body_a, _ids_a, current_a) = list_devices(&app, ip, &token_a).await;
    assert_eq!(current_a, device_a);
}

#[tokio::test]
async fn rename_device_updates_label_and_validates() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();

    let (token_a, _user_id, device_a, _username) = register_user_full(&app, ip, "devren_").await;

    let response = app
        .clone()
        .oneshot(authed_request("PATCH", &format!("/devices/{device_a}"), ip, &token_a, Some(json!({ "display_name": "Bob's phone" }))))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["display_name"], "Bob's phone");

    // Empty name is rejected.
    let response = app
        .clone()
        .oneshot(authed_request("PATCH", &format!("/devices/{device_a}"), ip, &token_a, Some(json!({ "display_name": "  " }))))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);

    // A device id not owned by this user is a 404.
    let response = app
        .clone()
        .oneshot(authed_request("PATCH", &format!("/devices/{}", Uuid::new_v4()), ip, &token_a, Some(json!({ "display_name": "x" }))))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

// ---------------------------------------------------------------------------
// L.8 / L.9: revoke -> session rejected, bundle excluded, push token dead
// ---------------------------------------------------------------------------

#[tokio::test]
async fn revoke_device_blocks_session_and_excludes_bundle() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();

    let (token_a, user_id, device_a, username) = register_user_full(&app, ip, "devrev_").await;
    let (token_b, device_b) = login_new_device(&app, ip, &username).await;

    // Revoke device A from device B.
    let response = app
        .clone()
        .oneshot(authed_request("DELETE", &format!("/devices/{device_a}"), ip, &token_b, None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NO_CONTENT);

    // Device A's session is now dead -> any authed request 401s.
    let response = app
        .clone()
        .oneshot(authed_request("GET", "/devices", ip, &token_a, None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    let body = body_json(response).await;
    assert_eq!(body["error"]["code"], "session_revoked");

    // Only device B remains in the list.
    let (_body, ids, _current) = list_devices(&app, ip, &token_b).await;
    assert_eq!(ids, vec![device_b]);

    // Bundle discovery no longer returns device A.
    let response = app
        .clone()
        .oneshot(authed_request("GET", &format!("/keys/bundles/{user_id}"), ip, &token_b, None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let bundles = body_json(response).await;
    let device_ids: Vec<String> = bundles["devices"].as_array().unwrap().iter().map(|d| d["device_id"].as_str().unwrap().to_string()).collect();
    assert!(!device_ids.contains(&device_a));
}

#[tokio::test]
async fn cannot_revoke_current_device() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();

    let (token_a, _user_id, device_a, _username) = register_user_full(&app, ip, "devself_").await;

    let response = app
        .clone()
        .oneshot(authed_request("DELETE", &format!("/devices/{device_a}"), ip, &token_a, None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::CONFLICT);
}

// ---------------------------------------------------------------------------
// L.17: revoke-others
// ---------------------------------------------------------------------------

#[tokio::test]
async fn revoke_other_devices_revokes_all_but_current() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();

    let (token_a, _user_id, _device_a, username) = register_user_full(&app, ip, "devall_").await;
    let (_token_b, _device_b) = login_new_device(&app, ip, &username).await;
    let (_token_c, _device_c) = login_new_device(&app, ip, &username).await;

    let response = app
        .clone()
        .oneshot(authed_request("POST", "/devices/revoke-others", ip, &token_a, None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["revoked_count"], 2);

    let (_body, ids, current) = list_devices(&app, ip, &token_a).await;
    assert_eq!(ids.len(), 1, "only the current device remains");
    assert_eq!(ids[0], current);
}

// ---------------------------------------------------------------------------
// L.16: security events + pagination
// ---------------------------------------------------------------------------

#[tokio::test]
async fn security_events_record_new_device_and_revoke() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();

    let (token_a, _user_id, device_a, username) = register_user_full(&app, ip, "devev_").await;
    let (_token_b, device_b) = login_new_device(&app, ip, &username).await;

    // Revoke device B from device A -> device_revoked event.
    let response = app
        .clone()
        .oneshot(authed_request("DELETE", &format!("/devices/{device_b}"), ip, &token_a, None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NO_CONTENT);

    let response = app
        .clone()
        .oneshot(authed_request("GET", "/security/events", ip, &token_a, None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    let events = body["events"].as_array().unwrap();
    // Newest first: device_revoked(B), new_device(B), new_device(A).
    assert_eq!(events.len(), 3);
    assert_eq!(events[0]["event_type"], "device_revoked");
    assert_eq!(events[0]["subject_device_id"], device_b);
    assert_eq!(events[1]["event_type"], "new_device");
    assert_eq!(events[1]["subject_device_id"], device_b);
    assert_eq!(events[2]["event_type"], "new_device");
    assert_eq!(events[2]["subject_device_id"], device_a);
}

#[tokio::test]
async fn security_events_paginate_by_cursor() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();

    let (token_a, _user_id, _device_a, username) = register_user_full(&app, ip, "devpage_").await;
    let (_b, _db) = login_new_device(&app, ip, &username).await;
    let (_c, _dc) = login_new_device(&app, ip, &username).await;

    let response = app
        .clone()
        .oneshot(authed_request("GET", "/security/events?limit=2", ip, &token_a, None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let first_page = body_json(response).await;
    assert_eq!(first_page["events"].as_array().unwrap().len(), 2);
    let cursor = first_page["next_cursor"].as_i64().unwrap();

    let response = app
        .clone()
        .oneshot(authed_request("GET", &format!("/security/events?limit=2&cursor={cursor}"), ip, &token_a, None))
        .await
        .unwrap();
    let second_page = body_json(response).await;
    assert_eq!(second_page["events"].as_array().unwrap().len(), 1, "third (oldest) event remains");
    assert!(second_page["next_cursor"].is_null());
}

// ---------------------------------------------------------------------------
// L.10: revoke pushes session.revoked over the live WS
// ---------------------------------------------------------------------------

#[tokio::test]
async fn revoke_pushes_session_revoked_over_ws() {
    let state = spawn_state().await;
    let registry = state.ws_registry.clone();
    let app = messenger_server::app(state);
    let ip = connect_info();

    let (token_a, _user_id, _device_a, username) = register_user_full(&app, ip, "devws_").await;
    let (_token_b, device_b) = login_new_device(&app, ip, &username).await;

    // Simulate a live connection for device B.
    let device_b_uuid = Uuid::parse_str(&device_b).unwrap();
    let (_conn_id, mut rx) = registry.register(device_b_uuid).await;

    let response = app
        .clone()
        .oneshot(authed_request("DELETE", &format!("/devices/{device_b}"), ip, &token_a, None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NO_CONTENT);

    let frame = rx.recv().await.expect("session.revoked frame should arrive");
    let value: Value = serde_json::from_str(&frame).unwrap();
    assert_eq!(value["type"], "session.revoked");
}

// ---------------------------------------------------------------------------
// L.18: new-device login wakes the other device via push outbox
// ---------------------------------------------------------------------------

#[tokio::test]
async fn new_device_login_enqueues_security_alert_to_other_devices() {
    let provider = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let state = spawn_state_with_provider(provider.clone()).await;
    let db = state.db.clone();
    let app = messenger_server::app(state);
    let ip = connect_info();

    // This is the only push-enabled test in this binary, but push_outbox rows
    // survive across runs (the shared test DB is never recreated) - clear any
    // leftovers from previous runs so the "empty" assertion is trustworthy.
    sqlx::query("DELETE FROM push_outbox").execute(&db).await.unwrap();

    let (token_a, _user_id, device_a, username) = register_user_full(&app, ip, "devpush_").await;
    push_common::register_push_token(&app, ip, &token_a, "fcm-token-a").await;

    let rows = push_common::outbox_rows(&db).await;
    assert!(rows.is_empty(), "registering the first device must not self-alert");

    let (_token_b, device_b) = login_new_device(&app, ip, &username).await;

    let rows = push_common::outbox_rows(&db).await;
    assert_eq!(rows.len(), 1);
    let (target_device, event_type, payload) = &rows[0];
    assert_eq!(target_device, &Uuid::parse_str(&device_a).unwrap());
    assert_eq!(event_type, "security_event");
    assert_eq!(payload["data"]["event_type"], "new_device");
    assert_eq!(payload["data"]["subject_device_id"], device_b);
}

// ---------------------------------------------------------------------------
// L.15: revoking the accepted device ends a connected/connecting call
// ---------------------------------------------------------------------------

#[tokio::test]
async fn revoking_accepted_device_ends_call_with_device_revoked() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();

    let (token_caller, _caller_id, _device_caller, _username_caller) = register_user_full(&app, ip, "callrev1_").await;
    let (token_d1, callee_id, _device_d1, username_callee) = register_user_full(&app, ip, "callrev2_").await;
    let (token_d2, device_d2) = login_new_device(&app, ip, &username_callee).await;

    // Direct chat (calls require it).
    let response = app
        .clone()
        .oneshot(authed_request("POST", "/chats/direct", ip, &token_caller, Some(json!({ "other_user_id": callee_id }))))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    // Create + accept the call with device D2.
    let response = app
        .clone()
        .oneshot(authed_request("POST", "/calls", ip, &token_caller, Some(json!({ "callee_user_id": callee_id, "media_kind": "audio" }))))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let call_id = body_json(response).await["id"].as_str().unwrap().to_string();

    let response = app
        .clone()
        .oneshot(authed_request("POST", &format!("/calls/{call_id}/accept"), ip, &token_d2, None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    let response = app
        .clone()
        .oneshot(authed_request("DELETE", &format!("/devices/{device_d2}"), ip, &token_d1, None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NO_CONTENT);

    // The call must now be ended with reason device_revoked.
    let response = app
        .clone()
        .oneshot(authed_request("GET", &format!("/calls/{call_id}"), ip, &token_caller, None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let call = body_json(response).await;
    assert_eq!(call["state"], "ended");
    assert_eq!(call["end_reason"], "device_revoked");
}

// P0: a revoked device must stay dead for its old session/refresh, but the
// physical installation may log in again through VALID authentication and
// reactivate the SAME account-device row (no silently "new" duplicate device).
#[tokio::test]
async fn revoked_device_relogin_with_valid_password_reactivates_same_row() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let installation = Uuid::new_v4();
    let username = format!("revrelog_{}", Uuid::new_v4().simple().to_string().chars().take(8).collect::<String>());

    let reg = Request::builder()
        .method("POST")
        .uri("/auth/register")
        .header("content-type", "application/json")
        .extension(ip)
        .body(Body::from(json!({
            "username": username,
            "password": "correct-horse-battery",
            "display_name": "A",
            "installation_id": installation,
            "device_platform": "android",
            "device_name": "A Device",
        }).to_string()))
        .unwrap();
    let resp = app.clone().oneshot(reg).await.unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
    let body = body_json(resp).await;
    let token_a = body["access_token"].as_str().unwrap().to_string();
    let device_a = body["device_id"].as_str().unwrap().to_string();

    // A second device to revoke A from.
    let (token_b, _device_b) = login_new_device(&app, ip, &username).await;

    // Revoke device A.
    let resp = app
        .clone()
        .oneshot(authed_request("DELETE", &format!("/devices/{device_a}"), ip, &token_b, None))
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::NO_CONTENT);

    // A's old session is dead (and its refresh token family was revoked too).
    let resp = app
        .clone()
        .oneshot(authed_request("GET", "/devices", ip, &token_a, None))
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::UNAUTHORIZED);

    // Valid re-login with the SAME installation_id reactivates the SAME row.
    let login = Request::builder()
        .method("POST")
        .uri("/auth/login")
        .header("content-type", "application/json")
        .extension(ip)
        .body(Body::from(json!({
            "username": username,
            "password": "correct-horse-battery",
            "installation_id": installation,
            "device_platform": "android",
            "device_name": "A Device",
        }).to_string()))
        .unwrap();
    let resp = app.oneshot(login).await.unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
    let body = body_json(resp).await;
    assert_eq!(body["device_id"].as_str().unwrap(), device_a, "relogin must reuse the same account-device row, not create a duplicate");
}

#[tokio::test]
async fn revoked_call_cleanup_rolls_back_on_slot_failure() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();

    let (token_caller, _caller_id, _device_caller, _username_caller) = register_user_full(&app, ip, "callrev1_").await;
    let (token_d1, callee_id, _device_d1, username_callee) = register_user_full(&app, ip, "callrev2_").await;
    let (token_d2, device_d2) = login_new_device(&app, ip, &username_callee).await;

    // Direct chat (calls require it).
    let response = app
        .clone()
        .oneshot(authed_request("POST", "/chats/direct", ip, &token_caller, Some(json!({ "other_user_id": callee_id }))))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    // Create + accept the call with device D2.
    let response = app
        .clone()
        .oneshot(authed_request("POST", "/calls", ip, &token_caller, Some(json!({ "callee_user_id": callee_id, "media_kind": "audio" }))))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let call_id = body_json(response).await["id"].as_str().unwrap().to_string();

    let response = app
        .clone()
        .oneshot(authed_request("POST", &format!("/calls/{call_id}/accept"), ip, &token_d2, None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    let hook = format!("fail_revoke_{}", Uuid::new_v4().simple());
    sqlx::query(&format!("CREATE FUNCTION {hook}() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test slot failure'; END $$"))
        .execute(&state.db).await.unwrap();
    sqlx::query(&format!("CREATE TRIGGER {hook} BEFORE DELETE ON call_participants_active FOR EACH ROW WHEN (OLD.call_id = '{call_id}'::uuid) EXECUTE FUNCTION {hook}()"))
        .execute(&state.db).await.unwrap();
    let response = app
        .clone()
        .oneshot(authed_request("DELETE", &format!("/devices/{device_d2}"), ip, &token_d1, None))
        .await
        .unwrap();
    sqlx::query(&format!("DROP TRIGGER {hook} ON call_participants_active")).execute(&state.db).await.unwrap();
    sqlx::query(&format!("DROP FUNCTION {hook}()")).execute(&state.db).await.unwrap();
    assert_eq!(response.status(), StatusCode::NO_CONTENT);

    // Slot failure must roll back the terminal call update as well.
    let response = app
        .clone()
        .oneshot(authed_request("GET", &format!("/calls/{call_id}"), ip, &token_caller, None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let call = body_json(response).await;
    assert_eq!(call["state"], "accepted");
    assert!(call["end_reason"].is_null());
    let id = Uuid::parse_str(&call_id).unwrap();
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM call_participants_active WHERE call_id=$1")
        .bind(id).fetch_one(&state.db).await.unwrap();
    assert_eq!(count, 2);
    let response = app.clone().oneshot(authed_request("POST", &format!("/calls/{call_id}/hangup"), ip, &token_caller, None)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM call_participants_active WHERE call_id=$1")
        .bind(id).fetch_one(&state.db).await.unwrap();
    assert_eq!(count, 0);
}


#[tokio::test]
async fn read_only_allows_both_revocation_routes_but_rejects_rename() {
    let state=spawn_state().await;
    let app=messenger_server::app(state.clone());
    let ip=connect_info();
    let (token,user,device,username)=register_user_full(&app,ip,"readonly_revoke_").await;
    let (_,other)=login_new_device(&app,ip,&username).await;
    let (_,third)=login_new_device(&app,ip,&username).await;
    sqlx::query("INSERT INTO admin_user_controls(user_id,read_only_reason,updated_by) VALUES($1,'regression',$1)")
        .bind(uuid::Uuid::parse_str(&user).unwrap()).execute(&state.db).await.unwrap();
    let result=app.clone().oneshot(authed_request("DELETE",&format!("/devices/{other}"),ip,&token,None)).await.unwrap();
    assert_eq!(result.status(),StatusCode::NO_CONTENT);
    let result=app.clone().oneshot(authed_request("POST","/devices/revoke-others",ip,&token,None)).await.unwrap();
    assert_eq!(result.status(),StatusCode::OK);
    assert_eq!(body_json(result).await["revoked_count"],1);
    let (_,ids,current)=list_devices(&app,ip,&token).await;
    assert_eq!(ids,vec![device.clone()]);
    assert_eq!(current,device);
    assert!(!ids.contains(&third));
    let result=app.oneshot(authed_request("PATCH",&format!("/devices/{device}"),ip,&token,
        Some(json!({"display_name":"Must not change"})))).await.unwrap();
    assert_eq!(result.status(),StatusCode::FORBIDDEN);
}

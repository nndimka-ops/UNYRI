use std::net::SocketAddr;

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use serde_json::{json, Value};
use tower::ServiceExt;
use uuid::Uuid;

use messenger_server::auth::jwt;
use messenger_server::config::{Config, S3Config};
use messenger_server::state::AppState;

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

fn unique_username(prefix: &str) -> String {
    format!("{prefix}{}", Uuid::new_v4().simple())
        .chars()
        .take(32)
        .collect()
}

/// Each test gets its own fake client IP so the shared Redis-backed rate
/// limiter (keyed by IP) can't cause cross-test interference when the suite
/// runs in parallel.
fn connect_info() -> ConnectInfo<SocketAddr> {
    let octets: [u8; 3] = std::array::from_fn(|_| rand::random());
    ConnectInfo(SocketAddr::from(([10, octets[0], octets[1], octets[2]], 12345)))
}

fn json_request(uri: &str, ip: ConnectInfo<SocketAddr>, body: Value) -> Request<Body> {
    Request::builder()
        .method("POST")
        .uri(uri)
        .header("content-type", "application/json")
        .extension(ip)
        .body(Body::from(body.to_string()))
        .unwrap()
}

fn auth_request(uri: &str, ip: ConnectInfo<SocketAddr>, token: &str) -> Request<Body> {
    Request::builder()
        .method("POST")
        .uri(uri)
        .header("authorization", format!("Bearer {token}"))
        .extension(ip)
        .body(Body::empty())
        .unwrap()
}

async fn body_json(response: axum::response::Response) -> Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap()
}

fn register_body(username: &str, password: &str, installation_id: Uuid) -> Value {
    json!({
        "username": username,
        "password": password,
        "display_name": "Test User",
        "installation_id": installation_id,
        "device_platform": "android",
        "device_name": "Test Device",
    })
}

fn login_body(username: &str, password: &str, installation_id: Uuid) -> Value {
    json!({
        "username": username,
        "password": password,
        "installation_id": installation_id,
        "device_platform": "android",
        "device_name": "Test Device",
    })
}

#[tokio::test]
async fn refresh_response_loss_retries_same_rotation_but_different_attempt_revokes() {
    let app = messenger_server::app(spawn_state().await);
    let ip = connect_info();
    let registered = app.clone().oneshot(json_request("/auth/register", ip,
        register_body(&unique_username("retry_"), "correct-horse", Uuid::new_v4()))).await.unwrap();
    let old = body_json(registered).await["refresh_token"].as_str().unwrap().to_owned();
    let request = json!({"refresh_token": old, "request_id": Uuid::new_v4()});
    let first = app.clone().oneshot(json_request("/auth/refresh", ip, request.clone())).await.unwrap();
    assert_eq!(first.status(), StatusCode::OK);
    let first = body_json(first).await;
    let retried = app.clone().oneshot(json_request("/auth/refresh", ip, request)).await.unwrap();
    assert_eq!(retried.status(), StatusCode::OK);
    assert_eq!(body_json(retried).await["refresh_token"], first["refresh_token"]);
    let stolen = app.clone().oneshot(json_request("/auth/refresh", ip,
        json!({"refresh_token": old, "request_id": Uuid::new_v4()}))).await.unwrap();
    assert_eq!(stolen.status(), StatusCode::UNAUTHORIZED);
    let revoked = app.oneshot(json_request("/auth/refresh", ip,
        json!({"refresh_token": first["refresh_token"], "request_id": Uuid::new_v4()}))).await.unwrap();
    assert_eq!(revoked.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn concurrent_same_refresh_attempt_returns_one_replacement() {
    let app = messenger_server::app(spawn_state().await);
    let ip = connect_info();
    let registered = app.clone().oneshot(json_request("/auth/register", ip,
        register_body(&unique_username("retry_parallel_"), "correct-horse", Uuid::new_v4()))).await.unwrap();
    let old = body_json(registered).await["refresh_token"].as_str().unwrap().to_owned();
    let request = json!({"refresh_token": old, "request_id": Uuid::new_v4()});
    let (a, b) = tokio::join!(
        app.clone().oneshot(json_request("/auth/refresh", ip, request.clone())),
        app.clone().oneshot(json_request("/auth/refresh", ip, request.clone())));
    let (a, b) = (a.unwrap(), b.unwrap());
    assert_eq!(a.status(), StatusCode::OK);
    assert_eq!(b.status(), StatusCode::OK);
    let token = body_json(a).await["refresh_token"].clone();
    assert_eq!(body_json(b).await["refresh_token"], token);
    let consumed = app.clone().oneshot(json_request("/auth/refresh", ip,
        json!({"refresh_token": token, "request_id": Uuid::new_v4()}))).await.unwrap();
    assert_eq!(consumed.status(), StatusCode::OK);
    let latest = body_json(consumed).await["refresh_token"].clone();
    let stale = app.clone().oneshot(json_request("/auth/refresh", ip, request)).await.unwrap();
    assert_eq!(stale.status(), StatusCode::UNAUTHORIZED);
    let still_valid = app.oneshot(json_request("/auth/refresh", ip,
        json!({"refresh_token": latest, "request_id": Uuid::new_v4()}))).await.unwrap();
    assert_eq!(still_valid.status(), StatusCode::OK);
}

#[tokio::test]
async fn refresh_retry_cannot_restore_logged_out_replacement() {
    let app = messenger_server::app(spawn_state().await);
    let ip = connect_info();
    let registered = app.clone().oneshot(json_request("/auth/register", ip,
        register_body(&unique_username("retry_logout_"), "correct-horse", Uuid::new_v4()))).await.unwrap();
    let old = body_json(registered).await["refresh_token"].as_str().unwrap().to_owned();
    let request = json!({"refresh_token": old, "request_id": Uuid::new_v4()});
    let rotated = app.clone().oneshot(json_request("/auth/refresh", ip, request.clone())).await.unwrap();
    assert_eq!(rotated.status(), StatusCode::OK);
    let rotated = body_json(rotated).await;
    let logout = app.clone().oneshot(auth_request("/auth/logout", ip, rotated["access_token"].as_str().unwrap())).await.unwrap();
    assert_eq!(logout.status(), StatusCode::NO_CONTENT);
    let replayed = app.oneshot(json_request("/auth/refresh", ip, request)).await.unwrap();
    assert_eq!(replayed.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn register_success() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let username = unique_username("reg_ok_");
    let device_id = Uuid::new_v4();
    let ip = connect_info();

    let response = app
        .oneshot(json_request("/auth/register", ip, register_body(&username, "correct-horse", device_id)))
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["user"]["username"], username);
    assert!(body["access_token"].as_str().unwrap().len() > 10);
    assert!(body["refresh_token"].as_str().unwrap().contains('.'));
}

#[tokio::test]
async fn register_duplicate_username() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let username = unique_username("reg_dup_");
    let ip = connect_info();

    let first = app
        .clone()
        .oneshot(json_request("/auth/register", ip, register_body(&username, "correct-horse", Uuid::new_v4())))
        .await
        .unwrap();
    assert_eq!(first.status(), StatusCode::OK);

    let second = app
        .oneshot(json_request("/auth/register", ip, register_body(&username, "another-password", Uuid::new_v4())))
        .await
        .unwrap();
    assert_eq!(second.status(), StatusCode::CONFLICT);
    let body = body_json(second).await;
    assert_eq!(body["error"]["code"], "username_taken");
}

#[tokio::test]
async fn register_invalid_password() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let username = unique_username("reg_badpw_");
    let ip = connect_info();

    let response = app
        .oneshot(json_request("/auth/register", ip, register_body(&username, "short", Uuid::new_v4())))
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let body = body_json(response).await;
    assert_eq!(body["error"]["code"], "validation_error");
}

#[tokio::test]
async fn login_success() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let username = unique_username("login_ok_");
    let reg_device = Uuid::new_v4();
    let ip = connect_info();

    app.clone()
        .oneshot(json_request("/auth/register", ip, register_body(&username, "correct-horse", reg_device)))
        .await
        .unwrap();

    let login_device = Uuid::new_v4();
    let response = app
        .oneshot(json_request("/auth/login", ip, login_body(&username, "correct-horse", login_device)))
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    // The returned device_id is the ACCOUNT-SCOPED server id, never the
    // client's installation id.
    let returned_device = Uuid::parse_str(body["device_id"].as_str().unwrap()).unwrap();
    assert_ne!(returned_device, login_device);
    assert_eq!(body["history_before_sequence"].as_i64(), Some(0));
}

#[tokio::test]
async fn login_wrong_password() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let username = unique_username("login_bad_");
    let ip = connect_info();

    app.clone()
        .oneshot(json_request("/auth/register", ip, register_body(&username, "correct-horse", Uuid::new_v4())))
        .await
        .unwrap();

    let response = app
        .oneshot(json_request("/auth/login", ip, login_body(&username, "wrong-password", Uuid::new_v4())))
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    let body = body_json(response).await;
    assert_eq!(body["error"]["code"], "invalid_credentials");
}

#[tokio::test]
async fn history_context_keeps_original_login_boundary_after_refresh() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let username = unique_username("history_");
    let registered = body_json(app.clone().oneshot(json_request("/auth/register", ip,
        register_body(&username, "correct-horse", Uuid::new_v4()))).await.unwrap()).await;
    let owner = Uuid::parse_str(registered["user"]["id"].as_str().unwrap()).unwrap();
    let device = Uuid::parse_str(registered["device_id"].as_str().unwrap()).unwrap();
    let chat: Uuid = sqlx::query_scalar("INSERT INTO chats(type, owner_user_id) VALUES ('saved', $1) \
        ON CONFLICT (owner_user_id) WHERE type = 'saved' DO UPDATE SET owner_user_id = EXCLUDED.owner_user_id RETURNING id")
        .bind(owner).fetch_one(&state.db).await.unwrap();
    sqlx::query("INSERT INTO chat_members(chat_id,user_id) VALUES($1,$2) ON CONFLICT DO NOTHING")
        .bind(chat).bind(owner).execute(&state.db).await.unwrap();
    let old_sequence: i64 = sqlx::query_scalar("INSERT INTO messages(chat_id,sender_id,sender_device_id,client_message_id,ciphertext) \
        VALUES($1,$2,$3,$4,$5) RETURNING sequence")
        .bind(chat).bind(owner).bind(device).bind(Uuid::new_v4()).bind(vec![1u8])
        .fetch_one(&state.db).await.unwrap();
    let login = body_json(app.clone().oneshot(json_request("/auth/login", ip,
        login_body(&username, "correct-horse", Uuid::new_v4()))).await.unwrap()).await;
    sqlx::query("INSERT INTO messages(chat_id,sender_id,sender_device_id,client_message_id,ciphertext) VALUES($1,$2,$3,$4,$5)")
        .bind(chat).bind(owner).bind(device).bind(Uuid::new_v4()).bind(vec![2u8])
        .execute(&state.db).await.unwrap();
    let mut req = auth_request("/auth/history-context", ip, login["access_token"].as_str().unwrap());
    *req.method_mut() = axum::http::Method::GET;
    let response = app.clone().oneshot(req).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let before = body_json(response).await;
    assert_eq!(before["before_sequence"].as_i64(), Some(old_sequence));
    let rotated = body_json(app.clone().oneshot(json_request("/auth/refresh", ip,
        json!({"refresh_token":login["refresh_token"],"request_id":Uuid::new_v4()}))).await.unwrap()).await;
    let mut req = auth_request("/auth/history-context", ip, rotated["access_token"].as_str().unwrap());
    *req.method_mut() = axum::http::Method::GET;
    assert_eq!(body_json(app.clone().oneshot(req).await.unwrap()).await, before);
    let other = body_json(app.clone().oneshot(json_request("/auth/register", ip,
        register_body(&unique_username("history_other_"), "correct-horse", Uuid::new_v4()))).await.unwrap()).await;
    let mut req = auth_request("/auth/history-context", ip, other["access_token"].as_str().unwrap());
    *req.method_mut() = axum::http::Method::GET;
    let other_context = body_json(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(other_context["before_sequence"].as_i64(), Some(0));
    let response = app.oneshot(Request::builder().uri("/auth/history-context").body(Body::empty()).unwrap()).await.unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn refresh_success() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let username = unique_username("refresh_ok_");
    let ip = connect_info();

    let register_response = app
        .clone()
        .oneshot(json_request("/auth/register", ip, register_body(&username, "correct-horse", Uuid::new_v4())))
        .await
        .unwrap();
    let register_body = body_json(register_response).await;
    let refresh_token = register_body["refresh_token"].as_str().unwrap().to_string();
    let old_access = register_body["access_token"].as_str().unwrap().to_string();

    let refresh_response = app
        .oneshot(json_request("/auth/refresh", ip, json!({ "refresh_token": refresh_token })))
        .await
        .unwrap();

    assert_eq!(refresh_response.status(), StatusCode::OK);
    let body = body_json(refresh_response).await;
    assert_ne!(body["access_token"].as_str().unwrap(), old_access);
    assert_ne!(body["refresh_token"].as_str().unwrap(), refresh_token);
}

#[tokio::test]
async fn refresh_rotation_invalidates_old_token() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let username = unique_username("refresh_rot_");
    let ip = connect_info();

    let register_response = app
        .clone()
        .oneshot(json_request("/auth/register", ip, register_body(&username, "correct-horse", Uuid::new_v4())))
        .await
        .unwrap();
    let refresh_token_a = body_json(register_response).await["refresh_token"].as_str().unwrap().to_string();

    let first_refresh = app
        .clone()
        .oneshot(json_request("/auth/refresh", ip, json!({ "refresh_token": refresh_token_a })))
        .await
        .unwrap();
    assert_eq!(first_refresh.status(), StatusCode::OK);

    // Token A was already rotated away; reusing it must now fail.
    let reuse_attempt = app
        .oneshot(json_request("/auth/refresh", ip, json!({ "refresh_token": refresh_token_a })))
        .await
        .unwrap();
    assert_eq!(reuse_attempt.status(), StatusCode::UNAUTHORIZED);
    let body = body_json(reuse_attempt).await;
    assert_eq!(body["error"]["code"], "invalid_refresh_token");
}

#[tokio::test]
async fn refresh_reuse_revokes_whole_family() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let username = unique_username("refresh_reuse_");
    let ip = connect_info();

    let register_response = app
        .clone()
        .oneshot(json_request("/auth/register", ip, register_body(&username, "correct-horse", Uuid::new_v4())))
        .await
        .unwrap();
    let token_a = body_json(register_response).await["refresh_token"].as_str().unwrap().to_string();

    let refresh_response = app
        .clone()
        .oneshot(json_request("/auth/refresh", ip, json!({ "refresh_token": token_a })))
        .await
        .unwrap();
    let token_b = body_json(refresh_response).await["refresh_token"].as_str().unwrap().to_string();

    // Replay the rotated-away token A: this must revoke the whole family,
    // including the currently-legitimate token B.
    app.clone()
        .oneshot(json_request("/auth/refresh", ip, json!({ "refresh_token": token_a })))
        .await
        .unwrap();

    let response_with_b = app
        .oneshot(json_request("/auth/refresh", ip, json!({ "refresh_token": token_b })))
        .await
        .unwrap();
    assert_eq!(response_with_b.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn logout_success() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let username = unique_username("logout_ok_");
    let ip = connect_info();

    let register_response = app
        .clone()
        .oneshot(json_request("/auth/register", ip, register_body(&username, "correct-horse", Uuid::new_v4())))
        .await
        .unwrap();
    let access_token = body_json(register_response).await["access_token"].as_str().unwrap().to_string();

    let response = app.oneshot(auth_request("/auth/logout", ip, &access_token)).await.unwrap();
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn revoked_session_is_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let username = unique_username("revoked_");
    let ip = connect_info();

    let register_response = app
        .clone()
        .oneshot(json_request("/auth/register", ip, register_body(&username, "correct-horse", Uuid::new_v4())))
        .await
        .unwrap();
    let access_token = body_json(register_response).await["access_token"].as_str().unwrap().to_string();

    app.clone().oneshot(auth_request("/auth/logout", ip, &access_token)).await.unwrap();

    // The same access token's session is now revoked; using it again must fail.
    let second_attempt = app.oneshot(auth_request("/auth/logout", ip, &access_token)).await.unwrap();
    assert_eq!(second_attempt.status(), StatusCode::UNAUTHORIZED);
    let body = body_json(second_attempt).await;
    assert_eq!(body["error"]["code"], "session_revoked");
}

#[tokio::test]
async fn expired_access_token_is_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let username = unique_username("expired_");
    let ip = connect_info();

    let register_response = app
        .clone()
        .oneshot(json_request("/auth/register", ip, register_body(&username, "correct-horse", Uuid::new_v4())))
        .await
        .unwrap();
    let body = body_json(register_response).await;
    let user_id = Uuid::parse_str(body["user"]["id"].as_str().unwrap()).unwrap();
    let device_id = Uuid::parse_str(body["device_id"].as_str().unwrap()).unwrap();

    // Mint a token that already expired, bypassing the normal TTL, to
    // exercise the expiry check without waiting in real time.
    let mut expired_config = state.config.clone();
    expired_config.access_token_ttl_seconds = -60;
    let expired = jwt::issue_access_token(&expired_config, user_id, device_id, Uuid::new_v4()).unwrap();

    let response = app.oneshot(auth_request("/auth/logout", ip, &expired.token)).await.unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn two_devices_and_repeat_login_have_independent_sessions() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let username = unique_username("multidev_");
    let ip = connect_info();

    let device_a = Uuid::new_v4();
    let register_response = app
        .clone()
        .oneshot(json_request("/auth/register", ip, register_body(&username, "correct-horse", device_a)))
        .await
        .unwrap();
    let access_a = body_json(register_response).await["access_token"].as_str().unwrap().to_string();

    // Login from a second device for the same account.
    let device_b = Uuid::new_v4();
    let login_response = app
        .clone()
        .oneshot(json_request("/auth/login", ip, login_body(&username, "correct-horse", device_b)))
        .await
        .unwrap();
    assert_eq!(login_response.status(), StatusCode::OK);
    let access_b = body_json(login_response).await["access_token"].as_str().unwrap().to_string();

    assert_ne!(access_a, access_b);

    // Logging out device A must not affect device B's independent session.
    app.clone().oneshot(auth_request("/auth/logout", ip, &access_a)).await.unwrap();
    let still_valid = app.oneshot(auth_request("/auth/logout", ip, &access_b)).await.unwrap();
    assert_eq!(still_valid.status(), StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn one_installation_can_be_used_by_multiple_accounts() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let installation = Uuid::new_v4();

    // Register A on installation X.
    let username_a = unique_username("sw_a_");
    let resp_a = app
        .clone()
        .oneshot(json_request("/auth/register", ip, register_body(&username_a, "correct-horse", installation)))
        .await
        .unwrap();
    assert_eq!(resp_a.status(), StatusCode::OK);
    let body_a = body_json(resp_a).await;
    let device_a = Uuid::parse_str(body_a["device_id"].as_str().unwrap()).unwrap();
    let access_a = body_a["access_token"].as_str().unwrap().to_string();

    // Logout A.
    app.clone().oneshot(auth_request("/auth/logout", ip, &access_a)).await.unwrap();

    // Register B on the SAME installation X - the P0 bug previously rejected
    // this with "Device ID is already associated with another account.".
    let username_b = unique_username("sw_b_");
    let resp_b = app
        .clone()
        .oneshot(json_request("/auth/register", ip, register_body(&username_b, "correct-horse", installation)))
        .await
        .unwrap();
    assert_eq!(resp_b.status(), StatusCode::OK, "registration on a previously-used installation must succeed");
    let device_b = Uuid::parse_str(body_json(resp_b).await["device_id"].as_str().unwrap()).unwrap();

    // A and B get DIFFERENT account-scoped device ids.
    assert_ne!(device_a, device_b);

    // Login A again on X -> success, same account-scoped device id (reuse).
    let resp_a2 = app
        .clone()
        .oneshot(json_request("/auth/login", ip, login_body(&username_a, "correct-horse", installation)))
        .await
        .unwrap();
    assert_eq!(resp_a2.status(), StatusCode::OK);
    let device_a2 = Uuid::parse_str(body_json(resp_a2).await["device_id"].as_str().unwrap()).unwrap();
    assert_eq!(device_a, device_a2, "same-account relogin must reuse the account-scoped device");

    // Login B again on X -> success.
    let resp_b2 = app
        .oneshot(json_request("/auth/login", ip, login_body(&username_b, "correct-horse", installation)))
        .await
        .unwrap();
    assert_eq!(resp_b2.status(), StatusCode::OK);
}

#[tokio::test]
async fn same_account_relogin_reuses_the_device_row() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let installation = Uuid::new_v4();
    let username = unique_username("relogin_");

    let resp = app
        .clone()
        .oneshot(json_request("/auth/register", ip, register_body(&username, "correct-horse", installation)))
        .await
        .unwrap();
    let body = body_json(resp).await;
    let device_id = Uuid::parse_str(body["device_id"].as_str().unwrap()).unwrap();
    let user_id = Uuid::parse_str(body["user"]["id"].as_str().unwrap()).unwrap();

    // Relogin the same account + installation.
    let resp2 = app
        .clone()
        .oneshot(json_request("/auth/login", ip, login_body(&username, "correct-horse", installation)))
        .await
        .unwrap();
    assert_eq!(resp2.status(), StatusCode::OK);
    let device_id2 = Uuid::parse_str(body_json(resp2).await["device_id"].as_str().unwrap()).unwrap();
    assert_eq!(device_id, device_id2);

    // Exactly one device row for (user, installation) - no uncontrolled dupes.
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM devices WHERE user_id = $1 AND installation_id = $2")
        .bind(user_id)
        .bind(installation)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(count, 1, "same-account relogin must not create duplicate device rows");
}

/// Simulates what migration 0013 did to every pre-existing device row:
/// `installation_id = id` (the old `devices.id` WAS the client installation
/// UUID, so the two become equal). The upgraded client then logs in with that
/// recovered UUID as `installation_id`.
#[tokio::test]
async fn legacy_backfilled_device_row_is_reused_on_upgraded_login() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let installation = Uuid::new_v4();
    let username = unique_username("legacy_");

    let resp = app
        .clone()
        .oneshot(json_request("/auth/register", ip, register_body(&username, "correct-horse", installation)))
        .await
        .unwrap();
    let body = body_json(resp).await;
    let device_id = Uuid::parse_str(body["device_id"].as_str().unwrap()).unwrap();
    let user_id = Uuid::parse_str(body["user"]["id"].as_str().unwrap()).unwrap();

    // Simulate the pre-0013 -> post-0013 state: a legacy row whose id WAS the
    // client installation UUID, backfilled by the migration to installation_id = id.
    sqlx::query("UPDATE devices SET installation_id = id WHERE id = $1")
        .bind(device_id)
        .execute(&state.db)
        .await
        .unwrap();

    // The upgraded client recovers its old UUID and logs in with it as
    // installation_id -> the SAME logical device row must be reused.
    let resp2 = app
        .clone()
        .oneshot(json_request("/auth/login", ip, login_body(&username, "correct-horse", device_id)))
        .await
        .unwrap();
    assert_eq!(resp2.status(), StatusCode::OK, "upgraded login with the recovered UUID must succeed");
    let device_id2 = Uuid::parse_str(body_json(resp2).await["device_id"].as_str().unwrap()).unwrap();
    assert_eq!(device_id, device_id2, "legacy row must be reused, not duplicated");

    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM devices WHERE user_id = $1")
        .bind(user_id)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(count, 1, "no duplicate account device may appear");
}

/// Legacy row A + a different account B logging in on the SAME installation
/// must give B its own account-scoped device - never reuse A's row.
#[tokio::test]
async fn legacy_device_row_is_not_reused_by_a_different_account() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let installation = Uuid::new_v4();
    let username_a = unique_username("lga_");

    let resp_a = app
        .clone()
        .oneshot(json_request("/auth/register", ip, register_body(&username_a, "correct-horse", installation)))
        .await
        .unwrap();
    let body_a = body_json(resp_a).await;
    let device_a = Uuid::parse_str(body_a["device_id"].as_str().unwrap()).unwrap();

    // Simulate legacy backfill: A's row now has installation_id = its old id.
    sqlx::query("UPDATE devices SET installation_id = id WHERE id = $1")
        .bind(device_a)
        .execute(&state.db)
        .await
        .unwrap();

    // B registers on the same installation (installation_id = A's old UUID).
    let username_b = unique_username("lgb_");
    let resp_b = app
        .oneshot(json_request("/auth/register", ip, register_body(&username_b, "correct-horse", device_a)))
        .await
        .unwrap();
    assert_eq!(resp_b.status(), StatusCode::OK);
    let device_b = Uuid::parse_str(body_json(resp_b).await["device_id"].as_str().unwrap()).unwrap();

    assert_ne!(device_a, device_b, "B must get its own account-scoped device, never A's row");
}

#[tokio::test]
async fn concurrent_first_login_on_same_installation_reuses_device() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let username = unique_username("race_");
    let registered = app.clone().oneshot(json_request("/auth/register", connect_info(),
        register_body(&username, "correct-horse", Uuid::new_v4()))).await.unwrap();
    assert_eq!(registered.status(), StatusCode::OK);
    let installation = Uuid::new_v4();
    let request = || json_request("/auth/login", connect_info(), login_body(&username, "correct-horse", installation));
    let (a, b) = tokio::join!(app.clone().oneshot(request()), app.oneshot(request()));
    let a = a.unwrap(); let b = b.unwrap();
    assert_eq!(a.status(), StatusCode::OK);
    assert_eq!(b.status(), StatusCode::OK);
    assert_eq!(body_json(a).await["device_id"], body_json(b).await["device_id"]);
}

#[tokio::test]
async fn live_socket_follows_refresh_but_stops_after_logout() {
    use messenger_server::auth::middleware::{AuthUser, socket_session_active};
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let response = app.clone().oneshot(json_request("/auth/register", connect_info(),
        register_body(&unique_username("socket_"), "correct-horse", Uuid::new_v4()))).await.unwrap();
    let body = body_json(response).await;
    let claims = jwt::decode_access_token(&state.config, body["access_token"].as_str().unwrap()).unwrap();
    let auth = AuthUser { user_id: claims.sub, device_id: claims.device_id, session_id: claims.session_id };
    assert!(socket_session_active(&state, &auth).await.unwrap());
    let refreshed = app.clone().oneshot(json_request("/auth/refresh", connect_info(),
        json!({"refresh_token": body["refresh_token"]}))).await.unwrap();
    let refreshed = body_json(refreshed).await;
    assert!(socket_session_active(&state, &auth).await.unwrap());
    let response = app.oneshot(Request::builder().method("POST").uri("/auth/logout")
        .header("Authorization", format!("Bearer {}", refreshed["access_token"].as_str().unwrap()))
        .body(Body::empty()).unwrap()).await.unwrap();
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    assert!(!socket_session_active(&state, &auth).await.unwrap());
}

#[tokio::test]
async fn login_account_limit_survives_ip_and_case_changes() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let username = unique_username("distributed_");
    let response = app.clone().oneshot(json_request("/auth/register", connect_info(),
        register_body(&username, "correct-horse", Uuid::new_v4()))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    for attempt in 0..21 {
        let name = if attempt % 2 == 0 { username.to_uppercase() } else { username.clone() };
        let response = app.clone().oneshot(json_request("/auth/login", connect_info(),
            login_body(&name, "wrong-password", Uuid::new_v4()))).await.unwrap();
        assert_eq!(response.status(), if attempt < 20 { StatusCode::UNAUTHORIZED } else { StatusCode::TOO_MANY_REQUESTS });
    }
    let other = unique_username("unknown_");
    let response = app.clone().oneshot(json_request("/auth/login", connect_info(),
        login_body(&other, "wrong-password", Uuid::new_v4()))).await.unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    use sha2::{Digest, Sha256};
    let key = format!("ratelimit:login-account:{:x}:0.0.0.0", Sha256::digest(username.to_lowercase().as_bytes()));
    let mut redis = state.redis.clone();
    let ttl: i64 = redis::cmd("TTL").arg(&key).query_async(&mut redis).await.unwrap();
    assert!(ttl > 0 && ttl <= 300);
    let _: i64 = redis::cmd("EXPIRE").arg(&key).arg(0).query_async(&mut redis).await.unwrap();
    let response = app.clone().oneshot(json_request("/auth/login", connect_info(),
        login_body(&username.to_uppercase(), "correct-horse", Uuid::new_v4()))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn installation_restrictions_cover_admission_sessions_expiry_and_shared_devices() {
    use axum::{extract::{State,Path},Json};
    use messenger_server::admin;
    let state=spawn_state().await;
    let app=messenger_server::app(state.clone());
    let installation=Uuid::new_v4();
    let a=unique_username("install_a");let b=unique_username("install_b");let owner_name=unique_username("install_owner");
    let first=body_json(app.clone().oneshot(json_request("/auth/register",connect_info(),register_body(&a,"correct-horse",installation))).await.unwrap()).await;
    let second=body_json(app.clone().oneshot(json_request("/auth/register",connect_info(),register_body(&b,"correct-horse",installation))).await.unwrap()).await;
    let owner_body=body_json(app.clone().oneshot(json_request("/auth/register",connect_info(),register_body(&owner_name,"correct-horse",Uuid::new_v4()))).await.unwrap()).await;
    let uid=Uuid::parse_str(first["user"]["id"].as_str().unwrap()).unwrap();
    let peer=Uuid::parse_str(second["user"]["id"].as_str().unwrap()).unwrap();
    let owner_id=Uuid::parse_str(owner_body["user"]["id"].as_str().unwrap()).unwrap();
    let owner=||admin::Admin{user:owner_id,role:"owner".into(),token_hash:"test-installations".into(),auth_session:uuid::Uuid::nil(),channel:admin::AdminChannel::Native};
    let suspension=|blocked,include|Json(serde_json::from_value::<admin::Suspension>(json!({"blocked":blocked,"include_installations":include,"reason":"test"})).unwrap());
    let chat_request=Request::builder().method("POST").uri("/chats/direct")
        .header("authorization",format!("Bearer {}",second["access_token"].as_str().unwrap())).header("content-type","application/json")
        .extension(connect_info()).body(Body::from(json!({"other_user_id":owner_id}).to_string())).unwrap();
    assert_eq!(app.clone().oneshot(chat_request).await.unwrap().status(),StatusCode::OK);
    let call_request=Request::builder().method("POST").uri("/calls")
        .header("authorization",format!("Bearer {}",second["access_token"].as_str().unwrap())).header("content-type","application/json")
        .extension(connect_info()).body(Body::from(json!({"callee_user_id":owner_id,"media_kind":"audio"}).to_string())).unwrap();
    let call_response=app.clone().oneshot(call_request).await.unwrap();assert_eq!(call_response.status(),StatusCode::OK);
    let call=Uuid::parse_str(body_json(call_response).await["id"].as_str().unwrap()).unwrap();
    let _=admin::suspend(State(state.clone()),owner(),Path(uid),suspension(true,true)).await.unwrap();
    let ended:String=sqlx::query_scalar("SELECT state::text FROM calls WHERE id=$1").bind(call).fetch_one(&state.db).await.unwrap();assert_eq!(ended,"ended");
    let participants:i64=sqlx::query_scalar("SELECT COUNT(*) FROM call_participants_active WHERE call_id=$1").bind(call).fetch_one(&state.db).await.unwrap();assert_eq!(participants,0);

    // Both accounts on the installation lose their existing sessions.
    for item in [&first,&second] {
        let refresh=app.clone().oneshot(json_request("/auth/refresh",connect_info(),json!({"refresh_token":item["refresh_token"]}))).await.unwrap();
        assert_eq!(refresh.status(),StatusCode::UNAUTHORIZED);
        let auth=messenger_server::auth::AuthUser {user_id:Uuid::parse_str(item["user"]["id"].as_str().unwrap()).unwrap(),device_id:Uuid::parse_str(item["device_id"].as_str().unwrap()).unwrap(),session_id:messenger_server::auth::refresh_token::parse_session_id(item["refresh_token"].as_str().unwrap()).unwrap()};
        assert!(!messenger_server::auth::middleware::socket_session_active(&state,&auth).await.unwrap());
    }
    let candidate=unique_username("install_new");
    let denied=app.clone().oneshot(json_request("/auth/register",connect_info(),register_body(&candidate,"correct-horse",installation))).await.unwrap();
    assert_eq!(denied.status(),StatusCode::FORBIDDEN);
    assert_eq!(body_json(denied).await["error"]["code"],"installation_restricted");
    let count:i64=sqlx::query_scalar("SELECT COUNT(*) FROM users WHERE username=$1").bind(&candidate).fetch_one(&state.db).await.unwrap();assert_eq!(count,0);
    assert_eq!(app.clone().oneshot(json_request("/auth/login",connect_info(),login_body(&b,"correct-horse",installation))).await.unwrap().status(),StatusCode::FORBIDDEN);
    assert_eq!(app.clone().oneshot(json_request("/auth/login",connect_info(),login_body(&b,"correct-horse",Uuid::new_v4()))).await.unwrap().status(),StatusCode::OK);
    // Lifting the account alone does not silently lift installation restrictions.
    let _=admin::suspend(State(state.clone()),owner(),Path(uid),suspension(false,false)).await.unwrap();
    assert_eq!(app.clone().oneshot(json_request("/auth/login",connect_info(),login_body(&a,"correct-horse",installation))).await.unwrap().status(),StatusCode::FORBIDDEN);
    // Independent restrictions on a shared installation survive lifting only one source.
    let _=admin::suspend(State(state.clone()),owner(),Path(peer),suspension(true,true)).await.unwrap();
    let _=admin::suspend(State(state.clone()),owner(),Path(uid),suspension(false,true)).await.unwrap();
    assert_eq!(app.clone().oneshot(json_request("/auth/login",connect_info(),login_body(&a,"correct-horse",installation))).await.unwrap().status(),StatusCode::FORBIDDEN);
    sqlx::query("UPDATE installation_suspensions SET until_date=now()-interval '1 second' WHERE source_user_id=$1").bind(peer).execute(&state.db).await.unwrap();
    assert_eq!(app.clone().oneshot(json_request("/auth/login",connect_info(),login_body(&a,"correct-horse",installation))).await.unwrap().status(),StatusCode::OK);
    let _=admin::suspend(State(state.clone()),owner(),Path(peer),suspension(false,true)).await.unwrap();
    // A concurrent registration either loses admission or has its new session revoked.
    let new_name=unique_username("install_race");
    let registration=app.clone().oneshot(json_request("/auth/register",connect_info(),register_body(&new_name,"correct-horse",installation)));
    let (ban,registration)=tokio::join!(admin::suspend(State(state.clone()),owner(),Path(uid),suspension(true,true)),registration);
    let _=ban.unwrap();let registration=registration.unwrap();
    if registration.status()==StatusCode::OK {
        let registered=body_json(registration).await;
        assert_eq!(app.clone().oneshot(json_request("/auth/refresh",connect_info(),json!({"refresh_token":registered["refresh_token"]}))).await.unwrap().status(),StatusCode::UNAUTHORIZED);
    } else {assert_eq!(registration.status(),StatusCode::FORBIDDEN);}
    let _=admin::suspend(State(state.clone()),owner(),Path(uid),suspension(false,true)).await.unwrap();
    // A currently known administrator installation cannot be swept into a ban.
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'owner')").bind(owner_id).execute(&state.db).await.unwrap();
    assert_eq!(app.clone().oneshot(json_request("/auth/login",connect_info(),login_body(&owner_name,"correct-horse",installation))).await.unwrap().status(),StatusCode::OK);
    assert!(admin::suspend(State(state.clone()),owner(),Path(uid),suspension(true,true)).await.is_err());
    let bans:i64=sqlx::query_scalar("SELECT COUNT(*) FROM server_suspensions WHERE user_id=$1").bind(uid).fetch_one(&state.db).await.unwrap();assert_eq!(bans,0);
    // Account-only restriction remains possible without locking the administrator out.
    let _=admin::suspend(State(state.clone()),owner(),Path(uid),suspension(true,false)).await.unwrap();
    assert_eq!(app.clone().oneshot(json_request("/auth/login",connect_info(),login_body(&owner_name,"correct-horse",installation))).await.unwrap().status(),StatusCode::OK);
}

#[tokio::test]
async fn pending_logout_revokes_rotated_family_but_preserves_new_login() {
    let state = spawn_state().await;
    let db = state.db.clone();
    let app = messenger_server::app(state);
    let ip = connect_info();
    let username = unique_username("offline_logout_");
    let installation = Uuid::new_v4();
    let response = app.clone().oneshot(json_request("/auth/register", ip,
        register_body(&username, "correct-horse", installation))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let original = body_json(response).await;
    let old = original["refresh_token"].as_str().unwrap();
    let old_id = messenger_server::auth::refresh_token::parse_session_id(old).unwrap();
    // Lost refresh response: an offline logout still holds its parent token.
    let rotated = app.clone().oneshot(json_request("/auth/refresh", ip,
        json!({"refresh_token":old,"request_id":Uuid::new_v4()}))).await.unwrap();
    assert_eq!(rotated.status(), StatusCode::OK);
    let rotated = body_json(rotated).await;
    let child = messenger_server::auth::refresh_token::parse_session_id(rotated["refresh_token"].as_str().unwrap()).unwrap();
    // A later independent login on the same installation must survive.
    let login = app.clone().oneshot(json_request("/auth/login", ip, login_body(&username,"correct-horse",installation))).await.unwrap();
    assert_eq!(login.status(), StatusCode::OK);
    let login = body_json(login).await;
    let fresh = messenger_server::auth::refresh_token::parse_session_id(login["refresh_token"].as_str().unwrap()).unwrap();
    // Expired access/refresh metadata must not prevent a proven revocation.
    sqlx::query("UPDATE sessions SET expires_at=now()-interval '1 day' WHERE id=$1").bind(old_id).execute(&db).await.unwrap();
    let forged = format!("{}.wrong-secret",old_id);
    let bad = app.clone().oneshot(json_request("/auth/logout-pending",ip,json!({"refresh_token":forged}))).await.unwrap();
    assert_eq!(bad.status(),StatusCode::UNAUTHORIZED);
    for _ in 0..2 {
        let response = app.clone().oneshot(json_request("/auth/logout-pending",ip,json!({"refresh_token":old}))).await.unwrap();
        assert_eq!(response.status(),StatusCode::NO_CONTENT);
    }
    let revoked: bool=sqlx::query_scalar("SELECT revoked_at IS NOT NULL FROM sessions WHERE id=$1").bind(child).fetch_one(&db).await.unwrap();
    let live: bool=sqlx::query_scalar("SELECT revoked_at IS NULL FROM sessions WHERE id=$1").bind(fresh).fetch_one(&db).await.unwrap();
    assert!(revoked); assert!(live);
}

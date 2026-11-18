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
        identity: Default::default(),
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

#[path = "common/safe_username.rs"]
mod safe_username;
use safe_username::unique_username;

fn connect_info() -> ConnectInfo<SocketAddr> {
    let octets: [u8; 3] = std::array::from_fn(|_| rand::random());
    ConnectInfo(SocketAddr::from(([10, octets[0], octets[1], octets[2]], 12345)))
}

fn register_request(ip: ConnectInfo<SocketAddr>, username: &str, display_name: &str) -> Request<Body> {
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

fn get_request(uri: &str, ip: ConnectInfo<SocketAddr>, token: &str) -> Request<Body> {
    Request::builder()
        .method("GET")
        .uri(uri)
        .header("authorization", format!("Bearer {token}"))
        .extension(ip)
        .body(Body::empty())
        .unwrap()
}

fn patch_request(uri: &str, ip: ConnectInfo<SocketAddr>, token: &str, body: Value) -> Request<Body> {
    Request::builder()
        .method("PATCH")
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

/// Returns (status, body) of a sign-up with this display name, from its own address.
async fn register(app: &axum::Router, display_name: &str) -> (StatusCode, Value) {
    let response = app.clone().oneshot(register_request(connect_info(), &unique_username("dn_"), display_name)).await.unwrap();
    let status = response.status();
    (status, body_json(response).await)
}

/// Registers an ordinary account and returns (access_token, user_id).
async fn register_user(app: &axum::Router) -> (String, String) {
    let (status, body) = register(app, "Test User").await;
    assert_eq!(status, StatusCode::OK);
    (body["access_token"].as_str().unwrap().to_string(), body["user"]["id"].as_str().unwrap().to_string())
}

async fn patch_me(app: &axum::Router, token: &str, body: Value) -> (StatusCode, Value) {
    let response = app.clone().oneshot(patch_request("/users/me", connect_info(), token, body)).await.unwrap();
    let status = response.status();
    (status, body_json(response).await)
}

async fn me(app: &axum::Router, token: &str) -> Value {
    let response = app.clone().oneshot(get_request("/users/me", connect_info(), token)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await
}

#[tokio::test]
async fn sign_up_stores_the_sanitised_name_and_rejects_reserved_ones() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);

    let (status, body) = register(&app, "\u{202E}Eve\u{200B}").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["user"]["display_name"], "Eve");
    let token = body["access_token"].as_str().unwrap();
    assert_eq!(me(&app, token).await["display_name"], "Eve");

    let (status, body) = register(&app, "UNYRI Support").await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["error"]["code"], "display_name_reserved");
    assert_eq!(body["error"]["message"], "This name is reserved for the service. Choose another name.");
}

#[tokio::test]
async fn profile_edits_sanitise_and_reject_reserved_or_invisible_names() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let (token, _) = register_user(&app).await;

    let (status, body) = patch_me(&app, &token, json!({"display_name": "  Bob \u{2066}  Smith "})).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["display_name"], "Bob Smith");
    assert_eq!(me(&app, &token).await["display_name"], "Bob Smith");

    let (status, body) = patch_me(&app, &token, json!({"display_name": "Поддержка UNYRI"})).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["error"]["code"], "display_name_reserved");

    let (status, body) = patch_me(&app, &token, json!({"display_name": "\u{2800}"})).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["error"]["code"], "validation_error");
    assert_eq!(me(&app, &token).await["display_name"], "Bob Smith");
}

#[tokio::test]
async fn officials_may_use_reserved_names() {
    let state = spawn_state().await;
    let db = state.db.clone();
    let app = messenger_server::app(state);
    let (token, id) = register_user(&app).await;
    let id = Uuid::parse_str(&id).unwrap();

    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'moderator')").bind(id).execute(&db).await.unwrap();
    let (status, body) = patch_me(&app, &token, json!({"display_name": "UNYRI Support"})).await;
    // Leave no administrator behind in the shared test database.
    sqlx::query("DELETE FROM server_administrators WHERE user_id=$1").bind(id).execute(&db).await.unwrap();
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["display_name"], "UNYRI Support");
    // The reply carries the role like GET /users/me: clients store it as their profile.
    assert_eq!(body["admin_role"], "moderator");
}

#[tokio::test]
async fn an_unchanged_legacy_name_never_blocks_other_edits() {
    let state = spawn_state().await;
    let db = state.db.clone();
    let app = messenger_server::app(state);
    let (token, id) = register_user(&app).await;
    let id = Uuid::parse_str(&id).unwrap();
    sqlx::query("UPDATE users SET display_name='UNYRI' WHERE id=$1").bind(id).execute(&db).await.unwrap();

    let (status, body) = patch_me(&app, &token, json!({"display_name": "UNYRI", "bio": "x"})).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["display_name"], "UNYRI");
    assert_eq!(body["bio"], "x");

    let (status, body) = patch_me(&app, &token, json!({"bio": "y"})).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["display_name"], "UNYRI");
    assert_eq!(body["bio"], "y");

    // An invisible legacy name sent back unchanged is grandfathered too.
    sqlx::query("UPDATE users SET display_name=$2 WHERE id=$1").bind(id).bind("\u{3164}").execute(&db).await.unwrap();
    let (status, body) = patch_me(&app, &token, json!({"display_name": "\u{3164}", "bio": "z"})).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["bio"], "z");
}

#[tokio::test]
async fn legacy_long_name_and_reserved_handle_survive_bio_and_case_only_edits() {
    let state = spawn_state().await;
    let db = state.db.clone();
    let app = messenger_server::app(state);
    let (token, id) = register_user(&app).await;
    let id = Uuid::parse_str(&id).unwrap();
    let legacy_handle = unique_username("admin_");
    let legacy_name = "N".repeat(40);
    sqlx::query("UPDATE users SET username=$2,display_name=$3 WHERE id=$1")
        .bind(id).bind(&legacy_handle).bind(&legacy_name).execute(&db).await.unwrap();
    let (status, body) = patch_me(&app, &token, json!({"bio":"legacy bio"})).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["username"], legacy_handle);
    assert_eq!(body["display_name"], legacy_name);
    let upper = legacy_handle.to_ascii_uppercase();
    let (status, body) = patch_me(&app, &token, json!({"username":upper,"display_name":legacy_name,"bio":"case only"})).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["username"], upper);
    assert_eq!(body["display_name"], legacy_name);
    assert_eq!(me(&app, &token).await["display_name"], legacy_name);
}

#[tokio::test]
async fn staff_can_set_reserved_handles_but_links_remain_forbidden() {
    let state = spawn_state().await;
    let db = state.db.clone();
    let app = messenger_server::app(state);
    let (token, id) = register_user(&app).await;
    let id = Uuid::parse_str(&id).unwrap();
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'moderator')")
        .bind(id).execute(&db).await.unwrap();
    let handle = unique_username("unyri_");
    let allowed = patch_me(&app, &token, json!({"username":handle,"display_name":"Bank Security"})).await;
    let denied = patch_me(&app, &token, json!({"username":"example.com"})).await;
    sqlx::query("DELETE FROM server_administrators WHERE user_id=$1").bind(id).execute(&db).await.unwrap();
    assert_eq!(allowed.0, StatusCode::OK, "{}", allowed.1);
    assert_eq!(allowed.1["username"], handle);
    assert_eq!(denied.0, StatusCode::BAD_REQUEST);
    assert_eq!(denied.1["error"]["code"], "username_link");
}

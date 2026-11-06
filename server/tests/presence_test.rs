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

fn connect_info() -> ConnectInfo<SocketAddr> {
    let octets: [u8; 3] = std::array::from_fn(|_| rand::random());
    ConnectInfo(SocketAddr::from(([10, octets[0], octets[1], octets[2]], 12345)))
}

fn register_request(ip: ConnectInfo<SocketAddr>, username: &str) -> Request<Body> {
    let body = json!({
        "username": username,
        "password": "correct-horse-battery",
        "display_name": "Test User",
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

fn post_json_request(uri: &str, ip: ConnectInfo<SocketAddr>, token: &str, body: Value) -> Request<Body> {
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

fn login_request(ip: ConnectInfo<SocketAddr>, username: &str) -> Request<Body> {
    Request::builder()
        .method("POST")
        .uri("/auth/login")
        .header("content-type", "application/json")
        .extension(ip)
        .body(Body::from(
            json!({
                "username": username,
                "password": "correct-horse-battery",
                "installation_id": Uuid::new_v4(),
                "device_platform": "android",
                "device_name": "Second Device",
            })
            .to_string(),
        ))
        .unwrap()
}

async fn register_user(app: &axum::Router, ip: ConnectInfo<SocketAddr>, prefix: &str) -> (String, String, String, String) {
    let username = unique_username(prefix);
    let response = app.clone().oneshot(register_request(ip, &username)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
        body["device_id"].as_str().unwrap().to_string(),
        username,
    )
}

async fn create_direct_chat(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, other_user_id: &str) -> String {
    let response = app
        .clone()
        .oneshot(post_json_request("/chats/direct", ip, token, json!({ "other_user_id": other_user_id })))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await["chat_id"].as_str().unwrap().to_string()
}


#[tokio::test]
async fn presence_requires_membership_and_respects_block_and_disconnect() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (a, _, _, _) = register_user(&app, ip, "presencea").await;
    let (b, bid, device, b_username) = register_user(&app, ip, "presenceb").await;
    let (outsider, _, _, _) = register_user(&app, ip, "presencex").await;
    let chat = create_direct_chat(&app, ip, &a, &bid).await;
    let uri = format!("/chats/{chat}/presence");
    assert_eq!(app.clone().oneshot(get_request(&uri, ip, None)).await.unwrap().status(), StatusCode::UNAUTHORIZED);
    assert_eq!(app.clone().oneshot(get_request(&uri, ip, Some(&outsider))).await.unwrap().status(), StatusCode::NOT_FOUND);
    assert_eq!(body_json(app.clone().oneshot(get_request(&uri, ip, Some(&a))).await.unwrap()).await["online"], false);
    let device = Uuid::parse_str(&device).unwrap();
    let bid = Uuid::parse_str(&bid).unwrap();
    let (conn, _receiver) = state.ws_registry.register(device).await;

    // A registered transport alone is not foreground presence.
    let transport_only = body_json(app.clone().oneshot(get_request(&uri, ip, Some(&a))).await.unwrap()).await;
    assert_eq!(transport_only["online"], false, "open socket in background must not be online");

    // The peer's own socket still must not make the requesting user online.
    assert_eq!(body_json(app.clone().oneshot(get_request(&uri, ip, Some(&b))).await.unwrap()).await["online"], false);

    state.ws_registry.foreground(bid, device, conn, true).await;
    let foreground = body_json(app.clone().oneshot(get_request(&uri, ip, Some(&a))).await.unwrap()).await;
    assert_eq!(foreground["online"], true);
    assert!(foreground["last_seen_at"].is_string());

    state.ws_registry.foreground(bid, device, conn, false).await;
    assert_eq!(body_json(app.clone().oneshot(get_request(&uri, ip, Some(&a))).await.unwrap()).await["online"], false);

    state.ws_registry.unregister(device, conn).await;
    let disconnected = body_json(app.clone().oneshot(get_request(&uri, ip, Some(&a))).await.unwrap()).await;
    assert_eq!(disconnected["online"], false);
    assert!(disconnected["last_seen_at"].is_string(), "last_seen survives background/disconnect");

    // Multiple devices aggregate: one background device must not hide the
    // other active device; the last active transition turns the user offline.
    let (first_conn, _first_rx) = state.ws_registry.register(device).await;
    state.ws_registry.foreground(bid, device, first_conn, true).await;
    let second = body_json(app.clone().oneshot(login_request(ip, &b_username)).await.unwrap()).await;
    let second_device = Uuid::parse_str(second["device_id"].as_str().unwrap()).unwrap();
    let (second_conn, _second_rx) = state.ws_registry.register(second_device).await;
    state.ws_registry.foreground(bid, second_device, second_conn, true).await;
    assert_eq!(body_json(app.clone().oneshot(get_request(&uri, ip, Some(&a))).await.unwrap()).await["online"], true);
    state.ws_registry.foreground(bid, device, first_conn, false).await;
    assert_eq!(body_json(app.clone().oneshot(get_request(&uri, ip, Some(&a))).await.unwrap()).await["online"], true, "second active device keeps user online");
    state.ws_registry.foreground(bid, second_device, second_conn, false).await;
    let offline = body_json(app.clone().oneshot(get_request(&uri, ip, Some(&a))).await.unwrap()).await;
    assert_eq!(offline["online"], false);
    assert!(offline["last_seen_at"].is_string());
    state.ws_registry.unregister(device, first_conn).await;
    state.ws_registry.unregister(second_device, second_conn).await;

    let blocked = app.clone().oneshot(post_json_request(&format!("/chats/{chat}/block"), ip, &a, json!({"blocked_user_id":bid}))).await.unwrap();
    assert_eq!(blocked.status(), StatusCode::OK);
    assert_eq!(app.clone().oneshot(get_request(&uri, ip, Some(&a))).await.unwrap().status(), StatusCode::CONFLICT);
    assert_eq!(app.clone().oneshot(get_request(&uri, ip, Some(&b))).await.unwrap().status(), StatusCode::CONFLICT);
}

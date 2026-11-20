use std::net::SocketAddr;

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, StatusCode};
use base64::{engine::general_purpose::STANDARD, Engine as _};
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

/// Registers a fresh user/device and returns (access_token, user_id, device_id, username).
/// Every registration comes from its own address, so the per-IP sign-up limit
/// never interferes with tests that need several accounts.
async fn register_user(app: &axum::Router, prefix: &str) -> (String, String, String, String) {
    let username = unique_username(prefix);
    let response = app.clone().oneshot(register_request(connect_info(), &username, "Test User")).await.unwrap();
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

fn envelope(recipient_device_id: &str, message_type: i16, plaintext: &str) -> Value {
    json!({
        "recipient_device_id": recipient_device_id,
        "message_type": message_type,
        "ciphertext": STANDARD.encode(plaintext.as_bytes()),
    })
}

fn send_body(client_message_id: Uuid, envelopes: Vec<Value>) -> Value {
    json!({ "client_message_id": client_message_id, "envelopes": envelopes })
}

fn send_body_to(client_message_id: Uuid, recipient_device_id: &str, plaintext: &str) -> Value {
    send_body(client_message_id, vec![envelope(recipient_device_id, 2, plaintext)])
}

async fn request(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: Option<&str>, method: &str, path: &str) -> axum::response::Response {
    let mut builder = Request::builder().method(method).uri(path).extension(ip);
    if let Some(token) = token {
        builder = builder.header("authorization", format!("Bearer {token}"));
    }
    app.clone().oneshot(builder.body(Body::empty()).unwrap()).await.unwrap()
}

async fn contacts(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, query: &str) -> Value {
    let response = request(app, ip, Some(token), "GET", &format!("/users/contacts{query}")).await;
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await
}

async fn is_contact(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, peer: &str) -> bool {
    let response = request(app, ip, Some(token), "GET", &format!("/users/{peer}/contact")).await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["user_id"], peer);
    body["is_contact"].as_bool().unwrap()
}

fn ids(page: &Value) -> Vec<String> {
    page["items"].as_array().unwrap().iter().map(|item| item["user"]["id"].as_str().unwrap().to_string()).collect()
}

async fn set_contact(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, peer: &str, contact: bool) -> Value {
    let response = request(app, ip, Some(token), if contact { "POST" } else { "DELETE" }, &format!("/users/{peer}/contact")).await;
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await
}

async fn set_block(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, peer: &str, blocked: bool) {
    let response = request(app, ip, Some(token), if blocked { "POST" } else { "DELETE" }, &format!("/users/{peer}/block")).await;
    assert_eq!(response.status(), StatusCode::OK);
}

async fn send_content(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, chat_id: &str, device: &str) {
    let response = app
        .clone()
        .oneshot(post_json_request(&format!("/chats/{chat_id}/messages"), ip, token, send_body_to(Uuid::new_v4(), device, "hi")))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn contact_routes_require_a_session() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let peer = Uuid::new_v4();
    for (method, path) in [("GET", "/users/contacts".to_string()), ("GET", format!("/users/{peer}/contact")), ("POST", format!("/users/{peer}/contact")), ("DELETE", format!("/users/{peer}/contact"))] {
        let response = request(&app, ip, None, method, &path).await;
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED, "{method} {path}");
    }
}

#[tokio::test]
async fn manual_add_and_remove_are_idempotent_private_and_one_way() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, id_a, _device_a, _) = register_user(&app, "ct_a_").await;
    let (token_b, id_b, _device_b, username_b) = register_user(&app, "ct_b_").await;

    assert!(!is_contact(&app, ip, &token_a, &id_b).await);
    let added = set_contact(&app, ip, &token_a, &id_b, true).await;
    assert!(is_contact(&app, ip, &token_a, &id_b).await, "one person's status matches the write");
    assert!(!is_contact(&app, ip, &token_b, &id_a).await, "and stays one-way");
    assert_eq!(added["user_id"], id_b.as_str());
    assert_eq!(added["is_contact"], true);
    assert_eq!(added["changed"], true);
    assert_eq!(added["source"], "manual");
    assert!(added["added_at"].is_string());

    let page = contacts(&app, ip, &token_a, "").await;
    assert_eq!(ids(&page), vec![id_b.clone()]);
    let item = &page["items"][0];
    assert_eq!(item["source"], "manual");
    assert!(item["added_at"].is_string());
    assert_eq!(item["user"]["username"], "");
    assert!(item["user"]["public_username"].is_null());
    assert_eq!(item["user"]["unyri_number"].as_str().unwrap().len(), 9);
    assert!(!page.to_string().contains(&username_b));
    assert_eq!(item["user"]["display_name"], "Test User");
    for key in ["bio", "avatar_url", "profile_version", "avatar_version", "has_avatar"] {
        assert!(item["user"].get(key).is_some(), "{key}");
    }
    assert!(page["next_cursor"].is_null());

    let again = set_contact(&app, ip, &token_a, &id_b, true).await;
    assert_eq!(again["changed"], false);
    assert_eq!(again["is_contact"], true);

    // One-way: B was never told and has no contact.
    assert!(ids(&contacts(&app, ip, &token_b, "").await).is_empty());

    let removed = set_contact(&app, ip, &token_a, &id_b, false).await;
    assert_eq!(removed["changed"], true);
    assert_eq!(removed["is_contact"], false);
    assert!(removed["source"].is_null());
    assert!(ids(&contacts(&app, ip, &token_a, "").await).is_empty());
    assert!(!is_contact(&app, ip, &token_a, &id_b).await);
    assert_eq!(set_contact(&app, ip, &token_a, &id_b, false).await["changed"], false);
    assert_eq!(set_contact(&app, ip, &token_a, &Uuid::new_v4().to_string(), false).await["changed"], false);

    let own = request(&app, ip, Some(&token_a), "POST", &format!("/users/{id_a}/contact")).await;
    assert_eq!(own.status(), StatusCode::BAD_REQUEST);
    let unknown = request(&app, ip, Some(&token_a), "POST", &format!("/users/{}/contact", Uuid::new_v4())).await;
    assert_eq!(unknown.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn blocks_hide_contacts_without_revealing_the_block() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _, _) = register_user(&app, "ctb_a_").await;
    let (_token_b, id_b, _, _) = register_user(&app, "ctb_b_").await;

    set_contact(&app, ip, &token_a, &id_b, true).await;
    set_block(&app, ip, &token_a, &id_b, true).await;
    assert!(ids(&contacts(&app, ip, &token_a, "").await).is_empty(), "a blocked contact is hidden");
    assert!(!is_contact(&app, ip, &token_a, &id_b).await, "the single status agrees with the list");
    set_block(&app, ip, &token_a, &id_b, false).await;
    assert!(is_contact(&app, ip, &token_a, &id_b).await);
    assert_eq!(ids(&contacts(&app, ip, &token_a, "").await), vec![id_b.clone()], "an unblock brings the row back");

    let (token_d, id_d, _, _) = register_user(&app, "ctb_d_").await;
    let (token_e, id_e, _, _) = register_user(&app, "ctb_e_").await;
    set_block(&app, ip, &token_e, &id_d, true).await;
    let added = set_contact(&app, ip, &token_d, &id_e, true).await;
    assert_eq!(added["is_contact"], true, "the answer never reveals the peer's block");
    assert!(ids(&contacts(&app, ip, &token_d, "").await).is_empty());
    set_block(&app, ip, &token_e, &id_d, false).await;
    assert_eq!(ids(&contacts(&app, ip, &token_d, "").await), vec![id_e]);
}

#[tokio::test]
async fn the_first_direct_message_adds_a_contact_once() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _) = register_user(&app, "cta_a_").await;
    let (token_b, id_b, device_b, _) = register_user(&app, "cta_b_").await;

    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;
    assert!(ids(&contacts(&app, ip, &token_a, "").await).is_empty(), "opening a chat adds nobody");

    send_content(&app, ip, &token_a, &chat_id, &device_b).await;
    let page = contacts(&app, ip, &token_a, "").await;
    assert_eq!(ids(&page), vec![id_b.clone()]);
    assert_eq!(page["items"][0]["source"], "chat");
    assert!(ids(&contacts(&app, ip, &token_b, "").await).is_empty(), "only the sender gains a contact");

    set_contact(&app, ip, &token_a, &id_b, false).await;
    send_content(&app, ip, &token_a, &chat_id, &device_b).await;
    assert!(ids(&contacts(&app, ip, &token_a, "").await).is_empty(), "a removed contact is never re-added by a message");

    let readded = set_contact(&app, ip, &token_a, &id_b, true).await;
    assert_eq!(readded["changed"], true);
    assert_eq!(readded["source"], "manual");
    assert_eq!(contacts(&app, ip, &token_a, "").await["items"][0]["source"], "manual");
}

#[tokio::test]
async fn contacts_page_by_cursor() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _, _, _) = register_user(&app, "ctp_a_").await;
    let mut expected = Vec::new();
    for prefix in ["ctp_b_", "ctp_c_", "ctp_d_"] {
        let (_, id, _, _) = register_user(&app, prefix).await;
        set_contact(&app, ip, &token_a, &id, true).await;
        expected.push(id);
    }
    expected.sort();

    let first = contacts(&app, ip, &token_a, "?limit=2").await;
    assert_eq!(ids(&first), expected[..2].to_vec());
    let cursor = first["next_cursor"].as_str().expect("a second page").to_string();
    assert_eq!(cursor, expected[1]);
    let second = contacts(&app, ip, &token_a, &format!("?limit=2&cursor={cursor}")).await;
    assert_eq!(ids(&second), expected[2..].to_vec());
    assert!(second["next_cursor"].is_null());
}

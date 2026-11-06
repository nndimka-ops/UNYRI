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

/// Each test gets its own fake client IP so the shared Redis-backed rate
/// limiter (keyed by IP) can't cause cross-test interference when the suite
/// runs in parallel.
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

fn get_request(uri: &str, ip: ConnectInfo<SocketAddr>, token: Option<&str>) -> Request<Body> {
    let mut builder = Request::builder().method("GET").uri(uri).extension(ip);
    if let Some(token) = token {
        builder = builder.header("authorization", format!("Bearer {token}"));
    }
    builder.body(Body::empty()).unwrap()
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

fn post_request(uri: &str, ip: ConnectInfo<SocketAddr>, token: &str) -> Request<Body> {
    if uri=="/users/me/avatar" {
        return post_json_request(uri,ip,token,json!({"size_bytes":avatar_bytes().len()}));
    }
    Request::builder()
        .method("POST")
        .uri(uri)
        .header("authorization", format!("Bearer {token}"))
        .extension(ip)
        .body(Body::empty())
        .unwrap()
}

fn avatar_bytes() -> Vec<u8> {
    let mut bytes=Vec::new();
    image::codecs::jpeg::JpegEncoder::new(&mut bytes).encode_image(&image::RgbImage::new(12,8)).unwrap();
    bytes
}

async fn body_json(response: axum::response::Response) -> Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap()
}

/// Registers a fresh user and returns (access_token, user_id, username).
async fn register_user(app: &axum::Router, ip: ConnectInfo<SocketAddr>, prefix: &str) -> (String, String, String) {
    let username = unique_username(prefix);
    let response = app.clone().oneshot(register_request(ip, &username, "Test User")).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
        username,
    )
}

#[tokio::test]
async fn get_me_returns_current_user_without_private_fields() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, user_id, username) = register_user(&app, ip, "me_ok_").await;

    let response = app.oneshot(get_request("/users/me", ip, Some(&token))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;

    assert_eq!(body["id"], user_id);
    assert_eq!(body["username"], username);
    assert_eq!(body["display_name"], "Test User");
    assert!(body["avatar_url"].is_null());
    assert!(body["bio"].is_null());
    assert_eq!(body["profile_version"], 1, "fresh account starts at profile_version 1");
    assert_eq!(body["avatar_version"], 0, "fresh account starts at avatar_version 0");
    assert_eq!(body["has_avatar"], false);
    assert!(body["created_at"].is_string());
    assert!(body["updated_at"].is_string());
    assert!(body.get("password_hash").is_none());
    assert!(body.get("refresh_token").is_none());
    assert!(body.get("avatar_key").is_none(), "avatar_key must never be exposed");
}

#[tokio::test]
async fn patch_display_name_updates_profile_and_ignores_id_field() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, user_id, username) = register_user(&app, ip, "patch_dn_").await;

    // A client-supplied `id` must never override the authenticated user id.
    let body = json!({ "id": Uuid::new_v4().to_string(), "display_name": "Updated Name" });
    let response = app.oneshot(patch_request("/users/me", ip, &token, body)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;

    assert_eq!(body["id"], user_id);
    assert_eq!(body["username"], username);
    assert_eq!(body["display_name"], "Updated Name");
}

#[tokio::test]
async fn patch_username_changes_username() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, user_id, _username) = register_user(&app, ip, "patch_un_").await;

    let new_username = unique_username("patch_un_new_");
    let body = json!({ "username": new_username });
    let response = app.oneshot(patch_request("/users/me", ip, &token, body)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;

    assert_eq!(body["id"], user_id);
    assert_eq!(body["username"], new_username);
}

#[tokio::test]
async fn patch_username_duplicate_is_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (_token_a, _id_a, username_a) = register_user(&app, ip, "dup_a_").await;
    let (token_b, _id_b, _username_b) = register_user(&app, ip, "dup_b_").await;

    let body = json!({ "username": username_a });
    let response = app.oneshot(patch_request("/users/me", ip, &token_b, body)).await.unwrap();
    assert_eq!(response.status(), StatusCode::CONFLICT);
    let body = body_json(response).await;
    assert_eq!(body["error"]["code"], "username_taken");
}

#[tokio::test]
async fn patch_username_invalid_is_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _user_id, _username) = register_user(&app, ip, "inval_un_").await;

    for bad_username in ["ab", "has space", "semi;colon", &"x".repeat(64)] {
        let body = json!({ "username": bad_username });
        let response = app.clone().oneshot(patch_request("/users/me", ip, &token, body)).await.unwrap();
        assert_eq!(response.status(), StatusCode::BAD_REQUEST, "expected {bad_username} to be rejected");
        let body = body_json(response).await;
        assert_eq!(body["error"]["code"], "validation_error");
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn concurrent_username_update_race_condition_is_resolved_by_db_constraint() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _username_a) = register_user(&app, ip, "race_a_").await;
    let (token_b, _id_b, _username_b) = register_user(&app, ip, "race_b_").await;

    let target_username = unique_username("race_target_");

    let app1 = app.clone();
    let app2 = app.clone();
    let body1 = json!({ "username": target_username });
    let body2 = json!({ "username": target_username });

    let handle1 = tokio::spawn(async move { app1.oneshot(patch_request("/users/me", ip, &token_a, body1)).await });
    let handle2 = tokio::spawn(async move { app2.oneshot(patch_request("/users/me", ip, &token_b, body2)).await });

    let status1 = handle1.await.unwrap().unwrap().status();
    let status2 = handle2.await.unwrap().unwrap().status();

    // Exactly one request must win the race; the other must be rejected as a
    // conflict, never both succeeding (which would violate uniqueness) and
    // never both failing.
    let statuses = [status1, status2];
    assert!(statuses.contains(&StatusCode::OK), "expected one winner, got {statuses:?}");
    assert!(statuses.contains(&StatusCode::CONFLICT), "expected one loser, got {statuses:?}");
}

#[tokio::test]
async fn avatar_url_metadata_set_clear_and_reject_data_uri() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _user_id, _username) = register_user(&app, ip, "avatar_").await;

    // Set.
    let body = json!({ "avatar_url": "https://cdn.example.com/avatars/a.png" });
    let response = app.clone().oneshot(patch_request("/users/me", ip, &token, body)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["avatar_url"], "https://cdn.example.com/avatars/a.png");

    // Reject a data: URI (must never be stored as image bytes disguised as a URL).
    let body = json!({ "avatar_url": "data:image/png;base64,abcd" });
    let response = app.clone().oneshot(patch_request("/users/me", ip, &token, body)).await.unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);

    // Explicit null clears it.
    let body = json!({ "avatar_url": null });
    let response = app.clone().oneshot(patch_request("/users/me", ip, &token, body)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert!(body["avatar_url"].is_null());

    // Omitting the field entirely must leave the current value untouched.
    let body = json!({ "avatar_url": "https://cdn.example.com/avatars/b.png" });
    app.clone().oneshot(patch_request("/users/me", ip, &token, body)).await.unwrap();
    let body = json!({ "display_name": "Still Has Avatar" });
    let response = app.oneshot(patch_request("/users/me", ip, &token, body)).await.unwrap();
    let body = body_json(response).await;
    assert_eq!(body["avatar_url"], "https://cdn.example.com/avatars/b.png");
}

#[tokio::test]
async fn bio_set_clear_and_reject_too_long() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _user_id, _username) = register_user(&app, ip, "bio_").await;

    // Set.
    let body = json!({ "bio": "Hello, I am a COCO user" });
    let response = app.clone().oneshot(patch_request("/users/me", ip, &token, body)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["bio"], "Hello, I am a COCO user");

    // Too long is rejected.
    let body = json!({ "bio": "x".repeat(161) });
    let response = app.clone().oneshot(patch_request("/users/me", ip, &token, body)).await.unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);

    // Explicit null clears it.
    let body = json!({ "bio": null });
    let response = app.clone().oneshot(patch_request("/users/me", ip, &token, body)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert!(body["bio"].is_null());

    // Empty string also clears it.
    let body = json!({ "bio": "  " });
    let response = app.clone().oneshot(patch_request("/users/me", ip, &token, body)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert!(body["bio"].is_null());

    // Omitting the field leaves the current value untouched.
    let body = json!({ "bio": "Back again" });
    app.clone().oneshot(patch_request("/users/me", ip, &token, body)).await.unwrap();
    let body = json!({ "display_name": "Still Has Bio" });
    let response = app.oneshot(patch_request("/users/me", ip, &token, body)).await.unwrap();
    let body = body_json(response).await;
    assert_eq!(body["bio"], "Back again");
}

#[tokio::test]
async fn avatar_presign_returns_key_and_url() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _user_id, _username) = register_user(&app, ip, "avatar_up_").await;

    // POST /users/me/avatar -> a presigned PUT for an `avatars/` object key.
    let response = app.clone().oneshot(post_request("/users/me/avatar", ip, &token)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    let key = body["object_key"].as_str().unwrap().to_string();
    assert!(key.starts_with("avatars/"), "avatar key must use the avatars/ prefix: {key}");
    assert!(body["upload_url"].as_str().unwrap().starts_with("http"));
    assert!(body["expires_at"].is_string());

    // GET /users/me/avatar -> 404 before any avatar is committed.
    let response = app.clone().oneshot(get_request("/users/me/avatar", ip, Some(&token))).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);

    // A presigned intent must actually be uploaded before PATCH can commit it.
    reqwest::Client::new().put(body["upload_url"].as_str().unwrap())
        .body(avatar_bytes()).send().await.unwrap().error_for_status().unwrap();
    // Committing the key via PATCH then GET resolves a download URL.
    let body = json!({ "avatar_url": key });
    let response = app.clone().oneshot(patch_request("/users/me", ip, &token, body)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let response = app.oneshot(get_request("/users/me/avatar", ip, Some(&token))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert!(body["download_url"].as_str().unwrap().starts_with("http"));
}

#[tokio::test]
async fn search_finds_matching_usernames() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (searcher_token, _id, _username) = register_user(&app, ip, "searcher_").await;

    let needle: String = Uuid::new_v4().simple().to_string().chars().take(8).collect();
    let username = format!("srch_{needle}_tgt");
    let response = app.clone().oneshot(register_request(ip, &username, "Findable User")).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    let uri = format!("/users/search?q={needle}");
    let response = app.oneshot(get_request(&uri, ip, Some(&searcher_token))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    let items = body["items"].as_array().unwrap();
    assert!(items.iter().any(|item| item["username"] == username));
}

#[tokio::test]
async fn search_paginates_results() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (searcher_token, _id, _username) = register_user(&app, ip, "pager_").await;

    let needle: String = Uuid::new_v4().simple().to_string().chars().take(8).collect();
    let mut usernames = Vec::new();
    for i in 0..5 {
        let username = format!("pg_{needle}_{i}");
        // Separate IP per registration: the register endpoint's own rate
        // limit (5/min) would otherwise trip when combined with the
        // searcher's registration above, which is unrelated to what this
        // test is actually verifying (search pagination).
        let response = app.clone().oneshot(register_request(connect_info(), &username, "Page User")).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        usernames.push(username);
    }

    let mut seen = std::collections::HashSet::new();
    let mut offset = 0i64;
    loop {
        let uri = format!("/users/search?q={needle}&limit=2&offset={offset}");
        let response = app.clone().oneshot(get_request(&uri, ip, Some(&searcher_token))).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = body_json(response).await;
        let items = body["items"].as_array().unwrap().clone();
        assert!(items.len() <= 2);
        for item in &items {
            let inserted = seen.insert(item["username"].as_str().unwrap().to_string());
            assert!(inserted, "page returned a duplicate item");
        }
        match body["next_offset"].as_i64() {
            Some(next) => offset = next,
            None => break,
        }
    }

    for username in &usernames {
        assert!(seen.contains(username), "missing {username} across pages");
    }
}

#[tokio::test]
async fn search_is_rate_limited() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _id, _username) = register_user(&app, ip, "ratelim_").await;

    let mut last_status = StatusCode::OK;
    for _ in 0..31 {
        let response = app.clone().oneshot(get_request("/users/search?q=zzz", ip, Some(&token))).await.unwrap();
        last_status = response.status();
        if last_status == StatusCode::TOO_MANY_REQUESTS {
            break;
        }
    }
    assert_eq!(last_status, StatusCode::TOO_MANY_REQUESTS);
}

#[tokio::test]
async fn users_me_requires_authentication() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();

    let response = app.oneshot(get_request("/users/me", ip, None)).await.unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    let body = body_json(response).await;
    assert_eq!(body["error"]["code"], "unauthorized");
}

#[tokio::test]
async fn revoked_session_cannot_access_users_me() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _id, _username) = register_user(&app, ip, "revoked_").await;

    let logout_response = app.clone().oneshot(post_request("/auth/logout", ip, &token)).await.unwrap();
    assert_eq!(logout_response.status(), StatusCode::NO_CONTENT);

    let response = app.oneshot(get_request("/users/me", ip, Some(&token))).await.unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    let body = body_json(response).await;
    assert_eq!(body["error"]["code"], "session_revoked");
}

#[tokio::test]
async fn two_users_see_correct_public_data_about_each_other() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _username_a) = register_user(&app, ip, "pub_a_").await;
    let needle: String = Uuid::new_v4().simple().to_string().chars().take(8).collect();
    let username_b = format!("pub_b_{needle}");
    let register_response = app.clone().oneshot(register_request(ip, &username_b, "Public B")).await.unwrap();
    let register_body = body_json(register_response).await;
    let id_b = register_body["user"]["id"].as_str().unwrap().to_string();

    let uri = format!("/users/search?q={needle}");
    let response = app.oneshot(get_request(&uri, ip, Some(&token_a))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    let found = body["items"].as_array().unwrap().iter().find(|item| item["username"] == username_b).unwrap();

    assert_eq!(found["id"], id_b);
    assert_eq!(found["display_name"], "Public B");
}

#[tokio::test]
async fn search_never_leaks_private_fields() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _id, _username) = register_user(&app, ip, "priv_").await;
    let needle: String = Uuid::new_v4().simple().to_string().chars().take(8).collect();
    let username = format!("priv_tgt_{needle}");
    app.clone().oneshot(register_request(ip, &username, "Private Target")).await.unwrap();

    let uri = format!("/users/search?q={needle}");
    let response = app.oneshot(get_request(&uri, ip, Some(&token))).await.unwrap();
    let body = body_json(response).await;
    let item = body["items"].as_array().unwrap().iter().find(|item| item["username"] == username).unwrap();

    let allowed_keys: std::collections::HashSet<&str> =
        ["id", "username", "display_name", "bio", "avatar_url", "profile_version", "avatar_version", "has_avatar"].into_iter().collect();
    for key in item.as_object().unwrap().keys() {
        assert!(allowed_keys.contains(key.as_str()), "unexpected field in search result: {key}");
    }
    assert!(item.get("password_hash").is_none());
    assert!(item.get("created_at").is_none());
    assert!(item.get("updated_at").is_none());
    assert!(item.get("avatar_key").is_none(), "avatar_key is internal and must never be exposed");
    assert!(item.get("device_id").is_none());
    assert!(item.get("session").is_none());
}

// ---------------------------------------------------------------------------
// E.1 profile/avatar foundation hardening tests
// ---------------------------------------------------------------------------

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

fn delete_request(uri: &str, ip: ConnectInfo<SocketAddr>, token: &str) -> Request<Body> {
    Request::builder()
        .method("DELETE")
        .uri(uri)
        .header("authorization", format!("Bearer {token}"))
        .extension(ip)
        .body(Body::empty())
        .unwrap()
}

/// Creates a presigned PUT intent, uploads real bytes to MinIO, and returns
/// the object key - the exact client upload flow up to (but not including)
/// the authoritative commit.
async fn upload_avatar_object(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str) -> String {
    let response = app.clone().oneshot(post_request("/users/me/avatar", ip, token)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    let key = body["object_key"].as_str().unwrap().to_string();
    let upload_url = body["upload_url"].as_str().unwrap().to_string();
    reqwest::Client::new().put(&upload_url).body(avatar_bytes()).send().await.unwrap().error_for_status().unwrap();
    key
}

async fn complete_avatar(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    token: &str,
    key: &str,
) -> axum::response::Response {
    app.clone()
        .oneshot(post_json_request("/users/me/avatar/complete", ip, token, json!({ "object_key": key })))
        .await
        .unwrap()
}

#[tokio::test]
async fn no_op_patch_does_not_increment_profile_version() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _user_id, _username) = register_user(&app, ip, "noop_").await;

    // A PATCH that supplies the current values verbatim must be a no-op.
    let response = app.clone().oneshot(patch_request("/users/me", ip, &token, json!({ "display_name": "Test User" }))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["profile_version"], 1, "no-op PATCH must not increment profile_version");
    assert_eq!(body["avatar_version"], 0);
}

#[tokio::test]
async fn text_fields_each_increment_profile_version_exactly_once() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _user_id, _username) = register_user(&app, ip, "ver_").await;

    let response = app.clone().oneshot(patch_request("/users/me", ip, &token, json!({ "display_name": "Renamed" }))).await.unwrap();
    assert_eq!(body_json(response).await["profile_version"], 2);

    let response = app.clone().oneshot(patch_request("/users/me", ip, &token, json!({ "username": unique_username("veru_") }))).await.unwrap();
    assert_eq!(body_json(response).await["profile_version"], 3);

    let response = app.clone().oneshot(patch_request("/users/me", ip, &token, json!({ "bio": "hello" }))).await.unwrap();
    let body = body_json(response).await;
    assert_eq!(body["profile_version"], 4);
    assert_eq!(body["avatar_version"], 0, "text-only changes must not touch avatar_version");
}

#[tokio::test]
async fn avatar_complete_increments_versions_once_and_retry_is_noop() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _user_id, _username) = register_user(&app, ip, "avc_").await;

    let key = upload_avatar_object(&app, ip, &token).await;
    let response = complete_avatar(&app, ip, &token, &key).await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["avatar_version"], 1);
    assert_eq!(body["profile_version"], 2);
    assert_eq!(body["has_avatar"], true);
    assert_eq!(body["avatar_url"], key);

    // Retrying the SAME key after a lost response must be a safe no-op.
    let response = complete_avatar(&app, ip, &token, &key).await;
    let body = body_json(response).await;
    assert_eq!(body["avatar_version"], 1, "same-key retry must not increment twice");
    assert_eq!(body["profile_version"], 2);
}

#[tokio::test]
async fn avatar_replacement_increments_once() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _user_id, _username) = register_user(&app, ip, "avr_").await;

    let key1 = upload_avatar_object(&app, ip, &token).await;
    complete_avatar(&app, ip, &token, &key1).await;

    let key2 = upload_avatar_object(&app, ip, &token).await;
    let response = complete_avatar(&app, ip, &token, &key2).await;
    let body = body_json(response).await;
    assert_eq!(body["avatar_version"], 2, "replacement increments avatar_version exactly once");
    assert_eq!(body["avatar_url"], key2);
}

#[tokio::test]
async fn avatar_delete_increments_once_and_noop_delete_is_safe() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _user_id, _username) = register_user(&app, ip, "avd_").await;

    let key = upload_avatar_object(&app, ip, &token).await;
    complete_avatar(&app, ip, &token, &key).await;

    let response = app.clone().oneshot(delete_request("/users/me/avatar", ip, &token)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["avatar_version"], 2, "removal increments once (present -> absent)");
    assert_eq!(body["has_avatar"], false);
    assert!(body["avatar_url"].is_null());

    // A no-op delete (already absent) must not create uncontrolled increments.
    let response = app.oneshot(delete_request("/users/me/avatar", ip, &token)).await.unwrap();
    let body = body_json(response).await;
    assert_eq!(body["avatar_version"], 2, "no-op delete must not increment");
}

#[tokio::test]
async fn other_user_avatar_get_is_authenticated_and_controlled() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, id_a, _username_a) = register_user(&app, ip, "avga_").await;
    let (token_b, id_b, _username_b) = register_user(&app, ip, "avgb_").await;

    // B has no avatar yet -> controlled 404 (and indistinguishable from a
    // missing user, so probing is impossible).
    let response = app.clone().oneshot(get_request(&format!("/users/{id_b}/avatar"), ip, Some(&token_a))).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);

    // B uploads + commits an avatar.
    let key = upload_avatar_object(&app, ip, &token_b).await;
    complete_avatar(&app, ip, &token_b, &key).await;

    // Authenticated other-user retrieval succeeds with a presigned URL.
    let response = app.clone().oneshot(get_request(&format!("/users/{id_b}/avatar"), ip, Some(&token_a))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert!(body["download_url"].as_str().unwrap().starts_with("http"));

    // Unauthenticated retrieval is rejected.
    let response = app.clone().oneshot(get_request(&format!("/users/{id_b}/avatar"), ip, None)).await.unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);

    // A nonexistent user id is the same controlled 404.
    let response = app.oneshot(get_request(&format!("/users/{}/avatar", Uuid::new_v4()), ip, Some(&token_a))).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    let _ = id_a;
}

#[tokio::test]
async fn own_profile_mutation_never_touches_another_user() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _username_a) = register_user(&app, ip, "mut_a_").await;
    let (token_b, id_b, _username_b) = register_user(&app, ip, "mut_b_").await;

    let key = upload_avatar_object(&app, ip, &token_a).await;
    complete_avatar(&app, ip, &token_a, &key).await;

    // B's profile is untouched by A's avatar mutation.
    let response = app.oneshot(get_request(&format!("/users/{id_b}/avatar"), ip, Some(&token_b))).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND, "A's avatar mutation must never touch B");
}

#[tokio::test]
async fn old_avatar_reference_replaced_only_after_successful_commit() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _user_id, _username) = register_user(&app, ip, "oldav_").await;

    // Commit a first avatar.
    let key1 = upload_avatar_object(&app, ip, &token).await;
    complete_avatar(&app, ip, &token, &key1).await;

    // A second key that was NEVER uploaded: complete must reject and the old
    // avatar must remain authoritative (no version bump, no reference change).
    let bogus = format!("avatars/{}", Uuid::new_v4());
    let response = complete_avatar(&app, ip, &token, &bogus).await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND, "a never-uploaded object must not be committed");

    let response = app.oneshot(get_request("/users/me", ip, Some(&token))).await.unwrap();
    let body = body_json(response).await;
    assert_eq!(body["avatar_url"], key1, "old avatar remains authoritative after a failed replace");
    assert_eq!(body["avatar_version"], 1);
}

#[tokio::test]
async fn another_account_cannot_commit_or_patch_an_avatar_upload() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (a, _, _) = register_user(&app, ip, "own_a").await;
    let (b, _, _) = register_user(&app, ip, "own_b").await;
    let key = upload_avatar_object(&app, ip, &a).await;
    assert_eq!(complete_avatar(&app, ip, &a, &key).await.status(), StatusCode::OK);
    assert!(complete_avatar(&app, ip, &b, &key).await.status().is_client_error());
    let response = app.clone().oneshot(patch_request("/users/me", ip, &b, json!({"avatar_url": key}))).await.unwrap();
    assert!(response.status().is_client_error());
    assert_eq!(app.oneshot(get_request("/users/me/avatar", ip, Some(&a))).await.unwrap().status(), StatusCode::OK);
}

fn account_json_request(uri: &str, ip: ConnectInfo<SocketAddr>, token: &str, body: Value) -> Request<Body> {
    Request::builder().method("POST").uri(uri).extension(ip)
        .header("content-type", "application/json").header("authorization", format!("Bearer {token}"))
        .body(Body::from(body.to_string())).unwrap()
}

#[tokio::test]
async fn delete_account_reauth_cascades_and_durable_peer_removals() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token, owner, _) = register_user(&app, ip, "del_owner_").await;
    let (peer_token, peer, _) = register_user(&app, connect_info(), "del_peer_").await;
    let (other_token, other, _) = register_user(&app, connect_info(), "del_other_").await;
    let owner_id = Uuid::parse_str(&owner).unwrap();
    let peer_id = Uuid::parse_str(&peer).unwrap();
    let mut chats = Vec::new();
    for target in [&peer, &other] {
        let response = app.clone().oneshot(account_json_request("/chats/direct", ip, &token, json!({"other_user_id":target}))).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        chats.push(Uuid::parse_str(body_json(response).await["chat_id"].as_str().unwrap()).unwrap());
    }
    let response = app.clone().oneshot(account_json_request("/chats/direct", ip, &peer_token, json!({"other_user_id":other}))).await.unwrap();
    let untouched = Uuid::parse_str(body_json(response).await["chat_id"].as_str().unwrap()).unwrap();
    let response = app.clone().oneshot(get_request("/chats/saved", ip, Some(&token))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let saved = Uuid::parse_str(body_json(response).await["chat_id"].as_str().unwrap()).unwrap();
    let _owner_device: Uuid = sqlx::query_scalar("SELECT id FROM devices WHERE user_id=$1 LIMIT 1").bind(owner_id).fetch_one(&state.db).await.unwrap();
    let peer_device: Uuid = sqlx::query_scalar("SELECT id FROM devices WHERE user_id=$1 LIMIT 1").bind(peer_id).fetch_one(&state.db).await.unwrap();
    let message: Uuid = sqlx::query_scalar("INSERT INTO messages(chat_id,sender_id,sender_device_id,client_message_id,encryption_version) VALUES($1,$2,$3,$4,1) RETURNING id")
        .bind(chats[0]).bind(peer_id).bind(peer_device).bind(Uuid::new_v4()).fetch_one(&state.db).await.unwrap();
    sqlx::query("INSERT INTO message_envelopes(message_id,recipient_device_id,message_type,ciphertext) VALUES($1,$2,1,$3)")
        .bind(message).bind(peer_device).bind(vec![1u8,2,3]).execute(&state.db).await.unwrap();
    let object = format!("attachments/deletion-test-{}",Uuid::new_v4());
    sqlx::query("INSERT INTO attachments(client_attachment_id,uploader_user_id,uploader_device_id,object_key,encrypted_size,status,message_id) VALUES($1,$2,$3,$4,3,'attached',$5)")
        .bind(Uuid::new_v4()).bind(peer_id).bind(peer_device).bind(&object).bind(message).execute(&state.db).await.unwrap();
    let avatar = format!("avatars/deletion-test-{}",Uuid::new_v4());
    sqlx::query("INSERT INTO avatar_uploads(object_key,user_id,expected_bytes,reserved_bytes,is_complete) VALUES($1,$2,1,1,true)").bind(&avatar).bind(owner_id).execute(&state.db).await.unwrap();
    sqlx::query("INSERT INTO bug_reports(id,user_id,payload) VALUES($1,$2,'{}'::jsonb)").bind(Uuid::new_v4()).bind(owner_id).execute(&state.db).await.unwrap();
    let wrong = app.clone().oneshot(account_json_request("/users/me/delete", ip, &token,json!({"password":"wrong"}))).await.unwrap();
    assert_eq!(wrong.status(), StatusCode::BAD_REQUEST);
    assert_eq!(app.clone().oneshot(get_request("/users/me",ip,Some(&token))).await.unwrap().status(),StatusCode::OK);
    let response = app.clone().oneshot(account_json_request("/users/me/delete",ip,&token,json!({"password":"correct-horse-battery"}))).await.unwrap();
    assert_eq!(response.status(),StatusCode::NO_CONTENT);
    assert_eq!(app.clone().oneshot(get_request("/users/me",ip,Some(&token))).await.unwrap().status(),StatusCode::UNAUTHORIZED);
    for table in ["users","devices","sessions","bug_reports"] {
        let column=if table=="users" { "id" } else { "user_id" };
        let count:i64=sqlx::query_scalar(&format!("SELECT count(*) FROM {table} WHERE {column}=$1")).bind(owner_id).fetch_one(&state.db).await.unwrap();
        assert_eq!(count,0,"remaining {table}");
    }
    let missing:i64=sqlx::query_scalar("SELECT count(*) FROM chats WHERE id=ANY($1)").bind(vec![chats[0],chats[1],saved]).fetch_one(&state.db).await.unwrap();assert_eq!(missing,0);
    let kept:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM chats WHERE id=$1)").bind(untouched).fetch_one(&state.db).await.unwrap();assert!(kept);
    for (peer_token,deleted) in [(&peer_token,chats[0]),(&other_token,chats[1])] {
        for cursor in [0,i64::MAX] {
            let response=app.clone().oneshot(get_request(&format!("/sync?after_sequence={cursor}"),ip,Some(peer_token))).await.unwrap();
            assert_eq!(response.status(),StatusCode::OK);
            let body=body_json(response).await;
            assert_eq!(body["deleted_chat_ids"],json!([deleted]));
            assert!(body["messages"].as_array().unwrap().is_empty());
        }
    }
    let queued:i64=sqlx::query_scalar("SELECT count(*) FROM account_object_deletions WHERE object_key=ANY($1) AND delete_after>now()")
        .bind(vec![object,avatar]).fetch_one(&state.db).await.unwrap();assert_eq!(queued,2);
}

#[tokio::test]
async fn delete_account_requires_auth_and_limits_reauthentication_per_account() {
    let state=spawn_state().await;
    let app=messenger_server::app(state.clone());
    let ip=connect_info();
    let (token, owner, _)=register_user(&app,ip,"del_limit_").await;
    let unauthenticated=Request::builder().method("POST").uri("/users/me/delete").extension(ip)
        .header("content-type","application/json").body(Body::from(json!({"password":"correct-horse-battery"}).to_string())).unwrap();
    assert_eq!(app.clone().oneshot(unauthenticated).await.unwrap().status(),StatusCode::UNAUTHORIZED);
    for attempt in 0..6 {
        let response=app.clone().oneshot(account_json_request("/users/me/delete",connect_info(),&token,json!({"password":"wrong"}))).await.unwrap();
        assert_eq!(response.status(),if attempt<5 {StatusCode::BAD_REQUEST} else {StatusCode::TOO_MANY_REQUESTS});
    }
    let exists:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM users WHERE id=$1)").bind(Uuid::parse_str(&owner).unwrap()).fetch_one(&state.db).await.unwrap();assert!(exists);
}

/// Serializes the two tests that invoke the GLOBAL `sweep_deleted_objects`
/// over the shared `account_object_deletions` table. Both run in the same
/// test process (`users_test`) against the shared `messenger_test` DB, so an
/// in-process lock is sufficient: without it, one test's sweep (real S3) can
/// consume the other test's due row (MinIO 404 is treated as a successful
/// delete), making the retention assertion flaky.
fn deletion_sweep_test_lock() -> &'static std::sync::Mutex<()> {
    static LOCK: std::sync::OnceLock<std::sync::Mutex<()>> = std::sync::OnceLock::new();
    LOCK.get_or_init(|| std::sync::Mutex::new(()))
}

#[tokio::test]
async fn delete_account_storage_failure_retains_durable_cleanup() {
    let _sweep_guard = deletion_sweep_test_lock().lock().unwrap_or_else(|e| e.into_inner());
    let mut state=spawn_state().await;
    let mut storage=state.config.s3.clone(); storage.endpoint_url="http://127.0.0.1:1".into();
    state.s3=std::sync::Arc::new(messenger_server::storage::S3Client::new(storage));
    let key=format!("attachments/deletion-retry-{}",Uuid::new_v4());
    sqlx::query("INSERT INTO account_object_deletions(object_key,delete_after) VALUES($1,now())").bind(&key).execute(&state.db).await.unwrap();
    assert_eq!(messenger_server::users::deletion::sweep_deleted_objects(&state).await.unwrap(),0);
    let exists:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM account_object_deletions WHERE object_key=$1)").bind(&key).fetch_one(&state.db).await.unwrap();assert!(exists);
    sqlx::query("DELETE FROM account_object_deletions WHERE object_key=$1").bind(key).execute(&state.db).await.unwrap();
}

#[tokio::test]
async fn delete_account_completed_multipart_not_found_still_deletes_object() {
    let _sweep_guard = deletion_sweep_test_lock().lock().unwrap_or_else(|e| e.into_inner());
    let state=spawn_state().await;
    let key=format!("attachments/deletion-completed-{}",Uuid::new_v4());
    let (url,_)=state.s3.presign_put_sized(&key,3).unwrap();
    reqwest::Client::new().put(url).body(vec![1u8,2,3]).send().await.unwrap().error_for_status().unwrap();
    assert_eq!(state.s3.head_object(&key).await.unwrap(),Some(3));
    // A completed multipart upload no longer has an upload ID; S3 returns 404.
    let finished_upload=Uuid::new_v4().to_string();
    state.s3.abort_multipart(&key,&finished_upload).await.expect("NoSuchUpload is an idempotent success");
    sqlx::query("INSERT INTO account_object_deletions(object_key,multipart_upload_id,delete_after) VALUES($1,$2,now())")
        .bind(&key).bind(finished_upload).execute(&state.db).await.unwrap();
    assert!(messenger_server::users::deletion::sweep_deleted_objects(&state).await.unwrap()>=1);
    assert_eq!(state.s3.head_object(&key).await.unwrap(),None);
    let pending:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM account_object_deletions WHERE object_key=$1)").bind(key).fetch_one(&state.db).await.unwrap();assert!(!pending);
}

#[tokio::test]
async fn avatar_renditions_are_private_bounded_reused_and_removed() {
    let state = spawn_state().await;
    state.s3.ensure_bucket().await.unwrap();
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token, _, _) = register_user(&app, ip, "rendition_").await;
    let mut bytes = Vec::new();
    image::codecs::jpeg::JpegEncoder::new(&mut bytes).encode_image(&image::RgbImage::new(800, 400)).unwrap();
    let response = app.clone().oneshot(post_json_request("/users/me/avatar", ip, &token,json!({"size_bytes":bytes.len()}))).await.unwrap();
    let upload = body_json(response).await;
    let key = upload["object_key"].as_str().unwrap();
    reqwest::Client::new().put(upload["upload_url"].as_str().unwrap()).body(bytes).send().await.unwrap().error_for_status().unwrap();
    assert_eq!(complete_avatar(&app, ip, &token, key).await.status(), StatusCode::OK);
    let response = app.clone().oneshot(get_request("/users/me/avatar?size=96", ip, None)).await.unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    let response = app.clone().oneshot(get_request("/users/me/avatar?size=9999", ip, Some(&token))).await.unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    for _ in 0..2 {
        let response = app.clone().oneshot(get_request("/users/me/avatar?size=96", ip, Some(&token))).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let payload = body_json(response).await;
        let bytes = reqwest::get(payload["download_url"].as_str().unwrap()).await.unwrap().bytes().await.unwrap();
        let decoded = image::load_from_memory(&bytes).unwrap();
        assert_eq!((decoded.width(), decoded.height()), (96, 48));
    }
    for size in [96,256,640] {
        assert!(state.s3.head_object(&format!("avatar-renditions/{}/size-{size}.jpg",key.trim_start_matches("avatars/"))).await.unwrap().is_some());
    }
    state.s3.delete_object(key).await.unwrap();
    for size in [96,256,640] {
        assert!(state.s3.head_object(&format!("avatar-renditions/{}/size-{size}.jpg",key.trim_start_matches("avatars/"))).await.unwrap().is_none());
    }
}

#[tokio::test]
async fn own_profile_changes_reach_both_real_websockets_without_foreign_or_noop_hints() {
    use futures_util::{SinkExt, StreamExt};
    use std::time::Duration;
    use tokio_tungstenite::tungstenite::{client::IntoClientRequest, Message};
    type Socket = tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;
    fn socket_request(addr: SocketAddr, token: &str) -> tokio_tungstenite::tungstenite::http::Request<()> {
        let mut request = format!("ws://{addr}/ws").into_client_request().unwrap();
        request.headers_mut().insert("Authorization", format!("Bearer {token}").parse().unwrap());
        request
    }
    async fn frame(socket: &mut Socket) -> Value {
        let message = tokio::time::timeout(Duration::from_secs(3), socket.next()).await.unwrap().unwrap().unwrap();
        let Message::Text(text) = message else { panic!("Expected JSON text") };
        serde_json::from_str(&text).unwrap()
    }
    async fn ready(socket: &mut Socket) {
        socket.send(Message::Text(json!({"type":"PING","request_id":"own-profile-ready"}).to_string().into())).await.unwrap();
        assert_eq!(frame(socket).await["type"], "PONG");
    }
    async fn hint(socket: &mut Socket, owner: &str, version: i64) {
        let value = frame(socket).await;
        assert_eq!(value, json!({"type":"profile.changed","user_id":owner,"profile_version":version}), "Hints contain no name, bio, URL or credentials");
    }
    async fn quiet(socket: &mut Socket) {
        assert!(tokio::time::timeout(Duration::from_millis(150), socket.next()).await.is_err(), "No-op and foreign sessions must not receive a profile event");
    }

    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (first, owner, username) = register_user(&app, ip, "profile_live_").await;
    let login = Request::builder().method("POST").uri("/auth/login").header("content-type", "application/json")
        .extension(ip).body(Body::from(json!({"username":username,"password":"correct-horse-battery",
            "installation_id":Uuid::new_v4(),"device_platform":"ios","device_name":"Own second profile device"}).to_string())).unwrap();
    let response = app.clone().oneshot(login).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let login = body_json(response).await;
    let second = login["access_token"].as_str().unwrap();
    let (foreign, _, _) = register_user(&app, connect_info(), "profile_foreign_").await;
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let router = app.clone();
    let server = tokio::spawn(async move { axum::serve(listener, router.into_make_service_with_connect_info::<SocketAddr>()).await.unwrap(); });
    let (mut own_a, _) = tokio_tungstenite::connect_async(socket_request(address, &first)).await.unwrap();
    let (mut own_b, _) = tokio_tungstenite::connect_async(socket_request(address, second)).await.unwrap();
    let (mut other, _) = tokio_tungstenite::connect_async(socket_request(address, &foreign)).await.unwrap();
    ready(&mut own_a).await; ready(&mut own_b).await; ready(&mut other).await;

    let changed = json!({"display_name":"Live own name","bio":"Live own bio"});
    let response = app.clone().oneshot(patch_request("/users/me", ip, &first, changed.clone())).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let profile = body_json(response).await;
    let version = profile["profile_version"].as_i64().unwrap();
    hint(&mut own_a, &owner, version).await; hint(&mut own_b, &owner, version).await; quiet(&mut other).await;
    let canonical = body_json(app.clone().oneshot(get_request("/users/me", ip, Some(second))).await.unwrap()).await;
    assert_eq!(canonical["display_name"], "Live own name"); assert_eq!(canonical["bio"], "Live own bio");
    assert_eq!(canonical["profile_version"], version, "The event is sent only after canonical commit");
    let unchanged = body_json(app.clone().oneshot(patch_request("/users/me", ip, &first, changed)).await.unwrap()).await;
    assert_eq!(unchanged["profile_version"], version);
    quiet(&mut own_a).await; quiet(&mut own_b).await; quiet(&mut other).await;

    let upload = body_json(app.clone().oneshot(post_request("/users/me/avatar", ip, &first)).await.unwrap()).await;
    let key = upload["object_key"].as_str().unwrap();
    reqwest::Client::new().put(upload["upload_url"].as_str().unwrap()).body(avatar_bytes()).send().await.unwrap().error_for_status().unwrap();
    let committed = complete_avatar(&app, ip, &first, key).await;
    assert_eq!(committed.status(), StatusCode::OK);
    let avatar = body_json(committed).await;
    assert_eq!(avatar["profile_version"], version + 1); assert_eq!(avatar["avatar_version"], 1);
    hint(&mut own_a, &owner, version + 1).await; hint(&mut own_b, &owner, version + 1).await; quiet(&mut other).await;
    assert_eq!(complete_avatar(&app, ip, &first, key).await.status(), StatusCode::OK);
    quiet(&mut own_a).await; quiet(&mut own_b).await;
    let removed = app.clone().oneshot(delete_request("/users/me/avatar", ip, &first)).await.unwrap();
    assert_eq!(removed.status(), StatusCode::OK);
    let removed = body_json(removed).await;
    assert_eq!(removed["profile_version"], version + 2); assert_eq!(removed["avatar_version"], 2); assert_eq!(removed["has_avatar"], false);
    hint(&mut own_a, &owner, version + 2).await; hint(&mut own_b, &owner, version + 2).await; quiet(&mut other).await;
    assert_eq!(app.clone().oneshot(delete_request("/users/me/avatar", ip, &first)).await.unwrap().status(), StatusCode::OK);
    quiet(&mut own_a).await; quiet(&mut own_b).await;
    own_a.close(None).await.unwrap(); own_b.close(None).await.unwrap(); other.close(None).await.unwrap();
    server.abort();
    state.s3.delete_object(key).await.unwrap();
}

/// The pool-release hook models a second canonical DB commit after the HTTP
/// mutation commits, before its connection can be acquired for a later read.
/// This is deterministic transaction-boundary coverage, not a claim that two
/// physical devices or two HTTP clients were exercised in this test.
#[tokio::test]
async fn avatar_mutation_ack_is_its_locked_snapshot_despite_a_later_canonical_db_commit() {
    use std::sync::{Arc, atomic::{AtomicUsize, Ordering}};
    for removal in [false, true] {
        let state = spawn_state().await;
        let app = messenger_server::app(state.clone());
        let ip = connect_info();
        let (token, owner, _) = register_user(&app, ip, "avatar_ack_").await;
        let first_key = upload_avatar_object(&app, ip, &token).await;
        let second_key = upload_avatar_object(&app, ip, &token).await;
        if removal { assert_eq!(complete_avatar(&app, ip, &token, &first_key).await.status(), StatusCode::OK); }
        let initial = body_json(app.clone().oneshot(get_request("/users/me", ip, Some(&token))).await.unwrap()).await;
        let own_version = initial["profile_version"].as_i64().unwrap() + 1;
        let own_avatar_version = initial["avatar_version"].as_i64().unwrap() + 1;
        let expected_key = if removal { None } else { Some(first_key.clone()) };
        let expected_response_key = expected_key.clone();
        let foreign_key = second_key.clone();
        let owner = Uuid::parse_str(&owner).unwrap();
        let commits = Arc::new(AtomicUsize::new(0));
        let observed = commits.clone();
        let pool = sqlx::postgres::PgPoolOptions::new().max_connections(1)
            .after_release(move |connection, _| {
                let observed = observed.clone();
                let expected_key = expected_key.clone();
                let foreign_key = foreign_key.clone();
                Box::pin(async move {
                    if observed.load(Ordering::SeqCst) == 0 {
                        let row: (Option<String>, i64) = sqlx::query_as(
                            "SELECT avatar_key,profile_version FROM users WHERE id=$1",
                        ).bind(owner).fetch_one(&mut *connection).await?;
                        if row.0 == expected_key && row.1 == own_version &&
                            observed.compare_exchange(0, 1, Ordering::SeqCst, Ordering::SeqCst).is_ok() {
                            sqlx::query("UPDATE users SET avatar_key=$1,avatar_url=$1,avatar_version=avatar_version+1,profile_version=profile_version+1 WHERE id=$2")
                                .bind(foreign_key).bind(owner).execute(&mut *connection).await?;
                        }
                    }
                    Ok(true)
                })
            }).connect(&state.config.database_url).await.unwrap();
        let mut raced = state.clone(); raced.db = pool;
        let app = messenger_server::app(raced.clone());
        let response = if removal {
            app.clone().oneshot(delete_request("/users/me/avatar", ip, &token)).await.unwrap()
        } else { complete_avatar(&app, ip, &token, &first_key).await };
        assert_eq!(response.status(), StatusCode::OK);
        let acknowledged = body_json(response).await;
        assert_eq!(commits.load(Ordering::SeqCst), 1, "The later canonical DB commit must actually run");
        assert_eq!(acknowledged["avatar_url"], expected_response_key.map(Value::String).unwrap_or(Value::Null),
            "The acknowledgement must describe its own locked mutation, not a later avatar's bytes/version");
        assert_eq!(acknowledged["profile_version"], own_version);
        assert_eq!(acknowledged["avatar_version"], own_avatar_version);
        assert_eq!(acknowledged["has_avatar"], !removal);
        let latest = body_json(app.oneshot(get_request("/users/me", ip, Some(&token))).await.unwrap()).await;
        assert_eq!(latest["avatar_url"], second_key);
        assert_eq!(latest["profile_version"], own_version + 1);
        assert_eq!(latest["avatar_version"], own_avatar_version + 1);
        raced.db.close().await;
    }
}

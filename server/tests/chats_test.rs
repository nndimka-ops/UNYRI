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

fn post_request(uri: &str, ip: ConnectInfo<SocketAddr>, token: &str) -> Request<Body> {
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

/// Registers a fresh user and returns (access_token, user_id, device_id).
async fn register_user(app: &axum::Router, ip: ConnectInfo<SocketAddr>, prefix: &str) -> (String, String, Uuid) {
    let username = unique_username(prefix);
    let response = app.clone().oneshot(register_request(ip, &username, "Test User")).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
        Uuid::parse_str(body["device_id"].as_str().unwrap()).unwrap(),
    )
}

#[tokio::test]
async fn create_direct_chat_succeeds() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "cd_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&app, ip, "cd_b_").await;

    let response = app
        .oneshot(post_json_request("/chats/direct", ip, &token_a, json!({ "other_user_id": id_b })))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;

    assert_eq!(body["type"], "direct");
    assert_eq!(body["other_user"]["id"], id_b);
    assert!(body["last_message_at"].is_null());
    assert!(body["chat_id"].as_str().is_some());
}

#[tokio::test]
async fn known_chats_never_enumerates_unrequested_or_foreign_chats() {
    let app = messenger_server::app(spawn_state().await);
    let ip = connect_info();
    let (a, _, _) = register_user(&app, ip, "known_a_").await;
    let (b, bid, _) = register_user(&app, ip, "known_b_").await;
    let (_, cid, _) = register_user(&app, ip, "known_c_").await;
    let ab = body_json(app.clone().oneshot(post_json_request("/chats/direct", ip, &a, json!({"other_user_id":bid}))).await.unwrap()).await;
    let ac = body_json(app.clone().oneshot(post_json_request("/chats/direct", ip, &a, json!({"other_user_id":cid}))).await.unwrap()).await;
    let bc = body_json(app.clone().oneshot(post_json_request("/chats/direct", ip, &b, json!({"other_user_id":cid}))).await.unwrap()).await;
    let abid = ab["chat_id"].as_str().unwrap();
    let bcid = bc["chat_id"].as_str().unwrap();
    assert_ne!(ab["chat_id"], ac["chat_id"]);
    let response = app.clone().oneshot(get_request(&format!("/chats/known?limit=50&chat_ids={abid},{bcid},{}",Uuid::new_v4()),ip,Some(&a))).await.unwrap();
    assert_eq!(response.status(),StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["items"].as_array().unwrap().len(),1);
    assert_eq!(body["items"][0]["chat_id"],abid);
    assert!(body["next_offset"].is_null());
    for uri in ["/chats/known", "/chats/known?chat_ids=", "/chats/known?chat_ids=not-a-uuid"] {
        assert_eq!(app.clone().oneshot(get_request(uri,ip,Some(&a))).await.unwrap().status(),StatusCode::BAD_REQUEST);
    }
    let too_many = vec![abid;51].join(",");
    assert_eq!(app.clone().oneshot(get_request(&format!("/chats/known?chat_ids={too_many}"),ip,Some(&a))).await.unwrap().status(),StatusCode::BAD_REQUEST);
    assert_eq!(app.clone().oneshot(get_request(&format!("/chats/known?chat_ids={abid}"),ip,None)).await.unwrap().status(),StatusCode::UNAUTHORIZED);
    let peer = body_json(app.clone().oneshot(get_request(&format!("/chats/peer/{bid}"),ip,Some(&a))).await.unwrap()).await;
    assert_eq!(peer["chat_id"],abid);
    let unknown = body_json(app.clone().oneshot(get_request(&format!("/chats/peer/{}",Uuid::new_v4()),ip,Some(&a))).await.unwrap()).await;
    assert!(unknown.is_null());
    let legacy = body_json(app.oneshot(get_request("/chats",ip,Some(&a))).await.unwrap()).await;
    assert_eq!(legacy["items"].as_array().unwrap().len(),2);
}

#[tokio::test]
async fn open_existing_direct_chat_via_get() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "open_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&app, ip, "open_b_").await;

    let create_response = app
        .clone()
        .oneshot(post_json_request("/chats/direct", ip, &token_a, json!({ "other_user_id": id_b })))
        .await
        .unwrap();
    let created = body_json(create_response).await;
    let chat_id = created["chat_id"].as_str().unwrap().to_string();

    let uri = format!("/chats/{chat_id}");
    let response = app.oneshot(get_request(&uri, ip, Some(&token_a))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["chat_id"], chat_id);
    assert_eq!(body["other_user"]["id"], id_b);
}

#[tokio::test]
async fn repeated_a_to_b_returns_same_chat() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "rep_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&app, ip, "rep_b_").await;

    let first = app
        .clone()
        .oneshot(post_json_request("/chats/direct", ip, &token_a, json!({ "other_user_id": id_b })))
        .await
        .unwrap();
    let first_body = body_json(first).await;

    let second = app
        .oneshot(post_json_request("/chats/direct", ip, &token_a, json!({ "other_user_id": id_b })))
        .await
        .unwrap();
    let second_body = body_json(second).await;

    assert_eq!(first_body["chat_id"], second_body["chat_id"]);
}

#[tokio::test]
async fn b_to_a_returns_same_chat_as_a_to_b() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, id_a, _dev_a) = register_user(&app, ip, "ba_a_").await;
    let (token_b, id_b, _dev_b) = register_user(&app, ip, "ba_b_").await;

    let a_to_b = app
        .clone()
        .oneshot(post_json_request("/chats/direct", ip, &token_a, json!({ "other_user_id": id_b })))
        .await
        .unwrap();
    let a_to_b_body = body_json(a_to_b).await;

    let b_to_a = app
        .oneshot(post_json_request("/chats/direct", ip, &token_b, json!({ "other_user_id": id_a })))
        .await
        .unwrap();
    let b_to_a_body = body_json(b_to_a).await;

    assert_eq!(a_to_b_body["chat_id"], b_to_a_body["chat_id"]);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn parallel_requests_create_exactly_one_chat() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, id_a, _dev_a) = register_user(&app, ip, "par_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&app, ip, "par_b_").await;

    let app1 = app.clone();
    let app2 = app.clone();
    let token1 = token_a.clone();
    let token2 = token_a.clone();
    let other1 = id_b.clone();
    let other2 = id_b.clone();

    let handle1 = tokio::spawn(async move {
        app1.oneshot(post_json_request("/chats/direct", ip, &token1, json!({ "other_user_id": other1 }))).await
    });
    let handle2 = tokio::spawn(async move {
        app2.oneshot(post_json_request("/chats/direct", ip, &token2, json!({ "other_user_id": other2 }))).await
    });

    let response1 = handle1.await.unwrap().unwrap();
    let response2 = handle2.await.unwrap().unwrap();
    assert_eq!(response1.status(), StatusCode::OK);
    assert_eq!(response2.status(), StatusCode::OK);
    let body1 = body_json(response1).await;
    let body2 = body_json(response2).await;
    assert_eq!(body1["chat_id"], body2["chat_id"]);

    let user_a = Uuid::parse_str(&id_a).unwrap();
    let user_b = Uuid::parse_str(&id_b).unwrap();
    let count: (i64,) = sqlx::query_as(
        "SELECT COUNT(*) FROM chats \
         WHERE user_a_id = LEAST($1, $2) AND user_b_id = GREATEST($1, $2) AND type = 'direct'",
    )
    .bind(user_a)
    .bind(user_b)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(count.0, 1, "exactly one direct chat row must exist for the pair");
}

#[tokio::test]
async fn user_cannot_access_someone_elses_chat() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "priv_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&app, ip, "priv_b_").await;
    let (token_c, _id_c, _dev_c) = register_user(&app, ip, "priv_c_").await;

    let create_response = app
        .clone()
        .oneshot(post_json_request("/chats/direct", ip, &token_a, json!({ "other_user_id": id_b })))
        .await
        .unwrap();
    let created = body_json(create_response).await;
    let chat_id = created["chat_id"].as_str().unwrap().to_string();

    let uri = format!("/chats/{chat_id}");
    let response = app.oneshot(get_request(&uri, ip, Some(&token_c))).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn cannot_create_chat_with_nonexistent_user() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "ghost_a_").await;

    let response = app
        .oneshot(post_json_request("/chats/direct", ip, &token_a, json!({ "other_user_id": Uuid::new_v4() })))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    let body = body_json(response).await;
    assert_eq!(body["error"]["code"], "not_found");
}

#[tokio::test]
async fn cannot_create_chat_with_self() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, id_a, _dev_a) = register_user(&app, ip, "self_a_").await;

    let response = app
        .oneshot(post_json_request("/chats/direct", ip, &token_a, json!({ "other_user_id": id_a })))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let body = body_json(response).await;
    assert_eq!(body["error"]["code"], "validation_error");
}

#[tokio::test]
async fn list_chats_returns_only_own_chats() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "list_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&app, ip, "list_b_").await;
    let (token_c, _id_c, _dev_c) = register_user(&app, ip, "list_c_").await;
    let (_token_d, id_d, _dev_d) = register_user(&app, ip, "list_d_").await;

    app.clone()
        .oneshot(post_json_request("/chats/direct", ip, &token_a, json!({ "other_user_id": id_b })))
        .await
        .unwrap();
    app.clone()
        .oneshot(post_json_request("/chats/direct", ip, &token_c, json!({ "other_user_id": id_d })))
        .await
        .unwrap();

    let response = app.oneshot(get_request("/chats", ip, Some(&token_a))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    let items = body["items"].as_array().unwrap();
    assert_eq!(items.len(), 1);
    assert_eq!(items[0]["other_user"]["id"], id_b);
}

#[tokio::test]
async fn list_chats_paginates() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "page_a_").await;

    let mut chat_ids = Vec::new();
    for i in 0..5 {
        // Separate IP per registration - the register endpoint's own rate
        // limit (5/min) would otherwise trip when combined with A's own
        // registration above, unrelated to what this test verifies.
        let (_token, id, _dev) = register_user(&app, connect_info(), &format!("page_p{i}_")).await;
        let response = app
            .clone()
            .oneshot(post_json_request("/chats/direct", ip, &token_a, json!({ "other_user_id": id })))
            .await
            .unwrap();
        let body = body_json(response).await;
        chat_ids.push(body["chat_id"].as_str().unwrap().to_string());
    }

    let mut seen = std::collections::HashSet::new();
    let mut offset = 0i64;
    loop {
        let uri = format!("/chats?limit=2&offset={offset}");
        let response = app.clone().oneshot(get_request(&uri, ip, Some(&token_a))).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = body_json(response).await;
        let items = body["items"].as_array().unwrap().clone();
        assert!(items.len() <= 2);
        for item in &items {
            assert!(seen.insert(item["chat_id"].as_str().unwrap().to_string()), "duplicate chat across pages");
        }
        match body["next_offset"].as_i64() {
            Some(next) => offset = next,
            None => break,
        }
    }

    for chat_id in &chat_ids {
        assert!(seen.contains(chat_id), "missing {chat_id} across pages");
    }
}

#[tokio::test]
async fn list_chats_orders_by_last_message_at_desc_then_chat_id() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, id_a, dev_a) = register_user(&app, ip, "ord_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&app, ip, "ord_b_").await;
    let (_token_c, id_c, _dev_c) = register_user(&app, ip, "ord_c_").await;
    let (_token_d, id_d, _dev_d) = register_user(&app, ip, "ord_d_").await;

    let create = |other_id: String, app: axum::Router, token: String| async move {
        let response = app
            .oneshot(post_json_request("/chats/direct", ip, &token, json!({ "other_user_id": other_id })))
            .await
            .unwrap();
        body_json(response).await["chat_id"].as_str().unwrap().to_string()
    };

    let chat_with_b = create(id_b, app.clone(), token_a.clone()).await;
    let chat_with_c = create(id_c, app.clone(), token_a.clone()).await;
    let chat_with_d = create(id_d, app.clone(), token_a.clone()).await;

    // Give chat_with_c an actual message so its last_message_at is populated
    // via the existing `messages_touch_chat` trigger, real Postgres behavior,
    // not something faked in the test.
    sqlx::query(
        "INSERT INTO messages (chat_id, sender_id, sender_device_id, client_message_id, ciphertext) \
         VALUES ($1, $2, $3, $4, $5)",
    )
    .bind(Uuid::parse_str(&chat_with_c).unwrap())
    .bind(Uuid::parse_str(&id_a).unwrap())
    .bind(dev_a)
    .bind(Uuid::new_v4())
    .bind(b"hello".as_slice())
    .execute(&state.db)
    .await
    .unwrap();

    let response = app.oneshot(get_request("/chats", ip, Some(&token_a))).await.unwrap();
    let body = body_json(response).await;
    let items = body["items"].as_array().unwrap();
    assert_eq!(items.len(), 3);

    // chat_with_c has a real last_message_at, so it must sort first.
    assert_eq!(items[0]["chat_id"], chat_with_c);
    assert!(!items[0]["last_message_at"].is_null());

    // The remaining two both have NULL last_message_at; tie-break is chat_id
    // DESC (matches Uuid's byte-wise Ord, which equals Postgres UUID order).
    let mut null_pair = vec![chat_with_b.clone(), chat_with_d.clone()];
    null_pair.sort_by(|a, b| Uuid::parse_str(b).unwrap().cmp(&Uuid::parse_str(a).unwrap()));
    assert_eq!(items[1]["chat_id"], null_pair[0]);
    assert_eq!(items[2]["chat_id"], null_pair[1]);
}

#[tokio::test]
async fn revoked_session_cannot_access_chats() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "revchat_a_").await;

    let logout_response = app.clone().oneshot(post_request("/auth/logout", ip, &token_a)).await.unwrap();
    assert_eq!(logout_response.status(), StatusCode::NO_CONTENT);

    let response = app.oneshot(get_request("/chats", ip, Some(&token_a))).await.unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    let body = body_json(response).await;
    assert_eq!(body["error"]["code"], "session_revoked");
}

#[tokio::test]
async fn activity_is_only_relayed_to_chat_members_with_a_live_session() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _, device_a) = register_user(&app, ip, "activity_a_").await;
    let (_, id_b, device_b) = register_user(&app, ip, "activity_b_").await;
    let (token_c, _, _) = register_user(&app, ip, "activity_c_").await;
    let response = app.clone().oneshot(post_json_request("/chats/direct", ip, &token_a, json!({"other_user_id":id_b}))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let chat = Uuid::parse_str(body_json(response).await["chat_id"].as_str().unwrap()).unwrap();
    let claims = messenger_server::auth::jwt::decode_access_token(&state.config, &token_a).unwrap();
    let auth = messenger_server::auth::middleware::AuthUser { user_id: claims.sub, device_id: device_a, session_id: claims.session_id };
    let claims_c = messenger_server::auth::jwt::decode_access_token(&state.config, &token_c).unwrap();
    let outsider = messenger_server::auth::middleware::AuthUser { user_id: claims_c.sub, device_id: claims_c.device_id, session_id: claims_c.session_id };
    let (_, mut inbox) = state.ws_registry.register(device_b).await;
    assert!(matches!(messenger_server::websocket::relay_activity(&state, &outsider, chat, "typing".into()).await,Err(messenger_server::error::AppError::Conflict(_))));
    assert!(inbox.try_recv().is_err());
    messenger_server::websocket::relay_activity(&state, &auth, chat, "unsupported".into()).await.unwrap();
    assert!(inbox.try_recv().is_err());
    messenger_server::websocket::relay_activity(&state, &auth, chat, "typing".into()).await.unwrap();
    let event: Value = serde_json::from_str(&inbox.try_recv().expect("peer activity")).unwrap();
    assert_eq!(event["type"], "chat.activity");
    assert_eq!(event["activity"], "typing");
    messenger_server::websocket::relay_activity(&state, &auth, chat, "uploading".into()).await.unwrap();
    let upload: Value = serde_json::from_str(&inbox.try_recv().expect("upload activity")).unwrap();
    assert_eq!(upload["activity"], "uploading");
    assert!(event.get("text").is_none());
    sqlx::query("UPDATE sessions SET revoked_at = NOW() WHERE id = $1").bind(auth.session_id).execute(&state.db).await.unwrap();
    messenger_server::websocket::relay_activity(&state, &auth, chat, "recording".into()).await.unwrap();
    assert!(inbox.try_recv().is_err());
}

#[tokio::test]
async fn activity_survives_refresh_but_not_membership_loss_or_logout() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let response = app.clone().oneshot(register_request(ip, &unique_username("rotate_activity_"), "Test User")).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let credentials = body_json(response).await;
    let token = credentials["access_token"].as_str().unwrap();
    let mut refresh = credentials["refresh_token"].as_str().unwrap().to_owned();
    let (_, peer, peer_device) = register_user(&app, ip, "rotate_peer_").await;
    let response = app.clone().oneshot(post_json_request("/chats/direct", ip, token, json!({"other_user_id":peer}))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let chat = Uuid::parse_str(body_json(response).await["chat_id"].as_str().unwrap()).unwrap();
    let claims = messenger_server::auth::jwt::decode_access_token(&state.config, token).unwrap();
    // Keep the exact identity captured by the already-open socket.
    let auth = messenger_server::auth::middleware::AuthUser { user_id: claims.sub, device_id: claims.device_id, session_id: claims.session_id };
    let (_, mut inbox) = state.ws_registry.register(peer_device).await;
    let mut current_token = token.to_owned();
    for activity in ["typing", "recording", "uploading", "idle"] {
        let response = app.clone().oneshot(post_json_request("/auth/refresh", ip, &current_token,
            json!({"refresh_token":refresh,"request_id":Uuid::new_v4()}))).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let rotated = body_json(response).await;
        current_token = rotated["access_token"].as_str().unwrap().to_owned();
        refresh = rotated["refresh_token"].as_str().unwrap().to_owned();
        messenger_server::websocket::relay_activity(&state, &auth, chat, activity.into()).await.unwrap();
        let event: Value = serde_json::from_str(&inbox.try_recv().expect("activity across refresh chain")).unwrap();
        assert_eq!(event["activity"], activity);
        assert_eq!(event["user_id"], auth.user_id.to_string());
    }
    sqlx::query("UPDATE chat_members SET left_at = NOW() WHERE chat_id = $1 AND user_id = $2")
        .bind(chat).bind(auth.user_id).execute(&state.db).await.unwrap();
    messenger_server::websocket::relay_activity(&state, &auth, chat, "typing".into()).await.unwrap();
    assert!(inbox.try_recv().is_err());
    sqlx::query("UPDATE chat_members SET left_at = NULL WHERE chat_id = $1 AND user_id = $2")
        .bind(chat).bind(auth.user_id).execute(&state.db).await.unwrap();
    let response = app.clone().oneshot(post_request("/auth/logout", ip, &current_token)).await.unwrap();
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    messenger_server::websocket::relay_activity(&state, &auth, chat, "typing".into()).await.unwrap();
    assert!(inbox.try_recv().is_err());
}

#[tokio::test]
async fn feedback_persists_once_and_requires_its_owner() {
    let state = spawn_state().await;
    let db = state.db.clone();
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _, _) = register_user(&app, ip, "feedback").await;
    let id = Uuid::new_v4();
    let body = json!({"id":id,"description":"Video preview is missing","platform":"ios","app_version":"0.2.0","os_version":"18","screenshots":[]});
    for _ in 0..2 {
        let response = app.clone().oneshot(post_json_request("/feedback",ip,&token,body.clone())).await.unwrap();
        assert_eq!(response.status(),StatusCode::OK);
        assert_eq!(body_json(response).await["id"],id.to_string());
    }
    let stored: i64 = sqlx::query_scalar("SELECT count(*) FROM bug_reports WHERE id=$1").bind(id).fetch_one(&db).await.unwrap();
    assert_eq!(stored, 1);
    let other_ip=connect_info();
    let (other,_,_)=register_user(&app,other_ip,"feedbackother").await;
    let response=app.clone().oneshot(post_json_request("/feedback",other_ip,&other,body.clone())).await.unwrap();
    assert!(!response.status().is_success());
    let response=app.clone().oneshot(post_json_request("/feedback",ip,"invalid",body)).await.unwrap();
    assert_eq!(response.status(),StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn feedback_allows_more_than_five_reports_and_search_excludes_self() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, user, _) = register_user(&app, ip, "feedback_search_").await;
    for _ in 0..6 {
        let report = json!({"id":Uuid::new_v4(),"description":"Another alpha issue","platform":"ios","app_version":"0.2","os_version":"18","screenshots":[]});
        assert_eq!(app.clone().oneshot(post_json_request("/feedback", ip, &token, report)).await.unwrap().status(), StatusCode::OK);
    }
    let response = app.oneshot(get_request("/users/search?q=feedback_search_", ip, Some(&token))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert!(body["items"].as_array().unwrap().iter().all(|item| item["id"] != user.to_string()));
}

#[tokio::test]
async fn draft_relay_is_private_bounded_ephemeral_and_session_scoped() {
    use messenger_server::websocket::{relay_draft, protocol::DraftEnvelope};
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token, _, device) = register_user(&app, ip, "draft_a_").await;
    let (_, peer, peer_device) = register_user(&app, ip, "draft_b_").await;
    let (other_token, _, other_device) = register_user(&app, ip, "draft_c_").await;
    let response = app.clone().oneshot(post_json_request("/chats/direct", ip, &token, json!({"other_user_id":peer}))).await.unwrap();
    let chat = Uuid::parse_str(body_json(response).await["chat_id"].as_str().unwrap()).unwrap();
    let claims = messenger_server::auth::jwt::decode_access_token(&state.config, &token).unwrap();
    let auth = messenger_server::auth::middleware::AuthUser { user_id: claims.sub, device_id: device, session_id: claims.session_id };
    let other = messenger_server::auth::jwt::decode_access_token(&state.config, &other_token).unwrap();
    let outsider = messenger_server::auth::middleware::AuthUser { user_id: other.sub, device_id: other_device, session_id: other.session_id };
    let (_, mut inbox) = state.ws_registry.register(peer_device).await;
    let (_, mut outside) = state.ws_registry.register(other_device).await;
    let envelope = DraftEnvelope { recipient_device_id: peer_device, message_type: 1, ciphertext: "opaque-key".into() };
    let stream = Uuid::new_v4();
    assert!(matches!(relay_draft(&state, &outsider, chat, stream, 1, "ciphertext".into(), vec![envelope.clone()]).await,Err(messenger_server::error::AppError::Conflict(_))));
    assert!(inbox.try_recv().is_err());
    relay_draft(&state, &auth, chat, stream, 2, "x".repeat(16385), vec![envelope.clone()]).await.unwrap();
    assert!(inbox.try_recv().is_err());
    let wrong_target = DraftEnvelope { recipient_device_id: other_device, ..envelope.clone() };
    relay_draft(&state, &auth, chat, stream, 3, "ciphertext".into(), vec![envelope.clone(), envelope.clone(), wrong_target]).await.unwrap();
    let event: Value = serde_json::from_str(&inbox.try_recv().unwrap()).unwrap();
    assert_eq!(event["type"], "chat.draft");
    assert_eq!(event["user_id"], auth.user_id.to_string());
    assert_eq!(event["payload"], "ciphertext");
    assert!(event.get("text").is_none());
    assert!(inbox.try_recv().is_err());
    assert!(outside.try_recv().is_err());
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM messages WHERE chat_id = $1").bind(chat).fetch_one(&state.db).await.unwrap();
    assert_eq!(count, 0);
    sqlx::query("UPDATE sessions SET revoked_at = NOW() WHERE id = $1").bind(auth.session_id).execute(&state.db).await.unwrap();
    relay_draft(&state, &auth, chat, stream, 4, "ciphertext".into(), vec![envelope]).await.unwrap();
    assert!(inbox.try_recv().is_err());
}

#[tokio::test]
async fn chat_mute_and_unmute_allow_sending() {
    let state = spawn_state().await;
    let db = state.db.clone();
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (alice_token, _, _alice_device) = register_user(&app, ip, "mute_alice_").await;
    let (_bob_token, bob_id, bob_device) = register_user(&app, ip, "mute_bob_").await;
    let create_chat = app
        .clone()
        .oneshot(post_json_request("/chats/direct", ip, &alice_token, json!({ "other_user_id": bob_id })))
        .await
        .unwrap();
    assert_eq!(create_chat.status(), StatusCode::OK);
    let chat_id = body_json(create_chat).await["chat_id"].as_str().unwrap().to_string();

    let mute_body = json!({});
    let mute_url = format!("/chats/{chat_id}/mute");
    let response = app.clone().oneshot(post_json_request(&mute_url, ip, &alice_token, mute_body)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    // Expiration changes the public state without requiring a cleanup job.
    sqlx::query("UPDATE chat_preferences SET muted_until = now() - interval '1 second' WHERE chat_id = $1")
        .bind(Uuid::parse_str(&chat_id).unwrap()).execute(&db).await.unwrap();
    let response = app.clone().oneshot(get_request(&format!("/chats/{chat_id}"), ip, Some(&alice_token))).await.unwrap();
    assert_eq!(body_json(response).await["is_muted"], false);
    let response = app.clone().oneshot(post_json_request(&mute_url, ip, &alice_token, json!({}))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    let send_body = json!({
        "client_message_id": Uuid::new_v4(),
        "envelopes": [
            {
                "recipient_device_id": bob_device,
                "message_type": 1,
                "ciphertext": "dGVzdA=="
            }
        ]
    });
    let send_url = format!("/chats/{chat_id}/messages");
    let response = app
        .clone()
        .oneshot(post_json_request(&send_url, ip, &alice_token, send_body))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    let unmute_url = format!("/chats/{chat_id}/unmute");
    let response = app.clone().oneshot(post_request(&unmute_url, ip, &alice_token)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    let send_body = json!({
        "client_message_id": Uuid::new_v4(),
        "envelopes": [
            {
                "recipient_device_id": bob_device,
                "message_type": 1,
                "ciphertext": "dGVzdA=="
            }
        ]
    });
    let response = app
        .clone()
        .oneshot(post_json_request(&send_url, ip, &alice_token, send_body))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn chat_block_prevents_peer_messages_until_unblocked() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (alice_token, alice_id, alice_device) = register_user(&app, ip, "block_alice_").await;
    let (bob_token, bob_id, bob_device) = register_user(&app, ip, "block_bob_").await;

    let create_chat = app
        .clone()
        .oneshot(post_json_request("/chats/direct", ip, &alice_token, json!({ "other_user_id": bob_id })))
        .await
        .unwrap();
    assert_eq!(create_chat.status(), StatusCode::OK);
    let chat_id = body_json(create_chat).await["chat_id"].as_str().unwrap().to_string();
    let block_url = format!("/chats/{chat_id}/block");
    let block_body = json!({ "blocked_user_id": alice_id });
    let response = app
        .clone()
        .oneshot(post_json_request(&block_url, ip, &bob_token, block_body))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    let send_url = format!("/chats/{chat_id}/messages");
    let send_body = json!({
        "client_message_id": Uuid::new_v4(),
        "envelopes": [
            {
                "recipient_device_id": bob_device,
                "message_type": 1,
                "ciphertext": "dGVzdA=="
            }
        ]
    });
    let response = app
        .clone()
        .oneshot(post_json_request(&send_url, ip, &alice_token, send_body))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::CONFLICT);

    // Blocking is enforced for calls and ephemeral events as well as messages.
    for (token, target) in [(&alice_token, &bob_id), (&bob_token, &alice_id)] {
        let response = app.clone().oneshot(post_json_request("/calls", ip, token,
            json!({"callee_user_id": target, "media_kind": "audio"}))).await.unwrap();
        assert_eq!(response.status(), StatusCode::CONFLICT);
    }
    let claims = messenger_server::auth::jwt::decode_access_token(&state.config, &alice_token).unwrap();
    let auth = messenger_server::auth::middleware::AuthUser {
        user_id: claims.sub, device_id: alice_device, session_id: claims.session_id,
    };
    let chat = Uuid::parse_str(&chat_id).unwrap();
    let (_, mut inbox) = state.ws_registry.register(bob_device).await;
    assert!(messenger_server::websocket::relay_activity(&state, &auth, chat, "typing".into()).await.is_err());
    let envelope = messenger_server::websocket::protocol::DraftEnvelope {
        recipient_device_id: bob_device, message_type: 1, ciphertext: "opaque".into(),
    };
    assert!(messenger_server::websocket::relay_draft(&state, &auth, chat,
        Uuid::new_v4(), 1, "opaque".into(), vec![envelope]).await.is_err());
    assert!(inbox.try_recv().is_err());

    let unblock_url = format!("/chats/{chat_id}/unblock");
    let response = app
        .clone()
        .oneshot(post_request(&unblock_url, ip, &bob_token))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    let send_body = json!({
        "client_message_id": Uuid::new_v4(),
        "envelopes": [
            {
                "recipient_device_id": bob_device,
                "message_type": 1,
                "ciphertext": "dGVzdA=="
            }
        ]
    });
    let response = app
        .clone()
        .oneshot(post_json_request(&send_url, ip, &alice_token, send_body))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn chat_report_rejects_invalid_reason_and_persists_valid_report() {
    let state = spawn_state().await;
    let db = state.db.clone();
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (alice_token, _alice_id, _alice_device) = register_user(&app, ip, "report_alice_").await;
    let (_bob_token, bob_id, _bob_device) = register_user(&app, ip, "report_bob_").await;

    let create_chat = app
        .clone()
        .oneshot(post_json_request("/chats/direct", ip, &alice_token, json!({ "other_user_id": bob_id })))
        .await
        .unwrap();
    assert_eq!(create_chat.status(), StatusCode::OK);
    let chat_id = body_json(create_chat).await["chat_id"].as_str().unwrap().to_string();

    let invalid_report_url = format!("/chats/{chat_id}/report");
    let bad = json!({ "reason": "invalid_reason" });
    let response = app
        .clone()
        .oneshot(post_json_request(&invalid_report_url, ip, &alice_token, bad))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);

    let good = json!({
        "reason": "spam",
        "reported_user_id": bob_id,
        "description": "Automated test report"
    });
    let response = app
        .clone()
        .oneshot(post_json_request(&invalid_report_url, ip, &alice_token, good))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM chat_reports WHERE chat_id = $1")
        .bind(Uuid::parse_str(&chat_id).unwrap())
        .fetch_one(&db)
        .await
        .unwrap();
    assert_eq!(count, 1);
}

#[tokio::test]
async fn chat_reports_validate_target_size_and_account_limit() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, user, _) = register_user(&app, ip, "report_guard_a_").await;
    let (_, peer, _) = register_user(&app, ip, "report_guard_b_").await;
    let (_, outsider, _) = register_user(&app, ip, "report_guard_c_").await;
    let response = app.clone().oneshot(post_json_request("/chats/direct", ip, &token,
        json!({"other_user_id":peer}))).await.unwrap();
    let chat = body_json(response).await["chat_id"].as_str().unwrap().to_owned();
    let url = format!("/chats/{chat}/report");
    for (body, expected) in [
        (json!({"reason":"spam", "reported_user_id":user}), StatusCode::BAD_REQUEST),
        (json!({"reason":"spam", "reported_user_id":outsider}), StatusCode::NOT_FOUND),
        (json!({"reason":"other", "description":"x".repeat(4001)}), StatusCode::BAD_REQUEST),
    ] {
        let response = app.clone().oneshot(post_json_request(&url, ip, &token, body)).await.unwrap();
        assert_eq!(response.status(), expected);
    }
    for index in 0..11 {
        let response = app.clone().oneshot(post_json_request(&url, ip, &token,
            json!({"reason":"spam", "reported_user_id":peer}))).await.unwrap();
        assert_eq!(response.status(), if index < 10 { StatusCode::OK } else { StatusCode::TOO_MANY_REQUESTS });
    }
}

async fn admin_key_proof(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, purpose: &str,
    key: &ring::signature::Ed25519KeyPair, new_key: Option<&ring::signature::Ed25519KeyPair>) -> Value {
    use base64::Engine;
    use ring::signature::KeyPair;
    let b64=base64::engine::general_purpose::URL_SAFE_NO_PAD;
    let public=b64.encode(new_key.unwrap_or(key).public_key().as_ref());
    let response=app.clone().oneshot(post_json_request("/admin/key-challenge",ip,token,json!({"purpose":purpose,"public_key":public}))).await.unwrap();
    assert_eq!(response.status(),StatusCode::OK);
    let data=body_json(response).await;
    let text=format!("COCO-ADMIN-PROOF-V1\n{}\n{}\n{}\n{}\n{}\n{}\n{}",
        data["server_id"].as_str().unwrap(),data["user_id"].as_str().unwrap(),data["auth_session"].as_str().unwrap(),
        data["challenge_id"].as_str().unwrap(),purpose,public,data["nonce"].as_str().unwrap());
    let mut proof=json!({"challenge_id":data["challenge_id"],"signature":b64.encode(key.sign(text.as_bytes()).as_ref())});
    if let Some(new)=new_key { proof["new_signature"]=json!(b64.encode(new.sign(text.as_bytes()).as_ref())); }
    proof
}

#[tokio::test]
async fn administration_is_private_second_factor_bound_expiring_and_revocable() {
    std::env::set_var("COCO_ADMIN_KEY", "11".repeat(32));
    let state=spawn_state().await;
    let app=messenger_server::app(state.clone());
    let ip=connect_info();
    let (token,user,_) = register_user(&app,ip,"admin_owner_").await;
    let (other,other_id,_) = register_user(&app,ip,"admin_normal_").await;
    let uid=Uuid::parse_str(&user).unwrap();
    let own=body_json(app.clone().oneshot(get_request("/users/me",ip,Some(&token))).await.unwrap()).await;
    assert!(own.get("admin_role").is_none());
    for path in ["/admin/status","/admin/reports","/admin/feedback","/admin/audit"] {
        assert_eq!(app.clone().oneshot(get_request(path,ip,Some(&token))).await.unwrap().status(),StatusCode::NOT_FOUND);
    }
    let credentials=json!({"password":"correct-horse-battery","code":"000000"});
    assert_eq!(app.clone().oneshot(post_json_request("/admin/setup",ip,&token,credentials.clone())).await.unwrap().status(),StatusCode::NOT_FOUND);
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'owner')").bind(uid).execute(&state.db).await.unwrap();
    let own=body_json(app.clone().oneshot(get_request("/users/me",ip,Some(&token))).await.unwrap()).await;
    assert_eq!(own["admin_role"],"owner");
    assert_eq!(app.clone().oneshot(post_json_request("/admin/setup",ip,&token,json!({"password":"wrong"}))).await.unwrap().status(),StatusCode::UNAUTHORIZED);
    assert_eq!(app.clone().oneshot(get_request("/admin/reports",ip,Some(&token))).await.unwrap().status(),StatusCode::NOT_FOUND);
    let setup=app.clone().oneshot(post_json_request("/admin/setup",ip,&token,credentials.clone())).await.unwrap();
    assert_eq!(setup.status(),StatusCode::OK);
    assert_eq!(setup.headers()["cache-control"],"no-store");
    let setup=body_json(setup).await;
    let secret=totp_rs::Secret::try_from_base32(setup["secret"].as_str().unwrap()).unwrap();
    let otp=totp_rs::Builder::new().with_secret(secret).build().unwrap();
    let key=ring::signature::Ed25519KeyPair::from_seed_unchecked(&[41;32]).unwrap();
    let mut login=json!({"password":"correct-horse-battery","code":otp.generate_current().to_string()});
    assert_eq!(app.clone().oneshot(post_json_request("/admin/unlock",ip,&token,login.clone())).await.unwrap().status(),StatusCode::UNAUTHORIZED,"paper-key proof is mandatory");
    login["key_proof"]=admin_key_proof(&app,ip,&token,"unlock",&key,None).await;
    let mut wrong=login.clone(); wrong["key_proof"]["signature"]=json!("invalid");
    assert_eq!(app.clone().oneshot(post_json_request("/admin/unlock",ip,&token,wrong)).await.unwrap().status(),StatusCode::UNAUTHORIZED,"invalid signature rejected");
    sqlx::query("UPDATE admin_key_challenges SET expires_at=now()-interval '1 second' WHERE user_id=$1").bind(uid).execute(&state.db).await.unwrap();
    assert_eq!(app.clone().oneshot(post_json_request("/admin/unlock",ip,&token,login.clone())).await.unwrap().status(),StatusCode::UNAUTHORIZED,"expired proof rejected");
    login["key_proof"]=admin_key_proof(&app,ip,&token,"unlock",&key,None).await;
    let unlocked=app.clone().oneshot(post_json_request("/admin/unlock",ip,&token,login.clone())).await.unwrap();
    assert_eq!(unlocked.status(),StatusCode::OK);
    let body=body_json(unlocked).await;
    let admin_token=body["token"].as_str().unwrap();
    let recovery=body["recovery_codes"][0].as_str().unwrap();
    assert_eq!(body["recovery_codes"].as_array().unwrap().len(),8);
    let header=|mut request:Request<Body>, value:&str| {request.headers_mut().insert("x-coco-admin",value.parse().unwrap());request};
    assert_eq!(app.clone().oneshot(header(get_request("/admin/reports",ip,Some(&token)),admin_token)).await.unwrap().status(),StatusCode::OK);
    assert_eq!(app.clone().oneshot(header(get_request("/admin/reports",ip,Some(&other)),admin_token)).await.unwrap().status(),StatusCode::NOT_FOUND);
    sqlx::query("UPDATE server_administrators SET role='moderator' WHERE user_id=$1").bind(uid).execute(&state.db).await.unwrap();
    assert_eq!(app.clone().oneshot(header(get_request("/admin/audit",ip,Some(&token)),admin_token)).await.unwrap().status(),StatusCode::NOT_FOUND,"moderators cannot read owner audit");
    sqlx::query("UPDATE server_administrators SET role='owner' WHERE user_id=$1").bind(uid).execute(&state.db).await.unwrap();
    assert_eq!(app.clone().oneshot(header(post_json_request(&format!("/admin/users/{user}/suspension"),ip,&token,json!({"blocked":true,"reason":"test"})),admin_token)).await.unwrap().status(),StatusCode::NOT_FOUND,"cannot suspend own admin account");
    assert_eq!(app.clone().oneshot(post_json_request("/admin/unlock",ip,&token,login.clone())).await.unwrap().status(),StatusCode::UNAUTHORIZED,"signature replay rejected");
    login["key_proof"]=admin_key_proof(&app,ip,&token,"unlock",&key,None).await;
    assert_eq!(app.clone().oneshot(post_json_request("/admin/unlock",ip,&token,login)).await.unwrap().status(),StatusCode::UNAUTHORIZED,"OTP replay rejected with fresh signature");
    sqlx::query("UPDATE admin_sessions SET last_used=now()-interval '12 hours 1 minute' WHERE user_id=$1").bind(uid).execute(&state.db).await.unwrap();
    assert_eq!(app.clone().oneshot(header(get_request("/admin/reports",ip,Some(&token)),admin_token)).await.unwrap().status(),StatusCode::NOT_FOUND);
    let mut recovery_body=json!({"password":"correct-horse-battery","code":recovery});
    recovery_body["key_proof"]=admin_key_proof(&app,ip,&token,"unlock",&key,None).await;
    let recovered=app.clone().oneshot(post_json_request("/admin/unlock",ip,&token,recovery_body.clone())).await.unwrap();
    assert_eq!(recovered.status(),StatusCode::OK);
    let recovered=body_json(recovered).await;
    let new_token=recovered["token"].as_str().unwrap();
    recovery_body["key_proof"]=admin_key_proof(&app,ip,&token,"unlock",&key,None).await;
    assert_eq!(app.clone().oneshot(post_json_request("/admin/unlock",ip,&token,recovery_body)).await.unwrap().status(),StatusCode::UNAUTHORIZED,"recovery code is single use even with fresh key proof");
    let suspension=format!("/admin/users/{other_id}/suspension");
    assert_eq!(app.clone().oneshot(header(post_json_request(&suspension,ip,&token,json!({"blocked":true,"reason":"test"})),new_token)).await.unwrap().status(),StatusCode::OK);
    assert_eq!(app.clone().oneshot(get_request("/users/me",ip,Some(&other))).await.unwrap().status(),StatusCode::UNAUTHORIZED);
    let replacement=ring::signature::Ed25519KeyPair::from_seed_unchecked(&[42;32]).unwrap();
    let rotation=admin_key_proof(&app,ip,&token,"rotate",&key,Some(&replacement)).await;
    assert_eq!(app.clone().oneshot(header(post_json_request("/admin/key-rotate",ip,&token,rotation.clone()),new_token)).await.unwrap().status(),StatusCode::OK,"old and new keys authorize replacement");
    assert_eq!(app.clone().oneshot(header(get_request("/admin/reports",ip,Some(&token)),new_token)).await.unwrap().status(),StatusCode::NOT_FOUND,"rotation invalidates every old admin session");
    assert_eq!(app.clone().oneshot(header(post_json_request("/admin/key-rotate",ip,&token,rotation),new_token)).await.unwrap().status(),StatusCode::NOT_FOUND,"rotation replay rejected");
    use ring::signature::KeyPair;
    let stored:Vec<u8>=sqlx::query_scalar("SELECT signing_public_key FROM server_administrators WHERE user_id=$1").bind(uid).fetch_one(&state.db).await.unwrap();
    assert_eq!(stored,replacement.public_key().as_ref());
    sqlx::query("DELETE FROM server_administrators WHERE user_id=$1").bind(uid).execute(&state.db).await.unwrap();
    assert_eq!(app.clone().oneshot(header(get_request("/admin/reports",ip,Some(&token)),new_token)).await.unwrap().status(),StatusCode::NOT_FOUND,"revocation is immediate");
    assert_eq!(app.clone().oneshot(get_request("/users/me",ip,Some(&token))).await.unwrap().status(),StatusCode::OK,"ordinary login remains active");
}


#[tokio::test]
async fn account_recovery_is_optional_single_use_and_revokes_sessions() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token, user, device) = register_user(&app, ip, "recover").await;
    let user_id = Uuid::parse_str(&user).unwrap();
    let session: Uuid = sqlx::query_scalar("SELECT id FROM sessions WHERE user_id=$1 AND revoked_at IS NULL")
        .bind(user_id).fetch_one(&state.db).await.unwrap();
    let stale_auth = messenger_server::auth::AuthUser { user_id,device_id:device,session_id:session };
    let (_,peer,_) = register_user(&app,ip,"recovery_peer").await;
    assert_eq!(app.clone().oneshot(post_json_request("/chats/direct",ip,&token,json!({"other_user_id":peer}))).await.unwrap().status(),StatusCode::OK);
    let call = app.clone().oneshot(post_json_request("/calls",ip,&token,json!({"callee_user_id":peer,"media_kind":"audio"}))).await.unwrap();
    assert_eq!(call.status(),StatusCode::OK);
    // Recovery must not remove the independent administrative factors.
    sqlx::query("INSERT INTO server_administrators(user_id,role,secret,signing_public_key) VALUES($1,'owner',$2,$3)")
        .bind(user_id).bind(vec![7u8;48]).bind(vec![9u8;32]).execute(&state.db).await.unwrap();
    let username: String = sqlx::query_scalar("SELECT username FROM users WHERE id=$1")
        .bind(user_id).fetch_one(&state.db).await.unwrap();
    let status = app.clone().oneshot(get_request("/auth/recovery/status",ip,Some(&token))).await.unwrap();
    assert_eq!(status.status(),StatusCode::OK);
    assert_eq!(body_json(status).await["enabled"],false);
    let bad = app.clone().oneshot(post_json_request("/auth/recovery/code",ip,&token,json!({"password":"wrong"}))).await.unwrap();
    assert_eq!(bad.status(),StatusCode::UNAUTHORIZED);
    let first = app.clone().oneshot(post_json_request("/auth/recovery/code",ip,&token,json!({"password":"correct-horse-battery"}))).await.unwrap();
    assert_eq!(first.status(),StatusCode::OK);
    assert_eq!(first.headers()["cache-control"],"no-store");
    let old = body_json(first).await["code"].as_str().unwrap().to_owned();
    let second = app.clone().oneshot(post_json_request("/auth/recovery/code",ip,&token,json!({"password":"correct-horse-battery"}))).await.unwrap();
    let code = body_json(second).await["code"].as_str().unwrap().to_owned();
    assert_ne!(code,old);
    let stored: Vec<u8> = sqlx::query_scalar("SELECT code_hash FROM account_recovery WHERE user_id=$1")
        .bind(user_id).fetch_one(&state.db).await.unwrap();
    assert_eq!(stored.len(),32);
    let reset = |name: &str, secret: &str| post_json_request("/auth/recovery/reset",ip,"",
        json!({"username":name,"code":secret,"new_password":"replacement-passphrase"}));
    assert_eq!(app.clone().oneshot(reset(&username,&old)).await.unwrap().status(),StatusCode::UNAUTHORIZED);
    assert_eq!(app.clone().oneshot(reset("does-not-exist",&code)).await.unwrap().status(),StatusCode::UNAUTHORIZED);
    let (a,b) = tokio::join!(app.clone().oneshot(reset(&username,&code)), app.clone().oneshot(reset(&username,&code)));
    let mut statuses = [a.unwrap().status().as_u16(), b.unwrap().status().as_u16()];
    statuses.sort();
    assert_eq!(statuses,[204,401],"only one concurrent recovery consumes the code");
    assert_eq!(app.clone().oneshot(get_request("/users/me",ip,Some(&token))).await.unwrap().status(),StatusCode::UNAUTHORIZED);
    let active: i64 = sqlx::query_scalar("SELECT count(*) FROM sessions WHERE user_id=$1 AND revoked_at IS NULL")
        .bind(user_id).fetch_one(&state.db).await.unwrap();
    assert_eq!(active,0);
    let active_devices: i64 = sqlx::query_scalar("SELECT count(*) FROM devices WHERE user_id=$1 AND revoked_at IS NULL")
        .bind(user_id).fetch_one(&state.db).await.unwrap();
    assert_eq!(active_devices,0);
    let active_calls: i64 = sqlx::query_scalar("SELECT count(*) FROM calls WHERE caller_user_id=$1 AND state<>'ended'")
        .bind(user_id).fetch_one(&state.db).await.unwrap();
    assert_eq!(active_calls,0);
    let busy_rows: i64 = sqlx::query_scalar("SELECT count(*) FROM call_participants_active WHERE user_id=$1")
        .bind(user_id).fetch_one(&state.db).await.unwrap();
    assert_eq!(busy_rows,0);
    let factors: (String,Vec<u8>,Vec<u8>) = sqlx::query_as("SELECT role,secret,signing_public_key FROM server_administrators WHERE user_id=$1")
        .bind(user_id).fetch_one(&state.db).await.unwrap();
    assert_eq!(factors,("owner".into(),vec![7u8;48],vec![9u8;32]));
    let login = |password: &str| post_json_request("/auth/login",ip,"",json!({
        "username":username,"password":password,"installation_id":Uuid::new_v4(),"device_platform":"android"
    }));
    assert_eq!(app.clone().oneshot(login("correct-horse-battery")).await.unwrap().status(),StatusCode::UNAUTHORIZED);
    let logged = app.clone().oneshot(login("replacement-passphrase")).await.unwrap();
    assert_eq!(logged.status(),StatusCode::OK);
    let login_body = body_json(logged).await;
    let new_token = login_body["access_token"].as_str().unwrap().to_owned();
    let new_device = Uuid::parse_str(login_body["device_id"].as_str().unwrap()).unwrap();
    // Bypass HTTP extraction to model a request admitted BEFORE recovery.
    let late = messenger_server::devices::handlers::revoke_other_devices(axum::extract::State(state.clone()),stale_auth.clone()).await;
    assert!(matches!(late,Err(messenger_server::error::AppError::SessionRevoked)));
    let late = messenger_server::devices::handlers::revoke_device(&state,&stale_auth,new_device,"device_revoked").await;
    assert!(matches!(late,Err(messenger_server::error::AppError::SessionRevoked)));
    let late_call = messenger_server::calls::handlers::create_call(axum::extract::State(state.clone()),messenger_server::net::ClientIp(ip.0.ip()),stale_auth,
        axum::Json(messenger_server::calls::models::CreateCallRequest { callee_user_id:Uuid::parse_str(&peer).unwrap(),media_kind:"audio".into() })).await;
    assert!(matches!(late_call,Err(messenger_server::error::AppError::SessionRevoked)));
    assert_eq!(app.clone().oneshot(get_request("/users/me",ip,Some(&new_token))).await.unwrap().status(),StatusCode::OK);

    let status = app.clone().oneshot(get_request("/auth/recovery/status",ip,Some(&new_token))).await.unwrap();
    assert_eq!(body_json(status).await["enabled"],false,"used code is invalidated");
    let issued = app.clone().oneshot(post_json_request("/auth/recovery/code",ip,&new_token,json!({"password":"replacement-passphrase"}))).await.unwrap();
    let disabled_code = body_json(issued).await["code"].as_str().unwrap().to_owned();
    assert_eq!(app.clone().oneshot(post_json_request("/auth/recovery/disable",ip,&new_token,json!({"password":"replacement-passphrase"}))).await.unwrap().status(),StatusCode::NO_CONTENT);
    assert_eq!(app.clone().oneshot(reset(&username,&disabled_code)).await.unwrap().status(),StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn server_management_permissions_quota_and_cleanup_contract() {
    use axum::extract::{State,Path};
    use axum::Json;
    use messenger_server::{admin,server_management as sm};
    let mut state=spawn_state().await;
    let schema=format!("management_{}",Uuid::new_v4().simple());
    sqlx::query(&format!("CREATE SCHEMA {schema}")).execute(&state.db).await.unwrap();
    sqlx::query(&format!("CREATE TABLE {schema}._sqlx_migrations (LIKE public._sqlx_migrations INCLUDING ALL)")).execute(&state.db).await.unwrap();
    let schema_pool=schema.clone();
    let pool=sqlx::postgres::PgPoolOptions::new().max_connections(5).after_connect(move |conn,_| {
        let command=format!("SET search_path TO {schema_pool},public");
        Box::pin(async move {sqlx::query(&command).execute(conn).await?;Ok(())})
    }).connect(&state.config.database_url).await.unwrap();
    sqlx::migrate!().run(&pool).await.unwrap();
    let original=state.db.clone();state.db=pool;
    let app=messenger_server::app(state.clone());let ip=connect_info();
    let (token,user,_)=register_user(&app,ip,"mg").await;let uid=Uuid::parse_str(&user).unwrap();
    let owner=||admin::Admin{user:uid,role:"owner".into(),token_hash:"test-management-session".into(),auth_session:uuid::Uuid::nil(),channel:admin::AdminChannel::Native};
    let moderator=||admin::Admin{user:uid,role:"moderator".into(),token_hash:"test-management-session".into(),auth_session:uuid::Uuid::nil(),channel:admin::AdminChannel::Native};
    assert!(sm::admin_policy(State(state.clone()),moderator()).await.is_err());
    for path in ["/admin/server-policy","/admin/storage"] {assert!(!app.clone().oneshot(get_request(path,ip,Some(&token))).await.unwrap().status().is_success());}
    let p=sm::Policy{name:"Family".into(),operator:"Owner".into(),contact:"support@example.test".into(),rules:"Private".into(),retention:"Until deletion".into(),max_file_bytes:12000,user_quota_bytes:18000,storage_budget_bytes:50000};
    let _ = sm::save_policy(State(state.clone()),owner(),Json(p)).await.unwrap();
    let public=body_json(app.clone().oneshot(get_request("/server-info",ip,None)).await.unwrap()).await;
    assert_eq!(public["name"],"Family");assert!(public.get("storage_budget_bytes").is_none());
    let aid1=Uuid::new_v4();let aid2=Uuid::new_v4();
    let request=|id,size|post_json_request("/attachments",ip,&token,json!({"client_attachment_id":id,"encrypted_size":size}));
    let (a,b)=tokio::join!(app.clone().oneshot(request(aid1,12000)),app.clone().oneshot(request(aid2,12000)));
    let a=a.unwrap();let b=b.unwrap();assert_ne!(a.status().is_success(),b.status().is_success());
    let (winner,response)=if a.status().is_success(){(aid1,a)}else{(aid2,b)};
    let upload=body_json(response).await;let attachment=Uuid::parse_str(upload["attachment_id"].as_str().unwrap()).unwrap();
    assert!(app.clone().oneshot(request(winner,12000)).await.unwrap().status().is_success());
    assert!(!app.clone().oneshot(request(Uuid::new_v4(),12001)).await.unwrap().status().is_success());
    let url=upload["upload_url"].as_str().unwrap();
    assert!(!reqwest::Client::new().put(url).body(vec![0u8;12001]).send().await.unwrap().status().is_success());
    assert!(reqwest::Client::new().put(url).body(vec![0u8;12000]).send().await.unwrap().status().is_success());
    sqlx::query("UPDATE attachments SET created_at=now()-interval '2 days',upload_lease_until=now()-interval '1 minute' WHERE id=$1").bind(attachment).execute(&state.db).await.unwrap();
    sqlx::query("UPDATE attachments SET upload_lease_until=now()+interval '1 hour' WHERE id=$1").bind(attachment).execute(&state.db).await.unwrap();
    assert!(sm::preview(State(state.clone()),owner()).await.unwrap().0["items"].as_array().unwrap().is_empty());
    sqlx::query("UPDATE attachments SET upload_lease_until=now()-interval '1 minute' WHERE id=$1").bind(attachment).execute(&state.db).await.unwrap();
    let plan=sm::preview(State(state.clone()),owner()).await.unwrap().0;assert_eq!(plan["items"].as_array().unwrap().len(),1);
    let confirmation=||Json(serde_json::from_value::<sm::Cleanup>(json!({"plan_id":plan["plan_id"]})).unwrap());
    let wrong=admin::Admin{user:uid,role:"owner".into(),token_hash:"different-session".into(),auth_session:uuid::Uuid::nil(),channel:admin::AdminChannel::Native};
    assert!(sm::cleanup(State(state.clone()),wrong,confirmation()).await.is_err());
    sqlx::query("UPDATE attachments SET status='attached' WHERE id=$1").bind(attachment).execute(&state.db).await.unwrap();
    assert_eq!(sm::cleanup(State(state.clone()),owner(),confirmation()).await.unwrap().0["skipped"],1);
    assert_eq!(state.s3.head_object(upload["object_key"].as_str().unwrap()).await.unwrap(),Some(12000));
    sqlx::query("UPDATE attachments SET status='created' WHERE id=$1").bind(attachment).execute(&state.db).await.unwrap();
    let mut unavailable=state.clone();let mut config=state.config.s3.clone();config.endpoint_url="http://127.0.0.1:9".into();
    unavailable.s3=std::sync::Arc::new(messenger_server::storage::S3Client::new(config));
    assert!(messenger_server::attachments::cleanup::delete_abandoned(&unavailable,attachment,Some(uid)).await.is_err());
    let pending:bool=sqlx::query_scalar("SELECT cleanup_pending FROM attachments WHERE id=$1").bind(attachment).fetch_one(&state.db).await.unwrap();assert!(pending);
    assert_eq!(app.clone().oneshot(request(winner,12000)).await.unwrap().status(),StatusCode::CONFLICT);
    assert_eq!(app.clone().oneshot(get_request(&format!("/attachments/{attachment}/download"),ip,Some(&token))).await.unwrap().status(),StatusCode::NOT_FOUND);
    assert_eq!(sm::cleanup(State(state.clone()),owner(),confirmation()).await.unwrap().0["deleted"],1);
    assert_eq!(sm::cleanup(State(state.clone()),owner(),confirmation()).await.unwrap().0["deleted"],0);
    assert_eq!(state.s3.head_object(upload["object_key"].as_str().unwrap()).await.unwrap(),None);
    let (_,peer,_)=register_user(&app,ip,"mp").await;
    let chat=body_json(app.clone().oneshot(post_json_request("/chats/direct",ip,&token,json!({"other_user_id":peer}))).await.unwrap()).await;
    let chatid=chat["chat_id"].as_str().unwrap();
    assert!(app.clone().oneshot(post_json_request(&format!("/chats/{chatid}/report"),ip,&token,json!({"reason":"spam","reported_user_id":peer,"description":"Voluntary excerpt"}))).await.unwrap().status().is_success());
    let report:Uuid=sqlx::query_scalar("SELECT id FROM chat_reports LIMIT 1").fetch_one(&state.db).await.unwrap();
    let review=|status,note,reason|Json(serde_json::from_value::<admin::Review>(json!({"status":status,"note":note,"decision_reason":reason})).unwrap());
    assert!(admin::review(State(state.clone()),moderator(),Path(report),review("resolved","note","")).await.is_err());
    let _ = admin::review(State(state.clone()),moderator(),Path(report),review("in_progress","checking","")).await.unwrap();
    let _ = admin::review(State(state.clone()),moderator(),Path(report),review("resolved","reviewed","Confirmed spam")).await.unwrap();
    let reports=admin::reports(State(state.clone()),moderator(),axum::extract::Query(admin::ReportsQuery::default())).await.unwrap().0;
    assert_eq!(reports["items"][0]["decision_reason"],"Confirmed spam");assert_eq!(reports["items"][0]["review_note"],"reviewed");
    state.db.close().await;sqlx::query(&format!("DROP SCHEMA {schema} CASCADE")).execute(&original).await.unwrap();
}

#[tokio::test]
async fn management_pins_are_private_atomic_and_delete_is_durable() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (a, aid, _) = register_user(&app, ip, "manage_a_").await;
    let (b, bid, _) = register_user(&app, ip, "manage_b_").await;
    let (c, _, _) = register_user(&app, ip, "manage_c_").await;
    let created = body_json(app.clone().oneshot(post_json_request("/chats/direct",ip,&a,json!({"other_user_id":bid}))).await.unwrap()).await;
    let id = created["chat_id"].as_str().unwrap();
    let r = app.clone().oneshot(post_json_request("/chats/pins",ip,&a,json!({"chat_ids":[id],"pinned":true}))).await.unwrap();
    assert_eq!(r.status(),StatusCode::OK);
    assert_eq!(body_json(r).await["chat_ids"],json!([id]));
    let other = body_json(app.clone().oneshot(get_request("/chats/pins",ip,Some(&b))).await.unwrap()).await;
    assert_eq!(other["chat_ids"],json!([]));
    let invalid = app.clone().oneshot(post_json_request("/chats/pins",ip,&a,json!({"chat_ids":[id,Uuid::new_v4()],"pinned":false}))).await.unwrap();
    assert_eq!(invalid.status(),StatusCode::NOT_FOUND);
    let mine = body_json(app.clone().oneshot(get_request("/chats/pins",ip,Some(&a))).await.unwrap()).await;
    assert_eq!(mine["chat_ids"],json!([id]));
    let uri = format!("/chats/{id}/delete");
    assert_eq!(app.clone().oneshot(post_json_request(&uri,ip,&c,json!({"for_everyone":true}))).await.unwrap().status(),StatusCode::NOT_FOUND);
    for _ in 0..2 { assert_eq!(app.clone().oneshot(post_json_request(&uri,ip,&a,json!({"for_everyone":true}))).await.unwrap().status(),StatusCode::OK); }
    for token in [&a,&b] {
        let sync = body_json(app.clone().oneshot(get_request("/sync",ip,Some(token))).await.unwrap()).await;
        assert!(sync["deleted_chat_ids"].as_array().unwrap().contains(&json!(id)));
    }
    let reopened = body_json(app.clone().oneshot(post_json_request("/chats/direct",ip,&b,json!({"other_user_id":aid}))).await.unwrap()).await;
    assert_ne!(reopened["chat_id"],json!(id));
}

#[tokio::test]
async fn personal_chat_deletion_preserves_peer_and_new_messages_on_retry() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (a, aid, adev) = register_user(&app,ip,"clear_a_").await;
    let (b, bid, bdev) = register_user(&app,ip,"clear_b_").await;
    let created = body_json(app.clone().oneshot(post_json_request("/chats/direct",ip,&a,json!({"other_user_id":bid}))).await.unwrap()).await;
    let chat = Uuid::parse_str(created["chat_id"].as_str().unwrap()).unwrap();
    let insert = || async {
        let row: (Uuid,i64) = sqlx::query_as("INSERT INTO messages(chat_id,sender_id,sender_device_id,client_message_id,ciphertext) VALUES($1,$2,$3,$4,$5) RETURNING id,sequence")
            .bind(chat).bind(Uuid::parse_str(&aid).unwrap()).bind(adev).bind(Uuid::new_v4()).bind(vec![1u8]).fetch_one(&state.db).await.unwrap();
        for device in [adev,bdev] {
            sqlx::query("INSERT INTO message_envelopes(message_id,recipient_device_id,message_type,ciphertext) VALUES($1,$2,1,$3)")
                .bind(row.0).bind(device).bind(vec![1u8]).execute(&state.db).await.unwrap();
        }
        row
    };
    let old = insert().await;
    let uri = format!("/chats/{chat}/delete");
    let request = json!({"for_everyone":false,"request_id":Uuid::new_v4()});
    assert_eq!(app.clone().oneshot(post_json_request(&uri,ip,&a,request.clone())).await.unwrap().status(),StatusCode::OK);
    let newer = insert().await;
    assert_eq!(app.clone().oneshot(post_json_request(&uri,ip,&a,request)).await.unwrap().status(),StatusCode::OK);
    let remaining: Vec<Uuid> = sqlx::query_scalar("SELECT message_id FROM message_envelopes WHERE recipient_device_id=$1 ORDER BY message_id")
        .bind(adev).fetch_all(&state.db).await.unwrap();
    assert_eq!(remaining,vec![newer.0]);
    let peer: i64 = sqlx::query_scalar("SELECT count(*) FROM message_envelopes WHERE recipient_device_id=$1").bind(bdev).fetch_one(&state.db).await.unwrap();
    assert_eq!(peer,2);
    let clear = body_json(app.clone().oneshot(get_request("/chats/cleared",ip,Some(&a))).await.unwrap()).await;
    assert_eq!(clear[0]["through_sequence"],json!(old.1));
    let peer_clear = body_json(app.clone().oneshot(get_request("/chats/cleared",ip,Some(&b))).await.unwrap()).await;
    assert_eq!(peer_clear,json!([]));
}


#[tokio::test]
async fn new_device_discovery_hides_old_membership_until_addressed_content_arrives() {
    let state = spawn_state().await;
    let db = state.db.clone();
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (a, aid, ad) = register_user(&app, ip, "privacy_a_").await;
    let (_, bid, bd) = register_user(&app, ip, "privacy_b_").await;
    let chat = body_json(app.clone().oneshot(post_json_request("/chats/direct", ip, &a, json!({"other_user_id":bid}))).await.unwrap()).await;
    let id = Uuid::parse_str(chat["chat_id"].as_str().unwrap()).unwrap();
    // Model membership predating the current device, without touching real QA data.
    sqlx::query("UPDATE chats SET created_at = now() - interval '1 day' WHERE id=$1").bind(id).execute(&db).await.unwrap();
    let hidden = body_json(app.clone().oneshot(get_request("/chats",ip,Some(&a))).await.unwrap()).await;
    assert!(hidden["items"].as_array().unwrap().is_empty());
    // Locally restored history supplies a known ID and can recover its metadata.
    let restored = body_json(app.clone().oneshot(get_request(&format!("/chats/known?chat_ids={id}"),ip,Some(&a))).await.unwrap()).await;
    assert_eq!(restored["items"].as_array().unwrap().len(),1);
    let mid: Uuid = sqlx::query_scalar("INSERT INTO messages(chat_id,sender_id,sender_device_id,client_message_id,ciphertext) VALUES($1,$2,$3,$4,$5) RETURNING id")
        .bind(id).bind(Uuid::parse_str(&bid).unwrap()).bind(bd).bind(Uuid::new_v4()).bind(vec![1u8]).fetch_one(&db).await.unwrap();
    // Content for another device must not expose the chat.
    sqlx::query("INSERT INTO message_envelopes(message_id,recipient_device_id,message_type,ciphertext) VALUES($1,$2,1,$3)")
        .bind(mid).bind(bd).bind(vec![1u8]).execute(&db).await.unwrap();
    let hidden = body_json(app.clone().oneshot(get_request("/chats",ip,Some(&a))).await.unwrap()).await;
    assert!(hidden["items"].as_array().unwrap().is_empty());
    sqlx::query("INSERT INTO message_envelopes(message_id,recipient_device_id,message_type,ciphertext) VALUES($1,$2,1,$3)")
        .bind(mid).bind(ad).bind(vec![1u8]).execute(&db).await.unwrap();
    let visible = body_json(app.oneshot(get_request("/chats",ip,Some(&a))).await.unwrap()).await;
    assert_eq!(visible["items"].as_array().unwrap().len(),1);
    assert_eq!(visible["items"][0]["other_user"]["id"],bid);
    let _ = aid;
}

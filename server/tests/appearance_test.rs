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
        "postgres://messenger:messenger@localhost:5432/messenger_test".to_string()
    });
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

    AppState { resources: Default::default(),
        db,
        redis,
        config,
        ws_registry: Default::default(),
        s3,
        push: messenger_server::push::disabled(),
    }
}

fn unique_username(prefix: &str) -> String {
    format!("{prefix}{}", Uuid::new_v4().simple())
        .chars()
        .take(32)
        .collect()
}

fn connect_info() -> ConnectInfo<SocketAddr> {
    let octets: [u8; 3] = std::array::from_fn(|_| rand::random());
    ConnectInfo(SocketAddr::from((
        [10, octets[0], octets[1], octets[2]],
        12345,
    )))
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

async fn register_user(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    prefix: &str,
) -> (String, String, String, String) {
    let username = unique_username(prefix);
    let response = app
        .clone()
        .oneshot(register_request(ip, &username))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
        body["device_id"].as_str().unwrap().to_string(),
        username,
    )
}

fn patch_request(
    ip: ConnectInfo<SocketAddr>,
    token: &str,
    values: Value,
    initialize: bool,
) -> Request<Body> {
    Request::builder()
        .method("PATCH")
        .uri("/users/me/appearance")
        .header("content-type", "application/json")
        .header("authorization", format!("Bearer {token}"))
        .extension(ip)
        .body(Body::from(
            json!({"version":1,"values":values,"initialize_only":initialize}).to_string(),
        ))
        .unwrap()
}
#[tokio::test]
async fn appearance_is_account_scoped_atomic_and_idempotent() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (a, uid, device, username) = register_user(&app, ip, "themea").await;
    let (b, bid, other_device, _) = register_user(&app, ip, "themeb").await;
    let (connection, mut hints) = state
        .ws_registry
        .register(Uuid::parse_str(&device).unwrap())
        .await;
    let (other_connection, mut other_hints) = state
        .ws_registry
        .register(Uuid::parse_str(&other_device).unwrap())
        .await;
    let unauthorized = app
        .clone()
        .oneshot(get_request("/users/me/appearance", ip, None))
        .await
        .unwrap();
    assert_eq!(unauthorized.status(), StatusCode::UNAUTHORIZED);
    let empty = body_json(
        app.clone()
            .oneshot(get_request("/users/me/appearance", ip, Some(&a)))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(empty["revision"], 0);
    let first = app
        .clone()
        .oneshot(patch_request(ip, &a, json!({"common.fontSize":"14"}), true))
        .await
        .unwrap();
    assert_eq!(first.status(), StatusCode::OK);
    let revision = body_json(first).await["revision"].as_i64().unwrap();
    let hint: Value =
        serde_json::from_str(&hints.try_recv().expect("same-account realtime hint")).unwrap();
    assert_eq!(hint["type"], "appearance.changed");
    assert!(
        other_hints.try_recv().is_err(),
        "appearance event leaked to another account"
    );
    let late = body_json(
        app.clone()
            .oneshot(patch_request(
                ip,
                &a,
                json!({"common.fontSize":"22","tokens.out":"#000000"}),
                true,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(late["values"]["common.fontSize"], "14");
    assert!(late["values"].get("tokens.out").is_none());
    let login = app
        .clone()
        .oneshot(login_request(ip, &username))
        .await
        .unwrap();
    assert_eq!(login.status(), StatusCode::OK);
    let second = body_json(login).await["access_token"]
        .as_str()
        .unwrap()
        .to_string();
    let (x, y) = tokio::join!(
        app.clone().oneshot(patch_request(
            ip,
            &a,
            json!({"tokens.out":"#123456"}),
            false
        )),
        app.clone().oneshot(patch_request(
            ip,
            &second,
            json!({"common.fontSize":"20"}),
            false
        ))
    );
    assert_eq!(x.unwrap().status(), StatusCode::OK);
    assert_eq!(y.unwrap().status(), StatusCode::OK);
    let merged = body_json(
        app.clone()
            .oneshot(get_request("/users/me/appearance", ip, Some(&second)))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(merged["values"]["tokens.out"], "#123456");
    assert_eq!(merged["values"]["common.fontSize"], "20");
    assert_eq!(merged["revision"], revision + 2);
    let repeat = body_json(
        app.clone()
            .oneshot(patch_request(
                ip,
                &a,
                json!({"common.fontSize":"20"}),
                false,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(repeat["revision"], merged["revision"]);
    let other = body_json(
        app.clone()
            .oneshot(get_request("/users/me/appearance", ip, Some(&b)))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(other["revision"], 0);
    assert_eq!(
        app.clone()
            .oneshot(patch_request(ip, &a, json!({"unknown":"private"}), false))
            .await
            .unwrap()
            .status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        app.clone()
            .oneshot(patch_request(
                ip,
                &a,
                json!({"common.fontSize":"999"}),
                false
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::BAD_REQUEST
    );
    // New feature data follows the existing account deletion cascade.
    sqlx::query("DELETE FROM users WHERE id=ANY($1)")
        .bind(vec![
            Uuid::parse_str(&uid).unwrap(),
            Uuid::parse_str(&bid).unwrap(),
        ])
        .execute(&state.db)
        .await
        .unwrap();
    let left: i64 =
        sqlx::query_scalar("SELECT count(*) FROM user_chat_appearance WHERE user_id=$1")
            .bind(Uuid::parse_str(&uid).unwrap())
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(left, 0);
    state
        .ws_registry
        .unregister(Uuid::parse_str(&device).unwrap(), connection)
        .await;
    state
        .ws_registry
        .unregister(Uuid::parse_str(&other_device).unwrap(), other_connection)
        .await;
}

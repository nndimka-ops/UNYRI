use std::net::SocketAddr;
use std::time::Duration;

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, StatusCode};
use futures_util::{SinkExt, StreamExt};
use http_body_util::BodyExt;
use serde_json::{json, Value};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::tungstenite::Message as WsMessage;
use tower::ServiceExt;
use uuid::Uuid;

use messenger_server::config::{Config, S3Config, TurnConfig};
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

fn test_turn_config() -> TurnConfig {
    TurnConfig {
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
    let redis_url = std::env::var("TEST_REDIS_URL").unwrap_or_else(|_| "redis://localhost:6379/1".to_string());

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
    format!("{prefix}{}", Uuid::new_v4().simple()).chars().take(32).collect()
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

/// Returns (access_token, user_id, device_id).
async fn register_user(app: &axum::Router, ip: ConnectInfo<SocketAddr>, prefix: &str) -> (String, String, String) {
    let username = unique_username(prefix);
    let response = app.clone().oneshot(register_request(ip, &username)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
        body["device_id"].as_str().unwrap().to_string(),
    )
}

/// Same as register_user, but also exposes the refresh token so tests can
/// exercise the original-session boundary after rotation.
async fn register_user_with_refresh(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    prefix: &str,
) -> (String, String, String, String) {
    let username = unique_username(prefix);
    let response = app.clone().oneshot(register_request(ip, &username)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_string(),
        body["refresh_token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
        body["device_id"].as_str().unwrap().to_string(),
    )
}

fn refresh_request(ip: ConnectInfo<SocketAddr>, refresh_token: &str) -> Request<Body> {
    Request::builder()
        .method("POST")
        .uri("/auth/refresh")
        .header("content-type", "application/json")
        .extension(ip)
        .body(Body::from(json!({"refresh_token": refresh_token}).to_string()))
        .unwrap()
}

/// Logs an existing user in from a brand new "device" (own device_id/name) -
/// used for the multi-device (two Bob devices) test scenarios.
fn login_request(ip: ConnectInfo<SocketAddr>, username: &str) -> Request<Body> {
    let body = json!({
        "username": username,
        "password": "correct-horse-battery",
        "installation_id": Uuid::new_v4(),
        "device_platform": "android",
        "device_name": "Second Device",
    });
    Request::builder()
        .method("POST")
        .uri("/auth/login")
        .header("content-type", "application/json")
        .extension(ip)
        .body(Body::from(body.to_string()))
        .unwrap()
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

async fn create_call(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, callee: &str, media_kind: &str) -> Value {
    let response = app
        .clone()
        .oneshot(post_json_request("/calls", ip, token, json!({ "callee_user_id": callee, "media_kind": media_kind })))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK, "expected call creation to succeed");
    body_json(response).await
}

async fn spawn_server(state: AppState) -> SocketAddr {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let app = messenger_server::app(state);
    tokio::spawn(async move {
        axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>()).await.unwrap();
    });
    addr
}

fn ws_request(addr: SocketAddr, token: &str) -> tokio_tungstenite::tungstenite::http::Request<()> {
    let mut request = format!("ws://{addr}/ws").into_client_request().unwrap();
    request.headers_mut().insert("Authorization", HeaderValue::from_str(&format!("Bearer {token}")).unwrap());
    request
}

async fn recv_json(ws: &mut (impl StreamExt<Item = Result<WsMessage, tokio_tungstenite::tungstenite::Error>> + Unpin)) -> Value {
    let frame = tokio::time::timeout(Duration::from_secs(2), ws.next()).await.unwrap().unwrap().unwrap();
    let WsMessage::Text(text) = frame else { panic!("expected a text frame") };
    serde_json::from_str(&text).unwrap()
}

async fn expect_no_frame(ws: &mut (impl StreamExt<Item = Result<WsMessage, tokio_tungstenite::tungstenite::Error>> + Unpin)) {
    let result = tokio::time::timeout(Duration::from_millis(400), ws.next()).await;
    assert!(result.is_err(), "expected no frame, but one arrived");
}

// ---------------------------------------------------------------------------
// Call creation
// ---------------------------------------------------------------------------

#[tokio::test]
async fn history_is_participant_scoped_and_keyset_paginated() {
    let state = spawn_state().await;
    let db = state.db.clone();
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (alice, alice_id, _) = register_user(&app, ip, "hist_a").await;
    let (_, bob_id, _) = register_user(&app, ip, "hist_b").await;
    let (outsider, _, _) = register_user(&app, ip, "hist_c").await;
    for _ in 0..3 {
        sqlx::query("INSERT INTO calls(caller_user_id, callee_user_id, media_kind, state, end_reason, ended_at) VALUES ($1,$2,'audio','ended','missed',now())")
            .bind(Uuid::parse_str(&alice_id).unwrap()).bind(Uuid::parse_str(&bob_id).unwrap())
            .execute(&db).await.unwrap();
    }
    let first = body_json(app.clone().oneshot(get_request("/calls/history?limit=2", ip, Some(&alice))).await.unwrap()).await;
    assert_eq!(first["items"].as_array().unwrap().len(), 2);
    let cursor = first["next_cursor"].as_str().unwrap();
    let second = body_json(app.clone().oneshot(get_request(&format!("/calls/history?limit=2&before={cursor}"), ip, Some(&alice))).await.unwrap()).await;
    assert_eq!(second["items"].as_array().unwrap().len(), 1);
    assert!(second["next_cursor"].is_null());
    assert!(!first["items"].as_array().unwrap().iter().any(|row| row["id"] == second["items"][0]["id"]));
    let private = body_json(app.clone().oneshot(get_request(&format!("/calls/history?before={cursor}"), ip, Some(&outsider))).await.unwrap()).await;
    assert!(private["items"].as_array().unwrap().is_empty());
    let filtered = body_json(app.clone().oneshot(get_request(&format!("/calls/history?other_user_id={}", Uuid::new_v4()), ip, Some(&alice))).await.unwrap()).await;
    assert!(filtered["items"].as_array().unwrap().is_empty());
}

#[tokio::test]
async fn create_audio_call_succeeds() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_aud_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&app, ip, "call_aud_b_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;

    let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
    assert_eq!(call["state"], "ringing");
    assert_eq!(call["media_kind"], "audio");
    assert_eq!(call["caller_user_id"], _id_a);
    assert_eq!(call["callee_user_id"], id_b);
}

#[tokio::test]
async fn create_video_call_succeeds() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_vid_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&app, ip, "call_vid_b_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;

    let call = create_call(&app, ip, &token_a, &id_b, "video").await;
    assert_eq!(call["state"], "ringing");
    assert_eq!(call["media_kind"], "video");
}

#[tokio::test]
async fn self_call_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, id_a, _dev_a) = register_user(&app, ip, "call_self_").await;

    let response = app
        .oneshot(post_json_request("/calls", ip, &token_a, json!({ "callee_user_id": id_a, "media_kind": "audio" })))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn nonexistent_callee_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_none_").await;

    let response = app
        .oneshot(post_json_request(
            "/calls",
            ip,
            &token_a,
            json!({ "callee_user_id": Uuid::new_v4(), "media_kind": "audio" }),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn call_requires_existing_chat_with_callee() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_nochat_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&app, ip, "call_nochat_b_").await;
    // Deliberately no /chats/direct call first.

    let response = app
        .oneshot(post_json_request("/calls", ip, &token_a, json!({ "callee_user_id": id_b, "media_kind": "audio" })))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

// ---------------------------------------------------------------------------
// Lookup / anti-probing
// ---------------------------------------------------------------------------

#[tokio::test]
async fn outsider_call_lookup_returns_404() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_out_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&app, ip, "call_out_b_").await;
    let (token_c, _id_c, _dev_c) = register_user(&app, ip, "call_out_c_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;
    let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
    let call_id = call["id"].as_str().unwrap();

    let uri = format!("/calls/{call_id}");
    let response = app.oneshot(get_request(&uri, ip, Some(&token_c))).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn participant_gets_call() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_part_a_").await;
    let (token_b, id_b, _dev_b) = register_user(&app, ip, "call_part_b_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;
    let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
    let call_id = call["id"].as_str().unwrap();

    let uri = format!("/calls/{call_id}");
    let response = app.oneshot(get_request(&uri, ip, Some(&token_b))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["state"], "ringing");
}

// ---------------------------------------------------------------------------
// Accept / reject / cancel / hangup state machine
// ---------------------------------------------------------------------------

#[tokio::test]
async fn accept_valid_transitions_to_accepted() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_acc_a_").await;
    let (token_b, id_b, _dev_b) = register_user(&app, ip, "call_acc_b_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;
    let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
    let call_id = call["id"].as_str().unwrap();

    let response = app.oneshot(post_request(&format!("/calls/{call_id}/accept"), ip, &token_b)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["state"], "accepted");
    assert!(body["accepted_at"].is_string());
}

#[tokio::test]
async fn caller_cannot_accept_own_call() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_selfacc_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&app, ip, "call_selfacc_b_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;
    let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
    let call_id = call["id"].as_str().unwrap();

    let response = app.oneshot(post_request(&format!("/calls/{call_id}/accept"), ip, &token_a)).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn reject_valid_ends_call_as_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_rej_a_").await;
    let (token_b, id_b, _dev_b) = register_user(&app, ip, "call_rej_b_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;
    let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
    let call_id = call["id"].as_str().unwrap();

    let response = app.oneshot(post_request(&format!("/calls/{call_id}/reject"), ip, &token_b)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["state"], "ended");
    assert_eq!(body["end_reason"], "rejected");
}

#[tokio::test]
async fn caller_cancel_valid() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_cnc_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&app, ip, "call_cnc_b_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;
    let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
    let call_id = call["id"].as_str().unwrap();

    let response = app.oneshot(post_request(&format!("/calls/{call_id}/cancel"), ip, &token_a)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["state"], "ended");
    assert_eq!(body["end_reason"], "cancelled");
}

#[tokio::test]
async fn hangup_connected_call() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_hu_a_").await;
    let (token_b, id_b, _dev_b) = register_user(&app, ip, "call_hu_b_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;
    let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
    let call_id = call["id"].as_str().unwrap();
    app.clone().oneshot(post_request(&format!("/calls/{call_id}/accept"), ip, &token_b)).await.unwrap();

    let response = app.oneshot(post_request(&format!("/calls/{call_id}/hangup"), ip, &token_a)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["state"], "ended");
    assert_eq!(body["end_reason"], "hangup");
}

#[tokio::test]
async fn duplicate_hangup_idempotent() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_duphu_a_").await;
    let (token_b, id_b, _dev_b) = register_user(&app, ip, "call_duphu_b_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;
    let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
    let call_id = call["id"].as_str().unwrap();
    app.clone().oneshot(post_request(&format!("/calls/{call_id}/accept"), ip, &token_b)).await.unwrap();

    let first = app.clone().oneshot(post_request(&format!("/calls/{call_id}/hangup"), ip, &token_a)).await.unwrap();
    assert_eq!(first.status(), StatusCode::OK);
    // Bob also hangs up moments later - must be a harmless no-op, not an error.
    let second = app.oneshot(post_request(&format!("/calls/{call_id}/hangup"), ip, &token_b)).await.unwrap();
    assert_eq!(second.status(), StatusCode::OK);
    let body = body_json(second).await;
    assert_eq!(body["state"], "ended");
    assert_eq!(body["end_reason"], "hangup");
}

// ---------------------------------------------------------------------------
// Races
// ---------------------------------------------------------------------------

#[tokio::test]
async fn accept_cancel_race_deterministic() {
    for _ in 0..5 {
        let state = spawn_state().await;
        let app = messenger_server::app(state);
        let ip = connect_info();
        let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_race_a_").await;
        let (token_b, id_b, _dev_b) = register_user(&app, ip, "call_race_b_").await;
        create_direct_chat(&app, ip, &token_a, &id_b).await;
        let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
        let call_id = call["id"].as_str().unwrap().to_string();

        let app_accept = app.clone();
        let ip_accept = ip;
        let token_b_clone = token_b.clone();
        let call_id_accept = call_id.clone();
        let accept_task = tokio::spawn(async move {
            app_accept
                .oneshot(post_request(&format!("/calls/{call_id_accept}/accept"), ip_accept, &token_b_clone))
                .await
                .unwrap()
                .status()
        });
        let cancel_task = tokio::spawn(async move {
            app.oneshot(post_request(&format!("/calls/{call_id}/cancel"), ip, &token_a)).await.unwrap().status()
        });

        let (accept_status, cancel_status) = tokio::join!(accept_task, cancel_task);
        let accept_status = accept_status.unwrap();
        let cancel_status = cancel_status.unwrap();
        // Exactly one of the two conditional UPDATEs (both gated on
        // `WHERE state = 'ringing'`) can affect a row - the other must see
        // 0 rows affected and report a conflict. Never both OK.
        let ok_count = [accept_status, cancel_status].iter().filter(|s| **s == StatusCode::OK).count();
        assert_eq!(ok_count, 1, "exactly one of accept/cancel must win the race");
    }
}

#[tokio::test]
async fn two_devices_accept_one_wins() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_2dev_a_").await;
    let username_b = unique_username("call_2dev_b_");
    let register_response = app.clone().oneshot(register_request(ip, &username_b)).await.unwrap();
    let reg_body = body_json(register_response).await;
    let (token_b1, id_b) = (
        reg_body["access_token"].as_str().unwrap().to_string(),
        reg_body["user"]["id"].as_str().unwrap().to_string(),
    );
    let login_response = app.clone().oneshot(login_request(ip, &username_b)).await.unwrap();
    let token_b2 = body_json(login_response).await["access_token"].as_str().unwrap().to_string();

    create_direct_chat(&app, ip, &token_a, &id_b).await;
    let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
    let call_id = call["id"].as_str().unwrap().to_string();

    let app1 = app.clone();
    let (call_id1, call_id2) = (call_id.clone(), call_id.clone());
    let t1 = tokio::spawn(async move {
        app1.oneshot(post_request(&format!("/calls/{call_id1}/accept"), ip, &token_b1)).await.unwrap().status()
    });
    let t2 = tokio::spawn(async move {
        app.oneshot(post_request(&format!("/calls/{call_id2}/accept"), ip, &token_b2)).await.unwrap().status()
    });
    let (s1, s2) = tokio::join!(t1, t2);
    let ok_count = [s1.unwrap(), s2.unwrap()].iter().filter(|s| **s == StatusCode::OK).count();
    assert_eq!(ok_count, 1, "exactly one of Bob's two devices must win the accept race");
}

// ---------------------------------------------------------------------------
// Busy / one-active-call-per-user
// ---------------------------------------------------------------------------

#[tokio::test]
async fn busy_callee_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_busy_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&app, ip, "call_busy_b_").await;
    let (token_c, _id_c, _dev_c) = register_user(&app, ip, "call_busy_c_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;
    create_direct_chat(&app, ip, &token_c, &id_b).await;

    create_call(&app, ip, &token_a, &id_b, "audio").await;
    // Bob is already ringing with Alice - Carol's call to Bob must be busy.
    let response = app
        .oneshot(post_json_request("/calls", ip, &token_c, json!({ "callee_user_id": id_b, "media_kind": "audio" })))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::CONFLICT);
}

#[tokio::test]
async fn simultaneous_pair_call_race_yields_one_active_call() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, id_a, _dev_a) = register_user(&app, ip, "call_pairrace_a_").await;
    let (token_b, id_b, _dev_b) = register_user(&app, ip, "call_pairrace_b_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;

    let app1 = app.clone();
    let (token_a1, id_b1) = (token_a.clone(), id_b.clone());
    let t1 = tokio::spawn(async move {
        app1.oneshot(post_json_request("/calls", ip, &token_a1, json!({ "callee_user_id": id_b1, "media_kind": "audio" })))
            .await
            .unwrap()
            .status()
    });
    let t2 = tokio::spawn(async move {
        app.oneshot(post_json_request("/calls", ip, &token_b, json!({ "callee_user_id": id_a, "media_kind": "audio" })))
            .await
            .unwrap()
            .status()
    });
    let (s1, s2) = tokio::join!(t1, t2);
    let ok_count = [s1.unwrap(), s2.unwrap()].iter().filter(|s| **s == StatusCode::OK).count();
    assert_eq!(ok_count, 1, "only one of the two simultaneous calls between the same pair may succeed");
}

// ---------------------------------------------------------------------------
// Timeouts (server-authoritative)
// ---------------------------------------------------------------------------

#[tokio::test]
async fn ringing_timeout_ends_as_missed() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_timeout_r_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&app, ip, "call_timeout_r_b_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;
    let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
    let call_id = Uuid::parse_str(call["id"].as_str().unwrap()).unwrap();

    // Force the deadline into the past instead of sleeping 40 real seconds.
    sqlx::query("UPDATE calls SET ringing_deadline = now() - interval '1 second' WHERE id = $1")
        .bind(call_id)
        .execute(&state.db)
        .await
        .unwrap();
    messenger_server::calls::handlers::sweep_expired_calls(&state).await;

    let response = app.oneshot(get_request(&format!("/calls/{call_id}"), ip, Some(&token_a))).await.unwrap();
    let body = body_json(response).await;
    assert_eq!(body["state"], "ended");
    assert_eq!(body["end_reason"], "missed");
}

#[tokio::test]
async fn connecting_timeout_ends_as_timeout() {    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_timeout_c_a_").await;
    let (token_b, id_b, _dev_b) = register_user(&app, ip, "call_timeout_c_b_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;
    let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
    let call_id = Uuid::parse_str(call["id"].as_str().unwrap()).unwrap();
    app.clone().oneshot(post_request(&format!("/calls/{call_id}/accept"), ip, &token_b)).await.unwrap();

    // Simulate the answer having been relayed (accepted -> connecting).
    sqlx::query("UPDATE calls SET state = 'connecting', connecting_deadline = now() - interval '1 second' WHERE id = $1")
        .bind(call_id)
        .execute(&state.db)
        .await
        .unwrap();
    messenger_server::calls::handlers::sweep_expired_calls(&state).await;

    let response = app.oneshot(get_request(&format!("/calls/{call_id}"), ip, Some(&token_a))).await.unwrap();
    let body = body_json(response).await;
    assert_eq!(body["state"], "ended");
    assert_eq!(body["end_reason"], "timeout");
}

/// Real bug found via live device testing: a call accepted but never
/// followed by an SDP answer (e.g. the caller's app crashed right after
/// accept) must NOT stay 'accepted' forever - `accept_call` now sets a
/// `connecting_deadline` immediately, and the sweep covers 'accepted' too.
#[tokio::test]
async fn accepted_call_without_answer_also_times_out() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_timeout_a_a_").await;
    let (token_b, id_b, _dev_b) = register_user(&app, ip, "call_timeout_a_b_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;
    let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
    let call_id = Uuid::parse_str(call["id"].as_str().unwrap()).unwrap();
    app.clone().oneshot(post_request(&format!("/calls/{call_id}/accept"), ip, &token_b)).await.unwrap();

    // No answer ever relayed - force the deadline into the past instead of
    // waiting the real 30s.
    sqlx::query("UPDATE calls SET connecting_deadline = now() - interval '1 second' WHERE id = $1")
        .bind(call_id)
        .execute(&state.db)
        .await
        .unwrap();
    messenger_server::calls::handlers::sweep_expired_calls(&state).await;

    let response = app.oneshot(get_request(&format!("/calls/{call_id}"), ip, Some(&token_a))).await.unwrap();
    let body = body_json(response).await;
    assert_eq!(body["state"], "ended");
    assert_eq!(body["end_reason"], "timeout");
}

/// N.2R: a connected call abandoned by every device (e.g. both apps force-
/// stopped mid-call with no chance to send hangup, so no `call.keep_alive`
/// heartbeats arrive) must be swept as failed, otherwise both users stay
/// permanently "busy" via the `call_participants_active` rows never released.
#[tokio::test]
async fn connected_call_without_keepalive_ends_as_failed() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_stale_a_").await;
    let (token_b, id_b, _dev_b) = register_user(&app, ip, "call_stale_b_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;
    let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
    let call_id = Uuid::parse_str(call["id"].as_str().unwrap()).unwrap();
    app.clone().oneshot(post_request(&format!("/calls/{call_id}/accept"), ip, &token_b)).await.unwrap();

    // Connected, but its liveness has gone stale (no keep-alive for >40s).
    sqlx::query("UPDATE calls SET state = 'connected', connected_at = now(), last_activity_at = now() - interval '41 seconds' WHERE id = $1")
        .bind(call_id)
        .execute(&state.db)
        .await
        .unwrap();
    messenger_server::calls::handlers::sweep_expired_calls(&state).await;

    let response = app.oneshot(get_request(&format!("/calls/{call_id}"), ip, Some(&token_a))).await.unwrap();
    let body = body_json(response).await;
    assert_eq!(body["state"], "ended");
    assert_eq!(body["end_reason"], "failed");

    // The busy marker must be released so either user can call again.
    let participants: i64 = sqlx::query_scalar("SELECT count(*) FROM call_participants_active WHERE call_id = $1")
        .bind(call_id)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(participants, 0, "abandoned call must release its participants");
}

/// N.2R: a connected call whose keep-alive is still fresh must NOT be swept -
/// a quiet long call (P2P media, signaling silent) is not abandoned just
/// because no SDP/ICE frames have flowed recently.
#[tokio::test]
async fn connected_call_with_fresh_keepalive_is_kept() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_fresh_a_").await;
    let (token_b, id_b, _dev_b) = register_user(&app, ip, "call_fresh_b_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;
    let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
    let call_id = Uuid::parse_str(call["id"].as_str().unwrap()).unwrap();
    app.clone().oneshot(post_request(&format!("/calls/{call_id}/accept"), ip, &token_b)).await.unwrap();

    sqlx::query("UPDATE calls SET state = 'connected', connected_at = now(), last_activity_at = now() WHERE id = $1")
        .bind(call_id)
        .execute(&state.db)
        .await
        .unwrap();
    messenger_server::calls::handlers::sweep_expired_calls(&state).await;

    let response = app.oneshot(get_request(&format!("/calls/{call_id}"), ip, Some(&token_a))).await.unwrap();
    let body = body_json(response).await;
    assert_eq!(body["state"], "connected", "fresh keep-alive must keep the call alive");
}

// ---------------------------------------------------------------------------
// Recovery
// ---------------------------------------------------------------------------

#[tokio::test]
async fn active_call_recovery_via_get_active() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_recov_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&app, ip, "call_recov_b_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;
    let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
    let call_id = call["id"].as_str().unwrap();

    let response = app.oneshot(get_request("/calls/active", ip, Some(&token_a))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["id"], call_id);
}

#[tokio::test]
async fn no_active_call_returns_null() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_noactive_").await;

    let response = app.oneshot(get_request("/calls/active", ip, Some(&token_a))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert!(body.is_null());
}

// ---------------------------------------------------------------------------
// WS signaling relay + authorization
// ---------------------------------------------------------------------------

#[tokio::test]
async fn ringing_delivered_over_websocket() {
    let state = spawn_state().await;
    let http_app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&http_app, ip, "call_ws_ring_a_").await;
    let (token_b, id_b, _dev_b) = register_user(&http_app, ip, "call_ws_ring_b_").await;
    create_direct_chat(&http_app, ip, &token_a, &id_b).await;
    let addr = spawn_server(state).await;

    let (mut ws_b, _) = tokio_tungstenite::connect_async(ws_request(addr, &token_b)).await.unwrap();
    create_call(&http_app, ip, &token_a, &id_b, "audio").await;

    let frame = recv_json(&mut ws_b).await;
    assert_eq!(frame["type"], "call.ringing");
    assert_eq!(frame["media_kind"], "audio");
}

#[tokio::test]
async fn offer_relay_requires_caller() {
    let state = spawn_state().await;
    let http_app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&http_app, ip, "call_ws_off_a_").await;
    let (token_b, id_b, _dev_b) = register_user(&http_app, ip, "call_ws_off_b_").await;
    create_direct_chat(&http_app, ip, &token_a, &id_b).await;
    let call = create_call(&http_app, ip, &token_a, &id_b, "audio").await;
    let call_id = call["id"].as_str().unwrap();
    let addr = spawn_server(state).await;

    let (mut ws_a, _) = tokio_tungstenite::connect_async(ws_request(addr, &token_a)).await.unwrap();
    let (mut ws_b, _) = tokio_tungstenite::connect_async(ws_request(addr, &token_b)).await.unwrap();

    // Callee (Bob) illegitimately tries to send an "offer" - must be rejected
    // and NOT relayed to Alice.
    ws_b.send(WsMessage::Text(json!({"type":"call.offer","call_id":call_id,"sdp":"bogus"}).to_string())).await.unwrap();
    let error_frame = recv_json(&mut ws_b).await;
    assert_eq!(error_frame["type"], "ERROR");
    expect_no_frame(&mut ws_a).await;

    // The real caller's offer IS relayed to the callee.
    ws_a.send(WsMessage::Text(json!({"type":"call.offer","call_id":call_id,"sdp":"v=0 real offer"}).to_string())).await.unwrap();
    let offer_frame = recv_json(&mut ws_b).await;
    assert_eq!(offer_frame["type"], "call.offer");
    assert_eq!(offer_frame["sdp"], "v=0 real offer");
}

#[tokio::test]
async fn answer_relay_requires_accepted_device() {
    let state = spawn_state().await;
    let http_app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&http_app, ip, "call_ws_ans_a_").await;
    let (token_b, id_b, _dev_b) = register_user(&http_app, ip, "call_ws_ans_b_").await;
    create_direct_chat(&http_app, ip, &token_a, &id_b).await;
    let call = create_call(&http_app, ip, &token_a, &id_b, "audio").await;
    let call_id = call["id"].as_str().unwrap();
    let addr = spawn_server(state).await;

    let (mut ws_a, _) = tokio_tungstenite::connect_async(ws_request(addr, &token_a)).await.unwrap();
    let (mut ws_b, _) = tokio_tungstenite::connect_async(ws_request(addr, &token_b)).await.unwrap();

    // Bob answers BEFORE accepting via REST - must be rejected (not yet the
    // accepted device for this call).
    ws_b.send(WsMessage::Text(json!({"type":"call.answer","call_id":call_id,"sdp":"too early"}).to_string())).await.unwrap();
    let error_frame = recv_json(&mut ws_b).await;
    assert_eq!(error_frame["type"], "ERROR");
    expect_no_frame(&mut ws_a).await;

    http_app.oneshot(post_request(&format!("/calls/{call_id}/accept"), ip, &token_b)).await.unwrap();
    // Consume the call.accepted frame pushed to Alice before the answer.
    let _ = recv_json(&mut ws_a).await;

    ws_b.send(WsMessage::Text(json!({"type":"call.answer","call_id":call_id,"sdp":"v=0 real answer"}).to_string())).await.unwrap();
    let answer_frame = recv_json(&mut ws_a).await;
    assert_eq!(answer_frame["type"], "call.answer");
    assert_eq!(answer_frame["sdp"], "v=0 real answer");
}

#[tokio::test]
async fn ice_relay_rejects_non_participant() {
    let state = spawn_state().await;
    let http_app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&http_app, ip, "call_ws_ice_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&http_app, ip, "call_ws_ice_b_").await;
    let (token_c, _id_c, _dev_c) = register_user(&http_app, ip, "call_ws_ice_c_").await;
    create_direct_chat(&http_app, ip, &token_a, &id_b).await;
    let call = create_call(&http_app, ip, &token_a, &id_b, "audio").await;
    let call_id = call["id"].as_str().unwrap();
    let addr = spawn_server(state).await;

    let (mut ws_c, _) = tokio_tungstenite::connect_async(ws_request(addr, &token_c)).await.unwrap();
    ws_c.send(WsMessage::Text(
        json!({"type":"call.ice","call_id":call_id,"candidate":"candidate:1 1 UDP 1 1.1.1.1 1 typ host"}).to_string(),
    ))
    .await
    .unwrap();
    let error_frame = recv_json(&mut ws_c).await;
    assert_eq!(error_frame["type"], "ERROR");
}

#[tokio::test]
async fn terminal_call_rejects_signaling() {
    let state = spawn_state().await;
    let http_app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&http_app, ip, "call_ws_term_a_").await;
    let (token_b, id_b, _dev_b) = register_user(&http_app, ip, "call_ws_term_b_").await;
    create_direct_chat(&http_app, ip, &token_a, &id_b).await;
    let call = create_call(&http_app, ip, &token_a, &id_b, "audio").await;
    let call_id = call["id"].as_str().unwrap();
    http_app.clone().oneshot(post_request(&format!("/calls/{call_id}/cancel"), ip, &token_a)).await.unwrap();
    let addr = spawn_server(state).await;

    let (mut ws_a, _) = tokio_tungstenite::connect_async(ws_request(addr, &token_a)).await.unwrap();
    ws_a.send(WsMessage::Text(json!({"type":"call.offer","call_id":call_id,"sdp":"too late"}).to_string())).await.unwrap();
    let error_frame = recv_json(&mut ws_a).await;
    assert_eq!(error_frame["type"], "ERROR");
    let _ = token_b;
}

#[tokio::test]
async fn answered_elsewhere_delivered_to_losing_device() {
    let state = spawn_state().await;
    let http_app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&http_app, ip, "call_ws_elsw_a_").await;
    let username_b = unique_username("call_ws_elsw_b_");
    let register_response = http_app.clone().oneshot(register_request(ip, &username_b)).await.unwrap();
    let reg_body = body_json(register_response).await;
    let (token_b1, id_b) = (
        reg_body["access_token"].as_str().unwrap().to_string(),
        reg_body["user"]["id"].as_str().unwrap().to_string(),
    );
    let login_response = http_app.clone().oneshot(login_request(ip, &username_b)).await.unwrap();
    let token_b2 = body_json(login_response).await["access_token"].as_str().unwrap().to_string();

    create_direct_chat(&http_app, ip, &token_a, &id_b).await;
    let call = create_call(&http_app, ip, &token_a, &id_b, "audio").await;
    let call_id = call["id"].as_str().unwrap();
    let addr = spawn_server(state).await;

    let (mut ws_b1, _) = tokio_tungstenite::connect_async(ws_request(addr, &token_b1)).await.unwrap();
    let (mut ws_b2, _) = tokio_tungstenite::connect_async(ws_request(addr, &token_b2)).await.unwrap();

    // Device 2 accepts; device 1 must be told to stop ringing, while device
    // 2 itself gets a `call.accepted` confirmation (not `answered_elsewhere`,
    // it won the race).
    http_app.oneshot(post_request(&format!("/calls/{call_id}/accept"), ip, &token_b2)).await.unwrap();

    let frame = recv_json(&mut ws_b1).await;
    assert_eq!(frame["type"], "call.answered_elsewhere");
    let confirmation = recv_json(&mut ws_b2).await;
    assert_eq!(confirmation["type"], "call.accepted");
    expect_no_frame(&mut ws_b1).await;
}

// ---------------------------------------------------------------------------
// TURN credentials
// ---------------------------------------------------------------------------

#[tokio::test]
async fn ice_servers_requires_auth() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let response = app.oneshot(get_request("/calls/ice-servers", ip, None)).await.unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn ice_servers_returns_time_limited_turn_credential() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_ice_").await;

    let response = app.oneshot(get_request("/calls/ice-servers", ip, Some(&token_a))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    let servers = body["ice_servers"].as_array().unwrap();
    assert!(servers.iter().any(|s| s["urls"][0].as_str().unwrap().starts_with("stun:")));
    let turn_entry = servers.iter().find(|s| s["urls"][0].as_str().unwrap().starts_with("turn:")).unwrap();
    let username = turn_entry["username"].as_str().unwrap();
    let expiry: i64 = username.parse().unwrap();
    assert!(expiry > chrono::Utc::now().timestamp(), "TURN credential must expire in the future, not be permanent");
    assert!(turn_entry["credential"].as_str().is_some());
}

#[tokio::test]
async fn ice_servers_rate_limited() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "call_icerl_").await;

    let mut last_status = StatusCode::OK;
    for _ in 0..35 {
        last_status = app.clone().oneshot(get_request("/calls/ice-servers", ip, Some(&token_a))).await.unwrap().status();
        if last_status == StatusCode::TOO_MANY_REQUESTS {
            break;
        }
    }
    assert_eq!(last_status, StatusCode::TOO_MANY_REQUESTS);
}

#[tokio::test]
async fn unauthorized_answer_cannot_advance_accepted_call() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _, _) = register_user(&app, ip, "call_auth_a_").await;
    let (token_b, id_b, _) = register_user(&app, ip, "call_auth_b_").await;
    let (token_c, _, _) = register_user(&app, ip, "call_auth_c_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;
    let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
    let id = call["id"].as_str().unwrap();
    let accepted = app.clone().oneshot(post_request(&format!("/calls/{id}/accept"), ip, &token_b)).await.unwrap();
    assert_eq!(accepted.status(), StatusCode::OK);
    let addr = spawn_server(state.clone()).await;
    let (mut ws, _) = tokio_tungstenite::connect_async(ws_request(addr, &token_c)).await.unwrap();
    ws.send(WsMessage::Text(json!({"type":"call.answer","call_id":id,"sdp":"invalid"}).to_string())).await.unwrap();
    assert_eq!(recv_json(&mut ws).await["type"], "ERROR");
    let row: (String,) = sqlx::query_as("SELECT state FROM calls WHERE id = $1")
        .bind(Uuid::parse_str(id).unwrap()).fetch_one(&state.db).await.unwrap();
    assert_eq!(row.0, "accepted", "rejected signaling must not change call state");
}

/// A busy-slot deletion failure must roll the terminal update back too, so
/// retrying the same public action can still release both participants.
#[tokio::test]
async fn terminal_actions_roll_back_when_slot_release_fails() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _, _) = register_user(&app, ip, "atomic_a_").await;
    let (token_b, id_b, _) = register_user(&app, ip, "atomic_b_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;

    for action in ["reject", "cancel", "hangup", "failed"] {
        let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
        let id = Uuid::parse_str(call["id"].as_str().unwrap()).unwrap();
        // Unique names and a UUID-scoped WHEN clause isolate parallel tests.
        let hook = format!("fail_slot_{}", id.simple());
        sqlx::query(&format!(
            "CREATE FUNCTION {hook}() RETURNS trigger LANGUAGE plpgsql AS $$ \
             BEGIN RAISE EXCEPTION 'test slot release failure'; END $$"
        )).execute(&state.db).await.unwrap();
        sqlx::query(&format!(
            "CREATE TRIGGER {hook} BEFORE DELETE ON call_participants_active \
             FOR EACH ROW WHEN (OLD.call_id = '{id}'::uuid) EXECUTE FUNCTION {hook}()"
        )).execute(&state.db).await.unwrap();
        let token = if action == "reject" { &token_b } else { &token_a };
        let response = app.clone().oneshot(post_request(&format!("/calls/{id}/{action}"), ip, token)).await.unwrap();
        // Remove fault injection before asserting, including on the old buggy code.
        sqlx::query(&format!("DROP TRIGGER {hook} ON call_participants_active")).execute(&state.db).await.unwrap();
        sqlx::query(&format!("DROP FUNCTION {hook}()")).execute(&state.db).await.unwrap();
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
        let row: (String, Option<String>, Option<chrono::DateTime<chrono::Utc>>) =
            sqlx::query_as("SELECT state, end_reason, ended_at FROM calls WHERE id = $1")
                .bind(id).fetch_one(&state.db).await.unwrap();
        assert_eq!(row, ("ringing".into(), None, None), "{action} must roll back termination");
        let slots: (i64,) = sqlx::query_as("SELECT count(*) FROM call_participants_active WHERE call_id = $1")
            .bind(id).fetch_one(&state.db).await.unwrap();
        assert_eq!(slots.0, 2);
        let retry = app.clone().oneshot(post_request(&format!("/calls/{id}/{action}"), ip, token)).await.unwrap();
        assert_eq!(retry.status(), StatusCode::OK);
        let slots: (i64,) = sqlx::query_as("SELECT count(*) FROM call_participants_active WHERE call_id = $1")
            .bind(id).fetch_one(&state.db).await.unwrap();
        assert_eq!(slots.0, 0, "{action} must release both busy slots");
    }
}

/// Hold an uncommitted refresh while the sweep sees the previously expired
/// version. Wait for its UPDATE to block on that exact transaction, then commit
/// the refresh: PostgreSQL must recheck the sweep predicate on the new version.
#[tokio::test]
async fn sweep_rechecks_expiry_after_concurrent_call_progress() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _, _) = register_user(&app, ip, "sweep_race_a_").await;
    let (_, id_b, _) = register_user(&app, ip, "sweep_race_b_").await;
    create_direct_chat(&app, ip, &token_a, &id_b).await;

    for (before, after) in [("ringing", "accepted"), ("accepted", "connected"), ("connected", "connected")] {
        let call = create_call(&app, ip, &token_a, &id_b, "audio").await;
        let id = Uuid::parse_str(call["id"].as_str().unwrap()).unwrap();
        sqlx::query("UPDATE calls SET state = $2, ringing_deadline = now() - interval '1 minute', \
                     connecting_deadline = now() - interval '1 minute', last_activity_at = now() - interval '1 minute' WHERE id = $1")
            .bind(id).bind(before).execute(&state.db).await.unwrap();
        let mut tx = state.db.begin().await.unwrap();
        let (blocker_pid,): (i32,) = sqlx::query_as("SELECT pg_backend_pid()")
            .fetch_one(&mut *tx).await.unwrap();
        sqlx::query("UPDATE calls SET state = $2, connecting_deadline = now() + interval '5 minutes', last_activity_at = now() WHERE id = $1")
            .bind(id).bind(after).execute(&mut *tx).await.unwrap();
        let sweep_state = state.clone();
        let sweep = tokio::spawn(async move {
            messenger_server::calls::handlers::sweep_expired_calls(&sweep_state).await;
        });
        let blocked = tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                let (waiting,): (bool,) = sqlx::query_as(
                    "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE $1 = ANY(pg_blocking_pids(pid)) \
                     AND query LIKE 'UPDATE calls SET state = ''ended''%')"
                ).bind(blocker_pid).fetch_one(&state.db).await.unwrap();
                if waiting { break; }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        }).await;
        tx.commit().await.unwrap();
        tokio::time::timeout(Duration::from_secs(5), sweep).await.unwrap().unwrap();
        assert!(blocked.is_ok(), "sweep must reach the contested {before} transition");
        let row: (String, Option<String>) = sqlx::query_as("SELECT state, end_reason FROM calls WHERE id = $1")
            .bind(id).fetch_one(&state.db).await.unwrap();
        assert_eq!(row, (after.into(), None), "fresh {after} must survive stale {before} snapshot");
        let slots: (i64,) = sqlx::query_as("SELECT count(*) FROM call_participants_active WHERE call_id = $1")
            .bind(id).fetch_one(&state.db).await.unwrap();
        assert_eq!(slots.0, 2, "sweep must preserve both active participants");
        let ended = app.clone().oneshot(post_request(&format!("/calls/{id}/hangup"), ip, &token_a)).await.unwrap();
        assert_eq!(ended.status(), StatusCode::OK);
    }
}


#[tokio::test]
async fn previous_calls_require_restore_on_each_new_device() {
    let state = spawn_state().await;
    let db = state.db.clone();
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (old_token, old_refresh, uid, old_device) = register_user_with_refresh(&app, ip, "restore_a").await;
    let (_, peer, _) = register_user(&app, ip, "restore_b").await;
    let uid = Uuid::parse_str(&uid).unwrap();
    let old_device = Uuid::parse_str(&old_device).unwrap();

    // The visibility boundary is the session's start, not the device's age.
    // Move this exact test session back so the old call is genuinely after it.
    let old_session: Uuid = sqlx::query_scalar(
        "SELECT id FROM sessions WHERE user_id=$1 AND device_id=$2 AND revoked_at IS NULL \
         ORDER BY created_at DESC LIMIT 1",
    )
    .bind(uid)
    .bind(old_device)
    .fetch_one(&db)
    .await
    .unwrap();
    sqlx::query("UPDATE sessions SET created_at=now()-interval '3 days' WHERE id=$1")
        .bind(old_session)
        .execute(&db)
        .await
        .unwrap();

    let call: Uuid = sqlx::query_scalar("INSERT INTO calls(caller_user_id,callee_user_id,media_kind,state,end_reason,created_at,ended_at) VALUES($1,$2,'audio','ended','missed',now()-interval '1 day',now()-interval '1 day') RETURNING id")
        .bind(uid).bind(Uuid::parse_str(&peer).unwrap()).fetch_one(&db).await.unwrap();
    let username: String = sqlx::query_scalar("SELECT username FROM users WHERE id=$1").bind(uid).fetch_one(&db).await.unwrap();

    // The original session sees the call, and refresh rotation must keep that
    // original boundary/history instead of starting a new workspace.
    let old = body_json(app.clone().oneshot(get_request("/calls/history",ip,Some(&old_token))).await.unwrap()).await;
    assert_eq!(old["items"].as_array().unwrap().len(),1);
    let rotated_old = body_json(app.clone().oneshot(refresh_request(ip, &old_refresh)).await.unwrap()).await;
    let rotated_old_token = rotated_old["access_token"].as_str().unwrap();
    let old_after_refresh = body_json(app.clone().oneshot(get_request("/calls/history",ip,Some(rotated_old_token))).await.unwrap()).await;
    assert_eq!(old_after_refresh["items"].as_array().unwrap().len(),1, "refresh must preserve the original session boundary");

    let login = body_json(app.clone().oneshot(login_request(ip,&username)).await.unwrap()).await;
    let fresh = login["access_token"].as_str().unwrap();
    let page = body_json(app.clone().oneshot(get_request("/calls/history",ip,Some(fresh))).await.unwrap()).await;
    assert_eq!(page["history_restored"],false);
    assert!(page["items"].as_array().unwrap().is_empty());
    assert_eq!(app.clone().oneshot(get_request(&format!("/calls/{call}"),ip,Some(fresh))).await.unwrap().status(),StatusCode::NOT_FOUND);

    // Refresh of the NEW session must not advance its boundary or reveal old history.
    let rotated_fresh = body_json(app.clone().oneshot(refresh_request(ip, login["refresh_token"].as_str().unwrap())).await.unwrap()).await;
    let fresh_after_refresh = body_json(app.clone().oneshot(get_request("/calls/history",ip,Some(rotated_fresh["access_token"].as_str().unwrap()))).await.unwrap()).await;
    assert!(fresh_after_refresh["items"].as_array().unwrap().is_empty(), "refresh must not open pre-login history");
    assert_eq!(fresh_after_refresh["history_restored"],false);
    let fresh = rotated_fresh["access_token"].as_str().unwrap();

    let new_call: Uuid = sqlx::query_scalar("INSERT INTO calls(caller_user_id,callee_user_id,media_kind,state,end_reason,ended_at) VALUES($1,$2,'audio','ended','missed',now()) RETURNING id")
        .bind(uid).bind(Uuid::parse_str(&peer).unwrap()).fetch_one(&db).await.unwrap();
    let fresh_page = body_json(app.clone().oneshot(get_request("/calls/history",ip,Some(fresh))).await.unwrap()).await;
    assert_eq!(fresh_page["items"].as_array().unwrap().len(),1);
    assert_eq!(fresh_page["items"][0]["id"],new_call.to_string());
    let bad = app.clone().oneshot(post_json_request("/history-backup/restore-complete",ip,fresh,json!({"backup_id":Uuid::new_v4()}))).await.unwrap();
    assert_eq!(bad.status(),StatusCode::CONFLICT);
    let backup = Uuid::new_v4();
    sqlx::query("INSERT INTO history_backup_accounts(user_id) VALUES($1) ON CONFLICT DO NOTHING").bind(uid).execute(&db).await.unwrap();
    sqlx::query("INSERT INTO history_backup_objects(user_id,backup_id,format_version,ciphertext,sha256,state,verified_at) VALUES($1,$2,1,$3,$4,'active',now())")
        .bind(uid).bind(backup).bind(vec![1u8;29]).bind("a".repeat(64)).execute(&db).await.unwrap();
    // The compatibility restore acknowledgement is idempotent, but it must not
    // unlock the unencrypted call-history endpoint. Historical calls are
    // restored by importing the authenticated client-encrypted archive; only
    // rows already visible to this new session may be returned here.
    for _ in 0..2 {
        let response = app.clone().oneshot(post_json_request("/history-backup/restore-complete",ip,fresh,json!({"backup_id":backup}))).await.unwrap();
        assert_eq!(response.status(),StatusCode::OK);
        assert_eq!(body_json(response).await,json!({"ok":true}));
    }
    let page = body_json(app.clone().oneshot(get_request("/calls/history",ip,Some(fresh))).await.unwrap()).await;
    assert_eq!(page["history_restored"],false);
    assert_eq!(page["items"].as_array().unwrap().len(),1);
    assert_eq!(page["items"][0]["id"],new_call.to_string());
    assert!(page["items"].as_array().unwrap().iter().all(|item| item["id"] != call.to_string()), "restore ack must not expose the pre-login call");
    let another = body_json(app.clone().oneshot(login_request(ip,&username)).await.unwrap()).await;
    let page = body_json(app.oneshot(get_request("/calls/history",ip,Some(another["access_token"].as_str().unwrap()))).await.unwrap()).await;
    assert!(page["items"].as_array().unwrap().is_empty());
}

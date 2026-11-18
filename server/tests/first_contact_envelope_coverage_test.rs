use std::net::SocketAddr;
use std::time::Duration;

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, StatusCode};
use base64::{engine::general_purpose::STANDARD, Engine as _};
use futures_util::{SinkExt, StreamExt};
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
    // Root supplies isolated TEST_DATABASE_URL/TEST_REDIS_URL explicitly; do not load a workspace .env.

    let database_url = std::env::var("TEST_DATABASE_URL").expect("explicit isolated TEST_DATABASE_URL is required");
    assert!(database_url.rsplit('/').next().is_some_and(|name| name.starts_with("unyri_first_contact_")),
        "this regression suite may run only against an owned unyri_first_contact_ database");
    let redis_url = std::env::var("TEST_REDIS_URL").expect("explicit isolated TEST_REDIS_URL is required");

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
    // No S3 network: this suite qualifies message admission/DB attachment claims only.

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

/// Registers a fresh user/device and returns (access_token, user_id, device_id, username).
async fn register_user(app: &axum::Router, ip: ConnectInfo<SocketAddr>, prefix: &str) -> (String, String, String, String) {
    let username = unique_username(prefix);
    let response = app.clone().oneshot(register_request(ip, &username, "Test User")).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
        body["device_id"].as_str().unwrap().to_string(),
        username,
    )
}

/// Logs the same account in from a brand-new device (multi-device), returns (access_token, device_id).
async fn login_new_device(app: &axum::Router, ip: ConnectInfo<SocketAddr>, username: &str) -> (String, String) {
    let response = app.clone().oneshot(login_request(ip, username)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (body["access_token"].as_str().unwrap().to_string(), body["device_id"].as_str().unwrap().to_string())
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

/// A single fake ciphertext envelope. The server never interprets the bytes
/// cryptographically, so tests just base64-encode plain text as a stand-in
/// for what would really be `SessionCipher.encrypt()` output.
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

/// Convenience for the common case: one envelope addressed to a single
/// recipient device, `message_type = 2` (an already-established session).
fn send_body_to(client_message_id: Uuid, recipient_device_id: &str, plaintext: &str) -> Value {
    send_body(client_message_id, vec![envelope(recipient_device_id, 2, plaintext)])
}

fn decode_ciphertext(message: &Value) -> String {
    let bytes = STANDARD.decode(message["ciphertext"].as_str().unwrap()).unwrap();
    String::from_utf8(bytes).unwrap()
}

async fn upload_keys_for_device(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str) {
    let response = app
        .clone()
        .oneshot(post_json_request(
            "/keys",
            ip,
            token,
            json!({
                "identity_key": STANDARD.encode(b"fake-identity-key"),
                "registration_id": 12345,
                "signed_prekey": { "key_id": 1, "public_key": STANDARD.encode(b"fake-spk"), "signature": STANDARD.encode(b"fake-sig") },
                "kyber_prekey": { "key_id": 1, "public_key": STANDARD.encode(b"fake-kyber"), "signature": STANDARD.encode(b"fake-ksig") },
                "one_time_prekeys": [{ "key_id": 1, "public_key": STANDARD.encode(b"fake-otp-1") }],
            }),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}

// First-contact P1 regression: these tests use the real router, PostgreSQL and
// attachment-claim state, but opaque canary bytes, not a Signal implementation.
// An uploaded attachment row is staged directly in the owned isolated database;
// no /attachments intent, S3 upload, bucket-readiness or /complete claim is made.

// This test starts at the message-admission boundary, with an already uploaded
// opaque attachment. A normal /attachments intent requires a verified restricted
// S3 signer; that endpoint is intentionally outside this no-S3 test suite.
async fn first_contact_upload_canary(db: &sqlx::PgPool, owner: &str, device: &str) -> String {
    let id=Uuid::new_v4();
    let owner=Uuid::parse_str(owner).unwrap();
    let device=Uuid::parse_str(device).unwrap();
    let actual_owner:Uuid=sqlx::query_scalar("SELECT user_id FROM devices WHERE id=$1 AND revoked_at IS NULL")
        .bind(device).fetch_one(db).await.unwrap();
    assert_eq!(actual_owner,owner,"fixture may reference only its owned test user/device");
    let inserted=sqlx::query("INSERT INTO attachments(id,client_attachment_id,uploader_user_id,uploader_device_id,object_key,encrypted_size,status,uploaded_at) VALUES($1,$2,$3,$4,$5,64,'uploaded',now())")
        .bind(id).bind(Uuid::new_v4()).bind(owner).bind(device)
        .bind(format!("attachments/{}",Uuid::new_v4())).execute(db).await.unwrap();
    assert_eq!(inserted.rows_affected(),1);
    let staged:(i64,String,Option<Uuid>,Uuid,Uuid)=sqlx::query_as("SELECT encrypted_size,status::text,message_id,uploader_user_id,uploader_device_id FROM attachments WHERE id=$1")
        .bind(id).fetch_one(db).await.unwrap();
    assert_eq!(staged,(64,"uploaded".into(),None,owner,device));
    eprintln!("PHOTO_UPLOADED_FIXTURE_READY=1");
    id.to_string()
}

async fn first_contact_assert_no_message(db: &sqlx::PgPool, client: Uuid) {
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM messages WHERE client_message_id=$1")
        .bind(client).fetch_one(db).await.unwrap();
    assert_eq!(count,0,"rejected send must not persist a message or claim attachments");
}

#[tokio::test]
async fn first_contact_own_only_photo_is_not_sent_and_same_id_retry_succeeds() {
    let state=spawn_state().await;
    let app=messenger_server::app(state.clone());
    let ip=connect_info();
    let (token_a,a_id,a1,username_a)=register_user(&app,ip,"fcp_a_").await;
    let (token_a2,a2)=login_new_device(&app,ip,&username_a).await;
    let (token_a3,a3)=login_new_device(&app,ip,&username_a).await;
    upload_keys_for_device(&app,ip,&token_a2).await;
    upload_keys_for_device(&app,ip,&token_a3).await;
    let (token_b,b_id,b1,_)=register_user(&app,ip,"fcp_b_").await;
    upload_keys_for_device(&app,ip,&token_b).await;
    let chat=create_direct_chat(&app,ip,&token_a,&b_id).await;
    let uri=format!("/chats/{chat}/messages");
    let attachment=first_contact_upload_canary(&state.db,&a_id,&a1).await;
    let client=Uuid::new_v4();
    let mut rejected_body=send_body(client,vec![envelope(&a2,1,"own2"),envelope(&a3,1,"own3")]);
    rejected_body["attachment_ids"]=json!([attachment]);
    let rejected=app.clone().oneshot(post_json_request(&uri,ip,&token_a,rejected_body)).await.unwrap();
    eprintln!("PHOTO_OWN_ONLY_MESSAGE_POST_STATUS={}",rejected.status().as_u16());
    assert_eq!(rejected.status(),StatusCode::CONFLICT,"an own-device echo is not delivery to the peer");
    let rejected=body_json(rejected).await;
    assert_eq!(rejected["error"]["code"],"recipient_not_ready");
    first_contact_assert_no_message(&state.db,client).await;
    let unchanged:(String,Option<Uuid>)=sqlx::query_as("SELECT status::text,message_id FROM attachments WHERE id=$1")
        .bind(Uuid::parse_str(&attachment).unwrap()).fetch_one(&state.db).await.unwrap();
    assert_eq!(unchanged,("uploaded".into(),None));
    let invisible=app.clone().oneshot(get_request(&uri,ip,Some(&token_b))).await.unwrap();
    assert_eq!(body_json(invisible).await["messages"].as_array().unwrap().len(),0);

    let mut retry=send_body(client,vec![envelope(&a2,1,"own2retry"),envelope(&a3,1,"own3retry"),envelope(&b1,1,"peer photo")]);
    retry["attachment_ids"]=json!([attachment]);
    let accepted=app.clone().oneshot(post_json_request(&uri,ip,&token_a,retry.clone())).await.unwrap();
    assert_eq!(accepted.status(),StatusCode::OK);
    let sent=body_json(accepted).await;
    assert_eq!(sent["status"],"sent");
    let received=app.clone().oneshot(get_request(&uri,ip,Some(&token_b))).await.unwrap();
    let received=body_json(received).await;
    assert_eq!(received["messages"].as_array().unwrap().len(),1);
    assert_eq!(received["messages"][0]["id"],sent["id"]);
    assert_eq!(received["messages"][0]["message_type"],1);
    let associated:(String,Option<Uuid>)=sqlx::query_as("SELECT status::text,message_id FROM attachments WHERE id=$1")
        .bind(Uuid::parse_str(&attachment).unwrap()).fetch_one(&state.db).await.unwrap();
    assert_eq!(associated,("attached".into(),Some(Uuid::parse_str(sent["id"].as_str().unwrap()).unwrap())));
    let duplicate=app.clone().oneshot(post_json_request(&uri,ip,&token_a,retry)).await.unwrap();
    assert_eq!(duplicate.status(),StatusCode::OK);
    assert_eq!(body_json(duplicate).await["id"],sent["id"]);
    let count:i64=sqlx::query_scalar("SELECT count(*) FROM message_envelopes e JOIN messages m ON m.id=e.message_id WHERE m.client_message_id=$1")
        .bind(client).fetch_one(&state.db).await.unwrap();
    assert_eq!(count,3,"idempotent retry must not duplicate envelopes");
}

#[tokio::test]
async fn first_contact_keyless_peer_does_not_accept_own_only_but_key_ready_retry_works() {
    let state=spawn_state().await;
    let app=messenger_server::app(state.clone());
    let ip=connect_info();
    let (a,_aid,a1,_)=register_user(&app,ip,"fck_a_").await;
    let (b,bid,b1,_)=register_user(&app,ip,"fck_b_").await;
    let chat=create_direct_chat(&app,ip,&a,&bid).await;
    let uri=format!("/chats/{chat}/messages");
    let client=Uuid::new_v4();
    let wrong=app.clone().oneshot(post_json_request(&uri,ip,&a,send_body_to(client,&a1,"own only"))).await.unwrap();
    eprintln!("KEYLESS_OWN_ONLY_MESSAGE_POST_STATUS={}",wrong.status().as_u16());
    assert_eq!(wrong.status(),StatusCode::CONFLICT);
    assert_eq!(body_json(wrong).await["error"]["code"],"recipient_not_ready");
    first_contact_assert_no_message(&state.db,client).await;
    upload_keys_for_device(&app,ip,&b).await;
    let retry=app.clone().oneshot(post_json_request(&uri,ip,&a,send_body_to(client,&b1,"now ready"))).await.unwrap();
    assert_eq!(retry.status(),StatusCode::OK);
}

#[tokio::test]
async fn first_contact_saved_empty_and_sibling_echo_remain_valid() {
    let state=spawn_state().await;
    let app=messenger_server::app(state);
    let ip=connect_info();
    let (a,_aid,_a1,username)=register_user(&app,ip,"fcs_a_").await;
    let saved=app.clone().oneshot(get_request("/chats/saved",ip,Some(&a))).await.unwrap();
    assert_eq!(saved.status(),StatusCode::OK);
    let saved=body_json(saved).await;
    let uri=format!("/chats/{}/messages",saved["chat_id"].as_str().unwrap());
    let single=app.clone().oneshot(post_json_request(&uri,ip,&a,send_body(Uuid::new_v4(),vec![]))).await.unwrap();
    assert_eq!(single.status(),StatusCode::OK,"single-device Saved must allow zero envelopes");
    let (a2_token,a2)=login_new_device(&app,ip,&username).await;
    upload_keys_for_device(&app,ip,&a2_token).await;
    let sibling=app.clone().oneshot(post_json_request(&uri,ip,&a,send_body_to(Uuid::new_v4(),&a2,"Saved echo"))).await.unwrap();
    assert_eq!(sibling.status(),StatusCode::OK,"Saved own-only echo must remain valid");
}

#[tokio::test]
async fn first_contact_offline_keyed_peer_still_receives_history_and_sync() {
    let state=spawn_state().await;
    assert_eq!(state.ws_registry.admin_metrics().await,(0,0,0));
    let app=messenger_server::app(state);
    let ip=connect_info();
    let (a,_aid,_a1,_)=register_user(&app,ip,"fco_a_").await;
    let (b,bid,b1,_)=register_user(&app,ip,"fco_b_").await;
    upload_keys_for_device(&app,ip,&b).await;
    let chat=create_direct_chat(&app,ip,&a,&bid).await;
    let uri=format!("/chats/{chat}/messages");
    let sent=app.clone().oneshot(post_json_request(&uri,ip,&a,send_body_to(Uuid::new_v4(),&b1,"offline"))).await.unwrap();
    assert_eq!(sent.status(),StatusCode::OK,"readiness is cryptographic, not an online check");
    let sent=body_json(sent).await;
    let history=app.clone().oneshot(get_request(&uri,ip,Some(&b))).await.unwrap();
    assert_eq!(body_json(history).await["messages"][0]["id"],sent["id"]);
    let sync=app.clone().oneshot(get_request("/sync?after_sequence=0",ip,Some(&b))).await.unwrap();
    assert_eq!(body_json(sync).await["messages"][0]["id"],sent["id"]);
}

#[tokio::test]
async fn first_contact_keyless_and_revoked_peer_devices_do_not_wedge_normal_send() {
    let state=spawn_state().await;
    let app=messenger_server::app(state);
    let ip=connect_info();
    let (a,_aid,_a1,_)=register_user(&app,ip,"fcr_a_").await;
    let (b,bid,b1,username)=register_user(&app,ip,"fcr_b_").await;
    upload_keys_for_device(&app,ip,&b).await;
    let (b2_token,b2)=login_new_device(&app,ip,&username).await;
    upload_keys_for_device(&app,ip,&b2_token).await;
    let (_keyless_token,_keyless_id)=login_new_device(&app,ip,&username).await;
    let revoke=app.clone().oneshot(Request::builder().method("DELETE").uri(format!("/devices/{b2}"))
        .header("authorization",format!("Bearer {b}")).extension(ip).body(Body::empty()).unwrap()).await.unwrap();
    assert_eq!(revoke.status(),StatusCode::NO_CONTENT);
    let chat=create_direct_chat(&app,ip,&a,&bid).await;
    let send=app.clone().oneshot(post_json_request(&format!("/chats/{chat}/messages"),ip,&a,
        send_body_to(Uuid::new_v4(),&b1,"active keyed only"))).await.unwrap();
    assert_eq!(send.status(),StatusCode::OK,"a keyless or revoked sibling must not require an impossible envelope");
}

#[tokio::test]
async fn first_contact_partial_peer_coverage_contract_is_not_expanded_by_this_fix() {
    let state=spawn_state().await;
    let app=messenger_server::app(state);
    let ip=connect_info();
    let (a,_aid,_a1,_)=register_user(&app,ip,"fcpv_a_").await;
    let (b,bid,b1,username)=register_user(&app,ip,"fcpv_b_").await;
    upload_keys_for_device(&app,ip,&b).await;
    let (b2_token,_b2)=login_new_device(&app,ip,&username).await;
    upload_keys_for_device(&app,ip,&b2_token).await;
    let chat=create_direct_chat(&app,ip,&a,&bid).await;
    let sent=app.clone().oneshot(post_json_request(&format!("/chats/{chat}/messages"),ip,&a,
        send_body_to(Uuid::new_v4(),&b1,"one eligible peer target"))).await.unwrap();
    assert_eq!(sent.status(),StatusCode::OK,"this fix prevents own-only sends without adding all-peer-device coverage policy");
}

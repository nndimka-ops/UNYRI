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

async fn body_json(response: axum::response::Response) -> Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap()
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

fn envelope(recipient_device_id: &str, message_type: i16, plaintext: &str) -> Value {
    json!({
        "recipient_device_id": recipient_device_id,
        "message_type": message_type,
        "ciphertext": STANDARD.encode(plaintext.as_bytes()),
    })
}

fn send_body_to(client_message_id: Uuid, recipient_device_id: &str, plaintext: &str) -> Value {
    json!({ "client_message_id": client_message_id, "envelopes": [envelope(recipient_device_id, 2, plaintext)] })
}

async fn send_message(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, chat_id: &str, recipient_device_id: &str, text: &str) -> i64 {
    let uri = format!("/chats/{chat_id}/messages");
    let response = app
        .clone()
        .oneshot(post_json_request(&uri, ip, token, send_body_to(Uuid::new_v4(), recipient_device_id, text)))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await["sequence"].as_i64().unwrap()
}

async fn mark_delivered(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, chat_id: &str, sequence: i64) -> axum::response::Response {
    let uri = format!("/chats/{chat_id}/delivered");
    app.clone().oneshot(post_json_request(&uri, ip, token, json!({ "sequence": sequence }))).await.unwrap()
}

async fn mark_read(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, chat_id: &str, sequence: i64) -> axum::response::Response {
    let uri = format!("/chats/{chat_id}/read");
    app.clone().oneshot(post_json_request(&uri, ip, token, json!({ "sequence": sequence }))).await.unwrap()
}

async fn get_chat(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, chat_id: &str) -> Value {
    let uri = format!("/chats/{chat_id}");
    let response = app.clone().oneshot(get_request(&uri, ip, Some(token))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await
}

async fn privacy(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str) -> Value {
    let response = app.clone().oneshot(get_request("/users/me/privacy", ip, Some(token))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await
}

fn update(snapshot: &Value) -> Value {
    let mut request = snapshot.clone();
    let object = request.as_object_mut().unwrap();
    let revision = object.remove("revision").unwrap();
    object.insert("expected_revision".into(), revision);
    request
}

async fn put_privacy(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, request: Value) -> axum::response::Response {
    let mut request = post_json_request("/users/me/privacy", ip, token, request);
    *request.method_mut() = axum::http::Method::PUT;
    app.clone().oneshot(request).await.unwrap()
}

async fn save(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, request: Value) -> Value {
    let response = put_privacy(app, ip, token, request).await;
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await
}

#[tokio::test]
async fn privacy_defaults_canonical_validation_and_concurrent_snapshots() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, owner, _, username) = register_user(&app, ip, "privsettings_").await;
    let (second, _) = login_new_device(&app, ip, &username).await;
    let initial = privacy(&app, ip, &token).await;
    assert_eq!(initial, json!({"online_visibility":"everyone","last_seen_visibility":"everyone","always_allow":[],"never_allow":[],"send_read_receipts":true,"revision":0}));
    assert_eq!(save(&app, ip, &token, update(&initial)).await["revision"], 0, "identical values are a no-op");
    let peer = Uuid::new_v4().to_string();
    let mut request = update(&initial);
    request["always_allow"] = json!([peer, peer, owner]);
    request["never_allow"] = json!([peer]);
    let saved = save(&app, ip, &token, request).await;
    assert_eq!(saved["always_allow"], json!([peer]));
    assert_eq!(saved["never_allow"], json!([peer]), "deny precedence permits shared input");
    assert_eq!(privacy(&app, ip, &second).await, saved, "account preference is live on another device");
    assert_eq!(put_privacy(&app, ip, &token, update(&initial)).await.status(), StatusCode::CONFLICT);
    for (key, bad) in [("online_visibility", json!("contacts")), ("expected_revision", json!(-1)), ("always_allow",json!([Uuid::nil()])), ("never_allow",json!(vec![peer.clone();257])), ("revision",json!(1))] {
        let mut invalid = update(&saved); invalid[key] = bad;
        assert!(put_privacy(&app, ip, &token, invalid).await.status().is_client_error(), "reject {key}");
    }
    let mut a = update(&saved); a["online_visibility"] = json!("nobody");
    let mut b = update(&saved); b["send_read_receipts"] = json!(false);
    let (a, b) = tokio::join!(put_privacy(&app, ip, &token, a), put_privacy(&app, ip, &second, b));
    assert_eq!([a.status(), b.status()].iter().filter(|s| **s == StatusCode::OK).count(), 1);
    assert_eq!([a.status(), b.status()].iter().filter(|s| **s == StatusCode::CONFLICT).count(), 1);
    assert_eq!(privacy(&app, ip, &token).await["revision"], 2);
}

#[tokio::test]
async fn privacy_read_only_accounts_can_restrict_visibility_and_account_requests_are_bounded() {
    let state = spawn_state().await; let app = messenger_server::app(state.clone()); let ip = connect_info();
    let (token, user, _, _) = register_user(&app,ip,"privreadonly_").await;
    sqlx::query("INSERT INTO admin_user_controls(user_id,read_only_reason) VALUES($1,'isolated fixture')")
        .bind(Uuid::parse_str(&user).unwrap()).execute(&state.db).await.unwrap();
    let initial = privacy(&app,ip,&token).await;
    let mut request = update(&initial); request["online_visibility"] = json!("nobody"); request["last_seen_visibility"] = json!("nobody"); request["send_read_receipts"] = json!(false);
    let saved = save(&app,ip,&token,request).await;
    assert_eq!(saved["online_visibility"],"nobody"); assert_eq!(saved["send_read_receipts"],false);
    for _ in 1..60 { assert_eq!(put_privacy(&app,ip,&token,update(&saved)).await.status(),StatusCode::OK); }
    assert_eq!(put_privacy(&app,ip,&token,update(&saved)).await.status(),StatusCode::TOO_MANY_REQUESTS);
    for _ in 1..120 { assert_eq!(app.clone().oneshot(get_request("/users/me/privacy",ip,Some(&token))).await.unwrap().status(),StatusCode::OK); }
    assert_eq!(app.clone().oneshot(get_request("/users/me/privacy",ip,Some(&token))).await.unwrap().status(),StatusCode::TOO_MANY_REQUESTS);
}

async fn assert_presence(app: &axum::Router, state: &AppState, ip: ConnectInfo<SocketAddr>, viewer: &str, viewer_id: &str, owner: &str, chat: &str, online: bool, seen: bool) {
    let direct = body_json(app.clone().oneshot(get_request(&format!("/chats/{chat}/presence"), ip, Some(viewer))).await.unwrap()).await;
    assert_eq!(direct["online"], online); assert_eq!(direct["last_seen_at"].is_string(), seen);
    let username: String = sqlx::query_scalar("SELECT username FROM users WHERE id=$1").bind(Uuid::parse_str(owner).unwrap()).fetch_one(&state.db).await.unwrap();
    let directory = body_json(app.clone().oneshot(get_request(&format!("/users/directory?q={username}"), ip, Some(viewer))).await.unwrap()).await;
    let item = directory["items"].as_array().unwrap().iter().find(|item| item["id"] == owner).unwrap();
    assert_eq!(item["online"], online); assert_eq!(item["last_seen_at"].is_string(), seen);
    let snapshot = messenger_server::presence::snapshot(state, Uuid::parse_str(viewer_id).unwrap(), &[Uuid::parse_str(owner).unwrap()]).await.unwrap();
    assert_eq!(snapshot.len(), 1); assert_eq!(snapshot[0].online, online); assert_eq!(snapshot[0].last_seen_at.is_some(), seen);
}

#[tokio::test]
async fn privacy_presence_projects_independent_fields_exceptions_and_blocks_on_rest() {
    let state = spawn_state().await; let app = messenger_server::app(state.clone()); let ip = connect_info();
    let (a, aid, _, _) = register_user(&app, ip, "privviewer_").await;
    let (b, bid, bd, _) = register_user(&app, ip, "privowner_").await;
    let chat = create_direct_chat(&app, ip, &a, &bid).await;
    let id = Uuid::parse_str(&bid).unwrap(); let device = Uuid::parse_str(&bd).unwrap();
    let (conn, _rx) = state.ws_registry.register(device).await;
    state.ws_registry.foreground(id, device, conn, true).await;
    messenger_server::presence::persist_user(&state, id).await;
    assert_presence(&app, &state, ip, &a, &aid, &bid, &chat, true, true).await;
    let mut p = update(&privacy(&app, ip, &b).await); p["online_visibility"] = json!("nobody");
    let p = save(&app, ip, &b, p).await;
    assert_presence(&app, &state, ip, &a, &aid, &bid, &chat, false, true).await;
    let mut p = update(&p); p["last_seen_visibility"] = json!("nobody");
    let p = save(&app, ip, &b, p).await;
    assert_presence(&app, &state, ip, &a, &aid, &bid, &chat, false, false).await;
    let own = messenger_server::presence::snapshot(&state, id, &[id]).await.unwrap();
    assert!(own[0].online && own[0].last_seen_at.is_some());
    let mut p = update(&p); p["always_allow"] = json!([aid]);
    let p = save(&app, ip, &b, p).await;
    assert_presence(&app, &state, ip, &a, &aid, &bid, &chat, true, true).await;
    let mut p = update(&p); p["never_allow"] = json!([aid]);
    let p = save(&app, ip, &b, p).await;
    assert_presence(&app, &state, ip, &a, &aid, &bid, &chat, false, false).await;
    let mut p = update(&p); p["never_allow"] = json!([]); p["online_visibility"] = json!("everyone");
    save(&app, ip, &b, p).await;
    assert_eq!(app.clone().oneshot(post_json_request(&format!("/users/{bid}/block"), ip, &a, json!({}))).await.unwrap().status(), StatusCode::OK);
    assert!(messenger_server::presence::snapshot(&state, Uuid::parse_str(&aid).unwrap(), &[id]).await.unwrap().is_empty(), "allow exception cannot override block");
    assert_eq!(app.clone().oneshot(get_request(&format!("/chats/{chat}/presence"),ip,Some(&a))).await.unwrap().status(), StatusCode::CONFLICT);
    state.ws_registry.unregister(device, conn).await;
}

#[tokio::test]
async fn privacy_presence_live_websocket_subscription_reprojects_within_five_seconds() {
    let state = spawn_state().await; let app = messenger_server::app(state.clone()); let ip = connect_info();
    let (a, _, _, _) = register_user(&app, ip, "privwsv_").await;
    let (b, bid, bd, _) = register_user(&app, ip, "privwso_").await;
    let device = Uuid::parse_str(&bd).unwrap(); let id = Uuid::parse_str(&bid).unwrap();
    let (conn, _rx) = state.ws_registry.register(device).await;
    state.ws_registry.foreground(id, device, conn, true).await;
    messenger_server::presence::persist_user(&state, id).await;
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap(); let addr = listener.local_addr().unwrap();
    let server = tokio::spawn(async move { axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>()).await.unwrap() });
    let mut request = format!("ws://{addr}/ws").into_client_request().unwrap();
    request.headers_mut().insert("authorization",HeaderValue::from_str(&format!("Bearer {a}")).unwrap());
    let (mut ws, _) = tokio_tungstenite::connect_async(request).await.unwrap();
    ws.send(WsMessage::Text(json!({"type":"presence.subscribe","user_ids":[bid]}).to_string())).await.unwrap();
    async fn snapshot(ws: &mut tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>, online: bool, seen: bool) {
        tokio::time::timeout(Duration::from_secs(5), async {
            loop { let message = ws.next().await.unwrap().unwrap();
                if let WsMessage::Text(text) = message { let p:Value = serde_json::from_str(&text).unwrap();
                    if p["type"] == "presence.snapshot" && p["items"][0]["online"] == online && p["items"][0]["last_seen_at"].is_string() == seen { break; }
                }
            }
        }).await.expect("current privacy reaches active subscription in <=5s");
    }
    snapshot(&mut ws, true, true).await;
    let direct_app = messenger_server::app(state.clone());
    let mut p = update(&privacy(&direct_app, ip, &b).await); p["online_visibility"] = json!("nobody"); p["last_seen_visibility"] = json!("nobody");
    let p = save(&direct_app, ip, &b, p).await;
    snapshot(&mut ws, false, false).await;
    state.ws_registry.foreground(id,device,conn,false).await;
    let mut p = update(&p); p["last_seen_visibility"] = json!("everyone");
    save(&direct_app,ip,&b,p).await;
    snapshot(&mut ws,false,true).await;
    ws.close(None).await.unwrap(); server.abort(); state.ws_registry.unregister(device,conn).await;
}

#[tokio::test]
async fn privacy_read_disable_preserves_own_cursor_delivery_and_hides_replays_until_new_unread_read() {
    let state = spawn_state().await; let app = messenger_server::app(state.clone()); let ip = connect_info();
    let (a, _, ad, _) = register_user(&app, ip, "privreada_").await;
    let (b, bid, bd, username) = register_user(&app, ip, "privreadb_").await;
    let chat = create_direct_chat(&app, ip, &a, &bid).await;
    let (conn, mut rx) = state.ws_registry.register(Uuid::parse_str(&ad).unwrap()).await;
    let first = send_message(&app,ip,&a,&chat,&bd,"visible").await;
    assert_eq!(mark_read(&app,ip,&b,&chat,first).await.status(),StatusCode::OK);
    async fn receipt(rx: &mut tokio::sync::mpsc::Receiver<messenger_server::websocket::registry::QueuedFrame>) -> Value {
        tokio::time::timeout(Duration::from_secs(3), async { loop {
            let payload: Value = serde_json::from_str(&rx.recv().await.unwrap()).unwrap();
            if payload["type"] == "receipt.updated" { return payload; }
        }}).await.unwrap()
    }
    assert_eq!(receipt(&mut rx).await["read_sequence"],first);
    let mut p = update(&privacy(&app,ip,&b).await); p["send_read_receipts"] = json!(false);
    let p = save(&app,ip,&b,p).await;
    let second = send_message(&app,ip,&a,&chat,&bd,"hidden second").await;
    let third = send_message(&app,ip,&a,&chat,&bd,"hidden third").await;
    assert_eq!(body_json(mark_read(&app,ip,&b,&chat,third).await).await["sequence"],third);
    let hidden = receipt(&mut rx).await;
    assert_eq!(hidden["read_sequence"],first); assert_eq!(hidden["delivered_sequence"],third);
    assert_eq!(get_chat(&app,ip,&a,&chat).await["other_member_read_sequence"],first);
    let list = body_json(app.clone().oneshot(get_request("/chats",ip,Some(&a))).await.unwrap()).await;
    let item = list["items"].as_array().unwrap().iter().find(|c| c["chat_id"] == chat).unwrap();
    assert_eq!(item["other_member_read_sequence"],first);
    assert_eq!(get_chat(&app,ip,&b,&chat).await["own_read_sequence"],third);
    let (own_second, _) = login_new_device(&app,ip,&username).await;
    assert_eq!(get_chat(&app,ip,&own_second,&chat).await["own_read_sequence"],third);
    let unread: i64 = sqlx::query_scalar("SELECT count(*) FROM message_receipts mr JOIN messages m ON m.id=mr.message_id WHERE m.chat_id=$1 AND mr.user_id=$2 AND mr.read_at IS NULL")
        .bind(Uuid::parse_str(&chat).unwrap()).bind(Uuid::parse_str(&bid).unwrap()).fetch_one(&state.db).await.unwrap();
    assert_eq!(unread,0,"own account unread remains cleared");
    let mut p = update(&p); p["send_read_receipts"] = json!(true); save(&app,ip,&own_second,p).await;
    assert_eq!(get_chat(&app,ip,&a,&chat).await["other_member_read_sequence"],first,"reenabling alone never publishes hidden cursor");
    assert_eq!(mark_delivered(&app,ip,&b,&chat,third).await.status(),StatusCode::OK);
    assert_eq!(receipt(&mut rx).await["read_sequence"],first,"delivered event does not recover hidden reads");
    assert_eq!(body_json(mark_read(&app,ip,&b,&chat,second).await).await["sequence"],third,"own cursor cannot regress");
    assert_eq!(receipt(&mut rx).await["read_sequence"],first,"opening an older hidden read must not publish account history");
    assert_eq!(mark_read(&app,ip,&own_second,&chat,third).await.status(),StatusCode::OK);
    assert_eq!(receipt(&mut rx).await["read_sequence"],first,"process-restart/device queue replay must not expose the hidden cursor");
    assert_eq!(get_chat(&app,ip,&a,&chat).await["other_member_read_sequence"],first);
    let own_sent = send_message(&app,ip,&b,&chat,&ad,"reader's own sent tail").await;
    assert_eq!(mark_read(&app,ip,&b,&chat,own_sent).await.status(),StatusCode::OK);
    assert_eq!(receipt(&mut rx).await["read_sequence"],first,"own sent tail is not new received unread and cannot expose hidden reads");
    let fourth = send_message(&app,ip,&a,&chat,&bd,"new unread").await;
    assert_eq!(mark_read(&app,ip,&b,&chat,fourth).await.status(),StatusCode::OK);
    assert_eq!(receipt(&mut rx).await["read_sequence"],fourth,"new unread advance exposes the fresh cumulative range");
    assert_eq!(get_chat(&app,ip,&a,&chat).await["other_member_read_sequence"],fourth);
    state.ws_registry.unregister(Uuid::parse_str(&ad).unwrap(),conn).await;
}


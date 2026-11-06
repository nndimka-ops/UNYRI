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
        max_upload_duration_seconds: std::env::var("S3_MAX_UPLOAD_DURATION_SECONDS")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(0),
        upload_access_key_id: std::env::var("S3_UPLOAD_ACCESS_KEY_ID").unwrap_or_default(),
        upload_secret_access_key: std::env::var("S3_UPLOAD_SECRET_ACCESS_KEY").unwrap_or_default(),

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
    assert_eq!(
        std::env::var("COCO_CHAT_ARCHIVE_TEST_ISOLATED").as_deref(),
        Ok("1"),
        "isolated archive fixture required"
    );

    let database_url =
        std::env::var("TEST_DATABASE_URL").expect("explicit isolated TEST_DATABASE_URL");
    assert!(
        database_url.ends_with("/coco_chat_archive_qa"),
        "refuse non-isolated database"
    );
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

    AppState {
        resources: Default::default(),
        db,
        redis,
        config,
        ws_registry: Default::default(),
        s3,
        push: messenger_server::push::disabled(),
    }
}

const PASSWORD: &str = "correct-horse-battery";
fn ip() -> ConnectInfo<SocketAddr> {
    ConnectInfo(SocketAddr::from((
        [10, rand::random(), rand::random(), rand::random()],
        12345,
    )))
}
fn request(method: &str, uri: &str, token: Option<&str>, body: Option<Value>) -> Request<Body> {
    let mut b = Request::builder().method(method).uri(uri).extension(ip());
    if let Some(token) = token {
        b = b.header("authorization", format!("Bearer {token}"));
    }
    if let Some(body) = body {
        b.header("content-type", "application/json")
            .body(Body::from(body.to_string()))
            .unwrap()
    } else {
        b.body(Body::empty()).unwrap()
    }
}
async fn json(response: axum::response::Response) -> Value {
    serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap()
}
async fn register(app: &axum::Router) -> Value {
    let response = app.clone().oneshot(request("POST","/auth/register",None,Some(json!({
        "username":format!("archive_{}",Uuid::new_v4().simple()).chars().take(32).collect::<String>(),
        "password":PASSWORD,"display_name":"Chat archive fixture","installation_id":Uuid::new_v4(),
        "device_platform":"android","device_name":"Archive fixture"
    })))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    json(response).await
}
fn token(auth: &Value) -> &str {
    auth["access_token"].as_str().unwrap()
}
fn user(auth: &Value) -> Uuid {
    auth["user"]["id"].as_str().unwrap().parse().unwrap()
}
fn device(auth: &Value) -> Uuid {
    auth["device_id"].as_str().unwrap().parse().unwrap()
}

async fn call(
    app: &axum::Router,
    method: &str,
    uri: &str,
    auth: &Value,
    body: Option<Value>,
) -> axum::response::Response {
    app.clone()
        .oneshot(request(method, uri, Some(token(auth)), body))
        .await
        .unwrap()
}
async fn archive(
    app: &axum::Router,
    auth: &Value,
    ids: &[Uuid],
    enabled: bool,
) -> axum::response::Response {
    call(
        app,
        "PUT",
        "/chats/archives",
        auth,
        Some(json!({"chat_ids":ids,"archived":enabled})),
    )
    .await
}
async fn archived(app: &axum::Router, auth: &Value) -> Value {
    let response = call(app, "GET", "/chats/archives", auth, None).await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = json(response).await;
    assert_eq!(
        body.as_object().unwrap().len(),
        1,
        "Never return chat or peer metadata"
    );
    assert!(body["chat_ids"].as_array().is_some());
    body["chat_ids"].clone()
}
async fn direct(app: &axum::Router, a: &Value, b: &Value) -> Uuid {
    let response = call(
        app,
        "POST",
        "/chats/direct",
        a,
        Some(json!({"other_user_id":user(b)})),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    json(response).await["chat_id"]
        .as_str()
        .unwrap()
        .parse()
        .unwrap()
}
async fn insert_message(state: &AppState, chat: Uuid, sender: &Value) -> Uuid {
    sqlx::query_scalar("INSERT INTO messages(chat_id,sender_id,sender_device_id,client_message_id,ciphertext) VALUES($1,$2,$3,$4,$5) RETURNING id")
        .bind(chat).bind(user(sender)).bind(device(sender)).bind(Uuid::new_v4()).bind(vec![1u8,2,3])
        .fetch_one(&state.db).await.unwrap()
}

#[tokio::test]
async fn archive_is_private_persistent_idempotent_and_independent_of_pins_mute_and_history() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let a = register(&app).await;
    let b = register(&app).await;
    let chat = direct(&app, &a, &b).await;
    assert_eq!(archived(&app, &a).await, json!([]));
    assert_eq!(archived(&app, &b).await, json!([]));
    assert_eq!(
        call(
            &app,
            "POST",
            "/chats/pins",
            &a,
            Some(json!({"chat_ids":[chat],"pinned":true}))
        )
        .await
        .status(),
        StatusCode::OK
    );
    assert_eq!(
        call(
            &app,
            "POST",
            &format!("/chats/{chat}/mute"),
            &a,
            Some(json!({}))
        )
        .await
        .status(),
        StatusCode::OK
    );
    let message = insert_message(&state, chat, &a).await;
    for _ in 0..2 {
        let response = archive(&app, &a, &[chat, chat], true).await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(json(response).await, json!({"chat_ids":[chat]}));
    }
    assert_eq!(
        archived(&app, &b).await,
        json!([]),
        "Peer archive is independent"
    );
    let new_app = messenger_server::app(state.clone());
    assert_eq!(
        archived(&new_app, &a).await,
        json!([chat]),
        "No in-memory-only state"
    );
    let second=app.clone().oneshot(request("POST","/auth/login",None,Some(json!({
        "username":a["user"]["username"],"password":PASSWORD,"installation_id":Uuid::new_v4(),"device_platform":"ios"
    })))).await.unwrap();
    assert_eq!(second.status(), StatusCode::OK);
    let second = json(second).await;
    assert_eq!(
        archived(&new_app, &second).await,
        json!([chat]),
        "Same account across sessions"
    );
    assert_eq!(
        archive(&app, &a, &[chat], false).await.status(),
        StatusCode::OK
    );
    assert_eq!(archived(&app, &second).await, json!([]));
    assert_eq!(
        archive(&app, &a, &[chat], false).await.status(),
        StatusCode::OK
    );
    let row:(bool,bool,bool)=sqlx::query_as("SELECT is_pinned,is_muted,is_archived FROM chat_preferences WHERE user_id=$1 AND chat_id=$2")
        .bind(user(&a)).bind(chat).fetch_one(&state.db).await.unwrap();
    assert_eq!(row, (true, true, false));
    let present: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM messages WHERE id=$1)")
        .bind(message)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert!(present, "Archive/restore never removes message history");
    let members: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM chat_members WHERE chat_id=$1 AND left_at IS NULL",
    )
    .bind(chat)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(members, 2, "Archive is not leaving a chat");
}

#[tokio::test]
async fn archive_batch_is_atomic_rejects_nonmembers_saved_and_unknown_chats() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let a = register(&app).await;
    let b = register(&app).await;
    let c = register(&app).await;
    let own = direct(&app, &a, &b).await;
    let foreign = direct(&app, &b, &c).await;
    assert_eq!(
        archive(&app, &a, &[own], true).await.status(),
        StatusCode::OK
    );
    for invalid in [foreign, Uuid::new_v4()] {
        assert_eq!(
            archive(&app, &a, &[own, invalid], false).await.status(),
            StatusCode::NOT_FOUND
        );
        assert_eq!(
            archived(&app, &a).await,
            json!([own]),
            "Valid first target must not be partially changed"
        );
    }
    let saved = call(&app, "GET", "/chats/saved", &a, None).await;
    assert_eq!(saved.status(), StatusCode::OK);
    let saved: Uuid = json(saved).await["chat_id"]
        .as_str()
        .unwrap()
        .parse()
        .unwrap();
    assert_eq!(
        archive(&app, &a, &[own, saved], false).await.status(),
        StatusCode::NOT_FOUND
    );
    assert_eq!(archived(&app, &a).await, json!([own]));
    assert_eq!(archived(&app, &b).await, json!([]));
    assert_eq!(
        archive(&app, &c, &[own], true).await.status(),
        StatusCode::NOT_FOUND
    );
    let rows: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM chat_preferences WHERE user_id=$1 AND is_archived",
    )
    .bind(user(&c))
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(rows, 0);
}

#[tokio::test]
async fn archive_request_validation_is_bounded_and_authentication_required() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    for method in ["GET", "PUT"] {
        assert_eq!(
            app.clone()
                .oneshot(request(
                    method,
                    "/chats/archives",
                    None,
                    if method == "PUT" {
                        Some(json!({"chat_ids":[],"archived":true}))
                    } else {
                        None
                    }
                ))
                .await
                .unwrap()
                .status(),
            StatusCode::UNAUTHORIZED
        );
    }
    let a = register(&app).await;
    let b = register(&app).await;
    let chat = direct(&app, &a, &b).await;
    for body in [
        json!({}),
        json!({"chat_ids":[chat]}),
        json!({"archived":true}),
        json!({"chat_ids":[chat],"archived":null}),
        json!({"chat_ids":[chat],"archived":"true"}),
        json!({"chat_ids":[chat],"archived":true,"user_id":user(&b)}),
        json!({"chat_ids":["not-a-uuid"],"archived":true}),
    ] {
        assert_eq!(
            call(&app, "PUT", "/chats/archives", &a, Some(body))
                .await
                .status(),
            StatusCode::UNPROCESSABLE_ENTITY
        );
    }
    assert_eq!(
        archive(&app, &a, &[], true).await.status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        archive(&app, &a, &vec![chat; 101], true).await.status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(archived(&app, &a).await, json!([]));
    assert_eq!(
        archive(&app, &a, &vec![chat; 100], true).await.status(),
        StatusCode::OK
    );
    assert_eq!(archived(&app, &a).await, json!([chat]));
}

#[tokio::test]
async fn incoming_messages_never_restore_manually_archived_chat_or_change_its_mute() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let a = register(&app).await;
    let b = register(&app).await;
    let chat = direct(&app, &a, &b).await;
    for muted in [false, true] {
        assert_eq!(
            call(
                &app,
                "POST",
                &format!("/chats/{chat}/{}", if muted { "mute" } else { "unmute" }),
                &a,
                Some(json!({}))
            )
            .await
            .status(),
            StatusCode::OK
        );
        assert_eq!(
            archive(&app, &a, &[chat], true).await.status(),
            StatusCode::OK
        );
        let id = insert_message(&state, chat, &b).await;
        assert_eq!(archived(&app, &a).await, json!([chat]));
        let remains: bool = sqlx::query_scalar(
            "SELECT is_muted FROM chat_preferences WHERE chat_id=$1 AND user_id=$2",
        )
        .bind(chat)
        .bind(user(&a))
        .fetch_one(&state.db)
        .await
        .unwrap();
        assert_eq!(remains, muted);
        assert!(
            sqlx::query_scalar::<_, bool>("SELECT EXISTS(SELECT 1 FROM messages WHERE id=$1)")
                .bind(id)
                .fetch_one(&state.db)
                .await
                .unwrap()
        );
    }
}

#[tokio::test]
async fn archive_hides_left_memberships_and_does_not_revive_them() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let a = register(&app).await;
    let b = register(&app).await;
    let chat = direct(&app, &a, &b).await;
    assert_eq!(
        archive(&app, &a, &[chat], true).await.status(),
        StatusCode::OK
    );
    sqlx::query("UPDATE chat_members SET left_at=now() WHERE chat_id=$1 AND user_id=$2")
        .bind(chat)
        .bind(user(&a))
        .execute(&state.db)
        .await
        .unwrap();
    assert_eq!(archived(&app, &a).await, json!([]));
    assert_eq!(
        archive(&app, &a, &[chat], false).await.status(),
        StatusCode::NOT_FOUND
    );
    let old: bool = sqlx::query_scalar(
        "SELECT is_archived FROM chat_preferences WHERE chat_id=$1 AND user_id=$2",
    )
    .bind(chat)
    .bind(user(&a))
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert!(old, "Failed write must not revive a left membership");
}

#[tokio::test]
async fn revoked_session_cannot_read_or_change_archive() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let a = register(&app).await;
    let b = register(&app).await;
    let chat = direct(&app, &a, &b).await;
    assert_eq!(
        archive(&app, &a, &[chat], true).await.status(),
        StatusCode::OK
    );
    sqlx::query("UPDATE sessions SET revoked_at=now() WHERE user_id=$1")
        .bind(user(&a))
        .execute(&state.db)
        .await
        .unwrap();
    for method in ["GET", "PUT"] {
        let response = call(
            &app,
            method,
            "/chats/archives",
            &a,
            if method == "PUT" {
                Some(json!({"chat_ids":[chat],"archived":false}))
            } else {
                None
            },
        )
        .await;
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
        assert_eq!(json(response).await["error"]["code"], "session_revoked");
    }
    assert!(sqlx::query_scalar::<_, bool>(
        "SELECT is_archived FROM chat_preferences WHERE chat_id=$1 AND user_id=$2"
    )
    .bind(chat)
    .bind(user(&a))
    .fetch_one(&state.db)
    .await
    .unwrap());
}

#[tokio::test]
async fn archive_change_notifies_own_devices_without_notifying_peer_or_including_chat_metadata() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let a = register(&app).await;
    let b = register(&app).await;
    let chat = direct(&app, &a, &b).await;
    let response=app.clone().oneshot(request("POST","/auth/login",None,Some(json!({
        "username":a["user"]["username"],"password":PASSWORD,"installation_id":Uuid::new_v4(),"device_platform":"ios"
    })))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let second = json(response).await;
    let (_, mut first_rx) = state.ws_registry.register(device(&a)).await;
    let (_, mut second_rx) = state.ws_registry.register(device(&second)).await;
    let (_, mut peer_rx) = state.ws_registry.register(device(&b)).await;
    assert_eq!(
        archive(&app, &a, &[chat], true).await.status(),
        StatusCode::OK
    );
    for rx in [&mut first_rx, &mut second_rx] {
        let frame = tokio::time::timeout(std::time::Duration::from_secs(1), rx.recv())
            .await
            .unwrap()
            .unwrap();
        let payload: Value = serde_json::from_str(frame.as_str()).unwrap();
        assert_eq!(payload, json!({"type":"chat.management_changed"}));
    }
    assert!(matches!(
        peer_rx.try_recv(),
        Err(tokio::sync::mpsc::error::TryRecvError::Empty)
    ));
}

#[tokio::test]
async fn populated_schema_59_upgrades_without_rewriting_preferences_or_history() {
    use std::borrow::Cow;
    assert_eq!(
        std::env::var("COCO_CHAT_ARCHIVE_TEST_ISOLATED").as_deref(),
        Ok("1")
    );
    let database =
        std::env::var("TEST_UPGRADE_DATABASE_URL").expect("explicit isolated upgrade database");
    assert!(database.ends_with("/coco_chat_archive_upgrade_qa"));
    let db = sqlx::postgres::PgPoolOptions::new()
        .max_connections(3)
        .connect(&database)
        .await
        .unwrap();
    let all = sqlx::migrate!();
    let old = sqlx::migrate::Migrator {
        migrations: Cow::Owned(all.iter().filter(|m| m.version <= 59).cloned().collect()),
        ..sqlx::migrate::Migrator::DEFAULT
    };
    old.run(&db).await.unwrap();
    let prior: i64 = sqlx::query_scalar("SELECT max(version) FROM _sqlx_migrations WHERE success")
        .fetch_one(&db)
        .await
        .unwrap();
    assert_eq!(prior, 59);
    let a = Uuid::new_v4();
    let b = Uuid::new_v4();
    let chat = Uuid::new_v4();
    sqlx::query("INSERT INTO users(id,username,display_name,password_hash) VALUES($1,'archive_upgrade_a','A','fixture-only'),($2,'archive_upgrade_b','B','fixture-only')")
        .bind(a).bind(b).execute(&db).await.unwrap();
    sqlx::query("INSERT INTO chats(id,type,user_a_id,user_b_id) VALUES($1,'direct',$2,$3)")
        .bind(chat)
        .bind(a.min(b))
        .bind(a.max(b))
        .execute(&db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO chat_preferences(chat_id,user_id,is_muted,muted_until,is_pinned) VALUES($1,$2,true,now()+interval '1 hour',true)")
        .bind(chat).bind(a).execute(&db).await.unwrap();
    let before: Value =
        sqlx::query_scalar("SELECT to_jsonb(p) FROM chat_preferences p WHERE user_id=$1")
            .bind(a)
            .fetch_one(&db)
            .await
            .unwrap();
    let users_before: Value =
        sqlx::query_scalar("SELECT jsonb_agg(to_jsonb(u) ORDER BY id) FROM users u")
            .fetch_one(&db)
            .await
            .unwrap();
    let chat_before: Value = sqlx::query_scalar("SELECT to_jsonb(c) FROM chats c WHERE id=$1")
        .bind(chat)
        .fetch_one(&db)
        .await
        .unwrap();
    let ledger_before: Vec<(i64, Vec<u8>)> =
        sqlx::query_as("SELECT version,checksum FROM _sqlx_migrations ORDER BY version")
            .fetch_all(&db)
            .await
            .unwrap();
    all.run(&db).await.unwrap();
    let after: Value = sqlx::query_scalar(
        "SELECT to_jsonb(p)-'is_archived' FROM chat_preferences p WHERE user_id=$1",
    )
    .bind(a)
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(
        before, after,
        "Existing pins, mute, IDs and timestamps must not be rewritten"
    );
    assert_eq!(
        users_before,
        sqlx::query_scalar::<_, Value>("SELECT jsonb_agg(to_jsonb(u) ORDER BY id) FROM users u")
            .fetch_one(&db)
            .await
            .unwrap()
    );
    assert_eq!(
        chat_before,
        sqlx::query_scalar::<_, Value>("SELECT to_jsonb(c) FROM chats c WHERE id=$1")
            .bind(chat)
            .fetch_one(&db)
            .await
            .unwrap()
    );
    assert!(!sqlx::query_scalar::<_, bool>(
        "SELECT is_archived FROM chat_preferences WHERE user_id=$1"
    )
    .bind(a)
    .fetch_one(&db)
    .await
    .unwrap());
    let ledger_after: Vec<(i64, Vec<u8>)> = sqlx::query_as(
        "SELECT version,checksum FROM _sqlx_migrations WHERE version<=59 ORDER BY version",
    )
    .fetch_all(&db)
    .await
    .unwrap();
    assert_eq!(
        ledger_before, ledger_after,
        "Historical migration checksums unchanged"
    );
    assert!(
        old.run(&db).await.is_err(),
        "A59 binary rejects ledger60; deployment recovery must be forward-only"
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT max(version) FROM _sqlx_migrations WHERE success")
            .fetch_one(&db)
            .await
            .unwrap(),
        60
    );
    db.close().await;
}

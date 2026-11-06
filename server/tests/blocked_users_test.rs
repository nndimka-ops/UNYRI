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
    assert_eq!(
        std::env::var("COCO_BLOCKED_TEST_ISOLATED").as_deref(),
        Ok("1"),
        "Use Test-BlockedUsers.ps1 with fresh owned containers only"
    );

    let database_url = std::env::var("TEST_DATABASE_URL").expect("isolated PostgreSQL URL");
    let redis_url = std::env::var("TEST_REDIS_URL").expect("isolated Redis URL");
    assert!(database_url.starts_with("postgres://blocked_qa:fixture@127.0.0.1:"));
    assert!(database_url.ends_with("/coco_blocked_test"));
    assert!(redis_url.starts_with("redis://127.0.0.1:"));

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

/// Each test gets its own fake client IP so the shared Redis-backed rate
/// limiter (keyed by IP) can't cause cross-test interference when the suite
/// runs in parallel.
fn connect_info() -> ConnectInfo<SocketAddr> {
    let octets: [u8; 3] = std::array::from_fn(|_| rand::random());
    ConnectInfo(SocketAddr::from((
        [10, octets[0], octets[1], octets[2]],
        12345,
    )))
}

fn register_request(
    ip: ConnectInfo<SocketAddr>,
    username: &str,
    display_name: &str,
) -> Request<Body> {
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

async fn body_json(response: axum::response::Response) -> Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap()
}

/// Registers a fresh user and returns (access_token, user_id, device_id).
async fn register_user(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    prefix: &str,
) -> (String, String, Uuid) {
    let username = unique_username(prefix);
    let response = app
        .clone()
        .oneshot(register_request(ip, &username, "Test User"))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
        Uuid::parse_str(body["device_id"].as_str().unwrap()).unwrap(),
    )
}

async fn request(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    token: &str,
    method: &str,
    path: &str,
    body: Option<Value>,
) -> axum::response::Response {
    let mut req = Request::builder()
        .method(method)
        .uri(path)
        .extension(ip)
        .header("authorization", format!("Bearer {token}"));
    let body = if let Some(body) = body {
        req = req.header("content-type", "application/json");
        Body::from(body.to_string())
    } else {
        Body::empty()
    };
    app.clone().oneshot(req.body(body).unwrap()).await.unwrap()
}

async fn create_chat(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    token: &str,
    peer: &str,
) -> String {
    let response = request(
        app,
        ip,
        token,
        "POST",
        "/chats/direct",
        Some(json!({"other_user_id":peer})),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await["chat_id"]
        .as_str()
        .unwrap()
        .to_owned()
}

async fn set_block(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    token: &str,
    peer: &str,
    blocked: bool,
) {
    let response = request(
        app,
        ip,
        token,
        if blocked { "POST" } else { "DELETE" },
        &format!("/users/{peer}/block"),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(body_json(response).await, Value::Null);
}

async fn blocked(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    token: &str,
    suffix: &str,
) -> Value {
    let response = request(
        app,
        ip,
        token,
        "GET",
        &format!("/users/blocked{suffix}"),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await
}

#[tokio::test]
async fn account_blocks_are_private_paged_searchable_and_never_restore_chats() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (owner, owner_id, _) = register_user(&app, ip, "bl_owner_").await;
    let (other, other_id, _) = register_user(&app, ip, "bl_other_").await;
    let (_, third_id, _) = register_user(&app, ip, "bl_third_").await;
    let (_, fourth_id, _) = register_user(&app, ip, "bl_fourth_").await;
    sqlx::query("UPDATE users SET display_name=$2 WHERE id=$1")
        .bind(Uuid::parse_str(&third_id).unwrap())
        .bind("Literal %_ match")
        .execute(&state.db)
        .await
        .unwrap();
    for id in [&other_id, &third_id, &fourth_id] {
        set_block(&app, ip, &owner, id, true).await;
    }
    set_block(&app, ip, &owner, &other_id, true).await;
    assert_eq!(blocked(&app, ip, &other, "").await["items"], json!([]));
    let first = blocked(&app, ip, &owner, "?limit=1").await;
    assert_eq!(first["items"].as_array().unwrap().len(), 1);
    assert!(first["items"][0]["blocked_at"].is_string());
    let cursor = first["next_cursor"].as_str().unwrap();
    let second = blocked(&app, ip, &owner, &format!("?limit=50&cursor={cursor}")).await;
    assert_eq!(second["items"].as_array().unwrap().len(), 2);
    assert!(second["next_cursor"].is_null());
    assert_ne!(
        first["items"][0]["user"]["id"],
        second["items"][0]["user"]["id"]
    );
    let literal = blocked(&app, ip, &owner, "?q=%25_").await;
    assert_eq!(
        literal["items"].as_array().unwrap().len(),
        1,
        "wildcards are literal"
    );
    assert_eq!(literal["items"][0]["user"]["id"], third_id);
    assert!(literal["items"][0]["user"].get("password_hash").is_none());
    assert!(literal["items"][0]["user"].get("installation_id").is_none());
    let chats: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM chats WHERE user_a_id=$1 OR user_b_id=$1")
            .bind(Uuid::parse_str(&owner_id).unwrap())
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(chats, 0, "security preferences must not create a chat");
    set_block(&app, ip, &owner, &other_id, false).await;
    set_block(&app, ip, &owner, &other_id, false).await;
    assert_eq!(
        blocked(&app, ip, &owner, "").await["items"]
            .as_array()
            .unwrap()
            .len(),
        2
    );
    let response = request(&app, ip, &owner, "GET", "/chats", None).await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(body_json(response).await["items"], json!([]));
    let no_auth = app
        .clone()
        .oneshot(get_request("/users/blocked", ip, None))
        .await
        .unwrap();
    assert_eq!(no_auth.status(), StatusCode::UNAUTHORIZED);
    for method in ["POST", "DELETE"] {
        let response = request(
            &app,
            ip,
            &owner,
            method,
            &format!("/users/{owner_id}/block"),
            None,
        )
        .await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    }
    let missing = Uuid::new_v4().to_string();
    assert_eq!(
        request(
            &app,
            ip,
            &owner,
            "POST",
            &format!("/users/{missing}/block"),
            None
        )
        .await
        .status(),
        StatusCode::NOT_FOUND
    );
    set_block(&app, ip, &owner, &missing, false).await;
    assert_eq!(
        request(&app, ip, &owner, "GET", "/users/blocked?cursor=bad", None)
            .await
            .status(),
        StatusCode::BAD_REQUEST
    );
}

#[tokio::test]
async fn blocks_deny_messages_calls_presence_profiles_and_reciprocal_unblock() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (a, aid, adev) = register_user(&app, ip, "privacy_a_").await;
    let (b, bid, bdev) = register_user(&app, ip, "privacy_b_").await;
    let chat = create_chat(&app, ip, &a, &bid).await;
    let a_uuid = Uuid::parse_str(&aid).unwrap();
    let b_uuid = Uuid::parse_str(&bid).unwrap();
    let (_, mut owner_events) = state.ws_registry.register(adev).await;
    let (_, mut peer_events) = state.ws_registry.register(bdev).await;
    set_block(&app, ip, &a, &bid, true).await;
    assert!(
        owner_events.try_recv().is_ok(),
        "owner devices refresh preferences"
    );
    assert!(
        peer_events.try_recv().is_err(),
        "do not tell peer who blocked them"
    );
    for (token, owner_id, peer, peer_device) in
        [(&a, a_uuid, b_uuid, bdev), (&b, b_uuid, a_uuid, adev)]
    {
        let send = || json!({"client_message_id":Uuid::new_v4(),"envelopes":[{"recipient_device_id":peer_device,"message_type":1,"ciphertext":"dGVzdA=="}]});
        assert_eq!(
            request(
                &app,
                ip,
                token,
                "POST",
                &format!("/chats/{chat}/messages"),
                Some(send())
            )
            .await
            .status(),
            StatusCode::CONFLICT
        );
        assert_eq!(
            request(
                &app,
                ip,
                token,
                "POST",
                "/calls",
                Some(json!({"callee_user_id":peer,"media_kind":"audio"}))
            )
            .await
            .status(),
            StatusCode::CONFLICT
        );
        assert!(
            messenger_server::presence::snapshot(&state, owner_id, &[peer])
                .await
                .unwrap()
                .is_empty()
        );
        assert_eq!(
            request(
                &app,
                ip,
                token,
                "GET",
                &format!("/users/{peer}/avatar"),
                None
            )
            .await
            .status(),
            StatusCode::NOT_FOUND
        );
        assert_eq!(
            request(
                &app,
                ip,
                token,
                "GET",
                &format!("/keys/devices/{peer}"),
                None
            )
            .await
            .status(),
            StatusCode::NOT_FOUND
        );
        let search =
            body_json(request(&app, ip, token, "GET", "/users/search?q=privacy", None).await).await;
        assert!(!search["items"]
            .as_array()
            .unwrap()
            .iter()
            .any(|u| u["id"] == json!(peer)));
        let directory =
            body_json(request(&app, ip, token, "GET", "/users/directory?q=privacy", None).await)
                .await;
        assert!(!directory["items"]
            .as_array()
            .unwrap()
            .iter()
            .any(|u| u["id"] == json!(peer)));
        assert_eq!(
            request(
                &app,
                ip,
                token,
                "POST",
                "/chats/direct",
                Some(json!({"other_user_id":peer}))
            )
            .await
            .status(),
            StatusCode::NOT_FOUND
        );
        let summaries = body_json(
            request(
                &app,
                ip,
                token,
                "GET",
                &format!("/chats?chat_ids={chat}"),
                None,
            )
            .await,
        )
        .await;
        assert_eq!(summaries["items"].as_array().unwrap().len(), 1);
        assert!(
            summaries["items"][0]["other_user"].is_null(),
            "fresh profiles stay private; local history is retained"
        );
        let claims =
            messenger_server::auth::jwt::decode_access_token(&state.config, token).unwrap();
        let auth = messenger_server::auth::middleware::AuthUser {
            user_id: owner_id,
            device_id: claims.device_id,
            session_id: claims.session_id,
        };
        assert!(messenger_server::websocket::relay_activity(
            &state,
            &auth,
            Uuid::parse_str(&chat).unwrap(),
            "typing".into()
        )
        .await
        .is_err());
    }
    // Clearing B's own preference cannot remove A's block.
    set_block(&app, ip, &b, &aid, false).await;
    assert!(messenger_server::chats::policy::require_contact_allowed(
        &state.db,
        Uuid::parse_str(&chat).unwrap()
    )
    .await
    .is_err());
    set_block(&app, ip, &b, &aid, true).await;
    set_block(&app, ip, &a, &bid, false).await;
    assert!(messenger_server::chats::policy::require_contact_allowed(
        &state.db,
        Uuid::parse_str(&chat).unwrap()
    )
    .await
    .is_err());
    set_block(&app, ip, &b, &aid, false).await;
    let sent = request(&app, ip, &a, "POST", &format!("/chats/{chat}/messages"), Some(json!({
        "client_message_id":Uuid::new_v4(),"envelopes":[{"recipient_device_id":bdev,"message_type":1,"ciphertext":"dGVzdA=="}]}))).await;
    assert_eq!(
        sent.status(),
        StatusCode::OK,
        "unblocked messages work with the same conversation"
    );
}

#[tokio::test]
async fn legacy_preferences_mirror_and_chat_deletion_cannot_remove_blocks() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (a, aid, _) = register_user(&app, ip, "legacy_a_").await;
    let (_, bid, _) = register_user(&app, ip, "legacy_b_").await;
    let chat = create_chat(&app, ip, &a, &bid).await;
    let chat_id = Uuid::parse_str(&chat).unwrap();
    let owner_id = Uuid::parse_str(&aid).unwrap();
    let peer_id = Uuid::parse_str(&bid).unwrap();
    assert_eq!(
        request(
            &app,
            ip,
            &a,
            "POST",
            &format!("/chats/{chat}/block"),
            Some(json!({"blocked_user_id":bid}))
        )
        .await
        .status(),
        StatusCode::OK
    );
    assert_eq!(
        blocked(&app, ip, &a, "").await["items"][0]["user"]["id"],
        bid
    );
    assert_eq!(
        request(
            &app,
            ip,
            &a,
            "POST",
            &format!("/chats/{chat}/unblock"),
            None
        )
        .await
        .status(),
        StatusCode::OK
    );
    assert_eq!(blocked(&app, ip, &a, "").await["items"], json!([]));
    // A legacy writer can still maintain the authoritative account preference.
    sqlx::query("UPDATE chat_preferences SET is_blocked=true,blocked_user_id=$3 WHERE chat_id=$1 AND user_id=$2")
        .bind(chat_id).bind(owner_id).bind(peer_id).execute(&state.db).await.unwrap();
    assert_eq!(
        blocked(&app, ip, &a, "").await["items"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        request(
            &app,
            ip,
            &a,
            "POST",
            &format!("/chats/{chat}/delete"),
            Some(json!({"for_everyone":true}))
        )
        .await
        .status(),
        StatusCode::OK
    );
    assert_eq!(
        blocked(&app, ip, &a, "").await["items"]
            .as_array()
            .unwrap()
            .len(),
        1,
        "deleting a chat never unblocks its peer"
    );
    let remaining: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM chats WHERE id=$1")
        .bind(chat_id)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(remaining, 0);
    set_block(&app, ip, &a, &bid, false).await;
    let remaining: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM chats WHERE (user_a_id=$1 AND user_b_id=$2) OR (user_a_id=$2 AND user_b_id=$1)")
        .bind(owner_id).bind(peer_id).fetch_one(&state.db).await.unwrap();
    assert_eq!(
        remaining, 0,
        "unblock does not recreate the deleted conversation"
    );
    let new_chat = create_chat(&app, ip, &a, &bid).await;
    assert_ne!(new_chat, chat);
    // Account-wide block is projected even when legacy preferences are absent.
    set_block(&app, ip, &a, &bid, true).await;
    sqlx::query("DELETE FROM chat_preferences WHERE chat_id=$1")
        .bind(Uuid::parse_str(&new_chat).unwrap())
        .execute(&state.db)
        .await
        .unwrap();
    let summaries = body_json(
        request(
            &app,
            ip,
            &a,
            "GET",
            &format!("/chats?chat_ids={new_chat}"),
            None,
        )
        .await,
    )
    .await;
    assert_eq!(summaries["items"][0]["is_blocked"], true);
    assert_eq!(summaries["items"][0]["blocked_user_id"], bid);
}

async fn credential(app: &axum::Router, ip: ConnectInfo<SocketAddr>) -> String {
    let response = request(app, ip, "", "POST", "/auth/installations", None).await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers().get("cache-control").unwrap(), "no-store");
    let value = body_json(response).await;
    let credential = value["installation_credential"].as_str().unwrap();
    assert_eq!(credential.len(), 43);
    credential.to_owned()
}

async fn register_proof(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    prefix: &str,
    client_id: Uuid,
    proof: Option<&str>,
) -> (String, String, Uuid, String) {
    let username = unique_username(prefix);
    let response = request(
        app,
        ip,
        "",
        "POST",
        "/auth/register",
        Some(json!({
        "username":username,"display_name":"Proof User","password":"correct-horse-battery",
        "installation_id":client_id,"installation_credential":proof,"device_platform":"android"})),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_owned(),
        body["user"]["id"].as_str().unwrap().to_owned(),
        Uuid::parse_str(body["device_id"].as_str().unwrap()).unwrap(),
        username,
    )
}

async fn login_proof(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    username: &str,
    client_id: Uuid,
    proof: Option<&str>,
) -> (String, Uuid) {
    let response = request(
        app,
        ip,
        "",
        "POST",
        "/auth/login",
        Some(json!({
        "username":username,"password":"correct-horse-battery","installation_id":client_id,
        "installation_credential":proof,"device_platform":"android"})),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_owned(),
        Uuid::parse_str(body["device_id"].as_str().unwrap()).unwrap(),
    )
}

#[tokio::test]
async fn verified_installation_blocks_alias_only_on_that_installation_not_a_clean_phone() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (a, aid, _) = register_user(&app, ip, "install_a_").await;
    let proof = credential(&app, ip).await;
    let legacy_id = Uuid::new_v4();
    let (b, bid, _, _) = register_proof(&app, ip, "install_b_", legacy_id, Some(&proof)).await;
    // Logout must clear account state without destroying the installation proof
    // which protects a blocker when this phone signs into a different account.
    assert_eq!(
        request(&app, ip, &b, "POST", "/auth/logout", None)
            .await
            .status(),
        StatusCode::NO_CONTENT
    );
    set_block(&app, ip, &a, &bid, true).await;
    let (c, cid, cdev, cname) =
        register_proof(&app, ip, "install_c_", legacy_id, Some(&proof)).await;
    let clean_proof = credential(&app, ip).await;
    let (clean, clean_device) =
        login_proof(&app, ip, &cname, Uuid::new_v4(), Some(&clean_proof)).await;
    let (spoof, _, _, _) = register_proof(&app, ip, "install_spoof_", legacy_id, None).await;
    let owner_id = Uuid::parse_str(&aid).unwrap();
    for (token, device, hidden) in [(&c, cdev, true), (&clean, clean_device, false)] {
        let claims =
            messenger_server::auth::jwt::decode_access_token(&state.config, token).unwrap();
        let auth = messenger_server::auth::middleware::AuthUser {
            user_id: claims.sub,
            device_id: device,
            session_id: claims.session_id,
        };
        let rows = messenger_server::presence::snapshot_for_device(&state, &auth, &[owner_id])
            .await
            .unwrap();
        assert_eq!(
            rows.is_empty(),
            hidden,
            "presence uses current device, never a global alias account ban"
        );
        let directory =
            body_json(request(&app, ip, token, "GET", "/users/directory?q=install_a", None).await)
                .await;
        assert_eq!(
            directory["items"]
                .as_array()
                .unwrap()
                .iter()
                .any(|u| u["id"] == aid),
            !hidden
        );
        let search =
            body_json(request(&app, ip, token, "GET", "/users/search?q=install_a", None).await)
                .await;
        assert_eq!(
            search["items"]
                .as_array()
                .unwrap()
                .iter()
                .any(|u| u["id"] == aid),
            !hidden
        );
        let direct = request(
            &app,
            ip,
            token,
            "POST",
            "/chats/direct",
            Some(json!({"other_user_id":aid})),
        )
        .await;
        assert_eq!(
            direct.status(),
            if hidden {
                StatusCode::NOT_FOUND
            } else {
                StatusCode::OK
            }
        );
    }
    let spoof_search =
        body_json(request(&app, ip, &spoof, "GET", "/users/search?q=install_a", None).await).await;
    assert!(
        spoof_search["items"]
            .as_array()
            .unwrap()
            .iter()
            .any(|u| u["id"] == aid),
        "self-reported UUID grants no restriction or proof"
    );
    // Account C itself is not exposed in A's blocked list.
    let list = blocked(&app, ip, &a, "").await;
    assert_eq!(list["items"].as_array().unwrap().len(), 1);
    assert_eq!(list["items"][0]["user"]["id"], bid);
    assert_ne!(list["items"][0]["user"]["id"], cid);
    // Owner-only management is not a profile-privacy bypass: C cannot add A
    // to its own list merely to recover A's hidden username/avatar/bio.
    set_block(&app, ip, &c, &aid, true).await;
    let hidden_profile = blocked(&app, ip, &c, "").await;
    assert_eq!(hidden_profile["items"][0]["user"]["id"], aid);
    assert_eq!(hidden_profile["items"][0]["user"]["username"], "");
    assert_eq!(
        hidden_profile["items"][0]["user"]["display_name"],
        "Недоступный аккаунт"
    );
    assert!(hidden_profile["items"][0]["user"]["bio"].is_null());
    assert!(hidden_profile["items"][0]["user"]["avatar_url"].is_null());
    set_block(&app, ip, &c, &aid, false).await;
    set_block(&app, ip, &a, &bid, false).await;
    let allowed = request(
        &app,
        ip,
        &c,
        "POST",
        "/chats/direct",
        Some(json!({"other_user_id":aid})),
    )
    .await;
    assert_eq!(
        allowed.status(),
        StatusCode::OK,
        "removing the source restriction reopens that installation"
    );
}

#[tokio::test]
async fn proof_cannot_be_forged_and_existing_blocks_capture_later_verified_binding() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (a, aid, _) = register_user(&app, ip, "bind_a_").await;
    let (b, bid, bdev, _) = register_proof(&app, ip, "bind_b_", Uuid::new_v4(), None).await;
    set_block(&app, ip, &a, &bid, true).await;
    let proof = credential(&app, ip).await;
    for invalid in [
        Uuid::new_v4().to_string(),
        "A".repeat(43),
        format!("{proof}="),
    ] {
        let response = request(
            &app,
            ip,
            &b,
            "POST",
            "/devices/me/installation",
            Some(json!({"installation_credential":invalid})),
        )
        .await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        assert_eq!(
            body_json(response).await["error"]["code"],
            "validation_error"
        );
    }
    let none: Option<Uuid> =
        sqlx::query_scalar("SELECT verified_installation_id FROM devices WHERE id=$1")
            .bind(bdev)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert!(
        none.is_none(),
        "failed proof attempts never overwrite the current device binding"
    );
    let response = request(
        &app,
        ip,
        &b,
        "POST",
        "/devices/me/installation",
        Some(json!({"installation_credential":proof})),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let (_, _, alias_device, _) =
        register_proof(&app, ip, "bind_alias_", Uuid::new_v4(), Some(&proof)).await;
    let blocked: bool = sqlx::query_scalar("SELECT user_device_blocked($1,$2)")
        .bind(alias_device)
        .bind(Uuid::parse_str(&aid).unwrap())
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert!(
        blocked,
        "binding after the original block captures the verified installation"
    );
    // Credential hashes are stored; raw credentials are neither ids nor DB values.
    let stored:Vec<u8>=sqlx::query_scalar("SELECT credential_hash FROM verified_installations v JOIN devices d ON d.verified_installation_id=v.id WHERE d.id=$1")
        .bind(bdev).fetch_one(&state.db).await.unwrap();
    assert_eq!(stored.len(), 32);
    assert_ne!(stored, proof.as_bytes());
    let no_auth = request(
        &app,
        ip,
        "",
        "POST",
        "/devices/me/installation",
        Some(json!({"installation_credential":proof})),
    )
    .await;
    assert_eq!(no_auth.status(), StatusCode::UNAUTHORIZED);
    let bad_login = request(
        &app,
        ip,
        "",
        "POST",
        "/auth/login",
        Some(json!({
        "username":"not_an_account","password":"incorrect","installation_id":Uuid::new_v4(),
        "device_platform":"android","installation_credential":proof})),
    )
    .await;
    assert_eq!(
        bad_login.status(),
        StatusCode::UNAUTHORIZED,
        "proof is not account authentication"
    );
}

#[tokio::test]
async fn deleted_block_sources_remain_manageable_and_unblocking_one_keeps_other_source() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (a, aid, _) = register_user(&app, ip, "source_a_").await;
    let proof = credential(&app, ip).await;
    let (b, bid, _, _) = register_proof(&app, ip, "source_b_", Uuid::new_v4(), Some(&proof)).await;
    let (_, did, _, _) = register_proof(&app, ip, "source_d_", Uuid::new_v4(), Some(&proof)).await;
    let (_, _, alias, _) =
        register_proof(&app, ip, "source_alias_", Uuid::new_v4(), Some(&proof)).await;
    set_block(&app, ip, &a, &bid, true).await;
    set_block(&app, ip, &a, &did, true).await;
    assert_eq!(
        request(
            &app,
            ip,
            &b,
            "POST",
            "/users/me/delete",
            Some(json!({"password":"correct-horse-battery"}))
        )
        .await
        .status(),
        StatusCode::NO_CONTENT
    );
    let list = blocked(&app, ip, &a, "").await;
    let deleted = list["items"]
        .as_array()
        .unwrap()
        .iter()
        .find(|v| v["user"]["id"] == bid)
        .expect("manageable tombstone");
    assert_eq!(deleted["user"]["display_name"], "Удалённый аккаунт");
    assert_eq!(deleted["user"]["username"], "");
    assert!(deleted["user"]["avatar_url"].is_null());
    set_block(&app, ip, &a, &did, false).await;
    let restricted = sqlx::query_scalar::<_, bool>("SELECT user_device_blocked($1,$2)")
        .bind(alias)
        .bind(Uuid::parse_str(&aid).unwrap())
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert!(
        restricted,
        "deleting B does not silently remove its source restriction"
    );
    set_block(&app, ip, &a, &bid, false).await;
    let remaining: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM user_block_installations WHERE user_id=$1")
            .bind(Uuid::parse_str(&aid).unwrap())
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(remaining, 0);
}

fn auth_for(state: &AppState, token: &str) -> messenger_server::auth::middleware::AuthUser {
    let claims = messenger_server::auth::jwt::decode_access_token(&state.config, token).unwrap();
    messenger_server::auth::middleware::AuthUser {
        user_id: claims.sub,
        device_id: claims.device_id,
        session_id: claims.session_id,
    }
}

#[tokio::test]
async fn restricted_alias_cannot_pull_receive_or_signal_but_its_clean_device_can() {
    use messenger_server::push::PushEvent;
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (a, aid, adev) = register_user(&app, ip, "fanout_a_").await;
    let proof = credential(&app, ip).await;
    let (_, bid, _, _) = register_proof(&app, ip, "fanout_b_", Uuid::new_v4(), Some(&proof)).await;
    let (c, cid, cdev, cname) =
        register_proof(&app, ip, "fanout_c_", Uuid::new_v4(), Some(&proof)).await;
    let clean_proof = credential(&app, ip).await;
    let (clean, clean_device) =
        login_proof(&app, ip, &cname, Uuid::new_v4(), Some(&clean_proof)).await;
    for token in [&c, &clean] {
        let response=request(&app,ip,token,"POST","/keys",Some(json!({
            "identity_key":"dGVzdA==","registration_id":12345,
            "signed_prekey":{"key_id":1,"public_key":"dGVzdA==","signature":"dGVzdA=="},
            "kyber_prekey":{"key_id":1,"public_key":"dGVzdA==","signature":"dGVzdA=="},"one_time_prekeys":[]}))).await;
        assert_eq!(response.status(), StatusCode::OK);
    }
    let chat = create_chat(&app, ip, &a, &cid).await;
    let chat_id = Uuid::parse_str(&chat).unwrap();
    set_block(&app, ip, &a, &bid, true).await;
    let (_, mut hidden_inbox) = state.ws_registry.register(cdev).await;
    let (_, mut clean_inbox) = state.ws_registry.register(clean_device).await;
    let owner_auth = auth_for(&state, &a);
    let hidden_auth = auth_for(&state, &c);
    let bundles =
        body_json(request(&app, ip, &a, "GET", &format!("/keys/devices/{cid}"), None).await).await;
    assert_eq!(bundles["devices"].as_array().unwrap().len(), 1);
    assert_eq!(bundles["devices"][0]["device_id"], json!(clean_device));
    // Even an obsolete client submitting a stale envelope list cannot leak a
    // message to the restricted device. The permitted clean phone still gets it.
    let sent = request(
        &app,
        ip,
        &a,
        "POST",
        &format!("/chats/{chat}/messages"),
        Some(json!({
        "client_message_id":Uuid::new_v4(),"envelopes":[
            {"recipient_device_id":cdev,"message_type":1,"ciphertext":"dGVzdA=="},
            {"recipient_device_id":clean_device,"message_type":1,"ciphertext":"dGVzdA=="}]})),
    )
    .await;
    assert_eq!(sent.status(), StatusCode::OK);
    let sent = body_json(sent).await;
    let message_id = Uuid::parse_str(sent["id"].as_str().unwrap()).unwrap();
    assert!(hidden_inbox.try_recv().is_err());
    let clean_event: Value = serde_json::from_str(&clean_inbox.try_recv().unwrap()).unwrap();
    assert_eq!(clean_event["type"], "message.new");
    assert_eq!(clean_event["message"]["id"], sent["id"]);
    let envelopes: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM message_envelopes WHERE message_id=$1 AND recipient_device_id=$2",
    )
    .bind(message_id)
    .bind(cdev)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(envelopes, 0);
    let (_, mut receipt_events) = state.ws_registry.register(adev).await;
    for action in ["delivered", "read"] {
        let response = request(
            &app,
            ip,
            &c,
            "POST",
            &format!("/chats/{chat}/{action}"),
            Some(json!({"sequence":sent["sequence"]})),
        )
        .await;
        assert_eq!(
            response.status(),
            StatusCode::CONFLICT,
            "restricted installation cannot forge {action}"
        );
        assert!(receipt_events.try_recv().is_err());
    }
    let untouched:(bool,bool)=sqlx::query_as("SELECT delivered_at IS NOT NULL,read_at IS NOT NULL FROM message_receipts WHERE message_id=$1 AND user_id=$2")
        .bind(message_id).bind(Uuid::parse_str(&cid).unwrap()).fetch_one(&state.db).await.unwrap();
    assert_eq!(
        untouched,
        (false, false),
        "denied receipt requests never mutate shared account receipts"
    );
    for action in ["delivered", "read"] {
        let response = request(
            &app,
            ip,
            &clean,
            "POST",
            &format!("/chats/{chat}/{action}"),
            Some(json!({"sequence":sent["sequence"]})),
        )
        .await;
        assert_eq!(
            response.status(),
            StatusCode::OK,
            "clean installation keeps working"
        );
        let receipt: Value = serde_json::from_str(&receipt_events.try_recv().unwrap()).unwrap();
        assert_eq!(receipt["type"], "receipt.updated");
    }
    for path in ["/sync".to_owned(), format!("/chats/{chat}/messages")] {
        let hidden = body_json(request(&app, ip, &c, "GET", &path, None).await).await;
        let visible = body_json(request(&app, ip, &clean, "GET", &path, None).await).await;
        let field = "messages";
        assert!(hidden[field].as_array().unwrap().is_empty());
        assert!(visible[field]
            .as_array()
            .unwrap()
            .iter()
            .any(|m| m["id"] == sent["id"]));
    }
    let message_push =
        PushEvent::new_message(chat_id, message_id, sent["sequence"].as_i64().unwrap());
    assert!(!messenger_server::users::blocks::allows_push(&state.db, cdev, &message_push).await);
    assert!(
        messenger_server::users::blocks::allows_push(&state.db, clean_device, &message_push).await
    );
    messenger_server::websocket::relay_draft(
        &state,
        &owner_auth,
        chat_id,
        Uuid::new_v4(),
        1,
        "opaque".into(),
        vec![
            messenger_server::websocket::protocol::DraftEnvelope {
                recipient_device_id: cdev,
                message_type: 1,
                ciphertext: "opaque".into(),
            },
            messenger_server::websocket::protocol::DraftEnvelope {
                recipient_device_id: clean_device,
                message_type: 1,
                ciphertext: "opaque".into(),
            },
        ],
    )
    .await
    .unwrap();
    assert!(
        hidden_inbox.try_recv().is_err(),
        "ephemeral drafts also filter recipient installations"
    );
    let clean_draft: Value = serde_json::from_str(&clean_inbox.try_recv().unwrap()).unwrap();
    assert_eq!(clean_draft["type"], "chat.draft");
    // Own multi-device echo must not bypass the restriction either.
    let send_back = request(
        &app,
        ip,
        &clean,
        "POST",
        &format!("/chats/{chat}/messages"),
        Some(json!({
        "client_message_id":Uuid::new_v4(),"envelopes":[
            {"recipient_device_id":adev,"message_type":1,"ciphertext":"dGVzdA=="},
            {"recipient_device_id":cdev,"message_type":1,"ciphertext":"dGVzdA=="}]})),
    )
    .await;
    assert_eq!(send_back.status(), StatusCode::OK);
    assert!(hidden_inbox.try_recv().is_err());
    assert_eq!(request(&app,ip,&c,"POST",&format!("/chats/{chat}/messages"),Some(json!({
        "client_message_id":Uuid::new_v4(),"envelopes":[{"recipient_device_id":adev,"message_type":1,"ciphertext":"dGVzdA=="}]}))).await.status(),StatusCode::CONFLICT);
    let call = request(
        &app,
        ip,
        &a,
        "POST",
        "/calls",
        Some(json!({"callee_user_id":cid,"media_kind":"video"})),
    )
    .await;
    assert_eq!(call.status(), StatusCode::OK);
    let call = body_json(call).await;
    let call_id = Uuid::parse_str(call["id"].as_str().unwrap()).unwrap();
    assert!(
        hidden_inbox.try_recv().is_err(),
        "restricted phone receives no ringing WS event"
    );
    let clean_ring: Value = serde_json::from_str(&clean_inbox.try_recv().unwrap()).unwrap();
    assert_eq!(clean_ring["type"], "call.ringing");
    assert!(
        body_json(request(&app, ip, &c, "GET", "/calls/active", None).await)
            .await
            .is_null()
    );
    assert_eq!(
        request(&app, ip, &c, "GET", &format!("/calls/{call_id}"), None)
            .await
            .status(),
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        request(
            &app,
            ip,
            &c,
            "POST",
            &format!("/calls/{call_id}/accept"),
            None
        )
        .await
        .status(),
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        request(
            &app,
            ip,
            &c,
            "POST",
            "/calls",
            Some(json!({"callee_user_id":aid,"media_kind":"audio"}))
        )
        .await
        .status(),
        StatusCode::CONFLICT
    );
    assert_eq!(
        request(&app, ip, &clean, "GET", &format!("/calls/{call_id}"), None)
            .await
            .status(),
        StatusCode::OK
    );
    messenger_server::calls::signaling::relay_offer(
        &state,
        &owner_auth,
        call_id,
        "opaque-test-sdp".into(),
    )
    .await
    .unwrap();
    assert!(hidden_inbox.try_recv().is_err());
    let offer: Value = serde_json::from_str(&clean_inbox.try_recv().unwrap()).unwrap();
    assert_eq!(offer["type"], "call.offer");
    assert!(messenger_server::calls::signaling::relay_ice(
        &state,
        &hidden_auth,
        call_id,
        "opaque".into(),
        None,
        None
    )
    .await
    .is_err());
    assert!(
        !messenger_server::users::blocks::allows_push(
            &state.db,
            cdev,
            &PushEvent::incoming_call(call_id, "video")
        )
        .await
    );
    assert!(
        messenger_server::users::blocks::allows_push(
            &state.db,
            clean_device,
            &PushEvent::incoming_call(call_id, "video")
        )
        .await
    );
    assert_eq!(
        request(
            &app,
            ip,
            &a,
            "POST",
            &format!("/calls/{call_id}/hangup"),
            None
        )
        .await
        .status(),
        StatusCode::OK
    );
    let hidden_history =
        body_json(request(&app, ip, &c, "GET", "/calls/history", None).await).await;
    let clean_history =
        body_json(request(&app, ip, &clean, "GET", "/calls/history", None).await).await;
    assert!(hidden_history["items"].as_array().unwrap().is_empty());
    assert!(clean_history["items"]
        .as_array()
        .unwrap()
        .iter()
        .any(|v| v["id"] == call["id"]));
}

async fn wait_for_user_lock_wait(db: &sqlx::PgPool) {
    for _ in 0..100 {
        let waiting:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM pg_stat_activity \
            WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'SELECT id FROM users%')")
            .fetch_one(db).await.unwrap();
        if waiting {
            return;
        }
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    }
    panic!("competing request did not wait for the held user lock");
}

#[tokio::test]
async fn first_verified_binding_and_block_serialize_in_both_orders() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    for block_first in [true, false] {
        let ip = connect_info();
        let (a, aid, _) = register_user(&app, ip, "race_block_a_").await;
        let (b, bid, bdev, _) =
            register_proof(&app, ip, "race_block_b_", Uuid::new_v4(), None).await;
        let proof = credential(&app, ip).await;
        let owner = Uuid::parse_str(&aid).unwrap();
        let peer = Uuid::parse_str(&bid).unwrap();
        let mut held = state.db.begin().await.unwrap();
        let pending = if block_first {
            sqlx::query("SELECT id FROM users WHERE id=ANY($1) ORDER BY id FOR UPDATE")
                .bind(vec![owner, peer])
                .execute(&mut *held)
                .await
                .unwrap();
            sqlx::query("INSERT INTO user_blocks(user_id,blocked_user_id) VALUES($1,$2)")
                .bind(owner)
                .bind(peer)
                .execute(&mut *held)
                .await
                .unwrap();
            let app = app.clone();
            let b = b.clone();
            let proof = proof.clone();
            tokio::spawn(async move {
                request(
                    &app,
                    ip,
                    &b,
                    "POST",
                    "/devices/me/installation",
                    Some(json!({"installation_credential":proof})),
                )
                .await
            })
        } else {
            let auth = auth_for(&state, &b);
            messenger_server::auth::middleware::lock_active_session(&mut held, &auth)
                .await
                .unwrap();
            let id = messenger_server::auth::installations::verify(&mut held, Some(&proof))
                .await
                .unwrap();
            messenger_server::auth::installations::bind_device(&mut held, peer, bdev, id)
                .await
                .unwrap();
            let app = app.clone();
            let a = a.clone();
            let bid = bid.clone();
            tokio::spawn(async move {
                request(&app, ip, &a, "POST", &format!("/users/{bid}/block"), None).await
            })
        };
        wait_for_user_lock_wait(&state.db).await;
        held.commit().await.unwrap();
        let response = tokio::time::timeout(std::time::Duration::from_secs(5), pending)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let captured: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM user_block_installations b \
            JOIN devices d ON d.verified_installation_id=b.installation_id \
            WHERE b.user_id=$1 AND b.blocked_user_id=$2 AND d.id=$3)",
        )
        .bind(owner)
        .bind(peer)
        .bind(bdev)
        .fetch_one(&state.db)
        .await
        .unwrap();
        assert!(
            captured,
            "block_first={block_first}: neither committed order can miss the verified membership"
        );
    }
}

#[tokio::test]
async fn blocking_an_active_explicit_pair_releases_slots_and_only_emits_terminal_cleanup() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (a, aid, adev) = register_user(&app, ip, "active_block_a_").await;
    let proof = credential(&app, ip).await;
    let (b, bid, bdev, _) =
        register_proof(&app, ip, "active_block_b_", Uuid::new_v4(), Some(&proof)).await;
    create_chat(&app, ip, &a, &bid).await;
    let call = request(
        &app,
        ip,
        &a,
        "POST",
        "/calls",
        Some(json!({"callee_user_id":bid,"media_kind":"audio"})),
    )
    .await;
    assert_eq!(call.status(), StatusCode::OK);
    let call_id = Uuid::parse_str(body_json(call).await["id"].as_str().unwrap()).unwrap();
    assert_eq!(
        request(
            &app,
            ip,
            &b,
            "POST",
            &format!("/calls/{call_id}/accept"),
            None
        )
        .await
        .status(),
        StatusCode::OK
    );
    let (_, mut a_events) = state.ws_registry.register(adev).await;
    let (_, mut b_events) = state.ws_registry.register(bdev).await;
    set_block(&app, ip, &a, &bid, true).await;
    let ended: (String, String) = sqlx::query_as("SELECT state,end_reason FROM calls WHERE id=$1")
        .bind(call_id)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(ended, ("ended".into(), "hangup".into()));
    let slots: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM call_participants_active WHERE call_id=$1")
            .bind(call_id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(
        slots, 0,
        "the block and busy-slot release commit atomically"
    );
    for inbox in [&mut a_events, &mut b_events] {
        let mut saw_cleanup = false;
        while let Ok(wire) = inbox.try_recv() {
            let event: Value = serde_json::from_str(&wire).unwrap();
            if event["type"] == "call.ended" {
                saw_cleanup = true;
                assert_eq!(event.as_object().unwrap().len(), 3);
                assert_eq!(event["call_id"], json!(call_id));
                assert!(event.get("caller_user_id").is_none());
                assert!(event.get("peer").is_none());
            }
        }
        assert!(
            saw_cleanup,
            "already ringing/calling UI still receives identity-free dismissal"
        );
    }
    assert_eq!(
        request(
            &app,
            ip,
            &b,
            "POST",
            &format!("/calls/{call_id}/hangup"),
            None
        )
        .await
        .status(),
        StatusCode::NOT_FOUND
    );
    // C shares the restricted installation, but its independent clean phone is
    // not globally banned. Repeating the explicit A->B block cannot end A->C.
    let (_, cid, _, cname) =
        register_proof(&app, ip, "active_clean_c_", Uuid::new_v4(), Some(&proof)).await;
    let clean_proof = credential(&app, ip).await;
    let (clean, _) = login_proof(&app, ip, &cname, Uuid::new_v4(), Some(&clean_proof)).await;
    create_chat(&app, ip, &a, &cid).await;
    let live = request(
        &app,
        ip,
        &a,
        "POST",
        "/calls",
        Some(json!({"callee_user_id":cid,"media_kind":"video"})),
    )
    .await;
    assert_eq!(live.status(), StatusCode::OK);
    let live_id = Uuid::parse_str(body_json(live).await["id"].as_str().unwrap()).unwrap();
    assert_eq!(
        request(
            &app,
            ip,
            &clean,
            "POST",
            &format!("/calls/{live_id}/accept"),
            None
        )
        .await
        .status(),
        StatusCode::OK
    );
    set_block(&app, ip, &a, &bid, true).await;
    let live_state: String = sqlx::query_scalar("SELECT state FROM calls WHERE id=$1")
        .bind(live_id)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(
        live_state, "accepted",
        "never terminate an alias account's independent-phone call"
    );
    assert_eq!(
        request(
            &app,
            ip,
            &a,
            "POST",
            &format!("/calls/{live_id}/hangup"),
            None
        )
        .await
        .status(),
        StatusCode::OK
    );
    let own = blocked(&app, ip, &a, "").await;
    assert_eq!(own["items"].as_array().unwrap().len(), 1);
    assert_ne!(own["items"][0]["user"]["id"], aid);
}

struct RecordingPush(std::sync::Arc<std::sync::atomic::AtomicUsize>);
#[async_trait::async_trait]
impl messenger_server::push::PushProvider for RecordingPush {
    async fn send(
        &self,
        _token: &str,
        _event: &messenger_server::push::PushEvent,
    ) -> Result<messenger_server::push::PushSendOutcome, messenger_server::push::PushError> {
        self.0.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        Ok(messenger_server::push::PushSendOutcome::Delivered)
    }
}

#[tokio::test]
async fn push_privacy_does_not_exhaust_a_single_connection_pool_and_rechecks_before_delivery() {
    let mut state = spawn_state().await;
    state.db = sqlx::postgres::PgPoolOptions::new()
        .max_connections(1)
        .connect(&state.config.database_url)
        .await
        .unwrap();
    let delivered = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
    state.push = messenger_server::push::build_with_provider(std::sync::Arc::new(RecordingPush(
        delivered.clone(),
    )));
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (a, _, _) = register_user(&app, ip, "pool_a_").await;
    let (b, bid, bdev) = register_user(&app, ip, "pool_b_").await;
    let registered = request(
        &app,
        ip,
        &b,
        "PUT",
        "/devices/me/push-token",
        Some(json!({"provider":"fcm","token":"task-owned-test-token"})),
    )
    .await;
    assert_eq!(registered.status(), StatusCode::OK);
    let chat = create_chat(&app, ip, &a, &bid).await;
    let send_path = format!("/chats/{chat}/messages");
    let send = request(
        &app,
        ip,
        &a,
        "POST",
        &send_path,
        Some(json!({
        "client_message_id":Uuid::new_v4(),"envelopes":[{"recipient_device_id":bdev,"message_type":1,"ciphertext":"dGVzdA=="}]})),
    );
    let sent=tokio::time::timeout(std::time::Duration::from_secs(3),send).await
        .expect("privacy checks must not borrow a pool connection from inside the held outbox transaction");
    assert_eq!(sent.status(), StatusCode::OK);
    let pending: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM push_outbox WHERE device_id=$1 AND delivered_at IS NULL",
    )
    .bind(bdev)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(pending, 1);
    set_block(&app, ip, &a, &bid, true).await;
    state.push.process_due(&state.db).await;
    assert_eq!(
        delivered.load(std::sync::atomic::Ordering::SeqCst),
        0,
        "a queued push cannot bypass a later committed block"
    );
    let outcome:(bool,Option<String>)=sqlx::query_as("SELECT delivered_at IS NOT NULL,last_error_code FROM push_outbox WHERE device_id=$1 ORDER BY created_at DESC LIMIT 1")
        .bind(bdev).fetch_one(&state.db).await.unwrap();
    assert_eq!(outcome, (true, Some("privacy_restricted".into())));
}

#[tokio::test]
async fn the_blocker_on_a_restricted_installation_can_access_own_keys_and_saved_sync() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (a, aid, _) = register_user(&app, ip, "self_scope_a_").await;
    let username = body_json(request(&app, ip, &a, "GET", "/users/me", None).await).await
        ["username"]
        .as_str()
        .unwrap()
        .to_owned();
    let proof = credential(&app, ip).await;
    let (_, bid, bdev, _) =
        register_proof(&app, ip, "self_scope_b_", Uuid::new_v4(), Some(&proof)).await;
    let chat = create_chat(&app, ip, &a, &bid).await;
    set_block(&app, ip, &a, &bid, true).await;
    let (on_same_phone, own_device) =
        login_proof(&app, ip, &username, Uuid::new_v4(), Some(&proof)).await;
    let upload=request(&app,ip,&on_same_phone,"POST","/keys",Some(json!({
        "identity_key":"dGVzdA==","registration_id":12345,
        "signed_prekey":{"key_id":1,"public_key":"dGVzdA==","signature":"dGVzdA=="},
        "kyber_prekey":{"key_id":1,"public_key":"dGVzdA==","signature":"dGVzdA=="},"one_time_prekeys":[]}))).await;
    assert_eq!(upload.status(), StatusCode::OK);
    for path in [
        format!("/keys/devices/{aid}"),
        format!("/keys/bundles/{aid}"),
    ] {
        let response = request(&app, ip, &on_same_phone, "GET", &path, None).await;
        assert_eq!(
            response.status(),
            StatusCode::OK,
            "a privacy restriction never blocks its owner's own keys"
        );
        let body = body_json(response).await;
        assert!(body["devices"]
            .as_array()
            .unwrap()
            .iter()
            .any(|d| d["device_id"] == json!(own_device)));
    }
    let saved = request(&app, ip, &a, "GET", "/chats/saved", None).await;
    assert_eq!(saved.status(), StatusCode::OK);
    let saved = body_json(saved).await;
    let saved_id = saved["chat_id"].as_str().unwrap();
    let sent=request(&app,ip,&a,"POST",&format!("/chats/{saved_id}/messages"),Some(json!({
        "client_message_id":Uuid::new_v4(),"envelopes":[{"recipient_device_id":own_device,"message_type":1,"ciphertext":"dGVzdA=="}]}))).await;
    assert_eq!(sent.status(), StatusCode::OK);
    let sent = body_json(sent).await;
    let sync = body_json(request(&app, ip, &on_same_phone, "GET", "/sync", None).await).await;
    assert!(sync["messages"]
        .as_array()
        .unwrap()
        .iter()
        .any(|m| m["id"] == sent["id"]));
    let still_blocked=request(&app,ip,&on_same_phone,"POST",&format!("/chats/{chat}/messages"),Some(json!({
        "client_message_id":Uuid::new_v4(),"envelopes":[{"recipient_device_id":bdev,"message_type":1,"ciphertext":"dGVzdA=="}]}))).await;
    assert_eq!(still_blocked.status(), StatusCode::CONFLICT);
}

#[tokio::test]
async fn restricted_receipts_recheck_a_block_committed_after_admission() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (a, aid, _) = register_user(&app, ip, "receipt_race_a_").await;
    let proof = credential(&app, ip).await;
    let (_, bid, _, _) =
        register_proof(&app, ip, "receipt_race_b_", Uuid::new_v4(), Some(&proof)).await;
    let (c, cid, cdev, _) =
        register_proof(&app, ip, "receipt_race_c_", Uuid::new_v4(), Some(&proof)).await;
    let chat = create_chat(&app, ip, &a, &cid).await;
    let sent=body_json(request(&app,ip,&a,"POST",&format!("/chats/{chat}/messages"),Some(json!({
        "client_message_id":Uuid::new_v4(),"envelopes":[{"recipient_device_id":cdev,"message_type":1,"ciphertext":"dGVzdA=="}]}))).await).await;
    let owner = Uuid::parse_str(&aid).unwrap();
    let peer = Uuid::parse_str(&bid).unwrap();
    for action in ["read", "delivered"] {
        let mut held = state.db.begin().await.unwrap();
        sqlx::query("SELECT id FROM users WHERE id=ANY($1) ORDER BY id FOR UPDATE")
            .bind(vec![owner, peer])
            .execute(&mut *held)
            .await
            .unwrap();
        sqlx::query("INSERT INTO user_blocks(user_id,blocked_user_id) VALUES($1,$2)")
            .bind(owner)
            .bind(peer)
            .execute(&mut *held)
            .await
            .unwrap();
        let app_clone = app.clone();
        let token = c.clone();
        let path = format!("/chats/{chat}/{action}");
        let sequence = sent["sequence"].clone();
        let waiting = tokio::spawn(async move {
            request(
                &app_clone,
                ip,
                &token,
                "POST",
                &path,
                Some(json!({"sequence":sequence})),
            )
            .await
        });
        wait_for_user_lock_wait(&state.db).await;
        held.commit().await.unwrap();
        let response = tokio::time::timeout(std::time::Duration::from_secs(5), waiting)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(
            response.status(),
            StatusCode::CONFLICT,
            "{action} must recheck after waiting for the block lock"
        );
        let untouched:(bool,bool)=sqlx::query_as("SELECT delivered_at IS NOT NULL,read_at IS NOT NULL FROM message_receipts WHERE message_id=$1 AND user_id=$2")
            .bind(Uuid::parse_str(sent["id"].as_str().unwrap()).unwrap()).bind(Uuid::parse_str(&cid).unwrap())
            .fetch_one(&state.db).await.unwrap();
        assert_eq!(untouched, (false, false));
        set_block(&app, ip, &a, &bid, false).await;
    }
}

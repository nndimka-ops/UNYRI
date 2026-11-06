//! ADMIN PRODUCT PACK 2 - server-side security and behaviour coverage for the
//! cross-platform moderation core.
//!
//! Every test here asserts the *server* contract. Client behaviour is covered
//! separately on Android and iOS.
//!
//! The suite shares one PostgreSQL test database with every other test binary
//! (they run in parallel processes), so assertions about table-wide counters are
//! deliberately written as lower bounds, or scoped to rows this test exclusively
//! owns (fresh UUIDs it just created). Exact global totals are never asserted -
//! that would make the suite order-dependent and flaky rather than stronger.
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

/// Each test gets its own fake client IP so the Redis-backed rate limiter keyed
/// by IP cannot cause cross-test interference.
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
    installation: Uuid,
) -> Request<Body> {
    let body = json!({
        "username": username,
        "password": "correct-horse-battery",
        "display_name": "Test User",
        "installation_id": installation,
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

fn login_request(ip: ConnectInfo<SocketAddr>, username: &str, installation: Uuid) -> Request<Body> {
    let body = json!({
        "username": username,
        "password": "correct-horse-battery",
        "installation_id": installation,
        "device_platform": "android",
        "device_name": "Test Device",
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

fn post_json_request(
    uri: &str,
    ip: ConnectInfo<SocketAddr>,
    token: &str,
    body: Value,
) -> Request<Body> {
    Request::builder()
        .method("POST")
        .uri(uri)
        .header("content-type", "application/json")
        .header("authorization", format!("Bearer {token}"))
        .extension(ip)
        .body(Body::from(body.to_string()))
        .unwrap()
}

fn patch_json_request(
    uri: &str,
    ip: ConnectInfo<SocketAddr>,
    token: &str,
    body: Value,
) -> Request<Body> {
    Request::builder()
        .method("PATCH")
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

struct Account {
    token: String,
    id: Uuid,
    username: String,
    installation: Uuid,
}

async fn register(app: &axum::Router, ip: ConnectInfo<SocketAddr>, prefix: &str) -> Account {
    let username = unique_username(prefix);
    let installation = Uuid::new_v4();
    let response = app
        .clone()
        .oneshot(register_request(ip, &username, installation))
        .await
        .unwrap();
    assert_eq!(
        response.status(),
        StatusCode::OK,
        "registration must succeed"
    );
    let body = body_json(response).await;
    Account {
        token: body["access_token"].as_str().unwrap().to_string(),
        id: Uuid::parse_str(body["user"]["id"].as_str().unwrap()).unwrap(),
        username,
        installation,
    }
}

async fn admin_key_proof(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    token: &str,
    purpose: &str,
    key: &ring::signature::Ed25519KeyPair,
) -> Value {
    use base64::Engine;
    use ring::signature::KeyPair;
    let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD;
    let public = b64.encode(key.public_key().as_ref());
    let response = app
        .clone()
        .oneshot(post_json_request(
            "/admin/key-challenge",
            ip,
            token,
            json!({"purpose": purpose, "public_key": public}),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let data = body_json(response).await;
    let text = format!(
        "COCO-ADMIN-PROOF-V1\n{}\n{}\n{}\n{}\n{}\n{}\n{}",
        data["server_id"].as_str().unwrap(),
        data["user_id"].as_str().unwrap(),
        data["auth_session"].as_str().unwrap(),
        data["challenge_id"].as_str().unwrap(),
        purpose,
        public,
        data["nonce"].as_str().unwrap()
    );
    json!({
        "challenge_id": data["challenge_id"],
        "signature": b64.encode(key.sign(text.as_bytes()).as_ref())
    })
}

/// Grants `role` locally and completes the full three-factor unlock, returning a
/// live administrative token.
async fn unlock_admin(
    app: &axum::Router,
    state: &AppState,
    ip: ConnectInfo<SocketAddr>,
    account: &Account,
    role: &str,
) -> String {
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,$2)")
        .bind(account.id)
        .bind(role)
        .execute(&state.db)
        .await
        .unwrap();
    let credentials = json!({"password": "correct-horse-battery", "code": "000000"});
    let setup = app
        .clone()
        .oneshot(post_json_request(
            "/admin/setup",
            ip,
            &account.token,
            credentials,
        ))
        .await
        .unwrap();
    assert_eq!(
        setup.status(),
        StatusCode::OK,
        "administrative setup must succeed"
    );
    assert_eq!(setup.headers()["cache-control"], "no-store");
    let secret =
        totp_rs::Secret::try_from_base32(body_json(setup).await["secret"].as_str().unwrap())
            .unwrap();
    let otp = totp_rs::Builder::new().with_secret(secret).build().unwrap();
    // A stable seed per account keeps the two enrolled administrators distinct.
    let seed = account.id.as_bytes()[0].max(1);
    let key = ring::signature::Ed25519KeyPair::from_seed_unchecked(&[seed; 32]).unwrap();
    let mut login = json!({
        "password": "correct-horse-battery",
        "code": otp.generate_current().to_string()
    });
    login["key_proof"] = admin_key_proof(app, ip, &account.token, "unlock", &key).await;
    let unlocked = app
        .clone()
        .oneshot(post_json_request(
            "/admin/unlock",
            ip,
            &account.token,
            login,
        ))
        .await
        .unwrap();
    assert_eq!(unlocked.status(), StatusCode::OK, "unlock must succeed");
    body_json(unlocked).await["token"]
        .as_str()
        .unwrap()
        .to_string()
}

fn with_admin(mut request: Request<Body>, token: &str) -> Request<Body> {
    request
        .headers_mut()
        .insert("x-coco-admin", token.parse().unwrap());
    request
}

/// Creates a direct chat and a user report about `target`, returning the report id.
async fn seed_report(
    app: &axum::Router,
    state: &AppState,
    ip: ConnectInfo<SocketAddr>,
    reporter: &Account,
    target: &Account,
    reason: &str,
) -> Uuid {
    let response = app
        .clone()
        .oneshot(post_json_request(
            "/chats/direct",
            ip,
            &reporter.token,
            json!({"other_user_id": target.id}),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let chat: Uuid =
        Uuid::parse_str(body_json(response).await["chat_id"].as_str().unwrap()).unwrap();
    let id = Uuid::new_v4();
    sqlx::query("INSERT INTO chat_reports(id,chat_id,reporter_user_id,reported_user_id,reason,description) VALUES($1,$2,$3,$4,$5::chat_report_reason,$6)")
        .bind(id)
        .bind(chat)
        .bind(reporter.id)
        .bind(target.id)
        .bind(reason)
        .bind("seed report")
        .execute(&state.db)
        .await
        .unwrap();
    id
}

/// 1x1 transparent PNG, base64 - the read path re-checks the image magic header.
const PNG_BASE64: &str = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==";

async fn seed_feedback(state: &AppState, owner: Uuid, description: &str) -> Uuid {
    let id = Uuid::new_v4();
    let payload = json!({
        "id": id,
        "description": description,
        "platform": "android",
        "app_version": "0.2.0",
        "os_version": "16",
        "screenshots": [{"mime_type": "image/png", "data_base64": PNG_BASE64}],
    });
    sqlx::query("INSERT INTO bug_reports(id,user_id,payload) VALUES($1,$2,$3)")
        .bind(id)
        .bind(owner)
        .bind(payload)
        .execute(&state.db)
        .await
        .unwrap();
    id
}

/// Every Pack 2 read route that must be indistinguishable from a missing route.
const PACK2_GET_PATHS: [&str; 9] = [
    "/admin/summary",
    "/admin/users",
    "/admin/users?query=a",
    "/admin/audit?action=suspend",
    "/admin/reports?status=open",
    "/admin/reports?reported_user_id=00000000-0000-0000-0000-000000000000",
    "/admin/feedback?platform=ios",
    "/admin/users/00000000-0000-0000-0000-000000000000/notes",
    "/admin/users/00000000-0000-0000-0000-000000000000/timeline",
];

#[tokio::test]
async fn every_pack2_admin_route_is_invisible_to_ordinary_accounts() {
    std::env::set_var("COCO_ADMIN_KEY", "11".repeat(32));
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let account = register(&app, ip, "pack2_plain_").await;
    let peer = register(&app, ip, "pack2_peer_").await;
    seed_report(&app, &state, ip, &account, &peer, "spam").await;
    let feedback = seed_feedback(&state, peer.id, "Camera is black").await;

    // Anti-probing: a normal account sees 404, never 403, on every new route.
    for path in PACK2_GET_PATHS {
        assert_eq!(
            app.clone()
                .oneshot(get_request(path, ip, Some(&account.token)))
                .await
                .unwrap()
                .status(),
            StatusCode::NOT_FOUND,
            "ordinary account reached {path}"
        );
    }
    assert_eq!(
        app.clone()
            .oneshot(get_request("/admin/summary", ip, None))
            .await
            .unwrap()
            .status(),
        StatusCode::UNAUTHORIZED,
        "an unauthenticated caller must not even reach the role check"
    );

    for (path, body) in [
        (
            format!("/admin/users/{}/notes", peer.id),
            json!({"body": "leak"}),
        ),
        (
            format!("/admin/installations/{}/suspension", peer.installation),
            json!({"blocked": true, "reason": "leak"}),
        ),
        (
            format!("/admin/feedback/{feedback}/review"),
            json!({"status": "resolved", "decision_reason": "leak"}),
        ),
        (
            format!("/admin/users/{}/suspension", peer.id),
            json!({"blocked": true, "reason": "leak"}),
        ),
    ] {
        assert_eq!(
            app.clone()
                .oneshot(post_json_request(&path, ip, &account.token, body))
                .await
                .unwrap()
                .status(),
            StatusCode::NOT_FOUND,
            "ordinary account reached {path}"
        );
    }
    for path in [
        format!("/admin/feedback/{feedback}"),
        format!("/admin/feedback/{feedback}/screenshots/0"),
        format!("/admin/users/{}/timeline", peer.id),
        format!("/admin/users/{}/notes", peer.id),
    ] {
        assert_eq!(
            app.clone()
                .oneshot(get_request(&path, ip, Some(&account.token)))
                .await
                .unwrap()
                .status(),
            StatusCode::NOT_FOUND,
            "ordinary account reached {path}"
        );
    }

    // Nothing was written by any rejected write. Scoped to this test's accounts
    // so the assertion stays valid alongside parallel test binaries.
    let notes: i64 =
        sqlx::query_scalar("SELECT count(*) FROM admin_notes WHERE subject_user_id=$1")
            .bind(peer.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(notes, 0);
    let audit: i64 = sqlx::query_scalar("SELECT count(*) FROM admin_audit WHERE actor=$1")
        .bind(account.id)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(
        audit, 0,
        "an ordinary account can never produce an audit row"
    );
    let restrictions: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM installation_suspensions WHERE installation_id=$1",
    )
    .bind(peer.installation)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(restrictions, 0);
    let status: String =
        sqlx::query_scalar("SELECT review_status FROM chat_reports WHERE reported_user_id=$1")
            .bind(peer.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(status, "open");
}

#[tokio::test]
async fn admin_operations_controls_roles_recovery_and_public_notice_are_enforced() {
    std::env::set_var("COCO_ADMIN_KEY", "11".repeat(32));
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let owner = register(&app, ip, "ops_owner_").await;
    let subject = register(&app, ip, "ops_subject_").await;
    let admin = unlock_admin(&app, &state, ip, &owner, "owner").await;

    // The centre is deliberately invisible without a live administrative
    // session, even to an authenticated account.
    assert_eq!(
        app.clone()
            .oneshot(get_request("/admin/operations", ip, Some(&subject.token)))
            .await
            .unwrap()
            .status(),
        StatusCode::NOT_FOUND
    );

    let overview = app
        .clone()
        .oneshot(with_admin(
            get_request("/admin/operations", ip, Some(&owner.token)),
            &admin,
        ))
        .await
        .unwrap();
    assert_eq!(overview.status(), StatusCode::OK);
    assert_eq!(overview.headers()["cache-control"], "no-store");
    let overview = body_json(overview).await;
    assert_eq!(overview["backups"]["restore_model"], "client_encrypted");
    assert!(overview["live"]["users"].as_i64().unwrap() >= 2);
    assert!(overview["live"].get("connections").is_some());

    let controls = app
        .clone()
        .oneshot(with_admin(
            post_json_request(
                "/admin/operations/controls",
                ip,
                &owner.token,
                json!({
                    "min_android_version": "0.2.1-alpha",
                    "min_ios_version": "0.2.1",
                    "min_desktop_version": "0.3.0",
                    "registration_open": true,
                    "automatic_login_protection": true,
                    "login_ip_limit": 7,
                    "login_account_limit": 13,
                    "reason": "Operations integration test"
                }),
            ),
            &admin,
        ))
        .await
        .unwrap();
    assert_eq!(controls.status(), StatusCode::OK);

    let info = app
        .clone()
        .oneshot(get_request("/server-info", ip, None))
        .await
        .unwrap();
    assert_eq!(info.status(), StatusCode::OK);
    let info = body_json(info).await;
    assert_eq!(info["minimum_versions"]["android"], "0.2.1-alpha");
    assert_eq!(info["minimum_versions"]["ios"], "0.2.1");
    assert_eq!(info["minimum_versions"]["desktop"], "0.3.0");
    assert_eq!(info["registration_open"], true);

    let expires_at = (chrono::Utc::now() + chrono::Duration::hours(2)).to_rfc3339();
    let published = app
        .clone()
        .oneshot(with_admin(
            post_json_request(
                "/admin/operations/announcements",
                ip,
                &owner.token,
                json!({
                    "title": "Плановое обслуживание",
                    "body": "Сервис будет обновлён без доступа к содержимому переписки.",
                    "severity": "warning",
                    "expires_at": expires_at,
                    "reason": "Operations integration test"
                }),
            ),
            &admin,
        ))
        .await
        .unwrap();
    assert_eq!(published.status(), StatusCode::OK);
    let announcement_id = body_json(published).await["id"]
        .as_str()
        .unwrap()
        .to_string();

    let info = body_json(
        app.clone()
            .oneshot(get_request("/server-info", ip, None))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(info["announcement"]["severity"], "warning");
    assert_eq!(info["announcement"]["title"], "Плановое обслуживание");

    let grant = app
        .clone()
        .oneshot(with_admin(
            post_json_request(
                &format!("/admin/operators/{}", subject.id),
                ip,
                &owner.token,
                json!({"role":"moderator","reason":"Operations integration test"}),
            ),
            &admin,
        ))
        .await
        .unwrap();
    assert_eq!(grant.status(), StatusCode::OK);
    let role: String =
        sqlx::query_scalar("SELECT role FROM server_administrators WHERE user_id=$1")
            .bind(subject.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(role, "moderator");

    // A reset must remove the TOTP seed instead of merely hiding it in UI.
    sqlx::query("UPDATE server_administrators SET secret=$2 WHERE user_id=$1")
        .bind(subject.id)
        .bind(vec![7_u8; 32])
        .execute(&state.db)
        .await
        .unwrap();
    let reset = app
        .clone()
        .oneshot(with_admin(
            post_json_request(
                &format!("/admin/operators/{}/reset-2fa", subject.id),
                ip,
                &owner.token,
                json!({"reason":"Operations integration test"}),
            ),
            &admin,
        ))
        .await
        .unwrap();
    assert_eq!(reset.status(), StatusCode::OK);
    let secret: Option<Vec<u8>> =
        sqlx::query_scalar("SELECT secret FROM server_administrators WHERE user_id=$1")
            .bind(subject.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert!(secret.is_none());

    let recovery = app
        .clone()
        .oneshot(with_admin(
            post_json_request(
                &format!("/admin/users/{}/recovery-code", subject.id),
                ip,
                &owner.token,
                json!({"reason":"Verified account recovery"}),
            ),
            &admin,
        ))
        .await
        .unwrap();
    assert_eq!(recovery.status(), StatusCode::OK);
    let recovery = body_json(recovery).await;
    let recovery_code = recovery["code"].as_str().unwrap();
    assert!(recovery["single_use"].as_bool().unwrap());
    assert!(!recovery_code.is_empty());
    let (stored_hash, expires, issuer): (
        Vec<u8>,
        Option<chrono::DateTime<chrono::Utc>>,
        Option<Uuid>,
    ) = sqlx::query_as(
        "SELECT code_hash,expires_at,issued_by_admin FROM account_recovery WHERE user_id=$1",
    )
    .bind(subject.id)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_ne!(
        stored_hash,
        recovery_code.as_bytes(),
        "the plaintext recovery code must never be stored"
    );
    assert!(expires.unwrap() > chrono::Utc::now());
    assert_eq!(issuer, Some(owner.id));

    let detail = app
        .clone()
        .oneshot(with_admin(
            get_request(
                &format!("/admin/users/{}", subject.id),
                ip,
                Some(&owner.token),
            ),
            &admin,
        ))
        .await
        .unwrap();
    assert_eq!(detail.status(), StatusCode::OK);
    let detail = body_json(detail).await;
    assert!(detail["security"]["risk_score"].as_i64().is_some());
    assert!(detail["security"]["risk_level"].as_str().is_some());
    assert!(detail["security"]["reasons"].is_array());

    let revoke = app
        .clone()
        .oneshot(with_admin(
            post_json_request(
                &format!("/admin/operators/{}", subject.id),
                ip,
                &owner.token,
                json!({"role":"revoke","reason":"Operations integration test"}),
            ),
            &admin,
        ))
        .await
        .unwrap();
    assert_eq!(revoke.status(), StatusCode::OK);
    let remains: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM server_administrators WHERE user_id=$1)")
            .bind(subject.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert!(!remains);

    let disabled = app
        .clone()
        .oneshot(with_admin(
            post_json_request(
                &format!("/admin/operations/announcements/{announcement_id}/disable"),
                ip,
                &owner.token,
                json!({}),
            ),
            &admin,
        ))
        .await
        .unwrap();
    assert_eq!(disabled.status(), StatusCode::OK);
    let info = body_json(
        app.clone()
            .oneshot(get_request("/server-info", ip, None))
            .await
            .unwrap(),
    )
    .await;
    assert!(info["announcement"].is_null());

    let audited: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM admin_audit WHERE actor=$1 AND action IN ('operations:controls','announcement:publish','announcement:disable','operator:role','operator:reset_2fa','credential:recovery_code')",
    )
    .bind(owner.id)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(audited, 7);
}

#[tokio::test]
async fn pagination_is_cursor_bounded_validated_and_filter_scoped() {
    std::env::set_var("COCO_ADMIN_KEY", "11".repeat(32));
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let owner = register(&app, ip, "pack2_page_owner_").await;
    let admin = unlock_admin(&app, &state, ip, &owner, "owner").await;

    let reporter = register(&app, ip, "pack2_page_rep_").await;
    let alice = register(&app, ip, "pack2_page_alice_").await;
    let bob = register(&app, ip, "pack2_page_bob_").await;
    for _ in 0..3 {
        seed_report(&app, &state, ip, &reporter, &alice, "spam").await;
    }
    for _ in 0..2 {
        seed_report(&app, &state, ip, &reporter, &bob, "harassment").await;
    }
    let alice_scope = format!("reported_user_id={}", alice.id);
    let bob_scope = format!("reported_user_id={}", bob.id);

    // Page size is bounded: a caller cannot ask for the whole table.
    for bad in ["0", "-1", "101", "1000000"] {
        assert_eq!(
            app.clone()
                .oneshot(with_admin(
                    get_request(
                        &format!("/admin/reports?limit={bad}"),
                        ip,
                        Some(&owner.token)
                    ),
                    &admin
                ))
                .await
                .unwrap()
                .status(),
            StatusCode::BAD_REQUEST,
            "limit={bad} must be rejected"
        );
    }

    // Walk one account's reports two rows at a time: no row is lost or repeated,
    // and a terminal page never advertises another one.
    let mut seen: Vec<String> = vec![];
    let mut cursor: Option<String> = None;
    for _ in 0..8 {
        let path = match &cursor {
            Some(value) => format!("/admin/reports?limit=2&{alice_scope}&cursor={value}"),
            None => format!("/admin/reports?limit=2&{alice_scope}"),
        };
        let page = body_json(
            app.clone()
                .oneshot(with_admin(
                    get_request(&path, ip, Some(&owner.token)),
                    &admin,
                ))
                .await
                .unwrap(),
        )
        .await;
        for item in page["items"].as_array().unwrap() {
            assert_eq!(item["user_id"], alice.id.to_string());
            seen.push(item["id"].as_str().unwrap().to_string());
        }
        if !page["has_more"].as_bool().unwrap() {
            assert!(
                page["next_cursor"].is_null(),
                "a terminal page must not offer a cursor"
            );
            break;
        }
        cursor = Some(page["next_cursor"].as_str().unwrap().to_string());
    }
    assert_eq!(
        seen.len(),
        3,
        "pagination lost or duplicated rows: {seen:?}"
    );
    assert_eq!(
        seen.len(),
        seen.iter().collect::<std::collections::HashSet<_>>().len()
    );

    // A tampered or foreign cursor is refused, not silently reinterpreted.
    let first = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/reports?limit=1&{alice_scope}"),
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    let good = first["next_cursor"].as_str().unwrap().to_string();
    let mut flipped = good.clone();
    flipped.replace_range(0..1, if good.starts_with('A') { "B" } else { "A" });
    for bad in ["not-a-cursor".to_string(), "A".repeat(600), flipped] {
        assert_eq!(
            app.clone()
                .oneshot(with_admin(
                    get_request(
                        &format!("/admin/reports?limit=1&{alice_scope}&cursor={bad}"),
                        ip,
                        Some(&owner.token)
                    ),
                    &admin
                ))
                .await
                .unwrap()
                .status(),
            StatusCode::BAD_REQUEST,
            "cursor {bad:?} must be rejected"
        );
    }
    // An absent cursor is simply "the first page" - it is not an error.
    for empty in ["", "&cursor="] {
        assert_eq!(
            app.clone()
                .oneshot(with_admin(
                    get_request(
                        &format!("/admin/reports?limit=1&{alice_scope}{empty}"),
                        ip,
                        Some(&owner.token)
                    ),
                    &admin
                ))
                .await
                .unwrap()
                .status(),
            StatusCode::OK
        );
    }
    // A cursor is bound to the filter set that produced it, so it cannot be
    // replayed to step through a different - possibly wider - selection.
    for foreign in [
        format!("/admin/reports?limit=1&{alice_scope}&status=resolved&cursor={good}"),
        format!("/admin/reports?limit=1&{bob_scope}&cursor={good}"),
    ] {
        assert_eq!(
            app.clone()
                .oneshot(with_admin(
                    get_request(&foreign, ip, Some(&owner.token)),
                    &admin
                ))
                .await
                .unwrap()
                .status(),
            StatusCode::BAD_REQUEST,
            "{foreign} must be rejected"
        );
    }

    // Filters are applied in SQL, so no page can step outside them.
    let mut filtered: Vec<String> = vec![];
    let mut cursor: Option<String> = None;
    for _ in 0..8 {
        let path = match &cursor {
            Some(value) => {
                format!("/admin/reports?limit=1&reason=spam&{alice_scope}&cursor={value}")
            }
            None => format!("/admin/reports?limit=1&reason=spam&{alice_scope}"),
        };
        let page = body_json(
            app.clone()
                .oneshot(with_admin(
                    get_request(&path, ip, Some(&owner.token)),
                    &admin,
                ))
                .await
                .unwrap(),
        )
        .await;
        for item in page["items"].as_array().unwrap() {
            assert_eq!(item["reason"], "spam");
            assert_eq!(item["user_id"], alice.id.to_string());
            filtered.push(item["id"].as_str().unwrap().to_string());
        }
        if !page["has_more"].as_bool().unwrap() {
            break;
        }
        cursor = Some(page["next_cursor"].as_str().unwrap().to_string());
    }
    assert_eq!(filtered.len(), 3);

    let by_user = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/reports?{bob_scope}"),
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(by_user["items"].as_array().unwrap().len(), 2);
    assert_eq!(by_user["has_more"], false);

    // bob's reports are all open, so a resolved filter over bob is empty. This
    // stays true regardless of what other test binaries are doing.
    assert_eq!(
        body_json(
            app.clone()
                .oneshot(with_admin(
                    get_request(
                        &format!("/admin/reports?status=resolved&{bob_scope}"),
                        ip,
                        Some(&owner.token)
                    ),
                    &admin
                ))
                .await
                .unwrap()
        )
        .await["items"]
            .as_array()
            .unwrap()
            .len(),
        0
    );
    assert_eq!(
        body_json(
            app.clone()
                .oneshot(with_admin(
                    get_request(
                        &format!("/admin/reports?from=2000-01-01T00:00:00Z&{bob_scope}"),
                        ip,
                        Some(&owner.token)
                    ),
                    &admin
                ))
                .await
                .unwrap()
        )
        .await["items"]
            .as_array()
            .unwrap()
            .len(),
        2
    );

    // Unknown enum values and malformed filters are rejected outright.
    for path in [
        "/admin/reports?status=deleted".to_string(),
        "/admin/reports?reason=invalid".to_string(),
        "/admin/reports?reported_user_id=not-a-uuid".to_string(),
        "/admin/reports?from=yesterday".to_string(),
        "/admin/feedback?platform=windows".to_string(),
        "/admin/users?status=nonsense".to_string(),
        "/admin/audit?actor=not-a-uuid".to_string(),
        format!("/admin/users?query={}", "z".repeat(101)),
    ] {
        assert_eq!(
            app.clone()
                .oneshot(with_admin(
                    get_request(&path, ip, Some(&owner.token)),
                    &admin
                ))
                .await
                .unwrap()
                .status(),
            StatusCode::BAD_REQUEST,
            "{path} must be rejected"
        );
    }

    // A free-text filter shaped like SQL is bound as a literal: it matches
    // nothing and never becomes a query error or a wider selection.
    for path in [
        "/admin/audit?action=%27%20OR%201%3D1--",
        "/admin/users?query=%27%20OR%201%3D1--",
        "/admin/audit?action=feedback%3Aopen%27%20OR%20%271%27%3D%271",
    ] {
        let response = app
            .clone()
            .oneshot(with_admin(
                get_request(path, ip, Some(&owner.token)),
                &admin,
            ))
            .await
            .unwrap();
        assert_eq!(
            response.status(),
            StatusCode::OK,
            "{path} must not become a query error"
        );
        assert_eq!(
            body_json(response).await["items"].as_array().unwrap().len(),
            0,
            "{path} must match nothing"
        );
    }
    // An enumerated filter never reaches SQL at all: an unknown value is refused
    // by the whitelist before a statement is built.
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    "/admin/reports?reason=spam%27%20OR%20%271%27%3D%271",
                    ip,
                    Some(&owner.token)
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::BAD_REQUEST,
        "an enumerated filter is whitelisted, so an injected value is rejected outright"
    );

    // Every list carries the same page envelope.
    for path in [
        "/admin/reports",
        "/admin/feedback",
        "/admin/users",
        "/admin/audit",
    ] {
        let page = body_json(
            app.clone()
                .oneshot(with_admin(
                    get_request(path, ip, Some(&owner.token)),
                    &admin,
                ))
                .await
                .unwrap(),
        )
        .await;
        assert!(page["items"].is_array(), "{path} has no items array");
        assert!(page["has_more"].is_boolean(), "{path} has no has_more flag");
        assert!(
            page.get("next_cursor").is_some(),
            "{path} has no next_cursor field"
        );
    }
}

#[tokio::test]
async fn moderation_center_notes_timeline_and_device_suspension_are_enforced() {
    std::env::set_var("COCO_ADMIN_KEY", "11".repeat(32));
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let owner = register(&app, ip, "pack2_center_owner_").await;
    let admin = unlock_admin(&app, &state, ip, &owner, "owner").await;
    let reporter = register(&app, connect_info(), "pack2_center_rep_").await;
    let target = register(&app, connect_info(), "pack2_center_target_").await;
    let report = seed_report(&app, &state, ip, &reporter, &target, "harassment").await;

    // The dashboard summary is computed by the database and must at minimum
    // reflect the work this test just created.
    let summary = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request("/admin/summary", ip, Some(&owner.token)),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    for key in [
        "open_reports",
        "in_progress_reports",
        "unresolved_feedback",
        "active_account_suspensions",
        "active_installation_suspensions",
        "reports_last_24h",
    ] {
        assert!(summary[key].is_number(), "summary is missing {key}");
    }
    assert!(
        summary["open_reports"].as_i64().unwrap() >= 1,
        "the report seeded by this test must be counted"
    );
    assert!(
        summary["storage"]["budget_bytes"].is_number(),
        "owners see storage figures"
    );

    // User Moderation Center: the primary fields are human-readable.
    let detail = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/users/{}", target.id),
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(detail["profile"]["username"], target.username);
    assert_eq!(detail["profile"]["display_name"], "Test User");
    assert_eq!(detail["moderation"]["status"], "active");
    assert_eq!(detail["moderation"]["reports_total"], 1);
    assert_eq!(detail["moderation"]["reports_open"], 1);
    assert_eq!(detail["moderation"]["notes"], 0);
    assert_eq!(detail["suspend_allowed"], true);
    let devices = detail["devices"].as_array().unwrap();
    assert_eq!(devices.len(), 1);
    assert_eq!(devices[0]["platform"], "android");
    assert_eq!(devices[0]["display_name"], "Test Device");
    assert_eq!(devices[0]["restriction_active"], false);
    assert!(devices[0]["last_active_at"].is_string());
    // An unknown account is not addressable.
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    "/admin/users/00000000-0000-0000-0000-000000000000",
                    ip,
                    Some(&owner.token)
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::NOT_FOUND
    );

    // A system notice is persisted, recorded in the account security journal,
    // audited, and queued for push delivery without exposing the message body in
    // the audit log.
    let notice = body_json(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/users/{}/notice", target.id),
                    ip,
                    &owner.token,
                    json!({
                        "title": "Security review",
                        "body": "Please review your active devices.",
                        "severity": "warning",
                        "reason": "Requested by the account owner"
                    }),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(notice["ok"], true);
    assert_eq!(notice["push_queued"], 0);
    assert!(notice["notice_id"].is_string());
    let notice_count: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM admin_user_notices WHERE user_id=$1 AND title='Security review'",
    )
    .bind(target.id)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(notice_count, 1);
    let security = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/users/{}/security-events", target.id),
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert!(security["items"]
        .as_array()
        .unwrap()
        .iter()
        .any(|event| event["event_type"] == "admin_notice"));

    // The diagnostic export is intentionally metadata-only and every export is
    // traceable through the admin audit log.
    let diagnostics = body_json(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/users/{}/diagnostics", target.id),
                    ip,
                    &owner.token,
                    json!({"reason": "Investigating a login report"}),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(diagnostics["schema"], "coco-admin-user-diagnostics-v1");
    assert_eq!(diagnostics["push"]["registered"], 0);
    assert_eq!(diagnostics["push"]["active"], 0);
    assert!(diagnostics["privacy"]
        .as_str()
        .unwrap()
        .contains("No message content"));
    assert!(diagnostics.get("messages").is_none());
    let export_audit: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM admin_audit WHERE target=$1 AND action='diagnostics:export'",
    )
    .bind(target.id)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(export_audit, 1);

    // Profile moderation clears only the selected public fields, requires a
    // reason, and records the exact fields that changed.
    sqlx::query(
        "UPDATE users SET display_name='Unsafe name',bio='Unsafe bio',avatar_key='avatars/moderation-test.png',avatar_url='legacy://avatar' WHERE id=$1",
    )
    .bind(target.id)
    .execute(&state.db)
    .await
    .unwrap();
    let moderated = body_json(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/users/{}/profile-moderation", target.id),
                    ip,
                    &owner.token,
                    json!({
                        "remove_avatar": true,
                        "clear_display_name": true,
                        "clear_bio": true,
                        "reason": "Public profile policy violation"
                    }),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(moderated["avatar_removed"], true);
    assert_eq!(moderated["display_name_cleared"], true);
    assert_eq!(moderated["bio_cleared"], true);
    let profile_after: (String, Option<String>, Option<String>) =
        sqlx::query_as("SELECT display_name,bio,avatar_key FROM users WHERE id=$1")
            .bind(target.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(profile_after.0, target.username);
    assert!(profile_after.1.is_none());
    assert!(profile_after.2.is_none());

    // Read-only mode keeps reads and login state working while the middleware
    // blocks ordinary writes. Removing the control restores writes immediately.
    let read_only_until = chrono::Utc::now() + chrono::Duration::hours(1);
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/users/{}/read-only", target.id),
                    ip,
                    &owner.token,
                    json!({
                        "enabled": true,
                        "until": read_only_until,
                        "reason": "Temporary review"
                    }),
                ),
                &admin,
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        app.clone()
            .oneshot(get_request("/users/me", ip, Some(&target.token)))
            .await
            .unwrap()
            .status(),
        StatusCode::OK,
        "read-only users must retain read access"
    );
    for (filter, expected) in [("restricted", 1), ("read_only", 1), ("active", 0)] {
        let listed = body_json(app.clone().oneshot(with_admin(get_request(
            &format!("/admin/users?query={}&status={filter}", target.username), ip, Some(&owner.token)), &admin)).await.unwrap()).await;
        assert_eq!(listed["items"].as_array().unwrap().len(), expected);
        if expected == 1 { assert_eq!(listed["items"][0]["status"], "read_only"); }
    }
    let blocked_write = app
        .clone()
        .oneshot(patch_json_request(
            "/users/me",
            ip,
            &target.token,
            json!({"bio": "must be rejected"}),
        ))
        .await
        .unwrap();
    assert_eq!(blocked_write.status(), StatusCode::FORBIDDEN);
    assert_eq!(
        body_json(blocked_write).await["error"]["code"],
        "account_read_only"
    );
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/users/{}/read-only", target.id),
                    ip,
                    &owner.token,
                    json!({
                        "enabled": false,
                        "until": null,
                        "reason": "Review completed"
                    }),
                ),
                &admin,
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        app.clone()
            .oneshot(patch_json_request(
                "/users/me",
                ip,
                &target.token,
                json!({"bio": "writes restored"}),
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );

    // Account suspension is server-authoritative and always audited.
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/users/{}/suspension", target.id),
                    ip,
                    &owner.token,
                    json!({"blocked": true, "reason": "harassment"})
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    let suspended = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/users/{}", target.id),
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(suspended["moderation"]["status"], "suspended");
    assert_eq!(suspended["moderation"]["reason"], "harassment");
    let after_suspend = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request("/admin/summary", ip, Some(&owner.token)),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert!(
        after_suspend["active_account_suspensions"]
            .as_i64()
            .unwrap()
            >= 1
    );

    // Administrators, including the acting one, stay protected.
    for victim in [owner.id, Uuid::nil()] {
        assert_eq!(
            app.clone()
                .oneshot(with_admin(
                    post_json_request(
                        &format!("/admin/users/{victim}/suspension"),
                        ip,
                        &owner.token,
                        json!({"blocked": true, "reason": "self"})
                    ),
                    &admin
                ))
                .await
                .unwrap()
                .status(),
            StatusCode::NOT_FOUND
        );
    }
    let other_admin = register(&app, connect_info(), "pack2_center_admin2_").await;
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'moderator')")
        .bind(other_admin.id)
        .execute(&state.db)
        .await
        .unwrap();
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/users/{}/suspension", other_admin.id),
                    ip,
                    &owner.token,
                    json!({"blocked": true, "reason": "peer"})
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::NOT_FOUND,
        "one administrator cannot restrict another"
    );
    // ...but another administrator is still readable.
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/users/{}", other_admin.id),
                    ip,
                    Some(&owner.token)
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );

    // Internal notes: validated, append-only and audited.
    for bad in [
        json!({"body": "   "}),
        json!({"body": "x".repeat(2001)}),
        json!({"body": ""}),
    ] {
        assert_eq!(
            app.clone()
                .oneshot(with_admin(
                    post_json_request(
                        &format!("/admin/users/{}/notes", target.id),
                        ip,
                        &owner.token,
                        bad
                    ),
                    &admin
                ))
                .await
                .unwrap()
                .status(),
            StatusCode::BAD_REQUEST
        );
    }
    let note = body_json(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/users/{}/notes", target.id),
                    ip,
                    &owner.token,
                    json!({"body": "  Worth watching; second report incoming.  "}),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert!(note["id"].is_string());
    let notes = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/users/{}/notes", target.id),
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(notes["items"].as_array().unwrap().len(), 1);
    assert_eq!(
        notes["items"][0]["body"],
        "Worth watching; second report incoming."
    );
    assert_eq!(notes["items"][0]["author_user_id"], owner.id.to_string());
    // A note can never be attached to an unknown account.
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    "/admin/users/00000000-0000-0000-0000-000000000000/notes",
                    ip,
                    &owner.token,
                    json!({"body": "x"})
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::NOT_FOUND
    );

    // The timeline is assembled from existing canonical records - no new event
    // table - and exposes stable semantic kinds shared by both clients.
    fn kinds(page: &Value) -> Vec<String> {
        page["items"]
            .as_array()
            .unwrap()
            .iter()
            .map(|item| item["kind"].as_str().unwrap().to_string())
            .collect()
    }
    let timeline = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/users/{}/timeline?limit=50", target.id),
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    let initial = kinds(&timeline);
    assert!(
        initial.contains(&"account_suspended".to_string()),
        "timeline: {initial:?}"
    );
    assert!(
        initial.contains(&"note_added".to_string()),
        "timeline: {initial:?}"
    );
    assert!(
        initial.contains(&"report_received".to_string()),
        "timeline: {initial:?}"
    );
    // Ordering is newest first.
    let stamps: Vec<&str> = timeline["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|item| item["occurred_at"].as_str().unwrap())
        .collect();
    let mut sorted = stamps.clone();
    sorted.sort_by(|a, b| b.cmp(a));
    assert_eq!(stamps, sorted, "timeline must be newest first");
    // A second read returns exactly the same page.
    let again = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/users/{}/timeline?limit=50", target.id),
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(timeline["items"], again["items"]);
    // Timeline pagination advances and stays bounded.
    let page1 = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/users/{}/timeline?limit=1", target.id),
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(page1["items"].as_array().unwrap().len(), 1);
    let cursor = page1["next_cursor"].as_str().unwrap().to_string();
    let page2 = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!(
                        "/admin/users/{}/timeline?limit=1&cursor={cursor}",
                        target.id
                    ),
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_ne!(
        page1["items"][0]["ref"], page2["items"][0]["ref"],
        "a cursor must advance, not repeat the first page"
    );

    // Reviewing the report becomes a semantic event, never a raw action string.
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/reports/{report}"),
                    ip,
                    &owner.token,
                    json!({"status": "in_progress", "note": "looking"})
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    let reviewed = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/users/{}/timeline?limit=50", target.id),
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    let kinds = kinds(&reviewed);
    assert!(
        kinds.contains(&"report_started".to_string()),
        "timeline: {kinds:?}"
    );
    assert!(
        !kinds.contains(&"other".to_string()),
        "every administrative action must map to a known semantic kind: {kinds:?}"
    );
    // Reports involving this account can be filtered directly.
    assert_eq!(
        body_json(
            app.clone()
                .oneshot(with_admin(
                    get_request(
                        &format!("/admin/reports?reported_user_id={}", target.id),
                        ip,
                        Some(&owner.token)
                    ),
                    &admin
                ))
                .await
                .unwrap()
        )
        .await["items"]
            .as_array()
            .unwrap()
            .len(),
        1
    );

    // Device suspension is narrower than account suspension and is enforced by
    // the same admission path every login already goes through.
    let installation: Uuid =
        sqlx::query_scalar("SELECT installation_id FROM devices WHERE user_id=$1")
            .bind(target.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/installations/{installation}/suspension"),
                    ip,
                    &owner.token,
                    json!({"blocked": true, "reason": "compromised device"})
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    let after_device = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/users/{}", target.id),
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    // The account-level state is untouched: only one install is restricted.
    assert_eq!(after_device["moderation"]["status"], "suspended");
    assert_eq!(after_device["devices"][0]["restriction_active"], true);
    assert_eq!(
        after_device["devices"][0]["restriction_reason"],
        "compromised device"
    );
    assert_eq!(
        after_device["devices"][0]["installation_id"],
        installation.to_string()
    );
    let after_device_summary = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request("/admin/summary", ip, Some(&owner.token)),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert!(
        after_device_summary["active_installation_suspensions"]
            .as_i64()
            .unwrap()
            >= 1
    );
    // The device event carries machine-readable context in the timeline.
    let device_timeline = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/users/{}/timeline?limit=50", target.id),
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    let device_event = device_timeline["items"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["kind"] == "device_suspended")
        .cloned()
        .expect("device suspension must appear in the timeline");
    assert_eq!(
        device_event["detail"]["installation_id"],
        installation.to_string()
    );

    // Lifting the ACCOUNT suspension leaves the DEVICE restriction in force: the
    // two levels are genuinely independent, and a restricted installation cannot
    // obtain a session on its own.
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/users/{}/suspension", target.id),
                    ip,
                    &owner.token,
                    json!({"blocked": false, "reason": "account cleared"})
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    let still_blocked = app
        .clone()
        .oneshot(login_request(ip, &target.username, installation))
        .await
        .unwrap();
    assert_eq!(
        still_blocked.status(),
        StatusCode::FORBIDDEN,
        "the installation restriction must survive an account-level restore"
    );
    assert_eq!(
        body_json(still_blocked).await["error"]["code"],
        "installation_restricted"
    );

    // Restoring the installation re-allows login.
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/installations/{installation}/suspension"),
                    ip,
                    &owner.token,
                    json!({"blocked": false, "reason": "restored after review"})
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        app.clone()
            .oneshot(login_request(ip, &target.username, installation))
            .await
            .unwrap()
            .status(),
        StatusCode::OK,
        "restoring an installation must re-allow login"
    );

    // Administrative sign-out targets exactly one server device, ends its
    // current sessions and leaves the installation eligible for a fresh login.
    let target_device: Uuid =
        sqlx::query_scalar("SELECT id FROM devices WHERE user_id=$1 AND installation_id=$2")
            .bind(target.id)
            .bind(installation)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/users/{}/avatar?size=640", target.id),
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::NOT_FOUND,
        "accounts without an avatar return a clear not-found result"
    );
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/devices/{target_device}/logout"),
                    ip,
                    &owner.token,
                    json!({"reason": "user requested session sign-out"})
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    let revoked_at: Option<chrono::DateTime<chrono::Utc>> =
        sqlx::query_scalar("SELECT revoked_at FROM devices WHERE id=$1")
            .bind(target_device)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert!(revoked_at.is_some(), "selected device must be revoked");
    let device_logout_timeline = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/users/{}/timeline?limit=50", target.id),
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert!(device_logout_timeline["items"]
        .as_array()
        .unwrap()
        .iter()
        .any(|item| item["kind"] == "device_session_ended"));
    assert_eq!(
        app.clone()
            .oneshot(login_request(ip, &target.username, installation))
            .await
            .unwrap()
            .status(),
        StatusCode::OK,
        "signing out a device must not create a persistent installation restriction"
    );
    let owner_device: Uuid = sqlx::query_scalar(
        "SELECT id FROM devices WHERE user_id=$1 AND installation_id=$2 LIMIT 1",
    )
    .bind(owner.id)
    .bind(owner.installation)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/devices/{owner_device}/logout"),
                    ip,
                    &owner.token,
                    json!({"reason": "must not terminate current admin device"})
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::CONFLICT,
        "the admin device used for the current admin session is protected"
    );

    // Unknown installations are not addressable; an administrator's own device is
    // protected from device-level restriction.
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    "/admin/installations/00000000-0000-0000-0000-000000000000/suspension",
                    ip,
                    &owner.token,
                    json!({"blocked": true, "reason": "x"})
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/installations/{}/suspension", owner.installation),
                    ip,
                    &owner.token,
                    json!({"blocked": true, "reason": "x"})
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::CONFLICT
    );
    // A reason and a future expiry are mandatory.
    for bad in [
        json!({"blocked": true, "reason": "   "}),
        json!({"blocked": true, "reason": "x", "until": "2000-01-01T00:00:00Z"}),
        json!({"blocked": true, "reason": "x".repeat(2001)}),
    ] {
        assert_eq!(
            app.clone()
                .oneshot(with_admin(
                    post_json_request(
                        &format!("/admin/installations/{installation}/suspension"),
                        ip,
                        &owner.token,
                        bad
                    ),
                    &admin
                ))
                .await
                .unwrap()
                .status(),
            StatusCode::BAD_REQUEST
        );
    }

    // Moderator limits: the moderation surface is available, owner surfaces are not.
    let moderator = register(&app, connect_info(), "pack2_center_mod_").await;
    let mod_token = unlock_admin(&app, &state, ip, &moderator, "moderator").await;
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/users/{}", target.id),
                    ip,
                    Some(&moderator.token)
                ),
                &mod_token
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    let mod_summary = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request("/admin/summary", ip, Some(&moderator.token)),
                &mod_token,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert!(mod_summary["open_reports"].is_number());
    assert!(
        mod_summary["storage"].is_null(),
        "moderators must not receive storage figures"
    );
    for path in [
        "/admin/audit",
        "/admin/audit?action=suspend",
        "/admin/storage",
        "/admin/server-policy",
    ] {
        assert_eq!(
            app.clone()
                .oneshot(with_admin(
                    get_request(path, ip, Some(&moderator.token)),
                    &mod_token
                ))
                .await
                .unwrap()
                .status(),
            StatusCode::NOT_FOUND,
            "moderator reached owner-only {path}"
        );
    }
    // Moderators still moderate: notes and restrictions remain available.
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/users/{}/notes", target.id),
                    ip,
                    &moderator.token,
                    json!({"body": "Moderator note"})
                ),
                &mod_token
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/users/{}/suspension", target.id),
                    ip,
                    &moderator.token,
                    json!({"blocked": false, "reason": "no action needed"})
                ),
                &mod_token
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );

    let delete_target = register(&app, connect_info(), "pack2_delete_user_").await;
    let avatar_key = format!("avatars/{}", Uuid::new_v4());
    sqlx::query("UPDATE users SET avatar_key=$2,avatar_url=$2 WHERE id=$1")
        .bind(delete_target.id)
        .bind(&avatar_key)
        .execute(&state.db)
        .await
        .unwrap();
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/users/{}/delete", delete_target.id),
                    ip,
                    &moderator.token,
                    json!({"confirm_username": delete_target.username, "reason": "policy violation"})
                ),
                &mod_token
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::NOT_FOUND,
        "moderators cannot delete accounts"
    );
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/users/{}/delete", owner.id),
                    ip,
                    &owner.token,
                    json!({"confirm_username": owner.username, "reason": "self"})
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::NOT_FOUND,
        "an owner cannot delete their own account through moderation"
    );
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/users/{}/delete", other_admin.id),
                    ip,
                    &owner.token,
                    json!({"confirm_username": other_admin.username, "reason": "peer"})
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::NOT_FOUND,
        "administrator accounts cannot be deleted"
    );
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/users/{}/delete", delete_target.id),
                    ip,
                    &owner.token,
                    json!({"confirm_username": "wrong-name", "reason": "policy violation"})
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::BAD_REQUEST,
        "deletion requires the exact username"
    );
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/users/{}/delete", delete_target.id),
                    ip,
                    &owner.token,
                    json!({"confirm_username": delete_target.username, "reason": "  policy violation  "})
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::NO_CONTENT
    );
    let still_exists: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM users WHERE id=$1)")
        .bind(delete_target.id)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert!(!still_exists);
    let audited: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM admin_audit WHERE actor=$1 AND target=$2 AND action='account_delete' AND reason='policy violation' AND detail->>'username'=$3)",
    )
    .bind(owner.id)
    .bind(delete_target.id)
    .bind(&delete_target.username)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert!(audited, "account deletion must remain in the audit log");
    let avatar_queued: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM account_object_deletions WHERE object_key=$1 AND delete_after>now())",
    )
    .bind(&avatar_key)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert!(
        avatar_queued,
        "the deleted account's avatar must be queued for cleanup"
    );

    // Role revocation ends administrative access on the very next request, and is
    // completely independent of anything a client may have installed.
    sqlx::query("DELETE FROM server_administrators WHERE user_id=$1")
        .bind(owner.id)
        .execute(&state.db)
        .await
        .unwrap();
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                get_request("/admin/summary", ip, Some(&owner.token)),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::NOT_FOUND,
        "a revoked administrator loses access immediately"
    );
    assert_eq!(
        app.clone()
            .oneshot(get_request("/users/me", ip, Some(&owner.token)))
            .await
            .unwrap()
            .status(),
        StatusCode::OK,
        "ordinary login survives administrative revocation"
    );
}

#[tokio::test]
async fn feedback_metadata_screenshots_and_review_are_separated_and_bounded() {
    std::env::set_var("COCO_ADMIN_KEY", "11".repeat(32));
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let owner = register(&app, ip, "pack2_fb_owner_").await;
    let admin = unlock_admin(&app, &state, ip, &owner, "owner").await;
    let submitter = register(&app, ip, "pack2_fb_user_").await;
    let feedback = seed_feedback(&state, submitter.id, "Camera is black").await;
    let other_feedback = seed_feedback(&state, submitter.id, "Second report").await;

    // Detail carries descriptors, never the encoded image.
    let response = app
        .clone()
        .oneshot(with_admin(
            get_request(
                &format!("/admin/feedback/{feedback}"),
                ip,
                Some(&owner.token),
            ),
            &admin,
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["cache-control"], "no-store");
    let raw = response.into_body().collect().await.unwrap().to_bytes();
    let text = String::from_utf8(raw.to_vec()).unwrap();
    assert!(
        !text.contains("data_base64"),
        "detail leaked the screenshot payload"
    );
    assert!(!text.contains(PNG_BASE64), "detail leaked the base64 image");
    let detail: Value = serde_json::from_str(&text).unwrap();
    assert_eq!(detail["description"], "Camera is black");
    assert_eq!(detail["platform"], "android");
    assert_eq!(detail["status"], "open");
    assert_eq!(detail["screenshots"].as_array().unwrap().len(), 1);
    assert_eq!(detail["screenshots"][0]["index"], 0);
    assert_eq!(detail["screenshots"][0]["mime_type"], "image/png");
    assert!(detail["screenshots"][0]["byte_size"].as_i64().unwrap() > 0);

    // The bytes are served separately, bounded and typed.
    let image = app
        .clone()
        .oneshot(with_admin(
            get_request(
                &format!("/admin/feedback/{feedback}/screenshots/0"),
                ip,
                Some(&owner.token),
            ),
            &admin,
        ))
        .await
        .unwrap();
    assert_eq!(image.status(), StatusCode::OK);
    assert_eq!(image.headers()["content-type"], "image/png");
    assert_eq!(image.headers()["cache-control"], "no-store");
    let bytes = image.into_body().collect().await.unwrap().to_bytes();
    assert!(bytes.starts_with(b"\x89PNG\r\n\x1a\n"));
    assert!(bytes.len() <= 1024 * 1024);
    // A neighbouring record is served under its own address, never through its
    // neighbour's: the index alone cannot cross a feedback boundary.
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/feedback/{other_feedback}/screenshots/0"),
                    ip,
                    Some(&owner.token)
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    // Out-of-range, negative, huge and unknown indices are all 404.
    for path in [
        format!("/admin/feedback/{feedback}/screenshots/1"),
        format!("/admin/feedback/{feedback}/screenshots/2"),
        format!("/admin/feedback/{feedback}/screenshots/3"),
        format!("/admin/feedback/{feedback}/screenshots/-1"),
        format!("/admin/feedback/{feedback}/screenshots/999999"),
        "/admin/feedback/00000000-0000-0000-0000-000000000000/screenshots/0".to_string(),
    ] {
        assert_eq!(
            app.clone()
                .oneshot(with_admin(
                    get_request(&path, ip, Some(&owner.token)),
                    &admin
                ))
                .await
                .unwrap()
                .status(),
            StatusCode::NOT_FOUND,
            "{path} must be 404"
        );
    }
    // A non-numeric index never reaches the handler as a number.
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/feedback/{feedback}/screenshots/abc"),
                    ip,
                    Some(&owner.token)
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::BAD_REQUEST
    );

    // Feedback has a lifecycle now, so the dashboard can report unresolved work.
    let before = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request("/admin/summary", ip, Some(&owner.token)),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert!(before["unresolved_feedback"].as_i64().unwrap() >= 2);

    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/feedback/{feedback}/review"),
                    ip,
                    &owner.token,
                    json!({"status": "resolved", "note": "fixed in 0.2.1"})
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::BAD_REQUEST,
        "closing feedback without a reason is refused"
    );
    for bad in [
        json!({"status": "done", "decision_reason": "x"}),
        json!({"status": "resolved", "decision_reason": "x".repeat(2001)}),
        json!({"status": "resolved", "note": "x".repeat(2001), "decision_reason": "x"}),
    ] {
        assert_eq!(
            app.clone()
                .oneshot(with_admin(
                    post_json_request(
                        &format!("/admin/feedback/{feedback}/review"),
                        ip,
                        &owner.token,
                        bad
                    ),
                    &admin
                ))
                .await
                .unwrap()
                .status(),
            StatusCode::BAD_REQUEST
        );
    }
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/feedback/{feedback}/review"),
                    ip,
                    &owner.token,
                    json!({"status": "resolved", "note": "fixed in 0.2.1", "decision_reason": "released"})
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    // The reviewed item appears under its new status and keeps its reason.
    let resolved = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    "/admin/feedback?status=resolved&platform=android",
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    let mine = resolved["items"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["id"] == feedback.to_string())
        .cloned()
        .expect("the reviewed feedback must be listed as resolved");
    assert_eq!(mine["decision_reason"], "released");
    assert_eq!(mine["reviewed_by"], owner.id.to_string());
    assert!(mine["reviewed_at"].is_string());
    // The other record is still open, so status filtering is real.
    let open = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    "/admin/feedback?status=open&platform=android",
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert!(open["items"]
        .as_array()
        .unwrap()
        .iter()
        .any(|item| item["id"] == other_feedback.to_string()));

    // Re-opening restores the unresolved state.
    assert_eq!(
        app.clone()
            .oneshot(with_admin(
                post_json_request(
                    &format!("/admin/feedback/{feedback}/review"),
                    ip,
                    &owner.token,
                    json!({"status": "open"})
                ),
                &admin
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    let reopened = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/feedback/{feedback}"),
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(reopened["status"], "open");

    // Feedback reviews are audited with machine-readable, non-secret detail.
    // Scoped to feedback so the enrolment row from the unlock is irrelevant.
    let recorded: Vec<String> = sqlx::query_scalar(
        "SELECT action FROM admin_audit WHERE actor=$1 AND action LIKE 'feedback:%' ORDER BY id",
    )
    .bind(owner.id)
    .fetch_all(&state.db)
    .await
    .unwrap();
    assert_eq!(
        recorded,
        vec!["feedback:resolved".to_string(), "feedback:open".to_string()],
        "exactly one audit row per review"
    );
    let audit = body_json(
        app.clone()
            .oneshot(with_admin(
                get_request(
                    &format!("/admin/audit?action=feedback:open&actor={}", owner.id),
                    ip,
                    Some(&owner.token),
                ),
                &admin,
            ))
            .await
            .unwrap(),
    )
    .await;
    let actions: Vec<String> = audit["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|item| item["action"].as_str().unwrap().to_string())
        .collect();
    assert_eq!(
        actions,
        vec!["feedback:open".to_string()],
        "the action filter must select only the requested action"
    );
    assert_eq!(
        audit["items"][0]["detail"]["feedback_id"],
        feedback.to_string()
    );
    assert_eq!(audit["items"][0]["target"], feedback.to_string());
    assert_eq!(audit["items"][0]["actor"], owner.id.to_string());
}


#[tokio::test]
async fn audit_fix_reciprocal_owner_changes_recheck_authority() {
    use axum::extract::{Path, State};
    use axum::Json;
    use messenger_server::admin::{Admin, AdminChannel};
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let a = register(&app, ip, "race_owner_a_").await;
    let b = register(&app, ip, "race_owner_b_").await;
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'owner'),($2,'owner')")
        .bind(a.id).bind(b.id).execute(&state.db).await.unwrap();
    let admitted = |user| Admin { user, role: "owner".into(), token_hash: String::new(),
        auth_session: Uuid::nil(), channel: AdminChannel::Native };
    let body = || serde_json::from_value(json!({"role":"moderator","reason":"reciprocal race regression"})).unwrap();
    let (first, second) = tokio::join!(
        messenger_server::admin_operations::change_operator(State(state.clone()), admitted(a.id), Path(b.id), Json(body())),
        messenger_server::admin_operations::change_operator(State(state.clone()), admitted(b.id), Path(a.id), Json(body()))
    );
    assert_eq!(usize::from(first.is_ok()) + usize::from(second.is_ok()), 1,
        "the actor demoted while waiting must lose authority, even if other owners exist");
    let owners: i64 = sqlx::query_scalar("SELECT count(*) FROM server_administrators WHERE user_id=ANY($1) AND role='owner'")
        .bind(vec![a.id,b.id]).fetch_one(&state.db).await.unwrap();
    assert_eq!(owners, 1);
}

#[tokio::test]
async fn audit_fix_read_only_blocks_admitted_send_but_allows_defensive_revocation() {
    use axum::extract::{Path, State};
    use axum::Json;
    use messenger_server::auth::middleware::AuthUser;
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let account = register(&app, ip, "admitted_send_").await;
    let (session_id,device_id): (Uuid,Uuid) = sqlx::query_as("SELECT id,device_id FROM sessions WHERE user_id=$1 AND revoked_at IS NULL")
        .bind(account.id).fetch_one(&state.db).await.unwrap();
    let auth = AuthUser {user_id:account.id,device_id,session_id};
    let saved = body_json(app.clone().oneshot(get_request("/chats/saved",ip,Some(&account.token))).await.unwrap()).await;
    let chat_id = Uuid::parse_str(saved["chat_id"].as_str().unwrap()).unwrap();
    let client_message_id = Uuid::new_v4();
    let request = serde_json::from_value(json!({"client_message_id":client_message_id,"envelopes":[]})).unwrap();
    let mut activation = state.db.begin().await.unwrap();
    sqlx::query("SELECT id FROM users WHERE id=$1 FOR UPDATE").bind(account.id).execute(&mut *activation).await.unwrap();
    let send_state=state.clone();
    let send_auth=auth.clone();
    let send=tokio::spawn(async move {
        messenger_server::messages::handlers::send_message(State(send_state),
            messenger_server::net::ClientIp(ip.0.ip()),send_auth,Path(chat_id),Json(request)).await
    });
    // The already admitted handler must recheck at the transaction boundary.
    sqlx::query("INSERT INTO admin_user_controls(user_id,read_only_reason,updated_by) VALUES($1,'test',$1)")
        .bind(account.id).execute(&mut *activation).await.unwrap();
    activation.commit().await.unwrap();
    assert!(matches!(send.await.unwrap(), Err(messenger_server::error::AppError::AccountReadOnly)));
    let count:i64=sqlx::query_scalar("SELECT count(*) FROM messages WHERE client_message_id=$1")
        .bind(client_message_id).fetch_one(&state.db).await.unwrap();
    assert_eq!(count,0);
    let history_status=app.clone().oneshot(get_request("/history-backup",ip,Some(&account.token))).await.unwrap();
    assert_eq!(history_status.status(),StatusCode::OK);
    for uri in [format!("/history-backup/objects/{}",Uuid::new_v4()),
                format!("/history-backup/media/{}",Uuid::new_v4()),
                format!("/history-transfer/{}",Uuid::new_v4())] {
        let response=app.clone().oneshot(get_request(&uri,ip,Some(&account.token))).await.unwrap();
        assert_eq!(response.status(),StatusCode::NOT_FOUND,"read-only must reach ownership lookup, not reject reading: {uri}");
    }
    // Security-only account lock permits revocation, but not content mutation.
    let revoke=app.clone().oneshot(post_json_request("/devices/revoke-others",ip,&account.token,json!({}))).await.unwrap();
    assert_eq!(revoke.status(),StatusCode::OK);
    let mut tx=state.db.begin().await.unwrap();
    messenger_server::auth::middleware::lock_active_session(&mut tx,&auth).await.unwrap();
    assert!(matches!(messenger_server::auth::middleware::lock_active_user(&mut tx,&auth).await,
        Err(messenger_server::error::AppError::AccountReadOnly)));
    tx.rollback().await.unwrap();
    // The ordinary successful send remains valid after restriction removal.
    sqlx::query("DELETE FROM admin_user_controls WHERE user_id=$1").bind(account.id).execute(&state.db).await.unwrap();
    let result=app.oneshot(post_json_request(&format!("/chats/{chat_id}/messages"),ip,&account.token,
        json!({"client_message_id":client_message_id,"envelopes":[]}))).await.unwrap();
    assert!(result.status().is_success());
}


struct AuditPushProvider;
#[async_trait::async_trait]
impl messenger_server::push::models::PushProvider for AuditPushProvider {
    async fn send(&self, _: &str, _: &messenger_server::push::models::PushEvent)
        -> Result<messenger_server::push::models::PushSendOutcome, messenger_server::push::PushError> {
        panic!("This test must not send a real push");
    }
}

#[tokio::test]
async fn audit_fix_notice_queue_counts_targets_and_rolls_back_atomically() {
    let state=spawn_state().await;
    let app=messenger_server::app(state.clone());
    let account=register(&app,connect_info(),"notice_queue_").await;
    let push=messenger_server::push::PushService::new(std::sync::Arc::new(AuditPushProvider));
    let event=messenger_server::push::models::PushEvent::admin_notice(Uuid::new_v4(),
        "Notice".into(),"Test".into(),"info".into());
    let mut tx=state.db.begin().await.unwrap();
    assert_eq!(push.enqueue_transactional(&mut tx,account.id,&event).await.unwrap(),0);
    tx.commit().await.unwrap();
    sqlx::query("INSERT INTO push_tokens(device_id,token) SELECT id,'audit-fake-token' FROM devices WHERE user_id=$1")
        .bind(account.id).execute(&state.db).await.unwrap();
    let mut tx=state.db.begin().await.unwrap();
    assert_eq!(push.enqueue_transactional(&mut tx,account.id,&event).await.unwrap(),1);
    tx.rollback().await.unwrap();
    let count:i64=sqlx::query_scalar("SELECT count(*) FROM push_outbox WHERE user_id=$1")
        .bind(account.id).fetch_one(&state.db).await.unwrap();
    assert_eq!(count,0,"a rolled-back notice must never leave an independently committed push");
    let mut tx=state.db.begin().await.unwrap();
    assert_eq!(push.enqueue_transactional(&mut tx,account.id,&event).await.unwrap(),1);
    tx.commit().await.unwrap();
    let count:i64=sqlx::query_scalar("SELECT count(*) FROM push_outbox WHERE user_id=$1")
        .bind(account.id).fetch_one(&state.db).await.unwrap();
    assert_eq!(count,1);
}


#[tokio::test]
async fn audit_fix_activation_waits_for_inflight_content_transaction() {
    use axum::extract::{Path, State};
    use axum::Json;
    use messenger_server::admin::{Admin, AdminChannel};
    let state=spawn_state().await;
    let app=messenger_server::app(state.clone());
    let ip=connect_info();
    let owner=register(&app,ip,"activation_owner_").await;
    let target=register(&app,ip,"activation_target_").await;
    let mut sending=state.db.begin().await.unwrap();
    sqlx::query("SELECT id FROM users WHERE id=$1 FOR UPDATE").bind(target.id)
        .execute(&mut *sending).await.unwrap();
    let admin=Admin {user:owner.id,role:"owner".into(),token_hash:String::new(),
        auth_session:Uuid::nil(),channel:AdminChannel::Native};
    let body=serde_json::from_value(json!({"enabled":true,"reason":"wait for admitted send"})).unwrap();
    let task_state=state.clone();
    let mut activation=tokio::spawn(async move {
        messenger_server::admin::set_read_only(State(task_state),admin,Path(target.id),Json(body)).await
    });
    assert!(tokio::time::timeout(std::time::Duration::from_millis(200),&mut activation).await.is_err(),
        "Activation cannot acknowledge success while an admitted write owns the account lock");
    sending.commit().await.unwrap();
    let result=tokio::time::timeout(std::time::Duration::from_secs(5),activation).await.unwrap().unwrap();
    assert!(result.is_ok());
}

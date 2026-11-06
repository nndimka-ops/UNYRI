//! ADMIN CONSOLE PACK 3 - server-side coverage for the server-hosted console.
//!
//! The console is a browser client, so the interesting properties are all about
//! authority: where it comes from, how long it lasts, what ends it, and what a
//! normal account can never do. Every test asserts the *server* contract.
//!
//! The suite shares one PostgreSQL test database with every other test binary
//! (they run as parallel processes), so assertions are scoped to rows a test
//! created itself; global counters are never asserted.
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
    ConnectInfo(SocketAddr::from(([10, octets[0], octets[1], octets[2]], 12345)))
}

fn register_request(ip: ConnectInfo<SocketAddr>, username: &str, installation: Uuid) -> Request<Body> {
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

async fn body_json(response: axum::response::Response) -> Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap()
}

async fn body_text(response: axum::response::Response) -> String {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    String::from_utf8_lossy(&bytes).to_string()
}

struct Account {
    token: String,
    id: Uuid,
    #[allow(dead_code)]
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
    assert_eq!(response.status(), StatusCode::OK, "registration must succeed");
    let body = body_json(response).await;
    Account {
        token: body["access_token"].as_str().unwrap().to_string(),
        id: Uuid::parse_str(body["user"]["id"].as_str().unwrap()).unwrap(),
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

/// Grants `role` locally and completes the full three-factor unlock.
///
/// The console adds no way to obtain a role; it exists only to carry one, which
/// is why every console test starts from exactly this native unlock.
async fn unlock_admin(
    app: &axum::Router,
    state: &AppState,
    ip: ConnectInfo<SocketAddr>,
    account: &Account,
    role: &str,
) -> (String, ring::signature::Ed25519KeyPair) {
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,$2)")
        .bind(account.id)
        .bind(role)
        .execute(&state.db)
        .await
        .unwrap();
    let credentials = json!({"password": "correct-horse-battery", "code": "000000"});
    let setup = app
        .clone()
        .oneshot(post_json_request("/admin/setup", ip, &account.token, credentials))
        .await
        .unwrap();
    assert_eq!(setup.status(), StatusCode::OK, "administrative setup must succeed");
    let secret = totp_rs::Secret::try_from_base32(body_json(setup).await["secret"].as_str().unwrap())
        .unwrap();
    let otp = totp_rs::Builder::new().with_secret(secret).build().unwrap();
    let seed = account.id.as_bytes()[0].max(1);
    let key = ring::signature::Ed25519KeyPair::from_seed_unchecked(&[seed; 32]).unwrap();
    let mut login = json!({
        "password": "correct-horse-battery",
        "code": otp.generate_current().to_string()
    });
    login["key_proof"] = admin_key_proof(app, ip, &account.token, "unlock", &key).await;
    let unlocked = app
        .clone()
        .oneshot(post_json_request("/admin/unlock", ip, &account.token, login))
        .await
        .unwrap();
    assert_eq!(unlocked.status(), StatusCode::OK, "unlock must succeed");
    let token = body_json(unlocked).await["token"].as_str().unwrap().to_string();
    (token, key)
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

fn with_admin(mut request: Request<Body>, token: &str) -> Request<Body> {
    request
        .headers_mut()
        .insert("x-coco-admin", token.parse().unwrap());
    request
}

// ----- console client helpers ---------------------------------------------

/// A browser request: cookie, `Host`, `Origin`, and the CSRF header.
///
/// The headers mirror what a real browser sends, including `Host`, because the
/// server checks that state-changing cookie requests are same-origin.
struct Browser {
    cookie: String,
    csrf: String,
}

fn gateway(uri: &str, ip: ConnectInfo<SocketAddr>) -> Request<Body> {
    Request::builder()
        .method("GET")
        .uri(uri)
        .extension(ip)
        .body(Body::empty())
        .unwrap()
}

fn browser_get(uri: &str, ip: ConnectInfo<SocketAddr>, cookie: &str) -> Request<Body> {
    Request::builder()
        .method("GET")
        .uri(uri)
        .header("host", "localhost")
        .header("cookie", cookie)
        .extension(ip)
        .body(Body::empty())
        .unwrap()
}

fn browser_post(
    uri: &str,
    ip: ConnectInfo<SocketAddr>,
    cookie: Option<&str>,
    csrf: Option<&str>,
    origin: Option<&str>,
    body: Value,
) -> Request<Body> {
    let mut builder = Request::builder()
        .method("POST")
        .uri(uri)
        .header("content-type", "application/json")
        .header("host", "localhost");
    if let Some(cookie) = cookie {
        builder = builder.header("cookie", cookie);
    }
    if let Some(csrf) = csrf {
        builder = builder.header("x-coco-csrf", csrf);
    }
    if let Some(origin) = origin {
        builder = builder.header("origin", origin);
    }
    builder
        .extension(ip)
        .body(Body::from(body.to_string()))
        .unwrap()
}

fn browser_delete(
    uri: &str,
    ip: ConnectInfo<SocketAddr>,
    cookie: &str,
    csrf: &str,
    origin: &str,
) -> Request<Body> {
    Request::builder()
        .method("DELETE")
        .uri(uri)
        .header("host", "localhost")
        .header("cookie", cookie)
        .header("x-coco-csrf", csrf)
        .header("origin", origin)
        .extension(ip)
        .body(Body::empty())
        .unwrap()
}

/// Extracts one cookie value from a `set-cookie` header value.
fn cookie_value(set_cookie: &str) -> String {
    set_cookie.split(';').next().unwrap().to_string()
}

fn set_cookie_header(response: &axum::response::Response, name: &str) -> String {
    for value in response.headers().get_all("set-cookie") {
        let text = value.to_str().unwrap();
        if text.starts_with(&format!("{name}=")) {
            return cookie_value(text);
        }
    }
    panic!("response did not set {name}");
}

/// Issues a handoff from an unlocked native client and redeems it in a browser,
/// returning the browser's console cookie and CSRF token.
async fn open_console(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    admin_token: &str,
    account_token: &str,
) -> Browser {
    let handoff = issue_handoff(app, ip, admin_token, account_token).await;
    let response = app
        .clone()
        .oneshot(browser_post(
            "/administration/handoff",
            ip,
            None,
            None,
            None,
            json!({ "handoff": handoff }),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK, "redemption must succeed");
    let cookie = set_cookie_header(&response, "coco_admin_console");
    let csrf_cookie = set_cookie_header(&response, "coco_console_csrf");
    let body = body_json(response).await;
    assert_eq!(csrf_cookie, format!("coco_console_csrf={}", body["csrf"].as_str().unwrap()));
    Browser {
        cookie,
        csrf: body["csrf"].as_str().unwrap().to_string(),
    }
}

async fn issue_handoff(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    admin_token: &str,
    account_token: &str,
) -> String {
    let request = with_admin(post_json_request("/admin/console-handoff", ip, account_token, json!({})), admin_token);
    let response = app.clone().oneshot(request).await.unwrap();
    let status = response.status();
    let body = body_json(response).await;
    assert_eq!(status, StatusCode::OK, "handoff issuance must succeed, got {status}: {body}");
    assert_eq!(body["expires_in"], json!(60));
    assert_eq!(body["console_path"], json!("/administration"));
    body["handoff"].as_str().unwrap().to_string()
}

// =========================================================================
// tests
// =========================================================================

/// The console documents are static, hardened, and hold no authority. A
/// browser that merely knows the URL learns nothing.
#[tokio::test]
async fn console_documents_are_static_hardened_and_carry_no_authority() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();

    let response = app.clone().oneshot(gateway("/administration", ip)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let headers = response.headers().clone();
    let csp = headers["content-security-policy"].to_str().unwrap();
    assert!(csp.contains("default-src 'none'"), "CSP must deny by default");
    assert!(csp.contains("frame-ancestors 'none'"));
    assert!(!csp.contains("unsafe-eval"), "no eval");
    assert!(!csp.contains("unsafe-inline"), "no inline script or style");
    for (name, expected) in [
        ("x-frame-options", "DENY"),
        ("referrer-policy", "no-referrer"),
        ("x-content-type-options", "nosniff"),
        ("cache-control", "no-store"),
    ] {
        assert_eq!(headers[name], expected, "header {name}");
    }
    assert!(headers["permissions-policy"].to_str().unwrap().contains("camera=()"));
    let html = body_text(response).await;
    // The shell contains no data: it only wires up the scripts.
    assert!(html.contains("/administration/assets/app.js"));
    assert!(!html.contains("token"), "the shell must not mention a token");

    for (path, content_type) in [
        ("/administration/assets/app.css", "text/css"),
        ("/administration/assets/app.js", "text/javascript"),
    ] {
        let response = app.clone().oneshot(gateway(path, ip)).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert!(response.headers()["content-type"].to_str().unwrap().starts_with(content_type));
        assert_eq!(response.headers()["cache-control"], "no-store");
    }

    // The console only ever calls moderation and server-management endpoints.
    // It has no code path that could read conversations, keys or attachments.
    let response = app.clone().oneshot(gateway("/administration/assets/app.js", ip)).await.unwrap();
    let script = body_text(response).await;
    for forbidden in ["/chats", "/messages", "/keys", "/attachments", "/sync", "/calls"] {
        assert!(!script.contains(forbidden), "console must not reference {forbidden}");
    }
    assert!(script.contains("/admin/summary"), "the console must use the moderation API");
}

/// Only an unlocked native administrator can mint a handoff. A normal account
/// cannot, and an unauthenticated caller cannot even learn the route exists.
#[tokio::test]
async fn handoff_requires_a_native_administrator() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let plain = register(&app, ip, "console_plain_").await;
    let admin = register(&app, ip, "console_admin_").await;
    // Owner, because this test also reads the action journal, which is
    // owner-only. Handoff issuance itself is not role-dependent.
    let (admin_token, _) = unlock_admin(&app, &state, ip, &admin, "owner").await;

    // No credentials at all: 404, exactly like every other administration route.
    let response = app
        .clone()
        .oneshot(post_json_request("/admin/console-handoff", ip, &plain.token, json!({})))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);

    // A normal account with a valid bearer session is still nobody.
    let request = with_admin(
        post_json_request("/admin/console-handoff", ip, &plain.token, json!({})),
        &admin_token,
    );
    let response = app.clone().oneshot(request).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    let rows: i64 = sqlx::query_scalar("SELECT count(*) FROM admin_console_handoffs WHERE user_id=$1")
        .bind(plain.id)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(rows, 0, "a rejected handoff must leave no row behind");

    // The unlocked administrator succeeds.
    let handoff = issue_handoff(&app, ip, &admin_token, &admin.token).await;
    assert_eq!(handoff.len(), 43);
    let audit: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM admin_audit WHERE actor=$1 AND action='console:handoff-issued'",
    )
    .bind(admin.id)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(audit, 1, "issuing a handoff is audited");

    // And the audit journal shows it as a readable event, not a raw token.
    let response = app
        .clone()
        .oneshot(with_admin(
            Request::builder()
                .method("GET")
                .uri(format!(
                    "/admin/audit?action=console:handoff-issued&actor={}",
                    admin.id
                ))
                .header("authorization", format!("Bearer {}", admin.token))
                .extension(ip)
                .body(Body::empty())
                .unwrap(),
            &admin_token,
        ))
        .await
        .unwrap();
    let page = body_json(response).await;
    let items = page["items"].as_array().unwrap();
    assert_eq!(items.len(), 1);
    assert_eq!(items[0]["action"], json!("console:handoff-issued"));
    assert_eq!(items[0]["reason"], json!("Administration console handoff issued"));
    assert!(
        !serde_json::to_string(&items[0]).unwrap().contains(&handoff),
        "the audit journal must never contain the handoff secret"
    );
}

/// A handoff is a credential for exactly one exchange.
#[tokio::test]
async fn handoff_is_single_use_and_expires() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let admin = register(&app, ip, "console_one_").await;
    let (admin_token, _) = unlock_admin(&app, &state, ip, &admin, "moderator").await;

    let handoff = issue_handoff(&app, ip, &admin_token, &admin.token).await;
    let first = app
        .clone()
        .oneshot(browser_post(
            "/administration/handoff",
            ip,
            None,
            None,
            None,
            json!({ "handoff": handoff }),
        ))
        .await
        .unwrap();
    assert_eq!(first.status(), StatusCode::OK);

    // Replay: the second redemption finds nothing, because the first `UPDATE`
    // consumed the row.
    let replay = app
        .clone()
        .oneshot(browser_post(
            "/administration/handoff",
            ip,
            None,
            None,
            None,
            json!({ "handoff": handoff }),
        ))
        .await
        .unwrap();
    assert_eq!(replay.status(), StatusCode::NOT_FOUND);

    // Expiry: an unused handoff that has aged out is refused.
    let stale = issue_handoff(&app, ip, &admin_token, &admin.token).await;
    sqlx::query("UPDATE admin_console_handoffs SET expires_at=now()-interval '1 second' WHERE token_hash=$1")
        .bind(messenger_server::admin::hash(&stale))
        .execute(&state.db)
        .await
        .unwrap();
    let expired = app
        .clone()
        .oneshot(browser_post(
            "/administration/handoff",
            ip,
            None,
            None,
            None,
            json!({ "handoff": stale }),
        ))
        .await
        .unwrap();
    assert_eq!(expired.status(), StatusCode::NOT_FOUND);

    // A handoff belonging to another administrator cannot be used to open a
    // console for anyone but its own owner.
    let other = register(&app, ip, "console_other_").await;
    let (other_token, _) = unlock_admin(&app, &state, ip, &other, "moderator").await;
    let foreign = issue_handoff(&app, ip, &other_token, &other.token).await;
    let opened = app
        .clone()
        .oneshot(browser_post(
            "/administration/handoff",
            ip,
            None,
            None,
            None,
            json!({ "handoff": foreign }),
        ))
        .await
        .unwrap();
    assert_eq!(opened.status(), StatusCode::OK);
    let cookie = set_cookie_header(&opened, "coco_admin_console");
    // The session works, and it is the *other* administrator's session.
    let response = app.clone().oneshot(browser_get("/admin/summary", ip, &cookie)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let sessions: Uuid = sqlx::query_scalar(
        "SELECT user_id FROM admin_console_sessions WHERE token_hash=$1",
    )
    .bind(messenger_server::admin::hash(cookie.split_once('=').unwrap().1))
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(sessions, other.id);
}

/// The console cookie is the only browser credential, and a state change needs
/// three independent proofs: the cookie, a same-origin request, and the CSRF
/// header.
#[tokio::test]
async fn console_session_requires_cookie_origin_and_csrf() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let admin = register(&app, ip, "console_csrf_").await;
    let (admin_token, _) = unlock_admin(&app, &state, ip, &admin, "owner").await;
    let browser = open_console(&app, ip, &admin_token, &admin.token).await;

    // Reads work with the cookie alone.
    let response = app.clone().oneshot(browser_get("/admin/summary", ip, &browser.cookie)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["cache-control"], "no-store");
    let summary = body_json(response).await;
    assert!(summary["storage"].is_object(), "an owner sees storage figures");

    // A wrong cookie, or a cookie-shaped value that was never issued, is nobody.
    let stranger = "coco_admin_console=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    let response = app.clone().oneshot(browser_get("/admin/summary", ip, stranger)).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);

    // A real target, so a rejected state change has something it could have
    // changed: every negative case below must leave it untouched.
    let target = register(&app, ip, "console_target_").await;
    let suspend = format!("/admin/users/{}/suspension", target.id);
    let restore = json!({"blocked": false, "reason": "test"});

    // A state change without the CSRF header is refused.
    let response = app
        .clone()
        .oneshot(browser_post(
            &suspend,
            ip,
            Some(&browser.cookie),
            None,
            Some("https://localhost"),
            restore.clone(),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);

    // A wrong CSRF value is refused. The value is the right length, so this is
    // a real comparison and not a shape check.
    let response = app
        .clone()
        .oneshot(browser_post(
            &suspend,
            ip,
            Some(&browser.cookie),
            Some("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"),
            Some("https://localhost"),
            restore.clone(),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);

    // A cross-site origin is refused even with a valid cookie and CSRF value.
    let response = app
        .clone()
        .oneshot(browser_post(
            &suspend,
            ip,
            Some(&browser.cookie),
            Some(&browser.csrf),
            Some("https://attacker.example"),
            restore.clone(),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);

    // A missing Origin/Referer is refused too: this is a fail-closed rule, not
    // an allow-list of bad values.
    let response = app
        .clone()
        .oneshot(browser_post(
            &suspend,
            ip,
            Some(&browser.cookie),
            Some(&browser.csrf),
            None,
            restore.clone(),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);

    let attempted: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM admin_audit WHERE actor=$1 AND target=$2 AND action LIKE 'suspend%'",
    )
    .bind(admin.id)
    .bind(target.id)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(attempted, 0, "a rejected request must not reach the handler");

    // The real thing: same origin, cookie and CSRF value all agree.
    let response = app
        .clone()
        .oneshot(browser_post(
            &suspend,
            ip,
            Some(&browser.cookie),
            Some(&browser.csrf),
            Some("https://localhost"),
            json!({"blocked": true, "reason": "console test"}),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let blocked: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM server_suspensions WHERE user_id=$1 AND (until_date IS NULL OR until_date>now()))",
    )
    .bind(target.id)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert!(blocked, "the console action must take effect");

    // The owner-only surfaces the console exposes are reachable from the
    // browser session, which is what makes the console a real client rather
    // than a viewer.
    for path in ["/admin/storage", "/admin/server-policy"] {
        let response = app.clone().oneshot(browser_get(path, ip, &browser.cookie)).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK, "{path}");
    }
}

/// Every existing revocation rule reaches the console, because the browser
/// session is foreign-keyed to the native administration session.
#[tokio::test]
async fn revocation_rules_close_the_console() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();

    // 1. An explicit native lock destroys the browser session.
    let locked = register(&app, ip, "console_lock_").await;
    let (locked_token, _) = unlock_admin(&app, &state, ip, &locked, "moderator").await;
    let browser = open_console(&app, ip, &locked_token, &locked.token).await;
    let response = app.clone().oneshot(browser_get("/admin/summary", ip, &browser.cookie)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let lock = app
        .clone()
        .oneshot(with_admin(
            post_json_request("/admin/lock", ip, &locked.token, json!({})),
            &locked_token,
        ))
        .await
        .unwrap();
    assert_eq!(lock.status(), StatusCode::OK);
    let response = app.clone().oneshot(browser_get("/admin/summary", ip, &browser.cookie)).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND, "lock must close the console");
    let left: i64 = sqlx::query_scalar("SELECT count(*) FROM admin_console_sessions WHERE user_id=$1")
        .bind(locked.id)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(left, 0, "the console session row must be gone, not merely ignored");

    // 2. Revoking the role ends the console immediately.
    let revoked = register(&app, ip, "console_revoke_").await;
    let (revoked_token, _) = unlock_admin(&app, &state, ip, &revoked, "moderator").await;
    let browser = open_console(&app, ip, &revoked_token, &revoked.token).await;
    sqlx::query("DELETE FROM server_administrators WHERE user_id=$1")
        .bind(revoked.id)
        .execute(&state.db)
        .await
        .unwrap();
    let response = app.clone().oneshot(browser_get("/admin/summary", ip, &browser.cookie)).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND, "revoking the role must close the console");

    // 3. Replacing the signing key ends every session, console included.
    let rotated = register(&app, ip, "console_rotate_").await;
    let (rotated_token, old_key) = unlock_admin(&app, &state, ip, &rotated, "moderator").await;
    let browser = open_console(&app, ip, &rotated_token, &rotated.token).await;
    let new_key = ring::signature::Ed25519KeyPair::from_seed_unchecked(&[7u8; 32]).unwrap();
    let proof = admin_rotate_proof(&app, ip, &rotated.token, &old_key, &new_key).await;
    let rotate = app
        .clone()
        .oneshot(with_admin(
            post_json_request("/admin/key-rotate", ip, &rotated.token, proof),
            &rotated_token,
        ))
        .await
        .unwrap();
    assert_eq!(rotate.status(), StatusCode::OK, "rotation must succeed");
    let response = app.clone().oneshot(browser_get("/admin/summary", ip, &browser.cookie)).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND, "rotation must close the console");

    // 4. Signing the native session out of COCO ends the console too: the
    //    browser session is bound to a live authentication session, not to a
    //    rolled refresh token.
    let logged_out = register(&app, ip, "console_logout_").await;
    let (logged_token, _) = unlock_admin(&app, &state, ip, &logged_out, "moderator").await;
    let browser = open_console(&app, ip, &logged_token, &logged_out.token).await;
    let logout = app
        .clone()
        .oneshot(post_json_request("/auth/logout", ip, &logged_out.token, json!({})))
        .await
        .unwrap();
    assert!(logout.status().is_success(), "logout must succeed");
    let response = app.clone().oneshot(browser_get("/admin/summary", ip, &browser.cookie)).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND, "signing out must close the console");
}

/// A rotation proof: the challenge is opened with the *new* public key, while the
/// existing key must sign the payload and the new key must countersign it.
async fn admin_rotate_proof(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    token: &str,
    old_key: &ring::signature::Ed25519KeyPair,
    new_key: &ring::signature::Ed25519KeyPair,
) -> Value {
    use base64::Engine;
    use ring::signature::KeyPair;
    let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD;
    let public = b64.encode(new_key.public_key().as_ref());
    let response = app
        .clone()
        .oneshot(post_json_request(
            "/admin/key-challenge",
            ip,
            token,
            json!({"purpose": "rotate", "public_key": public}),
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
        "rotate",
        public,
        data["nonce"].as_str().unwrap()
    );
    json!({
        "challenge_id": data["challenge_id"],
        "signature": b64.encode(old_key.sign(text.as_bytes()).as_ref()),
        "new_signature": b64.encode(new_key.sign(text.as_bytes()).as_ref()),
    })
}

/// Closing the console ends only the browser session: the phone stays unlocked,
/// and a console session can never mint another console session.
#[tokio::test]
async fn console_close_is_scoped_and_cannot_self_renew() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let admin = register(&app, ip, "console_close_").await;
    let (admin_token, _) = unlock_admin(&app, &state, ip, &admin, "moderator").await;
    let browser = open_console(&app, ip, &admin_token, &admin.token).await;

    // A console session may not mint a browser session for someone else: the
    // handoff route is native-only, so a stolen cookie is not a foothold.
    let self_issued = app
        .clone()
        .oneshot(browser_post(
            "/admin/console-handoff",
            ip,
            Some(&browser.cookie),
            Some(&browser.csrf),
            Some("https://localhost"),
            json!({}),
        ))
        .await
        .unwrap();
    assert_eq!(self_issued.status(), StatusCode::NOT_FOUND, "the browser may not mint handoffs");

    // Closing is a state change, so it is protected like every other mutation.
    let open_but_unprotected = app
        .clone()
        .oneshot(browser_delete(
            "/administration/session",
            ip,
            &browser.cookie,
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            "https://localhost",
        ))
        .await
        .unwrap();
    assert_eq!(open_but_unprotected.status(), StatusCode::NOT_FOUND, "a wrong CSRF value must not close the console");
    let cross_site = app
        .clone()
        .oneshot(browser_delete(
            "/administration/session",
            ip,
            &browser.cookie,
            &browser.csrf,
            "https://attacker.example",
        ))
        .await
        .unwrap();
    assert_eq!(cross_site.status(), StatusCode::NOT_FOUND, "a cross-site close must be refused");
    let still_open = app.clone().oneshot(browser_get("/admin/summary", ip, &browser.cookie)).await.unwrap();
    assert_eq!(still_open.status(), StatusCode::OK, "the refused closes must not have taken effect");

    // The native session is still alive after closing the console.
    let close = app
        .clone()
        .oneshot(browser_delete(
            "/administration/session",
            ip,
            &browser.cookie,
            &browser.csrf,
            "https://localhost",
        ))
        .await
        .unwrap();
    assert_eq!(close.status(), StatusCode::OK);
    let response = app.clone().oneshot(browser_get("/admin/summary", ip, &browser.cookie)).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND, "the closed cookie is dead");

    let native = app
        .clone()
        .oneshot(with_admin(
            Request::builder()
                .method("GET")
                .uri("/admin/summary")
                .header("authorization", format!("Bearer {}", admin.token))
                .extension(ip)
                .body(Body::empty())
                .unwrap(),
            &admin_token,
        ))
        .await
        .unwrap();
    assert_eq!(native.status(), StatusCode::OK, "the phone must stay unlocked");

    let audit: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM admin_audit WHERE actor=$1 AND action='console:session-close'",
    )
    .bind(admin.id)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(audit, 1, "closing the console is audited");
}

/// The session endpoint answers honestly without leaking anything, and the
/// console surface is invisible to an ordinary account.
#[tokio::test]
async fn unauthenticated_browsers_learn_nothing() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();

    let response = app.clone().oneshot(gateway("/administration/session", ip)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body, json!({"authenticated": false}));

    // An issued-and-since-deleted session reports unauthenticated rather than
    // leaking why.
    let admin = register(&app, ip, "console_anon_").await;
    let (admin_token, _) = unlock_admin(&app, &state, ip, &admin, "moderator").await;
    let browser = open_console(&app, ip, &admin_token, &admin.token).await;
    sqlx::query("DELETE FROM admin_console_sessions WHERE user_id=$1")
        .bind(admin.id)
        .execute(&state.db)
        .await
        .unwrap();
    let response = app.clone().oneshot(browser_get("/administration/session", ip, &browser.cookie)).await.unwrap();
    assert_eq!(body_json(response).await, json!({"authenticated": false}));

    // Every administration route stays 404 for an ordinary account, and for a
    // browser that presents a cookie it was never given: there is no way to
    // tell "exists but forbidden" from "does not exist".
    let plain = register(&app, ip, "console_plain_404_").await;
    for path in [
        "/admin/summary",
        "/admin/reports",
        "/admin/feedback",
        "/admin/users",
        "/admin/audit",
        "/admin/storage",
        "/admin/server-policy",
    ] {
        let request = with_admin(
            Request::builder()
                .method("GET")
                .uri(path)
                .header("authorization", format!("Bearer {}", plain.token))
                .extension(ip)
                .body(Body::empty())
                .unwrap(),
            &admin_token,
        );
        let response = app.clone().oneshot(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{path} must stay invisible");

        let response = app
            .clone()
            .oneshot(browser_get(
                path,
                ip,
                "coco_admin_console=CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC",
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{path} with an invented cookie");
    }
}

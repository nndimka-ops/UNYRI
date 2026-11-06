//! Authentication-policy integration regression tests, isolated resources only.
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
    // Explicit TEST URLs keep this suite away from live deployment resources.
    let database_url =
        std::env::var("TEST_DATABASE_URL").expect("explicit isolated TEST_DATABASE_URL required");
    let database_name = database_url
        .rsplit('/')
        .next()
        .unwrap_or("")
        .split('?')
        .next()
        .unwrap_or("");
    assert!(
        database_name.contains("test") || database_name.contains("qa"),
        "refuse a non-test database"
    );
    let redis_url =
        std::env::var("TEST_REDIS_URL").expect("explicit isolated TEST_REDIS_URL required");
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

async fn body_json(response: axum::response::Response) -> Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap()
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
    assert_eq!(
        response.status(),
        StatusCode::OK,
        "registration must succeed"
    );
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

fn with_admin(mut request: Request<Body>, token: &str) -> Request<Body> {
    request
        .headers_mut()
        .insert("x-coco-admin", token.parse().unwrap());
    request
}

struct Unlocked {
    token: String,
    key: Option<ring::signature::Ed25519KeyPair>,
    recovery: Vec<String>,
}
async fn enroll(
    app: &axum::Router,
    state: &AppState,
    ip: ConnectInfo<SocketAddr>,
    account: &Account,
    policy: &str,
    role: &str,
) -> Unlocked {
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,$2)")
        .bind(account.id)
        .bind(role)
        .execute(&state.db)
        .await
        .unwrap();
    let setup = app
        .clone()
        .oneshot(post_json_request(
            "/admin/setup",
            ip,
            &account.token,
            json!({"password":"correct-horse-battery","auth_policy":policy}),
        ))
        .await
        .unwrap();
    assert_eq!(setup.status(), StatusCode::OK);
    let setup = body_json(setup).await;
    let key = if policy != "authenticator" {
        Some(
            ring::signature::Ed25519KeyPair::from_seed_unchecked(
                Uuid::new_v4().as_bytes().repeat(2).as_slice(),
            )
            .unwrap(),
        )
    } else {
        None
    };
    let mut body = json!({"password":"correct-horse-battery","auth_policy":policy});
    if let Some(key) = key.as_ref() {
        body["key_proof"] = admin_key_proof(app, ip, &account.token, "unlock", key).await;
    }
    if policy != "paper_key" {
        body["code"] = json!(otp(setup["secret"].as_str().unwrap()));
    }
    let response = app
        .clone()
        .oneshot(post_json_request("/admin/unlock", ip, &account.token, body))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let data = body_json(response).await;
    Unlocked {
        token: data["token"].as_str().unwrap().into(),
        key,
        recovery: data["recovery_codes"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| v.as_str().unwrap().into())
            .collect(),
    }
}
fn otp(secret: &str) -> String {
    let secret = totp_rs::Secret::try_from_base32(secret).unwrap();
    totp_rs::Builder::new()
        .with_secret(secret)
        .build()
        .unwrap()
        .generate_current()
        .to_string()
}
async fn post_admin(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    account: &Account,
    token: &str,
    path: &str,
    body: Value,
) -> axum::response::Response {
    app.clone()
        .oneshot(with_admin(
            post_json_request(path, ip, &account.token, body),
            token,
        ))
        .await
        .unwrap()
}
async fn get_admin(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    account: &Account,
    token: &str,
    path: &str,
) -> axum::response::Response {
    app.clone()
        .oneshot(with_admin(
            Request::builder()
                .uri(path)
                .header("authorization", format!("Bearer {}", account.token))
                .extension(ip)
                .body(Body::empty())
                .unwrap(),
            token,
        ))
        .await
        .unwrap()
}
async fn prepare(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    account: &Account,
    admin: &Unlocked,
    target: &str,
) -> Value {
    let result = post_admin(
        app,
        ip,
        account,
        &admin.token,
        "/admin/security/prepare",
        json!({"password":"correct-horse-battery","auth_policy":target}),
    )
    .await;
    assert_eq!(result.status(), StatusCode::OK);
    body_json(result).await
}
async fn policy_state(state: &AppState, user: Uuid) -> (String, i64, bool, bool) {
    sqlx::query_as("SELECT auth_policy,auth_revision,secret IS NOT NULL,signing_public_key IS NOT NULL FROM server_administrators WHERE user_id=$1").bind(user).fetch_one(&state.db).await.unwrap()
}
async fn handoff(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    account: &Account,
    admin: &Unlocked,
) -> String {
    let response = post_admin(
        app,
        ip,
        account,
        &admin.token,
        "/admin/console-handoff",
        json!({}),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await["handoff"]
        .as_str()
        .unwrap()
        .into()
}
async fn redeem(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    token: &str,
) -> axum::response::Response {
    app.clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/administration/handoff")
                .header("content-type", "application/json")
                .extension(ip)
                .body(Body::from(json!({"handoff":token}).to_string()))
                .unwrap(),
        )
        .await
        .unwrap()
}

#[tokio::test]
async fn all_three_policies_reach_native_and_browser_and_enforce_required_factors() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    for mode in ["authenticator", "paper_key", "both"] {
        let ip = connect_info();
        let account = register(&app, ip, "policy_modes_").await;
        let admin = enroll(&app, &state, ip, &account, mode, "moderator").await;
        assert_eq!(
            policy_state(&state, account.id).await,
            (mode.into(), 0, mode != "paper_key", mode != "authenticator")
        );
        let status =
            body_json(get_admin(&app, ip, &account, &admin.token, "/admin/security").await).await;
        assert_eq!(status["auth_protocol"], 2);
        assert_eq!(status["configured"], true);
        assert_eq!(
            status["available_policies"],
            json!(["authenticator", "paper_key", "both"])
        );
        assert_eq!(
            get_admin(&app, ip, &account, &admin.token, "/admin/summary")
                .await
                .status(),
            StatusCode::OK
        );
        let browser = redeem(&app, ip, &handoff(&app, ip, &account, &admin).await).await;
        assert_eq!(
            browser.status(),
            StatusCode::OK,
            "browser must support each selected policy"
        );
        let missing = app
            .clone()
            .oneshot(post_json_request(
                "/admin/unlock",
                ip,
                &account.token,
                json!({"password":"correct-horse-battery"}),
            ))
            .await
            .unwrap();
        assert_eq!(
            missing.status(),
            StatusCode::UNAUTHORIZED,
            "required factors cannot be omitted"
        );
        let changed = app
            .clone()
            .oneshot(post_json_request(
                "/admin/unlock",
                ip,
                &account.token,
                json!({"password":"correct-horse-battery","auth_policy":"password_only"}),
            ))
            .await
            .unwrap();
        assert_eq!(
            changed.status(),
            StatusCode::UNAUTHORIZED,
            "unlock cannot select a weaker policy"
        );
    }
}

#[tokio::test]
async fn both_to_authenticator_requires_old_factors_and_revokes_every_derived_session() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let account = register(&app, ip, "policy_remove_").await;
    let admin = enroll(&app, &state, ip, &account, "both", "owner").await;
    let pending_handoff = handoff(&app, ip, &account, &admin).await;
    assert_eq!(
        redeem(&app, ip, &handoff(&app, ip, &account, &admin).await)
            .await
            .status(),
        StatusCode::OK
    );
    prepare(&app, ip, &account, &admin, "authenticator").await;
    let wrong_password = post_admin(
        &app,
        ip,
        &account,
        &admin.token,
        "/admin/security",
        json!({"password":"wrong-password","auth_policy":"authenticator"}),
    )
    .await;
    assert_eq!(
        wrong_password.status(),
        StatusCode::UNAUTHORIZED,
        "an active admin token does not replace fresh password confirmation"
    );
    let missing=post_admin(&app,ip,&account,&admin.token,"/admin/security",json!({"password":"correct-horse-battery","auth_policy":"authenticator","code":admin.recovery[0]})).await;
    assert_eq!(missing.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(
        policy_state(&state, account.id).await,
        ("both".into(), 0, true, true)
    );
    let proof = admin_key_proof(
        &app,
        ip,
        &account.token,
        "authorize",
        admin.key.as_ref().unwrap(),
    )
    .await;
    let success=post_admin(&app,ip,&account,&admin.token,"/admin/security",json!({"password":"correct-horse-battery","auth_policy":"authenticator","code":admin.recovery[0],"key_proof":proof})).await;
    assert_eq!(success.status(), StatusCode::OK);
    assert_eq!(body_json(success).await["reauthenticate"], true);
    assert_eq!(
        policy_state(&state, account.id).await,
        ("authenticator".into(), 1, true, false)
    );
    assert_eq!(
        get_admin(&app, ip, &account, &admin.token, "/admin/summary")
            .await
            .status(),
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        redeem(&app, ip, &pending_handoff).await.status(),
        StatusCode::NOT_FOUND
    );
    let remaining: i64 =
        sqlx::query_scalar("SELECT count(*) FROM admin_console_sessions WHERE user_id=$1")
            .bind(account.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(remaining, 0);
    let reauth = app
        .clone()
        .oneshot(post_json_request(
            "/admin/unlock",
            ip,
            &account.token,
            json!({"password":"correct-horse-battery","code":admin.recovery[1]}),
        ))
        .await
        .unwrap();
    assert_eq!(
        reauth.status(),
        StatusCode::OK,
        "paper key is no longer required"
    );
    let reused = app
        .clone()
        .oneshot(post_json_request(
            "/admin/unlock",
            ip,
            &account.token,
            json!({"password":"correct-horse-battery","code":admin.recovery[1]}),
        ))
        .await
        .unwrap();
    assert_eq!(
        reused.status(),
        StatusCode::UNAUTHORIZED,
        "recovery code remains single use"
    );
}

#[tokio::test]
async fn adding_paper_requires_new_possession_proof_and_moderator_can_change_only_self() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let account = register(&app, ip, "policy_addpaper_").await;
    let admin = enroll(&app, &state, ip, &account, "authenticator", "moderator").await;
    let prepared = prepare(&app, ip, &account, &admin, "both").await;
    assert_eq!(prepared["requires_new_paper_key"], true);
    assert_eq!(prepared["requires_new_totp"], false);
    let mut body =
        json!({"password":"correct-horse-battery","auth_policy":"both","code":admin.recovery[0]});
    assert_eq!(
        post_admin(
            &app,
            ip,
            &account,
            &admin.token,
            "/admin/security",
            body.clone()
        )
        .await
        .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        policy_state(&state, account.id).await,
        ("authenticator".into(), 0, true, false)
    );
    let key = ring::signature::Ed25519KeyPair::from_seed_unchecked(&[94; 32]).unwrap();
    body["new_key_proof"] = admin_key_proof(&app, ip, &account.token, "enroll", &key).await;
    let response = post_admin(&app, ip, &account, &admin.token, "/admin/security", body).await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        policy_state(&state, account.id).await,
        ("both".into(), 1, true, true)
    );
}

#[tokio::test]
async fn adding_authenticator_needs_new_valid_code_and_retains_old_paper_identity() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let account = register(&app, ip, "policy_addtotp_").await;
    let admin = enroll(&app, &state, ip, &account, "paper_key", "owner").await;
    let prepared = prepare(&app, ip, &account, &admin, "both").await;
    assert_eq!(prepared["requires_new_totp"], true);
    assert_eq!(prepared["requires_new_paper_key"], false);
    let proof = admin_key_proof(
        &app,
        ip,
        &account.token,
        "authorize",
        admin.key.as_ref().unwrap(),
    )
    .await;
    let mut body = json!({"password":"correct-horse-battery","auth_policy":"both","key_proof":proof,"new_code":"not-a-code"});
    assert_eq!(
        post_admin(
            &app,
            ip,
            &account,
            &admin.token,
            "/admin/security",
            body.clone()
        )
        .await
        .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        policy_state(&state, account.id).await,
        ("paper_key".into(), 0, false, true)
    );
    body["new_code"] = json!(otp(prepared["secret"].as_str().unwrap()));
    let result = post_admin(&app, ip, &account, &admin.token, "/admin/security", body).await;
    assert_eq!(result.status(), StatusCode::OK);
    let result = body_json(result).await;
    assert_eq!(result["recovery_codes"].as_array().unwrap().len(), 8);
    assert_eq!(
        policy_state(&state, account.id).await,
        ("both".into(), 1, true, true)
    );
    let plain = result["recovery_codes"][0].as_str().unwrap();
    let stored: Vec<String> =
        sqlx::query_scalar("SELECT code_hash FROM admin_recovery_codes WHERE user_id=$1")
            .bind(account.id)
            .fetch_all(&state.db)
            .await
            .unwrap();
    assert!(!stored.iter().any(|hash| hash == plain));
}

#[tokio::test]
async fn concurrent_policy_commit_has_one_winner_and_clears_removed_totp() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let account = register(&app, ip, "policy_race_").await;
    let admin = enroll(&app, &state, ip, &account, "both", "owner").await;
    prepare(&app, ip, &account, &admin, "paper_key").await;
    let proof = admin_key_proof(
        &app,
        ip,
        &account.token,
        "authorize",
        admin.key.as_ref().unwrap(),
    )
    .await;
    let body = json!({"password":"correct-horse-battery","auth_policy":"paper_key","code":admin.recovery[0],"key_proof":proof});
    let (one, two) = tokio::join!(
        post_admin(
            &app,
            ip,
            &account,
            &admin.token,
            "/admin/security",
            body.clone()
        ),
        post_admin(&app, ip, &account, &admin.token, "/admin/security", body)
    );
    let statuses = [one.status(), two.status()];
    assert_eq!(statuses.iter().filter(|v| **v == StatusCode::OK).count(), 1);
    assert!(statuses.iter().all(|v| [
        StatusCode::OK,
        StatusCode::NOT_FOUND,
        StatusCode::UNAUTHORIZED
    ]
    .contains(v)));
    assert_eq!(
        policy_state(&state, account.id).await,
        ("paper_key".into(), 1, false, true)
    );
    let codes: i64 =
        sqlx::query_scalar("SELECT count(*) FROM admin_recovery_codes WHERE user_id=$1")
            .bind(account.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(codes, 0);
    let audit: i64=sqlx::query_scalar("SELECT count(*) FROM admin_audit WHERE actor=$1 AND action='operator:authentication-policy'").bind(account.id).fetch_one(&state.db).await.unwrap();
    assert_eq!(audit, 1);
}

#[tokio::test]
async fn reset_authenticator_reenrolls_with_existing_paper_key() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let owner = register(&app, ip, "policy_resetowner_").await;
    let owner_admin = enroll(&app, &state, ip, &owner, "paper_key", "owner").await;
    let target = register(&app, connect_info(), "policy_resettarget_").await;
    let tip = connect_info();
    let admin = enroll(&app, &state, tip, &target, "both", "moderator").await;
    let reset = post_admin(
        &app,
        ip,
        &owner,
        &owner_admin.token,
        &format!("/admin/operators/{}/reset-2fa", target.id),
        json!({"reason":"Isolated reset regression"}),
    )
    .await;
    assert_eq!(reset.status(), StatusCode::OK);
    assert_eq!(
        policy_state(&state, target.id).await,
        ("both".into(), 1, false, true)
    );
    let wrong_policy = app
        .clone()
        .oneshot(post_json_request(
            "/admin/setup",
            tip,
            &target.token,
            json!({"password":"correct-horse-battery","auth_policy":"paper_key"}),
        ))
        .await
        .unwrap();
    assert_eq!(
        wrong_policy.status(),
        StatusCode::CONFLICT,
        "reset cannot downgrade policy"
    );
    let setup = app
        .clone()
        .oneshot(post_json_request(
            "/admin/setup",
            tip,
            &target.token,
            json!({"password":"correct-horse-battery"}),
        ))
        .await
        .unwrap();
    assert_eq!(setup.status(), StatusCode::OK);
    let data = body_json(setup).await;
    assert_eq!(data["paper_key_configured"], true);
    let proof = admin_key_proof(
        &app,
        tip,
        &target.token,
        "unlock",
        admin.key.as_ref().unwrap(),
    )
    .await;
    let result=app.clone().oneshot(post_json_request("/admin/unlock",tip,&target.token,json!({"password":"correct-horse-battery","key_proof":proof,"code":otp(data["secret"].as_str().unwrap())}))).await.unwrap();
    assert_eq!(result.status(), StatusCode::OK);
}

#[tokio::test]
async fn concurrent_distinct_handoffs_leave_one_browser_session_and_replay_is_denied() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let account = register(&app, ip, "policy_handoff_").await;
    let admin = enroll(&app, &state, ip, &account, "authenticator", "owner").await;
    let one = handoff(&app, ip, &account, &admin).await;
    let two = handoff(&app, ip, &account, &admin).await;
    let (first, second) = tokio::join!(redeem(&app, ip, &one), redeem(&app, ip, &two));
    assert_eq!(first.status(), StatusCode::OK);
    assert_eq!(second.status(), StatusCode::OK);
    let count: i64 =
        sqlx::query_scalar("SELECT count(*) FROM admin_console_sessions WHERE user_id=$1")
            .bind(account.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(count, 1);
    assert_eq!(redeem(&app, ip, &one).await.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn ordinary_user_invalid_policies_and_stale_revision_cannot_authorize() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let account = register(&app, ip, "policy_denied_").await;
    assert_eq!(
        get_admin(&app, ip, &account, "not-an-admin-token", "/admin/security")
            .await
            .status(),
        StatusCode::NOT_FOUND
    );
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'owner')")
        .bind(account.id)
        .execute(&state.db)
        .await
        .unwrap();
    for invalid in ["password_only", "", "either"] {
        let result = app
            .clone()
            .oneshot(post_json_request(
                "/admin/setup",
                ip,
                &account.token,
                json!({"password":"correct-horse-battery","auth_policy":invalid}),
            ))
            .await
            .unwrap();
        assert_eq!(result.status(), StatusCode::BAD_REQUEST);
    }
    let second = register(&app, connect_info(), "policy_stale_").await;
    let sip = connect_info();
    let admin = enroll(&app, &state, sip, &second, "paper_key", "owner").await;
    sqlx::query("UPDATE server_administrators SET auth_revision=auth_revision+1 WHERE user_id=$1")
        .bind(second.id)
        .execute(&state.db)
        .await
        .unwrap();
    assert_eq!(
        get_admin(&app, sip, &second, &admin.token, "/admin/security")
            .await
            .status(),
        StatusCode::NOT_FOUND
    );
}

#[tokio::test]
async fn offline_cli_cannot_remove_or_demote_last_owner() {
    // A fresh disposable database avoids borrowing other tests' owner rows.
    let mut state = spawn_state().await;
    let name = format!("admin_policy_cli_{}", Uuid::new_v4().simple());
    sqlx::query(&format!("CREATE DATABASE {name}"))
        .execute(&state.db)
        .await
        .unwrap();
    let base = std::env::var("TEST_DATABASE_URL").unwrap();
    let url = format!("{}/{}", base.rsplit_once('/').unwrap().0, name);
    let db = sqlx::PgPool::connect(&url).await.unwrap();
    sqlx::migrate!().run(&db).await.unwrap();
    state.db = db.clone();
    state.config.database_url = url.clone();
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let owner = register(&app, ip, "policy_cli_owner_").await;
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'owner')")
        .bind(owner.id)
        .execute(&db)
        .await
        .unwrap();
    let username: String = sqlx::query_scalar("SELECT username FROM users WHERE id=$1")
        .bind(owner.id)
        .fetch_one(&db)
        .await
        .unwrap();
    for action in ["revoke", "grant-moderator"] {
        let result = tokio::process::Command::new(env!("CARGO_BIN_EXE_coco-admin"))
            .args([action, &username])
            .env("DATABASE_URL", &url)
            .output()
            .await
            .unwrap();
        assert!(!result.status.success(), "last owner must survive {action}");
        assert!(String::from_utf8_lossy(&result.stderr).contains("last owner"));
        let role: String =
            sqlx::query_scalar("SELECT role FROM server_administrators WHERE user_id=$1")
                .bind(owner.id)
                .fetch_one(&db)
                .await
                .unwrap();
        assert_eq!(role, "owner");
    }
    let other = register(&app, connect_info(), "policy_cli_other_").await;
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'owner')")
        .bind(other.id)
        .execute(&db)
        .await
        .unwrap();
    let result = tokio::process::Command::new(env!("CARGO_BIN_EXE_coco-admin"))
        .args(["revoke", &username])
        .env("DATABASE_URL", &url)
        .output()
        .await
        .unwrap();
    assert!(
        result.status.success(),
        "another owner remains, so explicit revoke is allowed"
    );
    let role: Option<String> =
        sqlx::query_scalar("SELECT role FROM server_administrators WHERE user_id=$1")
            .bind(owner.id)
            .fetch_optional(&db)
            .await
            .unwrap();
    assert!(role.is_none());
    let other_name: String = sqlx::query_scalar("SELECT username FROM users WHERE id=$1")
        .bind(other.id)
        .fetch_one(&db)
        .await
        .unwrap();
    sqlx::query(
        "UPDATE server_administrators SET secret=$2,signing_public_key=$3 WHERE user_id=$1",
    )
    .bind(other.id)
    .bind(vec![7_u8; 32])
    .bind(vec![8_u8; 32])
    .execute(&db)
    .await
    .unwrap();
    let refused = tokio::process::Command::new(env!("CARGO_BIN_EXE_coco-admin"))
        .args(["reset-authentication", &other_name])
        .env("DATABASE_URL", &url)
        .output()
        .await
        .unwrap();
    assert!(
        !refused.status.success(),
        "full reset requires the explicit confirmation flag"
    );
    assert_eq!(
        policy_state(&state, other.id).await,
        ("both".into(), 0, true, true)
    );
    let reset = tokio::process::Command::new(env!("CARGO_BIN_EXE_coco-admin"))
        .args([
            "reset-authentication",
            &other_name,
            "--confirm-reset-factors",
        ])
        .env("DATABASE_URL", &url)
        .output()
        .await
        .unwrap();
    assert!(reset.status.success());
    assert_eq!(
        policy_state(&state, other.id).await,
        ("both".into(), 1, false, false)
    );
}

#[tokio::test]
async fn expired_preparation_and_wrong_proof_purpose_leave_factors_unchanged() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let account = register(&app, ip, "policy_expiry_").await;
    let admin = enroll(&app, &state, ip, &account, "paper_key", "owner").await;
    let data = prepare(&app, ip, &account, &admin, "both").await;
    sqlx::query("UPDATE server_administrators SET pending_expires=now()-interval '1 second' WHERE user_id=$1").bind(account.id).execute(&state.db).await.unwrap();
    let proof = admin_key_proof(
        &app,
        ip,
        &account.token,
        "authorize",
        admin.key.as_ref().unwrap(),
    )
    .await;
    let expired=post_admin(&app,ip,&account,&admin.token,"/admin/security",json!({"password":"correct-horse-battery","auth_policy":"both","key_proof":proof,"new_code":otp(data["secret"].as_str().unwrap())})).await;
    assert_eq!(expired.status(), StatusCode::UNAUTHORIZED);
    let data = prepare(&app, ip, &account, &admin, "both").await;
    let wrong = admin_key_proof(
        &app,
        ip,
        &account.token,
        "unlock",
        admin.key.as_ref().unwrap(),
    )
    .await;
    let rejected=post_admin(&app,ip,&account,&admin.token,"/admin/security",json!({"password":"correct-horse-battery","auth_policy":"both","key_proof":wrong,"new_code":otp(data["secret"].as_str().unwrap())})).await;
    assert_eq!(rejected.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(
        policy_state(&state, account.id).await,
        ("paper_key".into(), 0, false, true)
    );
}

#[tokio::test]
async fn upgrading_existing_administrator_preserves_factors_and_legacy_session_policy() {
    let mut state = spawn_state().await;
    let name = format!("admin_policy_upgrade_qa_{}", Uuid::new_v4().simple());
    sqlx::query(&format!("CREATE DATABASE {name}"))
        .execute(&state.db)
        .await
        .unwrap();
    let base = std::env::var("TEST_DATABASE_URL").unwrap();
    let url = format!("{}/{}", base.rsplit_once('/').unwrap().0, name);
    let db = sqlx::PgPool::connect(&url).await.unwrap();
    let all = sqlx::migrate!();
    let previous = sqlx::migrate::Migrator {
        migrations: std::borrow::Cow::Owned(
            all.iter().filter(|m| m.version < 48).cloned().collect(),
        ),
        ..sqlx::migrate::Migrator::DEFAULT
    };
    previous.run(&db).await.unwrap();
    state.db = db.clone();
    state.config.database_url = url;
    let app = messenger_server::app(state.clone());
    let account = register(&app, connect_info(), "policy_upgrade_").await;
    let secret = vec![17_u8; 40];
    let public = vec![29_u8; 32];
    sqlx::query("INSERT INTO server_administrators(user_id,role,secret,signing_public_key,last_step) VALUES($1,'owner',$2,$3,123)")
        .bind(account.id).bind(&secret).bind(&public).execute(&db).await.unwrap();
    let auth_session: Uuid = sqlx::query_scalar("SELECT id FROM sessions WHERE user_id=$1 LIMIT 1")
        .bind(account.id)
        .fetch_one(&db)
        .await
        .unwrap();
    let parent = messenger_server::admin::hash("pre-policy-native-session");
    sqlx::query("INSERT INTO admin_sessions(token_hash,user_id,auth_session,signing_public_key) VALUES($1,$2,$3,$4)")
        .bind(&parent).bind(account.id).bind(auth_session).bind(&public).execute(&db).await.unwrap();
    for token in ["old-one", "old-two"] {
        sqlx::query("INSERT INTO admin_console_sessions(token_hash,csrf_hash,user_id,auth_session,admin_session,role,expires_at) VALUES($1,'fixture-hash',$2,$3,$4,'owner',now()+interval '1 hour')")
            .bind(token).bind(account.id).bind(auth_session).bind(&parent).execute(&db).await.unwrap();
    }
    all.run(&db).await.unwrap();
    assert_eq!(
        policy_state(&state, account.id).await,
        ("both".into(), 0, true, true)
    );
    let unchanged: (Vec<u8>, Vec<u8>, i64) = sqlx::query_as(
        "SELECT secret,signing_public_key,last_step FROM server_administrators WHERE user_id=$1",
    )
    .bind(account.id)
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(unchanged, (secret, public, 123));
    let revision: i64 =
        sqlx::query_scalar("SELECT auth_revision FROM admin_sessions WHERE token_hash=$1")
            .bind(&parent)
            .fetch_one(&db)
            .await
            .unwrap();
    assert_eq!(revision, 0);
    let browser_count: i64 =
        sqlx::query_scalar("SELECT count(*) FROM admin_console_sessions WHERE user_id=$1")
            .bind(account.id)
            .fetch_one(&db)
            .await
            .unwrap();
    assert_eq!(browser_count, 1);
}

/// A request may pass HTTP extraction/password verification and then wait for
/// another administrator transaction. Logout during that wait must be checked
/// again at the mutation admission point, before installing any new factors.
#[tokio::test]
async fn enrollment_waiting_on_admin_row_cannot_commit_after_ordinary_logout() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let account = register(&app, ip, "policy_waitlogout_").await;
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'owner')")
        .bind(account.id)
        .execute(&state.db)
        .await
        .unwrap();
    let prepared = app
        .clone()
        .oneshot(post_json_request(
            "/admin/setup", ip, &account.token,
            json!({"password":"correct-horse-battery","auth_policy":"paper_key"}),
        ))
        .await
        .unwrap();
    assert_eq!(prepared.status(), StatusCode::OK);
    let key = ring::signature::Ed25519KeyPair::from_seed_unchecked(
        Uuid::new_v4().as_bytes().repeat(2).as_slice(),
    ).unwrap();
    let proof = admin_key_proof(&app, ip, &account.token, "unlock", &key).await;
    let before = policy_state(&state, account.id).await;
    assert_eq!(before, ("both".into(), 0, false, false));

    let mut barrier = state.db.begin().await.unwrap();
    let barrier_pid: i32 = sqlx::query_scalar("SELECT pg_backend_pid()")
        .fetch_one(&mut *barrier).await.unwrap();
    sqlx::query("SELECT user_id FROM server_administrators WHERE user_id=$1 FOR UPDATE")
        .bind(account.id).fetch_one(&mut *barrier).await.unwrap();
    let pending_app = app.clone();
    let pending_request = post_json_request(
        "/admin/unlock", ip, &account.token,
        json!({"password":"correct-horse-battery","auth_policy":"paper_key","key_proof":proof}),
    );
    let pending = tokio::spawn(async move { pending_app.oneshot(pending_request).await.unwrap() });

    // Observe the real PostgreSQL lock wait; no timing assumption substitutes
    // for knowing that HTTP admission/password verification already completed.
    wait_for_admin_row_barrier(&state, barrier_pid).await;
    let logout = tokio::time::timeout(std::time::Duration::from_secs(5), app.clone().oneshot(
        post_json_request("/auth/logout", ip, &account.token, json!({})),
    )).await.expect("ordinary logout must complete while the administrator row is locked").unwrap();
    assert_eq!(logout.status(), StatusCode::NO_CONTENT);
    let revoked: bool = sqlx::query_scalar(
        "SELECT revoked_at IS NOT NULL FROM sessions WHERE user_id=$1 ORDER BY created_at DESC LIMIT 1",
    ).bind(account.id).fetch_one(&state.db).await.unwrap();
    assert!(revoked, "revocation must be committed before releasing the barrier");

    barrier.commit().await.unwrap();
    let response = tokio::time::timeout(std::time::Duration::from_secs(10), pending)
        .await.expect("released unlock must complete").unwrap();
    let result_status = response.status();
    let after = policy_state(&state, account.id).await;
    let admin_sessions: i64 = sqlx::query_scalar("SELECT count(*) FROM admin_sessions WHERE user_id=$1")
        .bind(account.id).fetch_one(&state.db).await.unwrap();
    println!("late-admission proof: logout=204 revoked=true unlock={} factors_before={:?} factors_after={:?} admin_sessions={}",
        result_status.as_u16(), before, after, admin_sessions);
    if result_status == StatusCode::OK {
        let data = body_json(response).await;
        let token = data["token"].as_str().unwrap();
        let unusable = get_admin(&app, ip, &account, token, "/admin/summary").await;
        assert_eq!(unusable.status(), StatusCode::UNAUTHORIZED,
            "a token attached to the logged-out bearer must remain unusable");
    }
    assert_eq!(result_status, StatusCode::UNAUTHORIZED,
        "an enrollment that waited through logout must be rejected at admission");
    assert_eq!(after, before, "no factor or policy may be installed after the waiting session was revoked");
    assert_eq!(admin_sessions, 0, "no administrative session may be created for the revoked bearer");
}

async fn wait_for_admin_row_barrier(state: &AppState, barrier_pid: i32) -> i32 {
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(10);
    loop {
        let waiting: Option<i32> = sqlx::query_scalar(
            "SELECT a.pid FROM pg_stat_activity a WHERE a.datname=current_database() AND a.wait_event_type='Lock' AND $1=ANY(pg_blocking_pids(a.pid)) AND a.query LIKE '%FROM server_administrators WHERE user_id=$1 FOR UPDATE' LIMIT 1",
        ).bind(barrier_pid).fetch_optional(&state.db).await.unwrap();
        if let Some(pid) = waiting { return pid; }
        assert!(tokio::time::Instant::now() < deadline, "the sensitive request must reach the actual administrator-row lock wait");
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
}

#[tokio::test]
async fn setup_waiting_on_admin_row_cannot_prepare_factors_after_ordinary_logout() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let account = register(&app, ip, "policy_waitsetup_").await;
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'owner')")
        .bind(account.id).execute(&state.db).await.unwrap();
    let mut barrier = state.db.begin().await.unwrap();
    let barrier_pid: i32 = sqlx::query_scalar("SELECT pg_backend_pid()")
        .fetch_one(&mut *barrier).await.unwrap();
    sqlx::query("SELECT user_id FROM server_administrators WHERE user_id=$1 FOR UPDATE")
        .bind(account.id).fetch_one(&mut *barrier).await.unwrap();
    let pending_app = app.clone();
    let request = post_json_request("/admin/setup", ip, &account.token,
        json!({"password":"correct-horse-battery","auth_policy":"authenticator"}));
    let pending = tokio::spawn(async move { pending_app.oneshot(request).await.unwrap() });
    wait_for_admin_row_barrier(&state, barrier_pid).await;
    let logout = tokio::time::timeout(std::time::Duration::from_secs(5), app.clone().oneshot(
        post_json_request("/auth/logout", ip, &account.token, json!({})),
    )).await.expect("logout must complete while setup waits").unwrap();
    assert_eq!(logout.status(), StatusCode::NO_CONTENT);
    let revoked: bool = sqlx::query_scalar("SELECT revoked_at IS NOT NULL FROM sessions WHERE user_id=$1 ORDER BY created_at DESC LIMIT 1")
        .bind(account.id).fetch_one(&state.db).await.unwrap();
    assert!(revoked);
    barrier.commit().await.unwrap();
    let response = tokio::time::timeout(std::time::Duration::from_secs(10), pending)
        .await.expect("released setup must complete").unwrap();
    let pending_factors: (bool, bool, bool) = sqlx::query_as(
        "SELECT pending_secret IS NOT NULL,pending_session IS NOT NULL,pending_policy IS NOT NULL FROM server_administrators WHERE user_id=$1",
    ).bind(account.id).fetch_one(&state.db).await.unwrap();
    println!("late-setup proof: logout=204 revoked=true setup={} pending_secret/session/policy={:?}",
        response.status().as_u16(), pending_factors);
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED, "setup admitted before logout must recheck the session after waiting");
    assert_eq!(pending_factors, (false, false, false), "revoked setup must not leave prepared factors behind");
}

#[tokio::test]
async fn key_rotation_waiting_on_admin_row_cannot_replace_key_after_ordinary_logout() {
    use base64::Engine;
    use ring::signature::KeyPair;
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let account = register(&app, ip, "policy_waitrotate_").await;
    let admin = enroll(&app, &state, ip, &account, "paper_key", "owner").await;
    let old_key = admin.key.as_ref().unwrap();
    let new_key = ring::signature::Ed25519KeyPair::from_seed_unchecked(
        Uuid::new_v4().as_bytes().repeat(2).as_slice(),
    ).unwrap();
    let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD;
    let public = b64.encode(new_key.public_key().as_ref());
    let challenge = app.clone().oneshot(post_json_request(
        "/admin/key-challenge", ip, &account.token,
        json!({"purpose":"rotate","public_key":public}),
    )).await.unwrap();
    assert_eq!(challenge.status(), StatusCode::OK);
    let challenge = body_json(challenge).await;
    let text = format!("COCO-ADMIN-PROOF-V1\n{}\n{}\n{}\n{}\nrotate\n{}\n{}",
        challenge["server_id"].as_str().unwrap(), challenge["user_id"].as_str().unwrap(),
        challenge["auth_session"].as_str().unwrap(), challenge["challenge_id"].as_str().unwrap(),
        public, challenge["nonce"].as_str().unwrap());
    let proof = json!({"challenge_id":challenge["challenge_id"],
        "signature":b64.encode(old_key.sign(text.as_bytes()).as_ref()),
        "new_signature":b64.encode(new_key.sign(text.as_bytes()).as_ref())});
    let before = policy_state(&state, account.id).await;
    let before_public = old_key.public_key().as_ref().to_vec();
    let mut barrier = state.db.begin().await.unwrap();
    let barrier_pid: i32 = sqlx::query_scalar("SELECT pg_backend_pid()")
        .fetch_one(&mut *barrier).await.unwrap();
    sqlx::query("SELECT user_id FROM server_administrators WHERE user_id=$1 FOR UPDATE")
        .bind(account.id).fetch_one(&mut *barrier).await.unwrap();
    let pending_app = app.clone();
    let request = with_admin(post_json_request("/admin/key-rotate", ip, &account.token, proof), &admin.token);
    let pending = tokio::spawn(async move { pending_app.oneshot(request).await.unwrap() });
    wait_for_admin_row_barrier(&state, barrier_pid).await;
    let logout = tokio::time::timeout(std::time::Duration::from_secs(5), app.clone().oneshot(
        post_json_request("/auth/logout", ip, &account.token, json!({})),
    )).await.expect("logout must complete while rotation waits").unwrap();
    assert_eq!(logout.status(), StatusCode::NO_CONTENT);
    let revoked: bool = sqlx::query_scalar("SELECT revoked_at IS NOT NULL FROM sessions WHERE user_id=$1 ORDER BY created_at DESC LIMIT 1")
        .bind(account.id).fetch_one(&state.db).await.unwrap();
    assert!(revoked);
    barrier.commit().await.unwrap();
    let response = tokio::time::timeout(std::time::Duration::from_secs(10), pending)
        .await.expect("released key rotation must complete").unwrap();
    let after = policy_state(&state, account.id).await;
    let after_public: Vec<u8> = sqlx::query_scalar("SELECT signing_public_key FROM server_administrators WHERE user_id=$1")
        .bind(account.id).fetch_one(&state.db).await.unwrap();
    println!("late-rotation proof: logout=204 revoked=true rotate={} policy_revision_unchanged={} public_key_unchanged={}",
        response.status().as_u16(), before == after, before_public == after_public);
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED, "rotation must recheck the waiting session before installing a key");
    assert_eq!(after, before, "a revoked rotation must not change policy or revision");
    assert_eq!(after_public, before_public, "a revoked rotation must leave the original signing key intact");
}

#[tokio::test]
async fn enrollment_waiting_on_admin_row_rechecks_real_session_expiry_not_transaction_start() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let account = register(&app, ip, "policy_waitexpiry_").await;
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'owner')")
        .bind(account.id).execute(&state.db).await.unwrap();
    let setup = app.clone().oneshot(post_json_request("/admin/setup", ip, &account.token,
        json!({"password":"correct-horse-battery","auth_policy":"paper_key"}))).await.unwrap();
    assert_eq!(setup.status(), StatusCode::OK);
    let key = ring::signature::Ed25519KeyPair::from_seed_unchecked(
        Uuid::new_v4().as_bytes().repeat(2).as_slice(),
    ).unwrap();
    let proof = admin_key_proof(&app, ip, &account.token, "unlock", &key).await;
    let before = policy_state(&state, account.id).await;
    let mut barrier = state.db.begin().await.unwrap();
    let barrier_pid: i32 = sqlx::query_scalar("SELECT pg_backend_pid()")
        .fetch_one(&mut *barrier).await.unwrap();
    sqlx::query("SELECT user_id FROM server_administrators WHERE user_id=$1 FOR UPDATE")
        .bind(account.id).fetch_one(&mut *barrier).await.unwrap();
    let pending_app = app.clone();
    let request = post_json_request("/admin/unlock", ip, &account.token,
        json!({"password":"correct-horse-battery","auth_policy":"paper_key","key_proof":proof}));
    let pending = tokio::spawn(async move { pending_app.oneshot(request).await.unwrap() });
    let waiting_pid = wait_for_admin_row_barrier(&state, barrier_pid).await;
    sqlx::query("UPDATE sessions SET expires_at=clock_timestamp() WHERE user_id=$1")
        .bind(account.id).execute(&state.db).await.unwrap();
    let expired_after_start: bool = sqlx::query_scalar(
        "SELECT s.expires_at<=clock_timestamp() AND s.expires_at>(SELECT xact_start FROM pg_stat_activity WHERE pid=$2) FROM sessions s WHERE s.user_id=$1 ORDER BY s.created_at DESC LIMIT 1",
    ).bind(account.id).bind(waiting_pid).fetch_one(&state.db).await.unwrap();
    assert!(expired_after_start,
        "fixture expiry must be after the waiting transaction started but before real-time admission");
    barrier.commit().await.unwrap();
    let response = tokio::time::timeout(std::time::Duration::from_secs(10), pending)
        .await.expect("released expired-session enrollment must complete").unwrap();
    let after = policy_state(&state, account.id).await;
    let admin_sessions: i64 = sqlx::query_scalar("SELECT count(*) FROM admin_sessions WHERE user_id=$1")
        .bind(account.id).fetch_one(&state.db).await.unwrap();
    println!("late-expiry proof: database_expiry_after_tx_start=true unlock={} factors_unchanged={} admin_sessions={}",
        response.status().as_u16(), before == after, admin_sessions);
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED, "session expiry must use wall-clock time after the lock wait, not now() at transaction start");
    assert_eq!(after, before, "an expired session cannot install factors");
    assert_eq!(admin_sessions, 0, "an expired session cannot create administration sessions");
}

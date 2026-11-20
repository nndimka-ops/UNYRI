//! Real-router device-binding regressions. This suite only accepts an explicitly
//! opted-in disposable loopback database and nonzero Redis database. No dotenv,
//! cloud storage, push provider or external application server is used.
use axum::{
    body::Body,
    extract::ConnectInfo,
    http::{Request, StatusCode},
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use http_body_util::BodyExt;
use messenger_server::{
    auth::{jwt, refresh_token},
    config::{Config, S3Config},
    state::AppState,
};
use redis::AsyncCommands;
use ring::{
    rand::SystemRandom,
    signature::{EcdsaKeyPair, KeyPair, ECDSA_P256_SHA256_ASN1_SIGNING},
};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{net::SocketAddr, sync::Arc};
use tokio_tungstenite::tungstenite::{client::IntoClientRequest, Error as WsError};
use tower::ServiceExt;
use uuid::Uuid;

const PASSWORD: &str = "device-binding-fixture-strong-password";

async fn fixture() -> (AppState, axum::Router) {
    assert_eq!(
        std::env::var("COCO_DEVICE_BOUND_TEST_ISOLATED").as_deref(),
        Ok("1")
    );
    let database_url = std::env::var("TEST_DATABASE_URL")
        .expect("explicit disposable device_bound_test DB required");
    let redis_url = std::env::var("TEST_REDIS_URL").expect("explicit disposable Redis required");
    for raw in [&database_url, &redis_url] {
        let url = reqwest::Url::parse(raw).unwrap();
        assert!(
            matches!(url.host_str(), Some("127.0.0.1" | "localhost" | "[::1]")),
            "loopback fixture only"
        );
    }
    assert_eq!(
        reqwest::Url::parse(&database_url).unwrap().path(),
        "/device_bound_test"
    );
    let redis_db: usize = reqwest::Url::parse(&redis_url)
        .unwrap()
        .path()
        .trim_start_matches('/')
        .parse()
        .expect("explicit disposable Redis database number required");
    assert!(redis_db > 0, "default Redis database is forbidden");
    let db = sqlx::postgres::PgPoolOptions::new()
        .max_connections(8)
        .connect(&database_url)
        .await
        .unwrap();
    sqlx::migrate!().run(&db).await.unwrap();
    let redis = redis::Client::open(redis_url.clone())
        .unwrap()
        .get_connection_manager()
        .await
        .unwrap();
    let config = Config {
        identity: Default::default(),
        host: "127.0.0.1".into(),
        port: 0,
        database_url,
        database_max_connections: 8,
        redis_url,
        jwt_secret: "device-bound-fixture-only-secret".into(),
        trusted_proxies: vec![],
        jwt_issuer: "device-bound-test".into(),
        jwt_audience: "device-bound-test".into(),
        access_token_ttl_seconds: 900,
        refresh_token_ttl_seconds: 86400,
        s3: S3Config {
            endpoint_url: "http://127.0.0.1:1".into(),
            public_endpoint_url: "http://127.0.0.1:1".into(),
            region: "test".into(),
            bucket: "device-bound-test".into(),
            access_key_id: "test".into(),
            secret_access_key: "test".into(),
            upload_access_key_id: "test".into(),
            upload_secret_access_key: "test".into(),
            max_upload_duration_seconds: 60,
            presign_ttl_seconds: 600,
        },
        turn: messenger_server::config::TurnConfig {
            stun_urls: vec![],
            turn_urls: vec![],
            shared_secret: "fixture-only".into(),
            credential_ttl_seconds: 600,
        },
        fcm: Default::default(),
    };
    let s3 = Arc::new(messenger_server::storage::S3Client::new(config.s3.clone()));
    let state = AppState {
        resources: Default::default(),
        db,
        redis,
        config,
        ws_registry: Default::default(),
        s3,
        push: messenger_server::push::disabled(),
    };
    let app = messenger_server::app(state.clone());
    (state, app)
}

struct DeviceKey(EcdsaKeyPair);
impl DeviceKey {
    fn new() -> Self {
        let rng = SystemRandom::new();
        let pkcs8 = EcdsaKeyPair::generate_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, &rng).unwrap();
        Self(
            EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, pkcs8.as_ref(), &rng)
                .unwrap(),
        )
    }
    fn registration(&self) -> Value {
        json!({"public_key": URL_SAFE_NO_PAD.encode(self.0.public_key().as_ref()), "protection": "tee"})
    }
    // Deliberately independent from the server proof builder: changing canonical
    // order, separators, token hashing or DER encoding must break the integration.
    fn proof(&self, method: &str, target: &str, session: Uuid, token: &str, ts: i64) -> String {
        let jti = Uuid::new_v4();
        let token_hash = URL_SAFE_NO_PAD.encode(Sha256::digest(token.as_bytes()));
        let bytes =
            format!("UNYRI-DBS-1\n{method}\n{target}\n{session}\n{ts}\n{jti}\n{token_hash}");
        let signature = self.0.sign(&SystemRandom::new(), bytes.as_bytes()).unwrap();
        URL_SAFE_NO_PAD.encode(
            json!({"ts": ts, "jti": jti, "sig": URL_SAFE_NO_PAD.encode(signature.as_ref())})
                .to_string(),
        )
    }
    fn refresh_proof(&self, token: &str) -> String {
        self.proof(
            "POST",
            "/auth/refresh",
            refresh_token::parse_session_id(token).unwrap(),
            token,
            chrono::Utc::now().timestamp(),
        )
    }
}

async fn send(
    app: &axum::Router,
    method: &str,
    path: &str,
    token: &str,
    body: Value,
    proof: Option<&str>,
) -> (StatusCode, Value) {
    // Only the in-process request identity is synthetic; all sockets stay loopback.
    let octets: [u8; 3] = std::array::from_fn(|_| rand::random());
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("content-type", "application/json")
        .header("authorization", format!("Bearer {token}"))
        .extension(ConnectInfo(SocketAddr::from((
            [10, octets[0], octets[1], octets[2]],
            12345,
        ))));
    if let Some(proof) = proof {
        request = request.header("x-unyri-device-proof", proof);
    }
    let response = app
        .clone()
        .oneshot(request.body(Body::from(body.to_string())).unwrap())
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    )
}

struct Session {
    access: String,
    refresh: String,
    id: Uuid,
}
impl Session {
    fn from_response(state: &AppState, response: &Value) -> Self {
        let access = response["access_token"]
            .as_str()
            .expect("access token")
            .to_owned();
        let id = jwt::decode_access_token(&state.config, &access)
            .unwrap()
            .session_id;
        Self {
            access,
            refresh: response["refresh_token"]
                .as_str()
                .expect("refresh token")
                .to_owned(),
            id,
        }
    }
}
async fn register(
    state: &AppState,
    app: &axum::Router,
    key: Option<&DeviceKey>,
) -> (String, Session) {
    let username = format!("dbs_{}", &Uuid::new_v4().simple().to_string()[..16]);
    let installation = Uuid::new_v4();
    let mut body = json!({"username": username, "password": PASSWORD, "display_name": "Device Binding",
        "installation_id": installation, "device_platform": "android"});
    let proof = key.map(|k| {
        body["device_key"] = k.registration();
        k.proof(
            "POST",
            "/auth/register",
            installation,
            "",
            chrono::Utc::now().timestamp(),
        )
    });
    let (status, response) = send(app, "POST", "/auth/register", "", body, proof.as_deref()).await;
    assert_eq!(
        status,
        StatusCode::OK,
        "registration error: {}",
        response["error"]
    );
    (username, Session::from_response(state, &response))
}
async fn login(
    state: &AppState,
    app: &axum::Router,
    username: &str,
    key: Option<&DeviceKey>,
) -> Session {
    let installation = Uuid::new_v4();
    let mut body = json!({"username": username, "password": PASSWORD,
        "installation_id": installation, "device_platform": "android"});
    let proof = key.map(|k| {
        body["device_key"] = k.registration();
        k.proof(
            "POST",
            "/auth/login",
            installation,
            "",
            chrono::Utc::now().timestamp(),
        )
    });
    let (status, response) = send(app, "POST", "/auth/login", "", body, proof.as_deref()).await;
    assert_eq!(status, StatusCode::OK, "login error: {}", response["error"]);
    Session::from_response(state, &response)
}
async fn assert_binding(state: &AppState, session: &Session, key: &DeviceKey) {
    let (public, protection): (Option<Vec<u8>>, Option<String>) =
        sqlx::query_as("SELECT device_public_key,device_key_protection FROM sessions WHERE id=$1")
            .bind(session.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(public.as_deref(), Some(key.0.public_key().as_ref()));
    assert_eq!(protection.as_deref(), Some("tee"));
    let claims = jwt::decode_access_token(&state.config, &session.access).unwrap();
    assert!(
        claims.exp - claims.iat <= unyri_bound_ttl(),
        "bound access lifetime exceeds documented theft window"
    );
}
fn unyri_bound_ttl() -> i64 {
    messenger_server::auth::device_proof::BOUND_ACCESS_TTL_SECONDS
}
fn assert_update_required(response: (StatusCode, Value)) {
    assert_eq!(response.0, StatusCode::UPGRADE_REQUIRED);
    assert_eq!(response.1["error"]["code"], "app_update_required");
}
fn assert_terminal(response: (StatusCode, Value)) {
    assert_eq!(response.0, StatusCode::UNAUTHORIZED);
    assert_eq!(response.1["error"]["code"], "session_revoked");
    assert!(
        response.1["error"]["server_time"].is_null(),
        "bad proof must not get a clock challenge"
    );
}

async fn assert_ws_denied(addr: SocketAddr, token: &str, proof: Option<&str>) {
    let mut request = format!("ws://{addr}/ws").into_client_request().unwrap();
    request
        .headers_mut()
        .insert("authorization", format!("Bearer {token}").parse().unwrap());
    if let Some(proof) = proof {
        request
            .headers_mut()
            .insert("x-unyri-device-proof", proof.parse().unwrap());
    }
    match tokio_tungstenite::connect_async(request).await {
        Err(WsError::Http(response)) => assert_eq!(response.status(), StatusCode::UNAUTHORIZED),
        // Do not print the request or proof in assertion failures.
        _ => panic!("WebSocket admission must fail with HTTP 401"),
    }
}

// One serial scenario intentionally owns the policy switch for the entire run.
// Separate tests sharing server_policy could race with each other's rollout state.
#[tokio::test]
async fn device_bound_sessions_enforce_possession_replay_skew_rollout_and_websocket_contracts() {
    let (state, app) = fixture().await;
    let required: bool = sqlx::query_scalar(
        "SELECT device_bound_sessions_required FROM server_policy WHERE id=true",
    )
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert!(
        !required,
        "fresh migration must leave owner enforcement off"
    );
    let key = DeviceKey::new();
    let other_key = DeviceKey::new();

    // Old clients keep working while rollout is off; old access lifetime is unchanged.
    let (username, legacy_parent) = register(&state, &app, None).await;
    let claims = jwt::decode_access_token(&state.config, &legacy_parent.access).unwrap();
    assert_eq!(claims.exp - claims.iat, 900);
    let request_id = Uuid::new_v4();
    let (status, body) = send(
        &app,
        "POST",
        "/auth/refresh",
        "",
        json!({"refresh_token": legacy_parent.refresh, "request_id": request_id}),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let legacy = Session::from_response(&state, &body);

    // First refresh on an upgraded app enrolls this key and binds every ancestor.
    let proof = key.refresh_proof(&legacy.refresh);
    let (status, body) = send(&app, "POST", "/auth/refresh", "",
        json!({"refresh_token": legacy.refresh, "request_id": Uuid::new_v4(), "device_key": key.registration()}), Some(&proof)).await;
    assert_eq!(
        status,
        StatusCode::OK,
        "legacy enrollment error: {}",
        body["error"]
    );
    let bound = Session::from_response(&state, &body);
    assert_binding(&state, &bound, &key).await;
    assert!(
        sqlx::query(
            "UPDATE sessions SET device_public_key=NULL,device_key_protection=NULL WHERE id=$1"
        )
        .bind(bound.id)
        .execute(&state.db)
        .await
        .is_err(),
        "database allowed binding removal"
    );
    for ancestor in [&legacy_parent, &legacy] {
        let stored: Option<Vec<u8>> =
            sqlx::query_scalar("SELECT device_public_key FROM sessions WHERE id=$1")
                .bind(ancestor.id)
                .fetch_one(&state.db)
                .await
                .unwrap();
        assert_eq!(
            stored.as_deref(),
            Some(key.0.public_key().as_ref()),
            "legacy ancestor left downgradeable"
        );
        assert_terminal(
            send(
                &app,
                "POST",
                "/auth/refresh",
                "",
                json!({"refresh_token": ancestor.refresh, "request_id": request_id}),
                None,
            )
            .await,
        );
    }

    // Failed theft attempts never revoke the genuine session.
    let device = jwt::decode_access_token(&state.config, &bound.access)
        .unwrap()
        .device_id;
    let limiter_key = format!("ratelimit:refresh-device:{device}");
    let mut limiter = state.redis.clone();
    let refresh_budget_before: Option<u64> = limiter.get(&limiter_key).await.unwrap();
    assert_terminal(
        send(
            &app,
            "POST",
            "/auth/refresh",
            "",
            json!({"refresh_token": bound.refresh}),
            None,
        )
        .await,
    );
    assert_terminal(
        send(
            &app,
            "POST",
            "/auth/refresh",
            "",
            json!({"refresh_token": bound.refresh}),
            Some("malformed"),
        )
        .await,
    );
    let proof = other_key.refresh_proof(&bound.refresh);
    assert_terminal(
        send(
            &app,
            "POST",
            "/auth/refresh",
            "",
            json!({"refresh_token": bound.refresh}),
            Some(&proof),
        )
        .await,
    );
    let proof = key.refresh_proof(&bound.refresh);
    assert_terminal(
        send(
            &app,
            "POST",
            "/auth/refresh",
            "",
            json!({"refresh_token": bound.refresh, "device_key": other_key.registration()}),
            Some(&proof),
        )
        .await,
    );
    for (method, target, session, token) in [
        ("GET", "/auth/refresh", bound.id, bound.refresh.as_str()),
        ("POST", "ws", bound.id, bound.refresh.as_str()),
        (
            "POST",
            "/auth/refresh",
            Uuid::new_v4(),
            bound.refresh.as_str(),
        ),
        (
            "POST",
            "/auth/refresh",
            bound.id,
            "different-presented-token",
        ),
    ] {
        let proof = key.proof(
            method,
            target,
            session,
            token,
            chrono::Utc::now().timestamp(),
        );
        assert_terminal(
            send(
                &app,
                "POST",
                "/auth/refresh",
                "",
                json!({"refresh_token": bound.refresh}),
                Some(&proof),
            )
            .await,
        );
    }
    // Even a stale proof gets a clock hint only after verifying the genuine key.
    let forged_stale = other_key.proof(
        "POST",
        "/auth/refresh",
        bound.id,
        &bound.refresh,
        chrono::Utc::now().timestamp() - 120,
    );
    assert_terminal(
        send(
            &app,
            "POST",
            "/auth/refresh",
            "",
            json!({"refresh_token": bound.refresh}),
            Some(&forged_stale),
        )
        .await,
    );
    let refresh_budget_after: Option<u64> = limiter.get(&limiter_key).await.unwrap();
    assert_eq!(
        refresh_budget_after, refresh_budget_before,
        "copied-token attempts without device possession spent the genuine device budget"
    );

    // Proof replay is terminal, but a fresh signature preserves rotation idempotency.
    let rotation_id = Uuid::new_v4();
    let rotation_body = json!({"refresh_token": bound.refresh, "request_id": rotation_id});
    let proof = key.refresh_proof(&bound.refresh);
    let (status, body) = send(
        &app,
        "POST",
        "/auth/refresh",
        "",
        rotation_body.clone(),
        Some(&proof),
    )
    .await;
    assert_eq!(
        status,
        StatusCode::OK,
        "genuine device lost its session after theft attempts"
    );
    let rotated = Session::from_response(&state, &body);
    assert_binding(&state, &rotated, &key).await;
    assert_terminal(
        send(
            &app,
            "POST",
            "/auth/refresh",
            "",
            rotation_body.clone(),
            Some(&proof),
        )
        .await,
    );
    let fresh_proof = key.refresh_proof(&bound.refresh);
    let (status, retry) = send(
        &app,
        "POST",
        "/auth/refresh",
        "",
        rotation_body,
        Some(&fresh_proof),
    )
    .await;
    assert_eq!(
        status,
        StatusCode::OK,
        "fresh proof must recover a lost rotation response"
    );
    assert_eq!(retry["refresh_token"], body["refresh_token"]);

    // Password-authenticated login and registration establish independent binding.
    let signed_in = login(&state, &app, &username, Some(&other_key)).await;
    assert_binding(&state, &signed_in, &other_key).await;
    let (_, signed_up) = register(&state, &app, Some(&key)).await;
    assert_binding(&state, &signed_up, &key).await;
    assert_eq!(
        send(
            &app,
            "GET",
            "/users/me",
            &signed_up.access,
            Value::Null,
            None
        )
        .await
        .0,
        StatusCode::OK,
        "ordinary HTTP uses the documented short-lived bearer policy"
    );

    // Both clock directions return a retryable challenge, without consuming the session.
    let mut correction = chrono::Utc::now().timestamp();
    for offset in [-120, 120] {
        let proof = key.proof(
            "POST",
            "/auth/refresh",
            signed_up.id,
            &signed_up.refresh,
            chrono::Utc::now().timestamp() + offset,
        );
        let (status, body) = send(
            &app,
            "POST",
            "/auth/refresh",
            "",
            json!({"refresh_token": signed_up.refresh}),
            Some(&proof),
        )
        .await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        assert_eq!(body["error"]["code"], "device_proof_stale");
        let server_time = body["error"]["server_time"]
            .as_i64()
            .expect("clock correction value");
        assert!((server_time - chrono::Utc::now().timestamp()).abs() <= 2);
        correction = server_time;
    }
    let proof = key.proof(
        "POST",
        "/auth/refresh",
        signed_up.id,
        &signed_up.refresh,
        correction,
    );
    let (status, body) = send(
        &app,
        "POST",
        "/auth/refresh",
        "",
        json!({"refresh_token": signed_up.refresh}),
        Some(&proof),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "server clock correction rejected");
    let clock_recovered = Session::from_response(&state, &body);
    assert_binding(&state, &clock_recovered, &key).await;

    // Enrollment cannot reuse its proof for a second concurrent refresh grant.
    let proof = key.refresh_proof(&clock_recovered.refresh);
    let concurrent_body =
        json!({"refresh_token": clock_recovered.refresh, "request_id": Uuid::new_v4()});
    let (a, b) = tokio::join!(
        send(
            &app,
            "POST",
            "/auth/refresh",
            "",
            concurrent_body.clone(),
            Some(&proof)
        ),
        send(
            &app,
            "POST",
            "/auth/refresh",
            "",
            concurrent_body,
            Some(&proof)
        )
    );
    let statuses = [a.0, b.0];
    assert_eq!(statuses.iter().filter(|&&s| s == StatusCode::OK).count(), 1);
    assert_eq!(
        statuses
            .iter()
            .filter(|&&s| s == StatusCode::UNAUTHORIZED)
            .count(),
        1
    );
    assert_terminal(if a.0 == StatusCode::UNAUTHORIZED {
        a
    } else {
        b
    });

    // Account/session lock waits age transaction_timestamp(); replay retention
    // must start at insertion wall time, not at the beginning of the transaction.
    // A short controlled transaction age detects use of SQL now() without a
    // multi-minute lock-wait test. No key, proof or grant is needed for this check.
    let scope = Uuid::new_v4();
    let jti = Uuid::new_v4();
    let mut aging = state.db.begin().await.unwrap();
    sqlx::query("SELECT transaction_timestamp()")
        .execute(&mut *aging)
        .await
        .unwrap();
    tokio::time::sleep(std::time::Duration::from_millis(150)).await;
    messenger_server::auth::device_proof::consume(&mut aging, scope, jti)
        .await
        .unwrap();
    let retained_after_transaction_age: bool = sqlx::query_scalar(
        "SELECT expires_at > transaction_timestamp()+interval '180.1 seconds' \
         FROM device_proof_replays WHERE scope_id=$1 AND jti=$2",
    )
    .bind(scope)
    .bind(jti)
    .fetch_one(&mut *aging)
    .await
    .unwrap();
    assert!(
        retained_after_transaction_age,
        "replay expiry used transaction start instead of wall time"
    );
    aging.rollback().await.unwrap();

    // Real loopback upgrade tests distinguish HTTP rejection from an opened socket.
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let serving = app.clone();
    let server = tokio::spawn(async move {
        axum::serve(
            listener,
            serving.into_make_service_with_connect_info::<SocketAddr>(),
        )
        .await
        .unwrap();
    });
    assert_ws_denied(addr, &signed_in.access, None).await;
    let wrong = key.proof(
        "GET",
        "ws",
        signed_in.id,
        &signed_in.access,
        chrono::Utc::now().timestamp(),
    );
    assert_ws_denied(addr, &signed_in.access, Some(&wrong)).await;
    let stale = other_key.proof(
        "GET",
        "ws",
        signed_in.id,
        &signed_in.access,
        chrono::Utc::now().timestamp() - 120,
    );
    assert_ws_denied(addr, &signed_in.access, Some(&stale)).await;
    let proof = other_key.proof(
        "GET",
        "ws",
        signed_in.id,
        &signed_in.access,
        chrono::Utc::now().timestamp(),
    );
    let mut request = format!("ws://{addr}/ws").into_client_request().unwrap();
    request.headers_mut().insert(
        "authorization",
        format!("Bearer {}", signed_in.access).parse().unwrap(),
    );
    request
        .headers_mut()
        .insert("x-unyri-device-proof", proof.parse().unwrap());
    let (mut socket, response) = tokio_tungstenite::connect_async(request)
        .await
        .expect("genuine device WS upgrade");
    assert_eq!(response.status(), StatusCode::SWITCHING_PROTOCOLS);
    socket.close(None).await.unwrap();
    assert_ws_denied(addr, &signed_in.access, Some(&proof)).await;

    // Owner enforcement requires enrollment, and disabling it cannot downgrade an existing family.
    let unbound = login(&state, &app, &username, None).await;
    sqlx::query("UPDATE server_policy SET device_bound_sessions_required=true WHERE id=true")
        .execute(&state.db)
        .await
        .unwrap();
    let login_body = json!({"username": username, "password": PASSWORD, "installation_id": Uuid::new_v4(), "device_platform": "android"});
    assert_update_required(send(&app, "POST", "/auth/login", "", login_body, None).await);
    let register_body = json!({"username": format!("dbs_{}", &Uuid::new_v4().simple().to_string()[..16]),
        "password": PASSWORD, "display_name": "Device Binding", "installation_id": Uuid::new_v4(), "device_platform": "android"});
    assert_update_required(send(&app, "POST", "/auth/register", "", register_body, None).await);
    // 19 Nov: an unbound phone session is asked to update, never revoked - old apps erase the
    // account locally on session_revoked; the same session still enrolls from a new app below.
    assert_update_required(
        send(
            &app,
            "POST",
            "/auth/refresh",
            "",
            json!({"refresh_token": unbound.refresh}),
            None,
        )
        .await,
    );
    // Desktop and web have no hardware session key yet: required binding does not lock them out.
    let desktop_body = json!({"username": username, "password": PASSWORD, "installation_id": Uuid::new_v4(), "device_platform": "desktop"});
    let (desktop_status, desktop_login) = send(&app, "POST", "/auth/login", "", desktop_body, None).await;
    assert_eq!(desktop_status, StatusCode::OK, "desktop sign-in stays allowed under required binding");
    let desktop = Session::from_response(&state, &desktop_login);
    let (desktop_refresh, _) = send(&app, "POST", "/auth/refresh", "", json!({"refresh_token": desktop.refresh}), None).await;
    assert_eq!(desktop_refresh, StatusCode::OK, "desktop refresh stays allowed under required binding");
    assert_ws_denied(addr, &unbound.access, None).await;
    let proof = key.refresh_proof(&unbound.refresh);
    let (status, body) = send(
        &app,
        "POST",
        "/auth/refresh",
        "",
        json!({"refresh_token": unbound.refresh, "device_key": key.registration()}),
        Some(&proof),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let enrolled_when_required = Session::from_response(&state, &body);
    assert_binding(&state, &enrolled_when_required, &key).await;
    sqlx::query("UPDATE server_policy SET device_bound_sessions_required=false WHERE id=true")
        .execute(&state.db)
        .await
        .unwrap();
    assert_terminal(
        send(
            &app,
            "POST",
            "/auth/refresh",
            "",
            json!({"refresh_token": enrolled_when_required.refresh}),
            None,
        )
        .await,
    );
    let proof = key.refresh_proof(&enrolled_when_required.refresh);
    assert_eq!(
        send(
            &app,
            "POST",
            "/auth/refresh",
            "",
            json!({"refresh_token": enrolled_when_required.refresh}),
            Some(&proof)
        )
        .await
        .0,
        StatusCode::OK
    );
    server.abort();
    let _ = server.await;
}

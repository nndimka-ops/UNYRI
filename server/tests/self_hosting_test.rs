//! SH-2 contract suite. Run ALONE against a fresh, disposable sh2_* database.
//! No dotenv loading, real-server access, push credentials or external probes.
use axum::{
    body::Body,
    extract::ConnectInfo,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use messenger_server::{
    config::{Config, S3Config, TurnConfig},
    state::AppState,
};
use serde_json::{json, Value};
use std::{net::SocketAddr, sync::Arc};
use tower::ServiceExt;
use uuid::Uuid;

#[derive(Clone)]
struct LogBuffer(Arc<std::sync::Mutex<Vec<u8>>>);
impl std::io::Write for LogBuffer {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        self.0.lock().unwrap().extend_from_slice(bytes);
        Ok(bytes.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

fn local_url(key: &str) -> String {
    let value = std::env::var(key)
        .unwrap_or_else(|_| panic!("{key} is required; use a local disposable fixture"));
    let url = reqwest::Url::parse(&value).unwrap();
    assert!(
        matches!(url.host_str(), Some("localhost" | "127.0.0.1" | "[::1]")),
        "fixture must use loopback"
    );
    if key == "TEST_DATABASE_URL" {
        assert!(
            url.path().starts_with("/sh2_"),
            "database must be a fresh sh2_* database"
        );
    }
    value
}

async fn fixture() -> (AppState, tokio::task::JoinHandle<()>) {
    assert_eq!(std::env::var("SELFHOST_SH2_TESTS").as_deref(), Ok("1"));
    let database_url = local_url("TEST_DATABASE_URL");
    let redis_url = local_url("TEST_REDIS_URL");
    std::env::set_var("COCO_ADMIN_KEY", "11".repeat(32));
    let db = sqlx::postgres::PgPoolOptions::new()
        .max_connections(10)
        .connect(&database_url)
        .await
        .unwrap();
    sqlx::migrate!().run(&db).await.unwrap();
    let owners: i64 = sqlx::query_scalar("SELECT count(*) FROM server_administrators")
        .fetch_one(&db)
        .await
        .unwrap();
    assert_eq!(owners, 0, "suite requires a fresh database");
    let redis = redis::Client::open(redis_url.clone())
        .unwrap()
        .get_connection_manager()
        .await
        .unwrap();
    // Controlled loopback S3 HEAD fixture, no MinIO setup or writes needed.
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let endpoint = format!("http://{}", listener.local_addr().unwrap());
    let storage = tokio::spawn(async move {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        loop {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut buf = [0; 8192];
            let n = stream.read(&mut buf).await.unwrap();
            assert!(
                std::str::from_utf8(&buf[..n])
                    .unwrap()
                    .starts_with("HEAD /sh2-fixture?"),
                "self-check must be read-only"
            );
            stream
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .await
                .unwrap();
        }
    });
    let config = Config {
        identity: Default::default(),
        host: "127.0.0.1".into(),
        port: 0,
        database_url,
        database_max_connections: 10,
        redis_url,
        jwt_secret: "sh2-test-only-secret-not-for-production".into(),
        trusted_proxies: vec![],
        jwt_issuer: "sh2-fixture".into(),
        jwt_audience: "sh2-fixture-apps".into(),
        access_token_ttl_seconds: 900,
        refresh_token_ttl_seconds: 2592000,
        s3: S3Config {
            max_upload_duration_seconds: 60,
            upload_access_key_id: "write-only".into(),
            upload_secret_access_key: "test-only".into(),
            endpoint_url: endpoint.clone(),
            public_endpoint_url: endpoint,
            region: "us-east-1".into(),
            bucket: "sh2-fixture".into(),
            access_key_id: "fixture".into(),
            secret_access_key: "fixture-secret".into(),
            presign_ttl_seconds: 60,
        },
        turn: TurnConfig {
            stun_urls: vec![],
            turn_urls: vec!["turn:example.test:3478?transport=udp".into()],
            shared_secret: "fixture-secret".into(),
            credential_ttl_seconds: 1200,
        },
        fcm: Default::default(),
    };
    let s3 = Arc::new(messenger_server::storage::S3Client::new(config.s3.clone()));
    (
        AppState {
            db,
            redis,
            config,
            s3,
            resources: Default::default(),
            ws_registry: Default::default(),
            push: messenger_server::push::disabled(),
        },
        storage,
    )
}

fn request(
    method: &str,
    path: &str,
    token: Option<&str>,
    admin: Option<&str>,
    body: Option<Value>,
    ip: Option<SocketAddr>,
) -> Request<Body> {
    let mut r = Request::builder()
        .method(method)
        .uri(path)
        .extension(ConnectInfo(ip.unwrap_or_else(|| {
            let o: [u8; 3] = std::array::from_fn(|_| rand::random());
            SocketAddr::from(([10, o[0], o[1], o[2]], 12345))
        })));
    if let Some(token) = token {
        r = r.header("authorization", format!("Bearer {token}"));
    }
    if let Some(token) = admin {
        r = r.header("x-coco-admin", token);
    }
    if let Some(body) = body {
        r.header("content-type", "application/json")
            .body(Body::from(body.to_string()))
            .unwrap()
    } else {
        r.body(Body::empty()).unwrap()
    }
}
async fn call(
    app: &axum::Router,
    method: &str,
    path: &str,
    token: Option<&str>,
    admin: Option<&str>,
    body: Option<Value>,
) -> (StatusCode, Value) {
    response(
        app.clone()
            .oneshot(request(method, path, token, admin, body, None))
            .await
            .unwrap(),
    )
    .await
}
async fn response(r: axum::response::Response) -> (StatusCode, Value) {
    let status = r.status();
    let bytes = r.into_body().collect().await.unwrap().to_bytes();
    (
        status,
        serde_json::from_slice(&bytes)
            .unwrap_or_else(|_| Value::String(String::from_utf8_lossy(&bytes).into_owned())),
    )
}
fn signup(invite: Option<&str>) -> Value {
    json!({"username":format!("sh{}",&Uuid::new_v4().simple().to_string()[..16]),"password":"correct-horse-battery",
        "display_name":"Fixture User","installation_id":Uuid::new_v4(),"device_platform":"android","invite_code":invite})
}
fn token(account: &Value) -> &str {
    account["access_token"].as_str().unwrap()
}
fn user(account: &Value) -> Uuid {
    Uuid::parse_str(account["user"]["id"].as_str().unwrap()).unwrap()
}

async fn unlock(app: &axum::Router, account: &Value) -> String {
    use base64::Engine;
    use ring::signature::KeyPair;
    let (s, setup) = call(
        app,
        "POST",
        "/admin/setup",
        Some(token(account)),
        None,
        Some(json!({"password":"correct-horse-battery","code":"000000"})),
    )
    .await;
    assert_eq!(s, StatusCode::OK, "{setup}");
    let secret = totp_rs::Secret::try_from_base32(setup["secret"].as_str().unwrap()).unwrap();
    let otp = totp_rs::Builder::new().with_secret(secret).build().unwrap();
    let key = ring::signature::Ed25519KeyPair::from_seed_unchecked(
        &[user(account).as_bytes()[0].max(1); 32],
    )
    .unwrap();
    let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD;
    let public = b64.encode(key.public_key().as_ref());
    let (s, data) = call(
        app,
        "POST",
        "/admin/key-challenge",
        Some(token(account)),
        None,
        Some(json!({"purpose":"unlock","public_key":public})),
    )
    .await;
    assert_eq!(s, StatusCode::OK);
    let proof = format!(
        "COCO-ADMIN-PROOF-V1\n{}\n{}\n{}\n{}\nunlock\n{}\n{}",
        data["server_id"].as_str().unwrap(),
        data["user_id"].as_str().unwrap(),
        data["auth_session"].as_str().unwrap(),
        data["challenge_id"].as_str().unwrap(),
        public,
        data["nonce"].as_str().unwrap()
    );
    let (s,data)=call(app,"POST","/admin/unlock",Some(token(account)),None,Some(json!({"password":"correct-horse-battery","code":otp.generate_current().to_string(),"key_proof":{"challenge_id":data["challenge_id"],"signature":b64.encode(key.sign(proof.as_bytes()).as_ref())}}))).await;
    assert_eq!(s, StatusCode::OK, "{data}");
    data["token"].as_str().unwrap().to_owned()
}

async fn invite(app: &axum::Router, account: &Value, admin: &str, body: Value) -> Value {
    let (s, v) = call(
        app,
        "POST",
        "/admin/invites",
        Some(token(account)),
        Some(admin),
        Some(body),
    )
    .await;
    assert_eq!(s, StatusCode::OK, "{v}");
    v
}
async fn mode(app: &axum::Router, account: &Value, admin: &str, mode: &str) {
    assert_eq!(
        call(
            app,
            "POST",
            "/admin/registration",
            Some(token(account)),
            Some(admin),
            Some(json!({"registration_mode":mode}))
        )
        .await
        .0,
        StatusCode::OK
    );
}

#[tokio::test]
async fn self_hosting_contract_on_fresh_local_database() {
    use tracing::instrument::WithSubscriber;
    let (state, storage_task) = fixture().await;
    let app = messenger_server::app(state.clone());
    let dir = std::env::temp_dir().join(format!("sh2-integration-{}", Uuid::new_v4()));
    let file = dir.join("setup-code");
    std::env::set_var("COCO_SETUP_CODE_FILE", &file);
    std::env::set_var("COCO_DATA_DIR", &dir);
    let captured = Arc::new(std::sync::Mutex::new(Vec::new()));
    let writer = LogBuffer(captured.clone());
    let subscriber = tracing::Dispatch::new(
        tracing_subscriber::fmt()
            .with_ansi(false)
            .without_time()
            .with_writer(move || writer.clone())
            .finish(),
    );
    messenger_server::self_hosting::initialize(&state.db, &file)
        .with_subscriber(subscriber.clone())
        .await
        .unwrap();
    let code = std::fs::read_to_string(&file).unwrap().trim().to_owned();
    let hash: Vec<u8> = sqlx::query_scalar("SELECT code_hash FROM server_bootstrap")
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(hash.len(), 32);
    assert_ne!(hash, code.as_bytes());
    messenger_server::self_hosting::initialize(&state.db, &file)
        .with_subscriber(subscriber.clone())
        .await
        .unwrap();
    assert_eq!(
        std::fs::read_to_string(&file).unwrap().trim(),
        code,
        "restart keeps the same code"
    );
    std::fs::remove_file(&file).unwrap();
    messenger_server::self_hosting::initialize(&state.db, &file)
        .with_subscriber(subscriber)
        .await
        .unwrap();
    let rotated = std::fs::read_to_string(&file).unwrap().trim().to_owned();
    assert_ne!(
        code, rotated,
        "lost file rotates and invalidates the old code"
    );
    let logs = String::from_utf8(captured.lock().unwrap().clone()).unwrap();
    assert_eq!(
        logs.matches("FIRST OWNER SETUP CODE:").count(),
        2,
        "one log per generated code, none for a normal restart"
    );
    assert_eq!(logs.matches(&code).count(), 1);
    assert_eq!(logs.matches(&rotated).count(), 1);
    assert!(
        messenger_server::self_hosting::initialize(&state.db, &file.join("impossible"))
            .await
            .is_err(),
        "file delivery failure must fail startup"
    );

    // Open is backward compatible; supplied bad invites are ignored.
    let (s, first) = call(
        &app,
        "POST",
        "/auth/register",
        None,
        None,
        Some(signup(Some("ignored"))),
    )
    .await;
    assert_eq!(s, StatusCode::OK);
    let (s, second) = call(
        &app,
        "POST",
        "/auth/register",
        None,
        None,
        Some(signup(None)),
    )
    .await;
    assert_eq!(s, StatusCode::OK);
    let (s, third) = call(
        &app,
        "POST",
        "/auth/register",
        None,
        None,
        Some(signup(None)),
    )
    .await;
    assert_eq!(s, StatusCode::OK);
    assert_eq!(
        call(&app, "GET", "/auth/owner-setup", None, None, None)
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        call(
            &app,
            "GET",
            "/auth/owner-setup",
            Some(token(&first)),
            None,
            None
        )
        .await
        .1["available"],
        true
    );
    assert_eq!(
        call(
            &app,
            "POST",
            "/auth/owner-setup",
            Some(token(&first)),
            None,
            Some(json!({"code":code}))
        )
        .await
        .1["error"]["code"],
        "setup_invalid"
    );
    // Account limit holds across rotating IPs.
    for _ in 0..4 {
        assert_eq!(
            call(
                &app,
                "POST",
                "/auth/owner-setup",
                Some(token(&first)),
                None,
                Some(json!({"code":"wrong"}))
            )
            .await
            .0,
            StatusCode::FORBIDDEN
        );
    }
    assert_eq!(
        call(
            &app,
            "POST",
            "/auth/owner-setup",
            Some(token(&first)),
            None,
            Some(json!({"code":rotated}))
        )
        .await
        .0,
        StatusCode::TOO_MANY_REQUESTS
    );
    // IP ceiling holds across accounts (independent account counters).
    let ip: SocketAddr = "203.0.113.42:12345".parse().unwrap();
    let mut conn = state.redis.clone();
    let _: () = redis::cmd("SET")
        .arg("ratelimit:owner-claim:203.0.113.42")
        .arg(5)
        .arg("EX")
        .arg(900)
        .query_async(&mut conn)
        .await
        .unwrap();
    let r = app
        .clone()
        .oneshot(request(
            "POST",
            "/auth/owner-setup",
            Some(token(&second)),
            None,
            Some(json!({"code":rotated})),
            Some(ip),
        ))
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::TOO_MANY_REQUESTS);
    // Fresh servers are called UNYRI (0084), and the installer's name applies
    // only while the server is unclaimed.
    assert_eq!(
        call(&app, "GET", "/server-info", None, None, None).await.1["name"],
        "UNYRI"
    );
    assert!(!messenger_server::self_hosting::seed_server_name(&state.db, Some("bad\nname"))
        .await
        .unwrap());
    assert!(messenger_server::self_hosting::seed_server_name(&state.db, Some("  Family server "))
        .await
        .unwrap());
    assert!(!messenger_server::self_hosting::seed_server_name(&state.db, Some("Family server"))
        .await
        .unwrap(), "an unchanged name is not rewritten");
    assert_eq!(
        call(&app, "GET", "/server-info", None, None, None).await.1["name"],
        "Family server"
    );
    // Two valid concurrent claims yield exactly one owner.
    let (a, b) = tokio::join!(
        call(
            &app,
            "POST",
            "/auth/owner-setup",
            Some(token(&second)),
            None,
            Some(json!({"code":rotated}))
        ),
        call(
            &app,
            "POST",
            "/auth/owner-setup",
            Some(token(&third)),
            None,
            Some(json!({"code":rotated}))
        )
    );
    assert_eq!(
        [a.0, b.0].iter().filter(|s| **s == StatusCode::OK).count(),
        1
    );
    assert!([a.1["error"]["code"].clone(), b.1["error"]["code"].clone()]
        .contains(&json!("setup_not_available")));
    let owner = if a.0 == StatusCode::OK {
        second.clone()
    } else {
        third.clone()
    };
    let moderator = if a.0 == StatusCode::OK {
        third.clone()
    } else {
        second.clone()
    };
    assert!(!file.exists());
    let retired: (bool, Option<Vec<u8>>) =
        sqlx::query_as("SELECT completed,code_hash FROM server_bootstrap")
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(retired, (true, None));
    assert!(
        !messenger_server::self_hosting::seed_server_name(&state.db, Some("Installer rerun"))
            .await
            .unwrap(),
        "after the first owner the console owns the name"
    );
    assert_eq!(
        call(&app, "GET", "/server-info", None, None, None).await.1["name"],
        "Family server"
    );
    assert_eq!(
        call(
            &app,
            "POST",
            "/auth/owner-setup",
            Some(token(&first)),
            None,
            Some(json!({"code":"wrong"}))
        )
        .await
        .1["error"]["code"],
        "setup_not_available",
        "availability wins over previous throttling"
    );
    let owner_admin = unlock(&app, &owner).await;
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'moderator')")
        .bind(user(&moderator))
        .execute(&state.db)
        .await
        .unwrap();
    let mod_admin = unlock(&app, &moderator).await;

    // All new admin APIs require unlocked authority; moderator may only manage invites.
    for route in ["/admin/invites", "/admin/registration", "/admin/self-check"] {
        assert_eq!(
            call(&app, "GET", route, Some(token(&first)), None, None)
                .await
                .0,
            StatusCode::NOT_FOUND
        );
    }
    assert_eq!(
        call(
            &app,
            "GET",
            "/admin/self-check",
            Some(token(&moderator)),
            Some(&mod_admin),
            None
        )
        .await
        .0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        call(
            &app,
            "POST",
            "/admin/registration",
            Some(token(&moderator)),
            Some(&mod_admin),
            Some(json!({"registration_mode":"invite_only"}))
        )
        .await
        .0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        call(&app, "GET", "/server-info", None, None, None).await.1["invite_required"],
        false
    );
    assert_eq!(
        call(
            &app,
            "POST",
            "/admin/registration",
            Some(token(&owner)),
            Some(&owner_admin),
            Some(json!({"registration_mode":"bad"}))
        )
        .await
        .0,
        StatusCode::UNPROCESSABLE_ENTITY
    );
    mode(&app, &owner, &owner_admin, "invite_only").await;
    // Independent invitation IP/account limits refuse before password hashing.
    let _: () = redis::cmd("SET")
        .arg("ratelimit:invite-register:203.0.113.43")
        .arg(10)
        .arg("EX")
        .arg(900)
        .query_async(&mut conn)
        .await
        .unwrap();
    let r = app
        .clone()
        .oneshot(request(
            "POST",
            "/auth/register",
            None,
            None,
            Some(signup(None)),
            Some("203.0.113.43:12345".parse().unwrap()),
        ))
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::TOO_MANY_REQUESTS);
    let limited = signup(None);
    let account_key = format!(
        "ratelimit:invite-register-account:{}",
        messenger_server::admin::hash(&limited["username"].as_str().unwrap().to_lowercase())
    );
    let _: () = redis::cmd("SET")
        .arg(&account_key)
        .arg(5)
        .arg("EX")
        .arg(900)
        .query_async(&mut conn)
        .await
        .unwrap();
    assert_eq!(
        call(&app, "POST", "/auth/register", None, None, Some(limited))
            .await
            .0,
        StatusCode::TOO_MANY_REQUESTS
    );
    let public = call(&app, "GET", "/server-info", None, None, None).await.1;
    assert_eq!(public["invite_required"], true);
    assert_eq!(public["registration_mode"], "invite_only");
    assert_eq!(
        call(
            &app,
            "GET",
            "/admin/registration",
            Some(token(&owner)),
            Some(&owner_admin),
            None
        )
        .await
        .1["registration_mode"],
        "invite_only"
    );
    for (code, error) in [(None, "invite_required"), (Some("bad"), "invite_invalid")] {
        assert_eq!(
            call(
                &app,
                "POST",
                "/auth/register",
                None,
                None,
                Some(signup(code))
            )
            .await
            .1["error"]["code"],
            error
        );
    }
    let default = invite(&app, &moderator, &mod_admin, json!({})).await;
    assert_eq!(default["max_uses"], 1);
    let expires =
        chrono::DateTime::parse_from_rfc3339(default["expires_at"].as_str().unwrap()).unwrap();
    assert!((expires.timestamp() - chrono::Utc::now().timestamp() - 604800).abs() < 5);
    let code = default["code"].as_str().unwrap();
    let typo = format!("{}.AAAA-AAAA-AAAA-AAAA", default["id"].as_str().unwrap());
    assert_eq!(
        call(
            &app,
            "POST",
            "/auth/register",
            None,
            None,
            Some(signup(Some(&typo)))
        )
        .await
        .1["error"]["code"],
        "invite_invalid"
    );
    // A later username conflict must roll back the reserved invite use.
    let mut duplicate = signup(Some(code));
    duplicate["username"] = owner["user"]["username"].clone();
    assert_eq!(
        call(&app, "POST", "/auth/register", None, None, Some(duplicate))
            .await
            .0,
        StatusCode::CONFLICT
    );
    let uses: i32 = sqlx::query_scalar("SELECT uses FROM registration_invites WHERE id=$1")
        .bind(Uuid::parse_str(default["id"].as_str().unwrap()).unwrap())
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(uses, 0);
    let (a, b) = tokio::join!(
        call(
            &app,
            "POST",
            "/auth/register",
            None,
            None,
            Some(signup(Some(code)))
        ),
        call(
            &app,
            "POST",
            "/auth/register",
            None,
            None,
            Some(signup(Some(code)))
        )
    );
    assert_eq!(
        [a.0, b.0].iter().filter(|s| **s == StatusCode::OK).count(),
        1
    );
    assert!([a.1["error"]["code"].clone(), b.1["error"]["code"].clone()]
        .contains(&json!("invite_invalid")));
    // Multi-use, revoke, expiry and invalid configuration.
    let multi = invite(
        &app,
        &owner,
        &owner_admin,
        json!({"max_uses":2,"expires_in_seconds":3600}),
    )
    .await;
    for _ in 0..2 {
        assert_eq!(
            call(
                &app,
                "POST",
                "/auth/register",
                None,
                None,
                Some(signup(multi["code"].as_str()))
            )
            .await
            .0,
            StatusCode::OK
        );
    }
    assert_eq!(
        call(
            &app,
            "POST",
            "/auth/register",
            None,
            None,
            Some(signup(multi["code"].as_str()))
        )
        .await
        .1["error"]["code"],
        "invite_invalid"
    );
    let revoked = invite(&app, &owner, &owner_admin, json!({})).await;
    let route = format!("/admin/invites/{}/revoke", revoked["id"].as_str().unwrap());
    assert_eq!(
        call(
            &app,
            "POST",
            &route,
            Some(token(&moderator)),
            Some(&mod_admin),
            Some(json!({}))
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        call(
            &app,
            "POST",
            "/auth/register",
            None,
            None,
            Some(signup(revoked["code"].as_str()))
        )
        .await
        .1["error"]["code"],
        "invite_invalid"
    );
    let expired = invite(&app, &owner, &owner_admin, json!({})).await;
    sqlx::query("UPDATE registration_invites SET expires_at=now()-interval '1 second' WHERE id=$1")
        .bind(Uuid::parse_str(expired["id"].as_str().unwrap()).unwrap())
        .execute(&state.db)
        .await
        .unwrap();
    assert_eq!(
        call(
            &app,
            "POST",
            "/auth/register",
            None,
            None,
            Some(signup(expired["code"].as_str()))
        )
        .await
        .1["error"]["code"],
        "invite_invalid"
    );
    assert_eq!(
        call(
            &app,
            "POST",
            "/admin/invites",
            Some(token(&owner)),
            Some(&owner_admin),
            Some(json!({"max_uses":0}))
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
    let list = call(
        &app,
        "GET",
        "/admin/invites",
        Some(token(&moderator)),
        Some(&mod_admin),
        None,
    )
    .await
    .1;
    assert!(list["items"]
        .as_array()
        .unwrap()
        .iter()
        .any(|i| i["status"] == "exhausted"
            && i["uses"] == 1
            && i["created_by"] == user(&moderator).to_string()));
    for status in ["revoked", "expired"] {
        assert!(list["items"]
            .as_array()
            .unwrap()
            .iter()
            .any(|i| i["status"] == status));
    }
    assert!(!list.to_string().contains("code_hash"));
    assert!(!list.to_string().contains(code));
    assert_eq!(
        list["items"][0]["id"], expired["id"],
        "newest codes are listed first"
    );
    let older = call(
        &app,
        "GET",
        &format!("/admin/invites?before={}", multi["id"].as_str().unwrap()),
        Some(token(&owner)),
        Some(&owner_admin),
        None,
    )
    .await
    .1;
    assert_eq!(older["items"].as_array().unwrap().len(), 1);
    assert_eq!(older["items"][0]["id"], default["id"]);
    // Registration closure remains an independent emergency control.
    sqlx::query("UPDATE server_policy SET registration_open=false")
        .execute(&state.db)
        .await
        .unwrap();
    assert_eq!(
        call(
            &app,
            "POST",
            "/auth/register",
            None,
            None,
            Some(signup(None))
        )
        .await
        .0,
        StatusCode::CONFLICT
    );
    sqlx::query("UPDATE server_policy SET registration_open=true")
        .execute(&state.db)
        .await
        .unwrap();
    mode(&app, &owner, &owner_admin, "open").await;
    assert_eq!(
        call(
            &app,
            "POST",
            "/auth/register",
            None,
            None,
            Some(signup(None))
        )
        .await
        .0,
        StatusCode::OK
    );

    // A self-check probes only the configured storage fixture; no external call.
    for key in ["CONFERENCE_LIVEKIT_URL", "CONFERENCE_LIVEKIT_API_URL", "CONFERENCE_LIVEKIT_API_KEY", "CONFERENCE_LIVEKIT_API_SECRET"] {
        std::env::remove_var(key);
    }
    std::env::set_var("PUBLIC_API_ORIGIN", "https://sh2.example.test");
    std::env::set_var("TURN_EXTERNAL_IP", "203.0.113.9");
    std::env::set_var("TURN_MIN_PORT", "49152");
    std::env::set_var("TURN_MAX_PORT", "49200");
    std::env::set_var("TURN_RELAY_PROTOCOL", "udp");
    std::env::set_var("UNYRI_LAN_ADDRESS", "192.168.50.10");
    std::env::set_var("UNYRI_LAN_PORT", "443");
    std::env::set_var("UNYRI_LAN_DETECTED_AT", "2026-11-18T12:00:00Z");
    std::env::set_var("UNYRI_HOSTING_KIND", "home_pc");
    std::env::set_var("UNYRI_INGRESS_MODE", "direct");
    std::env::remove_var("UNYRI_TUNNEL_PROVIDER");
    std::env::remove_var("UNYRI_SUBNET");
    std::env::set_var("UNYRI_OFFLINE", "true");
    let (s, check) = call(
        &app,
        "GET",
        "/admin/self-check",
        Some(token(&owner)),
        Some(&owner_admin),
        None,
    )
    .await;
    assert_eq!(s, StatusCode::OK);
    assert_eq!(check["storage"]["reachable"], true);
    assert_eq!(check["setup"]["available"], false);
    // Compare with the applied level instead of a literal so new migrations do not break this.
    let latest: i64 = sqlx::query_scalar("SELECT max(version) FROM _sqlx_migrations WHERE success")
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert!(latest >= 84);
    assert_eq!(check["server"]["migration_level"], latest);
    assert_eq!(check["public_url"]["configured"], true);
    assert_eq!(check["public_url"]["tls"], "not_probed");
    assert_eq!(check["turn"]["secret_set"], false);
    assert_eq!(check["turn"]["external_ip_set"], true);
    assert_eq!(check["disk"]["status"], "ok");
    assert_eq!(check["push"]["offline"], true);
    assert!(!check.to_string().contains(&state.config.turn.shared_secret));
    let expected_addresses = json!({"schema":1,"public_origin":"https://sh2.example.test",
        "public_host":"sh2.example.test","public_port":443,
        "external_ip":{"address":"203.0.113.9","source":"installer_config","observed_at":null,"state":"unverified"},
        "lan":{"address":"192.168.50.10","port":443,"source":"default_route","observed_at":"2026-11-18T12:00:00Z","state":"detected"},
        "calls":{"turn_port":3478,"transports":["udp"],"relay_min":49152,"relay_max":49200,"relay_protocol":"udp"},
        "rooms":{"enabled":false},"ingress":{"mode":"direct","provider":null},"hosting":"home_pc"});
    assert_eq!(check["addresses"], expected_addresses);
    // Repeat direct requests with metadata present: denied bodies disclose none
    // of it, even when a user supplies somebody else's unlocked admin token.
    for (account, authority) in [(&first, None), (&first, Some(owner_admin.as_str())),
        (&moderator, Some(mod_admin.as_str()))] {
        let (s, denied) = call(&app, "GET", "/admin/self-check", Some(token(account)), authority, None).await;
        assert_eq!(s, StatusCode::NOT_FOUND);
        assert!(denied.get("addresses").is_none());
        assert!(!denied.to_string().contains("192.168.50.10"));
        assert!(!denied.to_string().contains("203.0.113.9"));
    }
    let public_info = call(&app, "GET", "/server-info", None, None, None).await.1;
    assert!(public_info.get("addresses").is_none());
    assert!(!public_info.to_string().contains("192.168.50.10"));
    // The database knows only 'owner' and 'moderator' (migration 0023); the self-check guard's
    // 'admin' branch has no row to test, so only the owner path is asserted above.
    storage_task.abort();
    let (s, check) = call(
        &app,
        "GET",
        "/admin/self-check",
        Some(token(&owner)),
        Some(&owner_admin),
        None,
    )
    .await;
    assert_eq!(s, StatusCode::OK);
    assert_eq!(
        check["storage"]["reachable"], false,
        "outage is a field, not a failed self-check"
    );

    // Existing console authority and CSRF rules apply to invite mutations.
    let (s, handoff) = call(
        &app,
        "POST",
        "/admin/console-handoff",
        Some(token(&owner)),
        Some(&owner_admin),
        Some(json!({})),
    )
    .await;
    assert_eq!(s, StatusCode::OK);
    let handoff = handoff["handoff"].as_str().unwrap();
    let redeemed = app
        .clone()
        .oneshot(request(
            "POST",
            "/administration/handoff",
            None,
            None,
            Some(json!({"handoff":handoff})),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(redeemed.status(), StatusCode::OK);
    let cookie = redeemed
        .headers()
        .get_all("set-cookie")
        .iter()
        .map(|h| h.to_str().unwrap().split(';').next().unwrap().to_owned())
        .collect::<Vec<_>>()
        .join("; ");
    let (_, session) = response(redeemed).await;
    let browser = || {
        Request::builder()
            .method("POST")
            .uri("/admin/invites")
            .header("content-type", "application/json")
            .header("host", "localhost")
            .header("origin", "http://localhost")
            .header("cookie", &cookie)
    };
    let r = app
        .clone()
        .oneshot(browser().body(Body::from("{}")).unwrap())
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::NOT_FOUND);
    let r = app
        .clone()
        .oneshot(
            browser()
                .header("x-coco-csrf", session["csrf"].as_str().unwrap())
                .body(Body::from("{}"))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::OK);
    let asset = app
        .clone()
        .oneshot(request(
            "GET",
            "/administration/assets/app.js",
            None,
            None,
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(asset.status(), StatusCode::OK);
    let script = String::from_utf8(
        asset
            .into_body()
            .collect()
            .await
            .unwrap()
            .to_bytes()
            .to_vec(),
    )
    .unwrap();
    assert!(script.contains("SECTIONS.selfhosting"));
    assert!(script.contains("invite_code="));

    let events: Vec<String> = sqlx::query_scalar("SELECT action FROM admin_audit")
        .fetch_all(&state.db)
        .await
        .unwrap();
    for action in [
        "setup:claim-owner",
        "setup:claim-invalid",
        "registration:mode",
        "invite:create",
        "invite:revoke",
        "invite:register",
        "invite:register-refused",
    ] {
        assert!(events.iter().any(|a| a == action), "missing audit {action}");
    }
    // Even deleting every role cannot reopen bootstrap. Re-granting through the
    // CLI's SQL path also permanently retires an unclaimed bootstrap state.
    sqlx::query("DELETE FROM server_administrators")
        .execute(&state.db)
        .await
        .unwrap();
    messenger_server::self_hosting::initialize(&state.db, &file)
        .await
        .unwrap();
    assert!(!file.exists());
    sqlx::query(
        "UPDATE server_bootstrap SET completed=false,code_hash=decode(repeat('ab',32),'hex')",
    )
    .execute(&state.db)
    .await
    .unwrap();
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'owner')")
        .bind(user(&first))
        .execute(&state.db)
        .await
        .unwrap();
    let retired: (bool, Option<Vec<u8>>) =
        sqlx::query_as("SELECT completed,code_hash FROM server_bootstrap")
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(retired, (true, None));
    std::fs::remove_dir(dir).unwrap();
}

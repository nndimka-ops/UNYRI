//! Local Docker services only. Never use production/QA URLs or flush shared Redis.
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
use sqlx::{migrate::Migrator, Executor};
use std::{borrow::Cow, net::SocketAddr};
use tower::ServiceExt;
use uuid::Uuid;

const ROOT: &str = "postgres://coco:test@127.0.0.1:55432/";
static INIT: tokio::sync::OnceCell<()> = tokio::sync::OnceCell::const_new();

fn guard() {
    assert_eq!(
        std::env::var("COCO_LOGIN2_TEST_ISOLATED").as_deref(),
        Ok("1")
    );
    assert_eq!(
        std::env::var("TEST_DATABASE_URL").as_deref(),
        Ok("postgres://coco:test@127.0.0.1:55432/coco_login2_review_test")
    );
    assert_eq!(
        std::env::var("TEST_REDIS_URL").as_deref(),
        Ok("redis://127.0.0.1:56379/11")
    );
}
async fn create_database(name: &str) {
    guard();
    assert!(
        name.starts_with("coco_login2_")
            && name
                .bytes()
                .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'_')
    );
    let db = sqlx::PgPool::connect(&format!("{ROOT}postgres"))
        .await
        .unwrap();
    let exists: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM pg_database WHERE datname=$1)")
            .bind(name)
            .fetch_one(&db)
            .await
            .unwrap();
    if !exists {
        db.execute(format!("CREATE DATABASE {name}").as_str())
            .await
            .unwrap();
    }
    db.close().await;
}
async fn spawn_state() -> AppState {
    guard();
    INIT.get_or_init(|| async {
        create_database("coco_login2_review_test").await;
    })
    .await;
    let database_url = std::env::var("TEST_DATABASE_URL").unwrap();
    let redis_url = std::env::var("TEST_REDIS_URL").unwrap();
    let db = sqlx::postgres::PgPoolOptions::new()
        .max_connections(5)
        .connect(&database_url)
        .await
        .unwrap();
    sqlx::migrate!().run(&db).await.expect("run migrations");
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
        database_max_connections: 5,
        redis_url,
        jwt_secret: "identity-test-only-secret".into(),
        trusted_proxies: vec![],
        jwt_issuer: "identity-test".into(),
        jwt_audience: "identity-clients".into(),
        access_token_ttl_seconds: 900,
        refresh_token_ttl_seconds: 2_592_000,
        s3: S3Config {
            endpoint_url: "http://127.0.0.1:59000".into(),
            public_endpoint_url: "http://127.0.0.1:59000".into(),
            region: "us-east-1".into(),
            bucket: "messenger-attachments-test".into(),
            access_key_id: "messenger_minio".into(),
            secret_access_key: "messenger_minio_secret".into(),
            presign_ttl_seconds: 600,
            max_upload_duration_seconds: 0,
            upload_access_key_id: String::new(),
            upload_secret_access_key: String::new(),
        },
        turn: TurnConfig {
            stun_urls: vec![],
            turn_urls: vec![],
            shared_secret: "test".into(),
            credential_ttl_seconds: 3600,
        },
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
fn ip() -> ConnectInfo<SocketAddr> {
    ConnectInfo(SocketAddr::from((
        [10, rand::random(), rand::random(), rand::random()],
        12345,
    )))
}
fn handle() -> String {
    format!("n{}", Uuid::new_v4().simple())[..32].into()
}
struct Account {
    token: String,
    id: Uuid,
    device: Uuid,
    login: String,
}
async fn body(response: axum::response::Response) -> Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes)
        .unwrap_or_else(|_| panic!("non-JSON response: {}", String::from_utf8_lossy(&bytes)))
}
fn request(
    method: &str,
    path: &str,
    ip: ConnectInfo<SocketAddr>,
    token: Option<&str>,
    payload: Option<Value>,
    admin: Option<&str>,
) -> Request<Body> {
    let mut builder = Request::builder().method(method).uri(path).extension(ip);
    if let Some(token) = token {
        builder = builder.header("authorization", format!("Bearer {token}"));
    }
    if let Some(admin) = admin {
        builder = builder.header("x-coco-admin", admin);
    }
    builder
        .header("content-type", "application/json")
        .body(
            payload
                .map(|p| Body::from(p.to_string()))
                .unwrap_or_else(Body::empty),
        )
        .unwrap()
}
async fn call(
    app: &axum::Router,
    actor: &Account,
    method: &str,
    path: &str,
    payload: Option<Value>,
) -> (StatusCode, Value) {
    let response = app
        .clone()
        .oneshot(request(
            method,
            path,
            ip(),
            Some(&actor.token),
            payload,
            None,
        ))
        .await
        .unwrap();
    let status = response.status();
    (status, body(response).await)
}
async fn register(app: &axum::Router) -> Account {
    let login = handle();
    let response=app.clone().oneshot(request("POST","/auth/register",ip(),None,Some(json!({"username":login,"password":"correct-horse-battery","display_name":"Identity User","installation_id":Uuid::new_v4(),"device_platform":"android"})),None)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let value = body(response).await;
    Account {
        token: value["access_token"].as_str().unwrap().into(),
        id: value["user"]["id"].as_str().unwrap().parse().unwrap(),
        device: value["device_id"].as_str().unwrap().parse().unwrap(),
        login,
    }
}
async fn own(app: &axum::Router, actor: &Account) -> Value {
    let (status, value) = call(app, actor, "GET", "/users/me/identity", None).await;
    assert_eq!(status, StatusCode::OK, "{value}");
    value
}
async fn update(app: &axum::Router, actor: &Account, mut payload: Value) -> (StatusCode, Value) {
    payload["expected_revision"] = own(app, actor).await["revision"].clone();
    call(app, actor, "PUT", "/users/me/identity", Some(payload)).await
}
async fn age_handle(state: &AppState, actor: &Account) {
    sqlx::query(
        "UPDATE user_identities SET username_changed_at=now()-interval '8 days' WHERE user_id=$1",
    )
    .bind(actor.id)
    .execute(&state.db)
    .await
    .unwrap();
}
async fn admin_token(state: &AppState, actor: &Account, role: &str) -> String {
    // Fixture an already unlocked authority; the existing admin_console_test
    // exercises factor proofs/handoff. These requests still use the real extractor.
    sqlx::query("INSERT INTO server_administrators(user_id,role,secret,auth_policy) VALUES($1,$2,$3,'authenticator')")
        .bind(actor.id).bind(role).bind(vec![1u8;32]).execute(&state.db).await.unwrap();
    let session = messenger_server::auth::jwt::decode_access_token(&state.config, &actor.token)
        .unwrap()
        .session_id;
    let token = format!("{}aaaaaaaaaaa", Uuid::new_v4().simple());
    sqlx::query("INSERT INTO admin_sessions(token_hash,user_id,auth_session) VALUES($1,$2,$3)")
        .bind(messenger_server::admin::hash(&token))
        .bind(actor.id)
        .bind(session)
        .execute(&state.db)
        .await
        .unwrap();
    token
}
async fn admin_call(
    app: &axum::Router,
    actor: &Account,
    admin: &str,
    path: &str,
    payload: Value,
) -> (StatusCode, Value) {
    let response = app
        .clone()
        .oneshot(request(
            "POST",
            path,
            ip(),
            Some(&actor.token),
            Some(payload),
            Some(admin),
        ))
        .await
        .unwrap();
    let status = response.status();
    (status, body(response).await)
}

/// Real pre-identity (before 0085, first written as 0068) users/devices/sessions: no identity or allocation ledger rows.
/// PKs are ordered so tests can observe exact bounded page/lock behavior.
async fn legacy_fixture(count: usize) -> (AppState, String, Vec<Account>) {
    let mut state = spawn_state().await;
    let name = format!("coco_login2_backfill_{}", Uuid::new_v4().simple());
    create_database(&name).await;
    let url = format!("{ROOT}{name}");
    let db = sqlx::postgres::PgPoolOptions::new()
        .max_connections(8)
        .connect(&url)
        .await
        .unwrap();
    let all = sqlx::migrate!();
    Migrator {
        migrations: Cow::Owned(all.iter().filter(|m| m.version < 85).cloned().collect()),
        ignore_missing: false,
        locking: true,
    }
    .run(&db)
    .await
    .unwrap();
    let hash = messenger_server::auth::password::run(|| {
        messenger_server::auth::password::hash_password("correct-horse-battery")
    })
    .await
    .unwrap();
    let mut accounts = Vec::new();
    for n in 0..count {
        let user = Uuid::from_u128(n as u128 + 1);
        let device = Uuid::new_v4();
        let session = Uuid::new_v4();
        let login = format!("legacy{n:05}");
        sqlx::query(
            "INSERT INTO users(id,username,display_name,password_hash) VALUES($1,$2,'Legacy',$3)",
        )
        .bind(user)
        .bind(&login)
        .bind(&hash)
        .execute(&db)
        .await
        .unwrap();
        sqlx::query("INSERT INTO devices(id,user_id,platform,signal_device_id,installation_id) VALUES($1,$2,'android',1,$3)")
            .bind(device).bind(user).bind(Uuid::new_v4()).execute(&db).await.unwrap();
        sqlx::query("INSERT INTO sessions(id,user_id,device_id,refresh_token_hash,expires_at) VALUES($1,$2,$3,$4,now()+interval '1 day')")
            .bind(session).bind(user).bind(device).bind(Uuid::new_v4().to_string()).execute(&db).await.unwrap();
        let token =
            messenger_server::auth::jwt::issue_access_token(&state.config, user, device, session)
                .unwrap()
                .token;
        accounts.push(Account {
            id: user,
            device,
            token,
            login,
        });
    }
    all.run(&db)
        .await
        .expect("schema migration commits without historical provisioning");
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM user_identities")
        .fetch_one(&db)
        .await
        .unwrap();
    assert_eq!(
        count, 0,
        "migration must not perform the historical backfill"
    );
    state.db = db;
    state.config.database_url = url;
    (state, name, accounts)
}

async fn drop_legacy_database(state: AppState, name: String) {
    state.db.close().await;
    let root = sqlx::PgPool::connect(&format!("{ROOT}postgres"))
        .await
        .unwrap();
    root.execute(format!("DROP DATABASE {name}").as_str())
        .await
        .unwrap();
    root.close().await;
}

async fn login_existing(app: &axum::Router, account: &Account) {
    let response=app.clone().oneshot(request("POST","/auth/login",ip(),None,Some(json!({
        "username":account.login,"password":"correct-horse-battery","installation_id":Uuid::new_v4(),
        "device_platform":"android"
    })),None)).await.unwrap();
    let status = response.status();
    let value = body(response).await;
    assert_eq!(status, StatusCode::OK, "{value}");
    assert_eq!(value["user"]["id"], account.id.to_string());
}

async fn open_browser(app: &axum::Router, actor: &Account, admin: &str) -> (String, String) {
    let (status, handoff) =
        admin_call(app, actor, admin, "/admin/console-handoff", json!({})).await;
    assert_eq!(status, StatusCode::OK, "{handoff}");
    let response = app
        .clone()
        .oneshot(request(
            "POST",
            "/administration/handoff",
            ip(),
            None,
            Some(json!({"handoff":handoff["handoff"]})),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let cookie = response
        .headers()
        .get_all("set-cookie")
        .iter()
        .map(|h| h.to_str().unwrap())
        .find(|h| h.starts_with("coco_admin_console="))
        .unwrap()
        .split(';')
        .next()
        .unwrap()
        .to_string();
    let csrf = body(response).await["csrf"].as_str().unwrap().to_string();
    (cookie, csrf)
}

async fn admitted_admin(
    app_state: &AppState,
    actor: &Account,
    token: &str,
) -> messenger_server::admin::Admin {
    use axum::extract::FromRequestParts;
    let (mut parts, _) = request(
        "POST",
        "/admin/identity/numbers/1/reserve",
        ip(),
        Some(&actor.token),
        None,
        Some(token),
    )
    .into_parts();
    messenger_server::admin::Admin::from_request_parts(&mut parts, app_state)
        .await
        .unwrap()
}

async fn wait_for_blocked_admin(db: &sqlx::PgPool, blocker: i32) -> i32 {
    wait_for_blocked_query(db, blocker, "%server_administrators%").await
}

async fn wait_for_blocked_query(db: &sqlx::PgPool, blocker: i32, query: &str) -> i32 {
    tokio::time::timeout(std::time::Duration::from_secs(10), async {
        loop {
            let pid: Option<i32> = sqlx::query_scalar(
                "SELECT pid FROM pg_stat_activity WHERE datname=current_database() \
                AND wait_event_type='Lock' AND $1=ANY(pg_blocking_pids(pid)) \
                AND query LIKE $2 LIMIT 1",
            )
            .bind(blocker)
            .bind(query)
            .fetch_optional(db)
            .await
            .unwrap();
            if let Some(pid) = pid {
                return pid;
            }
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
    })
    .await
    .expect("request must reach the coordinated row/advisory lock wait")
}

fn browser_request(
    method: &str,
    path: &str,
    cookie: &str,
    csrf: &str,
    payload: Option<Value>,
) -> Request<Body> {
    let mut request = request(method, path, ip(), None, payload, None);
    for (key, value) in [
        ("host", "localhost"),
        ("origin", "https://localhost"),
        ("cookie", cookie),
        ("x-coco-csrf", csrf),
    ] {
        request.headers_mut().insert(key, value.parse().unwrap());
    }
    request
}
async fn assert_absent(app: &axum::Router, actor: &Account, path: &str) {
    let start = std::time::Instant::now();
    let response = app
        .clone()
        .oneshot(request("GET", path, ip(), Some(&actor.token), None, None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    assert_eq!(response.headers()["cache-control"], "no-store");
    assert_eq!(
        body(response).await,
        json!({"error":{"code":"not_found","message":"User not found."}})
    );
    assert!(start.elapsed() >= std::time::Duration::from_millis(35));
}

#[tokio::test]
async fn numbers_are_unique_immutable_and_optional_owner_fields_are_compatible() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let a = register(&app).await;
    let b = register(&app).await;
    let one = own(&app, &a).await;
    let two = own(&app, &b).await;
    assert_ne!(one["unyri_number"], two["unyri_number"]);
    for value in [&one, &two] {
        let n = value["unyri_number"].as_str().unwrap();
        assert_eq!(n.len(), 9);
        assert!((100_000_000..=999_999_999).contains(&n.parse::<i64>().unwrap()));
        assert_eq!(value["discoverability"], "everyone");
        assert!(value["public_username"].is_null());
    }
    let (_, profile) = call(&app, &a, "GET", "/users/me", None).await;
    assert_eq!(profile["username"], a.login);
    assert_eq!(profile["unyri_number"], one["unyri_number"]);
    let unused: i64 = sqlx::query_scalar(
        "INSERT INTO public_numbers(number) \
        SELECT candidate FROM generate_series(100000000::bigint,100001000::bigint) candidate \
        WHERE NOT EXISTS(SELECT 1 FROM public_numbers WHERE number=candidate) LIMIT 1 \
        ON CONFLICT DO NOTHING RETURNING number",
    )
    .fetch_one(&state.db)
    .await
    .unwrap();
    let assigned: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM user_identities WHERE unyri_number=$1)")
            .bind(unused)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert!(
        !assigned,
        "replacement is a valid ledger number, unused by any identity"
    );
    let error = sqlx::query("UPDATE user_identities SET unyri_number=$2 WHERE user_id=$1")
        .bind(a.id)
        .bind(unused)
        .execute(&state.db)
        .await
        .unwrap_err();
    let database = error
        .as_database_error()
        .expect("database trigger must reject the update");
    assert_eq!(database.code().as_deref(), Some("P0001"));
    assert_eq!(database.message(), "canonical identity is immutable");
    let original: String =
        sqlx::query_scalar("SELECT unyri_number::text FROM user_identities WHERE user_id=$1")
            .bind(a.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(original, one["unyri_number"].as_str().unwrap());
    let name = handle();
    assert_eq!(
        update(&app, &a, json!({"public_username":name})).await.0,
        StatusCode::OK
    );
    let (status, found) = call(
        &app,
        &b,
        "GET",
        &format!(
            "/users/lookup?number={}",
            one["unyri_number"].as_str().unwrap()
        ),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(found["unyri_number"], one["unyri_number"]);
    assert!(found.get("username").is_none());
    let (status, patched) =
        call(&app, &a, "PATCH", "/users/me", Some(json!({"bio":"hello"}))).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(patched["public_username"], name);
    assert_eq!(patched["unyri_number"], one["unyri_number"]);
}

#[tokio::test]
async fn username_grammar_reserved_case_noop_cooldown_rename_and_freeze() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let a = register(&app).await;
    let b = register(&app).await;
    for name in ["ab__cd", "аlice", "1hello", "abcd"] {
        assert_eq!(
            update(&app, &a, json!({"public_username":name})).await.0,
            StatusCode::BAD_REQUEST,
            "{name}"
        );
    }
    for name in ["support", "ADMIN", "official"] {
        let (status, value) = update(&app, &a, json!({"public_username":name})).await;
        assert_eq!(status, StatusCode::CONFLICT, "{name}");
        assert_eq!(value, unavailable_username());
    }
    let name = handle();
    let next = handle();
    let (status, claimed) = update(&app, &a, json!({"public_username":name.to_uppercase()})).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(claimed["public_username"], name);
    assert_eq!(
        update(&app, &a, json!({"public_username":name.to_uppercase()}))
            .await
            .1,
        claimed
    );
    assert_eq!(
        update(&app, &b, json!({"public_username":name.to_uppercase()}))
            .await
            .0,
        StatusCode::CONFLICT
    );
    // Reset this test's attempt budget after intentionally exercising invalid attempts.
    let mut redis = state.redis.clone();
    let _: i64 = redis::cmd("DEL")
        .arg(format!("ratelimit:identity:write:user:{}", a.id))
        .query_async(&mut redis)
        .await
        .unwrap();
    assert_eq!(
        update(&app, &a, json!({"public_username":next})).await.0,
        StatusCode::TOO_MANY_REQUESTS
    );
    age_handle(&state, &a).await;
    let (status, renamed) = update(&app, &a, json!({"public_username":next})).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(renamed["revision"], 2);
    assert_absent(&app, &b, &format!("/users/lookup?username={name}")).await;
    assert_eq!(
        update(&app, &b, json!({"public_username":name})).await.0,
        StatusCode::CONFLICT
    );
    age_handle(&state, &a).await;
    assert_eq!(
        update(&app, &a, json!({"public_username":name})).await.0,
        StatusCode::CONFLICT,
        "former owner is frozen too"
    );
    sqlx::query("UPDATE public_usernames SET frozen_until=now()-interval '1 second' WHERE name=$1")
        .bind(&name)
        .execute(&state.db)
        .await
        .unwrap();
    assert_eq!(
        update(&app, &b, json!({"public_username":name})).await.0,
        StatusCode::OK
    );
    let (status, found) = call(
        &app,
        &a,
        "GET",
        &format!("/users/lookup?username=%40{}", name.to_uppercase()),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(found["id"], b.id.to_string());
    assert_eq!(
        update(&app, &a, json!({"public_username":null})).await.0,
        StatusCode::OK
    );
    assert!(own(&app, &a).await["public_username"].is_null());
    let freeze:bool=sqlx::query_scalar("SELECT user_id IS NULL AND frozen_until>now()+interval '29 days' FROM public_usernames WHERE name=$1").bind(next).fetch_one(&state.db).await.unwrap();
    assert!(freeze);
}

#[tokio::test]
async fn simultaneous_claims_and_revision_updates_are_atomic() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let a = register(&app).await;
    let b = register(&app).await;
    let name = handle();
    let (one, two) = tokio::join!(
        call(
            &app,
            &a,
            "PUT",
            "/users/me/identity",
            Some(json!({"expected_revision":0,"public_username":name}))
        ),
        call(
            &app,
            &b,
            "PUT",
            "/users/me/identity",
            Some(json!({"expected_revision":0,"public_username":name.to_uppercase()}))
        )
    );
    let statuses = [one.0, two.0];
    assert!(statuses.contains(&StatusCode::OK));
    assert!(statuses.contains(&StatusCode::CONFLICT));
    let winner = if one.0 == StatusCode::OK { &a } else { &b };
    let (one, two) = tokio::join!(
        call(
            &app,
            winner,
            "PUT",
            "/users/me/identity",
            Some(json!({"expected_revision":1,"discoverability":"contacts"}))
        ),
        call(
            &app,
            winner,
            "PUT",
            "/users/me/identity",
            Some(json!({"expected_revision":1,"discoverability":"nobody"}))
        )
    );
    assert!([one.0, two.0].contains(&StatusCode::OK));
    assert!([one.0, two.0].contains(&StatusCode::CONFLICT));
    let count: i64 =
        sqlx::query_scalar("SELECT count(*) FROM user_identities WHERE public_username=$1")
            .bind(&name)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(count, 1);
}

fn unavailable_username() -> Value {
    json!({"error":{"code":"unavailable","message":"Public username is unavailable"}})
}

#[tokio::test]
async fn taken_frozen_and_reserved_claims_have_one_unavailable_response() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let owner = register(&app).await;
    let claimant = register(&app).await;
    let frozen = handle();
    let taken = handle();
    let reserved = handle();
    assert_eq!(
        update(&app, &owner, json!({"public_username":frozen}))
            .await
            .0,
        StatusCode::OK
    );
    age_handle(&state, &owner).await;
    assert_eq!(
        update(&app, &owner, json!({"public_username":taken}))
            .await
            .0,
        StatusCode::OK
    );
    sqlx::query("INSERT INTO public_usernames(name,reserved) VALUES($1,true)")
        .bind(&reserved)
        .execute(&state.db)
        .await
        .unwrap();
    for name in [&taken, &frozen, &reserved] {
        let (status, value) = update(
            &app,
            &claimant,
            json!({"public_username":name.to_uppercase()}),
        )
        .await;
        assert_eq!(status, StatusCode::CONFLICT, "{name}");
        assert_eq!(value, unavailable_username());
    }
    let settings = own(&app, &claimant).await;
    assert_eq!(settings["revision"], 0);
    assert!(settings["public_username"].is_null());
    // Claim conflicts reveal availability only, not whether lookup may expose
    // the owner. Hidden and absent discovery continue using the same 404 body.
    assert_eq!(
        update(&app, &owner, json!({"discoverability":"nobody"}))
            .await
            .0,
        StatusCode::OK
    );
    assert_absent(&app, &claimant, &format!("/users/lookup?username={taken}")).await;
    assert_absent(&app, &claimant, &format!("/users/lookup?username={frozen}")).await;
    assert_absent(
        &app,
        &claimant,
        &format!("/users/lookup?username={reserved}"),
    )
    .await;
}

#[tokio::test]
async fn failed_claims_consume_the_confirmed_default_user_and_ip_budgets() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let actor = register(&app).await;
    let payload = json!({"expected_revision":0,"public_username":"support"});
    for _ in 0..10 {
        let response = app
            .clone()
            .oneshot(request(
                "PUT",
                "/users/me/identity",
                ip(),
                Some(&actor.token),
                Some(payload.clone()),
                None,
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::CONFLICT);
        assert_eq!(body(response).await, unavailable_username());
    }
    let response = app
        .clone()
        .oneshot(request(
            "PUT",
            "/users/me/identity",
            ip(),
            Some(&actor.token),
            Some(payload.clone()),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(
        response.status(),
        StatusCode::TOO_MANY_REQUESTS,
        "failed claims must count across IPs"
    );

    let shared_ip = ip();
    for _ in 0..6 {
        let actor = register(&app).await;
        for _ in 0..10 {
            let response = app
                .clone()
                .oneshot(request(
                    "PUT",
                    "/users/me/identity",
                    shared_ip,
                    Some(&actor.token),
                    Some(payload.clone()),
                    None,
                ))
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::CONFLICT);
            assert_eq!(body(response).await, unavailable_username());
        }
    }
    let actor = register(&app).await;
    let response = app
        .oneshot(request(
            "PUT",
            "/users/me/identity",
            shared_ip,
            Some(&actor.token),
            Some(payload),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(
        response.status(),
        StatusCode::TOO_MANY_REQUESTS,
        "failed claims must count across users"
    );
}

#[tokio::test]
async fn owner_origin_and_overridden_quotas_are_local_to_each_server_configuration() {
    use messenger_server::config::identity::IdentityQuota;
    let mut state = spawn_state().await;
    state.config.identity.public_origin = "https://self-hosted.example:8443".into();
    state.config.identity.write = IdentityQuota { user: 2, ip: 3 };
    let app = messenger_server::app(state.clone());
    let actor = register(&app).await;
    let peer = register(&app).await;
    let other = register(&app).await;
    let mut read = request(
        "GET",
        "/users/me/identity",
        ip(),
        Some(&actor.token),
        None,
        None,
    );
    read.headers_mut()
        .insert("host", "attacker.example".parse().unwrap());
    read.headers_mut()
        .insert("x-forwarded-host", "attacker.example".parse().unwrap());
    let response = app.clone().oneshot(read).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let identity = body(response).await;
    assert_eq!(
        identity["public_origin"],
        state.config.identity.public_origin
    );
    let (status, updated) = update(&app, &actor, json!({"discoverability":"everyone"})).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(updated["public_origin"], identity["public_origin"]);
    let (status, profile) = call(
        &app,
        &peer,
        "GET",
        &format!(
            "/users/lookup?number={}",
            identity["unyri_number"].as_str().unwrap()
        ),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert!(profile.get("public_origin").is_none());
    assert!(profile.get("username").is_none());
    let shared_ip = ip();
    let payload = json!({"expected_revision":0,"public_username":"support"});
    // Actor's no-op above was write 1; one failed claim exhausts its custom 2.
    let response = app
        .clone()
        .oneshot(request(
            "PUT",
            "/users/me/identity",
            shared_ip,
            Some(&actor.token),
            Some(payload.clone()),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::CONFLICT);
    let response = app
        .clone()
        .oneshot(request(
            "PUT",
            "/users/me/identity",
            ip(),
            Some(&actor.token),
            Some(payload.clone()),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
    for actor in [&peer, &other] {
        let response = app
            .clone()
            .oneshot(request(
                "PUT",
                "/users/me/identity",
                shared_ip,
                Some(&actor.token),
                Some(payload.clone()),
                None,
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::CONFLICT);
    }
    let last = register(&app).await;
    let response = app
        .oneshot(request(
            "PUT",
            "/users/me/identity",
            shared_ip,
            Some(&last.token),
            Some(payload),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
}

#[tokio::test]
async fn everyone_contacts_and_nobody_apply_to_number_handle_and_uuid_without_login_search_regression(
) {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let a = register(&app).await;
    let b = register(&app).await;
    let name = handle();
    assert_eq!(
        update(&app, &a, json!({"public_username":name})).await.0,
        StatusCode::OK
    );
    let n = own(&app, &a).await["unyri_number"]
        .as_str()
        .unwrap()
        .to_string();
    let paths = [
        format!("/users/lookup?number={n}"),
        format!("/users/lookup?username={name}"),
        format!("/users/{}/profile", a.id),
    ];
    for path in &paths {
        assert_eq!(call(&app, &b, "GET", path, None).await.0, StatusCode::OK);
    }
    assert_eq!(
        update(&app, &a, json!({"discoverability":"contacts"}))
            .await
            .0,
        StatusCode::OK
    );
    assert_eq!(
        call(&app, &b, "POST", &format!("/users/{}/contact", a.id), None)
            .await
            .0,
        StatusCode::OK
    );
    for path in &paths {
        assert_absent(&app, &b, path).await;
    }
    assert_eq!(
        call(&app, &a, "POST", &format!("/users/{}/contact", b.id), None)
            .await
            .0,
        StatusCode::OK
    );
    for path in &paths {
        assert_eq!(call(&app, &b, "GET", path, None).await.0, StatusCode::OK);
    }
    assert_eq!(
        call(
            &app,
            &a,
            "DELETE",
            &format!("/users/{}/contact", b.id),
            None
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_absent(&app, &b, &paths[0]).await;
    assert_eq!(
        update(&app, &a, json!({"discoverability":"nobody"}))
            .await
            .0,
        StatusCode::OK
    );
    assert_eq!(
        call(&app, &a, "POST", &format!("/users/{}/contact", b.id), None)
            .await
            .0,
        StatusCode::OK
    );
    for path in &paths {
        assert_absent(&app, &b, path).await;
        assert_eq!(call(&app, &a, "GET", path, None).await.0, StatusCode::OK);
    }
    let (status, old) = call(
        &app,
        &b,
        "GET",
        &format!("/users/search?q={}", a.login),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(old["items"][0]["username"], a.login);
    assert!(old["items"][0].get("unyri_number").is_none());
}

#[tokio::test]
async fn hidden_unknown_deleted_suspended_and_invalid_targets_have_identical_absence() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let a = register(&app).await;
    let b = register(&app).await;
    let n = own(&app, &a).await["unyri_number"]
        .as_str()
        .unwrap()
        .to_string();
    for path in [
        "/users/lookup?number=01".to_string(),
        "/users/lookup?username=ab__cd".into(),
        "/users/lookup".into(),
        "/users/lookup?number=1&username=alice".into(),
        "/users/lookup?extra=test".into(),
        format!("/users/{}/profile", Uuid::new_v4()),
    ] {
        assert_absent(&app, &b, &path).await;
    }
    assert_eq!(
        update(&app, &a, json!({"discoverability":"nobody"}))
            .await
            .0,
        StatusCode::OK
    );
    assert_absent(&app, &b, &format!("/users/lookup?number={n}")).await;
    assert_eq!(
        update(&app, &a, json!({"discoverability":"everyone"}))
            .await
            .0,
        StatusCode::OK
    );
    sqlx::query("INSERT INTO server_suspensions(user_id,reason) VALUES($1,'test')")
        .bind(a.id)
        .execute(&state.db)
        .await
        .unwrap();
    assert_absent(&app, &b, &format!("/users/lookup?number={n}")).await;
    sqlx::query("DELETE FROM server_suspensions WHERE user_id=$1")
        .bind(a.id)
        .execute(&state.db)
        .await
        .unwrap();
    sqlx::query("UPDATE users SET deleted_at=now() WHERE id=$1")
        .bind(a.id)
        .execute(&state.db)
        .await
        .unwrap();
    assert_absent(&app, &b, &format!("/users/lookup?number={n}")).await;
}

#[tokio::test]
async fn account_and_verified_installation_blocks_are_symmetric_for_discovery() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let a = register(&app).await;
    let b = register(&app).await;
    let name = handle();
    assert_eq!(
        update(&app, &a, json!({"public_username":name})).await.0,
        StatusCode::OK
    );
    let an = own(&app, &a).await["unyri_number"]
        .as_str()
        .unwrap()
        .to_string();
    let bn = own(&app, &b).await["unyri_number"]
        .as_str()
        .unwrap()
        .to_string();
    assert_eq!(
        call(&app, &a, "POST", &format!("/users/{}/block", b.id), None)
            .await
            .0,
        StatusCode::OK
    );
    assert_absent(&app, &b, &format!("/users/lookup?number={an}")).await;
    assert_absent(&app, &b, &format!("/users/lookup?username={name}")).await;
    assert_absent(&app, &a, &format!("/users/lookup?number={bn}")).await;
    assert_absent(&app, &b, &format!("/users/{}/profile", a.id)).await;
    assert_eq!(
        call(
            &app,
            &b,
            "GET",
            &format!("/users/search?q={}", a.login),
            None
        )
        .await
        .1["items"],
        json!([])
    );
    let alt = register(&app).await;
    let installation: Uuid = sqlx::query_scalar(
        "INSERT INTO verified_installations(credential_hash) VALUES($1) RETURNING id",
    )
    .bind(Uuid::new_v4().as_bytes().repeat(2))
    .fetch_one(&state.db)
    .await
    .unwrap();
    sqlx::query("UPDATE devices SET verified_installation_id=$2 WHERE id=$1")
        .bind(alt.device)
        .bind(installation)
        .execute(&state.db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO user_block_installations(user_id,blocked_user_id,installation_id) VALUES($1,$2,$3)").bind(a.id).bind(b.id).bind(installation).execute(&state.db).await.unwrap();
    assert_absent(&app, &alt, &format!("/users/lookup?number={an}")).await;
    assert_absent(&app, &alt, &format!("/users/lookup?username={name}")).await;
    assert_absent(&app, &alt, &format!("/users/{}/profile", a.id)).await;
    assert_eq!(
        call(&app, &a, "DELETE", &format!("/users/{}/block", b.id), None)
            .await
            .0,
        StatusCode::OK
    );
    assert_eq!(
        call(
            &app,
            &alt,
            "GET",
            &format!("/users/lookup?number={an}"),
            None
        )
        .await
        .0,
        StatusCode::OK
    );
}

#[tokio::test]
async fn discovery_budgets_are_shared_per_user_across_ips_and_per_ip_across_users() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let a = register(&app).await;
    for index in 0..30 {
        let path = if index % 2 == 0 {
            "/users/lookup?number=01".into()
        } else {
            format!("/users/{}/profile", Uuid::new_v4())
        };
        assert_absent(&app, &a, &path).await;
    }
    assert_eq!(
        call(&app, &a, "GET", "/users/lookup?number=01", None)
            .await
            .0,
        StatusCode::TOO_MANY_REQUESTS
    );
    let b = register(&app).await;
    let c = register(&app).await;
    let address = ip();
    let mut conn = state.redis.clone();
    let network = messenger_server::auth::rate_limit::auth_client_key(address.0.ip());
    let _: () = redis::cmd("SET")
        .arg(format!("ratelimit:identity:discover:ip:{network}"))
        .arg(119)
        .arg("EX")
        .arg(60)
        .query_async(&mut conn)
        .await
        .unwrap();
    let response = app
        .clone()
        .oneshot(request(
            "GET",
            "/users/lookup?number=01",
            address,
            Some(&b.token),
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    let response = app
        .clone()
        .oneshot(request(
            "GET",
            "/users/lookup?number=01",
            address,
            Some(&c.token),
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
    let _: () = redis::cmd("SET")
        .arg(format!("ratelimit:identity:discover-day:user:{}", b.id))
        .arg(300)
        .arg("EX")
        .arg(86400)
        .query_async(&mut conn)
        .await
        .unwrap();
    assert_eq!(
        call(&app, &b, "GET", "/users/lookup?number=01", None)
            .await
            .0,
        StatusCode::TOO_MANY_REQUESTS
    );
    let _: () = redis::cmd("SET")
        .arg(format!("ratelimit:identity:discover-day:ip:{network}"))
        .arg(3000)
        .arg("EX")
        .arg(86400)
        .query_async(&mut conn)
        .await
        .unwrap();
    // Fresh network minute bucket, same exhausted daily network.
    let _: i64 = redis::cmd("DEL")
        .arg(format!("ratelimit:identity:discover:ip:{network}"))
        .query_async(&mut conn)
        .await
        .unwrap();
    let response = app
        .clone()
        .oneshot(request(
            "GET",
            "/users/lookup?number=01",
            address,
            Some(&c.token),
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
}

#[tokio::test]
async fn admin_reserve_assign_free_and_authority_are_audited_without_changing_canonical_number() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let owner = register(&app).await;
    let peer = register(&app).await;
    let observer = register(&app).await;
    let admin = admin_token(&state, &owner, "owner").await;
    let name = handle();
    let vanity = rand::random::<u32>() % 90_000_000 + 1;
    let number_path = format!("/admin/identity/numbers/{vanity}");
    assert_eq!(
        call(
            &app,
            &peer,
            "POST",
            &format!("{number_path}/reserve"),
            Some(json!({"reason":"test"}))
        )
        .await
        .0,
        StatusCode::NOT_FOUND
    );
    let mod_token = admin_token(&state, &observer, "moderator").await;
    assert_eq!(
        admin_call(
            &app,
            &observer,
            &mod_token,
            &format!("{number_path}/reserve"),
            json!({"reason":"test"})
        )
        .await
        .0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        admin_call(
            &app,
            &owner,
            &admin,
            &format!("{number_path}/reserve"),
            json!({"reason":""})
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        admin_call(
            &app,
            &owner,
            &admin,
            &format!("{number_path}/reserve"),
            json!({"reason":"team"})
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        admin_call(
            &app,
            &owner,
            &admin,
            &format!("{number_path}/reserve"),
            json!({"reason":"team"})
        )
        .await
        .0,
        StatusCode::CONFLICT
    );
    let canonical = own(&app, &peer).await["unyri_number"].clone();
    assert_eq!(
        admin_call(
            &app,
            &owner,
            &admin,
            &format!("{number_path}/assign"),
            json!({"reason":"team","user_id":peer.id})
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        admin_call(
            &app,
            &owner,
            &admin,
            &format!("{number_path}/assign"),
            json!({"reason":"team","user_id":observer.id})
        )
        .await
        .0,
        StatusCode::CONFLICT
    );
    let (status, found) = call(
        &app,
        &observer,
        "GET",
        &format!("/users/lookup?number={vanity}"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(found["unyri_number"], canonical);
    let username_path = format!("/admin/identity/usernames/{name}");
    assert_eq!(
        admin_call(
            &app,
            &owner,
            &admin,
            &format!("{username_path}/reserve"),
            json!({"reason":"brand","reserved":true})
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        update(&app, &peer, json!({"public_username":name})).await.0,
        StatusCode::CONFLICT
    );
    assert_eq!(
        admin_call(
            &app,
            &owner,
            &admin,
            &format!("{username_path}/free"),
            json!({"reason":"free"})
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        update(&app, &peer, json!({"public_username":name})).await.0,
        StatusCode::CONFLICT,
        "free preserves reservation"
    );
    assert_eq!(
        admin_call(
            &app,
            &owner,
            &admin,
            &format!("{username_path}/reserve"),
            json!({"reason":"brand","reserved":false})
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        update(&app, &peer, json!({"public_username":name})).await.0,
        StatusCode::OK
    );
    assert_eq!(
        admin_call(
            &app,
            &owner,
            &admin,
            &format!("{username_path}/reserve"),
            json!({"reason":"brand","reserved":true})
        )
        .await
        .0,
        StatusCode::CONFLICT
    );
    assert_eq!(
        admin_call(
            &app,
            &owner,
            &admin,
            &format!("{username_path}/free"),
            json!({"reason":"moderation"})
        )
        .await
        .0,
        StatusCode::OK
    );
    assert!(own(&app, &peer).await["public_username"].is_null());
    assert_eq!(
        update(&app, &peer, json!({"public_username":handle()}))
            .await
            .0,
        StatusCode::OK,
        "admin free resets cooldown"
    );
    assert_eq!(
        update(&app, &observer, json!({"public_username":name}))
            .await
            .0,
        StatusCode::OK,
        "admin free removes freeze"
    );
    let response = app
        .clone()
        .oneshot(request(
            "GET",
            "/admin/identity?limit=1",
            ip(),
            Some(&owner.token),
            None,
            Some(&admin),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let page = body(response).await;
    assert_eq!(page["numbers"].as_array().unwrap().len(), 1);
    assert!(page["number_next"].is_string());
    let count: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM admin_audit WHERE actor=$1 AND action LIKE 'identity:%'",
    )
    .bind(owner.id)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(count, 6);
    assert_eq!(
        update(&app, &peer, json!({"discoverability":"nobody"}))
            .await
            .0,
        StatusCode::OK
    );
    assert_absent(&app, &owner, &format!("/users/lookup?number={vanity}")).await;
}

#[tokio::test]
async fn deleting_accounts_freezes_handles_and_permanently_retires_numbers() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let a = register(&app).await;
    let b = register(&app).await;
    let name = handle();
    assert_eq!(
        update(&app, &a, json!({"public_username":name})).await.0,
        StatusCode::OK
    );
    let number = own(&app, &a).await["unyri_number"]
        .as_str()
        .unwrap()
        .parse::<i64>()
        .unwrap();
    sqlx::query("DELETE FROM users WHERE id=$1")
        .bind(a.id)
        .execute(&state.db)
        .await
        .unwrap();
    assert_absent(&app, &b, &format!("/users/lookup?number={number}")).await;
    assert_eq!(
        update(&app, &b, json!({"public_username":name})).await.0,
        StatusCode::CONFLICT
    );
    let retired: Uuid = sqlx::query_scalar("SELECT user_id FROM public_numbers WHERE number=$1")
        .bind(number)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(retired, a.id);
    // A fresh UUID is valid in the permanent ledger (it deliberately has no
    // user FK), and has no canonical allocation to collide with the index.
    let replacement = Uuid::new_v4();
    let allocated: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM public_numbers WHERE user_id=$1 AND canonical)",
    )
    .bind(replacement)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert!(!allocated);
    let error = sqlx::query("UPDATE public_numbers SET user_id=$2 WHERE number=$1")
        .bind(number)
        .bind(replacement)
        .execute(&state.db)
        .await
        .unwrap_err();
    let database = error
        .as_database_error()
        .expect("ownership trigger must reject reassignment");
    assert_eq!(database.code().as_deref(), Some("P0001"));
    assert_eq!(database.message(), "number allocations are immutable");
    let owner: Uuid = sqlx::query_scalar("SELECT user_id FROM public_numbers WHERE number=$1")
        .bind(number)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(owner, a.id);
    let error = sqlx::query("DELETE FROM public_numbers WHERE number=$1")
        .bind(number)
        .execute(&state.db)
        .await
        .unwrap_err();
    let database = error
        .as_database_error()
        .expect("retirement trigger must preserve allocations");
    assert_eq!(database.code().as_deref(), Some("P0001"));
    assert_eq!(database.message(), "number allocations are permanent");
    let owner: Uuid = sqlx::query_scalar("SELECT user_id FROM public_numbers WHERE number=$1")
        .bind(number)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(owner, a.id);
}

#[tokio::test]
async fn identity_and_handoffs_lock_role_before_native_during_real_factor_reset_and_revocation() {
    use axum::{
        extract::{Path, State},
        response::IntoResponse,
        Json,
    };
    use messenger_server::{admin_operations, console, net::ClientIp, users::identity_admin};
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    // Two competing transaction connections, plus a gate/observer. A trigger
    // pauses the REAL reset/revoke handler after it owns the role row and before
    // it reaches native-session deletion. No production test hook or timed race.
    for revoke in [false, true] {
        for operation in 0..3 {
            let actor = register(&app).await;
            let controller = register(&app).await;
            let actor_token = admin_token(&state, &actor, "owner").await;
            let controller_token = admin_token(&state, &controller, "owner").await;
            let admitted = admitted_admin(&state, &actor, &actor_token).await;
            let controlling = admitted_admin(&state, &controller, &controller_token).await;
            let handoff = if operation == 2 {
                let (status, value) = admin_call(
                    &app,
                    &actor,
                    &actor_token,
                    "/admin/console-handoff",
                    json!({}),
                )
                .await;
                assert_eq!(status, StatusCode::OK);
                value["handoff"].as_str().unwrap().to_string()
            } else {
                String::new()
            };
            let fixture = format!("identity_role_gate_{}", Uuid::new_v4().simple());
            let key = 9_000_000 + i64::from(rand::random::<u32>() % 1_000_000);
            let (event, row) = if revoke {
                ("BEFORE DELETE", "OLD")
            } else {
                ("AFTER UPDATE OF secret", "NEW")
            };
            state
                .db
                .execute(
                    format!(
                        "CREATE FUNCTION {fixture}() RETURNS trigger LANGUAGE plpgsql AS $$ \
                BEGIN PERFORM pg_advisory_xact_lock({key}); RETURN {row}; END $$; \
                CREATE TRIGGER {fixture} {event} ON server_administrators FOR EACH ROW \
                WHEN ({row}.user_id='{}'::uuid) EXECUTE FUNCTION {fixture}()",
                        actor.id
                    )
                    .as_str(),
                )
                .await
                .unwrap();
            let mut gate = state.db.begin().await.unwrap();
            let gate_pid: i32 = sqlx::query_scalar("SELECT pg_backend_pid()")
                .fetch_one(&mut *gate)
                .await
                .unwrap();
            sqlx::query("SELECT pg_advisory_xact_lock($1)")
                .bind(key)
                .execute(&mut *gate)
                .await
                .unwrap();
            let resetting_state = state.clone();
            let actor_id = actor.id;
            let resetting = tokio::spawn(async move {
                if revoke {
                    let body = serde_json::from_value(
                        json!({"role":"revoke","reason":"lock ordering regression"}),
                    )
                    .unwrap();
                    admin_operations::change_operator(
                        State(resetting_state),
                        controlling,
                        Path(actor_id),
                        Json(body),
                    )
                    .await
                    .into_response()
                } else {
                    let body = serde_json::from_value(json!({"reason":"lock ordering regression"}))
                        .unwrap();
                    admin_operations::reset_operator_2fa(
                        State(resetting_state),
                        controlling,
                        Path(actor_id),
                        Json(body),
                    )
                    .await
                    .into_response()
                }
            });
            let reset_pid = wait_for_blocked_admin(&state.db, gate_pid).await;
            let pending_state = state.clone();
            let pending = tokio::spawn(async move {
                match operation {
                    0 => {
                        let body = serde_json::from_value(
                            json!({"reason":"stale admitted identity write"}),
                        )
                        .unwrap();
                        identity_admin::reserve_number(
                            State(pending_state),
                            ClientIp(ip().0.ip()),
                            admitted,
                            Path("12345678".into()),
                            Json(body),
                        )
                        .await
                        .into_response()
                    }
                    1 => console::issue_handoff(State(pending_state), admitted)
                        .await
                        .into_response(),
                    _ => {
                        let body = serde_json::from_value(json!({"handoff":handoff})).unwrap();
                        console::redeem(
                            State(pending_state),
                            messenger_server::net::ClientIp(std::net::IpAddr::from([127, 0, 0, 1])),
                            Json(body),
                        )
                            .await
                            .into_response()
                    }
                }
            });
            wait_for_blocked_admin(&state.db, reset_pid).await;
            // While the admitted request waits for the role, it must not own a
            // native-session lock. The old join order fails this NOWAIT probe.
            let probe: Result<Option<String>, _> = sqlx::query_scalar(
                "SELECT token_hash FROM admin_sessions WHERE token_hash=$1 FOR UPDATE NOWAIT",
            )
            .bind(messenger_server::admin::hash(&actor_token))
            .fetch_optional(&mut *gate)
            .await;
            // Account pins in identity and both handoff paths must permit peer
            // FK writes while waiting, without sacrificing writer serialization.
            let account_probe: Result<Option<Uuid>, _> =
                sqlx::query_scalar("SELECT id FROM users WHERE id=$1 FOR KEY SHARE NOWAIT")
                    .bind(actor.id)
                    .fetch_optional(&mut *gate)
                    .await;
            gate.rollback().await.unwrap();
            let reset_response =
                tokio::time::timeout(std::time::Duration::from_secs(10), resetting)
                    .await
                    .unwrap()
                    .unwrap();
            let pending_response =
                tokio::time::timeout(std::time::Duration::from_secs(10), pending)
                    .await
                    .unwrap()
                    .unwrap();
            state.db.execute(format!("DROP TRIGGER {fixture} ON server_administrators; DROP FUNCTION {fixture}()").as_str()).await.unwrap();
            assert!(matches!(probe,Ok(Some(_))),"native session was pinned before role; revoke={revoke}, operation={operation}, probe={probe:?}");
            assert!(matches!(account_probe,Ok(Some(_))),"account pin blocked a peer FK write; revoke={revoke}, operation={operation}, probe={account_probe:?}");
            assert_eq!(
                reset_response.status(),
                StatusCode::OK,
                "reset/revoke must commit without deadlock"
            );
            assert_eq!(
                pending_response.status(),
                StatusCode::NOT_FOUND,
                "stale authority must be revalidated after waiting"
            );
            let sessions: i64 =
                sqlx::query_scalar("SELECT count(*) FROM admin_sessions WHERE user_id=$1")
                    .bind(actor.id)
                    .fetch_one(&state.db)
                    .await
                    .unwrap();
            assert_eq!(sessions, 0);
            let audits:i64=sqlx::query_scalar("SELECT count(*) FROM admin_audit WHERE actor=$1 AND (action LIKE 'identity:%' OR action IN ('console:handoff-issued','console:session-open'))")
                .bind(actor.id).fetch_one(&state.db).await.unwrap();
            assert_eq!(
                audits,
                if operation == 2 { 1 } else { 0 },
                "no stale operation may produce an audit/mutation"
            );
        }
    }
}

#[tokio::test]
async fn deletion_peer_tombstones_do_not_deadlock_with_cross_account_identity_administration() {
    use axum::{
        extract::{Path, State},
        response::IntoResponse,
        Json,
    };
    use messenger_server::{net::ClientIp, users::identity_admin};
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    for free in [false, true] {
        let first = register(&app).await;
        let second = register(&app).await;
        // Force the troublesome order: the administrator's peer account is
        // pinned before the deleting target, which already owns its user row.
        let (actor, target) = if first.id < second.id {
            (first, second)
        } else {
            (second, first)
        };
        let token = admin_token(&state, &actor, "owner").await;
        let name = handle();
        let number: String = sqlx::query_scalar(
            "SELECT n::text FROM generate_series(2000000,2001000) n \
             WHERE NOT EXISTS(SELECT 1 FROM public_numbers WHERE number=n) LIMIT 1",
        )
        .fetch_one(&state.db)
        .await
        .unwrap();
        if free {
            assert_eq!(
                update(&app, &target, json!({"public_username":name}))
                    .await
                    .0,
                StatusCode::OK
            );
        } else {
            assert_eq!(
                admin_call(
                    &app,
                    &actor,
                    &token,
                    &format!("/admin/identity/numbers/{number}/reserve"),
                    json!({"reason":"deletion concurrency fixture"}),
                )
                .await
                .0,
                StatusCode::OK
            );
        }
        let (status, chat) = call(
            &app,
            &actor,
            "POST",
            "/chats/direct",
            Some(json!({"other_user_id":target.id})),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{chat}");
        let chat_id: Uuid = chat["chat_id"].as_str().unwrap().parse().unwrap();
        // Complete HTTP authority admission before coordinating the transaction
        // wait, as with the reset/revocation regression above.
        let admitted = admitted_admin(&state, &actor, &token).await;
        let fixture = format!("identity_delete_gate_{}", Uuid::new_v4().simple());
        let key = 11_000_000 + i64::from(rand::random::<u32>() % 1_000_000);
        state
            .db
            .execute(
                format!(
                    "CREATE FUNCTION {fixture}() RETURNS trigger LANGUAGE plpgsql AS $$ \
             BEGIN PERFORM pg_advisory_xact_lock({key}); RETURN NEW; END $$; \
             CREATE TRIGGER {fixture} BEFORE INSERT ON deleted_chats FOR EACH ROW \
             WHEN (NEW.user_id='{}'::uuid AND NEW.chat_id='{chat_id}'::uuid) \
             EXECUTE FUNCTION {fixture}()",
                    actor.id
                )
                .as_str(),
            )
            .await
            .unwrap();
        let mut gate = state.db.begin().await.unwrap();
        let gate_pid: i32 = sqlx::query_scalar("SELECT pg_backend_pid()")
            .fetch_one(&mut *gate)
            .await
            .unwrap();
        sqlx::query("SELECT pg_advisory_xact_lock($1)")
            .bind(key)
            .execute(&mut *gate)
            .await
            .unwrap();
        let deleting_app = app.clone();
        let target_id = target.id;
        let deleting = tokio::spawn(async move {
            deleting_app
                .oneshot(request(
                    "POST",
                    "/users/me/delete",
                    ip(),
                    Some(&target.token),
                    Some(json!({"password":"correct-horse-battery"})),
                    None,
                ))
                .await
                .unwrap()
        });
        let deleting_pid = wait_for_blocked_query(&state.db, gate_pid, "%deleted_chats%").await;
        let pending_state = state.clone();
        let pending_name = name.clone();
        let pending_number = number.clone();
        let pending = tokio::spawn(async move {
            if free {
                let body = serde_json::from_value(json!({"reason":"deletion concurrency fixture"}))
                    .unwrap();
                identity_admin::free_username(
                    State(pending_state),
                    ClientIp(ip().0.ip()),
                    admitted,
                    Path(pending_name),
                    Json(body),
                )
                .await
                .into_response()
            } else {
                let body = serde_json::from_value(
                    json!({"user_id":target_id,"reason":"deletion concurrency fixture"}),
                )
                .unwrap();
                identity_admin::assign_number(
                    State(pending_state),
                    ClientIp(ip().0.ip()),
                    admitted,
                    Path(pending_number),
                    Json(body),
                )
                .await
                .into_response()
            }
        });
        wait_for_blocked_query(&state.db, deleting_pid, "%FROM users%").await;
        // This is precisely the FK lock the gated tombstone will need. With
        // FOR UPDATE, the administrator owns B while waiting on A; this probe
        // fails and opening the gate would create the deletion/admin cycle.
        let probe: Result<Option<Uuid>, _> =
            sqlx::query_scalar("SELECT id FROM users WHERE id=$1 FOR KEY SHARE NOWAIT")
                .bind(actor.id)
                .fetch_optional(&mut *gate)
                .await;
        gate.rollback().await.unwrap();
        let deleted = tokio::time::timeout(std::time::Duration::from_secs(10), deleting)
            .await
            .unwrap()
            .unwrap();
        let response = tokio::time::timeout(std::time::Duration::from_secs(10), pending)
            .await
            .unwrap()
            .unwrap();
        state
            .db
            .execute(
                format!("DROP TRIGGER {fixture} ON deleted_chats; DROP FUNCTION {fixture}()")
                    .as_str(),
            )
            .await
            .unwrap();
        assert!(
            matches!(probe, Ok(Some(_))),
            "account pin blocked deletion's peer FK check; free={free}, probe={probe:?}"
        );
        assert_eq!(
            deleted.status(),
            StatusCode::NO_CONTENT,
            "deletion must commit without deadlock"
        );
        assert_eq!(
            response.status(),
            StatusCode::NOT_FOUND,
            "queued identity write must revalidate the deleted target"
        );
        let tombstone: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM deleted_chats WHERE user_id=$1 AND chat_id=$2)",
        )
        .bind(actor.id)
        .bind(chat_id)
        .fetch_one(&state.db)
        .await
        .unwrap();
        assert!(tombstone, "peer tombstone must survive deletion");
        let target_exists: bool =
            sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM users WHERE id=$1)")
                .bind(target_id)
                .fetch_one(&state.db)
                .await
                .unwrap();
        assert!(!target_exists);
        let audits: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM admin_audit WHERE actor=$1 AND action IN ('identity:number_assign','identity:username_free')",
        ).bind(actor.id).fetch_one(&state.db).await.unwrap();
        assert_eq!(audits, 0, "no stale assignment/free may commit");
        if free {
            let frozen: bool = sqlx::query_scalar(
                "SELECT user_id IS NULL AND frozen_until>now() FROM public_usernames WHERE name=$1",
            )
            .bind(&name)
            .fetch_one(&state.db)
            .await
            .unwrap();
            assert!(frozen, "deletion's username freeze must remain in force");
        } else {
            let owner: Option<Uuid> =
                sqlx::query_scalar("SELECT user_id FROM public_numbers WHERE number=$1")
                    .bind(number.parse::<i64>().unwrap())
                    .fetch_one(&state.db)
                    .await
                    .unwrap();
            assert_eq!(owner, None);
        }
    }
}

#[tokio::test]
async fn read_only_accounts_can_restrict_discovery_and_routes_require_authentication() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let a = register(&app).await;
    for (method, path) in [
        ("GET", "/users/me/identity"),
        ("PUT", "/users/me/identity"),
        ("GET", "/users/lookup?number=123456789"),
        ("GET", "/users/00000000-0000-0000-0000-000000000001/profile"),
    ] {
        let response = app
            .clone()
            .oneshot(request(method, path, ip(), None, Some(json!({})), None))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    }
    sqlx::query("INSERT INTO admin_user_controls(user_id,read_only_reason) VALUES($1,'test')")
        .bind(a.id)
        .execute(&state.db)
        .await
        .unwrap();
    assert_eq!(
        update(&app, &a, json!({"discoverability":"nobody"}))
            .await
            .0,
        StatusCode::OK
    );
    assert_eq!(
        update(&app, &a, json!({"public_username":handle()}))
            .await
            .0,
        StatusCode::FORBIDDEN
    );
}

#[tokio::test]
async fn migration_backfills_existing_accounts_and_survives_retry_without_changing_numbers() {
    use messenger_server::users::identity_backfill::{backfill_batch, spawn_backfill, BATCH_SIZE};
    let (state, name, accounts) = legacy_fixture(40).await;
    let db = &state.db;
    let batch = backfill_batch(db, None).await.unwrap();
    assert_eq!(batch.scanned, BATCH_SIZE as usize);
    assert_eq!(batch.provisioned, BATCH_SIZE as u64);
    let checkpoint: Vec<(Uuid, i64)> =
        sqlx::query_as("SELECT user_id,unyri_number FROM user_identities ORDER BY user_id")
            .fetch_all(db)
            .await
            .unwrap();
    // Simulate restart: discard the cursor and run the actual worker from zero.
    tokio::time::timeout(
        std::time::Duration::from_secs(5),
        spawn_backfill(db.clone()),
    )
    .await
    .unwrap()
    .unwrap();
    let rows: Vec<(Uuid, i64)> =
        sqlx::query_as("SELECT user_id,unyri_number FROM user_identities ORDER BY user_id")
            .fetch_all(db)
            .await
            .unwrap();
    assert_eq!(rows.len(), accounts.len());
    assert_eq!(
        rows.iter()
            .map(|r| r.1)
            .collect::<std::collections::HashSet<_>>()
            .len(),
        accounts.len()
    );
    assert_eq!(&rows[..checkpoint.len()], checkpoint.as_slice());
    sqlx::migrate!().run(db).await.unwrap();
    tokio::time::timeout(
        std::time::Duration::from_secs(5),
        spawn_backfill(db.clone()),
    )
    .await
    .unwrap()
    .unwrap();
    let again: Vec<(Uuid, i64)> =
        sqlx::query_as("SELECT user_id,unyri_number FROM user_identities ORDER BY user_id")
            .fetch_all(db)
            .await
            .unwrap();
    assert_eq!(rows, again);
    // Old provisioning writer: database trigger allocates without Rust cooperation.
    db.execute("INSERT INTO users(username,display_name,password_hash) VALUES('legacy_after','Legacy','fixture')").await.unwrap();
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM user_identities")
        .fetch_one(db)
        .await
        .unwrap();
    assert_eq!(count, 41);
    drop_legacy_database(state, name).await;
}

#[tokio::test]
async fn bounded_backfill_skips_locked_existing_accounts_and_runs_with_login_and_profile_mutations()
{
    use messenger_server::users::identity_backfill::{backfill_batch, spawn_backfill, BATCH_SIZE};
    let (state, name, accounts) = legacy_fixture(320).await;
    let app = messenger_server::app(state.clone());
    let mut hot = state.db.begin().await.unwrap();
    sqlx::query("SELECT id FROM users WHERE id=$1 FOR UPDATE")
        .bind(accounts[0].id)
        .execute(&mut *hot)
        .await
        .unwrap();
    let batch = backfill_batch(&state.db, None)
        .await
        .expect("hot account must be skipped, not waited for");
    assert_eq!(batch.scanned, BATCH_SIZE as usize);
    assert_eq!(batch.provisioned, BATCH_SIZE as u64 - 1);
    assert!(batch.retry_needed);
    // This FOR UPDATE models the exact conflicting lock used by login/PATCH.
    // A committed batch must release all its FK key-share locks immediately.
    let mut probe = state.db.begin().await.unwrap();
    sqlx::query("SET LOCAL lock_timeout='100ms'")
        .execute(&mut *probe)
        .await
        .unwrap();
    sqlx::query("SELECT id FROM users WHERE id=$1 FOR UPDATE")
        .bind(accounts[1].id)
        .execute(&mut *probe)
        .await
        .unwrap();
    probe.rollback().await.unwrap();
    // Run real login and profile mutations on an earlier completed page while
    // later historical pages are provisioned. Keep the hot account locked so
    // the worker must retry it after this traffic, rather than declare success.
    let worker = spawn_backfill(state.db.clone());
    let traffic = async {
        let edits = async {
            for n in 0..20 {
                let (status, value) = call(
                    &app,
                    &accounts[1],
                    "PATCH",
                    "/users/me",
                    Some(json!({"bio":format!("update {n}")})),
                )
                .await;
                assert_eq!(status, StatusCode::OK, "{value}");
                assert_eq!(value["unyri_number"].as_str().unwrap().len(), 9);
            }
        };
        let logins = async {
            for _ in 0..3 {
                login_existing(&app, &accounts[1]).await;
            }
        };
        tokio::join!(edits, logins);
    };
    tokio::time::timeout(std::time::Duration::from_secs(10), traffic)
        .await
        .expect("existing accounts must stay responsive");
    tokio::time::timeout(std::time::Duration::from_secs(5), async {
        loop {
            let reached_end: bool =
                sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM user_identities WHERE user_id=$1)")
                    .bind(accounts.last().unwrap().id)
                    .fetch_one(&state.db)
                    .await
                    .unwrap();
            if reached_end {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
    })
    .await
    .expect("other historical accounts must progress while the hot account is locked");
    assert!(
        !worker.is_finished(),
        "skipped work must not be marked complete"
    );
    hot.rollback().await.unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(5), worker)
        .await
        .unwrap()
        .unwrap();
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM user_identities")
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(count, 320);
    let (_, profile) = call(&app, &accounts[1], "GET", "/users/me", None).await;
    assert_eq!(profile["bio"], "update 19");
    drop_legacy_database(state, name).await;
}

#[tokio::test]
async fn pending_identity_reads_and_mutations_provision_on_demand_without_exposing_blocked_users() {
    let (state, name, accounts) = legacy_fixture(8).await;
    let app = messenger_server::app(state.clone());
    let (status, profile) = call(&app, &accounts[0], "GET", "/users/me", None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(profile["unyri_number"].as_str().unwrap().len(), 9);
    assert_eq!(
        own(&app, &accounts[1]).await["unyri_number"]
            .as_str()
            .unwrap()
            .len(),
        9
    );
    let (status, settings) = call(
        &app,
        &accounts[2],
        "PUT",
        "/users/me/identity",
        Some(json!({"expected_revision":0,"discoverability":"nobody"})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(settings["unyri_number"].as_str().unwrap().len(), 9);
    let (status, profile) = call(
        &app,
        &accounts[3],
        "PATCH",
        "/users/me",
        Some(json!({"bio":"legacy edit"})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert!(profile["unyri_number"].is_string());
    let (status, profile) = call(
        &app,
        &accounts[0],
        "GET",
        &format!("/users/{}/profile", accounts[4].id),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert!(profile["unyri_number"].is_string());
    let admin = admin_token(&state, &accounts[0], "owner").await;
    assert_eq!(
        admin_call(
            &app,
            &accounts[0],
            &admin,
            "/admin/identity/numbers/123/reserve",
            json!({"reason":"test"})
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        admin_call(
            &app,
            &accounts[0],
            &admin,
            "/admin/identity/numbers/123/assign",
            json!({"user_id":accounts[5].id,"reason":"test"})
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        call(&app, &accounts[0], "GET", "/users/lookup?number=123", None)
            .await
            .0,
        StatusCode::OK
    );
    login_existing(&app, &accounts[6]).await;
    assert_eq!(
        call(
            &app,
            &accounts[0],
            "POST",
            &format!("/users/{}/block", accounts[7].id),
            None
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_absent(
        &app,
        &accounts[0],
        &format!("/users/{}/profile", accounts[7].id),
    )
    .await;
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM user_identities")
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(
        count, 7,
        "blocked resolution did not provision or expose the pending account"
    );
    drop_legacy_database(state, name).await;
}

#[tokio::test]
async fn console_identity_writes_use_browser_lifetime_when_native_authority_is_over_twelve_hours_old(
) {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let owner = register(&app).await;
    let admin = admin_token(&state, &owner, "owner").await;
    let (cookie, csrf) = open_browser(&app, &owner, &admin).await;
    let native_hash = messenger_server::admin::hash(&admin);
    sqlx::query("UPDATE admin_sessions SET created_at=now()-interval '13 hours',last_used=now()-interval '13 hours' WHERE token_hash=$1")
        .bind(&native_hash).execute(&state.db).await.unwrap();
    sqlx::query("UPDATE admin_console_sessions SET created_at=now()-interval '2 hours',expires_at=now()+interval '6 hours',last_seen=now() WHERE admin_session=$1")
        .bind(&native_hash).execute(&state.db).await.unwrap();
    let number = rand::random::<u32>() % 90_000_000 + 1;
    let path = format!("/admin/identity/numbers/{number}/reserve");
    assert_eq!(
        admin_call(&app, &owner, &admin, &path, json!({"reason":"test"}))
            .await
            .0,
        StatusCode::NOT_FOUND,
        "expired native channel stays expired"
    );
    let response = app
        .clone()
        .oneshot(browser_request(
            "GET",
            "/admin/identity",
            &cookie,
            &csrf,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let response = app
        .clone()
        .oneshot(browser_request(
            "POST",
            &path,
            &cookie,
            &csrf,
            Some(json!({"reason":"valid browser"})),
        ))
        .await
        .unwrap();
    let status = response.status();
    let value = body(response).await;
    assert_eq!(status, StatusCode::OK, "{value}");
    sqlx::query("UPDATE admin_console_sessions SET last_seen=now()-interval '16 minutes' WHERE admin_session=$1").bind(&native_hash).execute(&state.db).await.unwrap();
    let response = app
        .clone()
        .oneshot(browser_request(
            "GET",
            "/admin/identity",
            &cookie,
            &csrf,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    sqlx::query("UPDATE admin_console_sessions SET last_seen=now(),expires_at=now()-interval '1 second' WHERE admin_session=$1").bind(&native_hash).execute(&state.db).await.unwrap();
    let response = app
        .clone()
        .oneshot(browser_request(
            "POST",
            &path,
            &cookie,
            &csrf,
            Some(json!({"reason":"expired browser"})),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn console_identity_authority_rechecks_idle_absolute_expiry_and_exact_session_after_admission(
) {
    use axum::extract::{FromRequestParts, Path, State};
    use messenger_server::{
        admin::Admin,
        net::ClientIp,
        users::identity_admin::{reserve_number, Reason},
    };
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let owner = register(&app).await;
    let admin = admin_token(&state, &owner, "owner").await;
    let (cookie, csrf) = open_browser(&app, &owner, &admin).await;
    let native_hash = messenger_server::admin::hash(&admin);
    for change in ["UPDATE admin_console_sessions SET last_seen=now()-interval '16 minutes' WHERE admin_session=$1",
        "UPDATE admin_console_sessions SET expires_at=now()-interval '1 second' WHERE admin_session=$1"] {
        let (mut parts,_)=browser_request("POST","/admin/identity/numbers/1/reserve",&cookie,&csrf,Some(json!({"reason":"test"}))).into_parts();
        let admitted=Admin::from_request_parts(&mut parts,&state).await.unwrap();
        sqlx::query(change).bind(&native_hash).execute(&state.db).await.unwrap();
        let reason:Reason=serde_json::from_value(json!({"reason":"stale admission"})).unwrap();
        let result=reserve_number(State(state.clone()),ClientIp(ip().0.ip()),admitted,Path("1".into()),axum::Json(reason)).await;
        assert!(matches!(result,Err(messenger_server::error::AppError::NotFound(_))));
        sqlx::query("UPDATE admin_console_sessions SET last_seen=now(),expires_at=now()+interval '1 hour' WHERE admin_session=$1").bind(&native_hash).execute(&state.db).await.unwrap();
    }
    let (mut parts, _) = browser_request(
        "POST",
        "/admin/identity/numbers/1/reserve",
        &cookie,
        &csrf,
        Some(json!({"reason":"test"})),
    )
    .into_parts();
    let admitted = Admin::from_request_parts(&mut parts, &state).await.unwrap();
    let (replacement_cookie, replacement_csrf) = open_browser(&app, &owner, &admin).await;
    let reason: Reason = serde_json::from_value(json!({"reason":"replaced console"})).unwrap();
    let result = reserve_number(
        State(state.clone()),
        ClientIp(ip().0.ip()),
        admitted,
        Path("1".into()),
        axum::Json(reason),
    )
    .await;
    assert!(
        matches!(result, Err(messenger_server::error::AppError::NotFound(_))),
        "a replacement session cannot authorize an admitted old cookie"
    );
    // Shared role/revision and parent revocation checks still apply to consoles.
    sqlx::query("UPDATE server_administrators SET auth_revision=auth_revision+1 WHERE user_id=$1")
        .bind(owner.id)
        .execute(&state.db)
        .await
        .unwrap();
    let response = app
        .clone()
        .oneshot(browser_request(
            "POST",
            "/admin/identity/numbers/1/reserve",
            &replacement_cookie,
            &replacement_csrf,
            Some(json!({"reason":"stale revision"})),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    // Other tests may own number 1: check this actor's audit, not global data.
    let count: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM admin_audit WHERE actor=$1 AND action LIKE 'identity:%'",
    )
    .bind(owner.id)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(count, 0);
}

#[tokio::test]
async fn number_allocation_excludes_reserved_ranges_and_fails_after_bounded_retries() {
    let state = spawn_state().await;
    let mut tx = state.db.begin().await.unwrap();
    sqlx::query("INSERT INTO public_number_ranges VALUES(100000000,999999999)")
        .execute(&mut *tx)
        .await
        .unwrap();
    let result = messenger_server::users::identity::allocate_number(&mut tx, Uuid::new_v4()).await;
    assert!(matches!(
        result,
        Err(messenger_server::error::AppError::Unavailable(_))
    ));
    tx.rollback().await.unwrap();
}

#[tokio::test]
async fn rename_during_lookup_never_redirects_or_returns_a_mixed_profile() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let a = register(&app).await;
    let b = register(&app).await;
    let old = handle();
    let next = handle();
    assert_eq!(
        update(&app, &a, json!({"public_username":old})).await.0,
        StatusCode::OK
    );
    age_handle(&state, &a).await;
    let lookup = async {
        for _ in 0..12 {
            let (status, value) = call(
                &app,
                &b,
                "GET",
                &format!("/users/lookup?username={old}"),
                None,
            )
            .await;
            if status == StatusCode::OK {
                assert_eq!(value["id"], a.id.to_string());
                assert_eq!(value["public_username"], old);
            } else {
                assert_eq!(status, StatusCode::NOT_FOUND);
                assert_eq!(
                    value,
                    json!({"error":{"code":"not_found","message":"User not found."}})
                );
            }
        }
    };
    let (changed, _) = tokio::join!(update(&app, &a, json!({"public_username":next})), lookup);
    assert_eq!(changed.0, StatusCode::OK);
    assert_absent(&app, &b, &format!("/users/lookup?username={old}")).await;
    let (status, value) = call(
        &app,
        &b,
        "GET",
        &format!("/users/lookup?username={next}"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(value["public_username"], next);
}

#[tokio::test]
async fn own_mutation_read_avatar_and_admin_budgets_fail_closed_at_the_limit() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let a = register(&app).await;
    let admin = admin_token(&state, &a, "owner").await;
    let mut conn = state.redis.clone();
    for (scope, limit, method, path, payload, credential) in [
        ("read", 120, "GET", "/users/me/identity", None, None),
        (
            "write",
            10,
            "PUT",
            "/users/me/identity",
            Some(json!({"expected_revision":0,"discoverability":"nobody"})),
            None,
        ),
        (
            "avatar",
            120,
            "GET",
            "/users/00000000-0000-0000-0000-000000000001/avatar",
            None,
            None,
        ),
        (
            "admin",
            60,
            "GET",
            "/admin/identity",
            None,
            Some(admin.as_str()),
        ),
    ] {
        let _: () = redis::cmd("SET")
            .arg(format!("ratelimit:identity:{scope}:user:{}", a.id))
            .arg(limit)
            .arg("EX")
            .arg(60)
            .query_async(&mut conn)
            .await
            .unwrap();
        let response = app
            .clone()
            .oneshot(request(
                method,
                path,
                ip(),
                Some(&a.token),
                payload,
                credential,
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS, "{scope}");
    }
    let _: i64 = redis::cmd("DEL")
        .arg(format!("ratelimit:identity:read:user:{}", a.id))
        .query_async(&mut conn)
        .await
        .unwrap();
    let address = ip();
    let network = messenger_server::auth::rate_limit::auth_client_key(address.0.ip());
    let _: () = redis::cmd("SET")
        .arg(format!("ratelimit:identity:read:ip:{network}"))
        .arg(240)
        .arg("EX")
        .arg(60)
        .query_async(&mut conn)
        .await
        .unwrap();
    let response = app
        .clone()
        .oneshot(request(
            "GET",
            "/users/me/identity",
            address,
            Some(&a.token),
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
    let row: (String, i64) =
        sqlx::query_as("SELECT discoverability,revision FROM user_identities WHERE user_id=$1")
            .bind(a.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(
        row,
        ("everyone".into(), 0),
        "limited writes cannot change state"
    );
}

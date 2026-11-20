//! B-033 administrator/public-identity regressions. Explicit disposable loopback DB/Redis only; no dotenv,
//! object-storage operations, live push provider, or external SFU is used.
use std::{net::SocketAddr, sync::Arc};

use axum::{
    body::Body,
    extract::ConnectInfo,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use messenger_server::{
    auth::{jwt, middleware::AuthUser},
    config::{Config, S3Config, TurnConfig},
    state::AppState,
};
use serde_json::{json, Value};
use tower::ServiceExt;
use uuid::Uuid;

#[path = "common/safe_username.rs"]
mod safe_username;

async fn fixture() -> (AppState, axum::Router) {
    assert_eq!(
        std::env::var("COCO_LOGIN2_TEST_ISOLATED").as_deref(),
        Ok("1")
    );
    let database_url =
        std::env::var("TEST_DATABASE_URL").expect("explicit disposable B-033 DB required");
    let redis_url = std::env::var("TEST_REDIS_URL").expect("explicit disposable Redis required");
    for raw in [&database_url, &redis_url] {
        let url = reqwest::Url::parse(raw).unwrap();
        assert!(
            matches!(url.host_str(), Some("127.0.0.1" | "localhost" | "[::1]")),
            "loopback fixtures only"
        );
    }
    assert_eq!(
        reqwest::Url::parse(&database_url).unwrap().path(),
        "/coco_login2_review_test"
    );
    let redis_db: u8 = reqwest::Url::parse(&redis_url)
        .unwrap()
        .path()
        .trim_start_matches('/')
        .parse()
        .expect("explicit nonzero disposable Redis index required");
    assert_eq!(redis_db, 11);
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
        jwt_secret: "b033-fixture-only-secret".into(),
        trusted_proxies: vec![],
        jwt_issuer: "b033-fixture".into(),
        jwt_audience: "b033-fixture-apps".into(),
        access_token_ttl_seconds: 900,
        refresh_token_ttl_seconds: 86400,
        s3: S3Config {
            endpoint_url: "http://127.0.0.1:59000".into(),
            public_endpoint_url: "http://127.0.0.1:59000".into(),
            region: "test".into(),
            bucket: "b033-fixture".into(),
            access_key_id: "fixture".into(),
            secret_access_key: "fixture".into(),
            upload_access_key_id: "fixture".into(),
            upload_secret_access_key: "fixture".into(),
            max_upload_duration_seconds: 60,
            presign_ttl_seconds: 60,
        },
        turn: TurnConfig {
            stun_urls: vec![],
            turn_urls: vec![],
            shared_secret: "fixture".into(),
            credential_ttl_seconds: 60,
        },
        fcm: Default::default(),
    };
    let s3 = Arc::new(messenger_server::storage::S3Client::new(config.s3.clone()));
    let state = AppState {
        resources: Default::default(),
        db,
        redis,
        config,
        s3,
        ws_registry: Default::default(),
        push: messenger_server::push::disabled(),
    };
    let app = messenger_server::app(state.clone());
    (state, app)
}

struct Account {
    token: String,
    auth: AuthUser,
    login: String,
    public_username: Option<String>,
    number: String,
}

async fn send(
    app: &axum::Router,
    token: Option<&str>,
    method: &str,
    path: &str,
    payload: Option<Value>,
) -> (StatusCode, Value) {
    let octets: [u8; 3] = std::array::from_fn(|_| rand::random());
    send_from(
        app,
        token,
        method,
        path,
        payload,
        [10, octets[0], octets[1], octets[2]],
    )
    .await
}

async fn send_from(
    app: &axum::Router,
    token: Option<&str>,
    method: &str,
    path: &str,
    payload: Option<Value>,
    ip: [u8; 4],
) -> (StatusCode, Value) {
    let mut builder = Request::builder()
        .method(method)
        .uri(path)
        .extension(ConnectInfo(SocketAddr::from((ip, 12345))));
    if let Some(token) = token {
        builder = builder.header("authorization", format!("Bearer {token}"));
    }
    let request = if let Some(payload) = payload {
        builder
            .header("content-type", "application/json")
            .body(Body::from(payload.to_string()))
            .unwrap()
    } else {
        builder.body(Body::empty()).unwrap()
    };
    let response = app.clone().oneshot(request).await.unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    let value = serde_json::from_slice(&bytes)
        .unwrap_or_else(|_| panic!("non-JSON response: {}", String::from_utf8_lossy(&bytes)));
    (status, value)
}

async fn ok(
    app: &axum::Router,
    actor: &Account,
    method: &str,
    path: &str,
    payload: Option<Value>,
) -> Value {
    let (status, value) = send(app, Some(&actor.token), method, path, payload).await;
    assert_eq!(status, StatusCode::OK, "{method} {path}: {value}");
    value
}

async fn account(state: &AppState, app: &axum::Router, public: bool) -> Account {
    let login = safe_username::unique_username("private_");
    let (status, body) = send(app, None, "POST", "/auth/register", Some(json!({
        "username": login, "password": "b033-strong-fixture-password", "display_name": "Public Person",
        "installation_id": Uuid::new_v4(), "device_platform": "android", "device_name": "B-033 fixture"
    }))).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let token = body["access_token"].as_str().unwrap().to_owned();
    let claims = jwt::decode_access_token(&state.config, &token).unwrap();
    let mut actor = Account {
        token,
        auth: AuthUser {
            user_id: claims.sub,
            device_id: claims.device_id,
            session_id: claims.session_id,
        },
        login,
        public_username: None,
        number: String::new(),
    };
    let identity = ok(app, &actor, "GET", "/users/me/identity", None).await;
    actor.number = identity["unyri_number"].as_str().unwrap().to_owned();
    assert_eq!(actor.number.len(), 9);
    assert!(actor.number.bytes().all(|digit| digit.is_ascii_digit()));
    if public {
        let handle = safe_username::unique_username("public_");
        assert_ne!(handle, actor.login);
        let value = ok(
            app,
            &actor,
            "PUT",
            "/users/me/identity",
            Some(json!({"expected_revision": identity["revision"], "public_username": handle})),
        )
        .await;
        assert_eq!(value["unyri_number"], actor.number);
        actor.public_username = Some(handle);
    }
    actor
}

async fn authority(state: &AppState, actor: &Account, role: &str) -> String {
    sqlx::query("INSERT INTO server_administrators(user_id,role,secret,auth_policy) VALUES($1,$2,$3,'authenticator')")
        .bind(actor.auth.user_id).bind(role).bind(vec![1u8;32])
        .execute(&state.db).await.unwrap();
    let token = format!("{}aaaaaaaaaaa", Uuid::new_v4().simple());
    sqlx::query("INSERT INTO admin_sessions(token_hash,user_id,auth_session) VALUES($1,$2,$3)")
        .bind(messenger_server::admin::hash(&token))
        .bind(actor.auth.user_id)
        .bind(actor.auth.session_id)
        .execute(&state.db)
        .await
        .unwrap();
    token
}

async fn admin_send(
    app: &axum::Router,
    actor: &Account,
    admin: &str,
    method: &str,
    path: &str,
    payload: Option<Value>,
    peers: &[&Account],
) -> (StatusCode, Value) {
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(method)
                .uri(path)
                .extension(ConnectInfo(SocketAddr::from((
                    [10, rand::random(), rand::random(), rand::random()],
                    12345,
                ))))
                .header("authorization", format!("Bearer {}", actor.token))
                .header("x-coco-admin", admin)
                .header("content-type", "application/json")
                .body(
                    payload
                        .map(|v| Body::from(v.to_string()))
                        .unwrap_or_else(Body::empty),
                )
                .unwrap(),
        )
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    let raw = String::from_utf8(bytes.to_vec()).unwrap();
    for peer in peers {
        assert!(
            !raw.to_lowercase().contains(&peer.login.to_lowercase()),
            "private login in raw admin response: {method} {path}: {raw}"
        );
    }
    (
        status,
        if raw.is_empty() {
            Value::Null
        } else {
            serde_json::from_str(&raw).unwrap()
        },
    )
}

#[tokio::test]
async fn repair_is_audited_scoped_idempotent_and_scrubs_retained_identity() {
    let (state, app) = fixture().await;
    let owner = account(&state, &app, false).await;
    let public = account(&state, &app, true).await;
    let numbered = account(&state, &app, false).await;
    let absent = account(&state, &app, false).await;
    let untouched = account(&state, &app, false).await;
    let chosen = account(&state, &app, false).await;
    let chat = ok(
        &app,
        &owner,
        "POST",
        "/chats/direct",
        Some(json!({"other_user_id":public.auth.user_id})),
    )
    .await;
    let chat_id: Uuid = chat["chat_id"].as_str().unwrap().parse().unwrap();
    let report: Uuid = sqlx::query_scalar("INSERT INTO chat_reports(chat_id,reporter_user_id,reported_user_id,reason) VALUES($1,$2,$3,'harassment') RETURNING id")
        .bind(chat_id).bind(owner.auth.user_id).bind(public.auth.user_id).fetch_one(&state.db).await.unwrap();
    let old_snapshot = json!([
        {"id":public.auth.user_id,"username":public.login,"display_name":public.login},
        {"id":Uuid::new_v4(),"username":"deleted-private-login","display_name":"deleted-private-login"}
    ]);
    sqlx::query("UPDATE chat_reports SET participants_snapshot=$2 WHERE id=$1")
        .bind(report)
        .bind(old_snapshot)
        .execute(&state.db)
        .await
        .unwrap();
    let mut tx = state.db.begin().await.unwrap();
    for actor in [&public, &numbered, &absent, &untouched] {
        sqlx::query("UPDATE users SET display_name=username WHERE id=$1")
            .bind(actor.auth.user_id)
            .execute(&mut *tx)
            .await
            .unwrap();
    }
    for actor in [&public, &numbered, &absent, &chosen] {
        sqlx::query("INSERT INTO admin_audit(actor,action,target,reason,detail) VALUES($1,'profile:moderate',$2,'fixture','{\"display_name_cleared\":true}')")
            .bind(owner.auth.user_id).bind(actor.auth.user_id).execute(&mut *tx).await.unwrap();
    }
    sqlx::query("DELETE FROM user_identities WHERE user_id=$1")
        .bind(absent.auth.user_id)
        .execute(&mut *tx)
        .await
        .unwrap();
    let deleted = Uuid::new_v4();
    sqlx::query("INSERT INTO admin_audit(actor,action,target,reason,detail) VALUES($1,'account_delete',$2,'fixture','{\"username\":\"deleted-audit-private-login\"}')")
        .bind(owner.auth.user_id).bind(deleted).execute(&mut *tx).await.unwrap();
    let version: i64 = sqlx::query_scalar("SELECT profile_version FROM users WHERE id=$1")
        .bind(public.auth.user_id)
        .fetch_one(&mut *tx)
        .await
        .unwrap();
    sqlx::raw_sql(include_str!(
        "../migrations/0091_b033_repair_moderated_names.sql"
    ))
    .execute(&mut *tx)
    .await
    .unwrap();
    for (actor, expected) in [
        (
            &public,
            format!("@{}", public.public_username.as_ref().unwrap()),
        ),
        (
            &numbered,
            format!(
                "{}\u{202f}{}\u{202f}{}",
                &numbered.number[..3],
                &numbered.number[3..6],
                &numbered.number[6..]
            ),
        ),
        (&absent, String::new()),
        (&untouched, untouched.login.clone()),
        (&chosen, "Public Person".into()),
    ] {
        let name: String = sqlx::query_scalar("SELECT display_name FROM users WHERE id=$1")
            .bind(actor.auth.user_id)
            .fetch_one(&mut *tx)
            .await
            .unwrap();
        assert_eq!(name, expected);
    }
    let repaired_version: i64 = sqlx::query_scalar("SELECT profile_version FROM users WHERE id=$1")
        .bind(public.auth.user_id)
        .fetch_one(&mut *tx)
        .await
        .unwrap();
    assert_eq!(repaired_version, version + 1);
    sqlx::raw_sql(include_str!(
        "../migrations/0091_b033_repair_moderated_names.sql"
    ))
    .execute(&mut *tx)
    .await
    .unwrap();
    let repeated_version: i64 = sqlx::query_scalar("SELECT profile_version FROM users WHERE id=$1")
        .bind(public.auth.user_id)
        .fetch_one(&mut *tx)
        .await
        .unwrap();
    assert_eq!(repeated_version, repaired_version);
    let snapshot: Value =
        sqlx::query_scalar("SELECT participants_snapshot FROM chat_reports WHERE id=$1")
            .bind(report)
            .fetch_one(&mut *tx)
            .await
            .unwrap();
    assert!(!snapshot.to_string().contains(&public.login));
    assert!(!snapshot.to_string().contains("deleted-private-login"));
    assert_eq!(snapshot.as_array().unwrap().len(), 2);
    assert!(snapshot
        .as_array()
        .unwrap()
        .iter()
        .all(|p| p.get("username").is_none()));
    let detail: Value = sqlx::query_scalar(
        "SELECT detail FROM admin_audit WHERE target=$1 AND action='account_delete'",
    )
    .bind(deleted)
    .fetch_one(&mut *tx)
    .await
    .unwrap();
    assert_eq!(detail["identity"], deleted.to_string());
    assert!(!detail.to_string().contains("deleted-audit-private-login"));
    tx.rollback().await.unwrap();
}

#[tokio::test]
async fn every_admin_identity_surface_is_public_for_owners_and_moderators() {
    let (state, app) = fixture().await;
    let owner = account(&state, &app, true).await;
    let moderator = account(&state, &app, false).await;
    let target = account(&state, &app, true).await;
    let owner_admin = authority(&state, &owner, "owner").await;
    let mod_admin = authority(&state, &moderator, "moderator").await;
    let peers = [&owner, &moderator, &target];
    let unique_name = format!("Public fixture {}", target.auth.user_id);
    sqlx::query("UPDATE users SET display_name=$2 WHERE id=$1")
        .bind(target.auth.user_id)
        .bind(&unique_name)
        .execute(&state.db)
        .await
        .unwrap();
    let chat = ok(
        &app,
        &owner,
        "POST",
        "/chats/direct",
        Some(json!({"other_user_id":target.auth.user_id})),
    )
    .await;
    let chat_id: Uuid = chat["chat_id"].as_str().unwrap().parse().unwrap();
    sqlx::query("INSERT INTO chat_reports(chat_id,reporter_user_id,reported_user_id,reason) VALUES($1,$2,$3,'harassment')")
        .bind(chat_id).bind(owner.auth.user_id).bind(target.auth.user_id).execute(&state.db).await.unwrap();
    let snapshot: Value =
        sqlx::query_scalar("SELECT participants_snapshot FROM chat_reports WHERE chat_id=$1")
            .bind(chat_id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    for peer in peers {
        assert!(!snapshot.to_string().contains(&peer.login));
    }
    for (actor, admin) in [(&owner, &owner_admin), (&moderator, &mod_admin)] {
        for (method, path, payload) in [
            ("GET", format!("/admin/users?query={}", target.number), None),
            ("GET", format!("/admin/users/{}", target.auth.user_id), None),
            (
                "GET",
                format!("/admin/reports?reported_user_id={}", target.auth.user_id),
                None,
            ),
            (
                "POST",
                format!("/admin/users/{}/diagnostics", target.auth.user_id),
                Some(json!({"reason":"B-033 fixture"})),
            ),
        ] {
            let (status, value) =
                admin_send(&app, actor, admin, method, &path, payload, &peers).await;
            assert_eq!(status, StatusCode::OK, "{path}: {value}");
            if path.contains("/admin/users?") {
                assert_eq!(
                    value["items"][0]["public_username"],
                    json!(target.public_username)
                );
                assert_eq!(value["items"][0]["unyri_number"], target.number);
            }
        }
        for query in [target.login.clone(), target.login[..8].into()] {
            let (_, value) = admin_send(
                &app,
                actor,
                admin,
                "GET",
                &format!("/admin/users?query={query}"),
                None,
                &peers,
            )
            .await;
            assert!(
                value["items"].as_array().unwrap().is_empty(),
                "login search must be unavailable"
            );
        }
        for query in [
            target.auth.user_id.to_string(),
            format!("@{}", target.public_username.as_ref().unwrap()),
            format!("Public%20fixture%20{}", target.auth.user_id),
        ] {
            let (_, value) = admin_send(
                &app,
                actor,
                admin,
                "GET",
                &format!("/admin/users?query={query}"),
                None,
                &peers,
            )
            .await;
            assert!(value["items"]
                .as_array()
                .unwrap()
                .iter()
                .any(|r| r["user_id"] == json!(target.auth.user_id)));
        }
        let (status, _) =
            admin_send(&app, actor, admin, "GET", "/admin/operations", None, &peers).await;
        assert_eq!(
            status,
            if actor.auth.user_id == owner.auth.user_id {
                StatusCode::OK
            } else {
                StatusCode::NOT_FOUND
            }
        );
    }
    let (status, _) = admin_send(
        &app,
        &owner,
        &owner_admin,
        "POST",
        &format!("/admin/users/{}/delete", target.auth.user_id),
        Some(json!({"confirm_identity":target.login,"reason":"privacy fixture"})),
        &peers,
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    let (status, _) = admin_send(
        &app,
        &owner,
        &owner_admin,
        "POST",
        &format!("/admin/users/{}/delete", target.auth.user_id),
        Some(json!({"confirm_identity":target.number,"reason":"privacy fixture"})),
        &peers,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (_, reports) = admin_send(
        &app,
        &moderator,
        &mod_admin,
        "GET",
        &format!("/admin/reports?reported_user_id={}", target.auth.user_id),
        None,
        &peers,
    )
    .await;
    assert_eq!(
        reports["items"][0]["reported_public_username"],
        Value::Null
    );
    assert_eq!(reports["items"][0]["reported_unyri_number"],target.number);
    assert_eq!(reports["items"][0]["reported_display_name"],"Удалённый аккаунт");
    let (_, audit) = admin_send(
        &app,
        &owner,
        &owner_admin,
        "GET",
        "/admin/audit",
        None,
        &peers,
    )
    .await;
    let event = audit["items"]
        .as_array()
        .unwrap()
        .iter()
        .find(|row| {
            row["action"] == "account_delete" && row["target"] == json!(target.auth.user_id)
        })
        .unwrap();
    assert_eq!(event["detail"]["identity"], target.number);
    let no_number = account(&state, &app, false).await;
    sqlx::query("DELETE FROM user_identities WHERE user_id=$1")
        .bind(no_number.auth.user_id)
        .execute(&state.db)
        .await
        .unwrap();
    let (status, _) = admin_send(
        &app,
        &owner,
        &owner_admin,
        "POST",
        &format!("/admin/users/{}/delete", no_number.auth.user_id),
        Some(json!({"confirm_identity":no_number.auth.user_id,"reason":"UUID fallback fixture"})),
        &[&no_number],
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
}

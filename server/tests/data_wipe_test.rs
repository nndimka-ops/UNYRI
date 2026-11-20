//! Data deletion regressions. Explicit disposable loopback DB/Redis only; no real
//! storage, push provider, TURN, or application-server connection is used.
use axum::{
    body::Body,
    extract::ConnectInfo,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use messenger_server::{
    auth::{jwt, middleware::AuthUser},
    config::{Config, S3Config},
    state::AppState,
};
use serde_json::{json, Value};
use std::{net::SocketAddr, sync::Arc};
use tower::ServiceExt;
use uuid::Uuid;

async fn fixture() -> (AppState, axum::Router) {
    assert_eq!(
        std::env::var("COCO_DATA_WIPE_TEST_ISOLATED").as_deref(),
        Ok("1")
    );
    let database_url =
        std::env::var("TEST_DATABASE_URL").expect("explicit disposable data_wipe_test DB required");
    let redis_url = std::env::var("TEST_REDIS_URL").expect("explicit disposable Redis required");
    for raw in [&database_url, &redis_url] {
        let url = reqwest::Url::parse(raw).unwrap();
        assert!(matches!(
            url.host_str(),
            Some("127.0.0.1" | "localhost" | "[::1]")
        ));
    }
    assert_eq!(
        reqwest::Url::parse(&database_url).unwrap().path(),
        "/data_wipe_test"
    );
    let redis_db: usize = reqwest::Url::parse(&redis_url)
        .unwrap()
        .path()
        .trim_start_matches('/')
        .parse()
        .expect("explicit nonzero disposable Redis DB required");
    assert!(redis_db > 0, "default Redis DB is not a disposable fixture");
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
        jwt_secret: "batch3-only-test-secret".into(),
        trusted_proxies: vec![],
        jwt_issuer: "batch3".into(),
        jwt_audience: "batch3-test".into(),
        access_token_ttl_seconds: 900,
        refresh_token_ttl_seconds: 86400,
        s3: S3Config {
            endpoint_url: "http://127.0.0.1:59000".into(),
            public_endpoint_url: "http://127.0.0.1:59000".into(),
            region: "test".into(),
            bucket: "batch3-test".into(),
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
            shared_secret: "test".into(),
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

fn request(method: &str, path: &str, token: &str, body: Value) -> Request<Body> {
    let octets: [u8; 3] = std::array::from_fn(|_| rand::random());
    Request::builder()
        .method(method)
        .uri(path)
        .header("content-type", "application/json")
        .header("authorization", format!("Bearer {token}"))
        // In-process fixture identities avoid cross-test auth-network buckets;
        // every actual listener/DB/Redis connection remains loopback.
        .extension(ConnectInfo(SocketAddr::from((
            [10, octets[0], octets[1], octets[2]],
            12345,
        ))))
        .body(Body::from(body.to_string()))
        .unwrap()
}
async fn send(
    app: &axum::Router,
    method: &str,
    path: &str,
    token: &str,
    body: Value,
) -> (StatusCode, Value) {
    let response = app
        .clone()
        .oneshot(request(method, path, token, body))
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    )
}
struct User {
    token: String,
    refresh: String,
    username: String,
    auth: AuthUser,
    installation: Uuid,
}
async fn user(state: &AppState, app: &axum::Router) -> User {
    // Each fixture has a fresh IP so the full suite stays below per-IP sign-up limits.
    let username = format!("sec3_{}", &Uuid::new_v4().simple().to_string()[..16]);
    let installation = Uuid::new_v4();
    let req = json!({"username":username,"password":"batch3-strong-password","display_name":"Batch Three",
        "installation_id":installation,"device_platform":"android"});
    let mut raw = request("POST", "/auth/register", "", req);
    let octets: [u8; 3] = std::array::from_fn(|_| rand::random());
    raw.extensions_mut().insert(ConnectInfo(SocketAddr::from((
        [10, octets[0], octets[1], octets[2]],
        12345,
    ))));
    let response = app.clone().oneshot(raw).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body: Value =
        serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap();
    let token = body["access_token"].as_str().unwrap().to_owned();
    let claims = jwt::decode_access_token(&state.config, &token).unwrap();
    User {
        token,
        refresh: body["refresh_token"].as_str().unwrap().into(),
        username,
        installation,
        auth: AuthUser {
            user_id: claims.sub,
            device_id: claims.device_id,
            session_id: claims.session_id,
        },
    }
}
async fn login(state: &AppState, app: &axum::Router, owner: &User, installation: Uuid) -> User {
    let (status, body) = send(
        app,
        "POST",
        "/auth/login",
        "",
        json!({"username":owner.username,"password":"batch3-strong-password",
        "installation_id":installation,"device_platform":"android"}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let token = body["access_token"].as_str().unwrap().to_owned();
    let claims = jwt::decode_access_token(&state.config, &token).unwrap();
    User {
        token,
        refresh: body["refresh_token"].as_str().unwrap().into(),
        username: owner.username.clone(),
        installation,
        auth: AuthUser {
            user_id: claims.sub,
            device_id: claims.device_id,
            session_id: claims.session_id,
        },
    }
}
async fn chat(app: &axum::Router, a: &User, b: &User) -> Uuid {
    let (s, v) = send(
        app,
        "POST",
        "/chats/direct",
        &a.token,
        json!({"other_user_id":b.auth.user_id}),
    )
    .await;
    assert_eq!(s, StatusCode::OK, "{v}");
    v["chat_id"].as_str().unwrap().parse().unwrap()
}
async fn family(state: &AppState, user: &User) -> Uuid {
    sqlx::query_scalar("SELECT family_id FROM sessions WHERE id=$1")
        .bind(user.auth.session_id)
        .fetch_one(&state.db)
        .await
        .unwrap()
}

#[derive(Default)]
struct PushRecorder(std::sync::Mutex<Vec<(String, messenger_server::push::PushEvent)>>);
#[async_trait::async_trait]
impl messenger_server::push::PushProvider for PushRecorder {
    async fn send(
        &self,
        token: &str,
        event: &messenger_server::push::PushEvent,
    ) -> Result<messenger_server::push::PushSendOutcome, messenger_server::push::PushError> {
        self.0.lock().unwrap().push((token.to_owned(), event.clone()));
        Ok(messenger_server::push::PushSendOutcome::Delivered)
    }
}
async fn push_fixture() -> (AppState, axum::Router, Arc<PushRecorder>) {
    let (mut state, _) = fixture().await;
    let recorder = Arc::new(PushRecorder::default());
    state.push = messenger_server::push::build_with_provider(recorder.clone());
    let app = messenger_server::app(state.clone());
    (state, app, recorder)
}
async fn authority(state: &AppState, actor: &User, role: &str) -> String {
    sqlx::query("INSERT INTO server_administrators(user_id,role,secret,auth_policy) VALUES($1,$2,$3,'authenticator')")
        .bind(actor.auth.user_id).bind(role).bind(vec![1u8;32]).execute(&state.db).await.unwrap();
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
    actor: &User,
    admin: &str,
    path: &str,
    body: Value,
) -> (StatusCode, Value) {
    let mut req = request("POST", path, &actor.token, body);
    req.headers_mut()
        .insert("x-coco-admin", admin.parse().unwrap());
    let response = app.clone().oneshot(req).await.unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    )
}
async fn assert_terminal(state: &AppState, app: &axum::Router, account: &User, code: &str) {
    for (method, path, token, body) in [
        ("GET", "/users/me", account.token.as_str(), Value::Null),
        (
            "POST",
            "/auth/refresh",
            "",
            json!({"refresh_token":account.refresh}),
        ),
    ] {
        let (status, value) = send(app, method, path, token, body).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED, "{value}");
        assert_eq!(value["error"]["code"], code);
    }
    assert_eq!(
        messenger_server::auth::middleware::terminal_reason(
            &state.db,
            account.auth.user_id,
            account.auth.device_id,
            account.auth.session_id
        )
        .await
        .unwrap(),
        code
    );
}
/// A real DB trigger asserts enqueue order inside the destructive transaction,
/// including FK cascades; checking the final state alone cannot prove this.
async fn require_wake_before_retirement(
    state: &AppState,
    app: &axum::Router,
    account: &User,
) -> String {
    let token = format!("wipe-order-{}", Uuid::new_v4());
    assert_eq!(
        send(
            app,
            "PUT",
            "/devices/me/push-token",
            &account.token,
            json!({"provider":"fcm","token":token})
        )
        .await
        .0,
        StatusCode::OK
    );
    let function = format!("wipe_order_{}", Uuid::new_v4().simple());
    let sql=format!("CREATE FUNCTION {function}() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF OLD.token='{token}' AND (TG_OP='DELETE' OR NEW.invalidated_at IS NOT NULL) AND NOT EXISTS(SELECT 1 FROM session_check_wakes WHERE token=OLD.token) THEN RAISE EXCEPTION 'session check must precede token retirement'; END IF; IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW; END $$");
    sqlx::query(&sql).execute(&state.db).await.unwrap();
    sqlx::query(&format!("CREATE TRIGGER {function} BEFORE UPDATE OR DELETE ON push_tokens FOR EACH ROW EXECUTE FUNCTION {function}()"))
        .execute(&state.db).await.unwrap();
    token
}
async fn number(state: &AppState, account: &User) -> String {
    sqlx::query_scalar("SELECT unyri_number::text FROM user_identities WHERE user_id=$1")
        .bind(account.auth.user_id)
        .fetch_one(&state.db)
        .await
        .unwrap()
}

#[tokio::test]
async fn self_delete_anonymizes_snapshots_detaches_number_and_notifies_peers() {
    let (state, app, recorder) = push_fixture().await;
    let a = user(&state, &app).await;
    let b = user(&state, &app).await;
    let chat_id = chat(&app, &a, &b).await;
    let number = number(&state, &a).await;
    let (_, mut events, _) = state
        .ws_registry
        .register_session(b.auth.device_id, Some(family(&state, &b).await))
        .await;
    let (_, _, terminal) = state
        .ws_registry
        .register_session(a.auth.device_id, Some(family(&state, &a).await))
        .await;
    let route = require_wake_before_retirement(&state, &app, &a).await;
    let report:Uuid=sqlx::query_scalar("INSERT INTO chat_reports(chat_id,reporter_user_id,reported_user_id,reason) VALUES($1,$2,$3,'spam') RETURNING id")
        .bind(chat_id).bind(b.auth.user_id).bind(a.auth.user_id).fetch_one(&state.db).await.unwrap();
    sqlx::query("INSERT INTO admin_audit(actor,action,target,reason,detail) VALUES($1,'profile:moderate',$1,'kept reason',jsonb_build_object('display_name','Secret old name','username','Secret login'))")
        .bind(a.auth.user_id).execute(&state.db).await.unwrap();
    sqlx::query("INSERT INTO installation_suspensions(installation_id,source_user_id,actor,reason,until_date) VALUES($1,$2,$2,'old identifying metadata',now()-interval '1 second')")
        .bind(a.installation).bind(a.auth.user_id).execute(&state.db).await.unwrap();
    assert_eq!(
        send(
            &app,
            "POST",
            "/users/me/delete",
            &a.token,
            json!({"password":"batch3-strong-password"})
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    assert_terminal(&state, &app, &a, "account_deleted").await;
    let invalid = format!("{}.{}", a.auth.session_id, "A".repeat(43));
    assert_eq!(
        send(
            &app,
            "POST",
            "/auth/refresh",
            "",
            json!({"refresh_token":invalid})
        )
        .await
        .1["error"]["code"],
        "invalid_refresh_token"
    );
    assert_eq!(
        serde_json::from_str::<Value>(terminal.borrow().as_ref().unwrap()).unwrap()["reason"],
        "account_deleted"
    );
    let mut deleted = false;
    while let Ok(frame) = events.try_recv() {
        let event: Value = serde_json::from_str(&frame).unwrap();
        if event["type"] == "user.deleted" {
            assert_eq!(event["user_id"], a.auth.user_id.to_string());
            deleted = true;
        }
    }
    assert!(deleted, "peer must discard the cached profile/avatar");
    let snapshots: Value =
        sqlx::query_scalar("SELECT participants_snapshot FROM chat_reports WHERE id=$1")
            .bind(report)
            .fetch_one(&state.db)
            .await
            .unwrap();
    let participant = snapshots
        .as_array()
        .unwrap()
        .iter()
        .find(|p| p["id"] == a.auth.user_id.to_string())
        .unwrap();
    assert_eq!(participant["display_name"], "Удалённый аккаунт");
    assert_eq!(participant["unyri_number"], number);
    assert!(participant.get("username").is_none() && participant.get("public_username").is_none());
    let detail: Value = sqlx::query_scalar(
        "SELECT detail FROM admin_audit WHERE target=$1 AND action='profile:moderate'",
    )
    .bind(a.auth.user_id)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(detail, json!({"unyri_number":number,"identity":number}));
    let owner: Option<Uuid> =
        sqlx::query_scalar("SELECT user_id FROM public_numbers WHERE number=$1")
            .bind(number.parse::<i64>().unwrap())
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert!(owner.is_none());
    assert!(
        sqlx::query("UPDATE public_numbers SET user_id=$2 WHERE number=$1")
            .bind(number.parse::<i64>().unwrap())
            .bind(b.auth.user_id)
            .execute(&state.db)
            .await
            .is_err(),
        "detached number cannot be reassigned"
    );
    let ban: (Uuid, Uuid, String) = sqlx::query_as(
        "SELECT source_user_id,actor,reason FROM installation_suspensions WHERE installation_id=$1",
    )
    .bind(a.installation)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_ne!(ban.0, a.auth.user_id);
    assert_eq!(ban.1, Uuid::nil());
    assert_eq!(ban.2, "Deleted account");
    let queued: i64 = sqlx::query_scalar("SELECT count(*) FROM session_check_wakes WHERE token=$1")
        .bind(&route)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(queued, 1);
    state.push.process_due(&state.db).await;
    let sent = recorder.0.lock().unwrap();
    let own_sent: Vec<_> = sent.iter().filter(|(token, _)| token == &route).collect();
    assert_eq!(own_sent.len(), 1, "recorded pushes: {sent:?}");
    assert_eq!(own_sent[0].1, messenger_server::push::PushEvent::session_check());
    assert!(own_sent[0].1.data.is_empty());
    drop(sent);
    sqlx::query("UPDATE deleted_session_proofs SET expires_at=now()-interval '1 second' WHERE session_id=$1")
        .bind(a.auth.session_id).execute(&state.db).await.unwrap();
    assert_eq!(
        send(
            &app,
            "POST",
            "/auth/refresh",
            "",
            json!({"refresh_token":a.refresh})
        )
        .await
        .1["error"]["code"],
        "invalid_refresh_token",
        "expired proof no longer attests the deletion reason"
    );
}

#[tokio::test]
async fn owner_delete_requires_role_number_reason_and_returns_deleted_to_old_phone() {
    let (state, app, _) = push_fixture().await;
    let owner = user(&state, &app).await;
    let moderator = user(&state, &app).await;
    let victim = user(&state, &app).await;
    let authority = authority(&state, &owner, "owner").await;
    let moderator_token = self::authority(&state, &moderator, "moderator").await;
    let number = number(&state, &victim).await;
    let path = format!("/admin/users/{}/delete", victim.auth.user_id);
    require_wake_before_retirement(&state, &app, &victim).await;
    let body = json!({"confirm_identity":number,"reason":"fixture removal"});
    assert_eq!(
        admin_send(&app, &moderator, &moderator_token, &path, body.clone())
            .await
            .0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        admin_send(
            &app,
            &owner,
            &authority,
            &path,
            json!({"confirm_identity":"000000000","reason":"fixture"})
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        admin_send(
            &app,
            &owner,
            &authority,
            &path,
            json!({"confirm_identity":number,"reason":""})
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        admin_send(&app, &owner, &authority, &path, body).await.0,
        StatusCode::NO_CONTENT
    );
    assert_terminal(&state, &app, &victim, "account_deleted").await;
}

#[tokio::test]
async fn suspension_and_installation_ban_queue_before_retirement_and_have_no_password_oracle() {
    let (state, app, _) = push_fixture().await;
    let owner = user(&state, &app).await;
    let admin = authority(&state, &owner, "owner").await;
    for installation_ban in [false, true] {
        let account = user(&state, &app).await;
        require_wake_before_retirement(&state, &app, &account).await;
        let (_, _, terminal) = state
            .ws_registry
            .register_session(account.auth.device_id, Some(family(&state, &account).await))
            .await;
        let path = if installation_ban {
            format!("/admin/installations/{}/suspension", account.installation)
        } else {
            format!("/admin/users/{}/suspension", account.auth.user_id)
        };
        assert_eq!(
            admin_send(
                &app,
                &owner,
                &admin,
                &path,
                json!({"blocked":true,"reason":"fixture restriction"})
            )
            .await
            .0,
            StatusCode::OK
        );
        assert_terminal(&state, &app, &account, "account_suspended").await;
        assert_eq!(
            serde_json::from_str::<Value>(terminal.borrow().as_ref().unwrap()).unwrap()["reason"],
            "account_suspended"
        );
        let login = |password: &str| json!({"username":account.username,"password":password,"installation_id":Uuid::new_v4(),"device_platform":"android"});
        assert_eq!(
            send(&app, "POST", "/auth/login", "", login("wrong"))
                .await
                .1["error"]["code"],
            "invalid_credentials"
        );
        if !installation_ban {
            assert_eq!(
                send(
                    &app,
                    "POST",
                    "/auth/login",
                    "",
                    login("batch3-strong-password")
                )
                .await
                .1["error"]["code"],
                "account_suspended"
            );
        }
    }
}

#[tokio::test]
async fn inactivity_delete_uses_the_same_terminal_proof_and_wake_order() {
    let (state, app, _) = push_fixture().await;
    let a = user(&state, &app).await;
    require_wake_before_retirement(&state, &app, &a).await;
    sqlx::query("UPDATE users SET auto_delete_inactive_days=1,auto_delete_last_active_at=now()-interval '3 days',last_seen_at=now()-interval '3 days' WHERE id=$1")
        .bind(a.auth.user_id).execute(&state.db).await.unwrap();
    assert_eq!(
        messenger_server::users::auto_delete::sweep_inactive_accounts(&state)
            .await
            .unwrap(),
        1
    );
    assert_terminal(&state, &app, &a, "account_deleted").await;
}

#[tokio::test]
async fn ordinary_logout_and_device_revoke_keep_the_session_revoked_reason() {
    let (state, app) = fixture().await;
    let a = user(&state, &app).await;
    let b = login(&state, &app, &a, Uuid::new_v4()).await;
    let (_, _, terminal) = state
        .ws_registry
        .register_session(a.auth.device_id, Some(family(&state, &a).await))
        .await;
    assert_eq!(
        send(
            &app,
            "DELETE",
            &format!("/devices/{}", a.auth.device_id),
            &b.token,
            Value::Null
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    assert_terminal(&state, &app, &a, "session_revoked").await;
    assert_eq!(
        serde_json::from_str::<Value>(terminal.borrow().as_ref().unwrap()).unwrap()["reason"],
        "session_revoked"
    );
    assert_eq!(
        send(&app, "POST", "/auth/logout", &b.token, Value::Null)
            .await
            .0,
        StatusCode::NO_CONTENT
    );
    assert_terminal(&state, &app, &b, "session_revoked").await;
}

#[tokio::test]
async fn rollback_and_expiry_erase_queued_wake_capabilities() {
    let (state, app, recorder) = push_fixture().await;
    let a = user(&state, &app).await;
    let route = require_wake_before_retirement(&state, &app, &a).await;
    let mut tx = state.db.begin().await.unwrap();
    state
        .push
        .enqueue_session_check_transactional(&mut tx, &[a.auth.device_id])
        .await
        .unwrap();
    tx.rollback().await.unwrap();
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM session_check_wakes WHERE token=$1")
        .bind(&route)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(count, 0);
    state.push.process_due(&state.db).await;
    {
        let sent = recorder.0.lock().unwrap();
        assert!(
            sent.iter().all(|(token, _)| token != &route),
            "rolled-back wake was delivered; recorded pushes: {sent:?}"
        );
    }
    let mut tx = state.db.begin().await.unwrap();
    state
        .push
        .enqueue_session_check_transactional(&mut tx, &[a.auth.device_id])
        .await
        .unwrap();
    tx.commit().await.unwrap();
    let count: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM session_check_wakes WHERE token=$1 AND expires_at>now()",
    )
    .bind(&route)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(count, 1, "committed wake must exist before expiry");
    let expired = sqlx::query(
        "UPDATE session_check_wakes SET expires_at=now()-interval '1 second' WHERE token=$1",
    )
    .bind(&route)
    .execute(&state.db)
    .await
    .unwrap();
    assert_eq!(expired.rows_affected(), 1);
    state.push.process_due(&state.db).await;
    {
        // Other tests in this suite can leave valid wakes in the shared DB;
        // the global dispatcher may deliver those, but never this test's route.
        let sent = recorder.0.lock().unwrap();
        assert!(
            sent.iter().all(|(token, _)| token != &route),
            "expired wake was delivered; recorded pushes: {sent:?}"
        );
    }
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM session_check_wakes WHERE token=$1")
        .bind(&route)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(count, 0, "expired routing capability must be erased");
    sqlx::query("DELETE FROM deleted_session_proofs WHERE session_id=$1")
        .bind(a.auth.session_id)
        .execute(&state.db)
        .await
        .unwrap();
}

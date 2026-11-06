mod apns_common;
use apns_common::legacy::*;
use apns_common::*;
use axum::{
    extract::{Extension, State},
    http::StatusCode,
    routing::put as route_put,
    Json, Router,
};
use messenger_server::{
    auth::middleware::AuthUser,
    net::ClientIp,
    push::{handlers, PushEvent},
    state::AppState,
};
use serde_json::json;
use std::{sync::Arc, time::Duration};
use tower::ServiceExt;
use uuid::Uuid;

#[tokio::test]
async fn alert_dispatch_rechecks_session_owner_and_absolute_ttl() {
    let (_guard, state, transport, _fcm) = state().await;
    let app = messenger_server::app(state.clone());
    let user = ios(&app).await;
    let (sender, _, _) = register_user(&app, connect_info(), "scope").await;
    let chat: Uuid = create_direct_chat(&app, connect_info(), &sender, &user.user.to_string())
        .await
        .parse()
        .unwrap();
    put(&app, &user, intent("apns", Uuid::new_v4(), 1)).await;
    let event = PushEvent::new_message(chat, Uuid::new_v4(), 1);
    state
        .push
        .enqueue_for_users(&state.db, &[user.user], None, &event)
        .await;
    sqlx::query("UPDATE sessions SET expires_at=now()-interval '1 second' WHERE device_id=$1")
        .bind(user.device)
        .execute(&state.db)
        .await
        .unwrap();
    state.push.process_due(&state.db).await;
    assert_eq!(transport.count(), 0);
    sqlx::query("UPDATE sessions SET expires_at=now()+interval '1 hour' WHERE device_id=$1")
        .bind(user.device)
        .execute(&state.db)
        .await
        .unwrap();
    state
        .push
        .enqueue_for_users(&state.db, &[user.user], None, &event)
        .await;
    sqlx::query("UPDATE push_outbox SET created_at=now()-interval '2 days' WHERE device_id=$1 AND delivered_at IS NULL").bind(user.device).execute(&state.db).await.unwrap();
    state.push.process_due(&state.db).await;
    assert_eq!(transport.count(), 0);
    let other = ios(&app).await;
    state
        .push
        .enqueue_for_users(&state.db, &[user.user], None, &event)
        .await;
    sqlx::query("UPDATE push_outbox SET user_id=$2 WHERE device_id=$1 AND delivered_at IS NULL")
        .bind(user.device)
        .bind(other.user)
        .execute(&state.db)
        .await
        .unwrap();
    state.push.process_due(&state.db).await;
    assert_eq!(transport.count(), 0);
}

#[tokio::test]
async fn older_410_timestamp_does_not_retire_fresh_registration() {
    let (_guard, state, transport, _fcm) = state().await;
    let app = messenger_server::app(state.clone());
    let user = ios(&app).await;
    let (sender, _, _) = register_user(&app, connect_info(), "stamp").await;
    let chat: Uuid = create_direct_chat(&app, connect_info(), &sender, &user.user.to_string())
        .await
        .parse()
        .unwrap();
    put(&app, &user, intent("apns", Uuid::new_v4(), 1)).await;
    transport.response(
        410,
        json!({"reason":"Unregistered","timestamp":chrono::Utc::now().timestamp_millis()-60000}),
    );
    state
        .push
        .enqueue_for_users(
            &state.db,
            &[user.user],
            None,
            &PushEvent::new_message(chat, Uuid::new_v4(), 1),
        )
        .await;
    state.push.process_due(&state.db).await;
    assert!(active(&state, &user, "apns").await);
    assert_eq!(transport.count(), 1);
}

async fn same_install_login(app: &Router, user: &User) -> User {
    let response=app.clone().oneshot(post_json_request("/auth/login",connect_info(),"",json!({
        "username":user.username,"password":"correct-horse-battery","installation_id":user.installation,
        "device_platform":"ios","device_name":"Owned APNs fixture"
    }))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["device_id"], user.device.to_string());
    User {
        access: body["access_token"].as_str().unwrap().into(),
        user: user.user,
        device: user.device,
        username: user.username.clone(),
        installation: user.installation,
    }
}

async fn wait_advisory(db: &sqlx::PgPool, object: i64) {
    tokio::time::timeout(Duration::from_secs(5),async {
        loop {
            let waiting:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND objid::bigint=$1 AND NOT granted)").bind(object).fetch_one(db).await.unwrap();
            if waiting {return;}tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }).await.unwrap();
}

#[tokio::test]
async fn old_logout_preserves_newer_independent_same_device_login() {
    let (_guard, state, _transport, _fcm) = state().await;
    let app = messenger_server::app(state.clone());
    let user = ios(&app).await;
    put(&app, &user, intent("apns", Uuid::new_v4(), 1)).await;
    let newer = same_install_login(&app, &user).await;
    let new_id = Uuid::new_v4();
    put(&app, &newer, intent("apns", new_id, 2)).await;
    let response = app
        .clone()
        .oneshot(post_request("/auth/logout", connect_info(), &user.access))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    assert!(active(&state, &newer, "apns").await);
    let id: Uuid = sqlx::query_scalar(
        "SELECT registration_id FROM push_tokens WHERE device_id=$1 AND provider='apns'",
    )
    .bind(user.device)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(id, new_id);
}

#[tokio::test]
async fn logout_paused_after_revocation_cannot_later_invalidate_relogin() {
    let (_guard, state, _transport, _fcm) = state().await;
    let app = messenger_server::app(state.clone());
    let user = ios(&app).await;
    put(&app, &user, intent("apns", Uuid::new_v4(), 1)).await;
    sqlx::raw_sql(&format!("CREATE FUNCTION apns_qa_pause_retire() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(909100113); RETURN NEW; END $$; CREATE TRIGGER apns_qa_pause_retire BEFORE UPDATE ON push_tokens FOR EACH ROW WHEN (NEW.device_id='{}'::uuid) EXECUTE FUNCTION apns_qa_pause_retire();",user.device)).execute(&state.db).await.unwrap();
    let mut gate = state.db.acquire().await.unwrap();
    sqlx::query("SELECT pg_advisory_lock(909100113)")
        .execute(&mut *gate)
        .await
        .unwrap();
    let logout_app = app.clone();
    let access = user.access.clone();
    let logout = tokio::spawn(async move {
        logout_app
            .oneshot(post_request("/auth/logout", connect_info(), &access))
            .await
            .unwrap()
    });
    wait_advisory(&state.db, 909100113).await;
    let login_app = app.clone();
    let owner = user.clone();
    let login = tokio::spawn(async move { same_install_login(&login_app, &owner).await });
    tokio::time::timeout(Duration::from_secs(5),async {loop {
        let waiting:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'SELECT password_hash FROM users%FOR UPDATE%')").fetch_one(&state.db).await.unwrap();
        if waiting {break;}tokio::time::sleep(Duration::from_millis(20)).await;
    }}).await.unwrap();
    sqlx::query("SELECT pg_advisory_unlock(909100113)")
        .execute(&mut *gate)
        .await
        .unwrap();
    drop(gate);
    assert_eq!(logout.await.unwrap().status(), StatusCode::NO_CONTENT);
    let newer = login.await.unwrap();
    sqlx::raw_sql(
        "DROP TRIGGER apns_qa_pause_retire ON push_tokens; DROP FUNCTION apns_qa_pause_retire();",
    )
    .execute(&state.db)
    .await
    .unwrap();
    assert_eq!(
        put(&app, &newer, intent("apns", Uuid::new_v4(), 2))
            .await
            .status(),
        StatusCode::OK
    );
    assert!(active(&state, &newer, "apns").await);
}

async fn paused_delete(
    State(state): State<AppState>,
    Extension(hold): Extension<Arc<Hold>>,
    auth: AuthUser,
    query: axum::extract::Query<handlers::DeletePushTokenQuery>,
) -> Result<StatusCode, messenger_server::error::AppError> {
    hold.entered.notify_one();
    hold.resume.notified().await;
    handlers::delete_push_token(State(state), auth, query).await
}

#[tokio::test]
async fn admitted_old_delete_after_logout_cannot_clear_new_login_registration() {
    let (_guard, state, _transport, _fcm) = state().await;
    let app = messenger_server::app(state.clone());
    let user = ios(&app).await;
    put(&app, &user, intent("apns", Uuid::new_v4(), 1)).await;
    let hold = Hold::new();
    let paused = Router::new()
        .route(
            "/devices/me/push-token",
            axum::routing::delete(paused_delete),
        )
        .layer(Extension(hold.clone()))
        .with_state(state.clone());
    let req = axum::http::Request::builder()
        .method("DELETE")
        .uri("/devices/me/push-token")
        .header("authorization", format!("Bearer {}", user.access))
        .extension(connect_info())
        .body(axum::body::Body::empty())
        .unwrap();
    let task = tokio::spawn(async move { paused.oneshot(req).await.unwrap() });
    tokio::time::timeout(Duration::from_secs(5), hold.entered.notified())
        .await
        .unwrap();
    assert_eq!(
        app.clone()
            .oneshot(post_request("/auth/logout", connect_info(), &user.access))
            .await
            .unwrap()
            .status(),
        StatusCode::NO_CONTENT
    );
    let newer = same_install_login(&app, &user).await;
    put(&app, &newer, intent("apns", Uuid::new_v4(), 2)).await;
    hold.resume.notify_one();
    assert_eq!(task.await.unwrap().status(), StatusCode::UNAUTHORIZED);
    assert!(active(&state, &newer, "apns").await);
}

#[tokio::test]
async fn admin_exclusive_device_retirement_blocks_new_slot_before_session_lock() {
    let (_guard, state, _transport, _fcm) = state().await;
    let app = messenger_server::app(state.clone());
    let user = ios(&app).await;
    let mut admin = state.db.begin().await.unwrap();
    messenger_server::auth::admission::lock(&mut admin, true)
        .await
        .unwrap();
    sqlx::query("SELECT id FROM devices WHERE id=$1 FOR UPDATE")
        .bind(user.device)
        .fetch_all(&mut *admin)
        .await
        .unwrap();
    let request = put_json_request(
        "/devices/me/push-token",
        connect_info(),
        &user.access,
        intent("apns", Uuid::new_v4(), 1),
    );
    let task = tokio::spawn(async move { app.oneshot(request).await.unwrap() });
    wait_advisory(&state.db, 29).await;
    sqlx::query("UPDATE devices SET revoked_at=now() WHERE id=$1")
        .bind(user.device)
        .execute(&mut *admin)
        .await
        .unwrap();
    sqlx::query("UPDATE sessions SET revoked_at=now() WHERE device_id=$1")
        .bind(user.device)
        .execute(&mut *admin)
        .await
        .unwrap();
    admin.commit().await.unwrap();
    assert_eq!(task.await.unwrap().status(), StatusCode::UNAUTHORIZED);
    assert!(!active(&state, &user, "apns").await);
}

#[tokio::test]
async fn real_put_holds_shared_admission_until_insert_then_admin_drains_without_cycle() {
    let (_guard, state, _transport, _fcm) = state().await;
    let app = messenger_server::app(state.clone());
    let user = ios(&app).await;
    // One owned-fixture trigger pauses the actual INSERT after its real locks.
    sqlx::raw_sql(&format!("CREATE FUNCTION apns_qa_pause_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(909100112); RETURN NEW; END $$; CREATE TRIGGER apns_qa_pause_insert BEFORE INSERT ON push_tokens FOR EACH ROW WHEN (NEW.device_id='{}'::uuid) EXECUTE FUNCTION apns_qa_pause_insert();",user.device)).execute(&state.db).await.unwrap();
    let mut gate = state.db.acquire().await.unwrap();
    sqlx::query("SELECT pg_advisory_lock(909100112)")
        .execute(&mut *gate)
        .await
        .unwrap();
    let req = put_json_request(
        "/devices/me/push-token",
        connect_info(),
        &user.access,
        intent("apns", Uuid::new_v4(), 1),
    );
    let task = tokio::spawn(async move { app.oneshot(req).await.unwrap() });
    wait_advisory(&state.db, 909100112).await;
    let db = state.db.clone();
    let device = user.device;
    let admin = tokio::spawn(async move {
        let mut tx = db.begin().await.unwrap();
        messenger_server::auth::admission::lock(&mut tx, true)
            .await
            .unwrap();
        sqlx::query("SELECT id FROM devices WHERE id=$1 FOR UPDATE")
            .bind(device)
            .fetch_all(&mut *tx)
            .await
            .unwrap();
        sqlx::query("UPDATE devices SET revoked_at=now() WHERE id=$1")
            .bind(device)
            .execute(&mut *tx)
            .await
            .unwrap();
        sqlx::query("UPDATE sessions SET revoked_at=now() WHERE device_id=$1")
            .bind(device)
            .execute(&mut *tx)
            .await
            .unwrap();
        sqlx::query("UPDATE push_tokens SET invalidated_at=now() WHERE device_id=$1")
            .bind(device)
            .execute(&mut *tx)
            .await
            .unwrap();
        tx.commit().await.unwrap();
    });
    wait_advisory(&state.db, 29).await;
    sqlx::query("SELECT pg_advisory_unlock(909100112)")
        .execute(&mut *gate)
        .await
        .unwrap();
    drop(gate);
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(5), task)
            .await
            .unwrap()
            .unwrap()
            .status(),
        StatusCode::OK
    );
    tokio::time::timeout(Duration::from_secs(5), admin)
        .await
        .unwrap()
        .unwrap();
    assert!(!active(&state, &user, "apns").await);
    sqlx::raw_sql(
        "DROP TRIGGER apns_qa_pause_insert ON push_tokens; DROP FUNCTION apns_qa_pause_insert();",
    )
    .execute(&state.db)
    .await
    .unwrap();
}

#[tokio::test]
async fn typed_slots_preserve_fcm_and_reject_wrong_platform_or_context() {
    let (_guard, state, _transport, _fcm) = state().await;
    let app = messenger_server::app(state.clone());
    let user = ios(&app).await;
    register_push_token(&app, connect_info(), &user.access, "legacy-fcm").await;
    assert_eq!(
        put(&app, &user, intent("apns", Uuid::new_v4(), 1))
            .await
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        put(&app, &user, intent("apns_voip", Uuid::new_v4(), 1))
            .await
            .status(),
        StatusCode::OK
    );
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM push_tokens WHERE device_id=$1")
        .bind(user.device)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(count, 3);
    let mut wrong = intent("apns", Uuid::new_v4(), 2);
    wrong["topic"] = json!("arbitrary.topic");
    assert_eq!(
        put(&app, &user, wrong).await.status(),
        StatusCode::SERVICE_UNAVAILABLE
    );
    let mut wrong = intent("apns", Uuid::new_v4(), 2);
    wrong["environment"] = json!("sandbox");
    assert_eq!(
        put(&app, &user, wrong).await.status(),
        StatusCode::SERVICE_UNAVAILABLE
    );
    let (access, _, _) = register_user(&app, connect_info(), "android").await;
    let response = app
        .oneshot(put_json_request(
            "/devices/me/push-token",
            connect_info(),
            &access,
            intent("apns", Uuid::new_v4(), 1),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn revision_cas_idempotence_and_exact_role_retirement() {
    let (_guard, state, _transport, _fcm) = state().await;
    let app = messenger_server::app(state.clone());
    let user = ios(&app).await;
    let first = Uuid::new_v4();
    let second = Uuid::new_v4();
    let voip = Uuid::new_v4();
    assert_eq!(
        put(&app, &user, intent("apns", first, 1)).await.status(),
        StatusCode::OK
    );
    let stamp: chrono::DateTime<chrono::Utc> = sqlx::query_scalar(
        "SELECT token_registered_at FROM push_tokens WHERE device_id=$1 AND provider='apns'",
    )
    .bind(user.device)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(
        put(&app, &user, intent("apns", first, 1)).await.status(),
        StatusCode::OK
    );
    let after: chrono::DateTime<chrono::Utc> = sqlx::query_scalar(
        "SELECT token_registered_at FROM push_tokens WHERE device_id=$1 AND provider='apns'",
    )
    .bind(user.device)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(stamp, after);
    assert_eq!(
        put(&app, &user, intent("apns", second, 2)).await.status(),
        StatusCode::OK
    );
    assert_eq!(
        put(&app, &user, intent("apns_voip", voip, 1))
            .await
            .status(),
        StatusCode::OK
    );
    let stale = body_json(put(&app, &user, intent("apns", first, 1)).await).await;
    assert_eq!(stale["error"]["code"], "push_registration_stale");
    assert_eq!(stale["error"]["current_revision"], 2);
    assert_eq!(
        delete(&app, &user, "apns", first, 1).await.status(),
        StatusCode::NO_CONTENT
    );
    assert!(active(&state, &user, "apns").await);
    assert_eq!(
        delete(&app, &user, "apns", second, 2).await.status(),
        StatusCode::NO_CONTENT
    );
    assert!(!active(&state, &user, "apns").await);
    assert!(active(&state, &user, "apns_voip").await);
    let retired = body_json(put(&app, &user, intent("apns", second, 2)).await).await;
    assert_eq!(retired["error"]["code"], "push_registration_retired");
}

#[tokio::test]
async fn delete_before_delayed_put_persists_absent_and_lower_revision_tombstones() {
    let (_guard, state, _transport, _fcm) = state().await;
    let app = messenger_server::app(state.clone());
    let user = ios(&app).await;
    for revision in [1, 3] {
        if revision == 3 {
            assert_eq!(
                put(&app, &user, intent("apns", Uuid::new_v4(), 2))
                    .await
                    .status(),
                StatusCode::OK
            );
        }
        let id = Uuid::new_v4();
        assert_eq!(
            delete(&app, &user, "apns", id, revision).await.status(),
            StatusCode::NO_CONTENT
        );
        let response = put(&app, &user, intent("apns", id, revision)).await;
        assert_eq!(response.status(), StatusCode::CONFLICT);
        assert_eq!(
            body_json(response).await["error"]["code"],
            "push_registration_retired"
        );
        assert!(!active(&state, &user, "apns").await);
    }
    let latest = Uuid::new_v4();
    assert_eq!(
        put(&app, &user, intent("apns", latest, 4)).await.status(),
        StatusCode::OK
    );
    delete(&app, &user, "apns", Uuid::new_v4(), 4).await;
    assert!(active(&state, &user, "apns").await);
    // A tokenless active row cannot exploit SQL CHECK's NULL/unknown semantics.
    let invalid=sqlx::query("UPDATE push_tokens SET token='',environment=NULL,topic=NULL,invalidated_at=NULL WHERE device_id=$1 AND provider='apns'").bind(user.device).execute(&state.db).await;
    assert!(invalid.is_err());
}

async fn paused_put(
    State(state): State<AppState>,
    Extension(hold): Extension<Arc<Hold>>,
    auth: AuthUser,
    ip: ClientIp,
    Json(req): Json<handlers::RegisterPushTokenRequest>,
) -> Result<Json<handlers::RegisterPushTokenResponse>, messenger_server::error::AppError> {
    // Actual AuthUser extraction already completed; no production test hook.
    hold.entered.notify_one();
    hold.resume.notified().await;
    handlers::register_push_token(State(state), ip, auth, Json(req)).await
}

#[tokio::test]
async fn admitted_http_put_cannot_resurrect_after_logout_or_earlier_delete() {
    let (_guard, state, _transport, _fcm) = state().await;
    let app = messenger_server::app(state.clone());
    let user = ios(&app).await;
    for logout in [false, true] {
        let hold = Hold::new();
        let paused = Router::new()
            .route("/devices/me/push-token", route_put(paused_put))
            .layer(Extension(hold.clone()))
            .with_state(state.clone());
        let id = Uuid::new_v4();
        let req = put_json_request(
            "/devices/me/push-token",
            connect_info(),
            &user.access,
            intent("apns", id, if logout { 2 } else { 1 }),
        );
        let task = tokio::spawn(async move { paused.oneshot(req).await.unwrap() });
        tokio::time::timeout(Duration::from_secs(5), hold.entered.notified())
            .await
            .unwrap();
        if logout {
            assert_eq!(
                app.clone()
                    .oneshot(post_request("/auth/logout", connect_info(), &user.access))
                    .await
                    .unwrap()
                    .status(),
                StatusCode::NO_CONTENT
            );
        } else {
            delete(&app, &user, "apns", id, 1).await;
        }
        hold.resume.notify_one();
        let response = task.await.unwrap();
        assert_eq!(
            response.status(),
            if logout {
                StatusCode::UNAUTHORIZED
            } else {
                StatusCode::CONFLICT
            }
        );
        assert!(!active(&state, &user, "apns").await);
    }
}

#[tokio::test]
async fn late_410_and_success_do_not_modify_same_token_new_registration() {
    let (_guard, state, transport, _fcm) = state().await;
    let app = messenger_server::app(state.clone());
    let user = ios(&app).await;
    let (sender, _, _) = register_user(&app, connect_info(), "send").await;
    let chat: Uuid = create_direct_chat(&app, connect_info(), &sender, &user.user.to_string())
        .await
        .parse()
        .unwrap();
    for (index, status) in [410, 200].into_iter().enumerate() {
        let revision = index as i64 * 2 + 1;
        put(&app, &user, intent("apns", Uuid::new_v4(), revision)).await;
        let event = PushEvent::new_message(chat, Uuid::new_v4(), 1);
        state
            .push
            .enqueue_for_users(&state.db, &[user.user], None, &event)
            .await;
        let hold = Hold::new();
        *transport.hold.lock().unwrap() = Some(hold.clone());
        transport.response(status,json!({"reason":"Unregistered","timestamp":chrono::Utc::now().timestamp_millis()+10000}));
        let worker = state.clone();
        let task = tokio::spawn(async move { worker.push.process_due(&worker.db).await });
        tokio::time::timeout(Duration::from_secs(5), hold.entered.notified())
            .await
            .unwrap();
        put(&app, &user, intent("apns", Uuid::new_v4(), revision + 1)).await;
        hold.resume.notify_one();
        task.await.unwrap();
        *transport.hold.lock().unwrap() = None;
        assert!(active(&state, &user, "apns").await);
        let success:bool=sqlx::query_scalar("SELECT last_success_at IS NOT NULL FROM push_tokens WHERE device_id=$1 AND provider='apns'").bind(user.device).fetch_one(&state.db).await.unwrap();
        assert!(!success);
    }
}

#[tokio::test]
async fn queued_message_rechecks_mute_preferences_and_keeps_fcm_independent() {
    let (_guard, state, transport, fcm) = state().await;
    let app = messenger_server::app(state.clone());
    let user = ios(&app).await;
    let (sender, _, _) = register_user(&app, connect_info(), "muter").await;
    let chat: Uuid = create_direct_chat(&app, connect_info(), &sender, &user.user.to_string())
        .await
        .parse()
        .unwrap();
    register_push_token(&app, connect_info(), &user.access, "legacy-fcm").await;
    put(&app, &user, intent("apns", Uuid::new_v4(), 1)).await;
    let event = PushEvent::new_message(chat, Uuid::new_v4(), 1);
    state
        .push
        .enqueue_for_users(&state.db, &[user.user], None, &event)
        .await;
    sqlx::query("INSERT INTO chat_preferences(chat_id,user_id,is_muted) VALUES($1,$2,true)")
        .bind(chat)
        .bind(user.user)
        .execute(&state.db)
        .await
        .unwrap();
    state.push.process_due(&state.db).await;
    assert_eq!(transport.count(), 0);
    assert_eq!(fcm.recorded().len(), 1);
    sqlx::query("UPDATE chat_preferences SET muted_until=now()-interval '1 second' WHERE chat_id=$1 AND user_id=$2").bind(chat).bind(user.user).execute(&state.db).await.unwrap();
    state
        .push
        .enqueue_for_users(&state.db, &[user.user], None, &event)
        .await;
    state.push.process_due(&state.db).await;
    assert_eq!(transport.count(), 1);
    let mut disabled = intent("apns", Uuid::new_v4(), 2);
    disabled["preferences"]["message_alert_enabled"] = json!(false);
    put(&app, &user, disabled).await;
    state
        .push
        .enqueue_for_users(&state.db, &[user.user], None, &event)
        .await;
    state.push.process_due(&state.db).await;
    assert_eq!(transport.count(), 1);
}

#[tokio::test]
async fn incoming_call_is_voip_only_and_dead_call_never_retries_as_new_ring() {
    let (_guard, state, transport, _fcm) = state().await;
    let app = messenger_server::app(state.clone());
    let user = ios(&app).await;
    let (sender, _, _) = register_user(&app, connect_info(), "caller").await;
    create_direct_chat(&app, connect_info(), &sender, &user.user.to_string()).await;
    put(&app, &user, intent("apns", Uuid::new_v4(), 1)).await;
    put(&app, &user, intent("apns_voip", Uuid::new_v4(), 1)).await;
    let response = app
        .clone()
        .oneshot(post_json_request(
            "/calls",
            connect_info(),
            &sender,
            json!({"callee_user_id":user.user,"media_kind":"audio"}),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let call = body_json(response).await;
    let id = call["id"].as_str().unwrap();
    let roles: Vec<String> =
        sqlx::query_scalar("SELECT provider::text FROM push_outbox WHERE device_id=$1")
            .bind(user.device)
            .fetch_all(&state.db)
            .await
            .unwrap();
    assert_eq!(roles, vec!["apns_voip"]);
    transport.response(503, json!({"reason":"ServiceUnavailable"}));
    state.push.process_due(&state.db).await;
    {
        let requests = transport.requests.lock().unwrap();
        assert_eq!(requests.len(), 1);
        assert_eq!(requests[0].push_type, "voip");
        assert_eq!(requests[0].expiration, 0);
    }
    app.clone()
        .oneshot(post_request(
            &format!("/calls/{id}/cancel"),
            connect_info(),
            &sender,
        ))
        .await
        .unwrap();
    sqlx::query("UPDATE push_outbox SET next_attempt_at=now() WHERE device_id=$1")
        .bind(user.device)
        .execute(&state.db)
        .await
        .unwrap();
    state.push.process_due(&state.db).await;
    assert_eq!(transport.count(), 1);
}

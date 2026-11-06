//! Explicitly opted-in tests: never use DATABASE_URL or the normal test DB.

#[tokio::test]
#[ignore = "requires dedicated conference fixture services"]
async fn isolated_signaling_budget_is_shared_by_fresh_tokens_and_preserves_authorization_grace() {
    let (state,_sfu,fixture)=isolated_fixture().await;
    let owner=fixture_user(&state.db).await;
    let room=create(State(state.clone()),owner.clone(),Json(create_request(2))).await.unwrap().0;
    let first=join(State(state.clone()),owner.clone(),Path(room.id),Json(join_request())).await.unwrap().0;
    for _ in 0..60 {
        assert_eq!(authorize_sfu(State(state.clone()),gate_headers(&first.participant_token)).await.unwrap(),StatusCode::NO_CONTENT);
    }
    let authorized:DateTime<Utc>=sqlx::query_scalar("SELECT last_authorized_at FROM conference_members WHERE user_id=$1").bind(owner.user_id).fetch_one(&state.db).await.unwrap();
    assert!(matches!(authorize_sfu(State(state.clone()),gate_headers(&first.participant_token)).await,Err(AppError::RateLimited)));
    tokio::time::sleep(std::time::Duration::from_millis(1100)).await;
    let fresh=join(State(state.clone()),owner.clone(),Path(room.id),Json(join_request())).await.unwrap().0;
    assert_ne!(fresh.participant_token,first.participant_token);
    let renewed:DateTime<Utc>=sqlx::query_scalar("SELECT last_authorized_at FROM conference_members WHERE user_id=$1").bind(owner.user_id).fetch_one(&state.db).await.unwrap();
    assert!(renewed>=authorized);
    assert!(matches!(authorize_sfu(State(state.clone()),gate_headers(&fresh.participant_token)).await,Err(AppError::RateLimited)));
    let after:DateTime<Utc>=sqlx::query_scalar("SELECT last_authorized_at FROM conference_members WHERE user_id=$1").bind(owner.user_id).fetch_one(&state.db).await.unwrap();
    assert_eq!(after,renewed,"rejected proofs must perform no timestamp writes");
    fixture.abort();
}

#[tokio::test]
#[ignore = "requires dedicated conference fixture services"]
async fn isolated_repeated_blocks_do_not_sweep_unrelated_rooms() {
    let (state,sfu,fixture)=isolated_fixture().await;
    let a=fixture_user(&state.db).await;let b=fixture_user(&state.db).await;
    let c=fixture_user(&state.db).await;let d=fixture_user(&state.db).await;
    let affected=create(State(state.clone()),a.clone(),Json(create_request(2))).await.unwrap().0;
    join(State(state.clone()),a.clone(),Path(affected.id),Json(join_request())).await.unwrap();
    join(State(state.clone()),b.clone(),Path(affected.id),Json(join_request())).await.unwrap();
    let unrelated=create(State(state.clone()),c.clone(),Json(create_request(2))).await.unwrap().0;
    join(State(state.clone()),c.clone(),Path(unrelated.id),Json(join_request())).await.unwrap();
    join(State(state.clone()),d.clone(),Path(unrelated.id),Json(join_request())).await.unwrap();
    let before=sfu.requests.lock().unwrap().len();
    crate::users::blocks::set_block(&state,&a,b.user_id,true,None).await.unwrap();
    let changed=sfu.requests.lock().unwrap()[before..].to_vec();
    assert_eq!(changed.iter().filter(|(method,_)|method=="DeleteRoom").count(),1);
    assert!(!changed.iter().any(|(method,_)|method=="ListParticipants"));
    let status:String=sqlx::query_scalar("SELECT state FROM conferences WHERE id=$1").bind(unrelated.id).fetch_one(&state.db).await.unwrap();assert_eq!(status,"open");
    let closed:String=sqlx::query_scalar("SELECT state FROM conferences WHERE id=$1").bind(affected.id).fetch_one(&state.db).await.unwrap();assert_eq!(closed,"ended");
    let count=sfu.requests.lock().unwrap().len();
    crate::users::blocks::set_block(&state,&a,b.user_id,true,None).await.unwrap();
    assert_eq!(sfu.requests.lock().unwrap().len(),count,"idempotent block must not trigger room RPCs");
    fixture.abort();
}
#[path = "discovery_tests.rs"]
mod discovery_tests;
#[path = "approval_tests.rs"]
mod approval_tests;
use super::*;
use crate::config::{Config, S3Config, TurnConfig};
use axum::response::IntoResponse;
use http_body_util::BodyExt;
use std::future::IntoFuture;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};
use tower::ServiceExt;

#[derive(Clone, Default)]
struct Sfu {
    requests: Arc<Mutex<Vec<(String, serde_json::Value)>>>,
    fail_delete: Arc<AtomicBool>,
    fail_update: Arc<AtomicBool>,
    fail_remove: Arc<AtomicBool>,
    roster: Arc<Mutex<Option<Vec<serde_json::Value>>>>,
    fail_roster: Arc<AtomicBool>,
    missing_roster: Arc<AtomicBool>,
    proxy_missing_roster: Arc<AtomicBool>,
    unauthorized_roster: Arc<AtomicBool>,
    forbidden_roster: Arc<AtomicBool>,
    hold_remove: Arc<AtomicBool>,
    remove_started: Arc<tokio::sync::Notify>,
    continue_remove: Arc<tokio::sync::Notify>,
}
async fn fixture_sfu(
    State(sfu): State<Sfu>,
    Path(method): Path<String>,
    Json(body): Json<serde_json::Value>,
) -> axum::response::Response {
    sfu.requests.lock().unwrap().push((method.clone(), body));
    if method == "RemoveParticipant" && sfu.hold_remove.load(Ordering::SeqCst) {
        sfu.remove_started.notify_one();
        sfu.continue_remove.notified().await;
    }
    if method == "ListParticipants" {
        if sfu.missing_roster.load(Ordering::SeqCst) {
            return (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({"code":"not_found","msg":"room not found"})),
            )
                .into_response();
        }
        if sfu.proxy_missing_roster.load(Ordering::SeqCst) {
            return (StatusCode::NOT_FOUND, "proxy route not found").into_response();
        }
        if sfu.forbidden_roster.load(Ordering::SeqCst) {
            return StatusCode::FORBIDDEN.into_response();
        }
        if sfu.unauthorized_roster.load(Ordering::SeqCst) {
            return StatusCode::UNAUTHORIZED.into_response();
        }
    }
    if (method == "DeleteRoom" && sfu.fail_delete.load(Ordering::SeqCst))
        || (method == "UpdateParticipant" && sfu.fail_update.load(Ordering::SeqCst))
        || (method == "RemoveParticipant" && sfu.fail_remove.load(Ordering::SeqCst))
        || (method == "ListParticipants" && sfu.fail_roster.load(Ordering::SeqCst))
    {
        StatusCode::SERVICE_UNAVAILABLE.into_response()
    } else if method == "ListParticipants" {
        let roster = sfu.roster.lock().unwrap().clone();
        match roster {
            Some(participants) => {
                Json(serde_json::json!({"participants":participants})).into_response()
            }
            None => StatusCode::OK.into_response(),
        }
    } else {
        StatusCode::OK.into_response()
    }
}
async fn fixture_user(db: &sqlx::PgPool) -> AuthUser {
    let auth = AuthUser {
        user_id: Uuid::new_v4(),
        device_id: Uuid::new_v4(),
        session_id: Uuid::new_v4(),
    };
    sqlx::query("INSERT INTO users(id,username,display_name,password_hash) VALUES($1,$2,'Conference fixture','fixture')").bind(auth.user_id).bind(format!("cf{}",Uuid::new_v4().simple()).chars().take(32).collect::<String>()).execute(db).await.unwrap();
    sqlx::query("INSERT INTO devices(id,user_id,platform,signal_device_id,installation_id) VALUES($1,$2,'android',1,$3)").bind(auth.device_id).bind(auth.user_id).bind(Uuid::new_v4()).execute(db).await.unwrap();
    fixture_session(db, &auth).await;
    auth
}
async fn fixture_session(db: &sqlx::PgPool, auth: &AuthUser) {
    sqlx::query("INSERT INTO sessions(id,user_id,device_id,refresh_token_hash,expires_at) VALUES($1,$2,$3,$4,now()+interval '1 day')").bind(auth.session_id).bind(auth.user_id).bind(auth.device_id).bind(Uuid::new_v4().to_string()).execute(db).await.unwrap();
}
fn gate_headers(token: &str) -> HeaderMap {
    let mut headers = HeaderMap::new();
    headers.insert("x-conference-token", token.parse().unwrap());
    headers
}
fn join_request() -> JoinRequest {
    JoinRequest {
        invite_secret: Some(URL_SAFE_NO_PAD.encode([7u8; 32])),
        ..Default::default()
    }
}
fn create_request(capacity: i16) -> CreateRequest {
    CreateRequest {
        title: "Secure fixture".into(),
        max_participants: capacity,
        invite_secret_hash: hex::encode(Sha256::digest([7u8; 32])),
        ..Default::default()
    }
}
fn settings_request(title: Option<&str>, locked: Option<bool>) -> UpdateSettingsRequest {
    UpdateSettingsRequest {
        title: title.map(str::to_owned),
        admission_locked: locked,
        ..Default::default()
    }
}

async fn settings_http(
    state: &AppState,
    auth: &AuthUser,
    id: Uuid,
    body: serde_json::Value,
) -> axum::response::Response {
    let access = crate::auth::jwt::issue_access_token(
        &state.config,
        auth.user_id,
        auth.device_id,
        auth.session_id,
    )
    .unwrap();
    crate::app(state.clone())
        .oneshot(
            axum::http::Request::builder()
                .method("PATCH")
                .uri(format!("/conferences/{id}/settings"))
                .header("authorization", format!("Bearer {}", access.token))
                .header("content-type", "application/json")
                .body(axum::body::Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap()
}

async fn moderation_http(
    state: &AppState,
    auth: &AuthUser,
    id: Uuid,
    user: Uuid,
    action: &str,
    body: serde_json::Value,
) -> axum::response::Response {
    let access = crate::auth::jwt::issue_access_token(
        &state.config,
        auth.user_id,
        auth.device_id,
        auth.session_id,
    )
    .unwrap();
    crate::app(state.clone())
        .oneshot(
            axum::http::Request::builder()
                .method("POST")
                .uri(format!("/conferences/{id}/participants/{user}/{action}"))
                .header("authorization", format!("Bearer {}", access.token))
                .header("content-type", "application/json")
                .body(axum::body::Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap()
}

#[tokio::test]
#[ignore = "requires dedicated ephemeral Postgres 55439 and Redis 56439; never uses application databases"]
async fn isolated_owner_room_list_exposes_empty_rooms_without_keys_or_other_accounts() {
    let (state, _sfu, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let stranger = fixture_user(&state.db).await;
    let mut ids = Vec::new();
    for _ in 0..3 {
        let room = create(State(state.clone()), owner.clone(), Json(create_request(2)))
            .await
            .unwrap()
            .0;
        let _ = join(
            State(state.clone()),
            owner.clone(),
            Path(room.id),
            Json(join_request()),
        )
        .await
        .unwrap();
        let _ = leave(State(state.clone()), owner.clone(), Path(room.id))
            .await
            .unwrap();
        ids.push(room.id);
    }
    assert!(
        matches!(create(State(state.clone()),owner.clone(),Json(create_request(2))).await,Err(AppError::Conflict(message)) if message=="End an existing conference before creating another.")
    );
    let rooms = owned(State(state.clone()), owner.clone()).await.unwrap().0;
    assert_eq!(rooms.len(), 3);
    assert!(rooms
        .iter()
        .all(|r| r.owner_user_id == owner.user_id && r.participants.is_empty()));
    assert!(rooms
        .windows(2)
        .all(|pair| pair[0].created_at >= pair[1].created_at));
    let serialized = serde_json::to_value(&rooms).unwrap();
    for room in serialized.as_array().unwrap() {
        for secret in [
            "invite",
            "invite_secret",
            "invite_secret_hash",
            "media_key",
            "participant_token",
        ] {
            assert!(
                room.get(secret).is_none(),
                "owner list must not reconstruct bearer capabilities"
            );
        }
    }
    assert!(owned(State(state.clone()), stranger)
        .await
        .unwrap()
        .0
        .is_empty());
    let _ = end(State(state.clone()), owner.clone(), Path(ids[0]))
        .await
        .unwrap();
    assert_eq!(
        owned(State(state.clone()), owner.clone())
            .await
            .unwrap()
            .0
            .len(),
        2
    );
    let replacement = create(State(state.clone()), owner.clone(), Json(create_request(2)))
        .await
        .unwrap()
        .0;
    for id in [ids[1], ids[2], replacement.id] {
        let _ = end(State(state.clone()), owner.clone(), Path(id))
            .await
            .unwrap();
    }
    fixture.abort();
}

#[tokio::test]
#[ignore = "requires dedicated ephemeral Postgres 55439 and Redis 56439; never uses application databases"]
async fn isolated_owner_audio_policy_is_generation_fenced_and_survives_rejoin() {
    let (state, sfu, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let member = fixture_user(&state.db).await;
    let outsider = fixture_user(&state.db).await;
    assert!(capabilities(owner.clone()).await.0.participant_moderation);
    let room = create(State(state.clone()), owner.clone(), Json(create_request(3)))
        .await
        .unwrap()
        .0;
    let owner_grant = join(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    let first = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    let body =
        serde_json::json!({"participant_identity":first.participant_identity,"blocked":true});
    assert_eq!(
        moderation_http(
            &state,
            &outsider,
            room.id,
            member.user_id,
            "audio",
            body.clone()
        )
        .await
        .status(),
        StatusCode::NOT_FOUND
    );
    assert_eq!(moderation_http(&state,&owner,room.id,owner.user_id,"audio",serde_json::json!({"participant_identity":owner_grant.participant_identity,"blocked":true})).await.status(),StatusCode::BAD_REQUEST);
    assert_eq!(moderation_http(&state,&owner,room.id,member.user_id,"audio",serde_json::json!({"participant_identity":livekit::participant_identity(member.user_id,member.device_id,Uuid::new_v4()),"blocked":true})).await.status(),StatusCode::CONFLICT);
    assert_eq!(moderation_http(&state,&owner,room.id,member.user_id,"audio",serde_json::json!({"participant_identity":first.participant_identity,"blocked":true,"enable_microphone":false})).await.status(),StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        moderation_http(&state, &owner, room.id, member.user_id, "audio", body)
            .await
            .status(),
        StatusCode::OK
    );
    let current = dto(&state, room.id).await.unwrap();
    assert!(
        current
            .participants
            .iter()
            .find(|p| p.user_id == member.user_id)
            .unwrap()
            .audio_publish_blocked
    );
    assert!(matches!(
        authorize_sfu(State(state.clone()), gate_headers(&first.participant_token)).await,
        Err(AppError::Unauthorized)
    ));
    sqlx::query("UPDATE conference_participant_policy SET needs_sync=true WHERE conference_id=$1 AND user_id=$2")
        .bind(room.id).bind(member.user_id).execute(&state.db).await.unwrap();
    let access = crate::auth::jwt::issue_access_token(
        &state.config,
        member.user_id,
        member.device_id,
        member.session_id,
    )
    .unwrap();
    let response = crate::app(state.clone())
        .oneshot(
            axum::http::Request::builder()
                .method("POST")
                .uri(format!("/conferences/{}/join", room.id))
                .header("authorization", format!("Bearer {}", access.token))
                .header("content-type", "application/json")
                .body(axum::body::Body::from(
                    serde_json::json!({"invite_secret":join_request().invite_secret}).to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(
        response.status(),
        StatusCode::SERVICE_UNAVAILABLE,
        "durable pending policy is recoverable, not terminal409"
    );
    sqlx::query("UPDATE conference_participant_policy SET needs_sync=false WHERE conference_id=$1 AND user_id=$2")
        .bind(room.id).bind(member.user_id).execute(&state.db).await.unwrap();
    let restricted = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(restricted.participant_identity, first.participant_identity);
    assert!(
        verified_sfu_grant(&configured().unwrap(), &restricted.participant_token)
            .unwrap()
            .2
    );
    assert!(authorize_sfu(
        State(state.clone()),
        gate_headers(&restricted.participant_token)
    )
    .await
    .is_ok());
    let _ = leave(State(state.clone()), member.clone(), Path(room.id))
        .await
        .unwrap();
    let fresh = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    assert_ne!(fresh.participant_identity, first.participant_identity);
    assert!(
        verified_sfu_grant(&configured().unwrap(), &fresh.participant_token)
            .unwrap()
            .2,
        "restriction persists across voluntary leave/new admission"
    );
    sfu.fail_roster.store(true, Ordering::SeqCst);
    let updates = sfu
        .requests
        .lock()
        .unwrap()
        .iter()
        .filter(|(method, _)| method == "UpdateParticipant")
        .count();
    moderation::reconcile(&state, &configured().unwrap())
        .await
        .unwrap();
    let requests = sfu.requests.lock().unwrap().clone();
    assert!(
        requests
            .iter()
            .filter(|(method, _)| method == "UpdateParticipant")
            .count()
            > updates,
        "failed roster must re-enforce already-synced audio restriction"
    );
    assert!(
        requests
            .iter()
            .rev()
            .find(|(method, _)| method == "UpdateParticipant")
            .unwrap()
            .1["identity"]
            == fresh.participant_identity,
        "fallback uses current admission, not the policy row's old generation"
    );
    assert_eq!(
        dto(&state, room.id).await.unwrap().state,
        "active",
        "successful direct re-enforcement keeps other participants connected"
    );
    sfu.fail_roster.store(false, Ordering::SeqCst);
    assert_eq!(
        moderation_http(
            &state,
            &owner,
            room.id,
            member.user_id,
            "audio",
            serde_json::json!({"participant_identity":first.participant_identity,"blocked":false})
        )
        .await
        .status(),
        StatusCode::CONFLICT
    );
    assert_eq!(
        moderation_http(
            &state,
            &owner,
            room.id,
            member.user_id,
            "audio",
            serde_json::json!({"participant_identity":fresh.participant_identity,"blocked":false})
        )
        .await
        .status(),
        StatusCode::OK
    );
    let allowed = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    assert!(
        !verified_sfu_grant(&configured().unwrap(), &allowed.participant_token)
            .unwrap()
            .2
    );
    let requests = sfu.requests.lock().unwrap().clone();
    assert!(requests
        .iter()
        .any(|(method, body)| method == "UpdateParticipant"
            && body["permission"]["can_publish_sources"] == serde_json::json!(["CAMERA"])));
    assert!(requests
        .iter()
        .any(|(method, body)| method == "UpdateParticipant"
            && body["permission"]["can_publish_sources"]
                == serde_json::json!(["CAMERA", "MICROPHONE"])));
    assert!(
        !requests
            .iter()
            .any(|(method, _)| method == "MutePublishedTrack"),
        "permission restoration must never remotely unmute a track"
    );
    let _ = end(State(state.clone()), owner, Path(room.id))
        .await
        .unwrap();
    fixture.abort();
}

#[tokio::test]
#[ignore = "requires dedicated ephemeral Postgres 55439 and Redis 56439; never uses application databases"]
async fn isolated_owner_remove_commits_ban_before_rpc_denies_replay_and_reaps_late_handshake() {
    let (state, sfu, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let member = fixture_user(&state.db).await;
    let other = fixture_user(&state.db).await;
    let room = create(State(state.clone()), owner.clone(), Json(create_request(3)))
        .await
        .unwrap()
        .0;
    let grant = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    let unaffected = join(
        State(state.clone()),
        other.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    sfu.hold_remove.store(true, Ordering::SeqCst);
    let task = {
        let state = state.clone();
        let owner = owner.clone();
        let identity = grant.participant_identity.clone();
        let user = member.user_id;
        tokio::spawn(async move {
            moderation_http(
                &state,
                &owner,
                room.id,
                user,
                "remove",
                serde_json::json!({"participant_identity":identity}),
            )
            .await
        })
    };
    tokio::time::timeout(
        std::time::Duration::from_secs(3),
        sfu.remove_started.notified(),
    )
    .await
    .unwrap();
    let persisted: (bool,bool)=sqlx::query_as("SELECT removed,needs_sync FROM conference_participant_policy WHERE conference_id=$1 AND user_id=$2").bind(room.id).bind(member.user_id).fetch_one(&state.db).await.unwrap();
    assert_eq!(
        persisted,
        (true, true),
        "denial is committed before SFU I/O"
    );
    assert!(matches!(
        authorize_sfu(State(state.clone()), gate_headers(&grant.participant_token)).await,
        Err(AppError::Unauthorized)
    ));
    // Do not call join here: moderation holds the room row until SFU confirms.
    sfu.hold_remove.store(false, Ordering::SeqCst);
    sfu.continue_remove.notify_one();
    assert_eq!(task.await.unwrap().status(), StatusCode::OK);
    assert!(matches!(
        join(
            State(state.clone()),
            member.clone(),
            Path(room.id),
            Json(join_request())
        )
        .await,
        Err(AppError::Gone(_))
    ));
    let mut another_login = member.clone();
    another_login.session_id = Uuid::new_v4();
    fixture_session(&state.db, &another_login).await;
    assert!(matches!(
        join(
            State(state.clone()),
            another_login,
            Path(room.id),
            Json(join_request())
        )
        .await,
        Err(AppError::Gone(_))
    ));
    let mut another_device = member.clone();
    another_device.device_id = Uuid::new_v4();
    another_device.session_id = Uuid::new_v4();
    sqlx::query("INSERT INTO devices(id,user_id,platform,signal_device_id,installation_id) VALUES($1,$2,'ios',2,$3)").bind(another_device.device_id).bind(member.user_id).bind(Uuid::new_v4()).execute(&state.db).await.unwrap();
    fixture_session(&state.db, &another_device).await;
    assert!(matches!(
        join(
            State(state.clone()),
            another_device,
            Path(room.id),
            Json(join_request())
        )
        .await,
        Err(AppError::Gone(_))
    ));
    assert_eq!(
        moderation_http(
            &state,
            &owner,
            room.id,
            member.user_id,
            "remove",
            serde_json::json!({"participant_identity":grant.participant_identity})
        )
        .await
        .status(),
        StatusCode::OK,
        "repeated same-generation remove is idempotent"
    );
    assert!(authorize_sfu(
        State(state.clone()),
        gate_headers(&unaffected.participant_token)
    )
    .await
    .is_ok());
    assert_eq!(dto(&state, room.id).await.unwrap().state, "active");
    let removals = sfu
        .requests
        .lock()
        .unwrap()
        .iter()
        .filter(|(method, _)| method == "RemoveParticipant")
        .count();
    *sfu.roster.lock().unwrap() = Some(vec![
        serde_json::json!({"identity":grant.participant_identity}),
    ]);
    moderation::reconcile(&state, &configured().unwrap())
        .await
        .unwrap();
    assert!(
        sfu.requests
            .lock()
            .unwrap()
            .iter()
            .filter(|(method, _)| method == "RemoveParticipant")
            .count()
            > removals,
        "already-authorized stale handshake is removed by reconciliation"
    );
    sfu.fail_roster.store(true, Ordering::SeqCst);
    let removals = sfu
        .requests
        .lock()
        .unwrap()
        .iter()
        .filter(|(method, _)| method == "RemoveParticipant")
        .count();
    assert_eq!(
        moderation_http(
            &state,
            &owner,
            room.id,
            member.user_id,
            "remove",
            serde_json::json!({"participant_identity":grant.participant_identity})
        )
        .await
        .status(),
        StatusCode::OK
    );
    let after_explicit = sfu
        .requests
        .lock()
        .unwrap()
        .iter()
        .filter(|(method, _)| method == "RemoveParticipant")
        .count();
    assert!(
        after_explicit > removals,
        "idempotent removal re-enforces known identity even when roster fails"
    );
    moderation::reconcile(&state, &configured().unwrap())
        .await
        .unwrap();
    assert!(
        sfu.requests
            .lock()
            .unwrap()
            .iter()
            .filter(|(method, _)| method == "RemoveParticipant")
            .count()
            > after_explicit,
        "periodic sweep cannot forget a durable ban after needs_sync clears"
    );
    assert_eq!(dto(&state, room.id).await.unwrap().state, "active");
    assert!(
        !sfu.requests
            .lock()
            .unwrap()
            .iter()
            .any(|(method, _)| method == "DeleteRoom"),
        "ordinary kick does not end other participants' room"
    );
    let _ = end(State(state.clone()), owner, Path(room.id))
        .await
        .unwrap();
    fixture.abort();
}

#[tokio::test]
#[ignore = "requires dedicated ephemeral Postgres 55439 and Redis 56439; never uses application databases"]
async fn isolated_moderation_rpc_failure_fails_closed_without_losing_durable_policy() {
    let (state, sfu, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let member = fixture_user(&state.db).await;
    let room = create(State(state.clone()), owner.clone(), Json(create_request(2)))
        .await
        .unwrap()
        .0;
    let grant = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    sfu.fail_update.store(true, Ordering::SeqCst);
    sfu.fail_delete.store(true, Ordering::SeqCst);
    assert_eq!(
        moderation_http(
            &state,
            &owner,
            room.id,
            member.user_id,
            "audio",
            serde_json::json!({"participant_identity":grant.participant_identity,"blocked":true})
        )
        .await
        .status(),
        StatusCode::SERVICE_UNAVAILABLE
    );
    let status: String = sqlx::query_scalar("SELECT state FROM conferences WHERE id=$1")
        .bind(room.id)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(status, "ending");
    let policy:(bool,bool)=sqlx::query_as("SELECT audio_publish_blocked,needs_sync FROM conference_participant_policy WHERE conference_id=$1 AND user_id=$2").bind(room.id).bind(member.user_id).fetch_one(&state.db).await.unwrap();
    assert_eq!(policy, (true, true));
    let count: i64 =
        sqlx::query_scalar("SELECT count(*) FROM conference_members WHERE conference_id=$1")
            .bind(room.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(count, 1, "busy slot retained until DeleteRoom confirms");
    assert!(matches!(
        authorize_sfu(State(state.clone()), gate_headers(&grant.participant_token)).await,
        Err(AppError::Unauthorized)
    ));
    sfu.fail_delete.store(false, Ordering::SeqCst);
    reconcile(&state).await.unwrap();
    assert_eq!(dto(&state, room.id).await.unwrap().state, "ended");
    let count: i64 =
        sqlx::query_scalar("SELECT count(*) FROM conference_members WHERE conference_id=$1")
            .bind(room.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(count, 0);
    fixture.abort();
}

async fn isolated_fixture() -> (
    AppState,
    Sfu,
    tokio::task::JoinHandle<Result<(), std::io::Error>>,
) {
    let database =
        std::env::var("CONFERENCE_TEST_DATABASE_URL").expect("dedicated fixture DB is required");
    let redis_url =
        std::env::var("CONFERENCE_TEST_REDIS_URL").expect("dedicated fixture Redis is required");
    let parsed = reqwest::Url::parse(&database).unwrap();
    assert_eq!(parsed.host_str(), Some("127.0.0.1"));
    assert_eq!(parsed.port(), Some(55439));
    assert_eq!(parsed.path(), "/conference_test");
    let parsed = reqwest::Url::parse(&redis_url).unwrap();
    assert_eq!(parsed.host_str(), Some("127.0.0.1"));
    assert_eq!(parsed.port(), Some(56439));
    let db = sqlx::postgres::PgPoolOptions::new()
        .max_connections(10)
        .connect(&database)
        .await
        .unwrap();
    sqlx::migrate!().run(&db).await.unwrap();
    // Each fixture owns a different fake SFU. Retire rows from the previous
    // serial test before its roster can be interpreted by this new fixture.
    sqlx::query("DELETE FROM conferences")
        .execute(&db)
        .await
        .unwrap();
    let redis = redis::Client::open(redis_url.clone())
        .unwrap()
        .get_connection_manager()
        .await
        .unwrap();
    let sfu = Sfu::default();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let fixture = tokio::spawn(
        axum::serve(
            listener,
            Router::new()
                .route("/twirp/livekit.RoomService/:method", post(fixture_sfu))
                .with_state(sfu.clone()),
        )
        .into_future(),
    );
    std::env::set_var("COCO_ENV", "development");
    for (key, value) in [
        ("CONFERENCE_LIVEKIT_URL", "ws://127.0.0.1:7880".to_string()),
        ("CONFERENCE_LIVEKIT_API_URL", format!("http://{address}")),
        ("CONFERENCE_LIVEKIT_API_KEY", "fixture-api-key".into()),
        (
            "CONFERENCE_LIVEKIT_API_SECRET",
            "fixture-api-secret-at-least-thirty-two-bytes".into(),
        ),
        ("CONFERENCE_LIVEKIT_AUTOCREATE_DISABLED", "true".into()),
        ("CONFERENCE_GATEWAY_REQUIRED", "true".into()),
    ] {
        std::env::set_var(key, value);
    }
    let s3 = S3Config {
        max_upload_duration_seconds: std::env::var("S3_MAX_UPLOAD_DURATION_SECONDS").ok().and_then(|v|v.parse().ok()).unwrap_or(0),
        upload_access_key_id: std::env::var("S3_UPLOAD_ACCESS_KEY_ID").unwrap_or_default(), upload_secret_access_key: std::env::var("S3_UPLOAD_SECRET_ACCESS_KEY").unwrap_or_default(),

        endpoint_url: "http://127.0.0.1:1".into(),
        public_endpoint_url: "http://127.0.0.1:1".into(),
        region: "fixture".into(),
        bucket: "fixture".into(),
        access_key_id: "fixture".into(),
        secret_access_key: "fixture".into(),
        presign_ttl_seconds: 60,
    };
    let config = Config {
        host: "127.0.0.1".into(),
        port: 0,
        database_url: database,
        database_max_connections: 10,
        redis_url,
        jwt_secret: "fixture-access-token-secret".into(),
        trusted_proxies: vec![],
        jwt_issuer: "fixture".into(),
        jwt_audience: "fixture".into(),
        access_token_ttl_seconds: 900,
        refresh_token_ttl_seconds: 900,
        s3: s3.clone(),
        turn: TurnConfig {
            stun_urls: vec![],
            turn_urls: vec![],
            shared_secret: "fixture".into(),
            credential_ttl_seconds: 60,
        },
        fcm: Default::default(),
    };
    let state = AppState { resources: Default::default(),
        db: db.clone(),
        redis,
        config,
        ws_registry: Default::default(),
        s3: Arc::new(crate::storage::S3Client::new(s3)),
        push: crate::push::disabled(),
    };
    (state, sfu, fixture)
}

#[tokio::test]
#[ignore = "requires dedicated ephemeral Postgres 55439 and Redis 56439; never uses application databases"]
async fn isolated_conference_lifecycle_capacity_busy_gate_and_security_closure() {
    let (state, sfu, fixture) = isolated_fixture().await;
    let db = state.db.clone();
    let owner = fixture_user(&db).await;
    let first = fixture_user(&db).await;
    let second = fixture_user(&db).await;
    let outsider = fixture_user(&db).await;
    let app = crate::app(state.clone());
    let unauthorized = app
        .clone()
        .oneshot(
            axum::http::Request::builder()
                .method("POST")
                .uri("/conferences")
                .header("content-type", "application/json")
                .body(axum::body::Body::from("{}"))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(
        unauthorized.status(),
        StatusCode::UNAUTHORIZED,
        "the exact contract route is mounted"
    );
    let access = crate::auth::jwt::issue_access_token(
        &state.config,
        owner.user_id,
        owner.device_id,
        owner.session_id,
    )
    .unwrap();
    let response = app
        .oneshot(
            axum::http::Request::builder()
                .uri("/conferences/capabilities")
                .header("authorization", format!("Bearer {}", access.token))
                .body(axum::body::Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let capabilities: serde_json::Value =
        serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap();
    assert_eq!(
        capabilities,
        serde_json::json!({"enabled":true,"max_participants":30,"e2ee_required":true,"host_controls":true,"participant_moderation":true,"owner_rooms":true,"room_directory":true,"room_access_v2":true,"room_key_handoff":true,"room_join_approval":true})
    );
    let room = create(State(state.clone()), owner.clone(), Json(create_request(2)))
        .await
        .unwrap()
        .0;
    assert_eq!(room.state, "active");
    assert!(room.participants.is_empty());
    assert_eq!(room.max_participants, 2);
    assert!((86_399..=86_401).contains(&(room.expires_at - room.created_at).num_seconds()), "created rooms must expire after 24 hours, not the old four-hour deadline");
    assert!(sfu.requests.lock().unwrap().iter().any(
        |(method, body)| method == "CreateRoom" && body["name"] == livekit::room_name(room.id)
            && body["empty_timeout"] == 86_400 && body["departure_timeout"] == 86_400
    ));
    let _owner_join = join(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    let (a, b) = tokio::join!(
        join(
            State(state.clone()),
            first.clone(),
            Path(room.id),
            Json(join_request())
        ),
        join(
            State(state.clone()),
            second.clone(),
            Path(room.id),
            Json(join_request())
        )
    );
    assert_eq!(
        usize::from(a.is_ok()) + usize::from(b.is_ok()),
        1,
        "one remaining capacity slot has exactly one winner"
    );
    let (member, joined) = if let Ok(joined) = a {
        (first.clone(), joined.0)
    } else {
        (second.clone(), b.unwrap().0)
    };
    assert_eq!(dto(&state, room.id).await.unwrap().participants.len(), 2);
    assert!(matches!(
        details(State(state.clone()), outsider.clone(), Path(room.id)).await,
        Err(AppError::NotFound(_))
    ));
    assert!(matches!(
        join(
            State(state.clone()),
            outsider.clone(),
            Path(room.id),
            Json(JoinRequest {
                invite_secret: Some(URL_SAFE_NO_PAD.encode([9u8; 32])),
                ..Default::default()
            })
        )
        .await,
        Err(AppError::NotFound(_))
    ));
    assert_eq!(
        authorize_sfu(
            State(state.clone()),
            gate_headers(&joined.participant_token)
        )
        .await
        .unwrap(),
        StatusCode::NO_CONTENT
    );

    // Existing private-call insertion also rejects a participant in a room.
    let call = Uuid::new_v4();
    let mut tx = db.begin().await.unwrap();
    sqlx::query("INSERT INTO calls(id,caller_user_id,callee_user_id,media_kind,state) VALUES($1,$2,$3,'audio','ringing')").bind(call).bind(owner.user_id).bind(outsider.user_id).execute(&mut *tx).await.unwrap();
    let collision =
        sqlx::query("INSERT INTO call_participants_active(user_id,call_id) VALUES($1,$2)")
            .bind(owner.user_id)
            .bind(call)
            .execute(&mut *tx)
            .await;
    assert!(collision.is_err());
    tx.rollback().await.unwrap();

    // Ordinary leave preserves the room; old and refreshed tokens carry the
    // removed admission identity and cannot bypass a subsequent busy check.
    let _ = leave(State(state.clone()), member.clone(), Path(room.id))
        .await
        .unwrap();
    assert_eq!(dto(&state, room.id).await.unwrap().state, "active");
    assert!(matches!(
        authorize_sfu(
            State(state.clone()),
            gate_headers(&joined.participant_token)
        )
        .await,
        Err(AppError::Unauthorized)
    ));
    let rejoined = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    assert_ne!(joined.participant_identity, rejoined.participant_identity);
    assert!(matches!(
        authorize_sfu(
            State(state.clone()),
            gate_headers(&joined.participant_token)
        )
        .await,
        Err(AppError::Unauthorized)
    ));

    // Refresh rotation preserves the admitted family. Independent later login
    // cannot resurrect a logged-out family's membership.
    let rotated = AuthUser {
        session_id: Uuid::new_v4(),
        ..member.clone()
    };
    fixture_session(&db, &rotated).await;
    sqlx::query("UPDATE sessions SET revoked_at=now(),replaced_by=$2 WHERE id=$1")
        .bind(member.session_id)
        .bind(rotated.session_id)
        .execute(&db)
        .await
        .unwrap();
    assert_eq!(
        authorize_sfu(
            State(state.clone()),
            gate_headers(&rejoined.participant_token)
        )
        .await
        .unwrap(),
        StatusCode::NO_CONTENT
    );
    sqlx::query("UPDATE sessions SET revoked_at=now() WHERE id=$1")
        .bind(rotated.session_id)
        .execute(&db)
        .await
        .unwrap();
    let independent = AuthUser {
        session_id: Uuid::new_v4(),
        ..member.clone()
    };
    fixture_session(&db, &independent).await;
    assert!(matches!(
        authorize_sfu(
            State(state.clone()),
            gate_headers(&rejoined.participant_token)
        )
        .await,
        Err(AppError::Unauthorized)
    ));
    sfu.fail_delete.store(true, Ordering::SeqCst);
    reconcile(&state).await.unwrap();
    assert_eq!(dto(&state, room.id).await.unwrap().state, "ended");
    let internal_state: String = sqlx::query_scalar("SELECT state FROM conferences WHERE id=$1")
        .bind(room.id)
        .fetch_one(&db)
        .await
        .unwrap();
    assert_eq!(internal_state, "ending");
    let busy_count: i64 =
        sqlx::query_scalar("SELECT count(*) FROM conference_members WHERE conference_id=$1")
            .bind(room.id)
            .fetch_one(&db)
            .await
            .unwrap();
    assert_eq!(
        busy_count, 2,
        "uncertain SFU removal must retain busy slots"
    );
    sfu.fail_delete.store(false, Ordering::SeqCst);
    reconcile(&state).await.unwrap();
    assert_eq!(dto(&state, room.id).await.unwrap().state, "ended");
    assert!(dto(&state, room.id).await.unwrap().participants.is_empty());

    // Another room remains independent; a verified installation alias is
    // rejected in either direction, even without an explicit account block.
    let room2 = create(
        State(state.clone()),
        owner.clone(),
        Json(create_request(30)),
    )
    .await
    .unwrap()
    .0;
    let owner2 = join(
        State(state.clone()),
        owner.clone(),
        Path(room2.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    let room3 = create(
        State(state.clone()),
        outsider.clone(),
        Json(create_request(2)),
    )
    .await
    .unwrap()
    .0;
    let _ = join(
        State(state.clone()),
        outsider.clone(),
        Path(room3.id),
        Json(join_request()),
    )
    .await
    .unwrap();
    // Conversely, an active private call denies conference admission.
    let private_user = fixture_user(&db).await;
    let private_peer = fixture_user(&db).await;
    let private_call = Uuid::new_v4();
    sqlx::query("INSERT INTO calls(id,caller_user_id,callee_user_id,media_kind,state) VALUES($1,$2,$3,'audio','ringing')").bind(private_call).bind(private_user.user_id).bind(private_peer.user_id).execute(&db).await.unwrap();
    sqlx::query("INSERT INTO call_participants_active(user_id,call_id) VALUES($1,$3),($2,$3)")
        .bind(private_user.user_id)
        .bind(private_peer.user_id)
        .bind(private_call)
        .execute(&db)
        .await
        .unwrap();
    assert!(matches!(
        join(
            State(state.clone()),
            private_user.clone(),
            Path(room3.id),
            Json(join_request())
        )
        .await,
        Err(AppError::Conflict(_))
    ));
    assert!(matches!(
        join(
            State(state.clone()),
            owner.clone(),
            Path(room3.id),
            Json(join_request())
        )
        .await,
        Err(AppError::Conflict(_))
    ));
    let installation = Uuid::new_v4();
    sqlx::query("INSERT INTO verified_installations(id,credential_hash) VALUES($1,$2)")
        .bind(installation)
        .bind(Sha256::digest(Uuid::new_v4().as_bytes()).to_vec())
        .execute(&db)
        .await
        .unwrap();
    sqlx::query("UPDATE devices SET verified_installation_id=$2 WHERE id=$1")
        .bind(independent.device_id)
        .bind(installation)
        .execute(&db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO verified_installation_users(installation_id,user_id) VALUES($1,$2)")
        .bind(installation)
        .bind(independent.user_id)
        .execute(&db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO user_blocks(user_id,blocked_user_id) VALUES($1,$2)")
        .bind(owner.user_id)
        .bind(independent.user_id)
        .execute(&db)
        .await
        .unwrap();
    assert!(matches!(
        join(
            State(state.clone()),
            independent.clone(),
            Path(room2.id),
            Json(join_request())
        )
        .await,
        Err(AppError::NotFound(_))
    ));
    let alias = fixture_user(&db).await;
    sqlx::query("UPDATE devices SET verified_installation_id=$2 WHERE id=$1")
        .bind(alias.device_id)
        .bind(installation)
        .execute(&db)
        .await
        .unwrap();
    assert!(matches!(
        join(
            State(state.clone()),
            alias.clone(),
            Path(room2.id),
            Json(join_request())
        )
        .await,
        Err(AppError::NotFound(_))
    ));
    assert!(matches!(
        end(State(state.clone()), outsider.clone(), Path(room2.id)).await,
        Err(AppError::NotFound(_))
    ));
    let _ = end(State(state.clone()), owner.clone(), Path(room2.id))
        .await
        .unwrap();
    assert!(matches!(
        authorize_sfu(
            State(state.clone()),
            gate_headers(&owner2.participant_token)
        )
        .await,
        Err(AppError::Unauthorized)
    ));
    assert_eq!(dto(&state, room3.id).await.unwrap().state, "active");
    sqlx::query("UPDATE conferences SET expires_at=now()-interval '1 second' WHERE id=$1")
        .bind(room3.id)
        .execute(&db)
        .await
        .unwrap();
    reconcile(&state).await.unwrap();
    assert_eq!(dto(&state, room3.id).await.unwrap().state, "ended");
    // A block introduced after admission closes the whole room, since the
    // removed user already knows its shared media key. No individual kick.
    let clean_member = fixture_user(&db).await;
    let room4 = create(State(state.clone()), owner.clone(), Json(create_request(2)))
        .await
        .unwrap()
        .0;
    let clean_join = join(
        State(state.clone()),
        clean_member.clone(),
        Path(room4.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    sqlx::query("INSERT INTO user_blocks(user_id,blocked_user_id) VALUES($1,$2)")
        .bind(owner.user_id)
        .bind(clean_member.user_id)
        .execute(&db)
        .await
        .unwrap();
    assert!(matches!(
        authorize_sfu(
            State(state.clone()),
            gate_headers(&clean_join.participant_token)
        )
        .await,
        Err(AppError::Unauthorized)
    ));
    reconcile(&state).await.unwrap();
    assert_eq!(
        dto(&state, room4.id).await.unwrap().end_reason.as_deref(),
        Some("authorization_changed")
    );
    // Durable cleanup still owns the room after hard account deletion.
    let deleted = fixture_user(&db).await;
    let room5 = create(
        State(state.clone()),
        deleted.clone(),
        Json(create_request(2)),
    )
    .await
    .unwrap()
    .0;
    let deleted_join = join(
        State(state.clone()),
        deleted.clone(),
        Path(room5.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    sqlx::query("DELETE FROM users WHERE id=$1")
        .bind(deleted.user_id)
        .execute(&db)
        .await
        .unwrap();
    assert!(matches!(
        authorize_sfu(
            State(state.clone()),
            gate_headers(&deleted_join.participant_token)
        )
        .await,
        Err(AppError::Unauthorized)
    ));
    reconcile(&state).await.unwrap();
    assert_eq!(dto(&state, room5.id).await.unwrap().state, "ended");
    let room6 = create(State(state.clone()), owner.clone(), Json(create_request(2)))
        .await
        .unwrap()
        .0;
    let abandoned = join(
        State(state.clone()),
        owner.clone(),
        Path(room6.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    sqlx::query("UPDATE conference_members SET last_authorized_at=now()-interval '91 seconds' WHERE conference_id=$1").bind(room6.id).execute(&db).await.unwrap();
    sfu.fail_roster.store(true, Ordering::SeqCst);
    reconcile(&state).await.unwrap();
    assert_eq!(
        dto(&state, room6.id).await.unwrap().participants.len(),
        1,
        "uncertain media presence retains the slot"
    );
    sfu.fail_roster.store(false, Ordering::SeqCst);
    reconcile(&state).await.unwrap();
    assert!(dto(&state, room6.id).await.unwrap().participants.is_empty());
    assert_eq!(dto(&state, room6.id).await.unwrap().state, "active");
    assert!(matches!(
        authorize_sfu(
            State(state.clone()),
            gate_headers(&abandoned.participant_token)
        )
        .await,
        Err(AppError::Unauthorized)
    ));
    fixture.abort();
}

#[tokio::test]
#[ignore = "requires dedicated ephemeral Postgres 55439 and Redis 56439; never uses application databases"]
async fn isolated_never_joined_room_times_out_but_used_empty_invitation_survives() {
    let (state, sfu, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let unused = create(State(state.clone()), owner.clone(), Json(create_request(2)))
        .await
        .unwrap()
        .0;
    let marker: Option<DateTime<Utc>> =
        sqlx::query_scalar("SELECT first_joined_at FROM conferences WHERE id=$1")
            .bind(unused.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert!(
        marker.is_none(),
        "creation alone does not mark a successful admission"
    );
    sqlx::query("UPDATE conferences SET created_at=now()-interval '90 seconds' WHERE id=$1")
        .bind(unused.id)
        .execute(&state.db)
        .await
        .unwrap();
    reconcile(&state).await.unwrap();
    assert_eq!(
        dto(&state, unused.id).await.unwrap().state,
        "active",
        "a new room retains its first-join window"
    );
    sqlx::query("UPDATE conferences SET created_at=now()-interval '121 seconds' WHERE id=$1")
        .bind(unused.id)
        .execute(&state.db)
        .await
        .unwrap();
    assert!(
        matches!(
            join(
                State(state.clone()),
                owner.clone(),
                Path(unused.id),
                Json(join_request())
            )
            .await,
            Err(AppError::Gone(_))
        ),
        "a late first join cannot race a delayed timeout sweep and extend an unused room"
    );
    reconcile(&state).await.unwrap();
    let ended = dto(&state, unused.id).await.unwrap();
    assert_eq!(ended.state, "ended");
    assert_eq!(ended.end_reason.as_deref(), Some("provisioning_timeout"));
    assert!(
        sfu.requests
            .lock()
            .unwrap()
            .iter()
            .any(|(method, body)| method == "DeleteRoom"
                && body["room"] == livekit::room_name(unused.id)),
        "expired invisible quota ownership deletes its actual SFU room"
    );
    let used = create(State(state.clone()), owner.clone(), Json(create_request(2)))
        .await
        .unwrap()
        .0;
    let _ = join(
        State(state.clone()),
        owner.clone(),
        Path(used.id),
        Json(join_request()),
    )
    .await
    .unwrap();
    let first: Option<DateTime<Utc>> =
        sqlx::query_scalar("SELECT first_joined_at FROM conferences WHERE id=$1")
            .bind(used.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert!(
        first.is_some(),
        "first successful admission commits its lifetime marker"
    );
    let _ = leave(State(state.clone()), owner.clone(), Path(used.id))
        .await
        .unwrap();
    sqlx::query("UPDATE conferences SET created_at=now()-interval '121 seconds' WHERE id=$1")
        .bind(used.id)
        .execute(&state.db)
        .await
        .unwrap();
    reconcile(&state).await.unwrap();
    let empty = dto(&state, used.id).await.unwrap();
    assert_eq!(empty.state, "active");
    assert!(empty.participants.is_empty());
    assert_eq!(
        empty.expires_at, used.expires_at,
        "used rooms retain the original four-hour deadline"
    );
    let _ = join(
        State(state.clone()),
        owner.clone(),
        Path(used.id),
        Json(join_request()),
    )
    .await
    .unwrap();
    let again: Option<DateTime<Utc>> =
        sqlx::query_scalar("SELECT first_joined_at FROM conferences WHERE id=$1")
            .bind(used.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(
        first, again,
        "rejoin does not rewrite the first-admission timestamp"
    );
    assert!(
        !sfu.requests
            .lock()
            .unwrap()
            .iter()
            .any(|(method, body)| method == "DeleteRoom"
                && body["room"] == livekit::room_name(used.id)),
        "the never-joined timeout must not delete a previously used empty room"
    );
    fixture.abort();
}

#[tokio::test]
#[ignore = "requires dedicated ephemeral Postgres 55439 and Redis 56439; never uses application databases"]
async fn isolated_gateway_cannot_authorize_one_room_and_forward_another_rooms_bearer() {
    let (state, _sfu, fixture) = isolated_fixture().await;
    let current = fixture_user(&state.db).await;
    let revoked = fixture_user(&state.db).await;
    let room_a = create(
        State(state.clone()),
        current.clone(),
        Json(create_request(2)),
    )
    .await
    .unwrap()
    .0;
    let room_b = create(
        State(state.clone()),
        revoked.clone(),
        Json(create_request(2)),
    )
    .await
    .unwrap()
    .0;
    let token_a = join(
        State(state.clone()),
        current.clone(),
        Path(room_a.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0
    .participant_token;
    let token_b = join(
        State(state.clone()),
        revoked.clone(),
        Path(room_b.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0
    .participant_token;
    sqlx::query("UPDATE sessions SET revoked_at=now() WHERE id=$1")
        .bind(revoked.session_id)
        .execute(&state.db)
        .await
        .unwrap();
    let app = crate::app(state.clone());
    let bearer_a = format!("Bearer {token_a}");
    let bearer_b = format!("Bearer {token_b}");
    for (query, bearer, status) in [
        (Some(token_a.as_str()), None, StatusCode::NO_CONTENT),
        (None, Some(bearer_a.as_str()), StatusCode::NO_CONTENT),
        (Some(""), Some(bearer_a.as_str()), StatusCode::NO_CONTENT),
        (
            Some(token_a.as_str()),
            Some(bearer_a.as_str()),
            StatusCode::NO_CONTENT,
        ),
        (
            Some(token_a.as_str()),
            Some(bearer_b.as_str()),
            StatusCode::UNAUTHORIZED,
        ),
        (
            Some(token_b.as_str()),
            Some(bearer_a.as_str()),
            StatusCode::UNAUTHORIZED,
        ),
        (
            Some(token_b.as_str()),
            Some(bearer_b.as_str()),
            StatusCode::UNAUTHORIZED,
        ),
        (None, Some(bearer_b.as_str()), StatusCode::UNAUTHORIZED),
    ] {
        let mut request = axum::http::Request::builder().uri("/conferences/authorize-sfu");
        if let Some(query) = query {
            request = request.header("x-conference-token", query);
        }
        if let Some(bearer) = bearer {
            request = request.header("authorization", bearer);
        }
        let response = app
            .clone()
            .oneshot(request.body(axum::body::Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(
            response.status(),
            status,
            "the gateway and SFU must use exactly the same credential"
        );
    }
    let duplicate = axum::http::Request::builder()
        .uri("/conferences/authorize-sfu")
        .header("x-conference-token", &token_a)
        .header("authorization", &bearer_a)
        .header("authorization", &bearer_a)
        .body(axum::body::Body::empty())
        .unwrap();
    assert_eq!(
        app.oneshot(duplicate).await.unwrap().status(),
        StatusCode::UNAUTHORIZED,
        "duplicate bearer headers cannot select a different upstream credential"
    );
    fixture.abort();
}

#[tokio::test]
#[ignore = "requires dedicated ephemeral Postgres 55439 and Redis 56439; never uses application databases"]
async fn isolated_reconnect_join_renews_grace_without_changing_identity_or_capacity() {
    let (state, sfu, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let peer = fixture_user(&state.db).await;
    let room = create(State(state.clone()), owner.clone(), Json(create_request(2)))
        .await
        .unwrap()
        .0;
    let initial = join(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    let _ = join(
        State(state.clone()),
        peer.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap();
    sqlx::query("UPDATE conference_members SET last_authorized_at=now()-interval '91 seconds' WHERE user_id=$1")
        .bind(owner.user_id).execute(&state.db).await.unwrap();
    let refreshed = AuthUser {
        session_id: Uuid::new_v4(),
        ..owner.clone()
    };
    fixture_session(&state.db, &refreshed).await;
    sqlx::query("UPDATE sessions SET revoked_at=now(),replaced_by=$2 WHERE id=$1")
        .bind(owner.session_id)
        .bind(refreshed.session_id)
        .execute(&state.db)
        .await
        .unwrap();

    let retry = join(
        State(state.clone()),
        refreshed.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(
        retry.participant_identity, initial.participant_identity,
        "transport recovery preserves the admission across refresh rotation and a full room"
    );
    let fresh: bool = sqlx::query_scalar("SELECT last_authorized_at>now()-interval '5 seconds' FROM conference_members WHERE user_id=$1")
        .bind(owner.user_id).fetch_one(&state.db).await.unwrap();
    assert!(
        fresh,
        "successful join must renew the pending media handshake grace"
    );
    reap_absent_members(&state, &configured().unwrap())
        .await
        .unwrap();
    assert_eq!(
        dto(&state, room.id).await.unwrap().participants.len(),
        2,
        "an empty SFU snapshot cannot immediately reap a newly returned join grant"
    );
    assert!(!sfu
        .requests
        .lock()
        .unwrap()
        .iter()
        .any(|(method, body)| method == "RemoveParticipant"
            && body["room"] == livekit::room_name(room.id)));
    assert_eq!(
        authorize_sfu(State(state.clone()), gate_headers(&retry.participant_token))
            .await
            .unwrap(),
        StatusCode::NO_CONTENT
    );

    sqlx::query("UPDATE sessions SET revoked_at=now() WHERE id=$1")
        .bind(peer.session_id)
        .execute(&state.db)
        .await
        .unwrap();
    assert!(
        matches!(
            join(
                State(state.clone()),
                refreshed.clone(),
                Path(room.id),
                Json(join_request())
            )
            .await,
            Err(AppError::Conflict(_))
        ),
        "fresh grace must not restore revoked room authorization"
    );
    assert!(matches!(
        authorize_sfu(State(state.clone()), gate_headers(&retry.participant_token)).await,
        Err(AppError::Unauthorized)
    ));
    sqlx::query("UPDATE conferences SET expires_at=now()-interval '1 second' WHERE id=$1")
        .bind(room.id)
        .execute(&state.db)
        .await
        .unwrap();
    assert!(
        matches!(
            join(
                State(state.clone()),
                refreshed,
                Path(room.id),
                Json(join_request())
            )
            .await,
            Err(AppError::Gone(_))
        ),
        "recovery must never extend the hard room deadline"
    );
    fixture.abort();
}

#[tokio::test]
#[ignore = "requires dedicated ephemeral Postgres 55439 and Redis 56439; never uses application databases"]
async fn isolated_absence_removal_serializes_with_a_new_signaling_authorization() {
    let (state, sfu, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let room = create(State(state.clone()), owner.clone(), Json(create_request(2)))
        .await
        .unwrap()
        .0;
    let initial = join(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    sqlx::query("UPDATE conference_members SET last_authorized_at=now()-interval '91 seconds' WHERE user_id=$1")
        .bind(owner.user_id).execute(&state.db).await.unwrap();
    sfu.hold_remove.store(true, Ordering::SeqCst);
    let sweep_state = state.clone();
    let sweep =
        tokio::spawn(
            async move { reap_absent_members(&sweep_state, &configured().unwrap()).await },
        );
    tokio::time::timeout(
        std::time::Duration::from_secs(5),
        sfu.remove_started.notified(),
    )
    .await
    .expect("absence removal must reach the held SFU request");
    let gate_state = state.clone();
    let old_token = initial.participant_token.clone();
    let mut gate =
        tokio::spawn(
            async move { authorize_sfu(State(gate_state), gate_headers(&old_token)).await },
        );
    // Check the actual PostgreSQL wait, rather than relying on task scheduling
    // or a sleep to infer that this authorization cannot race removal.
    tokio::select! {
        result = &mut gate => panic!("gate authorized during pending stale-member removal: {result:?}"),
        blocked = tokio::time::timeout(std::time::Duration::from_secs(5), async {
            loop {
                let waiting: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND query LIKE 'WITH allowed AS(SELECT member.user_id%' AND wait_event_type='Lock')")
                    .fetch_one(&state.db).await.unwrap();
                if waiting { break; }
                tokio::task::yield_now().await;
            }
        }) => blocked.expect("the signaling gate must wait for the stale admission row lock"),
    }
    sfu.continue_remove.notify_one();
    sweep.await.unwrap().unwrap();
    assert!(
        matches!(gate.await.unwrap(), Err(AppError::Unauthorized)),
        "an admission removed before a handshake cannot be authorized after cleanup"
    );
    assert!(dto(&state, room.id).await.unwrap().participants.is_empty());
    let retry = join(
        State(state.clone()),
        owner,
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    assert_ne!(
        retry.participant_identity, initial.participant_identity,
        "a bounded fresh join can safely recover using a new admission after reaping"
    );
    assert_eq!(
        authorize_sfu(State(state), gate_headers(&retry.participant_token))
            .await
            .unwrap(),
        StatusCode::NO_CONTENT
    );
    fixture.abort();
}

#[tokio::test]
#[ignore = "requires dedicated ephemeral Postgres 55439 and Redis 56439; never uses application databases"]
async fn isolated_missing_sfu_room_closes_but_uncertain_roster_failures_retain_admission() {
    let (state, sfu, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let room = create(State(state.clone()), owner.clone(), Json(create_request(2)))
        .await
        .unwrap()
        .0;
    let grant = join(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    sqlx::query("UPDATE conference_members SET last_authorized_at=now()-interval '91 seconds' WHERE user_id=$1")
        .bind(owner.user_id).execute(&state.db).await.unwrap();
    let config = configured().unwrap();
    for flag in [
        &sfu.fail_roster,
        &sfu.proxy_missing_roster,
        &sfu.unauthorized_roster,
        &sfu.forbidden_roster,
    ] {
        flag.store(true, Ordering::SeqCst);
        reap_absent_members(&state, &config).await.unwrap();
        let current = dto(&state, room.id).await.unwrap();
        assert_eq!(
            current.state, "active",
            "503, proxy 404, 401 and 403 cannot prove SFU room absence"
        );
        assert_eq!(
            current.participants.len(),
            1,
            "uncertain SFU responses retain the busy slot"
        );
        flag.store(false, Ordering::SeqCst);
    }
    // Even a failing DeleteRoom must not prevent retirement: this exact
    // ListParticipants response already confirms there is no room to delete.
    sqlx::query(
        "UPDATE conference_members SET last_authorized_at=clock_timestamp() WHERE user_id=$1",
    )
    .bind(owner.user_id)
    .execute(&state.db)
    .await
    .unwrap();
    sfu.fail_delete.store(true, Ordering::SeqCst);
    sfu.missing_roster.store(true, Ordering::SeqCst);
    reap_absent_members(&state, &config).await.unwrap();
    let ended = dto(&state, room.id).await.unwrap();
    assert_eq!(ended.state, "ended");
    assert_eq!(ended.end_reason.as_deref(), Some("media_room_missing"));
    assert!(
        ended.participants.is_empty(),
        "authoritative absence releases conference busy slots"
    );
    assert!(matches!(
        authorize_sfu(State(state.clone()), gate_headers(&grant.participant_token)).await,
        Err(AppError::Unauthorized)
    ));
    assert!(
        matches!(
            join(
                State(state.clone()),
                owner.clone(),
                Path(room.id),
                Json(join_request())
            )
            .await,
            Err(AppError::Gone(_))
        ),
        "missing SFU rooms must never be auto-recreated by recovery"
    );
    assert_eq!(
        sfu.requests
            .lock()
            .unwrap()
            .iter()
            .filter(|(method, _)| method == "CreateRoom")
            .count(),
        1
    );

    sfu.fail_delete.store(false, Ordering::SeqCst);
    sfu.missing_roster.store(false, Ordering::SeqCst);
    let other = create(State(state.clone()), owner.clone(), Json(create_request(2)))
        .await
        .unwrap()
        .0;
    let _ = join(
        State(state.clone()),
        owner.clone(),
        Path(other.id),
        Json(join_request()),
    )
    .await
    .unwrap();
    sqlx::query("UPDATE conference_members SET last_authorized_at=now()-interval '91 seconds' WHERE user_id=$1")
        .bind(owner.user_id).execute(&state.db).await.unwrap();
    let unreachable = ConferenceConfig {
        api_url: "http://127.0.0.1:1".into(),
        ..config
    };
    reap_absent_members(&state, &unreachable).await.unwrap();
    let retained = dto(&state, other.id).await.unwrap();
    assert_eq!(
        retained.state, "active",
        "transport failure is not authoritative room absence"
    );
    assert_eq!(retained.participants.len(), 1);
    fixture.abort();
}

#[tokio::test]
#[ignore = "requires dedicated ephemeral Postgres 55439 and Redis 56439; never uses application databases"]
async fn isolated_host_controls_preserve_media_and_reconnect_but_gate_new_admissions() {
    let (state, sfu, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let member = fixture_user(&state.db).await;
    let newcomer = fixture_user(&state.db).await;
    let room = create(State(state.clone()), owner.clone(), Json(create_request(3)))
        .await
        .unwrap()
        .0;
    assert!(
        !room.admission_locked,
        "migration default keeps existing admission policy"
    );
    let initial = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    let token_before =
        verified_sfu_identity(&configured().unwrap(), &initial.participant_token).unwrap();
    let hash_before: Vec<u8> =
        sqlx::query_scalar("SELECT invite_secret_hash FROM conferences WHERE id=$1")
            .bind(room.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    let media_requests_before = sfu.requests.lock().unwrap().len();
    let response = settings_http(
        &state,
        &owner,
        room.id,
        serde_json::json!({"title":"  Обсуждение  ","admission_locked":true}),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let updated: serde_json::Value =
        serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap();
    assert_eq!(updated["title"], "Обсуждение");
    assert_eq!(updated["admission_locked"], true);
    assert_eq!(updated["max_participants"], 3);
    assert_eq!(updated["participants"].as_array().unwrap().len(), 1);
    assert_eq!(
        sfu.requests.lock().unwrap().len(),
        media_requests_before,
        "metadata edits do not remove tracks, rotate identities or contact SFU"
    );
    let retry = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(initial.participant_identity, retry.participant_identity);
    assert_eq!(
        token_before,
        verified_sfu_identity(&configured().unwrap(), &retry.participant_token).unwrap()
    );
    assert!(
        authorize_sfu(
            State(state.clone()),
            gate_headers(&initial.participant_token)
        )
        .await
        .is_ok(),
        "locking new entries must not revoke the existing media token"
    );
    assert!(
        matches!(join(State(state.clone()), newcomer.clone(), Path(room.id), Json(join_request())).await, Err(AppError::Conflict(message)) if message == "Conference admission is locked.")
    );
    let bad_secret = JoinRequest {
        invite_secret: Some(URL_SAFE_NO_PAD.encode([8u8; 32])),
        ..Default::default()
    };
    assert!(
        matches!(
            join(
                State(state.clone()),
                newcomer.clone(),
                Path(room.id),
                Json(bad_secret)
            )
            .await,
            Err(AppError::NotFound(_))
        ),
        "locked state is not disclosed to an invalid capability"
    );
    let owner_grant = join(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    assert!(
        owner_grant.conference.admission_locked,
        "owner may return to their locked room"
    );
    let _ = leave(State(state.clone()), member.clone(), Path(room.id))
        .await
        .unwrap();
    assert!(
        matches!(join(State(state.clone()), member.clone(), Path(room.id), Json(join_request())).await, Err(AppError::Conflict(message)) if message == "Conference admission is locked."),
        "explicit leave removes the old admission; knowing the old room key is not a lock bypass"
    );
    let reopened = update_settings(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(settings_request(None, Some(false))),
    )
    .await
    .unwrap()
    .0;
    assert!(!reopened.admission_locked);
    assert_eq!(
        reopened.title, "Обсуждение",
        "partial update preserves title"
    );
    let fresh = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    assert_ne!(
        fresh.participant_identity, initial.participant_identity,
        "reopening never revives an old admission generation"
    );
    let _ = join(
        State(state.clone()),
        newcomer.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap();
    let hash_after: Vec<u8> =
        sqlx::query_scalar("SELECT invite_secret_hash FROM conferences WHERE id=$1")
            .bind(room.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(
        hash_before, hash_after,
        "host controls must not invalidate exported capabilities"
    );
    let _ = end(State(state.clone()), owner, Path(room.id))
        .await
        .unwrap();
    fixture.abort();
}

#[tokio::test]
#[ignore = "requires dedicated ephemeral Postgres 55439 and Redis 56439; never uses application databases"]
async fn isolated_host_controls_enforce_owner_session_readonly_and_room_lifetime() {
    let (state, _sfu, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let outsider = fixture_user(&state.db).await;
    let room = create(State(state.clone()), owner.clone(), Json(create_request(3)))
        .await
        .unwrap()
        .0;
    let _ = join(
        State(state.clone()),
        outsider.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap();
    assert_eq!(
        settings_http(
            &state,
            &outsider,
            room.id,
            serde_json::json!({"admission_locked":true})
        )
        .await
        .status(),
        StatusCode::NOT_FOUND,
        "membership is not host authority"
    );
    assert_eq!(
        settings_http(&state, &owner, room.id, serde_json::json!({}))
            .await
            .status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        settings_http(
            &state,
            &owner,
            room.id,
            serde_json::json!({"title":"Bad\u{2028}line"})
        )
        .await
        .status(),
        StatusCode::BAD_REQUEST
    );
    let unknown = settings_http(
        &state,
        &owner,
        room.id,
        serde_json::json!({"admission_locked":true,"e2ee_required":false}),
    )
    .await
    .status();
    assert_eq!(
        unknown,
        StatusCode::UNPROCESSABLE_ENTITY,
        "unknown media policy cannot reach mutation"
    );
    assert_eq!(dto(&state, room.id).await.unwrap().title, room.title);
    assert!(!dto(&state, room.id).await.unwrap().admission_locked);
    sqlx::query("INSERT INTO admin_user_controls(user_id,read_only_reason) VALUES($1,'fixture')")
        .bind(owner.user_id)
        .execute(&state.db)
        .await
        .unwrap();
    assert!(
        matches!(
            update_settings(
                State(state.clone()),
                owner.clone(),
                Path(room.id),
                Json(settings_request(None, Some(true)))
            )
            .await,
            Err(AppError::AccountReadOnly)
        ),
        "handler rechecks read-only after acquiring the user lock"
    );
    sqlx::query("DELETE FROM admin_user_controls WHERE user_id=$1")
        .bind(owner.user_id)
        .execute(&state.db)
        .await
        .unwrap();
    let revoked = AuthUser {
        session_id: Uuid::new_v4(),
        ..owner.clone()
    };
    fixture_session(&state.db, &revoked).await;
    sqlx::query("UPDATE sessions SET revoked_at=now() WHERE id=$1")
        .bind(revoked.session_id)
        .execute(&state.db)
        .await
        .unwrap();
    assert!(matches!(
        update_settings(
            State(state.clone()),
            revoked,
            Path(room.id),
            Json(settings_request(None, Some(true)))
        )
        .await,
        Err(AppError::SessionRevoked)
    ));
    sqlx::query("UPDATE conferences SET expires_at=now()-interval '1 second' WHERE id=$1")
        .bind(room.id)
        .execute(&state.db)
        .await
        .unwrap();
    assert!(matches!(
        update_settings(
            State(state.clone()),
            owner.clone(),
            Path(room.id),
            Json(settings_request(None, Some(true)))
        )
        .await,
        Err(AppError::Gone(_))
    ));
    let _ = end(State(state.clone()), owner.clone(), Path(room.id))
        .await
        .unwrap();
    assert!(matches!(
        update_settings(
            State(state.clone()),
            owner.clone(),
            Path(room.id),
            Json(settings_request(Some("Cannot revive"), Some(false)))
        )
        .await,
        Err(AppError::Gone(_))
    ));
    assert_eq!(dto(&state, room.id).await.unwrap().state, "ended");
    let never_joined = create(State(state.clone()), owner.clone(), Json(create_request(2)))
        .await
        .unwrap()
        .0;
    sqlx::query("UPDATE conferences SET created_at=now()-interval '121 seconds' WHERE id=$1")
        .bind(never_joined.id)
        .execute(&state.db)
        .await
        .unwrap();
    assert!(
        matches!(
            update_settings(
                State(state.clone()),
                owner.clone(),
                Path(never_joined.id),
                Json(settings_request(Some("Cannot extend"), Some(true)))
            )
            .await,
            Err(AppError::Gone(_))
        ),
        "metadata changes cannot extend the first-join deadline"
    );
    let _ = end(State(state.clone()), owner, Path(never_joined.id))
        .await
        .unwrap();
    fixture.abort();
}

#[tokio::test]
#[ignore = "requires dedicated ephemeral Postgres 55439 and Redis 56439; never uses application databases"]
async fn isolated_host_migration_preserves_preexisting_rows_as_unlocked() {
    let (state, _sfu, fixture) = isolated_fixture().await;
    let mut connection = state.db.acquire().await.unwrap();
    // A session-local table shadows the real table only on this owned connection.
    // Apply the literal production migration to a schema-51-shaped existing row.
    sqlx::query("CREATE TEMP TABLE conferences(id UUID PRIMARY KEY,title TEXT NOT NULL)")
        .execute(&mut *connection)
        .await
        .unwrap();
    let id = Uuid::new_v4();
    sqlx::query("INSERT INTO conferences(id,title) VALUES($1,'Existing room')")
        .bind(id)
        .execute(&mut *connection)
        .await
        .unwrap();
    sqlx::raw_sql(include_str!(
        "../../migrations/0052_conference_host_controls.sql"
    ))
    .execute(&mut *connection)
    .await
    .unwrap();
    let row: (Uuid, String, bool) =
        sqlx::query_as("SELECT id,title,admission_locked FROM conferences")
            .fetch_one(&mut *connection)
            .await
            .unwrap();
    assert_eq!(row, (id, "Existing room".to_owned(), false));
    assert!(
        sqlx::query("UPDATE conferences SET admission_locked=NULL")
            .execute(&mut *connection)
            .await
            .is_err(),
        "policy cannot become NULL"
    );
    sqlx::query("DROP TABLE pg_temp.conferences")
        .execute(&mut *connection)
        .await
        .unwrap();
    fixture.abort();
}

#[tokio::test]
#[ignore = "requires dedicated ephemeral Postgres 55439 and Redis 56439; never uses application databases"]
async fn isolated_locked_policy_serializes_with_join_and_thirty_member_capacity() {
    let (state, _sfu, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let newcomer = fixture_user(&state.db).await;
    let room = create(
        State(state.clone()),
        owner.clone(),
        Json(create_request(30)),
    )
    .await
    .unwrap()
    .0;
    let _ = join(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap();
    let mut policy_tx = state.db.begin().await.unwrap();
    let _ = lock_room(&mut policy_tx, room.id).await.unwrap();
    sqlx::query("UPDATE conferences SET admission_locked=true WHERE id=$1")
        .bind(room.id)
        .execute(&mut *policy_tx)
        .await
        .unwrap();
    let join_state = state.clone();
    let join_auth = newcomer.clone();
    let mut blocked_join = tokio::spawn(async move {
        join(
            State(join_state),
            join_auth,
            Path(room.id),
            Json(join_request()),
        )
        .await
    });
    assert!(
        tokio::time::timeout(std::time::Duration::from_millis(75), &mut blocked_join)
            .await
            .is_err(),
        "join must wait on the room lock instead of reading a stale unlocked policy"
    );
    policy_tx.commit().await.unwrap();
    assert!(
        matches!(blocked_join.await.unwrap(), Err(AppError::Conflict(message)) if message == "Conference admission is locked.")
    );
    assert_eq!(dto(&state, room.id).await.unwrap().participants.len(), 1);
    let _ = update_settings(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(settings_request(None, Some(false))),
    )
    .await
    .unwrap();
    let mut joins = Vec::new();
    for _ in 0..30 {
        let candidate = fixture_user(&state.db).await;
        let state = state.clone();
        joins.push(tokio::spawn(async move {
            join(State(state), candidate, Path(room.id), Json(join_request())).await
        }));
    }
    let mut admitted = 0;
    let mut full = 0;
    for task in joins {
        match task.await.unwrap() {
            Ok(_) => admitted += 1,
            Err(AppError::Conflict(message)) if message == "Conference is full." => full += 1,
            other => panic!(
                "unexpected admission result: {}",
                match other {
                    Err(e) => format!("{e:?}"),
                    _ => "unexpected success".into(),
                }
            ),
        }
    }
    assert_eq!(admitted, 29);
    assert_eq!(full, 1);
    assert_eq!(dto(&state, room.id).await.unwrap().participants.len(), 30);
    let _ = update_settings(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(settings_request(None, Some(true))),
    )
    .await
    .unwrap();
    let retry = join(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap();
    assert_eq!(
        retry.0.conference.participants.len(),
        30,
        "full + locked still permits idempotent reconnect, never a 31st slot"
    );
    let _ = end(State(state.clone()), owner, Path(room.id))
        .await
        .unwrap();
    fixture.abort();
}

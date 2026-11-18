//! Real isolated database tests; the parent fixture rejects application DB URLs.
use super::*;
use crate::conferences::approval;

fn ask_body(id: Uuid) -> approval::Request {
    approval::Request {
        request_id: id,
        invite_secret: Some(URL_SAFE_NO_PAD.encode([7u8; 32])),
        password: None,
    }
}
async fn ask(state: &AppState, user: &AuthUser, room: Uuid, id: Uuid) -> serde_json::Value {
    serde_json::to_value(
        approval::request(
            State(state.clone()),
            user.clone(),
            Path(room),
            Json(ask_body(id)),
        )
        .await
        .unwrap()
        .0,
    )
    .unwrap()
}
async fn decision(
    state: &AppState,
    owner: &AuthUser,
    room: Uuid,
    id: Uuid,
    approve: bool,
) -> serde_json::Value {
    serde_json::to_value(
        approval::decide(
            State(state.clone()),
            owner.clone(),
            Path((room, id)),
            Json(approval::Decision { approve }),
        )
        .await
        .unwrap()
        .0,
    )
    .unwrap()
}
async fn room(state: &AppState, owner: &AuthUser) -> ConferenceDto {
    let mut options = create_request(6);
    options.approval_required = Some(true);
    options.listed = Some(true);
    options.access_mode = Some("open".into());
    let room = create(State(state.clone()), owner.clone(), Json(options))
        .await
        .unwrap()
        .0;
    let _ = join(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(JoinRequest {
            supports_key_handoff: true,
            ..join_request()
        }),
    )
    .await
    .unwrap();
    room
}
async fn expiry(state: &AppState, id: Uuid, user: Uuid) {
    sqlx::query("UPDATE conference_join_cooldowns SET blocked_until=clock_timestamp()-interval '1 second' WHERE conference_id=$1 AND user_id=$2").bind(id).bind(user).execute(&state.db).await.unwrap();
}
async fn another_device(state: &AppState, user: &AuthUser) -> AuthUser {
    let auth = AuthUser {
        user_id: user.user_id,
        device_id: Uuid::new_v4(),
        session_id: Uuid::new_v4(),
    };
    sqlx::query("INSERT INTO devices(id,user_id,platform,signal_device_id,installation_id) VALUES($1,$2,'ios',2,$3)")
        .bind(auth.device_id).bind(auth.user_id).bind(Uuid::new_v4()).execute(&state.db).await.unwrap();
    fixture_session(&state.db, &auth).await;
    auth
}
fn needs_approval(value: Result<Json<JoinResponse>, AppError>) {
    assert!(matches!(value,Err(AppError::Conflict(s)) if s==approval::REQUIRED));
}

#[tokio::test]
#[ignore = "requires dedicated conference fixture services"]
async fn approval_gates_both_invite_and_catalog_and_only_creator_can_decide() {
    let (state, _, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let peer = fixture_user(&state.db).await;
    let c = room(&state, &owner).await;
    let id = Uuid::new_v4();
    needs_approval(
        join(
            State(state.clone()),
            peer.clone(),
            Path(c.id),
            Json(join_request()),
        )
        .await,
    );
    let denied = discovery::request_key(
        State(state.clone()),
        peer.clone(),
        Path(c.id),
        Json(discovery::KeyCreate {
            request_id: Uuid::new_v4(),
            nonce: URL_SAFE_NO_PAD.encode([9u8; 32]),
            password: None,
        }),
    )
    .await;
    assert!(matches!(denied,Err(AppError::Conflict(s)) if s==approval::REQUIRED));
    assert_eq!(ask(&state, &peer, c.id, id).await["status"], "pending");
    let list = serde_json::to_value(
        approval::pending(State(state.clone()), owner.clone(), Path(c.id))
            .await
            .unwrap()
            .0,
    )
    .unwrap();
    assert_eq!(list["items"].as_array().unwrap().len(), 1);
    assert!(
        approval::pending(State(state.clone()), peer.clone(), Path(c.id))
            .await
            .is_err()
    );
    assert!(approval::decide(
        State(state.clone()),
        peer.clone(),
        Path((c.id, id)),
        Json(approval::Decision { approve: true })
    )
    .await
    .is_err());
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM conference_members WHERE user_id=$1")
        .bind(peer.user_id)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(count, 0);
    assert_eq!(
        decision(&state, &owner, c.id, id, true).await["status"],
        "approved"
    );
    let admitted = join(
        State(state.clone()),
        peer.clone(),
        Path(c.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    assert!(admitted.e2ee_required);
    assert_eq!(
        decision(&state, &owner, c.id, id, true).await["status"],
        "consumed"
    );
    fixture.abort();
}
#[tokio::test]
#[ignore = "requires dedicated conference fixture services"]
async fn rejection_cooldown_is_one_then_ten_minutes_and_replays_do_not_extend_it() {
    let (state, _, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let peer = fixture_user(&state.db).await;
    let c = room(&state, &owner).await;
    let first = Uuid::new_v4();
    ask(&state, &peer, c.id, first).await;
    let denied = decision(&state, &owner, c.id, first, false).await;
    let until = DateTime::parse_from_rfc3339(denied["retry_at"].as_str().unwrap())
        .unwrap()
        .with_timezone(&Utc);
    assert!((55..=60).contains(&(until - Utc::now()).num_seconds()));
    let replay = decision(&state, &owner, c.id, first, false).await;
    assert_eq!(replay["retry_at"], denied["retry_at"]);
    let other = another_device(&state, &peer).await;
    let suppressed = ask(&state, &other, c.id, Uuid::new_v4()).await;
    assert_eq!(suppressed["status"], "cooldown");
    assert_eq!(suppressed["retry_at"], denied["retry_at"]);
    let queue = serde_json::to_value(
        approval::pending(State(state.clone()), owner.clone(), Path(c.id))
            .await
            .unwrap()
            .0,
    )
    .unwrap();
    assert!(queue["items"].as_array().unwrap().is_empty());
    expiry(&state, c.id, peer.user_id).await;
    let second = Uuid::new_v4();
    ask(&state, &peer, c.id, second).await;
    let denied = decision(&state, &owner, c.id, second, false).await;
    let until = DateTime::parse_from_rfc3339(denied["retry_at"].as_str().unwrap())
        .unwrap()
        .with_timezone(&Utc);
    assert!((595..=600).contains(&(until - Utc::now()).num_seconds()));
    let level: i16 = sqlx::query_scalar(
        "SELECT denial_level FROM conference_join_cooldowns WHERE conference_id=$1 AND user_id=$2",
    )
    .bind(c.id)
    .bind(peer.user_id)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(level, 2);
    fixture.abort();
}
#[tokio::test]
#[ignore = "requires dedicated conference fixture services"]
async fn approval_resets_escalation_and_rooms_have_independent_cooldowns() {
    let (state, _, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let peer = fixture_user(&state.db).await;
    let c = room(&state, &owner).await;
    let first = Uuid::new_v4();
    ask(&state, &peer, c.id, first).await;
    decision(&state, &owner, c.id, first, false).await;
    let other_owner = fixture_user(&state.db).await;
    let other = room(&state, &other_owner).await;
    assert_eq!(
        ask(&state, &peer, other.id, Uuid::new_v4()).await["status"],
        "pending"
    );
    expiry(&state, c.id, peer.user_id).await;
    let second = Uuid::new_v4();
    ask(&state, &peer, c.id, second).await;
    decision(&state, &owner, c.id, second, true).await;
    let count: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM conference_join_cooldowns WHERE conference_id=$1 AND user_id=$2",
    )
    .bind(c.id)
    .bind(peer.user_id)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(count, 0);
    fixture.abort();
}
#[tokio::test]
#[ignore = "requires dedicated conference fixture services"]
async fn admitted_recovery_survives_absence_but_explicit_leave_requires_new_approval() {
    let (state, _, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let peer = fixture_user(&state.db).await;
    let c = room(&state, &owner).await;
    let id = Uuid::new_v4();
    ask(&state, &peer, c.id, id).await;
    decision(&state, &owner, c.id, id, true).await;
    let first = join(
        State(state.clone()),
        peer.clone(),
        Path(c.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    let retry = join(
        State(state.clone()),
        peer.clone(),
        Path(c.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(first.participant_identity, retry.participant_identity);
    // Simulate the authoritative absence reaper, without the explicit leave endpoint.
    sqlx::query("DELETE FROM conference_members WHERE user_id=$1")
        .bind(peer.user_id)
        .execute(&state.db)
        .await
        .unwrap();
    let recovered = join(
        State(state.clone()),
        peer.clone(),
        Path(c.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    assert_ne!(first.participant_identity, recovered.participant_identity);
    let _ = leave(State(state.clone()), peer.clone(), Path(c.id))
        .await
        .unwrap();
    needs_approval(
        join(
            State(state.clone()),
            peer.clone(),
            Path(c.id),
            Json(join_request()),
        )
        .await,
    );
    fixture.abort();
}
#[tokio::test]
#[ignore = "requires dedicated conference fixture services"]
async fn approved_request_cannot_be_used_by_another_device_or_new_session_family() {
    let (state, _, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let peer = fixture_user(&state.db).await;
    let c = room(&state, &owner).await;
    let id = Uuid::new_v4();
    ask(&state, &peer, c.id, id).await;
    decision(&state, &owner, c.id, id, true).await;
    let other = another_device(&state, &peer).await;
    needs_approval(
        join(
            State(state.clone()),
            other.clone(),
            Path(c.id),
            Json(join_request()),
        )
        .await,
    );
    assert!(
        approval::read(State(state.clone()), other, Path((c.id, id)))
            .await
            .is_err()
    );
    let fresh = AuthUser {
        session_id: Uuid::new_v4(),
        ..peer.clone()
    };
    fixture_session(&state.db, &fresh).await;
    needs_approval(
        join(
            State(state.clone()),
            fresh,
            Path(c.id),
            Json(join_request()),
        )
        .await,
    );
    let _ = join(
        State(state.clone()),
        peer.clone(),
        Path(c.id),
        Json(join_request()),
    )
    .await
    .unwrap();
    fixture.abort();
}
#[tokio::test]
#[ignore = "requires dedicated conference fixture services"]
async fn cancellation_before_or_after_post_cannot_resurrect_a_waiting_request() {
    let (state, _, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let peer = fixture_user(&state.db).await;
    let c = room(&state, &owner).await;
    let before = Uuid::new_v4();
    let _ = approval::cancel(State(state.clone()), peer.clone(), Path((c.id, before)))
        .await
        .unwrap();
    assert_eq!(
        ask(&state, &peer, c.id, before).await["status"],
        "cancelled"
    );
    let after = Uuid::new_v4();
    ask(&state, &peer, c.id, after).await;
    let _ = approval::cancel(State(state.clone()), peer.clone(), Path((c.id, after)))
        .await
        .unwrap();
    assert_eq!(ask(&state, &peer, c.id, after).await["status"], "cancelled");
    let queue = serde_json::to_value(
        approval::pending(State(state.clone()), owner.clone(), Path(c.id))
            .await
            .unwrap()
            .0,
    )
    .unwrap();
    assert!(queue["items"].as_array().unwrap().is_empty());
    assert!(approval::decide(
        State(state.clone()),
        owner,
        Path((c.id, after)),
        Json(approval::Decision { approve: true })
    )
    .await
    .is_err());
    fixture.abort();
}
#[tokio::test]
#[ignore = "requires dedicated conference fixture services"]
async fn simultaneous_approve_and_reject_have_one_immutable_outcome() {
    let (state, _, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let peer = fixture_user(&state.db).await;
    let c = room(&state, &owner).await;
    let id = Uuid::new_v4();
    ask(&state, &peer, c.id, id).await;
    let (a, b) = tokio::join!(
        approval::decide(
            State(state.clone()),
            owner.clone(),
            Path((c.id, id)),
            Json(approval::Decision { approve: true })
        ),
        approval::decide(
            State(state.clone()),
            owner.clone(),
            Path((c.id, id)),
            Json(approval::Decision { approve: false })
        )
    );
    assert_ne!(a.is_ok(), b.is_ok());
    let status: String =
        sqlx::query_scalar("SELECT status FROM conference_join_requests WHERE id=$1")
            .bind(id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    let count: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM conference_join_cooldowns WHERE conference_id=$1 AND user_id=$2",
    )
    .bind(c.id)
    .bind(peer.user_id)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(count, if status == "denied" { 1 } else { 0 });
    fixture.abort();
}
#[tokio::test]
#[ignore = "requires dedicated conference fixture services"]
async fn disabling_approval_releases_waiter_and_old_approval_never_survives_access_revision() {
    let (state, _, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let peer = fixture_user(&state.db).await;
    let c = room(&state, &owner).await;
    let id = Uuid::new_v4();
    ask(&state, &peer, c.id, id).await;
    let value = update_settings(
        State(state.clone()),
        owner.clone(),
        Path(c.id),
        Json(UpdateSettingsRequest {
            approval_required: Some(false),
            expected_revision: Some(c.settings_revision),
            ..Default::default()
        }),
    )
    .await
    .unwrap()
    .0;
    let read = serde_json::to_value(
        approval::read(State(state.clone()), peer.clone(), Path((c.id, id)))
            .await
            .unwrap()
            .0,
    )
    .unwrap();
    assert_eq!(read["status"], "not_required");
    assert!(!value.approval_required);
    let _ = join(
        State(state.clone()),
        peer.clone(),
        Path(c.id),
        Json(join_request()),
    )
    .await
    .unwrap();
    let _ = leave(State(state.clone()), peer.clone(), Path(c.id))
        .await
        .unwrap();
    let _ = update_settings(
        State(state.clone()),
        owner.clone(),
        Path(c.id),
        Json(UpdateSettingsRequest {
            approval_required: Some(true),
            expected_revision: Some(value.settings_revision),
            ..Default::default()
        }),
    )
    .await
    .unwrap();
    needs_approval(join(State(state.clone()), peer, Path(c.id), Json(join_request())).await);
    fixture.abort();
}
#[tokio::test]
#[ignore = "requires dedicated conference fixture services"]
async fn default_off_preserves_legacy_join_and_private_metadata_does_not_leak_to_wrong_invitation()
{
    let (state, _, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let peer = fixture_user(&state.db).await;
    let c = create(State(state.clone()), owner.clone(), Json(create_request(3)))
        .await
        .unwrap()
        .0;
    assert!(!c.approval_required);
    let _ = join(
        State(state.clone()),
        owner,
        Path(c.id),
        Json(join_request()),
    )
    .await
    .unwrap();
    let bad = approval::Request {
        request_id: Uuid::new_v4(),
        invite_secret: Some(URL_SAFE_NO_PAD.encode([8u8; 32])),
        password: None,
    };
    assert!(
        approval::request(State(state.clone()), peer.clone(), Path(c.id), Json(bad))
            .await
            .is_err()
    );
    let _ = join(State(state.clone()), peer, Path(c.id), Json(join_request()))
        .await
        .unwrap();
    fixture.abort();
}

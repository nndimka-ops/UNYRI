//! Transport moderation; a removed participant's already known media key is
//! not erased or rotated. This must never be presented as cryptographic rekey.
use super::*;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AudioRequest {
    pub participant_identity: String,
    pub blocked: bool,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RemoveRequest {
    pub participant_identity: String,
}

#[derive(sqlx::FromRow)]
struct Policy {
    user_id: Uuid,
    device_id: Uuid,
    admission_id: Uuid,
    audio_publish_blocked: bool,
    removed: bool,
    needs_sync: bool,
}
impl Policy {
    fn identity(&self) -> String {
        livekit::participant_identity(self.user_id, self.device_id, self.admission_id)
    }
}

fn parse_target(user: Uuid, identity: &str) -> Result<(Uuid, Uuid), AppError> {
    let parts: Vec<_> = identity.split(':').collect();
    if parts.len() != 3 || identity.len() != 110 {
        return Err(AppError::Validation("Invalid participant identity.".into()));
    }
    let ids: Vec<Uuid> = parts
        .iter()
        .map(|part| Uuid::parse_str(part))
        .collect::<Result<_, _>>()
        .map_err(|_| AppError::Validation("Invalid participant identity.".into()))?;
    if ids[0] != user || identity != livekit::participant_identity(ids[0], ids[1], ids[2]) {
        return Err(AppError::Validation(
            "Participant identity does not match the target.".into(),
        ));
    }
    Ok((ids[1], ids[2]))
}

pub(super) async fn admission_audio_policy(
    tx: &mut Transaction<'_, Postgres>,
    room: Uuid,
    user: Uuid,
) -> Result<bool, AppError> {
    let policy: Option<(bool, bool, bool)> = sqlx::query_as(
        "SELECT audio_publish_blocked,removed,needs_sync FROM conference_participant_policy WHERE conference_id=$1 AND user_id=$2")
        .bind(room).bind(user).fetch_optional(&mut **tx).await?;
    match policy {
        Some((_, true, _)) => Err(AppError::Gone(
            "You were removed from this conference.".into(),
        )),
        Some((_, _, true)) => Err(AppError::Unavailable(
            "Conference participant policy is being applied.".into(),
        )),
        Some((blocked, _, _)) => Ok(blocked),
        None => Ok(false),
    }
}

pub async fn set_audio(
    State(state): State<AppState>,
    auth: AuthUser,
    Path((room, user)): Path<(Uuid, Uuid)>,
    Json(request): Json<AudioRequest>,
) -> Result<Json<ConferenceDto>, AppError> {
    change(
        &state,
        &auth,
        room,
        user,
        &request.participant_identity,
        Some(request.blocked),
    )
    .await?;
    Ok(Json(dto(&state, room).await?))
}
pub async fn remove(
    State(state): State<AppState>,
    auth: AuthUser,
    Path((room, user)): Path<(Uuid, Uuid)>,
    Json(request): Json<RemoveRequest>,
) -> Result<Json<ConferenceDto>, AppError> {
    change(
        &state,
        &auth,
        room,
        user,
        &request.participant_identity,
        None,
    )
    .await?;
    Ok(Json(dto(&state, room).await?))
}

async fn change(
    state: &AppState,
    auth: &AuthUser,
    id: Uuid,
    user: Uuid,
    identity: &str,
    audio: Option<bool>,
) -> Result<(), AppError> {
    let config = configured()?;
    limit(state, auth, "participant_moderation", 60).await?;
    let (device, admission) = parse_target(user, identity)?;
    let mut tx = state.db.begin().await?;
    let room = lock_room(&mut tx, id).await?;
    if room.owner_user_id != auth.user_id {
        return Err(not_found());
    }
    lock_active_user(&mut tx, auth).await?;
    if user == auth.user_id {
        return Err(AppError::Validation(
            "Use your own microphone or Leave control.".into(),
        ));
    }
    if room.state != "open"
        || room.expires_at <= Utc::now()
        || first_join_expired(&room, Utc::now())
    {
        return Err(AppError::Gone("Conference has ended.".into()));
    }
    if !room_healthy(&mut tx, id).await? {
        return Err(AppError::Conflict(
            "Conference authorization changed; the room is closing.".into(),
        ));
    }
    let prior: Option<Policy> = sqlx::query_as("SELECT user_id,device_id,admission_id,audio_publish_blocked,removed,needs_sync FROM conference_participant_policy WHERE conference_id=$1 AND user_id=$2")
        .bind(id).bind(user).fetch_optional(&mut *tx).await?;
    let current: Option<(Uuid, Uuid)> = sqlx::query_as("SELECT device_id,admission_id FROM conference_members WHERE conference_id=$1 AND user_id=$2")
        .bind(id).bind(user).fetch_optional(&mut *tx).await?;
    if let Some(prior) = &prior {
        if prior.removed {
            if audio.is_none() && prior.identity() == identity {
                tx.commit().await?;
                return apply_room(state, &config, id, true).await;
            }
            return Err(AppError::Gone(
                "The participant was removed from this conference.".into(),
            ));
        }
    }
    if current != Some((device, admission)) {
        return Err(AppError::Conflict(
            "The participant changed; refresh the participant list.".into(),
        ));
    }
    let removed = audio.is_none();
    sqlx::query("INSERT INTO conference_participant_policy(conference_id,user_id,device_id,admission_id,audio_publish_blocked,removed,needs_sync) VALUES($1,$2,$3,$4,$5,$6,true) ON CONFLICT(conference_id,user_id) DO UPDATE SET device_id=EXCLUDED.device_id,admission_id=EXCLUDED.admission_id,audio_publish_blocked=EXCLUDED.audio_publish_blocked,removed=conference_participant_policy.removed OR EXCLUDED.removed,needs_sync=true,updated_at=now()")
        .bind(id).bind(user).bind(device).bind(admission).bind(audio.unwrap_or(true)).bind(removed)
        .execute(&mut *tx).await?;
    // Persist denial before network I/O. A crashed worker cannot roll back the
    // ban, issue another full token, or forget that SFU enforcement is pending.
    tx.commit().await?;
    apply_room(state, &config, id, true).await
}

async fn apply_room(
    state: &AppState,
    config: &ConferenceConfig,
    id: Uuid,
    explicit: bool,
) -> Result<(), AppError> {
    let mut tx = state.db.begin().await?;
    let room = lock_room(&mut tx, id).await?;
    if room.state != "open" || room.expires_at <= Utc::now() {
        return if explicit {
            Err(AppError::Gone("Conference has ended.".into()))
        } else {
            Ok(())
        };
    }
    let policies: Vec<Policy> = sqlx::query_as("SELECT user_id,device_id,admission_id,audio_publish_blocked,removed,needs_sync FROM conference_participant_policy WHERE conference_id=$1 ORDER BY user_id")
        .bind(id).fetch_all(&mut *tx).await?;
    let mut failed = false;
    for policy in &policies {
        if !policy.needs_sync {
            continue;
        }
        let result = if policy.removed {
            livekit::remove_identity(config, id, &policy.identity()).await
        } else {
            livekit::set_audio_policy(config, id, &policy.identity(), policy.audio_publish_blocked)
                .await
        };
        if result.is_err() {
            failed = true;
            break;
        }
        if policy.removed {
            sqlx::query("DELETE FROM conference_members WHERE conference_id=$1 AND user_id=$2 AND device_id=$3 AND admission_id=$4")
                .bind(id).bind(policy.user_id).bind(policy.device_id).bind(policy.admission_id).execute(&mut *tx).await?;
        }
        sqlx::query("UPDATE conference_participant_policy SET needs_sync=false WHERE conference_id=$1 AND user_id=$2")
            .bind(id).bind(policy.user_id).execute(&mut *tx).await?;
    }
    // A handshake authorized before the committed policy can finish later.
    // Reconcile that SFU roster as well, not just future gateway connections.
    if !failed {
        let present = livekit::moderation_participants(config, id).await;
        let roster_failed = present.is_err();
        if let Ok(Some(present)) = present {
            let admitted: Vec<(Uuid, Uuid, Uuid)> = sqlx::query_as("SELECT user_id,device_id,admission_id FROM conference_members WHERE conference_id=$1")
                .bind(id).fetch_all(&mut *tx).await?;
            for participant in present {
                let member = admitted.iter().find(|(user, device, admission)| {
                    livekit::participant_identity(*user, *device, *admission)
                        == participant.identity
                });
                let policy = member.and_then(|(user, _, _)| {
                    policies.iter().find(|policy| policy.user_id == *user)
                });
                let result = if member.is_none() || policy.is_some_and(|policy| policy.removed) {
                    livekit::remove_identity(config, id, &participant.identity).await
                } else if policy.is_some_and(|policy| policy.audio_publish_blocked)
                    && !participant.audio_restricted
                {
                    livekit::set_audio_policy(config, id, &participant.identity, true).await
                } else {
                    Ok(())
                };
                if result.is_err() {
                    failed = true;
                    break;
                }
            }
        } else if roster_failed {
            // A previous confirmed mutation does not rule out a late handshake
            // authorized before the policy commit. When roster verification is
            // unavailable, re-enforce every durable denied identity/current
            // audio-blocked admission directly, not only needs_sync rows.
            let admitted: Vec<(Uuid, Uuid, Uuid)> = sqlx::query_as("SELECT user_id,device_id,admission_id FROM conference_members WHERE conference_id=$1")
                .bind(id).fetch_all(&mut *tx).await?;
            for policy in &policies {
                let result = if policy.removed {
                    livekit::remove_identity(config, id, &policy.identity()).await
                } else if policy.audio_publish_blocked {
                    if let Some((user, device, admission)) =
                        admitted.iter().find(|(user, _, _)| *user == policy.user_id)
                    {
                        livekit::set_audio_policy(
                            config,
                            id,
                            &livekit::participant_identity(*user, *device, *admission),
                            true,
                        )
                        .await
                    } else {
                        Ok(())
                    }
                } else {
                    Ok(())
                };
                if result.is_err() {
                    failed = true;
                    break;
                }
            }
        }
        // A roster read failure alone does not close the room when all direct
        // durable restrictions were confirmed. A direct RPC failure does.
    }
    if failed {
        sqlx::query("UPDATE conferences SET state='ending',ended_at=COALESCE(ended_at,now()),end_reason=COALESCE(end_reason,'moderation_enforcement_failed') WHERE id=$1 AND state='open'")
            .bind(id).execute(&mut *tx).await?;
        tx.commit().await?;
        // Existing closure logic retains busy slots until DeleteRoom confirms.
        let _ = finish_closure(state, config, id).await;
        return Err(AppError::Unavailable(
            "Participant moderation could not be confirmed; the room is closing safely.".into(),
        ));
    }
    tx.commit().await?;
    Ok(())
}

pub(super) async fn reconcile(state: &AppState, config: &ConferenceConfig) -> Result<(), AppError> {
    let ids: Vec<Uuid> = sqlx::query_scalar("SELECT DISTINCT c.id FROM conferences c JOIN conference_participant_policy p ON p.conference_id=c.id WHERE c.state='open' ORDER BY c.id")
        .fetch_all(&state.db).await?;
    for id in ids {
        if apply_room(state, config, id, false).await.is_err() {
            tracing::warn!(conference_id=%id,"conference participant moderation reconciliation pending");
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn participant_generation_is_canonical_and_bound_to_target() {
        let (user, device, admission) = (Uuid::new_v4(), Uuid::new_v4(), Uuid::new_v4());
        let identity = livekit::participant_identity(user, device, admission);
        assert_eq!(parse_target(user, &identity).unwrap(), (device, admission));
        for invalid in [
            identity.to_uppercase(),
            format!("{identity}:extra"),
            identity.replace(':', "/"),
            "".into(),
        ] {
            assert!(parse_target(user, &invalid).is_err());
        }
        assert!(parse_target(Uuid::new_v4(), &identity).is_err());
    }
    #[test]
    fn moderation_requests_are_strict_and_do_not_accept_key_or_unmute_commands() {
        assert!(serde_json::from_value::<AudioRequest>(
            serde_json::json!({"participant_identity":"fixture","blocked":true})
        )
        .is_ok());
        for invalid in [
            serde_json::json!({"participant_identity":"fixture"}),
            serde_json::json!({"participant_identity":"fixture","blocked":1}),
            serde_json::json!({"participant_identity":"fixture","blocked":true,"media_key":"forbidden"}),
            serde_json::json!({"participant_identity":"fixture","blocked":false,"enable_microphone":true}),
        ] {
            assert!(serde_json::from_value::<AudioRequest>(invalid).is_err());
        }
        assert!(serde_json::from_value::<RemoveRequest>(
            serde_json::json!({"participant_identity":"fixture","user_id":Uuid::new_v4()})
        )
        .is_err());
    }
}

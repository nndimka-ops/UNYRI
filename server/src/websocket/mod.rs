pub mod handler;
pub mod protocol;
pub mod registry;

use uuid::Uuid;

use crate::state::AppState;
use crate::websocket::protocol::ServerMessage;

/// Serializes `message` once and pushes it to every active connection of
/// `device_id`. A device with no active connection just misses the realtime
/// push - Postgres remains the source of truth, so it'll catch up via
/// `GET /chats/{chat_id}/messages` on its next sync.
pub async fn notify_device(state: &AppState, device_id: Uuid, message: &ServerMessage) {
    if !crate::users::blocks::allows_event(&state.db, device_id, message).await {
        return;
    }
    match serde_json::to_string(message) {
        Ok(payload) => state.ws_registry.send_to_device(device_id, &payload).await,
        Err(err) => tracing::error!(%err, "failed to serialize websocket server message"),
    }
}

/// Pushes to every active device of `user_id` - only appropriate for
/// events that carry no per-device ciphertext (receipts, presence, etc);
/// actual message envelopes must always go through [notify_device] instead,
/// addressed to the one device that can actually decrypt that payload.
pub async fn notify_user(state: &AppState, user_id: Uuid, message: &ServerMessage) {
    let devices: Vec<(Uuid,)> =
        match sqlx::query_as("SELECT id FROM devices WHERE user_id = $1 AND revoked_at IS NULL")
            .bind(user_id)
            .fetch_all(&state.db)
            .await
        {
            Ok(rows) => rows,
            Err(err) => {
                tracing::error!(%err, "failed to look up devices for notify_user");
                return;
            }
        };
    for (device_id,) in devices {
        notify_device(state, device_id, message).await;
    }
}


/// Ephemeral opt-in presence, no text, persistence, push, or delivery queue.
/// Transport security is WSS; unlike message bodies this metadata is visible to the service.
pub async fn relay_activity(state: &AppState, auth: &crate::auth::middleware::AuthUser, chat_id: Uuid, activity: String) -> Result<(), crate::error::AppError> {
    if !matches!(activity.as_str(), "typing" | "recording" | "uploading" | "idle") { return Ok(()); }
    // The socket retains its original identity across refresh rotation. Follow
    // the same session family as other socket events, while still checking
    // current chat membership on every hint.
    if !crate::auth::middleware::socket_session_active(state, auth).await? { return Ok(()); }
    crate::chats::policy::require_device_contact_allowed(&state.db, chat_id, auth.device_id).await?;
    let allowed: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM chat_members WHERE chat_id = $1 AND user_id = $2 AND left_at IS NULL)")
        .bind(chat_id).bind(auth.user_id).fetch_one(&state.db).await?;
    if !allowed { return Ok(()); }
    let peers: Vec<Uuid> = sqlx::query_scalar("SELECT user_id FROM chat_members WHERE chat_id = $1 AND user_id != $2 AND left_at IS NULL LIMIT 64")
        .bind(chat_id).bind(auth.user_id).fetch_all(&state.db).await?;
    let event = ServerMessage::ChatActivity { chat_id, user_id: auth.user_id, activity };
    for peer in peers { notify_user(state, peer, &event).await; }
    Ok(())
}

/// Ephemeral ciphertext only: no DB writes, push, history, retries or payload logs.
pub async fn relay_draft(state: &AppState, auth: &crate::auth::middleware::AuthUser,
    chat_id: Uuid, stream: Uuid, revision: u64, payload: String,
    envelopes: Vec<protocol::DraftEnvelope>) -> Result<(), crate::error::AppError> {
    if payload.len() > 16384 || envelopes.is_empty() || envelopes.len() > 16 ||
        envelopes.iter().any(|e| e.ciphertext.len() > 8192 || !matches!(e.message_type, 1 | 2)) { return Ok(()); }
    if !crate::auth::middleware::socket_session_active(state, auth).await? { return Ok(()); }
    crate::chats::policy::require_device_contact_allowed(&state.db, chat_id, auth.device_id).await?;
    let peers: Vec<Uuid> = sqlx::query_scalar(
        "SELECT d.id FROM devices d JOIN chat_members peer ON peer.user_id = d.user_id
         WHERE peer.chat_id = $1 AND peer.left_at IS NULL AND d.revoked_at IS NULL
         AND NOT user_device_chat_blocked(d.id,$1) AND NOT user_device_blocked(d.id,$2)
         AND d.user_id != $2 AND EXISTS(SELECT 1 FROM chat_members own WHERE own.chat_id = $1 AND own.user_id = $2 AND own.left_at IS NULL)")
        .bind(chat_id).bind(auth.user_id).fetch_all(&state.db).await?;
    let mut sent = std::collections::HashSet::new();
    for envelope in envelopes {
        let target = envelope.recipient_device_id;
        if peers.contains(&target) && sent.insert(target) {
            let event = ServerMessage::ChatDraft { chat_id, user_id: auth.user_id, device_id: auth.device_id,
                stream, revision, payload: payload.clone(), envelope };
            if let Ok(wire) = serde_json::to_string(&event) { state.ws_registry.send_ephemeral(target, &wire).await; }
        }
    }
    Ok(())
}

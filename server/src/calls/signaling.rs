use uuid::Uuid;

use crate::auth::middleware::AuthUser;
use crate::calls::handlers::mark_connecting;
use crate::state::AppState;
use crate::websocket;
use crate::websocket::protocol::ServerMessage;

/// Raw SDP/ICE relay for an in-progress call, invoked directly from the
/// authenticated WebSocket loop (`websocket::handler`) - NOT an HTTP
/// endpoint, since these need low-latency trickle delivery, not
/// request/response semantics. The backend never parses SDP/ICE content; it
/// only checks the sender is an authorized participant of `call_id` (never
/// trusting any identity claim in the payload itself - Этап J п.14/15) and
/// relays the opaque string to the correct other party.
struct Participants {
    caller_user_id: Uuid,
    callee_user_id: Uuid,
    accepted_device_id: Option<Uuid>,
    state: String,
}

async fn load(state: &AppState, call_id: Uuid, device: Uuid) -> Option<Participants> {
    sqlx::query_as::<_, (Uuid, Uuid, Option<Uuid>, String)>(
        "SELECT caller_user_id, callee_user_id, accepted_device_id, state FROM calls WHERE id = $1 AND NOT user_device_call_blocked($2,id)",
    )
    .bind(call_id)
    .bind(device)
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten()
    .map(|(caller_user_id, callee_user_id, accepted_device_id, state)| Participants {
        caller_user_id,
        callee_user_id,
        accepted_device_id,
        state,
    })
}

pub async fn relay_offer(state: &AppState, auth: &AuthUser, call_id: Uuid, sdp: String) -> Result<(), &'static str> {
    let call = load(state, call_id, auth.device_id).await.ok_or("call not found")?;
    if call.state == "ended" {
        return Err("call has ended");
    }
    // Initial offer: only the caller (routed to the callee). Renegotiation
    // offer (N.1A audio->video): either party may offer.
    relay_to_other(state, auth, &call, &ServerMessage::CallOffer { call_id, sdp }).await
}

pub async fn relay_answer(state: &AppState, auth: &AuthUser, call_id: Uuid, sdp: String) -> Result<(), &'static str> {
    let call = load(state, call_id, auth.device_id).await.ok_or("call not found")?;
    if call.state == "ended" {
        return Err("call has ended");
    }
    // Authorize before changing state/deadlines; a rejected frame must have no effects.
    if auth.user_id != call.caller_user_id
        && !(auth.user_id == call.callee_user_id && Some(auth.device_id) == call.accepted_device_id) {
        return Err("not authorized for this call");
    }
    // Only the initial answer moves accepted -> connecting. A renegotiation
    // answer must not touch the DB deadline/state.
    if call.state == "accepted" {
        mark_connecting(state, call_id).await;
    }
    relay_to_other(state, auth, &call, &ServerMessage::CallAnswer { call_id, sdp }).await
}

/// Relays an opaque SDP/ICE/upgrade frame to the OTHER participant of the
/// call: caller -> accepted callee device (or all callee devices before
/// accept), accepted callee device -> caller. Never trusts a payload-claimed
/// identity - the sender is always the authenticated connection's own user.
async fn relay_to_other(
    state: &AppState,
    auth: &AuthUser,
    call: &Participants,
    message: &ServerMessage,
) -> Result<(), &'static str> {
    if auth.user_id == call.caller_user_id {
        match call.accepted_device_id {
            Some(device_id) => websocket::notify_device(state, device_id, message).await,
            None => websocket::notify_user(state, call.callee_user_id, message).await,
        }
    } else if auth.user_id == call.callee_user_id && Some(auth.device_id) == call.accepted_device_id {
        websocket::notify_user(state, call.caller_user_id, message).await;
    } else {
        return Err("not authorized for this call");
    }
    Ok(())
}

pub async fn relay_ice(
    state: &AppState,
    auth: &AuthUser,
    call_id: Uuid,
    candidate: String,
    sdp_mid: Option<String>,
    sdp_m_line_index: Option<i32>,
) -> Result<(), &'static str> {
    let call = load(state, call_id, auth.device_id).await.ok_or("call not found")?;
    if call.state == "ended" {
        return Err("call has ended");
    }
    let message = ServerMessage::CallIce { call_id, candidate, sdp_mid, sdp_m_line_index };
    if auth.user_id == call.caller_user_id {
        match call.accepted_device_id {
            Some(device_id) => websocket::notify_device(state, device_id, &message).await,
            None => websocket::notify_user(state, call.callee_user_id, &message).await,
        }
    } else if auth.user_id == call.callee_user_id && Some(auth.device_id) == call.accepted_device_id {
        websocket::notify_user(state, call.caller_user_id, &message).await;
    } else {
        return Err("not authorized to send ICE for this call");
    }
    Ok(())
}

/// Shared relay for the transient N.1A upgrade frames: same authorization
/// and routing as ICE, no DB writes (the upgrade request lives only on the
/// wire - a lost request is simply re-sent by the requestor).
async fn relay_upgrade_frame(
    state: &AppState,
    auth: &AuthUser,
    call_id: Uuid,
    build: impl FnOnce(Uuid) -> ServerMessage,
) -> Result<(), &'static str> {
    let call = load(state, call_id, auth.device_id).await.ok_or("call not found")?;
    if call.state == "ended" {
        return Err("call has ended");
    }
    relay_to_other(state, auth, &call, &build(call_id)).await
}

pub async fn relay_video_upgrade_request(state: &AppState, auth: &AuthUser, call_id: Uuid) -> Result<(), &'static str> {
    relay_upgrade_frame(state, auth, call_id, |id| ServerMessage::CallVideoUpgradeRequest { call_id: id }).await
}

pub async fn relay_video_upgrade_accept(state: &AppState, auth: &AuthUser, call_id: Uuid) -> Result<(), &'static str> {
    relay_upgrade_frame(state, auth, call_id, |id| ServerMessage::CallVideoUpgradeAccept { call_id: id }).await
}

pub async fn relay_video_upgrade_reject(state: &AppState, auth: &AuthUser, call_id: Uuid) -> Result<(), &'static str> {
    relay_upgrade_frame(state, auth, call_id, |id| ServerMessage::CallVideoUpgradeReject { call_id: id }).await
}

pub async fn relay_video_upgrade_cancel(state: &AppState, auth: &AuthUser, call_id: Uuid) -> Result<(), &'static str> {
    relay_upgrade_frame(state, auth, call_id, |id| ServerMessage::CallVideoUpgradeCancel { call_id: id }).await
}

pub async fn relay_video_state(state: &AppState, auth: &AuthUser, call_id: Uuid, enabled: bool) -> Result<(), &'static str> {
    relay_upgrade_frame(state, auth, call_id, |id| ServerMessage::CallVideoState { call_id: id, enabled }).await
}

/// N.2R call-liveness heartbeat: any participant of a non-ended call may
/// refresh it. There is no reply frame and nothing is relayed to the peer -
/// this exists purely to keep `calls.last_activity_at` fresh so the sweep
/// can distinguish a live (P2P media flows, signaling silent) call from one
/// abandoned by every device. A silent drop is correct: the sender's own
/// liveness is what matters, and it re-sends on its own interval.
pub async fn relay_keep_alive(state: &AppState, auth: &AuthUser, call_id: Uuid) -> Result<(), &'static str> {
    let call = load(state, call_id, auth.device_id).await.ok_or("call not found")?;
    if call.state == "ended" {
        return Err("call has ended");
    }
    let is_participant = auth.user_id == call.caller_user_id || auth.user_id == call.callee_user_id;
    if !is_participant {
        return Err("not authorized for this call");
    }
    // Authenticated media use counts even while the app has no visible screen.
    // Recheck the rotated session lineage and serialize with account deletion.
    crate::users::auto_delete::socket_foreground(state, auth).await
        .map_err(|_| "call keep-alive session is inactive")?;
    sqlx::query("UPDATE calls SET last_activity_at = now() WHERE id = $1 AND state <> 'ended'")
        .bind(call_id)
        .execute(&state.db)
        .await
        .map_err(|_| "call keep-alive failed")?;
    Ok(())
}

use uuid::Uuid;

use crate::auth::middleware::AuthUser;
use crate::calls::handlers::mark_connecting;
use crate::state::AppState;
use crate::websocket;
use crate::websocket::protocol::ServerMessage;

/// Largest relayed ICE candidate (real ones and the SAS reveal are far smaller).
pub const MAX_ICE_CANDIDATE_BYTES: usize = 2048;

/// Raw SDP/ICE relay for an in-progress call, invoked directly from the
/// authenticated WebSocket loop (`websocket::handler`) - NOT an HTTP
/// endpoint, since these need low-latency trickle delivery, not
/// request/response semantics. The backend never parses SDP/ICE content; it
/// only checks the sender is an authorized participant of `call_id` (never
/// trusting any identity claim in the payload itself - Этап J п.14/15) and
/// relays the opaque string to the correct other party.
#[derive(sqlx::FromRow)]
struct Participants {
    caller_user_id: Uuid,
    callee_user_id: Uuid,
    accepted_device_id: Option<Uuid>,
    caller_device_id: Uuid,
    caller_session_family: Uuid,
    accepted_session_family: Option<Uuid>,
    state: String,
}

async fn load(state: &AppState, call_id: Uuid, auth: &AuthUser) -> Option<Participants> {
    sqlx::query_as::<_, Participants>(
        "SELECT caller_user_id, callee_user_id, accepted_device_id, caller_device_id, caller_session_family, accepted_session_family, state FROM calls WHERE id = $1 AND call_endpoint_allowed(id,$2,$3,$4)",
    )
    .bind(call_id)
    .bind(auth.user_id).bind(auth.device_id).bind(auth.session_id)
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten()
}

pub async fn relay_offer(state: &AppState, auth: &AuthUser, call_id: Uuid, sdp: String) -> Result<(), &'static str> {
    let call = load(state, call_id, auth).await.ok_or("call not found")?;
    if call.state == "ended" {
        return Err("call has ended");
    }
    // Initial offer: only the caller (routed to the callee). Renegotiation
    // offer (N.1A audio->video): either party may offer.
    relay_to_other(state, auth, &call, &ServerMessage::CallOffer { call_id, sdp }).await
}

pub async fn relay_answer(state: &AppState, auth: &AuthUser, call_id: Uuid, sdp: String) -> Result<(), &'static str> {
    let call = load(state, call_id, auth).await.ok_or("call not found")?;
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
            Some(device_id) => {
                let family = call.accepted_session_family.ok_or("call endpoint unavailable")?;
                websocket::notify_family(state, device_id, family, message).await;
            }
            None => websocket::notify_user(state, call.callee_user_id, message).await,
        }
    } else if auth.user_id == call.callee_user_id && Some(auth.device_id) == call.accepted_device_id {
        websocket::notify_family(state, call.caller_device_id, call.caller_session_family, message).await;
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
    // Real candidates (and the in-band SAS reveal) are a few hundred bytes. A
    // caller's ICE reaches every callee device while it is still ringing, so
    // oversized values are refused before they can pile up there.
    if candidate.len() > MAX_ICE_CANDIDATE_BYTES || sdp_mid.as_ref().is_some_and(|mid| mid.len() > 64) {
        return Err("ICE candidate too large");
    }
    let call = load(state, call_id, auth).await.ok_or("call not found")?;
    if call.state == "ended" {
        return Err("call has ended");
    }
    let message = ServerMessage::CallIce { call_id, candidate, sdp_mid, sdp_m_line_index };
    relay_to_other(state, auth, &call, &message).await
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
    let call = load(state, call_id, auth).await.ok_or("call not found")?;
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
    let call = load(state, call_id, auth).await.ok_or("call not found")?;
    if call.state == "ended" {
        return Err("call has ended");
    }
    let is_participant = auth.user_id == call.caller_user_id || auth.user_id == call.callee_user_id;
    if !is_participant {
        return Err("not authorized for this call");
    }
    // Only the accepting callee device keeps an answered call alive.
    if auth.user_id == call.callee_user_id && call.state != "ringing"
        && call.accepted_device_id.is_some_and(|device| device != auth.device_id) {
        return Err("call is active on another device");
    }
    // Authenticated media use counts even while the app has no visible screen.
    // Recheck the rotated session lineage and serialize with account deletion.
    crate::users::auto_delete::socket_foreground(state, auth).await
        .map_err(|_| "call keep-alive session is inactive")?;
    let mut tx = state.db.begin().await.map_err(|_| "call keep-alive failed")?;
    sqlx::query("SELECT id FROM calls WHERE id=$1 FOR UPDATE")
        .bind(call_id).execute(&mut *tx).await.map_err(|_| "call keep-alive failed")?;
    sqlx::query("UPDATE calls SET last_activity_at = now() WHERE id = $1 AND state <> 'ended' AND call_endpoint_allowed(id,$2,$3,$4)")
        .bind(call_id)
        .bind(auth.user_id).bind(auth.device_id).bind(auth.session_id)
        .execute(&mut *tx)
        .await
        .map_err(|_| "call keep-alive failed")?;
    tx.commit().await.map_err(|_| "call keep-alive failed")?;
    Ok(())
}

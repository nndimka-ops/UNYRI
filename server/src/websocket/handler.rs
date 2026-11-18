use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::State;
use axum::response::IntoResponse;

use crate::auth::middleware::AuthUser;
use crate::state::AppState;
use crate::net::ClientIp;
use crate::error::AppError;
use crate::resources::{SocketReservation, MAX_SOCKET_MESSAGE_BYTES};
use crate::websocket::protocol::{ClientMessage, ServerMessage};

/// `auth` reuses the exact same JWT + live-session check as every HTTP
/// endpoint (`AuthUser` is a normal Axum extractor) - no separate WebSocket
/// auth path to keep in sync. The client authenticates by sending the
/// standard `Authorization: Bearer <access_token>` header on the upgrade
/// request (OkHttp on Android supports custom headers on WebSocket
/// handshakes), so a revoked/expired session is rejected with 401 before the
/// socket ever opens, identically to any other endpoint.
pub async fn ws_handler(ws: WebSocketUpgrade, State(state): State<AppState>, ClientIp(ip): ClientIp, headers: axum::http::HeaderMap, auth: AuthUser) -> Result<axum::response::Response, AppError> {
    crate::auth::rate_limit::enforce(&state,"ws:upgrade:ip",ip,240,60).await?;
    crate::auth::rate_limit::enforce(&state,&format!("ws:upgrade:user:{}",auth.user_id),std::net::Ipv4Addr::UNSPECIFIED.into(),120,60).await?;
    crate::auth::rate_limit::enforce(&state,&format!("ws:upgrade:device:{}",auth.device_id),std::net::Ipv4Addr::UNSPECIFIED.into(),60,60).await?;
    let reservation=state.resources.sockets.reserve(&auth,ip)?;
    // Before the upgrade response, so a call created right after this socket
    // registers already sees the device's current build (CALL_RINGING_SPEC §2.2).
    crate::calls::ringing::record_capability(&state.db, auth.device_id, crate::calls::ringing::advertises_ring_ack(&headers)).await;
    Ok(ws.max_message_size(MAX_SOCKET_MESSAGE_BYTES).max_frame_size(MAX_SOCKET_MESSAGE_BYTES)
        .on_upgrade(move |socket| handle_socket(socket, state, auth, reservation)).into_response())
}

async fn send_frame(socket: &mut WebSocket, frame: Message) -> bool {
    matches!(tokio::time::timeout(std::time::Duration::from_secs(5),socket.send(frame)).await,Ok(Ok(())))
}

async fn close_revoked(socket: &mut WebSocket, state: &AppState, auth: &AuthUser,
    control: &tokio::sync::watch::Receiver<Option<String>>) {
    // A DB commit may win authorization just before control dispatch, including
    // across server processes. Recover the canonical reason without draining data.
    let pending = control.borrow().clone();
    let payload = if let Some(payload) = pending { payload } else {
        let reason: String = sqlx::query_scalar(
            "SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM users WHERE id=$2) THEN 'account_deleted' \
             ELSE COALESCE((SELECT s.revocation_reason FROM sessions root JOIN sessions s ON s.family_id=root.family_id WHERE root.id=$1 AND s.revocation_reason IS NOT NULL ORDER BY s.created_at DESC LIMIT 1), \
             CASE WHEN EXISTS(SELECT 1 FROM devices d WHERE d.id=$3 AND (d.revoked_at IS NOT NULL OR EXISTS(SELECT 1 FROM installation_suspensions b WHERE b.installation_id IN (SELECT d.installation_id UNION SELECT va.client_installation_id FROM verified_installation_aliases va WHERE va.installation_id=d.verified_installation_id) AND (b.until_date IS NULL OR b.until_date>now())))) \
               OR EXISTS(SELECT 1 FROM server_suspensions WHERE user_id=$2 AND (until_date IS NULL OR until_date>now())) \
             THEN 'device_revoked' ELSE 'session_revoked' END) END",
        ).bind(auth.session_id).bind(auth.user_id).bind(auth.device_id).fetch_one(&state.db).await
            .unwrap_or_else(|_| "session_revoked".into());
        serde_json::to_string(&ServerMessage::SessionRevoked { reason }).unwrap()
    };
    let _ = send_frame(socket, Message::Text(payload)).await;
    let _ = send_frame(socket, Message::Close(None)).await;
}

fn read_only_allows_client_message(message: &ClientMessage) -> bool {
    matches!(
        message,
        ClientMessage::PresenceHeartbeat { .. }
            | ClientMessage::PresenceSubscribe { .. }
            | ClientMessage::Ping { .. }
            | ClientMessage::CallOffer { .. }
            | ClientMessage::CallAnswer { .. }
            | ClientMessage::CallIce { .. }
            | ClientMessage::CallKeepAlive { .. }
            | ClientMessage::CallVideoUpgradeReject { .. }
            | ClientMessage::CallVideoUpgradeCancel { .. }
            | ClientMessage::CallVideoState { enabled: false, .. }
    )
}

async fn handle_socket(mut socket: WebSocket, state: AppState, auth: AuthUser, mut reservation: SocketReservation) {
    let mut last_draft_relay: Option<std::time::Instant> = None;
    let mut last_activity_relay: Option<std::time::Instant> = None;
    let family: Option<uuid::Uuid> = sqlx::query_scalar("SELECT family_id FROM sessions WHERE id=$1")
        .bind(auth.session_id).fetch_optional(&state.db).await.ok().flatten();
    let Some(family) = family else { return; };
    let (conn_id, mut outgoing, mut terminal) = state.ws_registry.register_session(auth.device_id, Some(family)).await;
    tracing::info!(user_id = %auth.user_id, device_id = %auth.device_id, "websocket client connected");

    // Этап L.3: a live socket is activity - coalesced (never per-message).
    let touch_db = state.db.clone();
    let touch_device = auth.device_id;
    tokio::spawn(async move {
        crate::devices::handlers::touch_last_active(&touch_db, touch_device).await;
    });

    let mut session_check = tokio::time::interval(std::time::Duration::from_secs(15));
    let mut presence_tick = tokio::time::interval(std::time::Duration::from_secs(2));
    presence_tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut watched = Vec::new();
    let mut last_presence: Option<Vec<(uuid::Uuid, bool, Option<chrono::DateTime<chrono::Utc>>)>> = None;
    let mut last_live: Option<Vec<uuid::Uuid>> = None;
    let mut last_epoch = state.ws_registry.presence_epoch();
    let mut last_presence_sent = std::time::Instant::now();
    let mut last_snapshot_read = std::time::Instant::now();
    let mut last_items: Vec<crate::presence::PresenceItem> = Vec::new();
    // Set by presence.subscribe lease_ms (clients from 17 Nov); legacy clients cap
    // leases at 5 s and need a resend every 2 s (served from memory, no DB).
    let mut long_lease = false;
    let mut last_presence_heartbeat: Option<std::time::Instant> = None;
    let mut last_account_activity: Option<std::time::Instant> = None;
    let mut foreground_active = false;
    let mut last_subscription: Option<std::time::Instant> = None;
    let mut last_incoming=std::time::Instant::now();
    let mut last_session_validation=std::time::Instant::now();
    let mut idle_check=tokio::time::interval(std::time::Duration::from_secs(15));
    loop {
        tokio::select! {
            biased;
            changed = terminal.changed() => {
                if changed.is_ok() && terminal.borrow().is_some() {
                    outgoing.close();
                    while outgoing.try_recv().is_ok() {}
                    close_revoked(&mut socket, &state, &auth, &terminal).await;
                    break;
                }
                // Ordinary queue eviction closes control without a revocation.
                break;
            }
            _ = idle_check.tick() => {
                if last_incoming.elapsed()>=std::time::Duration::from_secs(120) { break; }
            }
            _ = presence_tick.tick(), if !watched.is_empty() => {
                // Owner, 17 Nov: presence is not a 2-second loop. The tick is in-memory;
                // the DB snapshot runs only when a watched user's online state or the
                // visibility epoch changed, and a frame goes out only on a visible change
                // or the lease refresh (PRESENCE_REFRESH, lease VALID_FOR_MS).
                let live = state.ws_registry.online_among(&watched).await;
                let epoch = state.ws_registry.presence_epoch();
                let (lease, refresh) = if long_lease {
                    (crate::presence::VALID_FOR_MS, crate::presence::PRESENCE_REFRESH)
                } else {
                    (crate::presence::LEGACY_VALID_FOR_MS, crate::presence::LEGACY_REFRESH)
                };
                let mut changed = false;
                if last_presence.is_none() || last_live.as_ref() != Some(&live) || last_epoch != epoch
                    || last_snapshot_read.elapsed() >= crate::presence::SNAPSHOT_REREAD {
                    match crate::presence::snapshot_for_device(&state, &auth, &watched).await {
                        Ok(items) => {
                            last_live = Some(live); last_epoch = epoch; last_snapshot_read = std::time::Instant::now();
                            let key = crate::presence::change_key(&items);
                            if last_presence.as_ref() != Some(&key) { last_presence = Some(key); changed = true; }
                            last_items = items;
                        }
                        Err(error) => { tracing::warn!(?error, "presence snapshot unavailable"); }
                    }
                }
                if last_presence.is_some() && (changed || last_presence_sent.elapsed() >= refresh) {
                    last_presence_sent = std::time::Instant::now();
                    let event = ServerMessage::PresenceSnapshot { items: last_items.clone(), observed_at: chrono::Utc::now(), valid_for_ms: lease };
                    if let Ok(payload) = serde_json::to_string(&event) {
                        if !send_frame(&mut socket,Message::Text(payload)).await { break; }
                    }
                }
            }
            _ = session_check.tick() => {
                if !crate::auth::middleware::socket_session_active(&state, &auth).await.unwrap_or(false) {
                    close_revoked(&mut socket, &state, &auth, &terminal).await; break;
                }
                last_session_validation=std::time::Instant::now();
            }
            incoming = socket.recv() => {
                if let Some(Ok(frame))=&incoming {
                    let bytes=match frame { Message::Text(text)=>text.len(), Message::Binary(data)|Message::Ping(data)|Message::Pong(data)=>data.len(),Message::Close(_)=>0 };
                    if !reservation.admit_frame(bytes) { break; }
                    last_incoming=std::time::Instant::now();
                    state.ws_registry.touch(auth.device_id, conn_id).await;
                }
                match incoming {
                    Some(Ok(Message::Text(text))) => {
                        let message = match serde_json::from_str::<ClientMessage>(&text) {
                            Ok(message) => message,
                            Err(err) => {
                                tracing::warn!(%err, "failed to parse client message");
                                let response = ServerMessage::Error { message: "invalid or unsupported message".to_string() };
                                let Ok(payload) = serde_json::to_string(&response) else { break };
                                if !send_frame(&mut socket,Message::Text(payload)).await { break; }
                                continue;
                            }
                        };
                        // Invalid frames spend no SQL. Pure heartbeat/PING may
                        // reuse a <=1s live decision; privacy reads and all
                        // application mutations still validate the session now.
                        let transport_only=matches!(&message,ClientMessage::Ping { .. }|ClientMessage::PresenceHeartbeat { .. });
                        if !transport_only || last_session_validation.elapsed()>=std::time::Duration::from_secs(1) {
                            if !crate::auth::middleware::socket_session_active(&state,&auth).await.unwrap_or(false) {
                                close_revoked(&mut socket, &state, &auth, &terminal).await; break;
                            }
                            last_session_validation=std::time::Instant::now();
                        }
                        if !read_only_allows_client_message(&message) {
                            match crate::auth::middleware::account_read_only(&state, auth.user_id).await {
                                Ok(true) => {
                                    let response = ServerMessage::Error { message: "account_read_only".to_string() };
                                    let Ok(payload) = serde_json::to_string(&response) else { break };
                                    if !send_frame(&mut socket,Message::Text(payload)).await { break; }
                                    continue;
                                }
                                Ok(false) => {}
                                Err(error) => {
                                    tracing::warn!(?error, "read-only state unavailable");
                                    break;
                                }
                            }
                        }
                        // Call signaling frames relay directly to the other
                        // party (via ConnectionRegistry, not back to this
                        // socket) and only produce a reply on failure - a
                        // successful relay should not add reply-frame noise
                        // to a low-latency SDP/ICE trickle path.
                        let response = match message {
                            ClientMessage::PresenceHeartbeat { active } => {
                                if active && last_account_activity.is_none_or(|at| at.elapsed() >= std::time::Duration::from_secs(60)) {
                                    if crate::users::auto_delete::socket_foreground(&state, &auth).await.is_err() { break; }
                                    last_account_activity = Some(std::time::Instant::now());
                                }
                                if active && last_presence_heartbeat.is_none_or(|at| at.elapsed() >= std::time::Duration::from_secs(1)) {
                                    state.ws_registry.foreground(auth.user_id, auth.device_id, conn_id, true).await;
                                    last_presence_heartbeat = Some(std::time::Instant::now()); foreground_active = true;
                                } else if !active && foreground_active {
                                    state.ws_registry.foreground(auth.user_id, auth.device_id, conn_id, false).await;
                                    foreground_active = false;
                                }
                                None
                            }
                            ClientMessage::PresenceSubscribe { mut user_ids, lease_ms } => {
                                long_lease = lease_ms.is_some_and(|ms| ms >= crate::presence::VALID_FOR_MS);
                                if user_ids.len() > crate::presence::MAX_SUBSCRIPTIONS {
                                    Some(ServerMessage::Error { message: "Too many presence subscriptions.".into() })
                                } else {
                                    user_ids.sort_unstable(); user_ids.dedup(); watched = user_ids;
                                    last_presence = None;
                                    if last_subscription.is_some_and(|at| at.elapsed() < std::time::Duration::from_millis(100)) { continue; }
                                    last_subscription = Some(std::time::Instant::now());
                                    match crate::presence::snapshot_for_device(&state, &auth, &watched).await {
                                        Ok(items) => {
                                            last_presence = Some(crate::presence::change_key(&items)); last_presence_sent = std::time::Instant::now();
                                            last_live = Some(state.ws_registry.online_among(&watched).await); last_epoch = state.ws_registry.presence_epoch();
                                            last_snapshot_read = std::time::Instant::now(); last_items = items.clone();
                                            let lease = if long_lease { crate::presence::VALID_FOR_MS } else { crate::presence::LEGACY_VALID_FOR_MS };
                                            Some(ServerMessage::PresenceSnapshot { items, observed_at: chrono::Utc::now(), valid_for_ms: lease })
                                        }
                                        Err(_) => Some(ServerMessage::Error { message: "Presence unavailable.".into() }),
                                    }
                                }
                            }
                            ClientMessage::ChatDraft { chat_id, stream, revision, payload, envelopes } => {
                                if last_draft_relay.is_none_or(|last| last.elapsed() >= std::time::Duration::from_millis(250)) {
                                    last_draft_relay = Some(std::time::Instant::now());
                                    let _ = crate::websocket::relay_draft(&state, &auth, chat_id, stream, revision, payload, envelopes).await;
                                }
                                None
                            }
                            ClientMessage::ChatActivity { chat_id, activity } => {
                                if last_activity_relay.is_none_or(|last| last.elapsed() >= std::time::Duration::from_millis(500)) {
                                    last_activity_relay = Some(std::time::Instant::now());
                                    {
                                        if let Err(error) = crate::websocket::relay_activity(&state, &auth, chat_id, activity).await {
                                            tracing::warn!(?error, "activity relay unavailable");
                                        }
                                        None
                                    }
                                } else { None }
                            }
                            ClientMessage::Ping { request_id } => Some(ServerMessage::Pong { request_id }),
                            ClientMessage::CallOffer { call_id, sdp } => {
                                relay_result_to_error(crate::calls::signaling::relay_offer(&state, &auth, call_id, sdp).await)
                            }
                            ClientMessage::CallAnswer { call_id, sdp } => {
                                relay_result_to_error(crate::calls::signaling::relay_answer(&state, &auth, call_id, sdp).await)
                            }
                            ClientMessage::CallIce { call_id, candidate, sdp_mid, sdp_m_line_index } => {
                                relay_result_to_error(
                                    crate::calls::signaling::relay_ice(&state, &auth, call_id, candidate, sdp_mid, sdp_m_line_index).await,
                                )
                            }
                            ClientMessage::CallVideoUpgradeRequest { call_id } => {
                                relay_result_to_error(crate::calls::signaling::relay_video_upgrade_request(&state, &auth, call_id).await)
                            }
                            ClientMessage::CallVideoUpgradeAccept { call_id } => {
                                relay_result_to_error(crate::calls::signaling::relay_video_upgrade_accept(&state, &auth, call_id).await)
                            }
                            ClientMessage::CallVideoUpgradeReject { call_id } => {
                                relay_result_to_error(crate::calls::signaling::relay_video_upgrade_reject(&state, &auth, call_id).await)
                            }
                            ClientMessage::CallVideoUpgradeCancel { call_id } => {
                                relay_result_to_error(crate::calls::signaling::relay_video_upgrade_cancel(&state, &auth, call_id).await)
                            }
                            ClientMessage::CallVideoState { call_id, enabled } => {
                                relay_result_to_error(crate::calls::signaling::relay_video_state(&state, &auth, call_id, enabled).await)
                            }
                            ClientMessage::CallKeepAlive { call_id } => {
                                relay_result_to_error(crate::calls::signaling::relay_keep_alive(&state, &auth, call_id).await)
                            }
                        };
                        let Some(response) = response else { continue };
                        let Ok(payload) = serde_json::to_string(&response) else { break };
                        if !send_frame(&mut socket,Message::Text(payload)).await {
                            break;
                        }
                    }
                    Some(Ok(Message::Close(_))) | None => break,
                    Some(Err(_)) => break,
                    _ => {}
                }
            }
            // Pushed by other request handlers (e.g. a new message) via
            // ConnectionRegistry::send_to_device - not read from the socket.
            pushed = outgoing.recv() => {
                match pushed {
                    Some(payload) => {
                        if !crate::auth::middleware::socket_session_active(&state, &auth).await.unwrap_or(false) {
                            close_revoked(&mut socket, &state, &auth, &terminal).await; break;
                        }
                        if terminal.borrow().is_some() {
                            close_revoked(&mut socket, &state, &auth, &terminal).await; break;
                        }
                        if !send_frame(&mut socket,Message::Text(payload.into_text())).await {
                            break;
                        }
                    }
                    None => break,
                }
            }
        }
    }

    state.ws_registry.unregister(auth.device_id, conn_id).await;
    crate::presence::persist_user(&state, auth.user_id).await;
    tracing::info!(user_id = %auth.user_id, "websocket client disconnected");
}

fn relay_result_to_error(result: Result<(), &'static str>) -> Option<ServerMessage> {
    result.err().map(|message| ServerMessage::Error { message: message.to_string() })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn read_only_allows_transport_frames_but_blocks_user_realtime_actions() {
        assert!(read_only_allows_client_message(&ClientMessage::Ping { request_id: None }));
        assert!(read_only_allows_client_message(&ClientMessage::PresenceSubscribe { user_ids: Vec::new(), lease_ms: None }));
        assert!(!read_only_allows_client_message(&ClientMessage::ChatActivity {
            chat_id: uuid::Uuid::new_v4(),
            activity: "typing".into(),
        }));
        assert!(!read_only_allows_client_message(&ClientMessage::CallVideoUpgradeRequest {
            call_id: uuid::Uuid::new_v4(),
        }));
        assert!(read_only_allows_client_message(&ClientMessage::CallKeepAlive { call_id: uuid::Uuid::new_v4() }));
        assert!(read_only_allows_client_message(&ClientMessage::CallIce { call_id: uuid::Uuid::new_v4(), candidate: "candidate".into(), sdp_mid: None, sdp_m_line_index: None }));
        assert!(!read_only_allows_client_message(&ClientMessage::CallVideoState { call_id: uuid::Uuid::new_v4(), enabled: true }));
    }
}

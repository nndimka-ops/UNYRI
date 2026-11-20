//! Foreground presence is independent of transport/push liveness.
use axum::{extract::State, Json};
use chrono::{DateTime, Utc};
use serde::Serialize;
use std::collections::HashMap;
use uuid::Uuid;
use crate::net::ClientIp;
use crate::{auth::{middleware::AuthUser, rate_limit}, error::AppError, state::AppState, users::models::PublicUser};

pub const MAX_SUBSCRIPTIONS: usize = 64;
/// Lease sent to clients; snapshots are re-sent at least every PRESENCE_REFRESH.
pub const VALID_FOR_MS: u64 = 60_000;
pub const PRESENCE_REFRESH: std::time::Duration = std::time::Duration::from_secs(30);
/// Legacy clients (before 17 Nov) cap a lease at 5 s and expect a resend every 2 s.
pub const LEGACY_VALID_FOR_MS: u64 = 5_000;
pub const LEGACY_REFRESH: std::time::Duration = std::time::Duration::from_secs(2);
/// Unchanged snapshots are re-read from the DB at most this often (last seen, policy).
pub const SNAPSHOT_REREAD: std::time::Duration = std::time::Duration::from_secs(30);

/// What a watcher can see change: online flags, and last seen only while offline
/// (an online user's last seen moves with every heartbeat and is not shown).
pub fn change_key(items: &[PresenceItem]) -> Vec<(Uuid, bool, Option<DateTime<Utc>>)> {
    items.iter().map(|i| (i.user_id, i.online, if i.online { None } else { i.last_seen_at })).collect()
}
const ALLOWED: &str = "u.deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM server_suspensions s WHERE s.user_id=u.id AND (s.until_date IS NULL OR s.until_date>now())) AND NOT user_pair_blocked($1,u.id)";

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct PresenceItem {
    pub user_id: Uuid,
    pub online: bool,
    pub last_seen_at: Option<DateTime<Utc>>,
}

pub async fn snapshot(state: &AppState, viewer: Uuid, ids: &[Uuid]) -> Result<Vec<PresenceItem>, AppError> {
    snapshot_inner(state, viewer, None, ids).await
}

pub async fn snapshot_for_device(state: &AppState, auth: &AuthUser, ids: &[Uuid]) -> Result<Vec<PresenceItem>, AppError> {
    snapshot_inner(state, auth.user_id, Some(auth.device_id), ids).await
}

async fn snapshot_inner(state: &AppState, viewer: Uuid, device: Option<Uuid>, ids: &[Uuid]) -> Result<Vec<PresenceItem>, AppError> {
    if ids.len() > MAX_SUBSCRIPTIONS { return Err(AppError::Validation("Too many presence subscriptions.".into())); }
    let rows: Vec<(Uuid, Option<DateTime<Utc>>, bool, bool)> = sqlx::query_as(&format!("SELECT u.id,u.last_seen_at,user_presence_visible(u.id,$1,'online'),user_presence_visible(u.id,$1,'last_seen') FROM users u WHERE u.id=ANY($2) AND {ALLOWED} AND ($3::uuid IS NULL OR NOT user_device_blocked($3,u.id)) ORDER BY u.id"))
        .bind(viewer).bind(ids).bind(device).fetch_all(&state.db).await?;
    let mut items = Vec::with_capacity(rows.len());
    let statuses = state.ws_registry.foreground_batch(&rows.iter().map(|r| r.0).collect::<Vec<_>>()).await;
    for (id, persisted, online_visible, last_seen_visible) in rows {
        let (online, confirmed) = statuses.get(&id).copied().unwrap_or((false, None));
        items.push(PresenceItem { user_id: id, online: online_visible && online,
            last_seen_at: if last_seen_visible { persisted.max(confirmed) } else { None } });
    }
    Ok(items)
}

#[derive(Serialize)]
pub struct DirectoryUser {
    #[serde(flatten)] user: PublicUser,
    online: bool,
    last_seen_at: Option<DateTime<Utc>>,
}
#[derive(Serialize)]
pub struct DirectoryResponse { items: Vec<DirectoryUser>, next_offset: Option<i64> }

/// Legacy directory clients receive an empty page. Discovery now uses exact
/// public identity lookup; contacts and presence subscriptions are separate APIs.
pub async fn directory(State(state): State<AppState>, ClientIp(addr): ClientIp, auth: AuthUser) -> Result<Json<DirectoryResponse>, AppError> {
    rate_limit::enforce(&state, "directory:ip", addr, 300, 60).await?;
    rate_limit::enforce(
        &state,
        &format!("directory:user:{}", auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        120,
        60,
    ).await?;
    Ok(Json(DirectoryResponse { items: Vec::new(), next_offset: None }))
}

#[cfg(test)]
mod lease_tests {
    use super::*;

    #[test]
    fn online_last_seen_movement_is_not_a_visible_change() {
        let id = Uuid::new_v4();
        let at = |s: i64| Some(DateTime::<Utc>::from_timestamp(s, 0).unwrap());
        let online = |s| vec![PresenceItem { user_id: id, online: true, last_seen_at: at(s) }];
        let offline = |s| vec![PresenceItem { user_id: id, online: false, last_seen_at: at(s) }];
        assert_eq!(change_key(&online(1)), change_key(&online(2)), "heartbeats of an online user send nothing");
        assert_ne!(change_key(&online(2)), change_key(&offline(2)), "going offline is sent");
        assert_ne!(change_key(&offline(2)), change_key(&offline(3)), "a new last seen while offline is sent");
    }

    #[test]
    fn subscribe_lease_is_optional_and_legacy_compatible() {
        use crate::websocket::protocol::ClientMessage;
        let legacy: ClientMessage = serde_json::from_str(r#"{"type":"presence.subscribe","user_ids":[]}"#).unwrap();
        assert!(matches!(legacy, ClientMessage::PresenceSubscribe { lease_ms: None, .. }));
        let current: ClientMessage = serde_json::from_str(r#"{"type":"presence.subscribe","user_ids":[],"lease_ms":60000}"#).unwrap();
        assert!(matches!(current, ClientMessage::PresenceSubscribe { lease_ms: Some(60_000), .. }));
        assert!(LEGACY_VALID_FOR_MS <= 5_000, "pre-17-Nov clients cap leases at 5 s");
        assert!(PRESENCE_REFRESH.as_millis() * 2 <= VALID_FOR_MS as u128, "refresh well inside the lease");
    }
}

pub async fn persist_user(state: &AppState, user_id: Uuid) {
    if let (_, Some(at)) = state.ws_registry.foreground_status(user_id).await {
        if let Err(error) = sqlx::query("UPDATE users SET last_seen_at=GREATEST(COALESCE(last_seen_at,$2),$2) WHERE id=$1 AND (last_seen_at IS NULL OR last_seen_at<$2)")
            .bind(user_id).bind(at).execute(&state.db).await { tracing::warn!(?error, "presence persistence unavailable"); }
    }
}

/// Coalesce DB writes; heartbeat freshness itself stays in memory.
pub fn spawn_persistence(state: AppState) {
    tokio::spawn(async move {
        let mut saved = HashMap::new();
        let mut tick = tokio::time::interval(std::time::Duration::from_secs(10));
        loop {
            tick.tick().await;
            for (id, at) in state.ws_registry.last_seen_snapshot().await {
                if saved.get(&id) == Some(&at) {
                    if state.ws_registry.forget_persisted_inactive(id, at).await { saved.remove(&id); }
                    continue;
                }
                match sqlx::query("UPDATE users SET last_seen_at=GREATEST(COALESCE(last_seen_at,$2),$2) WHERE id=$1 AND (last_seen_at IS NULL OR last_seen_at<$2)")
                    .bind(id).bind(at).execute(&state.db).await {
                    Ok(_) => {
                        if state.ws_registry.forget_persisted_inactive(id, at).await { saved.remove(&id); }
                        else { saved.insert(id, at); }
                    }
                    Err(error) => tracing::warn!(?error, "presence persistence unavailable"),
                }
            }
        }
    });
}

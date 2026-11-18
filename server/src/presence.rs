//! Foreground presence is independent of transport/push liveness.
use axum::{extract::{Query, State}, Json};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
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

#[derive(Deserialize)]
pub struct DirectoryQuery { #[serde(default)] q: String, limit: Option<i64>, offset: Option<i64> }
#[derive(sqlx::FromRow)]
struct DirectoryRow {
    id: Uuid, username: String, display_name: String, bio: Option<String>, avatar_url: Option<String>,
    avatar_key: Option<String>, profile_version: i64, avatar_version: i64, last_seen_at: Option<DateTime<Utc>>,
    online_visible: bool, last_seen_visible: bool,
}

/// Shorter queries do not search the whole server: they list the
/// requester's own direct contacts (an empty query lists all of them) plus
/// an exact @handle match.
pub const DIRECTORY_MIN_QUERY_CHARS: usize = 3;
/// Deepest page start; together with the page size this bounds how many
/// accounts one query can enumerate.
pub const DIRECTORY_MAX_OFFSET: i64 = 200;

/// Which accounts a directory query may return.
#[derive(Debug, PartialEq, Eq)]
enum DirectoryScope { Server, Contacts }

fn directory_scope(q: &str) -> DirectoryScope {
    if q.chars().count() >= DIRECTORY_MIN_QUERY_CHARS { DirectoryScope::Server } else { DirectoryScope::Contacts }
}

/// Next page start, or none once the next page would begin past the cap.
fn directory_next_offset(more: bool, offset: i64, limit: i64) -> Option<i64> {
    let next = offset + limit;
    (more && next <= DIRECTORY_MAX_OFFSET).then_some(next)
}

/// Presence in directory rows: direct contacts per the owner's privacy
/// settings (including the always/never lists); anyone else only while the
/// owner's setting for that field is "everyone" (no stored row keeps the
/// default "everyone") and the requester is not on the owner's never list.
const DIRECTORY_PRESENCE: &str = "\
    CASE WHEN contact.is_direct THEN user_presence_visible(u.id,$1,'online') \
         ELSE COALESCE((SELECT p.online_visibility='everyone' AND NOT ($1=ANY(p.never_allow)) FROM user_privacy p WHERE p.user_id=u.id),true) END AS online_visible,\
    CASE WHEN contact.is_direct THEN user_presence_visible(u.id,$1,'last_seen') \
         ELSE COALESCE((SELECT p.last_seen_visibility='everyone' AND NOT ($1=ANY(p.never_allow)) FROM user_privacy p WHERE p.user_id=u.id),true) END AS last_seen_visible";
#[derive(Serialize)]
pub struct DirectoryUser {
    #[serde(flatten)] user: PublicUser,
    online: bool,
    last_seen_at: Option<DateTime<Utc>>,
}
#[derive(Serialize)]
pub struct DirectoryResponse { items: Vec<DirectoryUser>, next_offset: Option<i64> }

pub async fn directory(State(state): State<AppState>, ClientIp(addr): ClientIp, auth: AuthUser,
    Query(query): Query<DirectoryQuery>) -> Result<Json<DirectoryResponse>, AppError> {
    rate_limit::enforce(&state, "directory:ip", addr, 300, 60).await?;
    rate_limit::enforce(
        &state,
        &format!("directory:user:{}", auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        120,
        60,
    ).await?;
    let q = query.q.trim().trim_start_matches('@');
    if q.chars().count() > 256 { return Err(AppError::Validation("Search query is too long.".into())); }
    let escaped = q.replace('\\', "\\\\").replace('%', "\\%").replace('_', "\\_");
    let pattern = format!("%{escaped}%");
    let limit = query.limit.unwrap_or(30).clamp(1, 50);
    let offset = query.offset.unwrap_or(0).max(0);
    if offset > DIRECTORY_MAX_OFFSET {
        return Ok(Json(DirectoryResponse { items: Vec::new(), next_offset: None }));
    }
    let server_wide = directory_scope(q) == DirectoryScope::Server;
    let rows: Vec<DirectoryRow> = sqlx::query_as(&format!("SELECT u.id,u.username,u.display_name,u.bio,u.avatar_url,u.avatar_key,u.profile_version,u.avatar_version,u.last_seen_at,{DIRECTORY_PRESENCE} \
        FROM users u CROSS JOIN LATERAL (SELECT EXISTS(SELECT 1 FROM chats c WHERE c.type='direct' AND c.user_a_id=LEAST($1,u.id) AND c.user_b_id=GREATEST($1,u.id)) AS is_direct) contact \
        WHERE u.id<>$1 AND {ALLOWED} AND NOT user_device_blocked($5,u.id) \
        AND ((($6 OR contact.is_direct) AND (u.username_normalized ILIKE $2 ESCAPE '\\' OR u.display_name ILIKE $2 ESCAPE '\\')) OR u.username_normalized=lower($7)) \
        ORDER BY u.username_normalized,u.id LIMIT $3 OFFSET $4"))
        .bind(auth.user_id).bind(pattern).bind(limit + 1).bind(offset).bind(auth.device_id).bind(server_wide).bind(q)
        .fetch_all(&state.db).await?;
    let more = rows.len() > limit as usize;
    let mut items = Vec::new();
    let statuses = state.ws_registry.foreground_batch(&rows.iter().map(|r| r.id).collect::<Vec<_>>()).await;
    for row in rows.into_iter().take(limit as usize) {
        let (online, confirmed) = statuses.get(&row.id).copied().unwrap_or((false, None));
        items.push(DirectoryUser { online: row.online_visible && online,
            last_seen_at: if row.last_seen_visible { row.last_seen_at.max(confirmed) } else { None }, user: PublicUser {
                new_account: None,
            id: row.id, username: row.username, display_name: row.display_name, bio: row.bio, avatar_url: row.avatar_url,
            profile_version: row.profile_version, avatar_version: row.avatar_version, has_avatar: row.avatar_key.is_some(),
        }});
    }
    Ok(Json(DirectoryResponse { items, next_offset: directory_next_offset(more, offset, limit) }))
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

#[cfg(test)]
mod directory_tests {
    use super::*;

    #[test]
    fn short_queries_stay_within_contacts() {
        assert_eq!(directory_scope(""), DirectoryScope::Contacts);
        assert_eq!(directory_scope("a"), DirectoryScope::Contacts);
        assert_eq!(directory_scope("ab"), DirectoryScope::Contacts);
        assert_eq!(directory_scope("абв"), DirectoryScope::Server);
        assert_eq!(directory_scope("abc"), DirectoryScope::Server);
    }

    #[test]
    fn pagination_stops_at_the_offset_cap() {
        assert_eq!(directory_next_offset(true, 0, 30), Some(30));
        assert_eq!(directory_next_offset(false, 0, 30), None);
        assert_eq!(directory_next_offset(true, 150, 50), Some(200));
        assert_eq!(directory_next_offset(true, 180, 30), None);
        assert_eq!(directory_next_offset(true, 200, 50), None);
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

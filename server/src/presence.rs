//! Foreground presence is independent of transport/push liveness.
use axum::{extract::{Query, State}, Json};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;
use crate::net::ClientIp;
use crate::{auth::{middleware::AuthUser, rate_limit}, error::AppError, state::AppState, users::models::PublicUser};

pub const MAX_SUBSCRIPTIONS: usize = 64;
pub const VALID_FOR_MS: u64 = 5_000;
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
    let offset = query.offset.unwrap_or(0).clamp(0, 1_000_000);
    let rows: Vec<DirectoryRow> = sqlx::query_as(&format!("SELECT u.id,u.username,u.display_name,u.bio,u.avatar_url,u.avatar_key,u.profile_version,u.avatar_version,u.last_seen_at,user_presence_visible(u.id,$1,'online') AS online_visible,user_presence_visible(u.id,$1,'last_seen') AS last_seen_visible FROM users u WHERE u.id<>$1 AND {ALLOWED} AND NOT user_device_blocked($5,u.id) AND (u.username_normalized ILIKE $2 ESCAPE '\\' OR u.display_name ILIKE $2 ESCAPE '\\') ORDER BY u.username_normalized,u.id LIMIT $3 OFFSET $4"))
        .bind(auth.user_id).bind(pattern).bind(limit + 1).bind(offset).bind(auth.device_id).fetch_all(&state.db).await?;
    let more = rows.len() > limit as usize;
    let mut items = Vec::new();
    let statuses = state.ws_registry.foreground_batch(&rows.iter().map(|r| r.id).collect::<Vec<_>>()).await;
    for row in rows.into_iter().take(limit as usize) {
        let (online, confirmed) = statuses.get(&row.id).copied().unwrap_or((false, None));
        items.push(DirectoryUser { online: row.online_visible && online,
            last_seen_at: if row.last_seen_visible { row.last_seen_at.max(confirmed) } else { None }, user: PublicUser {
            id: row.id, username: row.username, display_name: row.display_name, bio: row.bio, avatar_url: row.avatar_url,
            profile_version: row.profile_version, avatar_version: row.avatar_version, has_avatar: row.avatar_key.is_some(),
        }});
    }
    Ok(Json(DirectoryResponse { items, next_offset: more.then_some(offset + limit) }))
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

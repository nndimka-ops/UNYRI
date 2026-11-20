//! Personal contacts (address book). One-way and private: the contact is never
//! told, and a block on either side hides the row without revealing the block.
use axum::{
    extract::{Path, Query, State},
    Json,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::{
    auth::{
        middleware::{lock_active_session, AuthUser},
        rate_limit,
    },
    error::AppError,
    net::ClientIp,
    state::AppState,
    users::models::PublicUser,
};

pub const MAX_ACTIVE_CONTACTS: i64 = 5000;

#[derive(Deserialize)]
pub struct ContactsQuery {
    limit: Option<i64>,
    cursor: Option<Uuid>,
}

#[derive(Serialize)]
pub struct ContactItem {
    user: PublicUser,
    source: String,
    added_at: DateTime<Utc>,
}

#[derive(Serialize)]
pub struct ContactsResponse {
    items: Vec<ContactItem>,
    next_cursor: Option<Uuid>,
}

#[derive(Serialize)]
pub struct ContactStatus {
    user_id: Uuid,
    is_contact: bool,
}

#[derive(Serialize)]
pub struct ContactChange {
    user_id: Uuid,
    is_contact: bool,
    changed: bool,
    source: Option<String>,
    added_at: Option<DateTime<Utc>>,
}

#[derive(sqlx::FromRow)]
struct ContactRow {
    id: Uuid,
    username: String,
    public_username: Option<String>,
    unyri_number: String,
    display_name: String,
    bio: Option<String>,
    avatar_url: Option<String>,
    profile_version: i64,
    avatar_version: i64,
    has_avatar: bool,
    source: String,
    added_at: DateTime<Utc>,
}

fn user_bucket() -> std::net::IpAddr {
    std::net::Ipv4Addr::UNSPECIFIED.into()
}

pub async fn list(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    auth: AuthUser,
    Query(query): Query<ContactsQuery>,
) -> Result<Json<ContactsResponse>, AppError> {
    rate_limit::enforce(&state, "contacts:ip", ip, 120, 60).await?;
    rate_limit::enforce(&state, &format!("contacts:read:{}", auth.user_id), user_bucket(), 60, 60).await?;
    let limit = query.limit.unwrap_or(200).clamp(1, 200);
    // Hidden rows stay stored, so a contact
    // comes back after an unblock or a lifted suspension.
    let rows: Vec<ContactRow> = sqlx::query_as(
        "SELECT u.id,COALESCE(i.public_username,'') AS username,i.public_username,i.unyri_number::text, \
         u.display_name,u.bio,u.avatar_url,u.profile_version,u.avatar_version, \
         (u.avatar_key IS NOT NULL) AS has_avatar, c.source, c.created_at AS added_at \
         FROM user_contacts c JOIN users u ON u.id=c.contact_id \
         JOIN user_identities i ON i.user_id=u.id \
         WHERE c.owner_id=$1 AND c.removed_at IS NULL AND ($2::uuid IS NULL OR c.contact_id>$2) \
           AND u.deleted_at IS NULL \
           AND NOT EXISTS(SELECT 1 FROM server_suspensions s WHERE s.user_id=u.id AND (s.until_date IS NULL OR s.until_date>now())) \
           AND NOT user_pair_blocked($1,u.id) AND NOT user_device_blocked($4,u.id) \
         ORDER BY c.contact_id LIMIT $3",
    )
    .bind(auth.user_id)
    .bind(query.cursor)
    .bind(limit + 1)
    .bind(auth.device_id)
    .fetch_all(&state.db)
    .await?;
    let more = rows.len() > limit as usize;
    let items: Vec<ContactItem> = rows
        .into_iter()
        .take(limit as usize)
        .map(|row| ContactItem {
            user: PublicUser {
                id: row.id,
                username: row.username,
                public_username: row.public_username,
                unyri_number: row.unyri_number,
                display_name: row.display_name,
                bio: row.bio,
                avatar_url: row.avatar_url,
                profile_version: row.profile_version,
                avatar_version: row.avatar_version,
                has_avatar: row.has_avatar,
                new_account: None,
            },
            source: row.source,
            added_at: row.added_at,
        })
        .collect();
    let next_cursor = if more { items.last().map(|item| item.user.id) } else { None };
    Ok(Json(ContactsResponse { items, next_cursor }))
}

/// One person's membership, for a profile screen that needs a single answer
/// instead of the whole book. Same visibility as the list, so the two agree.
pub async fn status(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    auth: AuthUser,
    Path(peer): Path<Uuid>,
) -> Result<Json<ContactStatus>, AppError> {
    rate_limit::enforce(&state, "contacts:ip", ip, 120, 60).await?;
    rate_limit::enforce(&state, &format!("contacts:check:{}", auth.user_id), user_bucket(), 120, 60).await?;
    let is_contact: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM user_contacts c JOIN users u ON u.id=c.contact_id \
         WHERE c.owner_id=$1 AND c.contact_id=$2 AND c.removed_at IS NULL AND u.deleted_at IS NULL \
           AND NOT EXISTS(SELECT 1 FROM server_suspensions s WHERE s.user_id=u.id AND (s.until_date IS NULL OR s.until_date>now())) \
           AND NOT user_pair_blocked($1,u.id) AND NOT user_device_blocked($3,u.id))",
    )
    .bind(auth.user_id)
    .bind(peer)
    .bind(auth.device_id)
    .fetch_one(&state.db)
    .await?;
    Ok(Json(ContactStatus { user_id: peer, is_contact }))
}

pub async fn add(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(peer): Path<Uuid>,
) -> Result<Json<ContactChange>, AppError> {
    if peer == auth.user_id {
        return Err(AppError::Validation("Cannot add yourself to contacts.".into()));
    }
    rate_limit::enforce(&state, &format!("contacts:write:{}", auth.user_id), user_bucket(), 120, 60).await?;
    let mut tx = state.db.begin().await?;
    lock_active_session(&mut tx, &auth).await?;
    let exists: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM users WHERE id=$1 AND deleted_at IS NULL)")
        .bind(peer)
        .fetch_one(&mut *tx)
        .await?;
    if !exists {
        return Err(AppError::NotFound("User not found.".into()));
    }
    // The owner's row lock (lock_active_session) serialises this count with
    // the insert, so concurrent adds cannot overshoot the limit.
    let (active, already): (i64, bool) = sqlx::query_as(
        "SELECT count(*), COALESCE(bool_or(contact_id=$2),false) FROM user_contacts WHERE owner_id=$1 AND removed_at IS NULL",
    )
    .bind(auth.user_id)
    .bind(peer)
    .fetch_one(&mut *tx)
    .await?;
    if active >= MAX_ACTIVE_CONTACTS && !already {
        return Err(AppError::Conflict("Contact limit reached.".into()));
    }
    let inserted: Option<(String, DateTime<Utc>)> = sqlx::query_as(
        "INSERT INTO user_contacts(owner_id,contact_id,source) VALUES($1,$2,'manual') \
         ON CONFLICT (owner_id,contact_id) DO UPDATE SET source='manual',created_at=now(),removed_at=NULL \
           WHERE user_contacts.removed_at IS NOT NULL \
         RETURNING source,created_at",
    )
    .bind(auth.user_id)
    .bind(peer)
    .fetch_optional(&mut *tx)
    .await?;
    let changed = inserted.is_some();
    let (source, added_at) = match inserted {
        Some(row) => row,
        None => {
            sqlx::query_as("SELECT source,created_at FROM user_contacts WHERE owner_id=$1 AND contact_id=$2")
                .bind(auth.user_id)
                .bind(peer)
                .fetch_one(&mut *tx)
                .await?
        }
    };
    tx.commit().await?;
    if changed {
        notify_changed(&state, auth.user_id, Some(auth.device_id), peer, true).await;
    }
    // Stored even when either side blocks the other: the answer must not
    // reveal a block.
    Ok(Json(ContactChange { user_id: peer, is_contact: true, changed, source: Some(source), added_at: Some(added_at) }))
}

pub async fn remove(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(peer): Path<Uuid>,
) -> Result<Json<ContactChange>, AppError> {
    rate_limit::enforce(&state, &format!("contacts:write:{}", auth.user_id), user_bucket(), 120, 60).await?;
    let mut tx = state.db.begin().await?;
    lock_active_session(&mut tx, &auth).await?;
    // A soft delete: the stored pair keeps a later message from re-adding them.
    let changed = sqlx::query(
        "UPDATE user_contacts SET removed_at=now() WHERE owner_id=$1 AND contact_id=$2 AND removed_at IS NULL",
    )
    .bind(auth.user_id)
    .bind(peer)
    .execute(&mut *tx)
    .await?
    .rows_affected()
        > 0;
    tx.commit().await?;
    if changed {
        notify_changed(&state, auth.user_id, Some(auth.device_id), peer, false).await;
    }
    Ok(Json(ContactChange { user_id: peer, is_contact: false, changed, source: None, added_at: None }))
}

/// Best-effort hint to the owner's devices. It names the one person that
/// changed, so a client patches that row instead of paging the whole book.
/// Never an error: the change is already committed. An installation that is
/// blocked toward that person (installation block) never hears about them,
/// like every other fan-out naming the peer.
pub async fn notify_changed(state: &AppState, owner: Uuid, except: Option<Uuid>, peer: Uuid, is_contact: bool) {
    let devices: Vec<Uuid> = sqlx::query_scalar(
        "SELECT id FROM devices WHERE user_id=$1 AND revoked_at IS NULL AND ($2::uuid IS NULL OR id<>$2)          AND NOT user_device_blocked(id,$3)",
    )
    .bind(owner)
    .bind(except)
    .bind(peer)
    .fetch_all(&state.db)
    .await
    .unwrap_or_default();
    let hint = serde_json::json!({"type": "contacts.changed", "user_id": peer, "is_contact": is_contact}).to_string();
    for device in devices {
        state.ws_registry.send_to_device(device, &hint).await;
    }
}

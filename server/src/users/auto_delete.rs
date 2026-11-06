//! Account removal is opt-in and uses confirmed foreground use, never refresh,
//! push delivery, socket transport liveness, or a client-provided clock.
use axum::{extract::State, http::StatusCode, Json};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::{
    auth::{
        middleware::{lock_active_session, AuthUser},
        password, rate_limit,
    },
    error::AppError,
    net::ClientIp,
    state::AppState,
};

// Activity writes coalesce for 60 seconds. The matching conservative grace
// prevents a final coalesced visit shortening the user's full selected period.
const SNAPSHOT: &str = "SELECT auto_delete_inactive_days AS inactive_days,auto_delete_last_active_at AS last_active_at,CASE WHEN auto_delete_inactive_days IS NOT NULL THEN auto_delete_last_active_at+auto_delete_inactive_days*interval '24 hours'+interval '60 seconds' END AS next_deletion_at,auto_delete_revision AS revision FROM users WHERE id=$1 AND deleted_at IS NULL";

#[derive(Debug, Serialize, sqlx::FromRow)]
pub struct AutoDeleteSnapshot {
    pub inactive_days: Option<i32>,
    pub last_active_at: DateTime<Utc>,
    pub next_deletion_at: Option<DateTime<Utc>>,
    pub revision: i64,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AutoDeleteUpdate {
    #[serde(deserialize_with = "explicit_period")]
    pub inactive_days: Option<i32>,
    pub expected_revision: i64,
    pub password: Option<String>,
}

fn explicit_period<'de, D: serde::Deserializer<'de>>(input: D) -> Result<Option<i32>, D::Error> {
    Option::<i32>::deserialize(input)
}

pub async fn get(
    State(state): State<AppState>,
    auth: AuthUser,
) -> Result<Json<AutoDeleteSnapshot>, AppError> {
    rate_limit::enforce(
        &state,
        &format!("auto-delete:read:{}", auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        120,
        60,
    )
    .await?;
    let mut tx = state.db.begin().await?;
    lock_active_session(&mut tx, &auth).await?;
    let snapshot = sqlx::query_as(SNAPSHOT)
        .bind(auth.user_id)
        .fetch_one(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(Json(snapshot))
}

pub async fn put(
    State(state): State<AppState>,
    ClientIp(addr): ClientIp,
    auth: AuthUser,
    Json(request): Json<AutoDeleteUpdate>,
) -> Result<Json<AutoDeleteSnapshot>, AppError> {
    if request.expected_revision < 0
        || request
            .inactive_days
            .is_some_and(|days| !(1..=1095).contains(&days))
    {
        return Err(AppError::Validation(
            "Choose an inactivity period from 1 to 1095 days, or disable automatic deletion."
                .into(),
        ));
    }
    rate_limit::enforce(&state, "auto-delete:write:ip", addr, 30, 3600).await?;
    rate_limit::enforce(
        &state,
        &format!("auto-delete:write:{}", auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        20,
        3600,
    )
    .await?;
    let mut tx = state.db.begin().await?;
    // Same users row lock as activity, the worker, recovery, and manual deletion.
    // Security settings remain available to an active read-only account.
    lock_active_session(&mut tx, &auth).await?;
    let current: AutoDeleteSnapshot = sqlx::query_as(SNAPSHOT)
        .bind(auth.user_id)
        .fetch_one(&mut *tx)
        .await?;
    if current.revision != request.expected_revision {
        return Err(AppError::Conflict(
            "Automatic deletion settings changed. Reload and try again.".into(),
        ));
    }
    if current.inactive_days != request.inactive_days {
        if request.inactive_days.is_some() {
            let supplied = request
                .password
                .filter(|password| !password.is_empty() && password.len() <= 1024)
                .ok_or_else(|| AppError::Validation("Enter your current password.".into()))?;
            let hash: String = sqlx::query_scalar("SELECT password_hash FROM users WHERE id=$1")
                .bind(auth.user_id)
                .fetch_one(&mut *tx)
                .await?;
            if !password::run(move || Ok(password::verify_password(&supplied, &hash))).await? {
                return Err(AppError::Validation(
                    "Current password is incorrect.".into(),
                ));
            }
        }
        // Enabling or changing the period starts a full new interval. Old users
        // and stale device clocks cannot schedule an immediate deletion.
        let revision = current.revision.checked_add(1).ok_or(AppError::Internal)?;
        sqlx::query("UPDATE users SET auto_delete_inactive_days=$2,auto_delete_last_active_at=clock_timestamp(),auto_delete_revision=$3 WHERE id=$1")
            .bind(auth.user_id).bind(request.inactive_days).bind(revision).execute(&mut *tx).await?;
    }
    let next: AutoDeleteSnapshot = sqlx::query_as(SNAPSHOT)
        .bind(auth.user_id)
        .fetch_one(&mut *tx)
        .await?;
    tx.commit().await?;
    let hint =
        serde_json::json!({"type":"auto_delete.changed","revision":next.revision}).to_string();
    let devices: Vec<Uuid> =
        sqlx::query_scalar("SELECT id FROM devices WHERE user_id=$1 AND revoked_at IS NULL")
            .bind(auth.user_id)
            .fetch_all(&state.db)
            .await
            .unwrap_or_default();
    for device in devices {
        state.ws_registry.send_to_device(device, &hint).await;
    }
    Ok(Json(next))
}

/// Clients call only on entering the foreground and every ~60s while visible.
/// There is deliberately no arbitrary account ID, timestamp, or background flag.
pub async fn activity(
    State(state): State<AppState>,
    auth: AuthUser,
    _body: axum::body::Bytes,
) -> Result<StatusCode, AppError> {
    rate_limit::enforce(
        &state,
        &format!("account-activity:{}", auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        120,
        60,
    )
    .await?;
    let mut tx = state.db.begin().await?;
    lock_active_session(&mut tx, &auth).await?;
    touch(&mut tx, auth.user_id).await?;
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn touch(tx: &mut sqlx::Transaction<'_, sqlx::Postgres>, user: Uuid) -> Result<(), AppError> {
    sqlx::query("UPDATE users SET auto_delete_last_active_at=clock_timestamp() WHERE id=$1 AND auto_delete_last_active_at<=clock_timestamp()-interval '60 seconds'")
        .bind(user).execute(&mut **tx).await?;
    Ok(())
}

/// An old WS session may have rotated through refresh. Revalidate its active
/// session lineage inside the same account-locked transaction before touching.
pub async fn socket_foreground(state: &AppState, auth: &AuthUser) -> Result<(), AppError> {
    let mut tx = state.db.begin().await?;
    let user: Option<Uuid> =
        sqlx::query_scalar("SELECT id FROM users WHERE id=$1 AND deleted_at IS NULL FOR UPDATE")
            .bind(auth.user_id)
            .fetch_optional(&mut *tx)
            .await?;
    if user.is_none() {
        return Err(AppError::SessionRevoked);
    }
    let active: bool = sqlx::query_scalar(
        "WITH RECURSIVE family AS (SELECT id,replaced_by,revoked_at,expires_at FROM sessions WHERE id=$1 AND user_id=$2 AND device_id=$3 UNION SELECT s.id,s.replaced_by,s.revoked_at,s.expires_at FROM sessions s JOIN family f ON s.id=f.replaced_by WHERE s.user_id=$2 AND s.device_id=$3) SELECT EXISTS(SELECT 1 FROM family WHERE revoked_at IS NULL AND expires_at>clock_timestamp()) AND EXISTS(SELECT 1 FROM devices WHERE id=$3 AND user_id=$2 AND revoked_at IS NULL) AND NOT EXISTS(SELECT 1 FROM devices d JOIN installation_suspensions b ON b.installation_id=d.installation_id WHERE d.id=$3 AND (b.until_date IS NULL OR b.until_date>clock_timestamp())) AND NOT EXISTS(SELECT 1 FROM server_suspensions WHERE user_id=$2 AND (until_date IS NULL OR until_date>clock_timestamp()))")
        .bind(auth.session_id).bind(auth.user_id).bind(auth.device_id).fetch_one(&mut *tx).await?;
    if !active {
        return Err(AppError::SessionRevoked);
    }
    touch(&mut tx, auth.user_id).await?;
    tx.commit().await?;
    Ok(())
}

/// One account per transaction, bounded work per sweep, and database locks make
/// overlapping workers (including different processes) safe. The call deletion
/// feed lock always precedes user locks, matching other history mutations.
pub async fn sweep_inactive_accounts(state: &AppState) -> Result<u64, AppError> {
    let mut removed = 0;
    for _ in 0..25 {
        let mut tx = state.db.begin().await?;
        let feed_lock: bool = sqlx::query_scalar("SELECT pg_try_advisory_xact_lock(6840210040)")
            .fetch_one(&mut *tx)
            .await?;
        if !feed_lock {
            break;
        }
        let user: Option<Uuid> = sqlx::query_scalar("SELECT id FROM users WHERE deleted_at IS NULL AND auto_delete_inactive_days BETWEEN 1 AND 1095 AND auto_delete_last_active_at+auto_delete_inactive_days*interval '24 hours'+interval '60 seconds'<=clock_timestamp() ORDER BY auto_delete_last_active_at,id LIMIT 1 FOR UPDATE SKIP LOCKED")
            .fetch_optional(&mut *tx).await?;
        let Some(user) = user else {
            break;
        };
        // Legacy clients already report explicit foreground presence. Preserve
        // that confirmed visit as well, including a heartbeat not yet flushed
        // by the coalesced presence writer on this process.
        let foreground = state.ws_registry.foreground_status(user).await.0;
        // Real media use remains activity while the app is in the background.
        // Only fresh call/SFU authorization leases protect an account; stale
        // busy rows, closed rooms, push, and refresh do not extend its clock.
        let live_media: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM call_participants_active p JOIN calls c ON c.id=p.call_id WHERE p.user_id=$1 AND c.state='connected' AND c.last_activity_at>clock_timestamp()-interval '40 seconds') OR EXISTS(SELECT 1 FROM conference_members m JOIN conferences c ON c.id=m.conference_id WHERE m.user_id=$1 AND c.state='open' AND c.expires_at>clock_timestamp() AND m.last_authorized_at>clock_timestamp()-interval '90 seconds' AND conference_session_active(m.session_id,m.user_id,m.device_id))")
            .bind(user).fetch_one(&mut *tx).await?;
        sqlx::query("UPDATE users SET auto_delete_last_active_at=GREATEST(auto_delete_last_active_at,COALESCE(last_seen_at,auto_delete_last_active_at),CASE WHEN $2 THEN clock_timestamp() ELSE auto_delete_last_active_at END) WHERE id=$1 AND (last_seen_at>auto_delete_last_active_at OR $2)")
            .bind(user).bind(foreground || live_media).execute(&mut *tx).await?;
        // Re-read the durable deadline under the acquired users lock after
        // recording any newly confirmed foreground/media activity.
        let still_due: bool = sqlx::query_scalar("SELECT auto_delete_inactive_days BETWEEN 1 AND 1095 AND auto_delete_last_active_at+auto_delete_inactive_days*interval '24 hours'+interval '60 seconds'<=clock_timestamp() FROM users WHERE id=$1")
            .bind(user).fetch_one(&mut *tx).await?;
        if !still_due {
            tx.commit().await?;
            continue;
        }
        let effects = super::deletion::delete_account_data(state, &mut tx, user).await?;
        tx.commit().await?;
        removed += 1;
        super::deletion::notify_account_deleted(state, effects).await;
    }
    Ok(removed)
}

pub fn spawn_worker(state: AppState) {
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(std::time::Duration::from_secs(60));
        tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            tick.tick().await;
            if sweep_inactive_accounts(&state).await.is_err() {
                tracing::warn!("inactive account deletion pending");
            }
        }
    });
}

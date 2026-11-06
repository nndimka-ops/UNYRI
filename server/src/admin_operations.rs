//! Owner-only operations centre. The API exposes aggregate operational state
//! and explicit, audited controls. It never returns message content, tokens,
//! password hashes, recovery-code hashes, encryption keys, or backup payloads.

use axum::{
    extract::{Path, State},
    Json,
};
use chrono::{DateTime, Duration, Utc};
use serde::Deserialize;
use serde_json::{json, Value};
use uuid::Uuid;

use crate::{
    admin::{audit_event, Admin},
    error::AppError,
    state::AppState,
};

fn owner(admin: &Admin) -> Result<(), AppError> {
    if admin.role != "owner" {
        return Err(AppError::NotFound("Not found.".into()));
    }
    Ok(())
}

fn reason(value: &str) -> Result<&str, AppError> {
    let value = value.trim();
    if value.is_empty() || value.chars().count() > 2000 {
        return Err(AppError::Validation(
            "A reason of 1-2000 characters is required.".into(),
        ));
    }
    Ok(value)
}

fn version(value: &str) -> bool {
    value.len() <= 64
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b".,_-+".contains(&b))
}

pub async fn overview(
    State(state): State<AppState>,
    admin: Admin,
) -> Result<Json<Value>, AppError> {
    owner(&admin)?;
    let controls: Value = sqlx::query_scalar(
        "SELECT jsonb_build_object(
            'min_android_version',min_android_version,
            'min_ios_version',min_ios_version,
            'min_desktop_version',min_desktop_version,
            'registration_open',registration_open,
            'automatic_login_protection',automatic_login_protection,
            'login_ip_limit',login_ip_limit,
            'login_account_limit',login_account_limit)
         FROM server_policy WHERE id=true",
    )
    .fetch_one(&state.db)
    .await?;

    let operators: Vec<Value> = sqlx::query_scalar(
        "SELECT jsonb_build_object(
            'user_id',a.user_id,'username',u.username,'display_name',u.display_name,
            'role',a.role,'two_factor_configured',a.secret IS NOT NULL,
            'paper_key_configured',a.signing_public_key IS NOT NULL,
            'auth_policy',a.auth_policy,'auth_revision',a.auth_revision,
            'created_at',a.created_at,'is_current',a.user_id=$1)
         FROM server_administrators a JOIN users u ON u.id=a.user_id
         WHERE u.deleted_at IS NULL
         ORDER BY CASE a.role WHEN 'owner' THEN 0 ELSE 1 END,u.username_normalized",
    )
    .bind(admin.user)
    .fetch_all(&state.db)
    .await?;

    let notice: Option<Value> = sqlx::query_scalar(
        "SELECT jsonb_build_object('id',id,'title',title,'body',body,'severity',severity,
             'created_at',created_at,'expires_at',expires_at)
         FROM server_announcements
         WHERE disabled_at IS NULL AND expires_at>now()
         ORDER BY created_at DESC LIMIT 1",
    )
    .fetch_optional(&state.db)
    .await?;

    let (users, active_sessions, suspended_accounts): (i64, i64, i64) = sqlx::query_as(
        "SELECT
            (SELECT count(*) FROM users WHERE deleted_at IS NULL),
            (SELECT count(*) FROM sessions WHERE revoked_at IS NULL AND expires_at>now()),
            (SELECT count(*) FROM server_suspensions WHERE until_date IS NULL OR until_date>now())",
    )
    .fetch_one(&state.db)
    .await?;
    let (connections, connected_devices, foreground_users) =
        state.ws_registry.admin_metrics().await;

    let (enabled, active, staged, bytes, last_verified): (
        i64,
        i64,
        i64,
        i64,
        Option<DateTime<Utc>>,
    ) = sqlx::query_as(
        "SELECT
            (SELECT count(*) FROM history_backup_accounts WHERE desired_enabled),
            (SELECT count(*) FROM history_backup_objects WHERE state='active'),
            (SELECT count(*) FROM history_backup_objects WHERE state='staged'),
            (SELECT COALESCE(sum(octet_length(ciphertext)),0)::bigint FROM history_backup_objects),
            (SELECT max(verified_at) FROM history_backup_objects WHERE state='active')",
    )
    .fetch_one(&state.db)
    .await?;

    Ok(Json(json!({
        "controls": controls,
        "operators": operators,
        "active_notice": notice,
        "live": {
            "users": users,
            "active_sessions": active_sessions,
            "suspended_accounts": suspended_accounts,
            "connections": connections,
            "connected_devices": connected_devices,
            "foreground_users": foreground_users,
            "observed_at": Utc::now(),
        },
        "backups": {
            "enabled_accounts": enabled,
            "active_objects": active,
            "staged_objects": staged,
            "ciphertext_bytes": bytes,
            "last_verified_at": last_verified,
            "restore_model": "client_encrypted",
        }
    })))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ControlsRequest {
    min_android_version: String,
    min_ios_version: String,
    min_desktop_version: String,
    registration_open: bool,
    automatic_login_protection: bool,
    login_ip_limit: i64,
    login_account_limit: i64,
    reason: String,
}

pub async fn save_controls(
    State(state): State<AppState>,
    admin: Admin,
    Json(body): Json<ControlsRequest>,
) -> Result<Json<Value>, AppError> {
    owner(&admin)?;
    let reason = reason(&body.reason)?;
    if !version(&body.min_android_version)
        || !version(&body.min_ios_version)
        || !version(&body.min_desktop_version)
        || !(1..=10).contains(&body.login_ip_limit)
        || !(1..=20).contains(&body.login_account_limit)
    {
        return Err(AppError::Validation(
            "Invalid administrative controls.".into(),
        ));
    }
    let mut tx = state.db.begin().await?;
    sqlx::query(
        "UPDATE server_policy SET min_android_version=$1,min_ios_version=$2,min_desktop_version=$3,
            registration_open=$4,automatic_login_protection=$5,login_ip_limit=$6,login_account_limit=$7
         WHERE id=true",
    ).bind(body.min_android_version.trim()).bind(body.min_ios_version.trim())
        .bind(body.min_desktop_version.trim()).bind(body.registration_open)
        .bind(body.automatic_login_protection).bind(body.login_ip_limit)
        .bind(body.login_account_limit).execute(&mut *tx).await?;
    audit_event(&mut tx, admin.user, "operations:controls", None, reason,
        json!({"registration_open":body.registration_open,
               "automatic_login_protection":body.automatic_login_protection,
               "login_ip_limit":body.login_ip_limit,"login_account_limit":body.login_account_limit})).await?;
    tx.commit().await?;
    Ok(Json(json!({"ok":true})))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AnnouncementRequest {
    title: String,
    body: String,
    severity: String,
    expires_at: DateTime<Utc>,
    reason: String,
}

pub async fn publish_announcement(
    State(state): State<AppState>,
    admin: Admin,
    Json(body): Json<AnnouncementRequest>,
) -> Result<Json<Value>, AppError> {
    owner(&admin)?;
    let reason = reason(&body.reason)?;
    let title = body.title.trim();
    let message = body.body.trim();
    if title.is_empty()
        || title.chars().count() > 120
        || message.is_empty()
        || message.chars().count() > 2000
        || !["info", "warning", "critical"].contains(&body.severity.as_str())
        || body.expires_at <= Utc::now() + Duration::minutes(1)
        || body.expires_at > Utc::now() + Duration::days(30)
    {
        return Err(AppError::Validation("Invalid service announcement.".into()));
    }
    let id = Uuid::new_v4();
    let mut tx = state.db.begin().await?;
    sqlx::query("UPDATE server_announcements SET disabled_at=now() WHERE disabled_at IS NULL AND expires_at>now()")
        .execute(&mut *tx).await?;
    sqlx::query("INSERT INTO server_announcements(id,title,body,severity,created_by,expires_at) VALUES($1,$2,$3,$4,$5,$6)")
        .bind(id).bind(title).bind(message).bind(&body.severity).bind(admin.user).bind(body.expires_at)
        .execute(&mut *tx).await?;
    audit_event(
        &mut tx,
        admin.user,
        "announcement:publish",
        None,
        reason,
        json!({"announcement_id":id,"severity":body.severity,"expires_at":body.expires_at}),
    )
    .await?;
    tx.commit().await?;
    Ok(Json(json!({"ok":true,"id":id})))
}

pub async fn disable_announcement(
    State(state): State<AppState>,
    admin: Admin,
    Path(id): Path<Uuid>,
) -> Result<Json<Value>, AppError> {
    owner(&admin)?;
    let mut tx = state.db.begin().await?;
    let changed = sqlx::query(
        "UPDATE server_announcements SET disabled_at=now() WHERE id=$1 AND disabled_at IS NULL",
    )
    .bind(id)
    .execute(&mut *tx)
    .await?
    .rows_affected();
    if changed == 0 {
        return Err(AppError::NotFound("Not found.".into()));
    }
    audit_event(
        &mut tx,
        admin.user,
        "announcement:disable",
        None,
        "Disabled service announcement",
        json!({"announcement_id":id}),
    )
    .await?;
    tx.commit().await?;
    Ok(Json(json!({"ok":true})))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RoleRequest {
    role: String,
    reason: String,
}

pub async fn change_operator(
    State(state): State<AppState>,
    admin: Admin,
    Path(user): Path<Uuid>,
    Json(body): Json<RoleRequest>,
) -> Result<Json<Value>, AppError> {
    owner(&admin)?;
    let reason = reason(&body.reason)?;
    if user == admin.user {
        return Err(AppError::Conflict(
            "You cannot change your own server role.".into(),
        ));
    }
    if !["owner", "moderator", "revoke"].contains(&body.role.as_str()) {
        return Err(AppError::Validation("Invalid server role.".into()));
    }
    let mut tx = state.db.begin().await?;
    // Shared with the offline administrator CLI. Take it before any role row lock.
    sqlx::query("SELECT pg_advisory_xact_lock(2392001)")
        .execute(&mut *tx)
        .await?;
    let still_owner: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM server_administrators WHERE user_id=$1 AND role='owner')",
    )
    .bind(admin.user)
    .fetch_one(&mut *tx)
    .await?;
    if !still_owner {
        return Err(AppError::NotFound("Not found.".into()));
    }

    let exists: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM users WHERE id=$1 AND deleted_at IS NULL)")
            .bind(user)
            .fetch_one(&mut *tx)
            .await?;
    if !exists {
        return Err(AppError::NotFound("Not found.".into()));
    }
    let current: Option<String> =
        sqlx::query_scalar("SELECT role FROM server_administrators WHERE user_id=$1 FOR UPDATE")
            .bind(user)
            .fetch_optional(&mut *tx)
            .await?;
    if current.as_deref() == Some("owner") && body.role != "owner" {
        let owners: i64 =
            sqlx::query_scalar("SELECT count(*) FROM server_administrators WHERE role='owner'")
                .fetch_one(&mut *tx)
                .await?;
        if owners <= 1 {
            return Err(AppError::Conflict(
                "The last owner cannot be removed.".into(),
            ));
        }
    }
    if body.role == "revoke" {
        sqlx::query("DELETE FROM server_administrators WHERE user_id=$1")
            .bind(user)
            .execute(&mut *tx)
            .await?;
    } else {
        sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,$2) ON CONFLICT(user_id) DO UPDATE SET role=excluded.role")
            .bind(user).bind(&body.role).execute(&mut *tx).await?;
        sqlx::query("DELETE FROM admin_sessions WHERE user_id=$1")
            .bind(user)
            .execute(&mut *tx)
            .await?;
        sqlx::query("DELETE FROM admin_key_challenges WHERE user_id=$1")
            .bind(user)
            .execute(&mut *tx)
            .await?;
    }
    audit_event(
        &mut tx,
        admin.user,
        "operator:role",
        Some(user),
        reason,
        json!({"previous_role":current,"new_role":body.role}),
    )
    .await?;
    tx.commit().await?;
    Ok(Json(json!({"ok":true})))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ReasonRequest {
    reason: String,
}

pub async fn reset_operator_2fa(
    State(state): State<AppState>,
    admin: Admin,
    Path(user): Path<Uuid>,
    Json(body): Json<ReasonRequest>,
) -> Result<Json<Value>, AppError> {
    owner(&admin)?;
    let reason = reason(&body.reason)?;
    if user == admin.user {
        return Err(AppError::Conflict(
            "Use the offline owner recovery procedure for your own account.".into(),
        ));
    }
    let mut tx = state.db.begin().await?;
    let changed=sqlx::query("UPDATE server_administrators SET secret=NULL,pending_secret=NULL,pending_session=NULL,pending_expires=NULL,pending_policy=NULL,pending_revision=NULL,auth_revision=auth_revision+1,last_step=-1 WHERE user_id=$1")
        .bind(user).execute(&mut *tx).await?.rows_affected();
    if changed == 0 {
        return Err(AppError::NotFound("Not found.".into()));
    }
    sqlx::query("DELETE FROM admin_recovery_codes WHERE user_id=$1")
        .bind(user)
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM admin_sessions WHERE user_id=$1")
        .bind(user)
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM admin_key_challenges WHERE user_id=$1")
        .bind(user)
        .execute(&mut *tx)
        .await?;
    audit_event(
        &mut tx,
        admin.user,
        "operator:reset_2fa",
        Some(user),
        reason,
        json!({}),
    )
    .await?;
    tx.commit().await?;
    Ok(Json(json!({"ok":true})))
}

pub async fn issue_recovery_code(
    State(state): State<AppState>,
    admin: Admin,
    Path(user): Path<Uuid>,
    Json(body): Json<ReasonRequest>,
) -> Result<Json<Value>, AppError> {
    owner(&admin)?;
    let reason = reason(&body.reason)?;
    let mut tx = state.db.begin().await?;
    let exists: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM users WHERE id=$1 AND deleted_at IS NULL)")
            .bind(user)
            .fetch_one(&mut *tx)
            .await?;
    if !exists {
        return Err(AppError::NotFound("Not found.".into()));
    }
    let code = crate::auth::recovery::generate()?;
    let normalized = crate::auth::recovery::normalize(&code).ok_or(AppError::Internal)?;
    let expires_at = Utc::now() + Duration::hours(1);
    sqlx::query("INSERT INTO account_recovery(user_id,code_hash,expires_at,issued_by_admin) VALUES($1,$2,$3,$4)
        ON CONFLICT(user_id) DO UPDATE SET code_hash=excluded.code_hash,created_at=now(),expires_at=excluded.expires_at,issued_by_admin=excluded.issued_by_admin")
        .bind(user).bind(crate::auth::recovery::hash(user,&normalized)).bind(expires_at).bind(admin.user)
        .execute(&mut *tx).await?;
    audit_event(
        &mut tx,
        admin.user,
        "credential:recovery_code",
        Some(user),
        reason,
        json!({"expires_at":expires_at}),
    )
    .await?;
    tx.commit().await?;
    Ok(Json(
        json!({"code":code,"expires_at":expires_at,"single_use":true}),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_fields_are_bounded_and_machine_readable() {
        assert!(version("0.2.0-alpha+17"));
        assert!(version(""));
        assert!(!version("0.2.0 alpha"));
        assert!(!version(&"a".repeat(65)));
    }

    #[test]
    fn audit_reason_is_required_and_bounded() {
        assert!(reason(" operator request ").is_ok());
        assert!(reason("  ").is_err());
        assert!(reason(&"x".repeat(2001)).is_err());
    }
}

use crate::{auth::middleware::AuthUser, error::AppError, state::AppState};
use sqlx::{Postgres, Transaction};
use uuid::Uuid;

pub const MAX_AVATAR_BYTES: i64 = 3 * 1024 * 1024;

pub async fn require_storage_ready(state: &AppState) -> Result<(), AppError> {
    state.s3.require_client_uploads()?;
    if legacy_pending(state).await? {
        return Err(AppError::Unavailable(
            "Storage upgrade is still verifying previous uploads. Retry shortly.".into(),
        ));
    }
    Ok(())
}

async fn legacy_pending(state: &AppState) -> Result<bool, AppError> {
    Ok(sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM avatar_uploads WHERE expected_bytes IS NULL AND NOT legacy_reconciled) OR EXISTS(SELECT 1 FROM account_object_deletions WHERE legacy_unverified)").fetch_one(&state.db).await?)
}

/// Reconcile only pre-migration rows. The deployment must first block old
/// privileged PUT capabilities and terminate/drain their existing requests.
/// A bounded batch also lets a large upgrade continue without blocking chat.
pub async fn reconcile_legacy(state: &AppState) -> Result<(), AppError> {
    if !legacy_pending(state).await? {
        return Ok(());
    }
    let revoked = std::env::var("S3_LEGACY_PUT_REVOKED_AT")
        .ok()
        .and_then(|s| s.parse::<i64>().ok())
        .filter(|n| *n > 0)
        .ok_or_else(|| {
            AppError::Unavailable(
                "Old storage PUT links must be revoked and drained before reconciliation.".into(),
            )
        })?;
    if chrono::Utc::now().timestamp().saturating_sub(revoked) < 60 {
        return Err(AppError::Unavailable(
            "Waiting for revoked storage requests to drain.".into(),
        ));
    }
    state.s3.verify_unversioned_bucket().await.map_err(|_| {
        AppError::Unavailable("Storage bucket is not verified for quota reconciliation.".into())
    })?;
    let roots:Vec<String>=sqlx::query_scalar("SELECT object_key FROM avatar_uploads WHERE expected_bytes IS NULL AND NOT legacy_reconciled ORDER BY object_key LIMIT 64").fetch_all(&state.db).await?;
    for key in roots {
        let bytes = state
            .s3
            .head_object(&key)
            .await
            .map_err(|_| {
                AppError::Unavailable("Storage audit is awaiting the object store.".into())
            })?
            .unwrap_or(0);
        let bytes = i64::try_from(bytes).map_err(|_| AppError::Internal)?;
        sqlx::query("UPDATE avatar_uploads SET reserved_bytes=$2,legacy_reconciled=true WHERE object_key=$1 AND expected_bytes IS NULL AND NOT legacy_reconciled").bind(key).bind(bytes).execute(&state.db).await?;
    }
    let deleted:Vec<String>=sqlx::query_scalar("SELECT object_key FROM account_object_deletions WHERE legacy_unverified ORDER BY object_key LIMIT 64").fetch_all(&state.db).await?;
    for key in deleted {
        let bytes = state
            .s3
            .head_object(&key)
            .await
            .map_err(|_| {
                AppError::Unavailable("Storage audit is awaiting the object store.".into())
            })?
            .unwrap_or(0);
        let bytes = i64::try_from(bytes).map_err(|_| AppError::Internal)?;
        sqlx::query("UPDATE account_object_deletions SET reserved_bytes=$2,legacy_unverified=false WHERE object_key=$1 AND legacy_unverified").bind(key).bind(bytes).execute(&state.db).await?;
    }
    if legacy_pending(state).await? {
        return Err(AppError::Unavailable(
            "Storage audit is continuing in the background.".into(),
        ));
    }
    Ok(())
}
pub fn validate_size(bytes: i64) -> Result<(), AppError> {
    if !(1..=MAX_AVATAR_BYTES).contains(&bytes) {
        return Err(AppError::Validation(
            "Avatar size must be between 1 byte and 3 MiB.".into(),
        ));
    }
    Ok(())
}
fn original(key: &str) -> Option<String> {
    if let Some(id) = key.strip_prefix("avatars/") {
        let id = Uuid::parse_str(id).ok()?;
        return (key == format!("avatars/{id}")).then(|| key.to_owned());
    }
    let (id, name) = key.strip_prefix("avatar-renditions/")?.split_once('/')?;
    let parsed = Uuid::parse_str(id).ok()?;
    if parsed.to_string() != id || !["size-96.jpg", "size-256.jpg", "size-640.jpg"].contains(&name)
    {
        return None;
    }
    Some(format!("avatars/{id}"))
}

/// Shared by both authoritative avatar commit routes, before taking user locks.
pub async fn validate_upload(
    state: &AppState,
    owner: Uuid,
    key: &str,
) -> Result<Option<i64>, AppError> {
    if original(key).as_deref() != Some(key) {
        return Err(AppError::Validation("Invalid avatar object key.".into()));
    }
    let row:Option<(Option<i64>,bool)>=sqlx::query_as("SELECT a.expected_bytes,EXISTS(SELECT 1 FROM users u WHERE u.id=$2 AND u.avatar_key=$1 AND u.deleted_at IS NULL) FROM avatar_uploads a WHERE a.object_key=$1 AND a.user_id=$2 AND NOT a.cleanup_pending")
        .bind(key).bind(owner).fetch_optional(&state.db).await?;
    let (expected, current) =
        row.ok_or_else(|| AppError::NotFound("Avatar upload not found.".into()))?;
    if expected.is_none() && !current {
        return Err(AppError::Validation("This legacy upload has no verified size. Select the avatar again with the latest UNYRI.".into()));
    }
    super::avatar_sizes::validate_original(state, key, expected).await?;
    Ok(expected)
}

/// The user row is already locked by the caller. Fence cleanup/validation races.
pub async fn lock_commit(
    tx: &mut Transaction<'_, Postgres>,
    owner: Uuid,
    key: &str,
    expected: Option<i64>,
) -> Result<(), AppError> {
    let row:Option<Option<i64>>=sqlx::query_scalar("SELECT expected_bytes FROM avatar_uploads WHERE object_key=$1 AND user_id=$2 AND NOT cleanup_pending FOR UPDATE")
        .bind(key).bind(owner).fetch_optional(&mut **tx).await?;
    if row != Some(expected) {
        return Err(AppError::Conflict(
            "Avatar upload changed. Select it again.".into(),
        ));
    }
    if expected.is_none() {
        let current:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM users WHERE id=$1 AND avatar_key=$2 AND deleted_at IS NULL)")
            .bind(owner).bind(key).fetch_one(&mut **tx).await?;
        if !current {
            return Err(AppError::Validation(
                "Legacy avatar is no longer current.".into(),
            ));
        }
    }
    sqlx::query("UPDATE avatar_uploads SET is_complete=true WHERE object_key=$1")
        .bind(key)
        .execute(&mut **tx)
        .await?;
    Ok(())
}

pub async fn create(state: &AppState, auth: &AuthUser, bytes: i64) -> Result<String, AppError> {
    validate_size(bytes)?;
    require_storage_ready(state).await?;
    let mut tx = state.db.begin().await?;
    crate::auth::middleware::lock_active_user(&mut tx, auth).await?;
    let (max_file,quota,budget):(i64,i64,i64)=sqlx::query_as("SELECT max_file_bytes,user_quota_bytes,storage_budget_bytes FROM server_policy WHERE id=true FOR UPDATE").fetch_one(&mut *tx).await?;
    if bytes > max_file {
        return Err(AppError::Validation(
            "Avatar exceeds this server's upload limit.".into(),
        ));
    }
    let used: i64 = sqlx::query_scalar(
        "SELECT COALESCE(SUM(bytes),0)::bigint FROM storage_reservations WHERE owner_id=$1",
    )
    .bind(auth.user_id)
    .fetch_one(&mut *tx)
    .await?;
    let total: i64 =
        sqlx::query_scalar("SELECT COALESCE(SUM(bytes),0)::bigint FROM storage_reservations")
            .fetch_one(&mut *tx)
            .await?;
    if (quota > 0 && bytes > quota.saturating_sub(used))
        || (budget > 0 && bytes > budget.saturating_sub(total))
    {
        return Err(AppError::Validation("Avatar storage quota is full.".into()));
    }
    let key = format!("avatars/{}", Uuid::new_v4());
    sqlx::query("INSERT INTO avatar_uploads(object_key,user_id,expected_bytes,reserved_bytes,upload_lease_until) VALUES($1,$2,$3,$3,now()+make_interval(secs=>$4))")
        .bind(&key).bind(auth.user_id).bind(bytes).bind(state.config.s3.presign_ttl_seconds.saturating_add(state.config.s3.max_upload_duration_seconds).saturating_add(60) as f64).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(key)
}

/// Reserve generated objects before writing, and keep a root lease across all I/O.
pub async fn reserve_renditions(
    state: &AppState,
    owner: Uuid,
    key: &str,
    images: &[(u32, Vec<u8>)],
) -> Result<bool, AppError> {
    if require_storage_ready(state).await.is_err() {
        return Ok(false);
    }
    let mut tx = state.db.begin().await?;
    let current: Option<Uuid> = sqlx::query_scalar(
        "SELECT id FROM users WHERE id=$1 AND avatar_key=$2 AND deleted_at IS NULL FOR UPDATE",
    )
    .bind(owner)
    .bind(key)
    .fetch_optional(&mut *tx)
    .await?;
    if current.is_none() {
        return Err(AppError::NotFound("Avatar changed.".into()));
    }
    let (max_file,quota,budget):(i64,i64,i64)=sqlx::query_as("SELECT max_file_bytes,user_quota_bytes,storage_budget_bytes FROM server_policy WHERE id=true FOR UPDATE").fetch_one(&mut *tx).await?;
    let mut required = 0i64;
    let mut targets = Vec::new();
    for (edge, bytes) in images {
        if ![96, 256, 640].contains(edge)
            || bytes.is_empty()
            || bytes.len() as i64 > MAX_AVATAR_BYTES.min(max_file)
        {
            return Ok(false);
        }
        let target = format!(
            "avatar-renditions/{}/size-{edge}.jpg",
            key.trim_start_matches("avatars/")
        );
        let old:Option<(i64,bool)>=sqlx::query_as("SELECT reserved_bytes,cleanup_pending FROM avatar_uploads WHERE object_key=$1 FOR UPDATE").bind(&target).fetch_optional(&mut *tx).await?;
        if old.is_some_and(|(_, cleaning)| cleaning) {
            return Ok(false);
        }
        required += (bytes.len() as i64)
            .saturating_sub(old.map_or(0, |v| v.0))
            .max(0);
        targets.push((target, bytes.len() as i64));
    }
    let used: i64 = sqlx::query_scalar(
        "SELECT COALESCE(SUM(bytes),0)::bigint FROM storage_reservations WHERE owner_id=$1",
    )
    .bind(owner)
    .fetch_one(&mut *tx)
    .await?;
    let total: i64 =
        sqlx::query_scalar("SELECT COALESCE(SUM(bytes),0)::bigint FROM storage_reservations")
            .fetch_one(&mut *tx)
            .await?;
    if (quota > 0 && required > quota.saturating_sub(used))
        || (budget > 0 && required > budget.saturating_sub(total))
    {
        return Ok(false);
    }
    for (target, bytes) in targets {
        sqlx::query("INSERT INTO avatar_uploads(object_key,user_id,expected_bytes,reserved_bytes,upload_lease_until) VALUES($1,$2,$3,$3,now()+interval '5 minutes') ON CONFLICT(object_key) DO UPDATE SET reserved_bytes=GREATEST(avatar_uploads.reserved_bytes,EXCLUDED.reserved_bytes),expected_bytes=EXCLUDED.expected_bytes,upload_lease_until=GREATEST(avatar_uploads.upload_lease_until,EXCLUDED.upload_lease_until)")
            .bind(target).bind(owner).bind(bytes).execute(&mut *tx).await?;
    }
    sqlx::query("UPDATE avatar_uploads SET upload_lease_until=GREATEST(upload_lease_until,now()+interval '5 minutes') WHERE object_key=$1 AND NOT cleanup_pending").bind(key).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(true)
}

pub async fn sweep(state: &AppState) -> Result<(), AppError> {
    let candidates:Vec<(String,Uuid)>=sqlx::query_as("SELECT object_key,user_id FROM avatar_uploads a WHERE (expected_bytes IS NOT NULL OR legacy_reconciled) AND upload_lease_until<now() AND (is_complete OR cleanup_pending OR created_at<now()-interval '24 hours') AND NOT EXISTS(SELECT 1 FROM users u WHERE u.avatar_key=a.object_key OR u.avatar_key='avatars/'||split_part(a.object_key,'/',2)) ORDER BY created_at LIMIT 64").fetch_all(&state.db).await?;
    for (key, owner) in candidates {
        let Some(root) = original(&key) else {
            continue;
        };
        let mut tx = state.db.begin().await?;
        sqlx::query("SELECT id FROM users WHERE id=$1 FOR UPDATE")
            .bind(owner)
            .execute(&mut *tx)
            .await?;
        let used: bool =
            sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM users WHERE avatar_key=$1)")
                .bind(&root)
                .fetch_one(&mut *tx)
                .await?;
        if used {
            continue;
        }
        let derived: Vec<String> = [96, 256, 640]
            .iter()
            .map(|edge| {
                format!(
                    "avatar-renditions/{}/size-{edge}.jpg",
                    root.trim_start_matches("avatars/")
                )
            })
            .collect();
        let live_child:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM avatar_uploads WHERE object_key=ANY($1) AND upload_lease_until>=now())").bind(&derived).fetch_one(&mut *tx).await?;
        if key == root && live_child {
            continue;
        }
        let marked=sqlx::query("UPDATE avatar_uploads SET cleanup_pending=true WHERE object_key=$1 AND user_id=$2 AND upload_lease_until<now()").bind(&key).bind(owner).execute(&mut *tx).await?.rows_affected()==1;
        tx.commit().await?;
        if !marked || state.s3.delete_object(&key).await.is_err() {
            continue;
        }
        let keys = if key == root {
            let mut keys = derived;
            keys.push(key.clone());
            keys
        } else {
            vec![key.clone()]
        };
        sqlx::query(
            "DELETE FROM avatar_uploads WHERE object_key=ANY($1) AND upload_lease_until<now()",
        )
        .bind(keys)
        .execute(&state.db)
        .await?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bounded_bytes_and_canonical_object_families() {
        assert!(validate_size(0).is_err());
        assert!(validate_size(-1).is_err());
        assert!(validate_size(MAX_AVATAR_BYTES + 1).is_err());
        assert!(validate_size(MAX_AVATAR_BYTES).is_ok());
        let id = Uuid::new_v4();
        let root = format!("avatars/{id}");
        assert_eq!(original(&root), Some(root.clone()));
        assert_eq!(
            original(&format!("avatar-renditions/{id}/size-96.jpg")),
            Some(root)
        );
        assert!(original("avatars/../secret").is_none());
        assert!(original(&format!("avatar-renditions/{id}/other.jpg")).is_none());
    }
}

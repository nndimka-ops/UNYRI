use uuid::Uuid;

use crate::state::AppState;

/// Revoke authorization in the same transaction as the message tombstone.
/// Storage deletion is durable/retryable and its bytes stay quota-accounted.
pub async fn retire_message_attachments(tx: &mut sqlx::Transaction<'_,sqlx::Postgres>, message: Uuid, presign_ttl: i64) -> Result<(),crate::error::AppError> {
    let grace=presign_ttl.max(0).saturating_add(60).max(86400);
    sqlx::query("INSERT INTO account_object_deletions(object_key,multipart_upload_id,delete_after,reserved_bytes,owner_user_id) \
        SELECT object_key,multipart_upload_id,GREATEST(upload_lease_until,now()+($2*interval '1 second')),encrypted_size,uploader_user_id \
        FROM attachments WHERE message_id=$1 ON CONFLICT(object_key) DO UPDATE SET \
        delete_after=GREATEST(account_object_deletions.delete_after,EXCLUDED.delete_after), \
        reserved_bytes=GREATEST(account_object_deletions.reserved_bytes,EXCLUDED.reserved_bytes), \
        owner_user_id=COALESCE(account_object_deletions.owner_user_id,EXCLUDED.owner_user_id)")
        .bind(message).bind(grace as f64).execute(&mut **tx).await?;
    sqlx::query("DELETE FROM attachments WHERE message_id=$1").bind(message).execute(&mut **tx).await?;
    Ok(())
}

/// Unattached uploads older than this are abandoned (app killed mid-flow,
/// upload succeeded but the message send never did, etc) - never delete
/// anything already `attached` to a real message (Этап H п.14/37: message
/// deletion doesn't exist yet, so an attached object's lifetime is simply
/// "as long as the message exists").
const ORPHAN_MAX_AGE_HOURS: i64 = 24;

/// Deletes attachments that were never attached to a message within
/// [ORPHAN_MAX_AGE_HOURS]. Best-effort object deletion in storage; the row
/// in Postgres is the authorization source of truth regardless, so a
/// leftover orphan blob (if the storage delete call fails) is inert - no
/// message ever gets to reference it once the row is gone.
/// Persist the tombstone before touching S3. Cancellation or process death cannot
/// make the object attachable again after its bytes have been removed.
pub async fn delete_abandoned(state: &AppState, id: Uuid, actor: Option<Uuid>) -> Result<bool, crate::error::AppError> {
    let mut tx=state.db.begin().await?;
    let row: Option<(String,Option<String>)> = sqlx::query_as("UPDATE attachments SET cleanup_pending=true WHERE id=$1 AND status<>'attached' AND message_id IS NULL AND created_at<now()-interval '24 hours' AND upload_lease_until<now() RETURNING object_key,multipart_upload_id")
        .bind(id).fetch_optional(&mut *tx).await?;
    let Some((key,upload))=row else {return Ok(false)};
    if let Some(actor)=actor {
        sqlx::query("INSERT INTO admin_audit(actor,action,target,reason) VALUES($1,'storage:cleanup_scheduled',$2,'Confirmed orphan cleanup preview')").bind(actor).bind(id).execute(&mut *tx).await?;
    }
    tx.commit().await?;
    if let Some(upload)=upload {state.s3.abort_multipart(&key,&upload).await.map_err(|_|crate::error::AppError::Internal)?;}
    state.s3.delete_object(&key).await.map_err(|_|crate::error::AppError::Internal)?;
    Ok(sqlx::query("DELETE FROM attachments WHERE id=$1 AND cleanup_pending").bind(id).execute(&state.db).await?.rows_affected()==1)
}

pub async fn sweep_orphan_attachments(state: &AppState) -> anyhow::Result<u64> {
    let cutoff = chrono::Utc::now() - chrono::Duration::hours(ORPHAN_MAX_AGE_HOURS);
    let rows: Vec<Uuid> = sqlx::query_scalar("SELECT id FROM attachments WHERE status<>'attached' AND message_id IS NULL AND created_at<$1 AND upload_lease_until<now() ORDER BY created_at LIMIT 1000")
        .bind(cutoff).fetch_all(&state.db).await?;
    let mut deleted=0;
    for id in rows {
        match delete_abandoned(state,id,None).await {
            Ok(true)=>deleted+=1,Ok(false)=>{},Err(_)=>tracing::warn!(attachment_id=%id,"Orphan cleanup will be retried"),
        }
    }
    Ok(deleted)
}

/// No new scheduler dependency - a plain `tokio::spawn` + `interval` loop is
/// all this needs (the runtime is already Tokio).
pub fn spawn_periodic_cleanup(state: AppState) {
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(3600));
        loop {
            interval.tick().await;
            match sweep_orphan_attachments(&state).await {
                Ok(count) if count > 0 => tracing::info!(count, "swept orphan attachments"),
                Ok(_) => {}
                Err(err) => tracing::error!(error = %err, "orphan attachment sweep failed"),
            }
        }
    });
}

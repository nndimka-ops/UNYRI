//! Bounded provisioning of historical accounts. The identity rows themselves
//! are durable checkpoints; the migration's trigger covers all new accounts.
use crate::error::AppError;
use sqlx::{PgPool, Postgres, Transaction};
use std::time::Duration;
use uuid::Uuid;

pub const BATCH_SIZE: i64 = 32;

/// On-demand provisioning shares the account lock with login, profile changes,
/// deletion, and the worker. Existing identities require no new row lock.
pub(crate) async fn ensure(
    tx: &mut Transaction<'_, Postgres>,
    user: Uuid,
) -> Result<bool, AppError> {
    let exists: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM user_identities WHERE user_id=$1)")
            .bind(user)
            .fetch_one(&mut **tx)
            .await?;
    if exists {
        return Ok(true);
    }
    let live: Option<Uuid> = sqlx::query_scalar(
        "SELECT id FROM users WHERE id=$1 AND deleted_at IS NULL FOR NO KEY UPDATE",
    )
    .bind(user)
    .fetch_optional(&mut **tx)
    .await?;
    if live.is_none() {
        return Ok(false);
    }
    // Recheck after waiting: another worker/request may have provisioned it.
    sqlx::query("INSERT INTO user_identities(user_id,unyri_number) \
        SELECT $1,allocate_public_number($1) WHERE NOT EXISTS(SELECT 1 FROM user_identities WHERE user_id=$1) \
        ON CONFLICT(user_id) DO NOTHING")
        .bind(user).execute(&mut **tx).await?;
    Ok(true)
}

#[derive(Debug)]
pub struct BackfillBatch {
    pub after: Option<Uuid>,
    pub scanned: usize,
    pub provisioned: u64,
    pub retry_needed: bool,
}

/// Page the users PK *before* filtering already completed work. This bounds
/// scans too: a restart never scans an unbounded completed prefix in one query.
/// Locked missing accounts are skipped and retried on the next pass.
pub async fn backfill_batch(db: &PgPool, after: Option<Uuid>) -> Result<BackfillBatch, AppError> {
    tokio::time::timeout(Duration::from_secs(1), async {
        let mut tx=db.begin().await?;
        sqlx::query("SET LOCAL lock_timeout='100ms'").execute(&mut *tx).await?;
        sqlx::query("SET LOCAL statement_timeout='500ms'").execute(&mut *tx).await?;
        sqlx::query("SET LOCAL idle_in_transaction_session_timeout='1s'").execute(&mut *tx).await?;
        let candidates: Vec<Uuid>=sqlx::query_scalar("SELECT id FROM users WHERE id>=COALESCE($1,'00000000-0000-0000-0000-000000000000'::uuid) AND ($1::uuid IS NULL OR id<>$1) ORDER BY id LIMIT $2")
            .bind(after).bind(BATCH_SIZE).fetch_all(&mut *tx).await?;
        let pending: Vec<Uuid>=sqlx::query_scalar("SELECT u.id FROM users u WHERE u.id=ANY($1) \
            AND NOT EXISTS(SELECT 1 FROM user_identities i WHERE i.user_id=u.id) \
            ORDER BY u.id FOR NO KEY UPDATE OF u SKIP LOCKED")
            .bind(&candidates).fetch_all(&mut *tx).await?;
        let provisioned=sqlx::query("INSERT INTO user_identities(user_id,unyri_number) \
            SELECT id,allocate_public_number(id) FROM unnest($1::uuid[]) AS candidate(id) \
            ON CONFLICT(user_id) DO NOTHING")
            .bind(&pending).execute(&mut *tx).await?.rows_affected();
        let retry_needed: bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM users u WHERE u.id=ANY($1) \
            AND NOT EXISTS(SELECT 1 FROM user_identities i WHERE i.user_id=u.id))")
            .bind(&candidates).fetch_one(&mut *tx).await?;
        tx.commit().await?;
        Ok(BackfillBatch {after:candidates.last().copied(),scanned:candidates.len(),provisioned,retry_needed})
    }).await.map_err(|_|AppError::Unavailable("Identity backfill batch timed out.".into()))?
}

pub fn spawn_backfill(db: PgPool) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let mut after = None;
        let mut retry_pass = false;
        loop {
            match backfill_batch(&db, after).await {
                Ok(batch) if batch.scanned == 0 => {
                    if !retry_pass {
                        tracing::info!("historical public identity provisioning complete");
                        return;
                    }
                    after = None;
                    retry_pass = false;
                    tokio::time::sleep(Duration::from_millis(250)).await;
                }
                Ok(batch) => {
                    after = batch.after;
                    retry_pass |= batch.retry_needed;
                    tokio::time::sleep(Duration::from_millis(10)).await;
                }
                Err(err) => {
                    // Keep committed progress and retry this same page. A transient
                    // error must neither stop startup nor mark skipped work done.
                    tracing::warn!(error=?err,"public identity backfill batch will retry");
                    tokio::time::sleep(Duration::from_secs(5)).await;
                }
            }
        }
    })
}

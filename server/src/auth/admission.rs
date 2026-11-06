use sqlx::{Postgres,Transaction};
use uuid::Uuid;
use crate::error::AppError;

// Two-integer advisory-lock namespace avoids the one-bigint per-account locks.
// Logins/registrations/refreshes share this lock; restriction changes take it
// exclusively before account/session locks. Requests can otherwise run concurrently.
pub async fn lock(tx: &mut Transaction<'_,Postgres>, exclusive: bool) -> Result<(),AppError> {
    let sql=if exclusive { "SELECT pg_advisory_xact_lock(1129268047,29)" }
        else { "SELECT pg_advisory_xact_lock_shared(1129268047,29)" };
    sqlx::query(sql).execute(&mut **tx).await?;Ok(())
}
pub async fn check_installation(tx: &mut Transaction<'_,Postgres>, installation: Uuid) -> Result<(),AppError> {
    let blocked:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM installation_suspensions WHERE installation_id=$1 AND (until_date IS NULL OR until_date>now()))")
        .bind(installation).fetch_one(&mut **tx).await?;
    if blocked {return Err(AppError::InstallationRestricted)}
    Ok(())
}

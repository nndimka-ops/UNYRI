use chrono::{DateTime, Utc};
use sqlx::PgPool;

use super::middleware::AuthUser;
use crate::error::AppError;

/// Password login starts a new private workspace even on the same installation.
/// Refresh rotation follows its parent chain and never advances this boundary.
pub async fn visible_since(db: &PgPool, auth: &AuthUser) -> Result<DateTime<Utc>, AppError> {
    let date: Option<DateTime<Utc>> = sqlx::query_scalar(
        "WITH RECURSIVE lineage AS (
           SELECT id, created_at FROM sessions WHERE id=$1 AND user_id=$2 AND device_id=$3
           UNION
           SELECT p.id, p.created_at FROM sessions p JOIN lineage c ON p.replaced_by=c.id
           WHERE p.user_id=$2 AND p.device_id=$3
         ) SELECT MIN(created_at) FROM lineage",
    ).bind(auth.session_id).bind(auth.user_id).bind(auth.device_id)
        .fetch_one(db).await?;
    date.ok_or(AppError::SessionRevoked)
}

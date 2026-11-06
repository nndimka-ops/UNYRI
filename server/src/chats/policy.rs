use sqlx::PgPool;
use uuid::Uuid;

use crate::error::AppError;

const BLOCKED: &str = "SELECT EXISTS(SELECT 1 FROM chat_preferences WHERE chat_id = $1 AND is_blocked) \
    OR EXISTS(SELECT 1 FROM chats c WHERE c.id=$1 AND c.type='direct' \
      AND user_pair_blocked(c.user_a_id,c.user_b_id))";

/// Muting affects notifications only. Blocking prevents new communication
/// in either direction until the person who set the block removes it.
pub async fn require_contact_allowed(db: &PgPool, chat_id: Uuid) -> Result<(), AppError> {
    let blocked: bool = sqlx::query_scalar(BLOCKED)
    .bind(chat_id)
    .fetch_one(db)
    .await?;
    if blocked {
        return Err(AppError::Conflict("Communication is unavailable for this chat.".into()));
    }
    Ok(())
}

pub async fn require_device_contact_allowed(db: &PgPool, chat_id: Uuid, device: Uuid) -> Result<(), AppError> {
    require_contact_allowed(db,chat_id).await?;
    let blocked:bool=sqlx::query_scalar("SELECT user_device_chat_blocked($1,$2)")
        .bind(device).bind(chat_id).fetch_one(db).await?;
    if blocked { return Err(AppError::Conflict("Communication is unavailable for this chat.".into())); }
    Ok(())
}

/// Recheck after acquiring the same ordered account locks as block mutations.
/// Once blocking commits, a later send cannot pass an earlier admission check.
pub async fn lock_contact_allowed(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>, chat_id: Uuid, device: Uuid,
) -> Result<(), AppError> {
    sqlx::query("SELECT id FROM users WHERE id IN \
        (SELECT user_a_id FROM chats WHERE id=$1 UNION SELECT user_b_id FROM chats WHERE id=$1) \
        ORDER BY id FOR UPDATE")
        .bind(chat_id).execute(&mut **tx).await?;
    let blocked: bool = sqlx::query_scalar(&format!("SELECT ({BLOCKED}) OR user_device_chat_blocked($2,$1)"))
        .bind(chat_id).bind(device).fetch_one(&mut **tx).await?;
    if blocked {
        return Err(AppError::Conflict("Communication is unavailable for this chat.".into()));
    }
    Ok(())
}

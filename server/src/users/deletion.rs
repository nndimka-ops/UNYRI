use crate::net::ClientIp;
use crate::{
    auth::{middleware::AuthUser, password, rate_limit},
    error::AppError,
    state::AppState,
    websocket::{self, protocol::ServerMessage},
};
use axum::{extract::State, http::StatusCode, Json};
use serde::Deserialize;
use sqlx::{Postgres, Transaction};
use uuid::Uuid;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DeleteAccountRequest {
    pub password: String,
}

pub struct DeletedAccountEffects {
    pub peers: Vec<(Uuid, Uuid)>,
    pub devices: Vec<Uuid>,
    pub call_peers: Vec<Uuid>,
}

/// Reauthentication and all relational removals are one account-locked transaction.
/// Storage bytes are reclaimed by a durable queue after outstanding capabilities expire.
pub async fn delete_account(
    State(state): State<AppState>,
    ClientIp(addr): ClientIp,
    auth: AuthUser,
    Json(request): Json<DeleteAccountRequest>,
) -> Result<StatusCode, AppError> {
    if request.password.is_empty() || request.password.len() > 1024 {
        return Err(AppError::Validation("Enter your current password.".into()));
    }
    rate_limit::enforce(&state, "account-delete", addr, 10, 3600).await?;
    // Account-wide budget remains effective if a caller changes its network address.
    rate_limit::enforce(
        &state,
        &format!("account-delete:{}", auth.user_id),
        "0.0.0.0".parse().unwrap(),
        5,
        3600,
    )
    .await?;
    let mut tx = state.db.begin().await?;
    // Feed allocation must commit in sequence order, before any account locks.
    sqlx::query("SELECT pg_advisory_xact_lock(6840210040)").execute(&mut *tx).await?;
    let hash: String = sqlx::query_scalar("SELECT password_hash FROM users WHERE id=$1 FOR UPDATE")
        .bind(auth.user_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or(AppError::Unauthorized)?;
    let valid = password::run(move || Ok(password::verify_password(&request.password, &hash))).await?;
    if !valid {
        return Err(AppError::Validation(
            "Current password is incorrect.".into(),
        ));
    }
    let session_active: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM sessions WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL AND expires_at>now())")
        .bind(auth.session_id).bind(auth.user_id).fetch_one(&mut *tx).await?;
    if !session_active {
        return Err(AppError::SessionRevoked);
    }
    let effects = delete_account_data(&state, &mut tx, auth.user_id).await?;
    tx.commit().await?;
    notify_account_deleted(&state, effects).await;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn delete_account_data(
    state: &AppState,
    tx: &mut Transaction<'_, Postgres>,
    user_id: Uuid,
) -> Result<DeletedAccountEffects, AppError> {
    sqlx::query("INSERT INTO account_performance_deletions(user_id) VALUES($1) ON CONFLICT DO NOTHING")
        .bind(user_id).execute(&mut **tx).await?;
    // Callers acquire the call-deletion feed lock before locking the user.
    // Calls themselves cascade on account removal, while peer tombstones must
    // survive so an offline device also removes its locally cached call rows.
    let call_peers: Vec<Uuid> = sqlx::query_scalar("SELECT DISTINCT CASE WHEN caller_user_id=$1 THEN callee_user_id ELSE caller_user_id END FROM calls WHERE caller_user_id=$1 OR callee_user_id=$1")
        .bind(user_id).fetch_all(&mut **tx).await?;
    sqlx::query("INSERT INTO call_history_deletions(user_id,call_id,call_created_at) SELECT CASE WHEN caller_user_id=$1 THEN callee_user_id ELSE caller_user_id END,id,created_at FROM calls WHERE caller_user_id=$1 OR callee_user_id=$1 ON CONFLICT(user_id,call_id) DO UPDATE SET call_created_at=EXCLUDED.call_created_at")
        .bind(user_id).execute(&mut **tx).await?;
    let devices: Vec<Uuid> = sqlx::query_scalar("SELECT id FROM devices WHERE user_id=$1")
        .bind(user_id)
        .fetch_all(&mut **tx)
        .await?;
    // Lock chats before collecting their attachment objects: new messages cannot race deletion.
    let chats: Vec<Uuid> = sqlx::query_scalar("SELECT id FROM chats WHERE user_a_id=$1 OR user_b_id=$1 OR owner_user_id=$1 ORDER BY id FOR UPDATE")
        .bind(user_id).fetch_all(&mut **tx).await?;
    let peers: Vec<(Uuid, Uuid)> = sqlx::query_as(
        "SELECT DISTINCT user_id, chat_id FROM chat_members WHERE chat_id=ANY($1) AND user_id<>$2",
    )
    .bind(&chats)
    .bind(user_id)
    .fetch_all(&mut **tx)
    .await?;
    for (user, chat) in &peers {
        sqlx::query(
            "INSERT INTO deleted_chats(user_id,chat_id) VALUES($1,$2) ON CONFLICT DO NOTHING",
        )
        .bind(user)
        .bind(chat)
        .execute(&mut **tx)
        .await?;
    }
    // Pending upload URLs can remain usable after revocation. Delay final object purge;
    // authorization disappears immediately, and failures retain durable cleanup ownership.
    let grace = state
        .config
        .s3
        .presign_ttl_seconds
        .max(0)
        .saturating_add(60)
        .max(86400);
    sqlx::query("INSERT INTO account_object_deletions(object_key,multipart_upload_id,delete_after,reserved_bytes,owner_user_id) SELECT object_key,multipart_upload_id,now()+($3 * interval '1 second'),encrypted_size,uploader_user_id FROM attachments WHERE uploader_user_id=$1 OR message_id IN (SELECT id FROM messages WHERE chat_id=ANY($2)) ON CONFLICT(object_key) DO UPDATE SET delete_after=GREATEST(account_object_deletions.delete_after,EXCLUDED.delete_after),reserved_bytes=GREATEST(account_object_deletions.reserved_bytes,EXCLUDED.reserved_bytes),owner_user_id=COALESCE(account_object_deletions.owner_user_id,EXCLUDED.owner_user_id)")
        .bind(user_id).bind(&chats).bind(grace as f64).execute(&mut **tx).await?;
    sqlx::query("INSERT INTO account_object_deletions(object_key,delete_after,reserved_bytes,legacy_unverified,owner_user_id) SELECT object_key,GREATEST(now()+($2 * interval '1 second'),upload_lease_until),reserved_bytes,expected_bytes IS NULL AND NOT legacy_reconciled,user_id FROM avatar_uploads WHERE user_id=$1 UNION SELECT avatar_key,now()+($2 * interval '1 second'),3145728::bigint,true,id FROM users WHERE id=$1 AND avatar_key IS NOT NULL AND NOT EXISTS(SELECT 1 FROM avatar_uploads WHERE object_key=users.avatar_key) ON CONFLICT(object_key) DO UPDATE SET delete_after=GREATEST(account_object_deletions.delete_after,EXCLUDED.delete_after),reserved_bytes=GREATEST(account_object_deletions.reserved_bytes,EXCLUDED.reserved_bytes),legacy_unverified=account_object_deletions.legacy_unverified OR EXCLUDED.legacy_unverified,owner_user_id=COALESCE(account_object_deletions.owner_user_id,EXCLUDED.owner_user_id)")
        .bind(user_id).bind(grace as f64).execute(&mut **tx).await?;
    sqlx::query("DELETE FROM attachments WHERE uploader_user_id=$1 OR message_id IN (SELECT id FROM messages WHERE chat_id=ANY($2))")
        .bind(user_id).bind(&chats).execute(&mut **tx).await?;
    sqlx::query("DELETE FROM avatar_uploads WHERE user_id=$1")
        .bind(user_id)
        .execute(&mut **tx)
        .await?;
    // Lifecycle/reply references may point at messages deleted in the same operation.
    sqlx::query("UPDATE messages SET target_message_id=NULL WHERE target_message_id IN (SELECT id FROM messages WHERE chat_id=ANY($1) OR sender_id=$2)")
        .bind(&chats).bind(user_id).execute(&mut **tx).await?;
    sqlx::query("DELETE FROM messages WHERE chat_id=ANY($1) OR sender_id=$2")
        .bind(&chats)
        .bind(user_id)
        .execute(&mut **tx)
        .await?;
    sqlx::query("DELETE FROM chats WHERE id=ANY($1)")
        .bind(&chats)
        .execute(&mut **tx)
        .await?;
    sqlx::query("DELETE FROM users WHERE id=$1")
        .bind(user_id)
        .execute(&mut **tx)
        .await?;
    Ok(DeletedAccountEffects { peers, devices, call_peers })
}

pub async fn notify_account_deleted(state: &AppState, effects: DeletedAccountEffects) {
    if sweep_deleted_performance(state).await.is_err() {
        tracing::warn!("deleted account performance cleanup pending");
    }
    crate::conferences::after_security_change(state).await;
    if let Ok(payload) = serde_json::to_string(&ServerMessage::SessionRevoked {
        reason: "account_deleted".into(),
    }) {
        for device in effects.devices {
            state.ws_registry.send_to_device(device, &payload).await;
        }
    }
    for (user, chat_id) in effects.peers {
        websocket::notify_user(state, user, &ServerMessage::ChatDeleted { chat_id }).await;
    }
    for user in effects.call_peers {
        websocket::notify_user(state, user, &ServerMessage::CallHistoryChanged).await;
    }
}

pub async fn sweep_deleted_objects(state: &AppState) -> anyhow::Result<u64> {
    let rows: Vec<(String, Option<String>)> = sqlx::query_as("SELECT object_key,multipart_upload_id FROM account_object_deletions WHERE delete_after<=now() AND NOT legacy_unverified ORDER BY delete_after LIMIT 100")
        .fetch_all(&state.db).await?;
    let mut removed = 0;
    for (key, upload) in rows {
        if let Some(upload) = upload {
            if state.s3.abort_multipart(&key, &upload).await.is_err() {
                continue;
            }
        }
        if state.s3.delete_object(&key).await.is_err() {
            continue;
        }
        sqlx::query("DELETE FROM account_object_deletions WHERE object_key=$1")
            .bind(key)
            .execute(&state.db)
            .await?;
        removed += 1;
    }
    Ok(removed)
}

pub async fn sweep_deleted_performance(state:&AppState)->Result<u64,AppError> {
    let mut tx=state.db.begin().await?;
    let users:Vec<Uuid>=sqlx::query_scalar("SELECT user_id FROM account_performance_deletions ORDER BY requested_at,user_id LIMIT 10 FOR UPDATE SKIP LOCKED")
        .fetch_all(&mut *tx).await?;
    if users.is_empty() { return Ok(0); }
    crate::performance::delete_account_samples(state,&users).await?;
    let removed=sqlx::query("DELETE FROM account_performance_deletions WHERE user_id=ANY($1)")
        .bind(&users).execute(&mut *tx).await?.rows_affected();
    tx.commit().await?;
    Ok(removed)
}
pub async fn sweep_avatar_objects(state: &AppState) -> Result<(),AppError> {
    super::avatar_uploads::sweep(state).await
}
pub async fn require_storage_ready(state:&AppState)->Result<(),AppError> {
    super::avatar_uploads::require_storage_ready(state).await
}
pub async fn reconcile_legacy_storage(state:&AppState)->Result<(),AppError> {
    super::avatar_uploads::reconcile_legacy(state).await
}
pub fn spawn_cleanup(state: AppState) {
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(60));
        loop {
            interval.tick().await;
            if reconcile_legacy_storage(&state).await.is_err() {
                tracing::warn!("previous storage uploads await quota reconciliation");
            }
            if sweep_avatar_objects(&state).await.is_err() {
                tracing::warn!("avatar storage cleanup pending");
            }
            if sweep_deleted_objects(&state).await.is_err() {
                tracing::warn!("Account object cleanup failed");
            }
            if sweep_deleted_performance(&state).await.is_err() {
                tracing::warn!("deleted account performance cleanup pending");
            }
        }
    });
}

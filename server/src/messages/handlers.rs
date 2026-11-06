use std::collections::HashMap;
use std::collections::HashSet;

use axum::extract::{Path, Query, State};
use axum::Json;
use base64::{engine::general_purpose::STANDARD, Engine as _};
use chrono::{DateTime, Utc};
use uuid::Uuid;

use crate::net::ClientIp;
use crate::auth::middleware::AuthUser;
use crate::auth::rate_limit;
use crate::error::AppError;
use crate::messages::models::{
    EnvelopeRow, ListMessagesQuery, MessageDto, MessageListResponse, MessageResponse, ReadReceiptRequest,
    ReadReceiptResponse, SendMessageRequest,
};
use crate::push::models::PushEvent;
use crate::state::AppState;
use crate::websocket;
use crate::websocket::protocol::ServerMessage;

const SEND_MESSAGE_LIMIT: u64 = 60;
const SEND_MESSAGE_WINDOW_SECONDS: u64 = 60;
const MAX_ENVELOPES_PER_MESSAGE: usize = 32;
const LIST_LIMIT_DEFAULT: i64 = 50;
const LIST_LIMIT_MAX: i64 = 100;

/// Reject invalid transport shape without inspecting E2E plaintext or Signal keys.
fn decode_message_ciphertext(value: &str) -> Result<Vec<u8>, AppError> {
    let ciphertext = crate::database_payload::decode_bounded(
        "ciphertext", value, crate::database_payload::MAX_CIPHERTEXT_BYTES,
    )?;
    if ciphertext.is_empty() {
        return Err(AppError::Validation("ciphertext must not be empty.".into()));
    }
    Ok(ciphertext)
}

async fn require_membership(state: &AppState, chat_id: Uuid, user_id: Uuid) -> Result<(), AppError> {
    let member: Option<(bool,)> =
        sqlx::query_as("SELECT true FROM chat_members WHERE chat_id = $1 AND user_id = $2 AND left_at IS NULL")
            .bind(chat_id)
            .bind(user_id)
            .fetch_optional(&state.db)
            .await?;
    if member.is_none() {
        return Err(AppError::NotFound("Chat not found.".into()));
    }
    Ok(())
}


struct TargetInfo {
    sender_id: Uuid,
    kind: String,
    deleted_at: Option<DateTime<Utc>>,
}

/// Looks up a lifecycle-event or reply target and enforces it belongs to
/// `chat_id` - returns 404 (never 403) for both "doesn't exist" and "wrong
/// chat", so a client can never use this to probe another chat's message
/// ids (Этап I п.34, same anti-probing pattern as `require_membership`).
async fn require_target(state: &AppState, chat_id: Uuid, target_message_id: Uuid) -> Result<TargetInfo, AppError> {
    let row: Option<(Uuid, Uuid, String, Option<DateTime<Utc>>)> = sqlx::query_as(
        "SELECT chat_id, sender_id, kind, deleted_at FROM messages WHERE id = $1",
    )
    .bind(target_message_id)
    .fetch_optional(&state.db)
    .await?;
    let (target_chat_id, sender_id, kind, deleted_at) =
        row.ok_or_else(|| AppError::NotFound("Message not found.".into()))?;
    if target_chat_id != chat_id {
        return Err(AppError::NotFound("Message not found.".into()));
    }
    Ok(TargetInfo { sender_id, kind, deleted_at })
}

/// `POST /chats/{chat_id}/messages` - idempotent on `client_message_id`, and
/// carries one Signal Protocol ciphertext envelope per recipient device (the
/// client decides the recipient device list - typically every active device
/// of every other chat member, plus optionally the sender's own other
/// devices for multi-device sync). The server never sees plaintext and
/// performs no cryptographic operations; it only validates that every
/// target device actually belongs to an active member of this chat, stores
/// the opaque bytes, and relays them.
///
/// Idempotency relies on the Этап A unique constraint
/// `messages_idempotency_key UNIQUE (sender_device_id, client_message_id)`
/// (scoped to the *sending device*, taken from the validated access token,
/// never from the request body). `INSERT ... ON CONFLICT DO NOTHING` then a
/// fallback SELECT is the same race-safe pattern used for direct chat
/// creation in Этап D - no explicit row locking needed.
pub async fn send_message(
    State(state): State<AppState>,
    ClientIp(addr): ClientIp,
    auth: AuthUser,
    Path(chat_id): Path<Uuid>,
    Json(req): Json<SendMessageRequest>,
) -> Result<Json<MessageResponse>, AppError> {
    req.validate_attachment_ids()?;
    rate_limit::enforce(&state, "send_message:ip", addr, 300, SEND_MESSAGE_WINDOW_SECONDS).await?;
    rate_limit::enforce(
        &state,
        &format!("send_message:user:{}", auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        SEND_MESSAGE_LIMIT,
        SEND_MESSAGE_WINDOW_SECONDS,
    ).await?;
    require_membership(&state, chat_id, auth.user_id).await?;
    crate::chats::policy::require_device_contact_allowed(&state.db, chat_id, auth.device_id).await?;

    // Saved Messages is the owner's own chat: on a single-device account there
    // is no other device to encrypt for, so zero envelopes is a valid send (the
    // sender keeps the plaintext locally; the message is still persisted
    // server-side for the saved-chat history). Every other chat type always has
    // at least one other participant, so zero envelopes stays invalid there.
    if req.envelopes.is_empty() {
        let is_saved_chat: bool = sqlx::query_scalar("SELECT (type = 'saved') FROM chats WHERE id = $1")
            .bind(chat_id)
            .fetch_one(&state.db)
            .await?;
        if !is_saved_chat {
            return Err(AppError::Validation("At least one recipient envelope is required.".into()));
        }
    }
    if req.envelopes.len() > MAX_ENVELOPES_PER_MESSAGE {
        return Err(AppError::Validation(format!("At most {MAX_ENVELOPES_PER_MESSAGE} envelopes per message.")));
    }

    let mut decoded = Vec::with_capacity(req.envelopes.len());
    for envelope in &req.envelopes {
        if envelope.message_type != 1 && envelope.message_type != 2 {
            return Err(AppError::Validation("message_type must be 1 (prekey) or 2 (whisper).".into()));
        }
        let ciphertext = decode_message_ciphertext(&envelope.ciphertext)?;
        decoded.push((envelope.recipient_device_id, envelope.message_type, ciphertext));
    }

    // Этап I: `kind` is a server-visible routing/validation discriminator
    // only - the actual edited text/reaction emoji/reply quote never
    // appears here, only inside the encrypted envelopes above. `target`
    // is dual-purpose: optional reply-to for 'content', required lifecycle
    // target for 'edit'/'delete'/'reaction'. Target must always be a
    // 'content' message (never reply-to or edit/delete/react an event row).
    if !matches!(req.kind.as_str(), "content" | "edit" | "delete" | "reaction") {
        return Err(AppError::Validation("kind must be one of content, edit, delete, reaction.".into()));
    }
    if let Some(target_id) = req.target_message_id {
        let target = require_target(&state, chat_id, target_id).await?;
        if target.kind != "content" {
            return Err(AppError::Validation("target_message_id must reference a content message.".into()));
        }
        if matches!(req.kind.as_str(), "edit" | "delete") && target.sender_id != auth.user_id {
            // Hide "exists but isn't yours" behind the same 404 as "doesn't exist" (anti-probing).
            return Err(AppError::NotFound("Message not found.".into()));
        }
        if req.kind == "edit" && target.deleted_at.is_some() {
            return Err(AppError::Validation("Cannot edit a deleted message.".into()));
        }
        if req.kind == "reaction" && target.deleted_at.is_some() {
            return Err(AppError::Validation("Cannot react to a deleted message.".into()));
        }
    } else if req.kind != "content" {
        return Err(AppError::Validation("target_message_id is required for edit, delete, and reaction.".into()));
    }

    // Every target device must belong to an active member of this chat -
    // never accept ciphertext addressed to an arbitrary device id.
    let recipient_device_ids: Vec<Uuid> = decoded.iter().map(|(id, _, _)| *id).collect();
    let valid_devices: Vec<(Uuid, Uuid)> = sqlx::query_as(
        "SELECT d.id, d.user_id FROM devices d \
         JOIN chat_members cm ON cm.user_id = d.user_id \
         WHERE d.id = ANY($1) AND cm.chat_id = $2 AND cm.left_at IS NULL AND d.revoked_at IS NULL",
    )
    .bind(&recipient_device_ids)
    .bind(chat_id)
    .fetch_all(&state.db)
    .await?;
    let mut device_owner: HashMap<Uuid, Uuid> = valid_devices.into_iter().collect();
    if recipient_device_ids.iter().any(|id| !device_owner.contains_key(id)) {
        return Err(AppError::Validation("One or more recipient devices are not members of this chat.".into()));
    }

    // Strict multi-device coverage (M.2): every OTHER active device of the
    // sender must receive an envelope. A single-device account has no such
    // requirement (zero-envelope Saved remains valid for it); a multi-device
    // account must never silently omit its own other devices - Saved and
    // direct chats both rely on this for multi-device sync. The sender's own
    // other devices are always members of its own chat, so membership above
    // is not enough: presence in the envelope list is required too.
    //
    // N.2R fix: only devices that have actually uploaded Signal key material
    // (an identity key) can ever be encrypted to - the client's bundle fetch
    // (`GET /keys/bundles`) silently skips keyless devices, so it can never
    // produce an envelope for them. Requiring an envelope for a keyless
    // device (e.g. a login that is still mid-bootstrap, or a session created
    // without the app's key-upload step) therefore made EVERY send fail with
    // "Every other active device of the sender must receive an envelope",
    // permanently wedging the account. A keyless device cannot decrypt
    // anything anyway, so excluding it from the coverage requirement is the
    // only consistent rule with the bundle fetch.
    let own_other_devices: Vec<Uuid> = sqlx::query_scalar(
        "SELECT d.id FROM devices d \
         JOIN device_identity_keys k ON k.device_id = d.id \
         WHERE d.user_id = $1 AND d.revoked_at IS NULL AND d.id <> $2 AND NOT user_device_chat_blocked(d.id,$3)",
    )
    .bind(auth.user_id)
    .bind(auth.device_id)
    .bind(chat_id)
    .fetch_all(&state.db)
    .await?;
    for own_device in own_other_devices {
        if !device_owner.contains_key(&own_device) {
            return Err(AppError::Validation(
                "Every other active device of the sender must receive an envelope.".into(),
            ));
        }
    }

    // Message insert + attachment association happen in one transaction -
    // an attachment must never end up "attached" to a message that itself
    // failed to commit (see Этап H п.13/44).
    let mut tx = state.db.begin().await?;
    crate::chats::policy::lock_contact_allowed(&mut tx, chat_id, auth.device_id).await?;
    crate::auth::middleware::lock_active_user(&mut tx, &auth).await?;
    let denied: Vec<Uuid> = sqlx::query_scalar("SELECT id FROM devices WHERE id=ANY($1) AND \
        (user_device_chat_blocked(id,$2) OR user_device_blocked(id,$3))")
        .bind(&recipient_device_ids).bind(chat_id).bind(auth.user_id).fetch_all(&mut *tx).await?;
    let had_peer = device_owner.values().any(|id| *id != auth.user_id);
    decoded.retain(|(id,_,_)| !denied.contains(id));
    device_owner.retain(|id,_| !denied.contains(id));
    if had_peer && !device_owner.values().any(|id| *id != auth.user_id) {
        return Err(AppError::Conflict("Communication is unavailable for this chat.".into()));
    }

    let inserted: Option<(Uuid, i64, DateTime<Utc>)> = sqlx::query_as(
        "INSERT INTO messages (chat_id, sender_id, sender_device_id, client_message_id, ciphertext, encryption_version, kind, target_message_id) \
         VALUES ($1, $2, $3, $4, NULL, 1, $5, $6) \
         ON CONFLICT (sender_device_id, client_message_id) DO NOTHING \
         RETURNING id, sequence, created_at",
    )
    .bind(chat_id)
    .bind(auth.user_id)
    .bind(auth.device_id)
    .bind(req.client_message_id)
    .bind(&req.kind)
    .bind(req.target_message_id)
    .fetch_optional(&mut *tx)
    .await?;

    let (message_id, sequence, created_at, is_new) = match inserted {
        Some((id, sequence, created_at)) => (id, sequence, created_at, true),
        None => {
            let existing: (Uuid, Uuid, i64, DateTime<Utc>) = sqlx::query_as(
                "SELECT id, chat_id, sequence, created_at FROM messages \
                 WHERE sender_device_id = $1 AND client_message_id = $2",
            )
            .bind(auth.device_id)
            .bind(req.client_message_id)
            .fetch_one(&mut *tx)
            .await?;
            if existing.1 != chat_id {
                return Err(AppError::Validation("client_message_id was already used for a different chat.".into()));
            }
            (existing.0, existing.2, existing.3, false)
        }
    };

    // Этап I: "delete for everyone" tombstones the ORIGINAL content message
    // server-side (deleted_at, already filtered by list_messages/sync) so a
    // brand-new device that joins later never sees it - the delete EVENT
    // row itself is never soft-deleted, so it keeps flowing through
    // history/sync/WS as the tombstone signal for devices that already
    // have the original cached locally. Idempotent (AND deleted_at IS NULL).
    let mut deletion_allocation=None;
    if is_new && req.kind == "delete" {
        if let Some(target_id) = req.target_message_id {
            let changed=sqlx::query("UPDATE messages SET deleted_at = now(),ciphertext=NULL WHERE id = $1 AND deleted_at IS NULL")
                .bind(target_id)
                .execute(&mut *tx)
                .await?;
            if changed.rows_affected()==0 {
                // A second device can delete from a stale screen. Return the
                // existing signal without persisting an unbounded stream of
                // fresh deletion IDs; roll back the provisional message first.
                tx.rollback().await?;
                let existing:Option<(Uuid,i64,DateTime<Utc>)>=sqlx::query_as("SELECT id,sequence,created_at FROM messages WHERE chat_id=$1 AND kind='delete' AND target_message_id=$2 ORDER BY sequence LIMIT 1")
                    .bind(chat_id).bind(target_id).fetch_optional(&state.db).await?;
                if let Some((id,sequence,created_at))=existing {
                    return Ok(Json(MessageResponse{id,chat_id,client_message_id:req.client_message_id,sender_id:auth.user_id,sequence,created_at,status:"sent"}));
                }
                return Err(AppError::Gone("Message is already deleted.".into()));
            }
            sqlx::query("DELETE FROM message_envelopes WHERE message_id=$1").bind(target_id).execute(&mut *tx).await?;
            deletion_allocation=sqlx::query_scalar::<_,i64>("SELECT bytes FROM message_payload_reservations WHERE message_id=$1")
                .bind(target_id).fetch_optional(&mut *tx).await?;
            crate::attachments::cleanup::retire_message_attachments(&mut tx,target_id,
                state.config.s3.presign_ttl_seconds).await?;
        }
    }

    // Associate every referenced attachment with this message. Idempotent:
    // a retried send for an already-associated attachment is a no-op
    // (message_id = $1 already matches), never an error. For a genuinely
    // new message, an attachment must be owned by this sender, already
    // fully uploaded, and not yet attached elsewhere - any mismatch rejects
    // the whole send (transaction rolls back, no partial association).
    if is_new && !req.attachment_ids.is_empty() {
        let result = sqlx::query(
            "UPDATE attachments SET status = 'attached', message_id = $1, attached_at = COALESCE(attached_at, now()) \
             WHERE id = ANY($2) AND uploader_user_id = $3 \
               AND NOT cleanup_pending AND ((status = 'uploaded' AND message_id IS NULL) OR message_id = $1)",
        )
        .bind(message_id)
        .bind(&req.attachment_ids)
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await?;
        if result.rows_affected() != req.attachment_ids.len() as u64 {
            return Err(AppError::Validation(
                "One or more attachments are invalid, not yet uploaded, or already attached to another message.".into(),
            ));
        }
    }

    // A retry must never observe a committed message without all its envelopes.
    if is_new {
        for (recipient_device_id, message_type, ciphertext) in &decoded {
            sqlx::query(
                "INSERT INTO message_envelopes (message_id, recipient_device_id, message_type, ciphertext) \
                 VALUES ($1, $2, $3, $4) ON CONFLICT (message_id, recipient_device_id) DO NOTHING",
            )
            .bind(message_id)
            .bind(recipient_device_id)
            .bind(message_type)
            .bind(ciphertext)
            .execute(&mut *tx)
            .await?;
        }
    }
    if is_new {
        let mut bytes=1024 + decoded.iter().map(|(_,_,body)| body.len() as i64 + 256).sum::<i64>();
        if let Some(original)=deletion_allocation {
            // A tombstone and its encrypted deletion signal share one logical
            // allocation. Retire original ciphertext; move its allocation to
            // the later event, so range-clear cannot leave uncharged payload.
            // A bounded <=2x cleanup allowance avoids blocking normal deletion
            // at a full quota. Larger opaque events require ordinary admission.
            if bytes<=original.saturating_mul(2) {bytes=original;}
            sqlx::query("UPDATE message_payload_reservations SET bytes=0 WHERE message_id=$1")
                .bind(req.target_message_id).execute(&mut *tx).await?;
        }
        crate::database_payload::reserve_message(&mut tx,message_id,auth.user_id,bytes).await?;
    }
    tx.commit().await?;

    if is_new {
        for (recipient_device_id, message_type, ciphertext) in decoded {
            let dto = MessageDto {
                id: message_id,
                chat_id,
                client_message_id: req.client_message_id,
                sender_id: auth.user_id,
                sender_device_id: auth.device_id,
                sequence,
                created_at,
                message_type,
                ciphertext: STANDARD.encode(ciphertext),
                kind: req.kind.clone(),
                target_message_id: req.target_message_id,
            };
            // A device belonging to the sender is the sender's own other
            // device (multi-device echo); anything else is a real recipient.
            let event = if device_owner.get(&recipient_device_id) == Some(&auth.user_id) {
                ServerMessage::MessageSent { message: dto }
            } else {
                ServerMessage::MessageNew { message: dto }
            };
            websocket::notify_device(&state, recipient_device_id, &event).await;
        }

        // K.6/K.10: privacy-safe FCM wake for every recipient user's eligible
        // devices (never the sender's). Payload carries only routing metadata -
        // chat_id / message_id / sequence - so FCM/Google never sees plaintext.
        let mut recipient_users: Vec<Uuid> = device_owner
            .values()
            .copied()
            .filter(|user_id| *user_id != auth.user_id)
            .collect();
        recipient_users.sort_unstable();
        recipient_users.dedup();

        let recipient_users = {
            let mut enabled_recipients = Vec::with_capacity(recipient_users.len());
            if !recipient_users.is_empty() {
                let muted_or_blocked: Vec<(Uuid,)> = sqlx::query_as(
                    "SELECT user_id FROM chat_preferences \
                     WHERE chat_id = $1 AND user_id = ANY($2) \
                     AND (is_blocked = TRUE OR (is_muted = TRUE AND (muted_until IS NULL OR muted_until > now())))",
                )
                .bind(chat_id)
                .bind(&recipient_users)
                .fetch_all(&state.db)
                .await?;
                let muted_or_blocked: HashSet<Uuid> = muted_or_blocked.into_iter().map(|(user_id,)| user_id).collect();
                for user_id in recipient_users {
                    if !muted_or_blocked.contains(&user_id) {
                        enabled_recipients.push(user_id);
                    }
                }
            }
            enabled_recipients
        };

        state
            .push
            .enqueue_for_users(&state.db, &recipient_users, None, &PushEvent::new_message(chat_id, message_id, sequence))
            .await;

        // Never log ciphertext - ids and sequence only.
        tracing::info!(chat_id = %chat_id, sender_id = %auth.user_id, sequence, "message sent");
    }

    Ok(Json(MessageResponse {
        id: message_id,
        chat_id,
        client_message_id: req.client_message_id,
        sender_id: auth.user_id,
        sequence,
        created_at,
        status: "sent",
    }))
}

/// `GET /chats/{chat_id}/messages?before_sequence=&limit=` - keyset
/// pagination over the monotonic `messages.sequence` (global across all
/// chats, not per-chat - see the Этап A/E schema notes; this is still fully
/// correct and race-safe for per-chat ordering and pagination). Returns only
/// the envelope addressed to the *requester's own device*
/// (`auth.device_id`) - a device that never received an envelope for a given
/// message (e.g. it didn't exist yet when the message was sent) correctly
/// can't see it here either, matching real E2E semantics; the sender's own
/// sent messages live in its local cache from the moment it sent them; it
/// never needs to re-fetch its own plaintext from the server. Returns
/// newest-first (`sequence DESC`); the Android client reverses for display.
pub async fn list_messages(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(chat_id): Path<Uuid>,
    Query(query): Query<ListMessagesQuery>,
) -> Result<Json<MessageListResponse>, AppError> {
    require_membership(&state, chat_id, auth.user_id).await?;
    let visible_since = crate::auth::history::visible_since(&state.db, &auth).await?;

    let limit = query.limit.unwrap_or(LIST_LIMIT_DEFAULT).clamp(1, LIST_LIMIT_MAX);

    let rows: Vec<EnvelopeRow> = sqlx::query_as(
        "SELECT m.id, m.chat_id, m.sender_id, m.sender_device_id, m.client_message_id, m.sequence, m.created_at, \
                me.message_type, me.ciphertext, m.kind, m.target_message_id \
         FROM messages m \
         JOIN message_envelopes me ON me.message_id = m.id AND me.recipient_device_id = $1 \
         WHERE m.chat_id = $2 AND m.deleted_at IS NULL \
           AND m.created_at >= $5 AND NOT user_device_chat_blocked($1,m.chat_id) \
           AND ($3::bigint IS NULL OR m.sequence < $3) \
         ORDER BY m.sequence DESC \
         LIMIT $4",
    )
    .bind(auth.device_id)
    .bind(chat_id)
    .bind(query.before_sequence)
    .bind(limit + 1)
    .bind(visible_since)
    .fetch_all(&state.db)
    .await?;

    let has_more = rows.len() as i64 > limit;
    let page: Vec<EnvelopeRow> = rows.into_iter().take(limit as usize).collect();
    let next_before_sequence = if has_more { page.last().map(|row| row.sequence) } else { None };

    // Fetching history implies the client now has these messages - mark
    // delivered for the requester's own receipts.
    let message_ids: Vec<Uuid> = page.iter().map(|row| row.id).collect();
    if !message_ids.is_empty() {
        sqlx::query(
            "UPDATE message_receipts SET delivered_at = COALESCE(delivered_at, now()) \
             WHERE user_id = $1 AND message_id = ANY($2)",
        )
        .bind(auth.user_id)
        .bind(&message_ids)
        .execute(&state.db)
        .await?;
    }

    let messages: Vec<MessageDto> = page.into_iter().map(Into::into).collect();

    Ok(Json(MessageListResponse { messages, has_more, next_before_sequence }))
}

/// `POST /chats/{chat_id}/read` - marks every message in the chat up to
/// `sequence` as read (and implicitly delivered) for the requester only.
/// Forward-only: a `sequence` lower than what's already recorded is a no-op,
/// and the response always reports the actual (never-decreasing) watermark.
pub async fn mark_read(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(chat_id): Path<Uuid>,
    Json(req): Json<ReadReceiptRequest>,
) -> Result<Json<ReadReceiptResponse>, AppError> {
    require_membership(&state, chat_id, auth.user_id).await?;
    crate::chats::policy::require_device_contact_allowed(&state.db,chat_id,auth.device_id).await?;

    let mut tx = state.db.begin().await?;
    crate::chats::policy::lock_contact_allowed(&mut tx,chat_id,auth.device_id).await?;
    crate::auth::middleware::lock_active_session(&mut tx,&auth).await?;

    let (current_max,): (Option<i64>,) = sqlx::query_as(
        "SELECT MAX(mr.sequence) FROM message_receipts mr \
         JOIN messages m ON m.id = mr.message_id \
         WHERE mr.user_id = $1 AND m.chat_id = $2 AND mr.read_at IS NOT NULL",
    )
    .bind(auth.user_id)
    .bind(chat_id)
    .fetch_one(&mut *tx)
    .await?;
    let current_max = current_max.unwrap_or(0);

    let effective_sequence = current_max.max(req.sequence);

    if req.sequence > current_max {
        let newly_read = sqlx::query(
            "UPDATE message_receipts SET read_at = now(), delivered_at = COALESCE(delivered_at, now()) \
               WHERE user_id = $1 AND read_at IS NULL \
               AND message_id IN (SELECT id FROM messages WHERE chat_id = $2 AND sequence <= $3)",
        )
        .bind(auth.user_id)
        .bind(chat_id)
        .bind(req.sequence)
        .execute(&mut *tx)
        .await?.rows_affected();

        // Queue retries and process-restart cursor replays are not a fresh
        // account read. Only advancing own unread can expose a cumulative
        // receipt after re-enabling; same/lower hidden cursors stay private.
        if newly_read > 0 { sqlx::query(
            "UPDATE message_receipts SET exposed_read_at = now() \
             WHERE user_id=$1 AND read_at IS NOT NULL AND exposed_read_at IS NULL \
               AND message_id IN (SELECT id FROM messages WHERE chat_id=$2 AND sequence<=$3) \
               AND COALESCE((SELECT send_read_receipts FROM user_privacy WHERE user_id=$1),true)",
        ).bind(auth.user_id).bind(chat_id).bind(req.sequence).execute(&mut *tx).await?; }
    }

    tx.commit().await?;

    notify_receipt_update(&state, chat_id, auth.user_id).await;

    Ok(Json(ReadReceiptResponse { sequence: effective_sequence }))
}

/// `POST /chats/{chat_id}/delivered` - the same forward-only watermark
/// pattern as `mark_read`, but for `delivered_at` only. Distinct from the
/// implicit "mark delivered on fetch" side effect in `list_messages`/`sync`
/// (which reflects "the opaque envelope reached this device over HTTP"):
/// this is the client's own explicit signal that it successfully decrypted
/// and durably persisted the message locally - a stronger, ingestion-pipeline-
/// driven notion of "delivered" than merely having downloaded the bytes.
pub async fn mark_delivered(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(chat_id): Path<Uuid>,
    Json(req): Json<ReadReceiptRequest>,
) -> Result<Json<ReadReceiptResponse>, AppError> {
    require_membership(&state, chat_id, auth.user_id).await?;
    crate::chats::policy::require_device_contact_allowed(&state.db,chat_id,auth.device_id).await?;

    let mut tx = state.db.begin().await?;
    crate::chats::policy::lock_contact_allowed(&mut tx,chat_id,auth.device_id).await?;
    crate::auth::middleware::lock_active_session(&mut tx,&auth).await?;

    let (current_max,): (Option<i64>,) = sqlx::query_as(
        "SELECT MAX(mr.sequence) FROM message_receipts mr \
         JOIN messages m ON m.id = mr.message_id \
         WHERE mr.user_id = $1 AND m.chat_id = $2 AND mr.delivered_at IS NOT NULL",
    )
    .bind(auth.user_id)
    .bind(chat_id)
    .fetch_one(&mut *tx)
    .await?;
    let current_max = current_max.unwrap_or(0);

    let effective_sequence = current_max.max(req.sequence);

    if req.sequence > current_max {
        sqlx::query(
            "UPDATE message_receipts SET delivered_at = now() \
               WHERE user_id = $1 AND delivered_at IS NULL \
               AND message_id IN (SELECT id FROM messages WHERE chat_id = $2 AND sequence <= $3)",
        )
        .bind(auth.user_id)
        .bind(chat_id)
        .bind(req.sequence)
        .execute(&mut *tx)
        .await?;
    }

    tx.commit().await?;

    notify_receipt_update(&state, chat_id, auth.user_id).await;

    Ok(Json(ReadReceiptResponse { sequence: effective_sequence }))
}

/// Looks up `user_id`'s current delivered/read watermarks for `chat_id` and
/// pushes a `receipt.updated` event to every other active member's devices.
/// Fire-and-forget by design (logged, never fails the caller's request) -
/// this is the WS *fast path* only; a missed event is always recoverable via
/// the reconciliation fields on `GET /chats`/`GET /chats/{id}`.
async fn notify_receipt_update(state: &AppState, chat_id: Uuid, user_id: Uuid) {
    let watermarks: Result<(Option<i64>, Option<i64>), AppError> = sqlx::query_as(
        "SELECT MAX(mr.sequence) FILTER (WHERE mr.delivered_at IS NOT NULL), \
                MAX(mr.sequence) FILTER (WHERE mr.exposed_read_at IS NOT NULL) \
         FROM message_receipts mr JOIN messages m ON m.id = mr.message_id \
         WHERE mr.user_id = $1 AND m.chat_id = $2",
    )
    .bind(user_id)
    .bind(chat_id)
    .fetch_one(&state.db)
    .await
    .map_err(AppError::from);

    let (delivered_sequence, read_sequence) = match watermarks {
        Ok((delivered, read)) => (delivered.unwrap_or(0), read.unwrap_or(0)),
        Err(err) => {
            tracing::error!(?err, "failed to compute receipt watermarks for receipt.updated push");
            return;
        }
    };

    let other_members: Vec<(Uuid,)> = match sqlx::query_as(
        "SELECT user_id FROM chat_members WHERE chat_id = $1 AND user_id <> $2 AND left_at IS NULL",
    )
    .bind(chat_id)
    .bind(user_id)
    .fetch_all(&state.db)
    .await
    {
        Ok(rows) => rows,
        Err(err) => {
            tracing::error!(%err, "failed to look up chat members for receipt.updated push");
            return;
        }
    };

    let event = ServerMessage::ReceiptUpdated { chat_id, user_id, delivered_sequence, read_sequence };
    for (other_user_id,) in other_members {
        websocket::notify_user(&state, other_user_id, &event).await;
    }
}


#[cfg(test)]
mod ciphertext_shape_tests {
    use super::*;

    #[test]
    fn rejects_empty_and_malformed_ciphertext_before_persistence() {
        assert!(decode_message_ciphertext("").is_err());
        for value in ["not base64", "====", "?", "AA"] {
            assert!(decode_message_ciphertext(value).is_err());
        }
    }

    #[test]
    fn preserves_existing_exact_byte_ceiling() {
        let max = crate::database_payload::MAX_CIPHERTEXT_BYTES;
        for size in [1, 32, max] {
            let bytes = vec![7_u8; size];
            assert_eq!(decode_message_ciphertext(&STANDARD.encode(&bytes)).unwrap(), bytes);
        }
        assert!(decode_message_ciphertext(&STANDARD.encode(vec![7_u8; max + 1])).is_err());
    }
}

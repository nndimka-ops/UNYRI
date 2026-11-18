use std::collections::{HashMap, HashSet};

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
async fn require_target(tx: &mut sqlx::Transaction<'_, sqlx::Postgres>, chat_id: Uuid, target_message_id: Uuid) -> Result<TargetInfo, AppError> {
    let row: Option<(Uuid, Uuid, String, Option<DateTime<Utc>>)> = sqlx::query_as(
        "SELECT chat_id, sender_id, kind, deleted_at FROM messages WHERE id = $1",
    )
    .bind(target_message_id)
    .fetch_optional(&mut **tx)
    .await?;
    let (target_chat_id, sender_id, kind, deleted_at) =
        row.ok_or_else(|| AppError::NotFound("Message not found.".into()))?;
    if target_chat_id != chat_id {
        return Err(AppError::NotFound("Message not found.".into()));
    }
    Ok(TargetInfo { sender_id, kind, deleted_at })
}

/// Users whose devices get the new-message alert: every recipient but the
/// sender. In an album (`batch_alerts` is Some) only users with a device that
/// claimed the batch alert for this message are alerted; see
/// `album_quiet_devices` for the devices an earlier item already alerted.
fn alert_recipients(
    device_owner: &HashMap<Uuid, Uuid>,
    sender: Uuid,
    batch_alerts: Option<&HashMap<Uuid, bool>>,
) -> Vec<Uuid> {
    let mut users: Vec<Uuid> = device_owner.values().copied().filter(|user| *user != sender).collect();
    if let Some(alerts) = batch_alerts {
        users.retain(|user| alerts.iter().any(|(device, alert)| *alert && device_owner.get(device) == Some(user)));
    }
    users.sort_unstable();
    users.dedup();
    users
}

/// Recipient devices that an earlier message of the album already alerted
/// (their batch claim existed). Each gets a quiet alert for this message: the
/// notification extension then acknowledges every item, so the sender's album
/// does not stay at one tick, and the album still rings only once per device.
fn album_quiet_devices(batch_alerts: Option<&HashMap<Uuid, bool>>) -> Vec<Uuid> {
    let mut devices: Vec<Uuid> = batch_alerts
        .into_iter()
        .flatten()
        .filter(|(_, alerted_now)| !**alerted_now)
        .map(|(device, _)| *device)
        .collect();
    devices.sort_unstable();
    devices
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

    // Resolve a committed send before interpreting today's roster or lifecycle
    // target. Serialize with sends by this account, and recheck authorization.
    let mut tx = state.db.begin().await?;
    crate::chats::policy::lock_contact_allowed(&mut tx, chat_id, auth.device_id).await?;
    crate::auth::middleware::lock_active_user(&mut tx, &auth).await?;
    let member: Option<Uuid> = sqlx::query_scalar(
        "SELECT user_id FROM chat_members WHERE chat_id=$1 AND user_id=$2 AND left_at IS NULL FOR SHARE",
    ).bind(chat_id).bind(auth.user_id).fetch_optional(&mut *tx).await?;
    if member.is_none() { return Err(AppError::NotFound("Chat not found.".into())); }
    let existing: Option<(Uuid, Uuid, i64, DateTime<Utc>)> = sqlx::query_as(
        "SELECT id,chat_id,sequence,created_at FROM messages WHERE sender_device_id=$1 AND client_message_id=$2",
    ).bind(auth.device_id).bind(req.client_message_id).fetch_optional(&mut *tx).await?;
    if let Some((id, existing_chat, sequence, created_at)) = existing {
        if existing_chat != chat_id {
            return Err(AppError::Validation("client_message_id was already used for a different chat.".into()));
        }
        tx.commit().await?;
        return Ok(Json(MessageResponse { id, chat_id, client_message_id: req.client_message_id,
            sender_id: auth.user_id, sequence, created_at, status: "sent" }));
    }

    // Saved Messages is the owner's own chat: on a single-device account there
    // is no other device to encrypt for, so zero envelopes is a valid send (the
    // sender keeps the plaintext locally; the message is still persisted
    // server-side for the saved-chat history). Every other chat type always has
    // at least one other participant, so zero envelopes stays invalid there.
    if req.envelopes.is_empty() {
        let is_saved_chat: bool = sqlx::query_scalar("SELECT (type = 'saved') FROM chats WHERE id = $1")
            .bind(chat_id)
            .fetch_one(&mut *tx)
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
        let target = require_target(&mut tx, chat_id, target_id).await?;
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
    .fetch_all(&mut *tx)
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
    let own_other_devices: Vec<(Uuid, bool)> = sqlx::query_as(
        "SELECT d.id, user_device_chat_blocked(d.id,$3) FROM devices d \
         JOIN device_identity_keys k ON k.device_id = d.id \
         WHERE d.user_id = $1 AND d.revoked_at IS NULL AND d.id <> $2",
    )
    .bind(auth.user_id)
    .bind(auth.device_id)
    .bind(chat_id)
    .fetch_all(&mut *tx)
    .await?;
    req.validate_own_device_coverage(&own_other_devices, &recipient_device_ids)?;

    // Message insert + attachment association happen in one transaction -
    // an attachment must never end up "attached" to a message that itself
    // failed to commit (see Этап H п.13/44).
    let denied: Vec<Uuid> = sqlx::query_scalar("SELECT id FROM devices WHERE id=ANY($1) AND \
        (user_device_chat_blocked(id,$2) OR user_device_blocked(id,$3))")
        .bind(&recipient_device_ids).bind(chat_id).bind(auth.user_id).fetch_all(&mut *tx).await?;
    let had_peer = device_owner.values().any(|id| *id != auth.user_id);
    decoded.retain(|(id,_,_)| !denied.contains(id));
    device_owner.retain(|id,_| !denied.contains(id));
    if had_peer && !device_owner.values().any(|id| *id != auth.user_id) {
        return Err(AppError::Conflict("Communication is unavailable for this chat.".into()));
    }

    // A successful direct send must reach at least one authenticated
    // peer. Check peer coverage in the SAME transaction before
    // claiming attachments or committing the message. The existing device
    // membership and block filters above already exclude invalid recipients.
    let (is_direct_chat, direct_peer): (bool, Option<Uuid>) = sqlx::query_as(
        "SELECT type = 'direct', CASE WHEN user_a_id = $2 THEN user_b_id ELSE user_a_id END FROM chats WHERE id = $1",
    )
    .bind(chat_id).bind(auth.user_id).fetch_one(&mut *tx).await?;
    if is_direct_chat {
        let has_peer = device_owner.values().any(|owner| *owner != auth.user_id);
        if !has_peer {
            return Err(AppError::RecipientNotReady);
        }
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
    // Batch claims commit with the envelopes: the first message of an album to
    // reach a recipient device alerts it, later ones get a quiet alert.
    let mut batch_alerts: HashMap<Uuid, bool> = HashMap::new();
    if is_new {
        for (recipient_device_id, message_type, ciphertext) in &decoded {
            let mut alert = None;
            if let Some(batch_id) = req.notification_batch_id {
                if device_owner.get(recipient_device_id) != Some(&auth.user_id) {
                    let claimed: Option<Uuid> = sqlx::query_scalar(
                        "INSERT INTO message_notification_batch_claims (sender_id,chat_id,batch_id,recipient_device_id,first_message_id) \
                         VALUES ($1,$2,$3,$4,$5) ON CONFLICT DO NOTHING RETURNING first_message_id",
                    )
                    .bind(auth.user_id).bind(chat_id).bind(batch_id).bind(recipient_device_id).bind(message_id)
                    .fetch_optional(&mut *tx).await?;
                    alert = Some(claimed.is_some());
                    batch_alerts.insert(*recipient_device_id, claimed.is_some());
                }
            }
            sqlx::query(
                "INSERT INTO message_envelopes (message_id, recipient_device_id, message_type, ciphertext, notification_batch_alert) \
                 VALUES ($1, $2, $3, $4, $5) ON CONFLICT (message_id, recipient_device_id) DO NOTHING",
            )
            .bind(message_id)
            .bind(recipient_device_id)
            .bind(message_type)
            .bind(ciphertext)
            .bind(alert)
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

    let mut contact_added: Option<Uuid> = None;
    if is_new && req.kind == "content" && is_direct_chat {
        if let Some(peer) = direct_peer.filter(|peer| *peer != auth.user_id) {
            // First message to someone makes them a contact, once per pair (see 0066).
            let inserted = sqlx::query("INSERT INTO user_contacts (owner_id,contact_id,source) VALUES ($1,$2,'chat') ON CONFLICT DO NOTHING")
                .bind(auth.user_id).bind(peer).execute(&mut *tx).await?.rows_affected() > 0;
            if inserted { contact_added = Some(peer); }
        }
    }

    // K.6/K.10: privacy-safe wake for every recipient user's eligible devices
    // (never the sender's); the payload carries only chat_id/message_id/sequence.
    // Only new content alerts. Edits, deletes and reactions are delivered over the
    // socket/sync; as alerts they rang "New message" and, sharing the per-chat
    // collapse id, replaced the notification of the real message.
    // Queued in this transaction: a failed enqueue fails the send and the
    // client's retry runs it again, while a duplicate (is_new=false) queues nothing.
    // Muted recipients get a background wake instead of an alert (decided in
    // the enqueue, APNs only).
    let mut push_queued = 0;
    if is_new && req.kind == "content" {
        let batch = req.notification_batch_id.map(|_| &batch_alerts);
        let recipients = alert_recipients(&device_owner, auth.user_id, batch);
        let quiet = album_quiet_devices(batch);
        push_queued = state
            .push
            .enqueue_message_transactional(&mut tx, chat_id, &recipients, &quiet, &PushEvent::new_message(chat_id, message_id, sequence))
            .await?;
    }
    tx.commit().await?;
    if push_queued > 0 {
        state.push.wake_worker();
    }
    if let Some(peer) = contact_added {
        // Every own device, the sending one included: it did not ask for this change.
        crate::users::contacts::notify_changed(&state, auth.user_id, None, peer, true).await;
    }

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
                notification_batch_alert: batch_alerts.get(&recipient_device_id).copied(),
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

    // Bounded by bytes as well as rows (audit server-032); the first row is
    // always included so a large envelope cannot stall pagination.
    let rows = sqlx::query(
        "SELECT * FROM ( \
           SELECT lim.*, SUM(lim.bytes) OVER (ORDER BY lim.sequence DESC) AS running, \
                  ROW_NUMBER() OVER (ORDER BY lim.sequence DESC) AS rn, COUNT(*) OVER () AS candidates \
           FROM ( \
             SELECT m.id, m.chat_id, m.sender_id, m.sender_device_id, m.client_message_id, m.sequence, m.created_at, \
                    me.message_type, me.ciphertext, m.kind, m.target_message_id, me.notification_batch_alert, \
                    octet_length(me.ciphertext)::bigint AS bytes \
             FROM messages m \
             JOIN message_envelopes me ON me.message_id = m.id AND me.recipient_device_id = $1 \
             WHERE m.chat_id = $2 AND m.deleted_at IS NULL \
               AND m.created_at >= $5 AND NOT user_device_chat_blocked($1,m.chat_id) \
               AND ($3::bigint IS NULL OR m.sequence < $3) \
             ORDER BY m.sequence DESC \
             LIMIT $4) lim) page \
         WHERE rn = 1 OR running <= $6 \
         ORDER BY sequence DESC",
    )
    .bind(auth.device_id)
    .bind(chat_id)
    .bind(query.before_sequence)
    .bind(limit + 1)
    .bind(visible_since)
    .bind(crate::sync::handlers::SYNC_BYTES_MAX)
    .fetch_all(&state.db)
    .await?;

    let candidates: i64 = match rows.first() { Some(r) => sqlx::Row::try_get(r, "candidates")?, None => 0 };
    let mut page: Vec<EnvelopeRow> = Vec::with_capacity(rows.len());
    for r in rows.iter().take(limit as usize) { page.push(<EnvelopeRow as sqlx::FromRow<_>>::from_row(r)?); }
    let has_more = candidates > page.len() as i64;
    let next_before_sequence = if has_more { page.last().map(|row| row.sequence) } else { None };

    // Fetching history implies the client now has these messages - mark
    // delivered for the requester's own receipts.
    let fetched: Vec<(Uuid, Uuid)> = page.iter().map(|row| (row.id, row.chat_id)).collect();
    mark_fetched_delivered(&state, &auth, &fetched).await?;

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

    // Rows changed, split by who can see the change: peers see delivery and
    // exposed reads, the reader's own devices see every read.
    let (mut newly_read, mut peer_visible) = (0, 0);
    let mut peer_read_changed = false;
    if req.sequence > current_max {
        // A read implies delivery. Counted on its own (same now(), same rows
        // as the former COALESCE) because peers see delivery even while read
        // receipts are off.
        let newly_delivered = sqlx::query(
            "UPDATE message_receipts SET delivered_at = now() \
               WHERE user_id = $1 AND delivered_at IS NULL \
               AND message_id IN (SELECT id FROM messages WHERE chat_id = $2 AND sequence <= $3)",
        )
        .bind(auth.user_id)
        .bind(chat_id)
        .bind(req.sequence)
        .execute(&mut *tx)
        .await?.rows_affected();
        newly_read = sqlx::query(
            "UPDATE message_receipts SET read_at = now() \
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
        let mut newly_exposed = 0;
        if newly_read > 0 { newly_exposed = sqlx::query(
            "UPDATE message_receipts SET exposed_read_at = now() \
             WHERE user_id=$1 AND read_at IS NOT NULL AND exposed_read_at IS NULL \
               AND message_id IN (SELECT id FROM messages WHERE chat_id=$2 AND sequence<=$3) \
               AND COALESCE((SELECT send_read_receipts FROM user_privacy WHERE user_id=$1),true)",
        ).bind(auth.user_id).bind(chat_id).bind(req.sequence).execute(&mut *tx).await?.rows_affected(); }
        peer_visible = newly_delivered + newly_exposed;
        peer_read_changed = newly_exposed > 0;
    }

    tx.commit().await?;

    // Launch flushes and queue replays repeat cursors the server already has;
    // they change no row and must not send anyone a no-op event. A hidden read
    // that moves nothing peers can see is not announced to them either, so its
    // timing does not leak.
    if peer_visible > 0 || newly_read > 0 {
        notify_receipt_update(&state, chat_id, auth.user_id, peer_visible > 0, peer_read_changed, (newly_read > 0).then_some(auth.device_id)).await;
    }

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

    let mut newly_delivered = 0;
    if req.sequence > current_max {
        newly_delivered = sqlx::query(
            "UPDATE message_receipts SET delivered_at = now() \
               WHERE user_id = $1 AND delivered_at IS NULL \
               AND message_id IN (SELECT id FROM messages WHERE chat_id = $2 AND sequence <= $3)",
        )
        .bind(auth.user_id)
        .bind(chat_id)
        .bind(req.sequence)
        .execute(&mut *tx)
        .await?
        .rows_affected();
    }

    tx.commit().await?;

    // Same as reads: an unchanged watermark is not announced.
    if newly_delivered > 0 {
        notify_receipt_update(&state, chat_id, auth.user_id, true, false, None).await;
    }

    Ok(Json(ReadReceiptResponse { sequence: effective_sequence }))
}

/// Marks `user_id`'s messages in `chat_id` delivered through `sequence` (cumulative,
/// like `POST /chats/{id}/delivered`) and notifies the other members. Used by the
/// notification-extension delivery path, which has no session.
pub async fn mark_delivered_through(state: &AppState, user_id: Uuid, chat_id: Uuid, sequence: i64) -> Result<(), AppError> {
    let changed = sqlx::query(
        "UPDATE message_receipts SET delivered_at = now()            WHERE user_id = $1 AND delivered_at IS NULL            AND message_id IN (SELECT id FROM messages WHERE chat_id = $2 AND sequence <= $3)",
    )
    .bind(user_id)
    .bind(chat_id)
    .bind(sequence)
    .execute(&state.db)
    .await?;
    if changed.rows_affected() > 0 { notify_receipt_update(state, chat_id, user_id, true, false, None).await; }
    Ok(())
}

/// Marks messages a device just fetched (GET /sync, GET /chats/{id}/messages)
/// delivered for its user, and announces every chat whose delivered watermark
/// moved. The announcement belongs here, where delivery is recorded: the
/// client's later POST /delivered for the same sequence changes nothing and is
/// not announced, so without it the sender would stay at one tick until it
/// reloads its chats. Chats the device may not contact are recorded as before
/// but not announced, as POST /delivered refuses them.
pub(crate) async fn mark_fetched_delivered(state: &AppState, auth: &AuthUser, fetched: &[(Uuid, Uuid)]) -> Result<(), AppError> {
    if fetched.is_empty() {
        return Ok(());
    }
    let ids: Vec<Uuid> = fetched.iter().map(|(message, _)| *message).collect();
    let changed: Vec<Uuid> = sqlx::query_scalar(
        "UPDATE message_receipts SET delivered_at = now() \
         WHERE user_id = $1 AND message_id = ANY($2) AND delivered_at IS NULL \
         RETURNING message_id",
    )
    .bind(auth.user_id)
    .bind(&ids)
    .fetch_all(&state.db)
    .await?;
    for chat_id in chats_with_new_deliveries(fetched, &changed) {
        if crate::chats::policy::require_device_contact_allowed(&state.db, chat_id, auth.device_id).await.is_ok() {
            notify_receipt_update(state, chat_id, auth.user_id, true, false, None).await;
        }
    }
    Ok(())
}

/// The chats (each once, sorted) of the fetched `(message, chat)` pairs whose
/// message is in `changed`.
fn chats_with_new_deliveries(fetched: &[(Uuid, Uuid)], changed: &[Uuid]) -> Vec<Uuid> {
    let changed: HashSet<&Uuid> = changed.iter().collect();
    let mut chats: Vec<Uuid> = fetched
        .iter()
        .filter(|(message, _)| changed.contains(message))
        .map(|(_, chat)| *chat)
        .collect();
    chats.sort_unstable();
    chats.dedup();
    chats
}

/// Announces `user_id`'s receipt change in `chat_id` as `receipt.updated`.
/// With `peers`, every other active member gets the delivered watermark and
/// the read watermark peers may see (exposed_read_at, held back while read
/// receipts are off). When a read moved on `reader_device`, the reader's other
/// devices get the same event shape with the account's real read watermark
/// (read_at, as in GET /chats own_read_sequence); clients apply it as "read on
/// another device" because `user_id` is their own account.
/// Fire-and-forget by design (logged, never fails the caller's request) -
/// this is the WS *fast path* only; a missed event is always recoverable via
/// the reconciliation fields on `GET /chats`/`GET /chats/{id}`.
/// Hidden-online non-contacts receive only newly exposed reads, with delivery
/// redacted. Delivery-only changes send them no event at all.
async fn notify_receipt_update(state: &AppState, chat_id: Uuid, user_id: Uuid, peers: bool, peer_read_changed: bool, reader_device: Option<Uuid>) {
    let watermarks: Result<(Option<i64>, Option<i64>, Option<i64>), AppError> = sqlx::query_as(
        "SELECT MAX(mr.sequence) FILTER (WHERE mr.delivered_at IS NOT NULL), \
                MAX(mr.sequence) FILTER (WHERE mr.exposed_read_at IS NOT NULL), \
                MAX(mr.sequence) FILTER (WHERE mr.read_at IS NOT NULL) \
         FROM message_receipts mr JOIN messages m ON m.id = mr.message_id \
         WHERE mr.user_id = $1 AND m.chat_id = $2",
    )
    .bind(user_id)
    .bind(chat_id)
    .fetch_one(&state.db)
    .await
    .map_err(AppError::from);

    let (peer_event, own_event, read_event) = match watermarks {
        Ok((delivered, exposed_read, read)) => {
            let (peer, own) = receipt_events(
                chat_id,
                user_id,
                delivered.unwrap_or(0),
                exposed_read.unwrap_or(0),
                read.unwrap_or(0),
            );
            (peer, own, ServerMessage::ReceiptUpdated { chat_id, user_id, delivered_sequence: 0, read_sequence: exposed_read.unwrap_or(0) })
        },
        Err(err) => {
            tracing::error!(?err, "failed to compute receipt watermarks for receipt.updated push");
            return;
        }
    };

    if peers {
        match sqlx::query_as::<_, (Uuid, bool)>(
            "SELECT user_id,user_delivery_visible($2,user_id) FROM chat_members WHERE chat_id = $1 AND user_id <> $2 AND left_at IS NULL",
        )
        .bind(chat_id)
        .bind(user_id)
        .fetch_all(&state.db)
        .await
        {
            Ok(other_members) => {
                for (other_user_id, delivery_visible) in other_members {
                    if delivery_visible {
                        websocket::notify_user(state, other_user_id, &peer_event).await;
                    } else if peer_read_changed {
                        websocket::notify_user(state, other_user_id, &read_event).await;
                    }
                }
            }
            // Own devices below are still told.
            Err(err) => tracing::error!(%err, "failed to look up chat members for receipt.updated push"),
        }
    }
    // The device that read already knows; only its siblings need the event.
    if let Some(device) = reader_device {
        websocket::notify_user_except(state, user_id, device, &own_event).await;
    }
}

/// The event for peers and the one for the reader's own devices. They differ
/// only in `read_sequence`: turning read receipts off hides reads from others,
/// never from the account itself.
fn receipt_events(
    chat_id: Uuid,
    user_id: Uuid,
    delivered_sequence: i64,
    exposed_read_sequence: i64,
    read_sequence: i64,
) -> (ServerMessage, ServerMessage) {
    (
        ServerMessage::ReceiptUpdated { chat_id, user_id, delivered_sequence, read_sequence: exposed_read_sequence },
        ServerMessage::ReceiptUpdated { chat_id, user_id, delivered_sequence, read_sequence },
    )
}

#[cfg(test)]
mod receipt_event_tests {
    use super::*;

    #[test]
    fn own_devices_get_the_real_read_even_when_receipts_are_hidden() {
        let (chat, user) = (Uuid::new_v4(), Uuid::new_v4());
        let (peer, own) = receipt_events(chat, user, 12, 0, 9);
        let peer = serde_json::to_value(&peer).unwrap();
        let own = serde_json::to_value(&own).unwrap();
        assert_eq!(peer["read_sequence"], 0, "peers never see a hidden read");
        assert_eq!(own["read_sequence"], 9);
        assert_eq!(peer["delivered_sequence"], 12);
        assert_eq!(own["delivered_sequence"], 12);
    }

    #[test]
    fn a_fetch_announces_each_chat_whose_delivery_moved_once() {
        let (chat_a, chat_b, chat_c) = (Uuid::new_v4(), Uuid::new_v4(), Uuid::new_v4());
        let (m1, m2, m3, m4) = (Uuid::new_v4(), Uuid::new_v4(), Uuid::new_v4(), Uuid::new_v4());
        let fetched = [(m1, chat_a), (m2, chat_a), (m3, chat_b), (m4, chat_c)];
        // m3 was already delivered (an earlier fetch); chat_b has nothing new.
        let mut expected = vec![chat_a, chat_c];
        expected.sort_unstable();
        assert_eq!(chats_with_new_deliveries(&fetched, &[m2, m1, m4]), expected);
        assert!(chats_with_new_deliveries(&fetched, &[]).is_empty(), "a repeated fetch announces nothing");
        assert!(chats_with_new_deliveries(&[], &[m1]).is_empty());
    }

    #[test]
    fn own_device_event_keeps_the_shape_clients_decode() {
        // iOS (RealtimeModels ReceiptEnvelope) and Android decode exactly these
        // keys and pick the own-account branch by user_id.
        let (chat, user) = (Uuid::new_v4(), Uuid::new_v4());
        let (_, own) = receipt_events(chat, user, 3, 3, 3);
        let value = serde_json::to_value(&own).unwrap();
        let mut keys: Vec<&str> = value.as_object().unwrap().keys().map(String::as_str).collect();
        keys.sort_unstable();
        assert_eq!(keys, ["chat_id", "delivered_sequence", "read_sequence", "type", "user_id"]);
        assert_eq!(value["type"], "receipt.updated");
        assert_eq!(value["chat_id"], chat.to_string());
        assert_eq!(value["user_id"], user.to_string());
    }
}

#[cfg(test)]
mod alert_recipient_tests {
    use super::*;

    #[test]
    fn excludes_the_sender_and_lists_each_user_once() {
        let (sender, bob, carol) = (Uuid::new_v4(), Uuid::new_v4(), Uuid::new_v4());
        let owners: HashMap<Uuid, Uuid> = [
            (Uuid::new_v4(), sender),
            (Uuid::new_v4(), bob),
            (Uuid::new_v4(), bob),
            (Uuid::new_v4(), carol),
        ]
        .into_iter()
        .collect();
        let mut expected = vec![bob, carol];
        expected.sort_unstable();
        assert_eq!(alert_recipients(&owners, sender, None), expected);
    }

    #[test]
    fn album_wakes_only_users_whose_device_claimed_the_alert() {
        let (sender, bob, carol) = (Uuid::new_v4(), Uuid::new_v4(), Uuid::new_v4());
        let (bob_phone, bob_tablet, carol_phone) = (Uuid::new_v4(), Uuid::new_v4(), Uuid::new_v4());
        let owners: HashMap<Uuid, Uuid> =
            [(bob_phone, bob), (bob_tablet, bob), (carol_phone, carol)].into_iter().collect();
        // Bob's tablet claimed the album alert with this message; Carol was
        // already alerted by an earlier message of the album.
        let alerts: HashMap<Uuid, bool> =
            [(bob_phone, false), (bob_tablet, true), (carol_phone, false)].into_iter().collect();
        assert_eq!(alert_recipients(&owners, sender, Some(&alerts)), vec![bob]);
        assert!(alert_recipients(&owners, sender, Some(&HashMap::new())).is_empty());
    }

    #[test]
    fn album_items_after_the_first_go_quietly_to_devices_already_alerted() {
        let (bob_phone, bob_tablet, carol_phone) = (Uuid::new_v4(), Uuid::new_v4(), Uuid::new_v4());
        // Bob's tablet claims the album alert with this message (it rings); his
        // phone and Carol's phone rang for an earlier item, so they get a
        // quiet alert that their notification extension acknowledges.
        let alerts: HashMap<Uuid, bool> =
            [(bob_phone, false), (bob_tablet, true), (carol_phone, false)].into_iter().collect();
        let mut expected = vec![bob_phone, carol_phone];
        expected.sort_unstable();
        assert_eq!(album_quiet_devices(Some(&alerts)), expected);
        // Not an album: every recipient gets the ordinary alert.
        assert!(album_quiet_devices(None).is_empty());
        // The first item of an album alerts every device it reaches.
        let first: HashMap<Uuid, bool> = [(bob_phone, true), (carol_phone, true)].into_iter().collect();
        assert!(album_quiet_devices(Some(&first)).is_empty());
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

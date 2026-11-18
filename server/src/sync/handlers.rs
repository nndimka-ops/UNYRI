use axum::extract::{Query, State};
use axum::Json;
use uuid::Uuid;

use crate::auth::middleware::AuthUser;
use crate::error::AppError;
use crate::messages::models::EnvelopeRow;
use sqlx::{FromRow, Row};
use crate::state::AppState;
use crate::sync::models::{SyncQuery, SyncResponse};

const SYNC_LIMIT_DEFAULT: i64 = 100;
const SYNC_LIMIT_MAX: i64 = 500;
/// Ciphertext bytes per sync page (the first row is always included).
pub const SYNC_BYTES_MAX: i64 = 8 * 1024 * 1024;

/// `GET /sync?after_sequence=&limit=` - the *correctness path*: forward
/// catch-up over the global monotonic `messages.sequence` cursor (Этап A's
/// "Global monotonic cursor used for SYNC" - reused as-is, no second
/// cursor). WebSocket is the fast path; this is what a client falls back
/// to after any disconnect, force-stop, reboot, or missed WS event to
/// reach a provably-consistent state, and it's also what a device runs
/// once at startup to catch up on anything it missed while not connected.
///
/// Device-scoped, not user-scoped: only envelopes addressed to the
/// requester's own `auth.device_id` are ever returned (the same join used
/// by `GET /chats/{id}/messages`, just without a `chat_id` filter, since it
/// spans every chat the device is a member of at once) - a device can never
/// see another device's (even its own account's other device's) ciphertext,
/// and a message with no envelope for this device (sent before it existed,
/// or addressed elsewhere) is correctly invisible, not a "gap".
///
/// Ordered `sequence ASC` (oldest catch-up first) - the opposite of
/// `GET /chats/{id}/messages`, which paginates backwards from the newest
/// message for history scrollback. These are deliberately different
/// endpoints for different directions of travel; conflating them would
/// make either direction's pagination math (and "has more") ambiguous.
///
/// Gaps in the *global* sequence (other users' messages interleaved, or
/// messages with no envelope for this device) are normal and expected -
/// the cursor only tracks "furthest global sequence processed", never
/// implies every intervening value was seen.
pub async fn sync(
    State(state): State<AppState>,
    auth: AuthUser,
    Query(query): Query<SyncQuery>,
) -> Result<Json<SyncResponse>, AppError> {
    let visible_since = crate::auth::history::visible_since(&state.db, &auth).await?;
    let clear: Option<(i64,i64)> = sqlx::query_as("SELECT before_ms,through_sequence FROM history_clear_state WHERE singleton=TRUE")
        .fetch_optional(&state.db).await?;
    let history_cleared_before_ms = clear.map(|v| v.0);
    let history_cleared_through_sequence = clear.map(|v| v.1);
    let after_sequence = query.after_sequence.unwrap_or(0).max(0);
    let limit = query.limit.unwrap_or(SYNC_LIMIT_DEFAULT).clamp(1, SYNC_LIMIT_MAX);

    // Pages are bounded by bytes as well as rows: one envelope may be 1 MiB, so
    // 500 rows could materialize hundreds of MiB per request (audit server-032).
    // The first row is always included so a large envelope cannot stall the cursor.
    let rows = sqlx::query(
        "SELECT * FROM (            SELECT lim.*, SUM(lim.bytes) OVER (ORDER BY lim.sequence) AS running,                   ROW_NUMBER() OVER (ORDER BY lim.sequence) AS rn, COUNT(*) OVER () AS candidates            FROM (              SELECT m.id, m.chat_id, m.sender_id, m.sender_device_id, m.client_message_id, m.sequence, m.created_at,                     me.message_type, me.ciphertext, m.kind, m.target_message_id, me.notification_batch_alert,                     octet_length(me.ciphertext)::bigint AS bytes              FROM messages m              JOIN message_envelopes me ON me.message_id = m.id AND me.recipient_device_id = $1              WHERE m.sequence > $2 AND m.deleted_at IS NULL                AND m.created_at >= $4 AND NOT user_device_chat_blocked($1,m.chat_id)              ORDER BY m.sequence ASC              LIMIT $3) lim) page          WHERE rn = 1 OR running <= $5          ORDER BY sequence ASC",
    )
    .bind(auth.device_id)
    .bind(after_sequence)
    .bind(limit + 1)
    .bind(visible_since)
    .bind(SYNC_BYTES_MAX)
    .fetch_all(&state.db)
    .await?;

    // candidates = rows the row limit admitted (limit + 1 at most); more remains
    // when the row limit or the byte budget cut the page short.
    let candidates: i64 = match rows.first() { Some(r) => r.try_get("candidates")?, None => 0 };
    let mut page: Vec<EnvelopeRow> = Vec::with_capacity(rows.len());
    for r in rows.iter().take(limit as usize) { page.push(EnvelopeRow::from_row(r)?); }
    let has_more = candidates > page.len() as i64;
    let next_sequence = page.last().map(|row| row.sequence).unwrap_or(after_sequence);

    // Fetching via sync is the same "the device now has this envelope"
    // signal as history pagination - mark delivered accordingly. Whether
    // the client actually manages to decrypt it locally afterwards is a
    // separate, purely client-side concern the server has no visibility
    // into (and shouldn't - it never sees plaintext). Senders are told
    // here: the client's later POST /delivered for the same sequence is a no-op.
    let fetched: Vec<(Uuid, Uuid)> = page.iter().map(|row| (row.id, row.chat_id)).collect();
    crate::messages::handlers::mark_fetched_delivered(&state, &auth, &fetched).await?;

    let messages = page.into_iter().map(Into::into).collect();

    // Repeated independently of the message cursor: offline peers cannot skip a removal.
    let deleted_chat_ids = sqlx::query_scalar("SELECT chat_id FROM deleted_chats WHERE user_id=$1 ORDER BY chat_id")
        .bind(auth.user_id).fetch_all(&state.db).await?;
    Ok(Json(SyncResponse { history_cleared_through_sequence, history_cleared_before_ms, messages, deleted_chat_ids, next_sequence, has_more }))
}

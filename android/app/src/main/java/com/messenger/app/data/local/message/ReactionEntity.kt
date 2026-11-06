package com.messenger.app.data.local.message

import androidx.room.Entity

/**
 * Current reaction state, one row per (targetMessageId, userId) - the
 * composite PK itself enforces "0 or 1 active reaction per user per
 * message" (Этап I п.18) at the schema level. `targetMessageId` is a
 * SERVER id (matches `MessageEntity.serverId`, not clientMessageId), since
 * that's what the wire-level `target_message_id` field carries.
 *
 * `emoji = null` represents "removed" - the row is deliberately KEPT (not
 * deleted) so [lastReactionSequence] bookkeeping survives, which is what
 * stops a stale/reordered "set" event from resurrecting an already-removed
 * reaction (Этап I п.42): a later-arriving-but-earlier-sequence event is
 * rejected by comparing against this row's sequence regardless of whether
 * the reaction is currently active or removed.
 */
@Entity(tableName = "reactions", primaryKeys = ["targetMessageId", "userId"])
data class ReactionEntity(
    val targetMessageId: String,
    val userId: String,
    val emoji: String?,
    val lastReactionSequence: Long,
)

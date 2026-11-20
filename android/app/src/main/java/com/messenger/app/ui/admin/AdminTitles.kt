package com.messenger.app.ui.admin

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------------------
// Admin Product Pack 2.1 - display helpers for row metadata.
//
// Keeps opaque identifiers out of primary UI text and renders timestamps in a
// readable local form. Pure Kotlin; unit-testable.
// ---------------------------------------------------------------------------
internal object AdminTitles {
    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    fun meaningful(value: String?): String? = value?.takeIf { it.isNotBlank() && it != "null" }

    /** Public identity when explicitly available, otherwise a shortened id. */
    fun account(userId: String?, cache: AdminCache): String {
        val key = meaningful(userId) ?: return "—"
        val row = cache.userRow(key)
        return accountLabel(key, row?.publicUsername, row?.unyriNumber)
    }

    /** Compatibility username fields can contain a private login on older servers. */
    fun accountLabel(userId: String?, publicUsername: String?, unyriNumber: String? = null): String {
        val key = meaningful(userId) ?: return "—"
        return com.messenger.app.data.identity.PeerIdentityPolicy.handle(publicUsername, unyriNumber)
            .takeIf { it.isNotEmpty() } ?: AdminPresentation.shortId(key)
    }

    /** Best-effort human name for an account row. */
    fun displayName(row: AdminUserRowDto?): String {
        if (row == null) return "—"
        val name = meaningful(row.displayName?.let { com.messenger.app.domain.model.DisplayNamePolicy.display(it, "") })
        return name ?: accountLabel(row.userId, row.publicUsername, row.unyriNumber)
    }

    /** Best-effort human name for the account open in the moderation centre. */
    fun profileName(profile: AdminProfileDto?): String {
        if (profile == null) return "—"
        return meaningful(profile.displayName.let { com.messenger.app.domain.model.DisplayNamePolicy.display(it, "") })
            ?: accountLabel(profile.userId, profile.publicUsername, profile.unyriNumber)
    }

    /**
     * ISO-8601 from the server to local time. The raw value is never shown
     * twice: an unparseable timestamp is reported as unreadable rather than
     * leaking a machine timestamp into the interface.
     */
    fun time(value: String?): String {
        val raw = meaningful(value) ?: return ""
        return runCatching {
            Instant.parse(raw).atZone(ZoneId.systemDefault()).format(timeFormat)
        }.getOrDefault(raw)
    }

    /** The timestamp, or null when there is no expiry to show. */
    fun timeOrNull(value: String?): String? = meaningful(value)?.let { time(it) }
}

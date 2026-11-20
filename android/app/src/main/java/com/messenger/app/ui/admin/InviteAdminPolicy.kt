package com.messenger.app.ui.admin

/** Foreground, event-driven form and pagination rules; no clock or background work. */
internal object InviteAdminPolicy {
    const val defaultUses = 1
    const val defaultDays = 7

    fun validLimits(uses: Int?, days: Int?): Boolean = uses in 1..10000 && days in 1..365

    fun limits(uses: String, days: String): Pair<Int, Int>? {
        if (uses.isEmpty() || days.isEmpty() || uses.any { it !in '0'..'9' } || days.any { it !in '0'..'9' }) return null
        val count = uses.toIntOrNull()
        val lifetime = days.toIntOrNull()
        return if (validLimits(count, lifetime)) count!! to lifetime!! else null
    }

    fun append(current: InvitePage?, next: InvitePage, cursor: String): InvitePage {
        require(next.nextBefore != cursor) { "Invitation page did not advance" }
        return InvitePage((current?.items.orEmpty() + next.items).distinctBy { it.id }, next.nextBefore)
    }
}

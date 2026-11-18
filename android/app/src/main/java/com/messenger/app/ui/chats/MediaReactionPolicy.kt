package com.messenger.app.ui.chats

internal object MediaReactionPolicy {
    fun incoming(oldCount: Int, oldMine: Boolean, count: Int, mine: Boolean) =
        count - (if (mine) 1 else 0) > oldCount - (if (oldMine) 1 else 0)
    fun mayAnimate(own: Boolean, visible: Boolean, active: Boolean, covered: Boolean, sinceOpen: Long, sinceConnection: Long) =
        visible && active && !covered && (own || sinceOpen >= 1000 && sinceConnection >= 1500)
}
internal class MediaReactionAdmission {
    private val active = mutableSetOf<Any>()
    fun enter(token: Any): Boolean { if (active.size >= 3 && token !in active) return false; active.add(token); return true }
    fun leave(token: Any) { active.remove(token) }
}

package com.messenger.app.push

object ReminderPolicy {
    enum class Action { SCHEDULE, PAUSE, DROP, KEEP_DUE }
    fun action(due: Long, now: Long, enabled: Boolean, startup: Boolean = false): Action = when {
        due <= now && startup -> Action.KEEP_DUE
        due <= now -> Action.DROP
        !enabled -> Action.PAUSE
        else -> Action.SCHEDULE
    }
}

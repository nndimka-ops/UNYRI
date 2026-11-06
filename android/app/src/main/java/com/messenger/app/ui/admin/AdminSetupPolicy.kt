package com.messenger.app.ui.admin

/** Short, monotonic-clock window for an unfinished form. Unlocked sessions never use it. */
internal object AdminSetupPolicy {
    fun validCode(value: String, recovery: Boolean): Boolean =
        if (recovery) value.matches(Regex("[A-Za-z0-9_-]{43}"))
        else value.length == 6 && value.all { it in '0'..'9' }

    const val resumeWindowMs = 180_000L
    fun expired(backgroundAt: Long?, now: Long): Boolean =
        backgroundAt != null && (now < backgroundAt || now - backgroundAt >= resumeWindowMs)
}

/** Unknown v2 policies fail closed; absent protocol remains the original both. */
internal enum class AdminFactorPolicy(val wire: String, val authenticator: Boolean, val paper: Boolean) {
    Authenticator("authenticator", true, false), PaperKey("paper_key", false, true), Both("both", true, true);

    companion object {
        fun fromStatus(protocol: Int, policy: String?): AdminFactorPolicy? =
            if (protocol < 2) Both else entries.firstOrNull { it.wire == policy }

        fun acceptsServerPin(previous: String?, pending: String?, server: String): Boolean =
            (previous == null && pending == null) || previous?.substringBefore('|') == server || pending?.substringBefore('|') == server
    }
}

package com.messenger.app.ui.admin

/** Missing or malformed limits are omitted; zero explicitly means unlimited. */
internal object ServerCardValuePolicy {
    fun parseLimit(raw: String): Long? = raw.trim().toLongOrNull()?.takeIf { it >= 0L }
}

package com.messenger.app.data.identity

/** Only explicit public identity is displayable. Legacy peers have display names only. */
object PeerIdentityPolicy {
    fun handle(publicUsername: String?, number: String?, legacyUsername: String = "", legacy: Boolean = false): String {
        if (legacy) return ""
        val name = publicUsername?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
        if (name != null) return "@$name"
        number?.trim()?.takeIf { it.isNotEmpty() }?.let { return IdentityPolicy.formattedNumber(it) }
        return ""
    }

    /** B-051 (owner, 19 Nov: «почему я не вижу персональный номер людей в контактах»): a Contacts row
     *  always names the public number grouped by three, then " · @username" when one is set. */
    fun contactCaption(publicUsername: String?, number: String?, legacy: Boolean = false): String {
        if (legacy) return ""
        val grouped = number?.trim()?.takeIf { it.isNotEmpty() }?.let { IdentityPolicy.formattedNumber(it) }
        val name = handle(publicUsername, null).takeIf { it.isNotEmpty() }
        return listOfNotNull(grouped, name).joinToString(" · ")
    }
}

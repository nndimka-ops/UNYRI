package com.messenger.app.security

/** The only external destination admitted alongside verified notification capabilities.
 * It names a public profile, never a chat, message, workspace or account. */
internal class PublicProfileLink private constructor(val username: String) {
    companion object {
        private val usernamePattern = Regex("[A-Za-z0-9_.]{3,32}")

        fun uriFor(username: String?): String? = username?.takeIf(usernamePattern::matches)
            ?.let { "unyri://user/$it" }

        fun parse(action: String?, value: String?): PublicProfileLink? = runCatching {
            if (action != "android.intent.action.VIEW" || value == null) return null
            com.messenger.app.data.identity.IdentityPolicy.parse(value)?.let { return PublicProfileLink(value) }
            val uri = java.net.URI(value)
            if (!uri.scheme.equals("unyri", ignoreCase = true) ||
                !uri.rawAuthority.equals("user", ignoreCase = true) ||
                uri.rawQuery != null || uri.rawFragment != null) return null
            val username = uri.rawPath?.removePrefix("/") ?: return null
            if (uri.rawPath != "/$username" || uriFor(username) == null) return null
            PublicProfileLink(username)
        }.getOrNull()
    }
}

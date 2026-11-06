package com.messenger.app.ui.admin

import android.content.Context
import android.content.Intent
import android.net.Uri

// ---------------------------------------------------------------------------
// ADMIN CONSOLE PACK 3 - native side of the server-hosted console.
//
// The phone keeps only the secure core: it performs the three-factor unlock, and
// mints a single-use handoff for the browser. It no longer duplicates the
// administration product - that lives at /administration and is served by the
// COCO server. The native Pack 2 screens remain available as a fallback while the
// console is verified.
//
// The long-lived administration token never leaves the native channel: the URL
// carries a handoff that is good for one redemption and at most a minute.
// ---------------------------------------------------------------------------

internal object AdminConsolePolicy {
    /** A handoff is a 32-byte base64url token. */
    const val HandoffLength = 43

    /** The server path that advertises the console. */
    const val ConsolePath = "administration"

    /** The entry exists only for a role the server actually granted. */
    fun entryVisible(role: String?): Boolean = AdminNavigation.isAdministrator(role)

    /**
     * The console URL for a handoff, or null when it cannot be built safely.
     *
     * The handoff travels in the fragment: a fragment is never sent to the
     * server, never recorded by a reverse proxy and never leaks through a
     * `Referer` header. The console page wipes it from the address bar before it
     * does anything else. HTTPS is required - administration over cleartext is
     * not offered, and the console sends `Secure` cookies and enforces
     * same-origin requests.
     */
    fun consoleUrl(base: String, handoff: String): String? {
        if (handoff.length != HandoffLength) return null
        if (handoff.any { !(it.isLetterOrDigit() || it == '-' || it == '_') }) return null
        val origin = base.trim().trimEnd('/')
        if (!origin.startsWith("https://")) return null
        return "$origin/$ConsolePath#handoff=$handoff"
    }

    /**
     * Only the native channel may mint a handoff, so a stolen browser cookie
     * cannot be turned into a durable foothold. The client mirrors the server
     * rule to avoid offering an action that would be refused.
     */
    fun canMintHandoff(role: String?): Boolean = entryVisible(role)

    fun failure(status: Int?): AdminPresentation.Failure =
        AdminPresentation.failure(status, status == null)
}

internal object AdminConsole {
    /**
     * Opens the console in the user's browser.
     *
     * Deliberately the system browser rather than an embedded WebView: a WebView
     * would put administration cookies inside the app process, and there is no
     * native integration that needs it. `ACTION_VIEW` also keeps the console's
     * own security model (HTTPS, same-origin, `SameSite=Strict`) intact.
     */
    fun open(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

package com.messenger.app.data.auth

/** Queue ownership survives erasure; a transient/offline transport never delays local privacy. */
internal object InactiveAccountErasure {
    suspend fun run(reason: String?, refreshToken: String?, queueLogout: suspend (String) -> Unit,
        eraseSecurity: suspend () -> Unit, eraseWorkspace: suspend () -> Unit) {
        if (reason == null && refreshToken != null) queueLogout(refreshToken)
        if (reason == "account_deleted") eraseSecurity()
        eraseWorkspace()
    }
}

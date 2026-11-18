package com.messenger.app.push

import android.content.Context
import com.messenger.app.MessengerApp
import com.messenger.app.data.auth.AccountWorkspaceContext
import com.messenger.app.data.secure.SecureTokenStore

/** Open a saved account's own encrypted identity; never borrow the active account's credentials. */
class NotificationAccounts(private val app: MessengerApp) {
    data class Account(val key: String, val origin: String, val name: String,
        val storage: Context, val session: SecureTokenStore)
    fun find(owner: String, device: String?, workspace: String? = null): Account? {
        for (record in app.accountWorkspaces.accounts().filter { workspace == null || it.key == workspace }) {
            val storage = AccountWorkspaceContext(app.baseContext, record.key)
            val session = runCatching { SecureTokenStore(storage) }.getOrNull() ?: continue
            if (session.userId() == owner && (device == null && workspace != null || session.deviceId() == device) && session.refreshToken() != null)
                return Account(record.key, record.origin, record.displayName, storage, session)
        }
        return null
    }
}

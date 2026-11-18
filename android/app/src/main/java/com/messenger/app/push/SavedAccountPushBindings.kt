package com.messenger.app.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class SavedPushAccount(val workspace: String, val origin: String)
internal data class SavedPushOwner(val account: SavedPushAccount, val user: String, val device: String, val generation: String = "session")

/** Active-account uploads and saved-account migration share write ordering. */
internal object PushBindingWrites { val mutex = Mutex() }

/** Saved registry + current SDK token - durable per-owner acknowledgements = pending bindings.
 * A failed account does not prevent the others from migrating. Replacement workers serialize
 * with in-flight writes so a retired token cannot overwrite a newer binding afterward. */
internal class SavedAccountPushBindings(
    private val accounts: () -> List<SavedPushAccount>,
    private val owner: suspend (SavedPushAccount) -> SavedPushOwner?,
    private val acknowledged: (SavedPushOwner, String) -> Boolean,
    private val register: suspend (SavedPushOwner, String) -> Unit,
    private val isCurrent: (SavedPushOwner) -> Boolean,
    private val acknowledge: (SavedPushOwner, String) -> Unit,
    private val mutex: Mutex = Mutex(),
) {
    suspend fun reconcile(token: String): Boolean = mutex.withLock {
        var complete = true
        val snapshot = accounts()
        for (account in snapshot) {
            currentCoroutineContext().ensureActive()
            try {
                val expected = owner(account) ?: continue // A logged-out account has no binding to repair.
                if (acknowledged(expected, token)) continue
                withContext(NonCancellable) {
                    if (!isCurrent(expected)) { complete = false; return@withContext }
                    register(expected, token) // The transport has a bounded HTTP deadline.
                    if (isCurrent(expected)) acknowledge(expected, token) else complete = false
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { complete = false }
        }
        complete && accounts().toSet() == snapshot.toSet()
    }
}

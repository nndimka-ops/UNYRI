package com.messenger.app.ui.chats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/** Synchronous button admission. The repository transaction owns durable draft/outbox ownership. */
internal class VoiceDraftActions {
    enum class Action { Send, Delete }
    internal class Claim(val id: String, val action: Action) {
        var committed = false
            private set
        // A committed storage operation stays retired if later UI cleanup fails.
        fun commit() { committed = true }
    }

    var busy by mutableStateOf(false)
        private set
    private val retired = mutableSetOf<String>()

    fun launch(id: String, action: Action, scope: CoroutineScope, onFailure: (Exception) -> Unit,
        operation: suspend (Claim) -> Unit): Boolean {
        if (busy || id in retired) return false
        busy = true
        val claim = Claim(id, action)
        scope.launch(NonCancellable) {
            try { operation(claim) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { onFailure(error) }
            finally {
                if (claim.committed) retired += id
                busy = false
            }
        }
        return true
    }
}

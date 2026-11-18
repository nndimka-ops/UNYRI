package com.messenger.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.messenger.app.data.identity.IdentityPolicy
import com.messenger.app.domain.model.PublicUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun profileLinkMatches(raw: String, user: PublicUser): Boolean {
    val target = IdentityPolicy.parse(raw)?.target ?: return user.username.equals(raw, true)
    return when (target.parameter) {
        "number" -> user.publicNumber != null && (target.value.length < 9 || user.publicNumber == target.value)
        "username" -> user.publicUsername == target.value
        else -> false
    }
}

/** Uses the same direct-chat and contact-store operations as Contacts, fenced to the visible card. */
internal class ProfileLinkActions(private val owner: String, private val currentOwner: () -> String?,
    private val currentCard: () -> Boolean, private val openDirect: suspend (String) -> Result<String>,
    private val add: suspend () -> Boolean) {
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private fun current() = owner == currentOwner() && currentCard()
    suspend fun message(userId: String): String? {
        if (busy || !current()) return null
        busy = true; failed = false
        return try {
            val result = openDirect(userId)
            currentCoroutineContext().ensureActive()
            if (!current()) null else result.getOrElse { failed = true; null }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { if (current()) failed = true; null }
        finally { busy = false }
    }
    suspend fun addContact(alreadyContact: Boolean) {
        if (alreadyContact || busy || !current()) return
        busy = true; failed = false
        try { val success = add(); if (current()) failed = !success }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { if (current()) failed = true }
        finally { busy = false }
    }
}

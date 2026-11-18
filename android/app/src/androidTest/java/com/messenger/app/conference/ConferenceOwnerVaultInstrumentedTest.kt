package com.messenger.app.conference

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.messenger.app.data.secure.SecureTokenStore
import java.time.Instant
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Keystore-backed adapter; uses a dedicated disposable preferences file. */
@RunWith(AndroidJUnit4::class)
class ConferenceOwnerVaultInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefsName = "conference_owner_vault_qa_" + UUID.randomUUID().toString()
    private val user = UUID.randomUUID().toString()
    private val device = UUID.randomUUID().toString()
    private val tokens = SecureTokenStore(context, prefsName)
    private fun vault(tokens: SecureTokenStore = this.tokens) = ConferenceOwnedRoomStore(
        tokens::conferenceRoomOwner, tokens::readConferenceRoomAccess, tokens::saveConferenceRoomAccess)
    private fun login() = tokens.saveSession("qa-not-a-real-access-token", Instant.now().plusSeconds(300),
        "qa-not-a-real-refresh-token", user, "owned_vault_qa", "QA", device)
    private fun remember(): Pair<ConferenceInvite, ConferenceDto> {
        val owner = requireNotNull(tokens.conferenceRoomOwner())
        val invite = ConferenceInvite.fresh(owner.origin)
        val deadline = Instant.now().plusSeconds(3_600).toString()
        assertTrue(vault().remember(owner, invite, deadline, user))
        return invite to ConferenceDto(invite.roomId, "Vault QA", user, 30, "active", expiresAt = deadline)
    }
    @After fun dispose() { tokens.clearSession(); context.deleteSharedPreferences(prefsName) }

    @Test fun keystoreRecordRestoresExactlyAcrossStoreRecreation() {
        login(); val (invite, room) = remember()
        val restored = vault(SecureTokenStore(context, prefsName)).invitation(room)
        assertNotNull(restored); assertTrue(requireNotNull(restored).sameCapability(invite))
    }
    @Test fun tokenRefreshPreservesRoomButLogoutAndSameAccountLoginDoNot() {
        login(); val (invite, room) = remember(); val oldOwner = requireNotNull(tokens.conferenceRoomOwner())
        tokens.saveTokens("qa-refreshed-access", Instant.now().plusSeconds(300), "qa-refreshed-refresh")
        assertEquals(oldOwner, tokens.conferenceRoomOwner())
        assertTrue(requireNotNull(vault().invitation(room)).sameCapability(invite))
        tokens.clearSession()
        assertNull(tokens.conferenceRoomOwner()); assertNull(vault().invitation(room))
        login()
        assertNotEquals(oldOwner, tokens.conferenceRoomOwner()); assertNull(vault().invitation(room))
        assertFalse(tokens.saveConferenceRoomAccess(oldOwner, "stale writer"))
        assertNull(vault().invitation(room))
    }
    @Test fun accountReplacementWithoutPriorClearStillRotatesOwnerAndDropsRoom() {
        login(); val (_, room) = remember(); val previous = requireNotNull(tokens.conferenceRoomOwner())
        login()
        assertNotEquals(previous, tokens.conferenceRoomOwner())
        assertNull(vault().invitation(room))
        assertFalse(tokens.saveConferenceRoomAccess(previous, "late write"))
    }
}

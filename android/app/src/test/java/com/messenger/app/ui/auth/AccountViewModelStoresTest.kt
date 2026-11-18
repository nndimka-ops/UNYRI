package com.messenger.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Test

class AccountViewModelStoresTest {
    class CachedProfile : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CachedProfile() as T
    }

    private fun profileOf(owner: androidx.lifecycle.ViewModelStoreOwner): CachedProfile =
        ViewModelProvider(owner, factory)[CachedProfile::class.java]

    @Test fun `rotation keeps state but next login clears old models`() {
        val stores = AccountViewModelStores()
        val first = stores.forSession(1)
        val profile = profileOf(first)
        assertSame(first, stores.forSession(1))
        assertFalse(profile.cleared)
        val next = stores.forSession(3)
        assertTrue(profile.cleared)
        assertNotSame(first, next)
        assertNotSame(profile, profileOf(next))
    }

    /** P1 account switch: the very same session must stay stable, so repeated
     *  compositions never re-create account state. */
    @Test fun `same session resolves the same scope and the same view model`() {
        val stores = AccountViewModelStores()
        val owner = stores.forSession(5)
        assertSame(owner, stores.forSession(5))
        assertSame(profileOf(owner), profileOf(stores.forSession(5)))
    }

    /** P1 account switch: a resolved session must never hand back the previous
     *  account's instance, and going A -> B -> A must be a new session every time. */
    @Test fun `account switch never returns the previous account instance`() {
        val stores = AccountViewModelStores()
        val ownerA = stores.forSession(1)
        val profileA = profileOf(ownerA)
        val ownerB = stores.forSession(2)
        val profileB = profileOf(ownerB)
        assertNotSame(ownerA, ownerB)
        assertNotSame(profileA, profileB)
        assertTrue(profileA.cleared)

        val ownerAAgain = stores.forSession(3)
        assertNotSame(ownerA, ownerAAgain)
        assertNotSame(profileB, profileOf(ownerAAgain))
        assertTrue(profileB.cleared)
    }
}

package com.messenger.app.data.directtransfer

import kotlin.test.*

class DirectFileDestinationTest {
    private val chat = "11111111-1111-4111-8111-111111111111"
    private val own = "22222222-2222-4222-8222-222222222222"
    private val peer = "33333333-3333-4333-8333-333333333333"
    private fun destination() = DirectFileDestinationSnapshot(chat, "direct", false, peer, own, 7)
    @Test fun onlyAvailableOtherPersonInCurrentDirectChatIsEligible() {
        assertEquals(peer, directFileRecipient(destination(), chat, own, 7))
        for (value in listOf(destination().copy(type="saved"), destination().copy(type="self",peerUserId=null),
            destination().copy(type="group"), destination().copy(blocked=true), destination().copy(peerUserId=own),
            destination().copy(peerUserId=null), destination().copy(peerUserId="broken"), destination().copy(chatId=peer)))
            assertNull(directFileRecipient(value,chat,own,7))
    }
    @Test fun cachedDestinationCannotCrossAccountEpochOrConversation() {
        assertNull(directFileRecipient(destination(),chat,peer,7))
        assertNull(directFileRecipient(destination(),chat,own,8))
        assertNull(directFileRecipient(destination(),peer,own,7))
        assertNull(directFileRecipient(destination(),chat,null,7))
    }
    @Test fun unknownUnsupportedProviderStaysNormalRatherThanAssumedOversize() {
        assertEquals(DirectOriginalRoute.NORMAL,directOriginalRoute(-1,false))
        assertEquals(DirectOriginalRoute.NORMAL,directOriginalRoute(0,false))
        assertEquals(DirectOriginalRoute.DIRECT,directOriginalRoute(-1,true))
        assertEquals(DirectOriginalRoute.NORMAL,directOriginalRoute(DIRECT_NORMAL_SOURCE_LIMIT,false))
        assertEquals(DirectOriginalRoute.UNAVAILABLE,directOriginalRoute(DIRECT_NORMAL_SOURCE_LIMIT+1,false))
        assertEquals(DirectOriginalRoute.DIRECT,directOriginalRoute(DIRECT_NORMAL_SOURCE_LIMIT+1,true))
    }
    @Test fun unsupportedLargeItemDoesNotDiscardUnknownAndOrdinaryMixedItems() {
        val selected = listOf("small" to 8L,"unknown" to -1L,"large" to DIRECT_NORMAL_SOURCE_LIMIT+1)
        val original = selected.toList()
        assertEquals(listOf("small","unknown"),selected.filter { directOriginalRoute(it.second,false)==DirectOriginalRoute.NORMAL }.map {it.first})
        assertEquals(listOf("large"),selected.filter { directOriginalRoute(it.second,false)==DirectOriginalRoute.UNAVAILABLE }.map {it.first})
        assertEquals(original,selected)
    }
}

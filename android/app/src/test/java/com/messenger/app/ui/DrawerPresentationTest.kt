package com.messenger.app.ui

import com.messenger.app.data.directtransfer.*
import com.messenger.app.ui.theme.SavedSidebarStyle
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent

class DrawerPresentationTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun qrPresentationWaitsForCloseAndCancelledCloseCannotPresentLater() = runTest {
        val closed = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val open = launch { closeDrawerThenShowQr({ closed.await(); events += "closed" }, { events += "qr" }) }
        runCurrent(); assertTrue(events.isEmpty())
        closed.complete(Unit); open.join()
        assertEquals(listOf("closed", "qr"), events)
        val cancelled = launch { closeDrawerThenShowQr({ CompletableDeferred<Unit>().await() }, { error("Cancelled close must not show QR") }) }
        runCurrent(); cancelled.cancelAndJoin()
    }

    @Test fun injectedRowsFollowReferenceAnchorsAndVisibility() {
        val values = drawerItems(SavedSidebarStyle.items, true, true).map { it.id }
        assertEquals(listOf("chats", "archive", "transfers", "calls", "rooms", "saved", "settings", "server", "devices", "help", "report"), values)
        val withoutCallsOrSaved = drawerItems(SavedSidebarStyle.items, false, false).map { it.id }
        assertEquals(listOf("chats", "archive", "transfers", "rooms", "settings", "server", "devices", "help", "report"), withoutCallsOrSaved)
    }

    @Test fun customOrderColorsAndHiddenRowsAreNotOverwritten() {
        val saved = SavedSidebarStyle.items.map {
            when (it.id) {
                "chats" -> it.copy(order = 8, color = 0xFF123456)
                "settings" -> it.copy(order = -1)
                "help" -> it.copy(visible = false)
                else -> it
            }
        } + SavedSidebarStyle.Item("archive", "custom archive", "archive", 0xFF123456, false, 10, "main")
        val values = drawerItems(saved, true, true)
        assertEquals("settings", values.first { it.id in SavedSidebarStyle.items.map { item -> item.id } }.id)
        assertFalse(values.any { it.id == "archive" || it.id == "help" })
        assertEquals(0xFF123456, values.single { it.id == "chats" }.color)
        assertEquals(0xFF123456, values.single { it.id == "transfers" }.color)
        assertEquals(values.map { it.id }.distinct(), values.map { it.id })
    }

    private val offer = DirectFileOffer(transferId = "transfer", offerClientMessageId = "message", senderUserId = "sender",
        senderDeviceId = "device", recipientUserId = "recipient", offerNonce = "nonce", filename = "file.bin",
        mimeType = "application/octet-stream", size = 1024, sha256 = "a".repeat(64), expiresAt = 1)
    private fun item(id: String, state: String, completed: Boolean = false) = DirectTransferManagerItem(id, "chat", true,
        DirectFilePresentation(offer, state, "sender", completed = completed), 0, DirectTransferManagerGroup.ACTIVE)

    @Test fun counterIncludesPausedRecordsAndIgnoresProgressAndTerminalRecords() {
        val items = listOf(item("active", "sending"), item("held", "paused"), item("waiting", "offered"),
            item("done", "completed", true), item("cancel", "cancelled"), item("reject", "rejected"),
            item("expired", "expired"), item("other", "other_device"),
            DirectTransferManagerItem("preparation", "chat", true, null, 0, DirectTransferManagerGroup.QUEUED))
        assertEquals(3, drawerTransferCount(items))
        assertEquals(3, drawerTransferCount(items.map { it.copy(presentation = it.presentation?.copy(offset = 999, speedBytesPerSecond = 99999)) }))
        assertEquals(0, drawerTransferCount(emptyList()))
        assertEquals(2, drawerTransferCount(items.drop(1)))
    }

    @Test fun middleTruncationKeepsBothEndsAndNeverOverflowsItsMeasuredSlot() {
        val text = "@long_username_12345"
        val result = middleEllipsized(text) { it.length <= 11 }
        assertTrue(result.startsWith("@long"))
        assertTrue(result.endsWith("12345"))
        assertTrue(result.length <= 11)
        assertEquals(text, middleEllipsized(text) { true })
        assertEquals("", middleEllipsized(text) { false })
    }
}

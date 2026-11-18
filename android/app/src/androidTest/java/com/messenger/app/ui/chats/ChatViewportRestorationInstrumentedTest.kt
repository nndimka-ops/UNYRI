package com.messenger.app.ui.chats

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatViewportRestorationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reclaimedProcessRestoresOlderPositionAndOffsetBeforeDelayedHydrationWithoutBookmarkMap() {
        val restoration = StateRestorationTester(compose)
        val keys = (0..99).map { "message-$it" }
        var ready by mutableStateOf(true)
        var frozen by mutableStateOf(false)
        lateinit var viewport: ChatViewportState
        lateinit var scope: CoroutineScope
        restoration.setContent {
            val state = rememberChatViewportState("chat", 0, 0)
            viewport = state
            scope = rememberCoroutineScope()
            SideEffect { state.hydrated(ready, frozen) }
            LaunchedEffect(ready, frozen, state) {
                if (ready && !frozen) placeInitialChatViewport(state, keys, null, false)
            }
            if (state.canPresent(ready, frozen)) LazyColumn(state = state.listState) {
                items(keys, key = { it }) { Text(it, Modifier.height(80.dp)) }
            }
        }
        compose.waitUntil(10_000) { viewport.initialPlaced }
        compose.runOnIdle { scope.launch { viewport.listState.scrollToItem(23, 17) } }
        compose.waitUntil(10_000) { viewport.listState.firstVisibleItemIndex == 23 }
        compose.runOnIdle { ready = false; frozen = true }
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertTrue(viewport.initialPlaced)
            assertEquals(23, viewport.listState.firstVisibleItemIndex)
            assertEquals(17, viewport.listState.firstVisibleItemScrollOffset)
            ready = true // Only an early frozen/call-only snapshot is available.
        }
        compose.waitForIdle()
        compose.onNodeWithText("message-23").assertDoesNotExist()
        compose.runOnIdle { assertEquals(23, viewport.listState.firstVisibleItemIndex); frozen = false }
        compose.onNodeWithText("message-23").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(23, viewport.listState.firstVisibleItemIndex)
            assertEquals(17, viewport.listState.firstVisibleItemScrollOffset)
        }
    }

    @Test fun freshChatAppliesItsBookmarkOnceAndDoesNotReapplyItOnLaterHydration() {
        val keys = (0..99).map { "message-$it" }
        var hydration by mutableStateOf(0)
        lateinit var viewport: ChatViewportState
        lateinit var scope: CoroutineScope
        compose.setContent {
            val state = rememberChatViewportState("chat", 0, 0)
            viewport = state; scope = rememberCoroutineScope()
            LaunchedEffect(hydration) {
                placeInitialChatViewport(state, keys, ChatScrollBookmark("message-31", 9, false), false)
            }
            LazyColumn(state = state.listState) { items(keys, key = { it }) { Text(it, Modifier.height(80.dp)) } }
        }
        compose.waitUntil(10_000) { viewport.initialPlaced }
        compose.runOnIdle { assertEquals(31, viewport.listState.firstVisibleItemIndex); scope.launch { viewport.listState.scrollToItem(12, 5) } }
        compose.waitUntil(10_000) { viewport.listState.firstVisibleItemIndex == 12 }
        compose.runOnIdle { hydration++ }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(12, viewport.listState.firstVisibleItemIndex)
            assertEquals(5, viewport.listState.firstVisibleItemScrollOffset)
        }
    }
}

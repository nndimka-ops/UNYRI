package com.messenger.app.ui.chats

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class NotificationTargetScrollInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun targetWaitsForHydratedLayoutAndCompletesOnlyWhenItsRowIsVisible() {
        val keys = (0..99).map { "row-$it" }
        var rows by mutableStateOf(listOf("old-call"))
        val completed = AtomicBoolean(false)
        compose.setContent {
            val state = rememberLazyListState()
            LaunchedEffect(Unit) { scrollToNotificationTarget(state, keys, 80, 0); completed.set(true) }
            LazyColumn(state = state) { items(rows, key = { it }) { Text(it, Modifier.height(80.dp)) } }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse("An old measured layout cannot acknowledge the target", completed.get()); rows = keys }
        compose.waitUntil(10_000) { completed.get() }
        compose.onNodeWithText("row-80").assertIsDisplayed()
    }
}

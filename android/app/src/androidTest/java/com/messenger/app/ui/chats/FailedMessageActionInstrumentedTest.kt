package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.R
import com.messenger.app.domain.repository.FailedMessageAction
import com.messenger.app.ui.theme.CocoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

class FailedMessageActionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun actionsFitNarrowWidthAndLargeText() {
        var retried = 0; var cancelled = 0
        compose.setContent { CocoTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Box(Modifier.statusBarsPadding().width(280.dp)) {
                    FailedMessageActionNotice(FailedMessageAction("test", "delete"), { retried++ }, { cancelled++ })
                }
            }
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.chat_failed_delete)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.retry)).assertIsDisplayed().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retried); assertEquals(1, cancelled) }
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.filesDir, "failed-action-large-text.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }; image.recycle()
    }
    @Test fun realSqliteFailureQueryTracksRetryAndCancellation() = runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(compose.activity,
            com.messenger.app.data.local.chat.MessengerDatabase::class.java).build()
        try {
            val dao = db.messageDao()
            val failed = com.messenger.app.data.local.message.MessageEntity("failed", null, "chat", "owner", "payload", null, "2026-10-04", "failed", kind="reaction", targetMessageId="target")
            dao.upsert(failed)
            dao.upsert(failed.copy(clientMessageId="other", chatId="other"))
            assertEquals(listOf("failed"), dao.observeFailedActions("chat").first().map { it.clientMessageId })
            dao.resetToPending("failed")
            assertTrue(dao.observeFailedActions("chat").first().isEmpty())
            dao.upsert(failed.copy(hiddenLocally=true))
            assertTrue(dao.observeFailedActions("chat").first().isEmpty())
            assertEquals("reaction", dao.findByClientMessageId("failed")?.kind)
            assertEquals("target", dao.findByClientMessageId("failed")?.targetMessageId)
        } finally { db.close() }
    }
}

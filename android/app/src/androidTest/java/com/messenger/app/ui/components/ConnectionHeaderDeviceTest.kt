package com.messenger.app.ui.components

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.MessengerApp
import com.messenger.app.MainActivity
import com.messenger.app.domain.repository.SocketConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in test on an existing signed-in QA installation. Never changes keys or server. */
@RunWith(AndroidJUnit4::class)
class ConnectionHeaderDeviceTest {
    @Test fun actualServerRttAndPreference() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowTestAccount") == "yes")
        val app = ApplicationProvider.getApplicationContext<MessengerApp>()
        app.historyReady.await()
        args.getString("pingEnabled")?.let {
            require(it == "true" || it == "false")
            app.getSharedPreferences("coco-appearance", 0).edit().putBoolean("connection-ping", it.toBoolean()).commit()
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        withTimeout(30_000) { app.messageRepository.socketState.first { it == SocketConnectionState.CONNECTED } }
        val ping = app.messageRepository.measurePing()
        assertNotNull("Authenticated server PONG must provide a real RTT", ping)
        assertTrue(ping!! in 1..5000)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "Server RTT: $ping ms\n") })
    }
}

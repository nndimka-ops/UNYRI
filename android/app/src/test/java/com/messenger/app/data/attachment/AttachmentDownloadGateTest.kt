package com.messenger.app.data.attachment

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AttachmentDownloadGateTest {
    @Test fun duplicateWriterWaitsButAnotherAttachmentDoesNot() = runBlocking {
        val gate = AttachmentDownloadGate()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = launch { gate.run("photo") { started.complete(Unit); release.await(); events += "first" } }
        started.await()
        val second = launch { gate.run("photo") { events += "second" } }
        val other = launch { gate.run("file") { events += "other" } }
        other.join()
        assertEquals(listOf("other"), events)
        release.complete(Unit)
        first.join(); second.join()
        assertEquals(listOf("other", "first", "second"), events)
    }

    @Test fun cancelledWaiterAndFailedWriterDoNotPoisonRetry() = runBlocking {
        val gate = AttachmentDownloadGate()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch { gate.run("photo") { started.complete(Unit); release.await() } }
        started.await()
        val waiter = launch { gate.run("photo") { error("Cancelled waiter ran") } }
        yield(); waiter.cancelAndJoin()
        release.complete(Unit); first.join()
        assertTrue(runCatching { gate.run("photo") { error("failed transfer") } }.isFailure)
        assertEquals("retried", gate.run("photo") { "retried" })
    }
    private suspend fun queuedPolicyChange(changeNetwork: Boolean) = coroutineScope {
        val gate=AttachmentDownloadGate(); val started=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        var network=DownloadNetwork.WIFI; var wifiLimit=100L
        val events=mutableListOf<String>()
        val first=launch { gate.run("same") { started.complete(Unit); release.await() } }
        started.await()
        val automatic=async { runCatching { gate.run("same",beforeStart={
            val limit=if(network==DownloadNetwork.WIFI) wifiLimit else 0L
            if(!MediaDownloadPolicy.allows(network,10,limit)) throw AutomaticDownloadDeferred()
        }) { events.add("automatic-http") } } }
        yield()
        if(changeNetwork) network=DownloadNetwork.CELLULAR else wifiLimit=0
        val manual=launch { gate.run("same") { events.add("manual-http") } }
        release.complete(Unit); first.join(); manual.join()
        assertTrue(automatic.await().exceptionOrNull() is AutomaticDownloadDeferred)
        assertEquals(listOf("manual-http"),events)
    }
    @Test fun queuedWifiAutomaticCannotStartAfterCellularSwitchButManualCan() = runBlocking { queuedPolicyChange(true) }
    @Test fun queuedAutomaticCannotStartAfterPreferenceDisableButManualCan() = runBlocking { queuedPolicyChange(false) }

}

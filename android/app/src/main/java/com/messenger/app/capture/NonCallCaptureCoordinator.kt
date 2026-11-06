package com.messenger.app.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class CaptureOwner(val userId: String, val origin: String, val deviceId: String)
data class CaptureSession(val owner: CaptureOwner, val epoch: Int)
enum class NonCallCaptureKind { PREVIEW, CAMERA, VOICE }
data class NonCallCaptureState(val busy: Boolean = false, val revision: Long = 0)

/** App-owned capture only. Incoming presentation must never use [state] as a
 * conference-busy flag. An interrupted hardware owner remains reserved until it
 * acknowledges actual stop/result; permission/registration callbacks cannot revive it. */
class NonCallCaptureCoordinator(
    private val currentSession: () -> CaptureSession?,
    private val callsBusy: () -> Boolean,
) {
    class Lease internal constructor(val id: String, val session: CaptureSession, val kind: NonCallCaptureKind) {
        override fun toString() = "NonCallCaptureLease([owner redacted])"
    }
    private class Entry(val lease: Lease, var stop: () -> Unit, var revoked: Boolean = false, var external: Boolean = false)
    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    private var revision = 0L
    private val mutableState = MutableStateFlow(NonCallCaptureState())
    val state: StateFlow<NonCallCaptureState> = mutableState
    val busy: Boolean get() = synchronized(lock) { entries.isNotEmpty() }
    private fun snapshotLocked() = NonCallCaptureState(entries.isNotEmpty(), ++revision)
    private fun publish(value: NonCallCaptureState) = mutableState.update { old -> if (value.revision > old.revision) value else old }
    private fun stopOutsideLock(stops: List<() -> Unit>) { stops.forEach { runCatching(it) } }

    /** A camera/voice request may retire the visible gallery preview, while its
     * new reservation closes the gap before asynchronous CameraDevice.onClosed. */
    fun acquire(kind: NonCallCaptureKind, session: CaptureSession?, stop: () -> Unit): Lease? {
        val decision = synchronized(lock) {
            if (session == null || session != currentSession() || callsBusy() ||
                entries.values.any { kind == NonCallCaptureKind.PREVIEW || it.lease.kind != NonCallCaptureKind.PREVIEW }) return@synchronized null
            val stops = entries.values.filter { !it.revoked }.map { it.revoked = true; it.stop }
            val lease = Lease(UUID.randomUUID().toString(), session, kind)
            entries[lease.id] = Entry(lease, stop)
            Triple(lease, snapshotLocked(), stops)
        } ?: return null
        publish(decision.second)
        stopOutsideLock(decision.third)
        return decision.first
    }

    fun find(id: String?): Lease? = synchronized(lock) { entries[id]?.lease }
    fun valid(lease: Lease): Boolean = synchronized(lock) {
        val entry = entries[lease.id]
        entry?.lease === lease && !entry.revoked && lease.session == currentSession() && !callsBusy()
    }
    fun ready(lease: Lease): Boolean = synchronized(lock) { valid(lease) && entries.size == 1 }
    suspend fun awaitReady(lease: Lease): Boolean {
        state.first { !valid(lease) || ready(lease) }
        return ready(lease)
    }
    fun isExternal(lease: Lease): Boolean = synchronized(lock) { entries[lease.id]?.takeIf { it.lease === lease }?.external == true }
    fun markExternal(lease: Lease): Boolean {
        val next = synchronized(lock) {
            if (!ready(lease)) return@synchronized null
            entries.getValue(lease.id).external = true
            snapshotLocked()
        } ?: return false
        publish(next)
        return true
    }

    /** Persisted external camera requests survive process recreation. Their
     * actual result is checked by stable owner/origin/device, never a RAM epoch. */
    fun restoreExternal(id: String, owner: CaptureOwner): Lease {
        val decision = synchronized(lock) {
            entries[id]?.let { return it.lease }
            val current = currentSession()
            val session = current?.takeIf { it.owner == owner } ?: CaptureSession(owner, -1)
            val lease = Lease(id, session, NonCallCaptureKind.CAMERA)
            entries[id] = Entry(lease, {}, revoked = current?.owner != owner, external = true)
            lease to snapshotLocked()
        }
        publish(decision.second)
        return decision.first
    }

    fun bindStop(lease: Lease, stop: () -> Unit) {
        val interrupted = synchronized(lock) {
            val entry = entries[lease.id]?.takeIf { it.lease === lease } ?: return
            entry.stop = stop
            entry.revoked
        }
        if (interrupted) stopOutsideLock(listOf(stop))
    }
    fun release(lease: Lease) {
        val next = synchronized(lock) {
            if (entries[lease.id]?.lease !== lease) return@synchronized null
            entries.remove(lease.id)
            snapshotLocked()
        } ?: return
        publish(next)
    }
    fun interrupt(lease: Lease) = interruptWhere { it === lease }
    fun interruptAll() = interruptWhere { true }
    fun interruptStaleSessions() = interruptWhere { it.session != currentSession() }
    private fun interruptWhere(predicate: (Lease) -> Boolean) {
        val decision = synchronized(lock) {
            val stops = entries.values.filter { !it.revoked && predicate(it.lease) }.map { it.revoked = true; it.stop }
            snapshotLocked() to stops
        }
        publish(decision.first)
        stopOutsideLock(decision.second)
    }
    suspend fun stopAndAwaitIdle(timeoutMs: Long = 1_500): Boolean {
        interruptAll()
        return withTimeoutOrNull(timeoutMs) { state.first { !it.busy }; true } ?: false
    }
}

@Serializable
data class PendingExternalCapture(
    val id: String, val owner: CaptureOwner, val uri: String, val video: Boolean, val launched: Boolean = false,
    // Optional, backward-compatible metadata for the app-owned chat camera.
    // External OEM/Avatar requests never set these fields.
    val directSource: String? = null, val destination: String? = null,
    val stage: String = "preview", val outputVideo: Boolean = false,
    val front: Boolean = false, val confirmed: Boolean = false,
) {
    fun acceptsCompletedResult(currentOwner: CaptureOwner?, requestStillPending: Boolean = true): Boolean = launched && requestStillPending && currentOwner == owner
    fun mayLaunch(currentOwner: CaptureOwner?, ramLeaseValid: Boolean): Boolean = !launched && currentOwner == owner && ramLeaseValid
    fun acceptsDirectResult(currentOwner: CaptureOwner?, destination: String, requestStillPending: Boolean): Boolean =
        directSource != null && this.destination == destination && confirmed && stage == "review" &&
            acceptsCompletedResult(currentOwner, requestStillPending)
}

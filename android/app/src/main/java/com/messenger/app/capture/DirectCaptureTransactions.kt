package com.messenger.app.capture

import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Direct writes must distinguish acknowledged disk state from SharedPreferences'
 * immediately updated memory map when Editor.commit() returns false. */
internal class DirectCapturePersistence(
    private val readStored: (String) -> PendingExternalCapture?,
    private val writeStored: (String, PendingExternalCapture?) -> Boolean,
) {
    private val acknowledged = mutableMapOf<String, PendingExternalCapture?>()

    @Synchronized fun read(id: String): PendingExternalCapture? =
        if (acknowledged.containsKey(id)) acknowledged[id] else readStored(id)

    @Synchronized fun replace(expected: PendingExternalCapture?, next: PendingExternalCapture): Boolean {
        if (read(next.id) != expected) return false
        write(next.id, expected, next)
        return true
    }

    @Synchronized fun remove(id: String) { write(id, read(id), null) }
    @Synchronized fun clearAcknowledged() { acknowledged.clear() }
    @Synchronized fun retainedIds(): Set<String> = acknowledged.keys.toSet()

    private fun write(id: String, old: PendingExternalCapture?, next: PendingExternalCapture?) {
        acknowledged[id] = old
        // Keep the acknowledged snapshot on false or exception. Neither a
        // volatile memory mutation nor a reread can turn a failed confirmation
        // into a durable, deliverable result.
        try {
            if (!writeStored(id, next)) throw IOException("Could not save camera state")
        } catch (failure: Exception) {
            // Also restore the backing memory map. Otherwise a later unrelated
            // successful editor commit could persist the failed confirmation.
            // A failed rollback still restores SharedPreferences' memory first;
            // the acknowledged snapshot remains authoritative in this process.
            try { writeStored(id, old) }
            catch (cancel: CancellationException) { if (failure !is CancellationException) throw cancel }
            catch (_: Exception) { /* The original failure is reported below. */ }
            throw failure
        }
        acknowledged.remove(id) // Successful memory and disk state agree again.
    }
}

/** Every activity operation uses this boundary, including failure recovery.
 * Cancellation remains cancellation; storage/runtime errors are shown safely. */
internal suspend fun directCaptureOperation(
    onFailure: (Exception) -> Unit,
    operation: suspend () -> Unit,
) {
    try { operation() }
    catch (cancel: CancellationException) { throw cancel }
    catch (error: Exception) { onFailure(error) }
}

internal fun sameDirectCapture(expected: PendingExternalCapture, current: PendingExternalCapture): Boolean =
    expected.id == current.id && expected.owner == current.owner && expected.uri == current.uri &&
        expected.directSource != null && expected.directSource == current.directSource &&
        expected.destination != null && expected.destination == current.destination

/** Hardware admission may be revoked after the confirmation CAS. It does not
 * revoke a still-pending same-owner explicit confirmation or own its bytes. */
internal fun confirmedDirectCapture(
    expected: PendingExternalCapture?, current: PendingExternalCapture?, owner: CaptureOwner?,
): PendingExternalCapture? = current?.takeIf {
    expected != null && sameDirectCapture(expected, it) &&
        it.acceptsDirectResult(owner, requireNotNull(expected.destination), requestStillPending = true)
}

internal enum class DirectCaptureDisposition { DELIVER_CONFIRMED, PRESERVE, DISCARD_UNCONFIRMED }

internal fun directCaptureDisposition(
    expected: PendingExternalCapture?, current: PendingExternalCapture?, owner: CaptureOwner?, recreating: Boolean,
): DirectCaptureDisposition = when {
    confirmedDirectCapture(expected, current, owner) != null -> DirectCaptureDisposition.DELIVER_CONFIRMED
    recreating || expected == null || current == null || owner != expected.owner ||
        !sameDirectCapture(expected, current) || current.confirmed -> DirectCaptureDisposition.PRESERVE
    else -> DirectCaptureDisposition.DISCARD_UNCONFIRMED
}

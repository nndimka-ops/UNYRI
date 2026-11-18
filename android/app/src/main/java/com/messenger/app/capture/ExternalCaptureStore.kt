package com.messenger.app.capture

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json

/** Non-secret ownership metadata only; never conference invitations or keys. */
class ExternalCaptureStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("non_call_capture_pending", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private fun readStored(id: String): PendingExternalCapture? = preferences.getString("request:$id", null)?.let {
        runCatching { json.decodeFromString(PendingExternalCapture.serializer(), it) }.getOrNull()
    }
    private val directPersistence = DirectCapturePersistence(::readStored) { id, record ->
        val editor = preferences.edit()
        if (record == null) editor.remove("request:$id") else
            editor.putString("request:$id", json.encodeToString(PendingExternalCapture.serializer(), record))
        editor.commit()
    }
    fun get(id: String): PendingExternalCapture? = directPersistence.read(id)
    fun all(): List<PendingExternalCapture> = (preferences.all.keys.filter { it.startsWith("request:") }
        .map { it.removePrefix("request:") } + directPersistence.retainedIds()).distinct().mapNotNull(::get)
    private fun save(record: PendingExternalCapture) {
        check(preferences.edit().putString("request:${record.id}", json.encodeToString(PendingExternalCapture.serializer(), record)).commit())
    }
    fun prepare(coordinator: NonCallCaptureCoordinator, lease: NonCallCaptureCoordinator.Lease, uri: Uri, video: Boolean): Boolean {
        if (uri.scheme != "content" || uri.authority != "${context.packageName}.fileprovider" || !coordinator.ready(lease)) return false
        save(PendingExternalCapture(lease.id, lease.session.owner, uri.toString(), video))
        if (!coordinator.markExternal(lease)) { remove(lease.id); return false }
        // The trampoline, rather than the calling Composable, now owns cleanup.
        coordinator.bindStop(lease) {}
        return true
    }
    fun prepareDirect(
        coordinator: NonCallCaptureCoordinator, lease: NonCallCaptureCoordinator.Lease,
        uri: Uri, source: java.io.File, destination: String, initialVideo: Boolean = false,
    ): Boolean {
        val record = PendingExternalCapture(lease.id, lease.session.owner, uri.toString(), false,
            directSource = source.path, destination = destination, outputVideo = initialVideo)
        if (destination.isBlank() || DirectCaptureFiles.from(context, record) == null || !coordinator.ready(lease)) return false
        if (!directPersistence.replace(null, record)) return false
        if (!coordinator.markExternal(lease)) { remove(lease.id); return false }
        coordinator.bindStop(lease) {} // The capture activity installs the actual hardware stop callback.
        return true
    }

    @Synchronized
    fun replaceDirect(expected: PendingExternalCapture, next: PendingExternalCapture): Boolean {
        if (get(expected.id) != expected || DirectCaptureFiles.from(context, expected) == null ||
            next.id != expected.id || next.owner != expected.owner || next.uri != expected.uri ||
            next.directSource != expected.directSource || next.destination != expected.destination ||
            next.stage !in setOf("preview", "capturing", "review") ||
            (next.confirmed && (next.stage != "review" || !next.launched))) return false
        return directPersistence.replace(expected, next)
    }

    fun markLaunched(record: PendingExternalCapture) = save(record.copy(launched = true))
    fun remove(id: String) {
        if (get(id)?.directSource != null) directPersistence.remove(id)
        else check(preferences.edit().remove("request:$id").commit())
    }
    fun retireAll() {
        all().forEach { record ->
            val uri = Uri.parse(record.uri)
            if (uri.scheme == "content" && uri.authority == "${context.packageName}.fileprovider") {
                context.revokeUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        check(preferences.edit().clear().commit()) { "Could not retire camera requests" }
        directPersistence.clearAcknowledged()
        // The existing owner-scoped PrivateCachePurger removes camera/avatar outputs.
    }
}

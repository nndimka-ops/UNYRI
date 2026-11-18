package com.messenger.app.ui.chats

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.data.directtransfer.*
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class DirectPickRequest(val uris: List<Uri>, val labels: List<String>, val complete: () -> Unit)

internal class DirectOriginalPicker(private val select: (List<Uri>, Boolean, Boolean, () -> Unit) -> Boolean) {
    operator fun invoke(uris: List<Uri>, original: Boolean, asFile: Boolean, cleanup: () -> Unit): Boolean =
        select(uris, original, asFile, cleanup)
}

/** Intercept original selections before the ordinary 100/500MiB importer. */
@Composable
internal fun rememberDirectOriginalSelection(
    chat: String,
    epoch: Int,
    destination: ChatSummary?,
    accountUserId: String?,
    captionRetained: Boolean = false,
    trayOriginals: Set<Uri> = emptySet(),
    onDirectCommitted: (List<Uri>) -> Unit = {},
    onUnavailable: (List<Uri>) -> Unit = {},
    normal: (List<Uri>, Boolean, Long?, () -> Unit) -> Boolean,
): DirectOriginalPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as MessengerApp
    val coordinator = app.directTransfers
    fun currentAccount(): Boolean = filePickerResultIsCurrent(epoch, app.authRepository.currentSessionEpoch(), accountUserId,
        (app.authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id)
    val latestDestination by rememberUpdatedState(destination)
    fun destinationEligible(): Boolean = directFileRecipient(
        directFileDestination(latestDestination, accountUserId, epoch), chat,
        (app.authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id,
        app.authRepository.currentSessionEpoch(),
    ) != null
    val latestNormal by rememberUpdatedState(normal)
    val latestCommitted by rememberUpdatedState(onDirectCommitted)
    val latestUnavailable by rememberUpdatedState(onUnavailable)
    val latestTrayOriginals by rememberUpdatedState(trayOriginals)
    var request by remember(chat, epoch) { mutableStateOf<DirectPickRequest?>(null) }
    var inspecting by remember(chat, epoch) { mutableStateOf(false) }
    val staging by coordinator.staging.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED)
    LaunchedEffect(coordinator) {
        coordinator.errors.collect { reason ->
            val resource = com.messenger.app.ui.transfers.directTransferErrorResource(reason)
            Toast.makeText(context, resource, Toast.LENGTH_LONG).show()
        }
    }
    DisposableEffect(chat, epoch) { onDispose { request?.complete?.invoke(); request = null } }
    request?.let { current ->
        DirectOriginalConsentDialog(current.labels, captionRetained, onConfirm = confirm@{
                request = null
                if (!destinationEligible()) {
                    current.complete()
                    Toast.makeText(context, R.string.direct_destination_unavailable, Toast.LENGTH_LONG).show()
                    return@confirm
                }
                if (!coordinator.offerOriginals(chat, current.uris, epoch, current.complete, { latestCommitted(it) }, consumeComposerDrafts = true)) {
                    current.complete(); Toast.makeText(context, R.string.direct_too_many, Toast.LENGTH_LONG).show()
                }
            }, onDismiss = { request = null; current.complete() })
    }
    return DirectOriginalPicker { uris, original, asFile, cleanup ->
        if (!currentAccount()) { cleanup(); false }
        else if (!original) latestNormal(uris, asFile, null, cleanup)
        else if (request != null || inspecting || chat in staging) false
        else {
            inspecting = true
            scope.launch {
                var transferredCleanup = false
                try {
                    val unavailableSources = mutableListOf<Uri>()
                    val metadata = withContext(Dispatchers.IO) {
                        uris.distinct().mapNotNull { uri ->
                            try {
                            val value = if (uri.scheme == "file") java.io.File(uri.path.orEmpty()).let { it.name to it.length() }
                            else context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                                if (!cursor.moveToFirst()) null to -1L else {
                                    val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                    val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                                    (if (name >= 0 && !cursor.isNull(name)) cursor.getString(name) else null) to (if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size) else -1L)
                                }
                            } ?: (null to -1L)
                            Triple(uri, value.first, value.second)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { unavailableSources += uri; null }
                        }
                    }
                    if (!currentAccount()) throw CancellationException("Account changed")
                    if (unavailableSources.isNotEmpty()) latestUnavailable(unavailableSources)
                    val eligible = destinationEligible()
                    fun route(item: Triple<Uri, String?, Long>) = pickedOriginalRoute(item.third, eligible, item.first in latestTrayOriginals)
                    val direct = metadata.filter { route(it) == DirectOriginalRoute.DIRECT }
                    val regular = metadata.filter { route(it) == DirectOriginalRoute.NORMAL }.map { it.first }
                    val unavailable = metadata.any { route(it) == DirectOriginalRoute.UNAVAILABLE }
                    // An unknown unsupported provider still uses the existing importer,
                    // with its actual stream bounded to 100MiB, including video sources.
                    val sourceLimit = DIRECT_NORMAL_SOURCE_LIMIT.takeIf { !eligible && metadata.any { it.third <= 0 } }
                    if (unavailable) Toast.makeText(context, R.string.direct_destination_unavailable, Toast.LENGTH_LONG).show()
                    if (direct.isEmpty()) {
                        if (regular.isNotEmpty()) transferredCleanup = latestNormal(regular, asFile, sourceLimit, cleanup)
                    } else if (direct.size > 8) {
                        Toast.makeText(context, R.string.direct_too_many, Toast.LENGTH_LONG).show()
                    } else {
                        val branches = AtomicInteger(if (regular.isEmpty()) 1 else 2)
                        val release = { if (branches.decrementAndGet() == 0) cleanup(); Unit }
                        if (regular.isNotEmpty() && !latestNormal(regular, asFile, null, release)) release()
                        request = DirectPickRequest(direct.map { it.first }, direct.map { (uri, name, size) ->
                            (name ?: context.getString(R.string.file_preview)) + if (size >= 0) " · " + com.messenger.app.data.attachment.AttachmentFormat.fileSize(size) else ""
                        }, release)
                        transferredCleanup = true
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { Toast.makeText(context, R.string.direct_prepare_failed, Toast.LENGTH_LONG).show() }
                finally { inspecting = false; if (!transferredCleanup) cleanup() }
            }
            true
        }
    }
}

package com.messenger.app.call

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import com.messenger.app.domain.call.CallController
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.call.CallForegroundServiceTicket
import com.messenger.app.domain.model.CallMediaKind
import com.messenger.app.domain.model.CallInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal data class CallForegroundServiceRequest(val ticket: CallForegroundServiceTicket, val title: String, val foregroundType: Int)

internal fun shouldStopStaleCallService(promoted: CallForegroundServiceTicket?, current: CallForegroundServiceTicket?): Boolean =
    promoted == null || promoted != current

internal fun callForegroundServiceRequest(state: CallUiState, ticket: CallForegroundServiceTicket?, cameraGranted: Boolean): CallForegroundServiceRequest? {
    val media = state.serviceMedia() ?: return null
    val owner = ticket?.takeIf { it.callId == media.first.callId } ?: return null
    val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
        if (media.second && cameraGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
    return CallForegroundServiceRequest(owner, media.first.otherDisplayName, type)
}

private fun CallUiState.serviceMedia(): Pair<CallInfo, Boolean>? = when (this) {
    is CallUiState.Connecting -> call to (call.mediaKind == CallMediaKind.VIDEO)
    // Receiving peer video never grants access to our camera. Only our
    // own active camera intent needs the camera foreground-service type.
    is CallUiState.Connected -> call to localVideoEnabled
    is CallUiState.Reconnecting -> since.serviceMedia()
    else -> null
}

/** Both starting a service and promoting it may be refused by Android.
 * Keep the identity check on both sides of the platform operation: a late
 * refusal from the previous account must not terminate the new call. */
internal fun admitCallForegroundService(
    request: CallForegroundServiceTicket,
    current: () -> CallForegroundServiceTicket?,
    onDenied: (CallForegroundServiceTicket) -> Unit,
    operation: () -> Boolean,
): Boolean {
    if (current() != request) return false
    try {
        if (operation()) return current() == request
    } catch (error: CancellationException) {
        throw error
    } catch (error: RuntimeException) {
        // Includes FGS start/type restrictions and while-in-use SecurityException.
        android.util.Log.w("CallForegroundService", "Call service admission refused: ${error.javaClass.simpleName}")
    }
    if (current() == request) onDenied(request)
    return false
}

/** One identity-bound service request per call/type; Android start admission
 * is a platform boundary, not an uncaught application-scope exception. */
class CallForegroundServiceController internal constructor(
    private val state: StateFlow<CallUiState>,
    private val ticket: () -> CallForegroundServiceTicket?,
    private val scope: CoroutineScope,
    private val cameraGranted: () -> Boolean,
    private val startService: (CallForegroundServiceRequest) -> Boolean,
    private val stopService: () -> Unit,
    private val onDenied: (CallForegroundServiceTicket) -> Unit,
) {
    constructor(context: Context, callController: CallController, scope: CoroutineScope) : this(
        callController.state, callController::foregroundServiceTicket, scope,
        cameraGranted = { ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED },
        startService = { request ->
            val intent = Intent(context, CallForegroundService::class.java)
                .putExtra(CallForegroundService.EXTRA_TITLE, request.title)
                .putExtra(CallForegroundService.EXTRA_FOREGROUND_TYPE, request.foregroundType)
                .putExtra(CallForegroundService.EXTRA_CALL_ID, request.ticket.callId)
                .putExtra(CallForegroundService.EXTRA_GENERATION, request.ticket.generation)
                .putExtra(CallForegroundService.EXTRA_OWNER, request.ticket.owner)
            ContextCompat.startForegroundService(context, intent) != null
        },
        stopService = { context.stopService(Intent(context, CallForegroundService::class.java)); Unit },
        onDenied = { callController.foregroundServiceUnavailable(it); Unit },
    )

    fun start() = scope.launch {
        state.map { snapshot -> callForegroundServiceRequest(snapshot, ticket(), cameraGranted()) }
            .distinctUntilChanged().collect { request ->
            if (request == null) {
                try { stopService() }
                catch (error: CancellationException) { throw error }
                catch (error: RuntimeException) { android.util.Log.w("CallForegroundService", "Call service cleanup refused: ${error.javaClass.simpleName}") }
            } else {
                admitCallForegroundService(request.ticket, ticket, onDenied) { startService(request) }
            }
        }
    }

}

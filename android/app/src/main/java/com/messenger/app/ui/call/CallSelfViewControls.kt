package com.messenger.app.ui.call

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.messenger.app.domain.call.CallUiState

/** Production self-view actions, separate from the native renderer for input contracts. */
@Composable
internal fun CallSelfViewControls(
    state: CallUiState.Connected,
    multipleCameras: Boolean,
    chrome: Boolean,
    liveState: () -> CallUiState,
    hasMultipleCameras: () -> Boolean,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier,
    torch: com.messenger.app.data.call.CallTorchState = com.messenger.app.data.call.CallTorchState(),
    onTorch: () -> Unit = {},
) {
    // Replacement cancels the old pointer sequence, rather than retargeting its UP
    // to the new call's updated onClick. A live guard also covers updates before render.
    key(state.call.callId) {
        // Match iOS: 44dp targets overlap by 8dp, leaving 6dp between the 30dp circles.
        Row(modifier, horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
            if (CallPresentationPolicy.torchVisible(state, torch.available, chrome)) {
                CallTorchButton(torch.on, onClick = {
                    if (CallPresentationPolicy.canTorch(state.call.callId, liveState(), torch.available)) onTorch()
                })
            }
            if (CallPresentationPolicy.flipVisible(state, multipleCameras, chrome)) {
                CallFlipCameraButton(onClick = {
                    if (CallPresentationPolicy.canFlip(state.call.callId, liveState(), hasMultipleCameras())) onFlip()
                })
            }
        }
    }
}

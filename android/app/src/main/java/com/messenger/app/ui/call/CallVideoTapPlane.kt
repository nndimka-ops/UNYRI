package com.messenger.app.ui.call

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.zIndex
import com.messenger.app.R

/** Actual video background input layer. Visible alert chrome must remain above it. */
@Composable
internal fun CallVideoTapPlane(owner: String, chromeHidden: Boolean, canToggle: Boolean, canScale: Boolean,
    onToggle: () -> Unit, onScale: () -> Unit) {
    val toggle by rememberUpdatedState(onToggle)
    val scale by rememberUpdatedState(onScale)
    val toggleLabel = stringResource(if (chromeHidden) R.string.call_show_controls else R.string.call_hide_controls)
    val scaleLabel = stringResource(R.string.call_video_fit)
    Box(Modifier.fillMaxSize().testTag("call-video-background").zIndex(if (chromeHidden) 1f else 0f)
        .then(if (canToggle) Modifier.semantics {
            contentDescription = toggleLabel
            onClick(label = toggleLabel) { toggle(); true }
            if (canScale) customActions = listOf(CustomAccessibilityAction(scaleLabel) { scale(); true })
        } else Modifier)
        .pointerInput(owner, canToggle, canScale) {
            if (canToggle) detectTapGestures(onTap = { toggle() }, onDoubleTap = { if (canScale) scale() })
        })
}

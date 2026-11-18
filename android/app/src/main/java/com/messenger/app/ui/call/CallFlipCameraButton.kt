package com.messenger.app.ui.call

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoIcons

/** 30dp circle inset 7dp; 44dp hit target occupies the tile corner. */
@Composable
internal fun CallFlipCameraButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.Transparent,
        modifier = modifier.size(44.dp).testTag("call-flip")) {
        Box(contentAlignment = Alignment.Center) {
            Surface(Modifier.size(30.dp), shape = CircleShape, color = CallGlass, border = BorderStroke(1.dp, CallEdge)) {
                Icon(CocoIcons.SwitchCamera, stringResource(R.string.call_switch_camera), Modifier.padding(7.dp), tint = CallWhite)
            }
        }
    }
}

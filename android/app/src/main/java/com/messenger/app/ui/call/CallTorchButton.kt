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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.messenger.app.R

private val TorchBolt = ImageVector.Builder("CallTorch", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(13f, 2f); lineTo(4f, 14f); lineTo(11f, 14f)
        lineTo(10f, 22f); lineTo(20f, 9f); lineTo(13f, 9f); close()
    }
}.build()

/** Same 30dp glass circle / 44dp target as flip; lit = light circle, dark bolt. */
@Composable
internal fun CallTorchButton(on: Boolean, onClick: () -> Unit) {
    val state = stringResource(if (on) R.string.call_torch_on else R.string.call_torch_off)
    Surface(onClick = onClick, shape = CircleShape, color = Color.Transparent,
        modifier = Modifier.size(44.dp).testTag("call-torch").semantics { stateDescription = state }) {
        Box(contentAlignment = Alignment.Center) {
            Surface(Modifier.size(30.dp), shape = CircleShape,
                color = if (on) CallWhite else CallGlass,
                border = if (on) null else BorderStroke(1.dp, CallEdge)) {
                Icon(TorchBolt, stringResource(R.string.call_torch), Modifier.padding(7.dp),
                    tint = if (on) Color(0xFF121212) else CallWhite)
            }
        }
    }
}

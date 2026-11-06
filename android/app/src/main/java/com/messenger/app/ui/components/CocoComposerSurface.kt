package com.messenger.app.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.messenger.app.ui.theme.LocalCocoColors

/** Android's lightweight tinted Material surface; no capture, shader or backdrop blur. */
@Composable
internal fun CocoComposerSurface(
    savedField: Color,
    background: Color,
    modifier: Modifier = Modifier,
    solidFallback: Boolean = false,
    surfaceStyle: CocoComposerSurfaceStyle? = null,
    content: @Composable () -> Unit,
) {
    val surface = surfaceStyle ?: rememberCocoComposerSurfaceStyle(savedField, background, solidFallback)
    Surface(modifier = modifier, shape = RoundedCornerShape(22.dp), color = surface.fill,
        contentColor = LocalCocoColors.current.primaryText, tonalElevation = 0.dp,
        shadowElevation = 0.dp, border = surface.border, content = content)
}

internal data class CocoComposerSurfaceStyle(val fill: Color, val border: BorderStroke?)

/** Shared by the input and attachment menu; saved RGBA is resolved exactly once. */
@Composable
internal fun rememberCocoComposerSurfaceStyle(
    savedField: Color,
    background: Color,
    solidFallback: Boolean = false,
): CocoComposerSurfaceStyle {
    val lowPower = rememberComposerPowerSave()
    val usesDefault = savedField == Color(0xCC000000)
    // 90% dark tint over black resolves to #131313. Custom saved RGBA is untouched.
    val tint = if (usesDefault) Color(0xE6151515) else savedField
    val solid = solidFallback || lowPower
    return CocoComposerSurfaceStyle(
        fill = if (solid) tint.compositeOver(background.copy(alpha = 1f)) else tint,
        border = if (!solid && usesDefault) BorderStroke(.5.dp, Color.White.copy(alpha = .08f)) else null,
    )
}

/** One public power-state listener, scoped to a visible composer and released on disposal. */
@Composable
private fun rememberComposerPowerSave(): Boolean {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val power = remember(context) { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var enabled by remember(power) { mutableStateOf(power.isPowerSaveMode) }
    DisposableEffect(context, power, lifecycle) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { enabled = power.isPowerSaveMode }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = power.isPowerSaveMode
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycle.addObserver(observer)
        onDispose { context.unregisterReceiver(receiver); lifecycle.removeObserver(observer) }
    }
    return enabled
}

/** Native 48dp target around a compact 36dp action. Text and callbacks stay with the composer. */
@Composable
internal fun CocoComposerIconButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    fill: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
        Box(Modifier.size(36.dp).then(if (fill != null) Modifier.background(fill, CircleShape) else Modifier),
            contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = tint.copy(alpha = if (enabled) 1f else .4f), modifier = Modifier.size(20.dp))
        }
    }
}

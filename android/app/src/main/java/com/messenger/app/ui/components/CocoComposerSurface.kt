package com.messenger.app.ui.components

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.theme.LocalGlassBackdrop
import com.messenger.app.ui.theme.GlassBackdrop
import com.messenger.app.ui.theme.frostedGlass

/** One material behind the input and tray. The recorded timeline excludes controls. */
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
    CompositionLocalProvider(LocalContentColor provides LocalCocoColors.current.primaryText) {
        Box(modifier.cocoChromeMaterial(RoundedCornerShape(22.dp), surface)) { content() }
    }
}

internal data class CocoComposerSurfaceStyle(
    /** Complete material overlay when sampling; opaque panel color otherwise. */
    val fill: Color,
    val border: BorderStroke?,
    val backdropEnabled: Boolean = false,
    val background: Color = Color.Black,
    /** Popups and unavailable backdrop sources must never render only a translucent tint. */
    val opaqueFill: Color = fill.compositeOver(background.copy(alpha = 1f)),
)

/**
 * Dark material under the saved tint. Blur alone leaves white media white.
 * A light tint over a restrained dark veil keeps the backdrop visible and
 * bounds the default panel's brightness even above an all-white photo, including
 * enough contrast for the secondary/placeholder ink as well as white controls.
 * This is an Android material approximation, not an iOS system-material token.
 */
internal fun darkChromeMaterialFill(tint: Color): Color =
    tint.compositeOver(Color.Black.copy(alpha = .82f))

/** May also be supplied by a host with an explicit Reduce Transparency preference. */
internal data class CocoChromePolicy(
    val active: Boolean,
    val lowPower: Boolean,
    val increasedContrast: Boolean,
    val reduceTransparency: Boolean = false,
) {
    val solid: Boolean get() = !active || lowPower || increasedContrast || reduceTransparency
    fun samplesBackdrop(supported: Boolean): Boolean = supported && !solid
}

internal val LocalCocoChromePolicy = staticCompositionLocalOf<CocoChromePolicy?> { null }
internal val LocalCocoChromeBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** Resolve saved RGBA once, including the iOS default-white 8-bit alpha override. */
internal fun resolveComposerSurfaceStyle(
    savedField: Color, background: Color, samplesBackdrop: Boolean,
): CocoComposerSurfaceStyle {
    val usesDefault = savedField == Color(0xCC000000)
    val tint = if (usesDefault) Color(0x24FFFFFF) else savedField
    val opaqueBackground = background.copy(alpha = 1f)
    val opaqueFill = tint.compositeOver(opaqueBackground)
    return CocoComposerSurfaceStyle(
        fill = if (samplesBackdrop) darkChromeMaterialFill(if (usesDefault) Color.White.copy(alpha = .045f) else tint) else opaqueFill,
        border = if (samplesBackdrop) BorderStroke(.5.dp, Color.White.copy(alpha = .10f)) else null,
        backdropEnabled = samplesBackdrop,
        background = opaqueBackground,
        opaqueFill = opaqueFill,
    )
}

@Composable
internal fun rememberCocoComposerSurfaceStyle(
    savedField: Color,
    background: Color,
    solidFallback: Boolean = false,
): CocoComposerSurfaceStyle {
    val policy = rememberCocoChromePolicy()
    val samples = !solidFallback && policy.samplesBackdrop(Build.VERSION.SDK_INT >= 31) &&
        (LocalCocoChromeBackdrop.current ?: LocalGlassBackdrop.current)?.blurEnabled == true
    return remember(savedField, background, samples) { resolveComposerSurfaceStyle(savedField, background, samples) }
}

/**
 * The chat header's island material: the name capsule, the call/options capsule
 * and the app-wide Back circle share one look (owner, 17 Nov: the Back circle
 * must not differ from the capsule with the call and settings buttons).
 */
@Composable
internal fun rememberHeaderChromeStyle(): CocoComposerSurfaceStyle {
    val policy = rememberCocoChromePolicy()
    val samples = policy.samplesBackdrop(Build.VERSION.SDK_INT >= 31) &&
        (LocalCocoChromeBackdrop.current ?: LocalGlassBackdrop.current)?.blurEnabled == true
    return remember(samples) { headerChromeStyle(samples) }
}

internal fun headerChromeStyle(samplesBackdrop: Boolean): CocoComposerSurfaceStyle {
    val opaqueFill = Color(0xFF202020).copy(alpha = .92f).compositeOver(Color.Black)
    return CocoComposerSurfaceStyle(
        fill = if (samplesBackdrop) darkChromeMaterialFill(Color.White.copy(alpha = .045f)) else opaqueFill,
        border = BorderStroke(.5.dp, Color.White.copy(alpha = if (samplesBackdrop) .10f else .08f)),
        backdropEnabled = samplesBackdrop,
        opaqueFill = opaqueFill,
    )
}

/** Event-driven settings only; no frame clock, polling, bitmap readback or idle animation. */
@Composable
internal fun rememberCocoChromePolicy(): CocoChromePolicy {
    LocalCocoChromePolicy.current?.let { return it }
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    val uiMode = remember(context) { context.getSystemService(UiModeManager::class.java) }
    fun snapshot() = CocoChromePolicy(
        active = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        lowPower = power?.isPowerSaveMode == true,
        increasedContrast = Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) != 0 ||
            (Build.VERSION.SDK_INT >= 34 && (uiMode?.contrast ?: 0f) > 0f),
    )
    var policy by remember(context, lifecycle) { mutableStateOf(snapshot()) }
    DisposableEffect(context, lifecycle) {
        fun refresh() { policy = snapshot() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refresh()
        }
        val observer = LifecycleEventObserver { _, _ -> refresh() }
        val contrastObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        val contrastListener = if (Build.VERSION.SDK_INT >= 34) UiModeManager.ContrastChangeListener { refresh() } else null
        if (Build.VERSION.SDK_INT >= 34 && contrastListener != null) uiMode?.addContrastChangeListener(ContextCompat.getMainExecutor(context), contrastListener)
        context.contentResolver.registerContentObserver(Settings.Secure.getUriFor("high_text_contrast_enabled"), false, contrastObserver)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycle.addObserver(observer)
        refresh()
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
            context.contentResolver.unregisterContentObserver(contrastObserver)
            if (Build.VERSION.SDK_INT >= 34 && contrastListener != null) uiMode?.removeContrastChangeListener(contrastListener)
        }
    }
    return policy
}

/** Shared panel-sized frost; solid gates allocate no effect layer. */
internal fun Modifier.cocoChromeMaterial(shape: Shape, style: CocoComposerSurfaceStyle): Modifier = composed {
    this.frostedGlass(shape, LocalCocoChromeBackdrop.current ?: LocalGlassBackdrop.current,
        style.fill, style.opaqueFill, enabled = style.backdropEnabled)
        .then(if (!style.backdropEnabled) style.border?.let { Modifier.border(it, shape) } ?: Modifier else Modifier)
}

/** 44dp layout footprint; Compose expands the hit region to its native 48dp minimum. */
@Composable
internal fun CocoComposerIconButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    fill: Color? = null,
    glyphSize: Dp = 20.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(modifier.size(44.dp).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp).then(if (fill != null) Modifier.background(fill, CircleShape) else Modifier),
            contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = tint.copy(alpha = if (enabled) 1f else .4f), modifier = Modifier.size(glyphSize))
        }
    }
}

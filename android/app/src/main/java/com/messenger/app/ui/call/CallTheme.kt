package com.messenger.app.ui.call

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import com.messenger.app.ui.theme.CocoTheme
import com.messenger.app.ui.theme.ThemeMode
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.theme.CocoDarkColors
import androidx.compose.runtime.CompositionLocalProvider

/** Full-screen media controls use a dark palette even when the surrounding app is light. */
@Composable
internal fun CallTheme(manageSystemBars: Boolean = true, content: @Composable () -> Unit) {
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(window, manageSystemBars) {
        val controller = window?.takeIf { manageSystemBars }?.let { WindowInsetsControllerCompat(it, it.decorView) }
        val lightStatus = controller?.isAppearanceLightStatusBars
        val lightNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (lightStatus != null) controller.isAppearanceLightStatusBars = lightStatus
            if (lightNavigation != null) controller.isAppearanceLightNavigationBars = lightNavigation
        }
    }
    CocoTheme(mode = ThemeMode.DARK) {
        CompositionLocalProvider(LocalCocoColors provides CocoDarkColors, content = content)
    }
}

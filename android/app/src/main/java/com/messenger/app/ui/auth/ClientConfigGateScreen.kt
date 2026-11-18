package com.messenger.app.ui.auth

import android.animation.ValueAnimator
import android.content.Context
import android.os.PowerManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.data.config.ClientConfigGate
import com.messenger.app.ui.theme.CocoScreenSurface
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.theme.glassPanel
import kotlinx.coroutines.launch

@Composable
private fun updateSurface(corner: Int): Modifier {
    val context = LocalContext.current
    val lowPower = remember { context.getSystemService(PowerManager::class.java).isPowerSaveMode }
    val shape = RoundedCornerShape(corner.dp)
    val colors = LocalCocoColors.current
    val background = if (lowPower) Modifier.background(colors.glassFloating, shape) else Modifier.glassPanel(shape)
    return background.border(0.5.dp, colors.outlineSecondary, shape)
}

@Composable
fun UpdateRequiredScreen(gate: ClientConfigGate, requirement: ClientConfigGate.Requirement, accountEscape: @Composable () -> Unit) {
    val colors = LocalCocoColors.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val simple = remember { !ValueAnimator.areAnimatorsEnabled() || context.getSystemService(PowerManager::class.java).isPowerSaveMode }
    var appeared by remember(requirement) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (appeared) 1f else 0f, tween(if (simple) 200 else 280), label = "update-card-alpha")
    val scale by animateFloatAsState(if (appeared || simple) 1f else 0.98f, tween(280), label = "update-card-scale")
    var checking by remember(requirement) { mutableStateOf(false) }
    var note by remember(requirement) { mutableStateOf<Int?>(null) }
    val title = stringResource(R.string.login_update_title)
    LaunchedEffect(requirement) { appeared = true }
    CocoScreenSurface(Modifier.fillMaxSize().semantics { paneTitle = title }) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).heightIn(min = maxHeight),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(vertical = 20.dp)
                    .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale }
                    .then(updateSurface(28)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.primaryText, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.login_update_required_detail, requirement.host), color = colors.secondaryText, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.login_update_versions, requirement.current, requirement.minimum), style = MaterialTheme.typography.bodySmall, color = colors.veryMutedText, textAlign = TextAlign.Center)
                    if (requirement.updateUrl != null) {
                        Button(onClick = { runCatching { uriHandler.openUri(requirement.updateUrl) } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.login_update_action)) }
                    } else Text(stringResource(R.string.login_update_store), color = colors.secondaryText, textAlign = TextAlign.Center)
                    TextButton(enabled = !checking, onClick = {
                        checking = true; note = null
                        scope.launch {
                            try {
                                val outcome = gate.recheck()
                                note = if (outcome == ClientConfigGate.Outcome.UNREACHABLE) R.string.login_update_network else R.string.login_update_still_required
                            } finally { checking = false }
                        }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                        if (checking) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Text(stringResource(R.string.login_update_check))
                    }
                    note?.let { Text(stringResource(it), color = colors.secondaryText, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    HorizontalDivider(color = colors.outlineSecondary)
                    Text(stringResource(R.string.login_update_other_account), color = colors.secondaryText, style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth().semantics { heading() })
                    accountEscape()
                }
            }
        }
    }
}

@Composable
fun UpdateRequiredNotice(requirement: ClientConfigGate.Requirement) {
    val uriHandler = LocalUriHandler.current
    val colors = LocalCocoColors.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp).then(updateSurface(18)).padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.login_update_signed_out, requirement.host), modifier = Modifier.weight(1f), color = colors.primaryText, style = MaterialTheme.typography.bodySmall)
        requirement.updateUrl?.let { url -> TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text(stringResource(R.string.login_update_action)) } }
    }
}

@Composable
fun UpdateRecommendationBanner(gate: ClientConfigGate, recommendation: ClientConfigGate.Recommendation?, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val colors = LocalCocoColors.current
    val reduceMotion = remember { !ValueAnimator.areAnimatorsEnabled() }
    var last by remember { mutableStateOf(recommendation) }
    if (recommendation != null) last = recommendation
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = recommendation != null
    AnimatedVisibility(visibleState = visible, modifier = modifier.statusBarsPadding(),
        enter = if (reduceMotion) fadeIn(tween(200)) else fadeIn(tween(200)) + slideInVertically(spring(dampingRatio = 0.9f, stiffness = 385f)) { -it },
        exit = if (reduceMotion) fadeOut(tween(200)) else fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).widthIn(max = 560.dp).fillMaxWidth().then(updateSurface(18)).padding(start = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.login_update_available), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.primaryText)
            last?.updateUrl?.let { url -> TextButton(onClick = { runCatching { uriHandler.openUri(url) }; gate.dismissRecommendation() }) { Text(stringResource(R.string.login_update_action)) } }
            IconButton(onClick = gate::dismissRecommendation, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Close, stringResource(R.string.login_update_later), tint = colors.secondaryText) }
        }
    }
}

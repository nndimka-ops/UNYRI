package com.messenger.app.ui.admin

import com.messenger.app.ui.theme.cocoConversationBackground

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.CocoShapes
import com.messenger.app.ui.theme.CocoTouchTarget
import com.messenger.app.ui.theme.LocalCocoColors

// ---------------------------------------------------------------------------
// Admin UX Pack 1 - shared building blocks.
//
// Small, purpose-built set (not a design system): screen shells, the four
// mandatory states (loading / content / empty / error), section headers and
// action rows. Everything consumes COCO theme tokens only.
// ---------------------------------------------------------------------------

/** Retains only the HTTP status; never a response body or authentication material. */
internal class AdminHttpFailure(val status: Int) : Exception("Admin HTTP $status")

/** 200 ms - inside the 150-250 ms band requested for admin transitions. */
internal const val AdminTransitionMs = 200

/** Debounce for the user search field. */
internal const val AdminSearchDebounceMs = 350L

@Composable
private fun AdminTopBar(title: String, onBack: (() -> Unit)?, trailing: (@Composable () -> Unit)?) {
    val colors = LocalCocoColors.current
    com.messenger.app.ui.components.CocoPageHeader(
        title = title,
        modifier = Modifier.fillMaxWidth().padding(horizontal = CocoSpacing.sm, vertical = CocoSpacing.sm),
        titleFontWeight = FontWeight.SemiBold,
        start = {
            if (onBack != null) com.messenger.app.ui.components.CocoBackButton(onClick = onBack, label = stringResource(R.string.admin_back))
        },
        end = { trailing?.invoke() },
    )
}

/** Screen shell with a compact top bar and a scrolling body (forms, details). */
@Composable
internal fun AdminScrollingScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalCocoColors.current
    Column(Modifier.fillMaxSize().cocoConversationBackground(colors)) {
        AdminTopBar(title, onBack, trailing)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CocoSpacing.lg)
                .padding(bottom = CocoSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(CocoSpacing.md),
        ) { content() }
    }
}

/** Screen shell for list screens: the body owns its own LazyColumn. */
@Composable
internal fun AdminListScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalCocoColors.current
    Column(Modifier.fillMaxSize().cocoConversationBackground(colors)) {
        AdminTopBar(title, onBack, trailing)
        Box(Modifier.fillMaxSize()) { content() }
    }
}

/** Animated switch between loading / content / empty / error. */
@Composable
internal fun <T> AdminStateBox(stateKey: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    Crossfade(targetState = stateKey, animationSpec = tween(AdminTransitionMs), label = "admin-state", modifier = modifier) { targetState ->
        content(targetState)
    }
}

@Composable
internal fun AdminLoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(CocoSpacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CocoSpacing.md),
    ) {
        CircularProgressIndicator(color = LocalCocoColors.current.accent)
        Text(
            stringResource(R.string.admin_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalCocoColors.current.secondaryText,
        )
    }
}

@Composable
internal fun AdminEmptyState(message: String, modifier: Modifier = Modifier) {
    val colors = LocalCocoColors.current
    Column(
        modifier = modifier.fillMaxWidth().padding(CocoSpacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CocoSpacing.sm),
    ) {
        Icon(CocoIcons.Logo, contentDescription = null, tint = colors.veryMutedText, modifier = Modifier.size(32.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText)
    }
}

/** Renders only localized failure copy plus an explicit diagnostic stage token. */
@Composable
internal fun AdminErrorState(
    failure: AdminPresentation.Failure,
    diagnostic: String? = null,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val colors = LocalCocoColors.current
    Column(
        modifier = modifier.fillMaxWidth().padding(CocoSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CocoSpacing.sm),
    ) {
        Text(
            stringResource(R.string.admin_error_title),
            style = MaterialTheme.typography.titleMedium,
            color = colors.danger,
        )
        Text(stringResource(failure.message), style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText)
        diagnostic?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = colors.veryMutedText)
        }
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry), color = colors.accentSoft) }
        }
    }
}

@Composable
internal fun AdminSectionHeader(title: String, subtitle: String? = null) {
    val colors = LocalCocoColors.current
    Column(Modifier.fillMaxWidth().padding(top = CocoSpacing.sm)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = colors.secondaryText,
            fontWeight = FontWeight.SemiBold,
        )
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.veryMutedText)
        }
    }
}

@Composable
internal fun AdminCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalCocoColors.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CocoShapes.radiusCard,
        colors = CardDefaults.cardColors(containerColor = colors.elevated),
        border = BorderStroke(1.dp, colors.outlineSubtle),
    ) {
        Column(Modifier.padding(CocoSpacing.md), verticalArrangement = Arrangement.spacedBy(CocoSpacing.sm)) { content() }
    }
}

/** Tappable row for dashboard quick actions. */
@Composable
internal fun AdminActionRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LocalCocoColors.current
    val tint = when {
        !enabled -> colors.veryMutedText
        destructive -> colors.danger
        else -> colors.primaryText
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = CocoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CocoSpacing.md),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = if (destructive) colors.danger else colors.accentSoft)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint, fontWeight = FontWeight.Medium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.mutedText) }
        }
    }
}

/** Compact summary tile. `value == null` renders an explicit "unavailable" mark. */
@Composable
internal fun AdminMetricCard(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalCocoColors.current
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = CocoShapes.radiusMedium,
        color = colors.elevated,
        border = BorderStroke(1.dp, colors.outlineSubtle),
    ) {
        Column(Modifier.padding(CocoSpacing.md), verticalArrangement = Arrangement.spacedBy(CocoSpacing.xs)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.mutedText, maxLines = 2)
            Text(
                value ?: stringResource(R.string.admin_metric_unavailable),
                style = MaterialTheme.typography.titleLarge,
                color = if (value == null) colors.veryMutedText else colors.primaryText,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
internal fun AdminStatusChip(text: String, tone: Color) {
    Surface(shape = CocoShapes.pill, color = tone.copy(alpha = 0.16f)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = CocoSpacing.sm, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = tone,
        )
    }
}

@Composable
internal fun AdminKeyValue(label: String, value: String, monospace: Boolean = false) {
    val colors = LocalCocoColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CocoSpacing.md)) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = colors.mutedText,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.primaryText,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Destructive / privileged confirmation. Disabled until the caller allows it. */
@Composable
internal fun AdminConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean,
    enabled: Boolean,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = LocalCocoColors.current
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(CocoSpacing.md)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                content?.invoke(this)
            }
        },
        confirmButton = {
            TextButton(
                enabled = enabled && !busy,
                onClick = onConfirm,
            ) { Text(confirmLabel, color = if (destructive) colors.danger else colors.accentSoft) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Thin divider sized to the theme instead of Material's default height. */
@Composable
internal fun AdminDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalCocoColors.current.divider))
}

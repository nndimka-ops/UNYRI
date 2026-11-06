package com.messenger.app.ui.calls

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.LocalCocoColors
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size

/** Calls root tab (Phase 8). There is no call-history API or local event log
 *  yet, so this is a truthful shell — never a fake history list. Call
 *  functionality is entirely unaffected by this screen. */
@Composable
fun CallsScreen(modifier: Modifier = Modifier) {
    val colors = LocalCocoColors.current
    com.messenger.app.ui.theme.CocoScreenSurface(modifier = modifier.fillMaxSize(), color = colors.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(CocoSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(CocoSpacing.xxxl))
            Text(
                text = stringResource(R.string.calls_title),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.primaryText,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = CocoIcons.AudioCall,
                contentDescription = null,
                tint = colors.mutedText,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(CocoSpacing.lg))
            Text(
                text = stringResource(R.string.calls_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.primaryText,
            )
            Spacer(modifier = Modifier.height(CocoSpacing.xs))
            Text(
                text = stringResource(R.string.calls_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.mutedText,
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

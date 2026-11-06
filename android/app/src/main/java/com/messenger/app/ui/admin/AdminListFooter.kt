package com.messenger.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.LocalCocoColors

// ---------------------------------------------------------------------------
// Admin Product Pack 2.1 - shared list footer.
//
// Every paged administration list ends the same way: an explicit "load more", an
// in-progress indicator for the next page, a recoverable failure that keeps the
// rows already loaded, and an explicit end marker. Nothing ever downloads the
// whole table silently.
// ---------------------------------------------------------------------------
@Composable
internal fun AdminListFooter(
    state: AdminLoadState,
    hasMore: Boolean,
    failed: AdminPresentation.Failure?,
    rowCount: Int,
    enabled: Boolean,
    onLoadMore: () -> Unit,
) {
    val colors = LocalCocoColors.current
    when {
        failed != null -> Column(
            modifier = Modifier.fillMaxWidth().padding(CocoSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CocoSpacing.sm),
        ) {
            // The already-loaded rows stay on screen; only the next page failed.
            Text(
                stringResource(failed.message),
                style = MaterialTheme.typography.bodySmall,
                color = colors.danger,
            )
            TextButton(enabled = enabled, onClick = onLoadMore) {
                Text(stringResource(R.string.admin_retry))
            }
        }

        state == AdminLoadState.LoadingMore -> Column(
            modifier = Modifier.fillMaxWidth().padding(CocoSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CocoSpacing.sm),
        ) {
            CircularProgressIndicator()
            Text(
                stringResource(R.string.admin_loading_more),
                style = MaterialTheme.typography.bodySmall,
                color = colors.mutedText,
            )
        }

        hasMore -> TextButton(
            modifier = Modifier.fillMaxWidth().padding(CocoSpacing.sm),
            enabled = enabled,
            onClick = onLoadMore,
        ) {
            Text(stringResource(R.string.admin_load_more))
        }

        rowCount > 0 -> Text(
            stringResource(R.string.admin_list_end),
            modifier = Modifier.fillMaxWidth().padding(CocoSpacing.lg),
            style = MaterialTheme.typography.bodySmall,
            color = colors.veryMutedText,
        )

        else -> Unit
    }
}

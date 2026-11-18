package com.messenger.app.ui.auth

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.messenger.app.data.auth.SavedLocalAccount
import com.messenger.app.domain.model.DisplayNamePolicy

/** This row is above the auth destinations, so it owns their physical top inset. */
@Composable
internal fun SavedAccountReturnRow(
    accounts: List<SavedLocalAccount>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    insets: WindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
) {
    Row(modifier.fillMaxWidth().windowInsetsPadding(insets).horizontalScroll(rememberScrollState())) {
        accounts.forEach { account ->
            TextButton(onClick = { onSelect(account.key) }) {
                Text(DisplayNamePolicy.display(account.displayName, "UNYRI"))
            }
        }
    }
}

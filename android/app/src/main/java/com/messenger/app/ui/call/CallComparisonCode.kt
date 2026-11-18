package com.messenger.app.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.domain.call.CallSasHandshake

@Composable internal fun CallComparisonCode(snapshot: CallChromeSnapshot) {
    val code = snapshot.comparisonCode?.collectAsState()?.value
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (code == null) Text(stringResource(R.string.hostile_call_unverified))
        else {
            val names = stringArrayResource(R.array.hostile_sas_names)
            Text(code.joinToString("  ") { CallSasHandshake.symbols[it] }, style = MaterialTheme.typography.headlineMedium)
            Text(code.joinToString(" · ") { names[it] })
            Text(stringResource(R.string.hostile_call_compare))
        }
    }
}

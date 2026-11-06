package com.messenger.app.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Public server card. Unauthenticated by design: the server exposes only the
// operator-provided fields and never statistics, addresses or secrets.
// Unchanged by ADMIN UX PACK 1 apart from living in its own file.
// ---------------------------------------------------------------------------
private val publicCardFields = listOf(
    "name" to R.string.manage_name,
    "operator" to R.string.manage_operator,
    "contact" to R.string.manage_contact,
    "rules" to R.string.manage_rules,
    "retention" to R.string.manage_retention,
    "max_file_bytes" to R.string.manage_file,
    "user_quota_bytes" to R.string.manage_quota,
)

@Composable
fun ServerCard(serverAddress: String? = null, onClose: () -> Unit) {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    val base = remember(serverAddress) {
        serverAddress ?: com.messenger.app.data.api.ServerEndpoint.resolve(com.messenger.app.BuildConfig.API_BASE_URL)
    }
    LaunchedEffect(attempt) {
        failed = false
        try {
            data = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val client = okhttp3.OkHttpClient.Builder()
                    .followRedirects(false).followSslRedirects(false)
                    .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
                client.newCall(okhttp3.Request.Builder().url(base.trimEnd('/') + "/server-info").build()).execute().use {
                    check(it.isSuccessful)
                    val source = it.body?.source() ?: error("empty")
                    source.request(65537)
                    check(source.buffer.size <= 65536)
                    JSONObject(source.buffer.readUtf8())
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.manage_card)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(base, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.manage_claim))
                if (failed) Text(stringResource(R.string.manage_unavailable))
                else if (data == null) CircularProgressIndicator()
                data?.let { card ->
                    publicCardFields.forEach { (key, label) ->
                        Text(stringResource(label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                        Text(card.optString(key).ifBlank { "—" })
                    }
                }
            }
        },
        confirmButton = { com.messenger.app.ui.settings.SettingsDialogButton(onClick = onClose) { Text(stringResource(R.string.admin_close)) } },
        dismissButton = { if (failed) com.messenger.app.ui.settings.SettingsDialogButton(onClick = { attempt++ }) { Text(stringResource(R.string.retry)) } },
    )
}

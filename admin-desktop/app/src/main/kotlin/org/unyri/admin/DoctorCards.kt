package org.unyri.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import org.unyri.admin.core.*
import org.unyri.desktop.design.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class DoctorViewData(val addresses: ConnectionDoctorAddresses?, val capturedAt: Instant,
    val activeOrigin: String? = null, val serverName: String? = null, val error: String? = null)

@Composable fun DoctorCards(m: AdminModel, data: DoctorViewData?, loading: Boolean = false) {
    val t = m.strings
    val clipboard = LocalClipboardManager.current
    val snapshot = remember(data, m.language) {
        data?.let {
            val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(Locale.forLanguageTag(m.language)).withZone(ZoneId.systemDefault())
            ConnectionDoctorPresentation.make(it.addresses, it.activeOrigin, it.serverName, it.capturedAt, formatter::format)
        }
    }
    fun copy(value: String) {
        runCatching { clipboard.setText(AnnotatedString(value)) }
            .onSuccess { m.toast = "Address copied." }.onFailure { m.toast = "Could not copy the address. Select and copy the text." }
    }
    @Composable fun rows(values: List<ConnectionDoctorPresentation.Row>) {
        SelectionContainer { Column { values.forEach { Text(t.doctor(it)) } } }
    }
    GlassCard(Modifier.fillMaxWidth(), m.glass) {
        Text(t["Server addresses"], style = MaterialTheme.typography.titleLarge)
        when {
            loading -> Text(t["Loading addresses…"])
            data?.error != null -> Text(t[data.error])
            snapshot != null -> {
                Text(t["From the internet"], style = MaterialTheme.typography.titleMedium)
                rows(snapshot.internet)
                snapshot.publicCopy?.let { OutlinedButton({ copy(it) }) { Text(t["Copy internet address"]) } }
                Text(t["On your home network"], style = MaterialTheme.typography.titleMedium)
                rows(snapshot.home)
                snapshot.homeCopy?.let { OutlinedButton({ copy(it) }) { Text(t["Copy home address"]) } }
            }
            else -> Text(t["Loading addresses…"])
        }
    }
    GlassCard(Modifier.fillMaxWidth(), m.glass) {
        Text(t["How you are connected"], style = MaterialTheme.typography.titleLarge)
        when {
            loading -> Text(t["Loading addresses…"])
            data?.error != null -> Text(t[data.error])
            snapshot != null -> rows(snapshot.connection)
            else -> Text(t["The connection path is not yet known."])
        }
    }
}

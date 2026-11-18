package com.messenger.app.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.messenger.app.data.call.WebRtcCallStats
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.messenger.app.R

/** The same 1 Hz read as the diagnostic events; opening this sheet never polls WebRTC. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallStatisticsSheet(stats: WebRtcCallStats, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) { CallStatisticsContent(stats) }
    }
}

@Composable
internal fun CallStatisticsContent(stats: WebRtcCallStats) {
    val m = stats.metrics
    fun number(value: Double?, decimals: Int = 0) = value?.takeIf { it.isFinite() && it >= 0 }?.let { String.format(Locale.getDefault(), "%.${decimals}f", it) } ?: "—"
    fun codec(name: String?, code: Double?) = if (name == null) "—" else {
        val short = name.substringAfterLast('/')
        if (short.equals("h264", true) && code != null && code.toInt() % 100 > 0) "$short ${code.toInt() % 100 / 10}.${code.toInt() % 10}" else short
    }
    fun video(width: Long?, height: Long?, fps: Double?) = if (width == null || height == null) "—" else "$width × $height · ${number(fps, 1)} fps"
    Column {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Text(stringResource(R.string.call_statistics), style = MaterialTheme.typography.titleLarge)
            val rows = listOf(
                (stringResource(R.string.call_stat_video_codec)) to "${codec(stats.videoInbound?.codecName, stats.videoInbound?.codecCode)} / ${codec(stats.videoOutbound?.codecName, stats.videoOutbound?.codecCode)}",
                (stringResource(R.string.call_stat_incoming_video)) to video(stats.videoInbound?.frameWidth, stats.videoInbound?.frameHeight, stats.videoInbound?.framesPerSecond),
                (stringResource(R.string.call_stat_outgoing_video)) to video(stats.videoOutbound?.frameWidth, stats.videoOutbound?.frameHeight, stats.videoOutbound?.framesPerSecond),
                (stringResource(R.string.call_stat_camera)) to video(stats.camera?.width, stats.camera?.height, stats.camera?.framesPerSecond),
                "Encoder QP" to number(m?.videoOutQp, 1),
                (stringResource(R.string.call_stat_encode)) to number(m?.videoEncodeMs, 1),
                (stringResource(R.string.call_stat_pacer)) to number(m?.videoPacerMs, 1),
                (stringResource(R.string.call_stat_rtt)) to number(m?.rttMs),
                "STUN / RTCP, ms" to "${number(m?.stunRttMs)} / ${number(m?.rtcpRttMs)}",
                (stringResource(R.string.call_stat_audio_delay)) to number(m?.audioPlayoutDelayMs),
                (stringResource(R.string.call_stat_video_delay)) to number(m?.videoPlayoutDelayMs),
                (stringResource(R.string.call_stat_sync)) to number(m?.avSyncDelayMs),
            ) + if ((m?.audioPacketsOutPerSecond ?: 0.0) >= 8) listOf((stringResource(R.string.call_stat_packet)) to number(1000 / m!!.audioPacketsOutPerSecond!!, 1)) else emptyList()
            rows.forEach { (label, value) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            } }
        }
    }
}

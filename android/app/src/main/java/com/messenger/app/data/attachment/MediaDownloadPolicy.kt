package com.messenger.app.data.attachment

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.domain.model.Attachment
import com.messenger.app.domain.model.AttachmentMediaType
import com.messenger.app.domain.model.AuthSessionState

enum class DownloadNetwork { WIFI, CELLULAR, UNAVAILABLE }
enum class DownloadKind { PHOTO, VIDEO, VOICE, FILE }
object MediaDownloadPolicy {
    fun kind(mediaType: String, mimeType: String): DownloadKind = when {
        mediaType == "voice" -> DownloadKind.VOICE
        mimeType.startsWith("video/") -> DownloadKind.VIDEO
        mediaType == "image" -> DownloadKind.PHOTO
        else -> DownloadKind.FILE
    }
    fun allows(network: DownloadNetwork, size: Long, limitBytes: Long): Boolean =
        network != DownloadNetwork.UNAVAILABLE && size > 0 && limitBytes > 0 && size <= limitBytes
    fun network(context: Context): DownloadNetwork {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return DownloadNetwork.UNAVAILABLE
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return DownloadNetwork.UNAVAILABLE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> DownloadNetwork.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> DownloadNetwork.CELLULAR
            else -> DownloadNetwork.UNAVAILABLE
        }
    }
}

/** Device network limits persist per server/account; manual downloads bypass these preferences. */
class MediaDownloadPreferences(private val context: Context) {
    private val app get() = context.applicationContext as com.messenger.app.MessengerApp
    private val owner get() = (app.authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id?.let {
        ServerEndpoint.current().trimEnd('/') + "|" + it
    }
    val preferences = context.getSharedPreferences("media-download", Context.MODE_PRIVATE)
    private fun key(network: DownloadNetwork, kind: DownloadKind) = owner?.let { "$it|${network.name}|${kind.name}" }
    fun limitMb(network: DownloadNetwork, kind: DownloadKind): Int {
        val key = key(network, kind) ?: return 0
        val legacy = if (network == DownloadNetwork.WIFI) "wifi" else "mobile"
        val fallback = if (preferences.getBoolean(legacy, false)) if (network == DownloadNetwork.WIFI) 50 else 5 else 0
        return preferences.getInt(key, fallback).coerceIn(0, 100)
    }
    fun setLimitMb(network: DownloadNetwork, kind: DownloadKind, mb: Int) {
        val key = key(network, kind) ?: return
        preferences.edit().putInt(key, mb.coerceIn(0, 100)).apply()
    }
    fun allows(mediaType: String, mime: String, size: Long): Boolean {
        val network = MediaDownloadPolicy.network(context)
        return MediaDownloadPolicy.allows(network, size, limitMb(network, MediaDownloadPolicy.kind(mediaType, mime)) * 1024L * 1024L)
    }
}

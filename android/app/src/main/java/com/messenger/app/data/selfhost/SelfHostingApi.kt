package com.messenger.app.data.selfhost

import com.messenger.app.data.api.*
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable private data class OwnerAvailability(val available: Boolean)
@Serializable private data class OwnerClaimResult(val ok: Boolean, val role: String)

class SelfHostingApi(client: OkHttpClient) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).cache(null).build()
    val policies = RegistrationPolicyCache()
    suspend fun registration(origin: String, force: Boolean = false): RegistrationPolicy = policies.get(origin, force) {
        ApiJson.decodeApiResponse<RegistrationPolicy>(request(it, "/server-info"))
    }
    private suspend fun request(origin: String, path: String, token: String? = null, body: JsonObject? = null): String {
        val base = ServerEndpoint.normalize(origin, com.messenger.app.BuildConfig.ALLOW_LOCAL_HTTP)
        val builder = Request.Builder().url(base + path).header("Cache-Control", "no-store")
        token?.let { builder.header("Authorization", "Bearer $it") }
        body?.let { builder.post(it.toString().toRequestBody("application/json".toMediaType())) }
        return client.executeCancellableApi(builder.build(), ApiJson)
    }
    suspend fun ownerAvailable(origin: String, token: String): Boolean =
        ApiJson.decodeApiResponse<OwnerAvailability>(request(origin, "/auth/owner-setup", token)).available
    suspend fun claim(origin: String, token: String, code: String) {
        val normalized = requireNotNull(OwnerClaimPolicy.normalize(code))
        val result = ApiJson.decodeApiResponse<OwnerClaimResult>(request(origin, "/auth/owner-setup", token,
            buildJsonObject { put("code", normalized) }))
        check(result.ok && result.role == "owner")
    }

    data class ConnectionCheck(val dns: Boolean, val tls: Boolean?, val api: Boolean?, val compatible: Boolean?)
    /** Explicit foreground action only. No credential, active socket or relay state is changed. */
    suspend fun checkConnection(origin: String): ConnectionCheck {
        val base = ServerEndpoint.normalize(origin, com.messenger.app.BuildConfig.ALLOW_LOCAL_HTTP)
        val dns = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { client.dns.lookup(java.net.URI(base).host).isNotEmpty() }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { false }
        if (!dns) return ConnectionCheck(false, null, null, null)
        var tls: Boolean? = null
        val probe = client.newBuilder().callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .eventListener(object : okhttp3.EventListener() {
                override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) { tls = handshake != null }
            }).build()
        val api = try {
            ApiJson.decodeApiResponse<RegistrationPolicy>(probe.executeCancellableApi(Request.Builder().url("$base/server-info")
                .header("Cache-Control", "no-store").build(), ApiJson))
            true
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { false }
        val compatible = if (api) try {
            val minimum = ClientConfigApi(probe).fetch(base)?.minimum
            minimum?.let { com.messenger.app.data.config.AppVersion.compare(com.messenger.app.BuildConfig.VERSION_NAME, it)?.let { result -> result >= 0 } }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { null } else null
        return ConnectionCheck(true, if (api && base.startsWith("https://")) true else tls, api, compatible)
    }
}

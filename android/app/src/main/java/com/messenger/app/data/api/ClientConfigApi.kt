package com.messenger.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class ClientPlatformPolicy(
    @SerialName("minimum_version") val minimum: String? = null,
    @SerialName("recommended_version") val recommended: String? = null,
    @SerialName("update_url") val updateUrl: String? = null,
)

@Serializable
private data class ClientPlatforms(val android: ClientPlatformPolicy? = null)

@Serializable
private data class ClientConfigResponse(val platforms: ClientPlatforms)

/** Public compatibility discovery: never sends credentials, cookies or follows redirects. */
class ClientConfigApi(client: OkHttpClient, private val json: Json = ApiJson) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES).cache(null).authenticator(okhttp3.Authenticator.NONE).build()

    suspend fun fetch(origin: String): ClientPlatformPolicy? {
        val request = Request.Builder().url(origin.trimEnd('/') + "/client/config").get()
            .header("Cache-Control", "no-cache").build()
        return try {
            json.decodeApiResponse<ClientConfigResponse>(client.executeCancellableApi(request, json)).platforms.android
        } catch (error: AuthApiException.Api) {
            if (error.httpStatus == 404 || error.httpStatus == 405 || error.code in listOf("not_found", "route_not_found")) null
            else throw error
        }
    }
}

package com.messenger.app.data.identity

import com.messenger.app.data.api.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
enum class Discoverability {
    @SerialName("everyone") Everyone,
    @SerialName("contacts") Contacts,
    @SerialName("nobody") Nobody
}
@Serializable
data class OwnerIdentity(
    @SerialName("user_id") val userId: String,
    @SerialName("unyri_number") val number: String,
    @SerialName("public_username") val username: String? = null,
    val discoverability: Discoverability = Discoverability.Everyone,
    val revision: Long,
    @SerialName("public_origin") val publicOrigin: String,
    @SerialName("username_changed_at") val changedAt: String? = null,
) {
    init {
        require(IdentityPolicy.number(number) != null && number.length == 9 && revision >= 0)
        require(username == null || IdentityPolicy.username(username) == username)
        require(IdentityPolicy.origin(publicOrigin) != null)
    }
}
@Serializable
data class IdentityProfile(
    val id: String,
    @SerialName("unyri_number") val number: String,
    @SerialName("public_username") val username: String? = null,
    @SerialName("display_name") val displayName: String,
    val bio: String? = null,
    @SerialName("has_avatar") val hasAvatar: Boolean = false,
    @SerialName("profile_version") val profileVersion: Long = 1,
    @SerialName("avatar_version") val avatarVersion: Long = 0,
) {
    init {
        require(IdentityPolicy.number(number) != null && number.length == 9)
        require(username == null || IdentityPolicy.username(username) == username)
        require(profileVersion >= 0 && avatarVersion >= 0)
    }
    fun domain() = com.messenger.app.domain.model.PublicUser(id, "", displayName, null, bio,
        profileVersion, avatarVersion, hasAvatar, number, username)
}

class IdentityApi(client: OkHttpClient, private val json: Json = ApiJson) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).cache(null).build()
    /** Pre-auth hint only. Authenticated GET remains authoritative for discovery and onboarding. */
    suspend fun supports(origin: String): Boolean? = try {
        client.executeCancellableApi(Request.Builder().url(origin.trimEnd('/') + "/users/me/identity")
            .header("Cache-Control", "no-store").build(), json)
        null
    } catch (error: AuthApiException.Api) {
        when {
            IdentityPolicy.legacy(error.httpStatus) -> false
            error.httpStatus == 401 -> true
            else -> throw error
        }
    }
    private suspend fun execute(origin: String, token: String, path: String, body: JsonObject? = null): String {
        val builder = Request.Builder().url(origin.trimEnd('/') + path).header("Authorization", "Bearer $token")
            .header("Cache-Control", "no-store")
        if (body != null) builder.put(body.toString().toRequestBody("application/json".toMediaType()))
        return client.executeCancellableApi(builder.build(), json)
    }
    suspend fun get(origin: String, token: String): OwnerIdentity? = try {
        json.decodeFromString<OwnerIdentity>(execute(origin, token, "/users/me/identity"))
    } catch (error: AuthApiException.Api) {
        if (IdentityPolicy.legacy(error.httpStatus)) null else throw error
    }
    suspend fun update(origin: String, token: String, current: OwnerIdentity, username: String?, policy: Discoverability,
        changeUsername: Boolean): OwnerIdentity = json.decodeFromString(execute(origin, token, "/users/me/identity", buildJsonObject {
        put("expected_revision", current.revision)
        if (changeUsername) put("public_username", username?.let(::JsonPrimitive) ?: JsonNull)
        put("discoverability", json.encodeToJsonElement(policy))
    }))
    suspend fun lookup(origin: String, token: String, target: IdentityPolicy.Target): IdentityProfile {
        val url = (origin.trimEnd('/') + "/users/lookup").toHttpUrl().newBuilder()
            .addQueryParameter(target.parameter, target.value).build()
        return json.decodeFromString(client.executeCancellableApi(Request.Builder().url(url)
            .header("Authorization", "Bearer $token").header("Cache-Control", "no-store").build(), json))
    }
    suspend fun profile(origin: String, token: String, id: String): IdentityProfile =
        json.decodeFromString(execute(origin, token, "/users/${java.util.UUID.fromString(id)}/profile"))
    suspend fun recovery(origin: String, token: String, password: String): String {
        val body = buildJsonObject { put("password", password) }
        val response = client.executeCancellableApi(Request.Builder().url(origin.trimEnd('/') + "/auth/recovery/code")
            .header("Authorization", "Bearer $token").post(body.toString().toRequestBody("application/json".toMediaType())).build(), json)
        return json.parseToJsonElement(response).jsonObject["code"]!!.jsonPrimitive.content.also {
            require(IdentityPolicy.recoveryParts(it) != null)
        }
    }
}

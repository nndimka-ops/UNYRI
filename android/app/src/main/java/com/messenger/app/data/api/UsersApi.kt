package com.messenger.app.data.api

import com.messenger.app.domain.model.AvatarUpdate
import com.messenger.app.domain.model.BioUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** REST client for the profile/search endpoints. Every call takes a
 *  caller-supplied access token; refreshing it is the caller's
 *  responsibility (see AuthRepository.currentAccessToken()), so this class
 *  never touches token storage directly. */
class UsersApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String,
) : BlockedUsersRemote, com.messenger.app.data.profile.AccountAutoDeleteRemote {
    @kotlinx.serialization.Serializable
    data class AppearanceSnapshot(val revision: Long, val values: Map<String, String>)

    suspend fun appearance(accessToken: String, changes: Map<String, String>? = null, initializeOnly: Boolean = false): AppearanceSnapshot {
        val builder = authorized("${ServerEndpoint.resolve(baseUrl)}/users/me/appearance", accessToken)
        val request = if (changes == null) builder.get().build() else builder.patch(
            buildJsonObject {
                put("version", 1)
                put("initialize_only", initializeOnly)
                put("values", kotlinx.serialization.json.JsonObject(changes.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
        ).build()
        return json.decodeFromString(execute(request))
    }
    suspend fun privacy(accessToken: String, update: com.messenger.app.data.profile.PrivacyUpdate? = null, origin: String = ServerEndpoint.resolve(baseUrl)): com.messenger.app.data.profile.PrivacySnapshot {
        val builder = authorized("${origin.trimEnd('/')}/users/me/privacy", accessToken)
        val request = if (update == null) builder.get().build() else builder.put(
            json.encodeToString(com.messenger.app.data.profile.PrivacyUpdate.serializer(), update).toRequestBody(JSON_MEDIA_TYPE)
        ).build()
        return json.decodeFromString(client.executeCancellableApi(request, json))
    }

    suspend fun privacyDirectory(accessToken: String, query: String, origin: String): SearchResponseDto {
        val url = "${origin.trimEnd('/')}/users/directory".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.take(128)).addQueryParameter("limit", "50").addQueryParameter("offset", "0").build()
        return json.decodeFromString(client.executeCancellableApi(authorized(url.toString(), accessToken).get().build(), json))
    }

    override suspend fun getAutoDelete(token: String, origin: String): com.messenger.app.data.profile.AccountAutoDeleteSnapshot {
        val request = authorized("${origin.trimEnd('/')}/users/me/auto-delete", token).get().build()
        return json.decodeFromString(client.executeCancellableApi(request, json))
    }

    override suspend fun updateAutoDelete(token: String, origin: String, update: com.messenger.app.data.profile.AccountAutoDeleteUpdate): com.messenger.app.data.profile.AccountAutoDeleteSnapshot {
        val body = buildJsonObject {
            put("inactive_days", update.inactiveDays?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
            put("expected_revision", update.expectedRevision)
            update.password?.let { put("password", it) }
        }
        val request = authorized("${origin.trimEnd('/')}/users/me/auto-delete", token)
            .put(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        return json.decodeFromString(client.executeCancellableApi(request, json))
    }

    override suspend fun recordAccountActivity(token: String, origin: String) {
        val request = authorized("${origin.trimEnd('/')}/users/me/activity", token)
            .post("".toRequestBody(JSON_MEDIA_TYPE)).build()
        client.executeCancellableApi(request, json)
    }

    suspend fun getMe(accessToken: String, origin: String = ServerEndpoint.resolve(baseUrl)): UserProfileDto {
        val request = authorized("${origin.trimEnd('/')}/users/me", accessToken).get().build()
        return json.decodeFromString(execute(request))
    }

    suspend fun updateProfile(
        accessToken: String,
        username: String? = null,
        displayName: String? = null,
        bioUpdate: BioUpdate = BioUpdate.Keep,
        avatarUpdate: AvatarUpdate = AvatarUpdate.Keep,
        origin: String = ServerEndpoint.resolve(baseUrl),
    ): UserProfileDto {
        // Built manually (not via a @Serializable data class) so a field can
        // be omitted entirely - distinct from sending it as `null` - which
        // is required to express "leave avatar_url untouched" vs "clear it".
        val requestBody = buildJsonObject {
            username?.let { put("username", it) }
            displayName?.let { put("display_name", it) }
            when (bioUpdate) {
                is BioUpdate.Set -> put("bio", bioUpdate.text)
                BioUpdate.Clear -> put("bio", JsonNull)
                BioUpdate.Keep -> Unit
            }
            when (avatarUpdate) {
                is AvatarUpdate.Set -> put("avatar_url", avatarUpdate.url)
                AvatarUpdate.Clear -> put("avatar_url", JsonNull)
                AvatarUpdate.Keep -> Unit
            }
        }
        val request = authorized("${origin.trimEnd('/')}/users/me", accessToken)
            .patch(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return json.decodeFromString(execute(request))
    }

    suspend fun search(accessToken: String, query: String, limit: Int, offset: Long): SearchResponseDto {
        val url = "${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/users/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        val request = authorized(url.toString(), accessToken).get().build()
        return json.decodeFromString(execute(request))
    }

    override suspend fun directory(accessToken: String, query: String, limit: Int, offset: Long): SearchResponseDto {
        val url = "${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/users/directory".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        val request = authorized(url.toString(), accessToken).get().build()
        return json.decodeFromString(execute(request))
    }

    override suspend fun blockedUsers(accessToken: String, query: String, cursor: String?): BlockedUsersPageDto {
        val url = "${ServerEndpoint.resolve(baseUrl)}/users/blocked".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "50")
            .apply {
                if (query.isNotBlank()) addQueryParameter("q", query)
                cursor?.let { addQueryParameter("cursor", it) }
            }.build()
        return json.decodeApiResponse(client.executeCancellableApi(authorized(url.toString(), accessToken).get().build(), json))
    }

    override suspend fun setUserBlocked(accessToken: String, userId: String, blocked: Boolean) {
        // Path segments must never be interpolated from user-visible search text.
        val id = java.util.UUID.fromString(userId).toString()
        val builder = authorized("${ServerEndpoint.resolve(baseUrl)}/users/$id/block", accessToken)
        client.executeCancellableApi(if (blocked) builder.post("".toRequestBody(JSON_MEDIA_TYPE)).build() else builder.delete().build(), json)
    }

    /** PROFILE.1: a fresh `avatars/{uuid}` object + presigned PUT URL. */
    suspend fun createAvatar(accessToken: String, sizeBytes: Long): CreateAvatarDto {
        require(sizeBytes in 1..3L * 1024 * 1024)
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/users/me/avatar", accessToken)
            .post(org.json.JSONObject().put("size_bytes", sizeBytes).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return json.decodeFromString(execute(request))
    }

    /** E.1: presigned GET URL for a visible user's uploaded avatar (self or
     *  another user - the download URL is always derived/temporary). */
    suspend fun getAvatarDownloadUrl(accessToken: String, userId: String): AvatarDownloadDto {
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/users/$userId/avatar?size=640", accessToken).get().build()
        return json.decodeFromString(execute(request))
    }

    /** E.1: authoritative avatar commit - the server HEAD-verifies the object
     *  and bumps avatar_version/profile_version exactly once. Returns the
     *  updated own profile. */
    suspend fun completeAvatar(accessToken: String, objectKey: String, origin: String = ServerEndpoint.resolve(baseUrl)): UserProfileDto {
        val body = buildJsonObject { put("object_key", objectKey) }
        val request = authorized("${origin.trimEnd('/')}/users/me/avatar/complete", accessToken)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return json.decodeFromString(execute(request))
    }

    /** E.1: authoritative avatar removal (bumps versions only if an avatar
     *  actually existed). Returns the updated own profile. */
    suspend fun deleteAvatar(accessToken: String, origin: String = ServerEndpoint.resolve(baseUrl)): UserProfileDto {
        val request = authorized("${origin.trimEnd('/')}/users/me/avatar", accessToken).delete().build()
        return json.decodeFromString(execute(request))
    }

    /** PROFILE.1: PUT avatar bytes straight to the presigned URL - the server
     *  never proxies avatar bytes. No auth header (the URL is self-signed). */
    suspend fun uploadAvatarBytes(uploadUrl: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(uploadUrl)
            .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw AuthApiException.Network("Avatar upload failed: HTTP ${response.code}")
            }
        }
    }

    /** PROFILE.1: download avatar bytes from a presigned GET URL. */
    suspend fun downloadAvatarBytes(downloadUrl: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(downloadUrl).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw AuthApiException.Network("Avatar download failed: HTTP ${response.code}")
            }
            val body = response.body ?: return@withContext ByteArray(0)
            val limit = 3 * 1024 * 1024
            if (body.contentLength() > limit) throw AuthApiException.Network("Avatar too large")
            body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > limit - output.size()) throw AuthApiException.Network("Avatar too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    private fun authorized(url: String, accessToken: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $accessToken")

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (e: java.io.IOException) {
            throw AuthApiException.Network(e.message ?: "Network error")
        }
        response.use {
            val bodyString = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw parseApiException(json, it.code, bodyString)
            }
            bodyString
        }
    }
}

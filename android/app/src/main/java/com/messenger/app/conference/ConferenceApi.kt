package com.messenger.app.conference

import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.data.api.parseApiException
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

@Serializable data class ConferenceMember(@SerialName("user_id") val userId: String, @SerialName("device_id") val deviceId: String, @SerialName("display_name") val displayName: String,
    @SerialName("participant_identity") val participantIdentity: String = "", @SerialName("audio_publish_blocked") val audioPublishBlocked: Boolean = false)
@Serializable data class ConferenceDto(val id: String, val title: String, @SerialName("owner_user_id") val ownerUserId: String, @SerialName("max_participants") val maxParticipants: Int, val state: String, val participants: List<ConferenceMember> = emptyList(), @SerialName("expires_at") val expiresAt: String = "", @SerialName("admission_locked") val admissionLocked: Boolean = false, val description: String = "", val listed: Boolean = false, @SerialName("access_mode") val accessMode: String = "invite", @SerialName("approval_required") val approvalRequired: Boolean? = null, @SerialName("settings_revision") val settingsRevision: Long = 1, @SerialName("access_revision") val accessRevision: Long = 1)
@Serializable data class ConferenceCapabilities(@SerialName("host_controls") val hostControls: Boolean = false,
    @SerialName("participant_moderation") val participantModeration: Boolean = false, @SerialName("owner_rooms") val ownerRooms: Boolean = false, @SerialName("room_directory") val roomDirectory: Boolean = false, @SerialName("room_access_v2") val roomAccessV2: Boolean = false, @SerialName("room_key_handoff") val roomKeyHandoff: Boolean = false, @SerialName("room_join_approval") val roomJoinApproval: Boolean = false)
@Serializable private data class ConferenceAudioRestriction(@SerialName("participant_identity") val participantIdentity: String, val blocked: Boolean)
@Serializable private data class ConferenceRemoval(@SerialName("participant_identity") val participantIdentity: String)
@Serializable private data class ConferenceSettings(val title: String, @SerialName("admission_locked") val admissionLocked: Boolean)
@Serializable private data class CreateConference(val title: String, @SerialName("max_participants") val maxParticipants: Int, @SerialName("invite_secret_hash") val inviteSecretHash: String)
@Serializable private data class JoinConference(@SerialName("invite_secret") val inviteSecret: String)
@Serializable class ConferenceGrant(val conference: ConferenceDto, @SerialName("livekit_url") val livekitUrl: String, @SerialName("participant_token") val participantToken: String, @SerialName("participant_identity") val participantIdentity: String, @SerialName("e2ee_required") val e2eeRequired: Boolean) {
    override fun toString() = "ConferenceGrant([redacted])"
}

/** Every request is bound to the current authenticated origin and session epoch. */
class ConferenceApi(client: OkHttpClient, private val json: Json, private val auth: AuthRepository, private val selectedOrigin: () -> String = ServerEndpoint::current) {
    // Passwords and invite capabilities must never follow a 307/308 to another origin.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    suspend fun create(title: String, max: Int, hash: String, origin: String, epoch: Int): ConferenceDto = json.decodeFromString(request("", json.encodeToString(CreateConference.serializer(), CreateConference(title, max, hash)), origin, epoch))
    suspend fun join(invite: ConferenceInvite, epoch: Int): ConferenceGrant = json.decodeFromString(request("/${id(invite.roomId)}/join", json.encodeToString(JoinConference.serializer(), JoinConference(invite.secret)), invite.server, epoch))
    suspend fun leave(room: String, origin: String, epoch: Int) { request("/${id(room)}/leave", "{}", origin, epoch) }
    suspend fun end(room: String, origin: String, epoch: Int) { request("/${id(room)}/end", "{}", origin, epoch) }
    suspend fun capabilities(origin: String, epoch: Int): ConferenceCapabilities = json.decodeFromString(request("/capabilities", "", origin, epoch, "GET"))
    suspend fun get(room: String, origin: String, epoch: Int): ConferenceDto = json.decodeFromString(request("/${id(room)}", "", origin, epoch, "GET"))
    suspend fun owned(origin: String, epoch: Int): List<ConferenceDto> = json.decodeFromString(request("/owned", "", origin, epoch, "GET"))
    suspend fun settings(room: String, title: String, locked: Boolean, origin: String, epoch: Int): ConferenceDto {
        require(ConferenceInvitation.validTitle(title.trim()))
        return json.decodeFromString(request("/${id(room)}/settings", json.encodeToString(ConferenceSettings.serializer(), ConferenceSettings(title.trim(), locked)), origin, epoch, "PATCH"))
    }
    suspend fun audioRestriction(room: String, user: String, identity: String, blocked: Boolean, origin: String, epoch: Int): ConferenceDto {
        validateIdentity(user, identity)
        return json.decodeFromString(request("/${id(room)}/participants/${id(user)}/audio",
            json.encodeToString(ConferenceAudioRestriction.serializer(), ConferenceAudioRestriction(identity, blocked)), origin, epoch))
    }
    suspend fun removeParticipant(room: String, user: String, identity: String, origin: String, epoch: Int): ConferenceDto {
        validateIdentity(user, identity)
        return json.decodeFromString(request("/${id(room)}/participants/${id(user)}/remove",
            json.encodeToString(ConferenceRemoval.serializer(), ConferenceRemoval(identity)), origin, epoch))
    }
    suspend fun create(options: ConferenceCreateOptions, hash: String, origin: String, epoch: Int, accessV2: Boolean, approvalAvailable: Boolean = false): ConferenceDto {
        options.validate()
        require(!options.approvalRequired || approvalAvailable)
        val body = buildJsonObject {
            put("title", options.title.trim()); put("max_participants", options.maxParticipants); put("invite_secret_hash", hash)
            if (options.approvalRequired) put("approval_required", true)
            if (accessV2) { put("description", options.description); put("listed", options.listed); put("access_mode", options.access.wire); options.password?.let { put("password", it) } }
            else require(!options.listed && options.access == ConferenceAccess.INVITE && options.description.isEmpty())
        }
        return json.decodeFromString(request("", body.toString(), origin, epoch))
    }
    suspend fun join(admission: ConferenceAdmission, epoch: Int, handoff: Boolean): ConferenceGrant {
        val body = buildJsonObject {
            when (admission) {
                is ConferenceAdmission.Invitation -> { put("invite_secret", admission.value.secret); admission.password?.let { put("password", it) } }
                is ConferenceAdmission.Catalog -> put("key_request_id", id(admission.requestId))
            }
            if (handoff) put("supports_key_handoff", true)
        }
        return json.decodeFromString(request("/${id(admission.roomId)}/join", body.toString(), admission.server, epoch))
    }
    suspend fun directory(query: String, cursor: String?, origin: String, epoch: Int): ConferenceDirectory {
        val url = "https://placeholder.invalid/".toHttpUrl().newBuilder().addQueryParameter("q", query).addQueryParameter("limit", "20")
        cursor?.let { url.addQueryParameter("cursor", it) }
        return json.decodeFromString(request("/directory?${url.build().encodedQuery}", "", origin, epoch, "GET"))
    }
    suspend fun settings(room: String, edit: ConferenceSettingsEdit, origin: String, epoch: Int): ConferenceDto {
        edit.validate()
        val body = buildJsonObject {
            put("title", edit.title.trim()); put("description", edit.description); put("listed", edit.listed); put("access_mode", edit.access.wire)
            edit.approvalRequired?.let { put("approval_required", it) }
            put("admission_locked", edit.locked); put("expected_revision", edit.expectedRevision); put("password_action", edit.passwordAction)
            edit.password?.let { put("password", it) }
        }
        return json.decodeFromString(request("/${id(room)}/settings", body.toString(), origin, epoch, "PATCH"))
    }
    suspend fun requestKey(room: String, requestId: String, nonce: String, password: String?, origin: String, epoch: Int): ConferenceKeyRequest {
        ConferenceDiscoveryPolicy.key(nonce)
        val body = buildJsonObject { put("request_id", id(requestId)); put("nonce", nonce); password?.let { put("password", it) } }
        return json.decodeFromString(request("/${id(room)}/key-requests", body.toString(), origin, epoch))
    }
    suspend fun readKey(room: String, requestId: String, origin: String, epoch: Int): ConferenceKeyRequest =
        json.decodeFromString(request("/${id(room)}/key-requests/${id(requestId)}", "", origin, epoch, "GET"))
    suspend fun cancelKey(room: String, requestId: String, origin: String, epoch: Int) { request("/${id(room)}/key-requests/${id(requestId)}", "{}", origin, epoch, "DELETE") }
    suspend fun pendingKeys(room: String, origin: String, epoch: Int): ConferenceKeyRequests =
        json.decodeFromString(request("/${id(room)}/key-requests", "", origin, epoch, "GET"))
    suspend fun fulfillKey(room: String, requestId: String, assignment: Long, access: Long, envelope: ConferenceKeyEnvelope, origin: String, epoch: Int): ConferenceKeyRequest {
        require(assignment > 0 && access > 0)
        ConferenceDiscoveryPolicy.envelope(envelope, envelope.recipientDeviceId)
        val body = buildJsonObject { put("assignment_revision", assignment); put("access_revision", access); put("envelope", json.encodeToJsonElement(ConferenceKeyEnvelope.serializer(), envelope)) }
        return json.decodeFromString(request("/${id(room)}/key-requests/${id(requestId)}/fulfill", body.toString(), origin, epoch))
    }
    internal fun approvalOwner(origin: String, epoch: Int): Pair<String, String> {
        if (epoch != auth.currentSessionEpoch() || origin != selectedOrigin()) throw CancellationException("Room session changed")
        val user = (auth.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user?.id ?: throw CancellationException("Signed out")
        return user to requireNotNull(auth.currentDeviceId())
    }
    suspend fun requestJoinApproval(room: String, requestId: String, secret: String?, password: String?, origin: String, epoch: Int): ConferenceJoinRequest {
        val body = buildJsonObject { put("request_id", id(requestId)); secret?.let { put("invite_secret", it) }; password?.let { put("password", it) } }
        return json.decodeFromString(request("/${id(room)}/join-requests", body.toString(), origin, epoch))
    }
    suspend fun readJoinApproval(room: String, requestId: String, origin: String, epoch: Int): ConferenceJoinRequest =
        json.decodeFromString(request("/${id(room)}/join-requests/${id(requestId)}", "", origin, epoch, "GET"))
    suspend fun cancelJoinApproval(room: String, requestId: String, origin: String, epoch: Int) {
        request("/${id(room)}/join-requests/${id(requestId)}", "{}", origin, epoch, "DELETE")
    }
    suspend fun pendingJoinApprovals(room: String, origin: String, epoch: Int): ConferenceJoinRequests =
        json.decodeFromString(request("/${id(room)}/join-requests", "", origin, epoch, "GET"))
    suspend fun decideJoinApproval(room: String, requestId: String, approve: Boolean, origin: String, epoch: Int): ConferenceJoinRequest {
        val body = buildJsonObject { put("approve", approve) }
        return json.decodeFromString(request("/${id(room)}/join-requests/${id(requestId)}/decision", body.toString(), origin, epoch))
    }
    private fun validateIdentity(user: String, identity: String) {
        val parts = identity.split(':')
        require(parts.size == 3 && parts.first() == user && parts.all { id(it) == it })
    }
    private fun id(value: String): String { require(UUID.fromString(value).toString() == value); return value }
    private suspend fun request(path: String, body: String, origin: String, epoch: Int, method: String = "POST"): String {
        fun current() = epoch == auth.currentSessionEpoch() && origin == selectedOrigin()
        check(current()) { "Server changed" }
        val token = auth.currentAccessToken() ?: if (current() && auth.authState.value is AuthSessionState.LoggedIn) {
            // Refresh keeps a valid local session on transient I/O failure. Recovery
            // must retry token acquisition rather than interpreting it as logout.
            throw AuthApiException.Network("Conference access token temporarily unavailable")
        } else throw CancellationException("Signed out")
        if (!current()) throw CancellationException("Session changed")
        val request = Request.Builder().url("$origin/conferences$path").header("Authorization", "Bearer $token")
            .method(method, if (method == "GET") null else body.toRequestBody("application/json".toMediaType())).build()
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { continuation.resumeWith(Result.failure(e)) }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    continuation.resumeWith(runCatching { response.use {
                        if (!current()) throw CancellationException("Session changed")
                        val raw = it.body?.string().orEmpty()
                        if (!it.isSuccessful) throw parseApiException(json, it.code, raw)
                        raw
                    } })
                }
            })
        }
    }
}

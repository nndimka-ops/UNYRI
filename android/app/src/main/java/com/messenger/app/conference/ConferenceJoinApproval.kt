package com.messenger.app.conference

import com.messenger.app.data.api.AuthApiException
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable class ConferenceJoinRequest(
    @SerialName("request_id") val requestId: String,
    @SerialName("conference_id") val conferenceId: String,
    @SerialName("requester_user_id") val requesterUserId: String,
    @SerialName("requester_device_id") val requesterDeviceId: String,
    @SerialName("display_name") val displayName: String,
    val username: String, val status: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("retry_at") val retryAt: String? = null,
) { override fun toString() = "ConferenceJoinRequest([redacted])" }
@Serializable class ConferenceJoinRequests(val items: List<ConferenceJoinRequest> = emptyList())

class ConferenceJoinApprovalFailure(val reason: Reason, val retryAt: Long? = null) : Exception("Room entry decision") {
    enum class Reason { DENIED, EXPIRED, CANCELLED }
}
internal fun needsConferenceApproval(error: Exception) = error is AuthApiException.Api &&
    error.httpStatus == 409 && error.message == "Conference creator approval is required."

/** One cancelled caller owns one exact server request; no polling after account changes. */
internal suspend fun ConferenceApi.awaitJoinApproval(room: String, secret: String?, password: String?,
    origin: String, epoch: Int, waiting: () -> Unit) {
    val owner = approvalOwner(origin, epoch)
    val request = UUID.randomUUID().toString()
    var accepted = false
    fun current() = runCatching { approvalOwner(origin, epoch) == owner }.getOrDefault(false)
    fun validate(value: ConferenceJoinRequest) {
        check(current()) { "Room session changed" }
        require(value.requestId == request && value.conferenceId == room &&
            value.requesterUserId == owner.first && value.requesterDeviceId == owner.second)
        require(value.status in setOf("pending", "approved", "consumed", "not_required", "denied", "cooldown", "cancelled", "expired"))
        Instant.parse(value.expiresAt); value.retryAt?.let(Instant::parse)
    }
    fun transient(error: Exception) = error is java.io.IOException || error is AuthApiException.Network ||
        (error is AuthApiException.Api && (error.httpStatus == 429 || error.httpStatus in 500..599))
    fun backoff(error: Exception): Long {
        val status = when (error) { is AuthApiException.Api -> error.httpStatus; is AuthApiException.Network -> error.httpStatus; else -> null }
        return if (status == 429) 61_000L else 3_000L
    }
    try {
        withTimeout(130_000) {
            var value: ConferenceJoinRequest? = null
            while (value == null) {
                currentCoroutineContext().ensureActive()
                if (!current()) throw CancellationException("Room session changed")
                try { value = requestJoinApproval(room, request, secret, password, origin, epoch) }
                catch (error: Exception) {
                    if (!transient(error)) throw error
                    var retryDelay = backoff(error)
                    // A POST can commit before its response is lost. Recover that exact UUID.
                    try { value = readJoinApproval(room, request, origin, epoch) }
                    catch (read: Exception) {
                        if (!transient(read) && (read as? AuthApiException.Api)?.httpStatus != 404) throw read
                        retryDelay = maxOf(retryDelay, backoff(read))
                    }
                    if (value == null) delay(retryDelay)
                }
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val response = requireNotNull(value); validate(response)
                when (response.status) {
                    "approved", "consumed", "not_required" -> { accepted = true; return@withTimeout }
                    "denied", "cooldown" -> throw ConferenceJoinApprovalFailure(ConferenceJoinApprovalFailure.Reason.DENIED,
                        response.retryAt?.let { Instant.parse(it).epochSecond })
                    "expired" -> throw ConferenceJoinApprovalFailure(ConferenceJoinApprovalFailure.Reason.EXPIRED)
                    "cancelled" -> throw ConferenceJoinApprovalFailure(ConferenceJoinApprovalFailure.Reason.CANCELLED)
                }
                waiting(); delay(3_000)
                try { value = readJoinApproval(room, request, origin, epoch) }
                catch (error: Exception) { if (!transient(error)) throw error; delay(backoff(error)) }
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw ConferenceJoinApprovalFailure(ConferenceJoinApprovalFailure.Reason.EXPIRED)
    } finally {
        if (!accepted && current()) withContext(NonCancellable) {
            runCatching { withTimeout(3_000) { cancelJoinApproval(room, request, origin, epoch) } }
        }
    }
}

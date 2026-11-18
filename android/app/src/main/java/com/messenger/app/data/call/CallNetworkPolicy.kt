package com.messenger.app.data.call

/** Platform-free call policies, mirrored from iOS CallNetworkPolicy (12 November). Times are milliseconds. */
enum class CallNetworkTier { WIFI, CELLULAR, SAVING }

data class CallMediaProfile(
    val tier: CallNetworkTier, val width: Int, val height: Int, val fps: Int,
    val maxVideoBitrate: Int, val startBitrate: Int, val audioMinBitrate: Int,
    val audioMaxBitrate: Int, val requestsDtx: Boolean, val prefersRed: Boolean,
)

object CallMediaLadder {
    val wifi = CallMediaProfile(CallNetworkTier.WIFI, 1280, 720, 30, 2_000_000, 800_000, 16_000, 32_000, false, true)
    val cellular = CallMediaProfile(CallNetworkTier.CELLULAR, 960, 540, 30, 1_000_000, 650_000, 16_000, 32_000, true, true)
    // The start describes the whole link, so may intentionally exceed the video cap.
    val saving = CallMediaProfile(CallNetworkTier.SAVING, 640, 360, 15, 350_000, 400_000, 16_000, 20_000, true, false)
    fun profile(tier: CallNetworkTier) = when (tier) { CallNetworkTier.WIFI -> wifi; CallNetworkTier.CELLULAR -> cellular; CallNetworkTier.SAVING -> saving }
}

class CallVideoPausePolicy {
    enum class Decision { PAUSE, RESUME }
    var paused = false; private set
    var failedTries = 0; private set
    private var lowSince: Long? = null
    private var healthySince: Long? = null
    private var retryAt: Long? = null
    private var lastSampleAt: Long? = null
    private var graceUntil: Long? = null
    private var lossEvidenceAt: Long? = null

    fun reset(): Decision? {
        val wasPaused = paused
        paused = false; failedTries = 0; lowSince = null; healthySince = null
        retryAt = null; lastSampleAt = null; graceUntil = null; lossEvidenceAt = null
        return if (wasPaused) Decision.RESUME else null
    }
    fun networkChanged(now: Long): Decision? {
        failedTries = 0; lowSince = null; healthySince = null; lossEvidenceAt = null
        graceUntil = now + RESUME_GRACE_MS
        if (!paused) return null
        paused = false; retryAt = null
        return Decision.RESUME
    }
    fun captureChanged(now: Long) {
        lowSince = null; healthySince = null; lossEvidenceAt = null
        graceUntil = now + RESUME_GRACE_MS
    }
    fun update(now: Long, availableKbps: Double?, limitedByBandwidth: Boolean, rttMs: Double?,
               outgoingLoss: Double?, sending: Boolean, captureInterrupted: Boolean = false): Decision? {
        if (!sending) return reset()
        if (lastSampleAt == null) graceUntil = now + RESUME_GRACE_MS
        if (captureInterrupted) { captureChanged(now); return null }
        if (lastSampleAt?.let { now - it > SAMPLE_GAP_MS } == true) captureChanged(now)
        lastSampleAt = now
        val kbps = availableKbps ?: return null
        if (paused) {
            if (now < (retryAt ?: now)) return null
            paused = false; retryAt = null; lowSince = null; healthySince = null; lossEvidenceAt = null
            graceUntil = now + RESUME_GRACE_MS
            return Decision.RESUME
        }
        if ((outgoingLoss ?: 0.0) >= CONGESTED_LOSS) lossEvidenceAt = now
        if (kbps < PAUSE_BELOW_KBPS) {
            healthySince = null
            val lossy = lossEvidenceAt?.let { now - it <= LOSS_EVIDENCE_MS } == true
            val congested = (rttMs ?: 0.0) >= CONGESTED_RTT_MS || lossy
            if (!limitedByBandwidth || !congested || now < (graceUntil ?: now)) { lowSince = null; return null }
            val since = lowSince ?: now.also { lowSince = it }
            if (now - since < PAUSE_AFTER_MS) return null
            paused = true; lowSince = null
            retryAt = now + RETRY_AFTER_MS[minOf(failedTries, RETRY_AFTER_MS.lastIndex)]
            failedTries++
            return Decision.PAUSE
        }
        lowSince = null
        val since = healthySince ?: now.also { healthySince = it }
        if (now - since >= HEALTHY_RESET_MS) failedTries = 0
        return null
    }
    companion object {
        const val PAUSE_BELOW_KBPS = 150.0
        const val PAUSE_AFTER_MS = 5_000L
        const val RESUME_GRACE_MS = 15_000L
        const val CONGESTED_RTT_MS = 400.0
        const val CONGESTED_LOSS = 0.06
        const val LOSS_EVIDENCE_MS = 8_000L
        const val HEALTHY_RESET_MS = 30_000L
        const val SAMPLE_GAP_MS = 3_000L
        val RETRY_AFTER_MS = listOf(10_000L, 20_000L, 40_000L, 60_000L)
    }
}

object CallBitrateSeedPolicy {
    enum class Cause { NEW_PATH, CAMERA_START, RETRY }
    data class Link(val rttMs: Double?, val outgoingLoss: Double?, val availableKbps: Double?)
    fun seed(cause: Cause, profile: CallMediaProfile, link: Link?): Int? = when (cause) {
        Cause.NEW_PATH -> profile.startBitrate
        Cause.RETRY -> null
        Cause.CAMERA_START -> if (link?.rttMs != null && link.rttMs < CallVideoPausePolicy.CONGESTED_RTT_MS &&
            (link.outgoingLoss ?: 0.0) < 0.02 && link.availableKbps != null &&
            link.availableKbps * 1000 < profile.startBitrate) profile.startBitrate else null
    }
}

class CallDeadPairPolicy {
    private var pairId: String? = null
    private var watchedSince: Long? = null
    private var packetsAtStart = 0L
    private var refusedAtStart = 0L
    private var bytesSentAtStart: Long? = null
    private var outageJudgesSilence: Boolean? = null
    private var judgesSilence = true
    private var settled = false
    private var lastVoiceTotal: Long? = null
    private var voiceGrewAt: Long? = null
    var reported = 0; private set
    var packets = 0L; private set
    var refused = 0L; private set
    var watchedMs = 0L; private set
    var sendsRefused = false; private set

    fun noteOutage(now: Long) {
        if (outageJudgesSilence == null) outageJudgesSilence = (lastVoiceTotal ?: 0) == 0L ||
            voiceGrewAt?.let { now - it <= ARRIVING_WITHIN_MS } == true
    }
    fun update(now: Long, pairId: String?, dtlsConnected: Boolean?, audioPacketsReceived: Long?,
               packetsDiscardedOnSend: Long? = null, pairBytesSent: Long? = null, voiceExpected: Boolean = true): Boolean {
        if (pairId == null) return false
        val total = audioPacketsReceived ?: 0L
        val heardBefore = (lastVoiceTotal ?: 0) > 0
        if (lastVoiceTotal?.let { total > it } == true) {
            voiceGrewAt = now
            if (pairId == this.pairId) outageJudgesSilence = null
        }
        lastVoiceTotal = total
        if (pairId != this.pairId) {
            this.pairId = pairId; watchedSince = null; packetsAtStart = 0; refusedAtStart = 0
            bytesSentAtStart = null; settled = false
            judgesSilence = outageJudgesSilence ?: (!heardBefore || voiceGrewAt?.let { now - it <= ARRIVING_WITHIN_MS } == true)
            outageJudgesSilence = null; packets = 0; refused = 0; watchedMs = 0
        }
        if (settled || dtlsConnected != true) return false
        val discarded = packetsDiscardedOnSend ?: 0L
        val since = watchedSince ?: run {
            watchedSince = now; packetsAtStart = total; refusedAtStart = discarded; bytesSentAtStart = pairBytesSent
            return false
        }
        packets = maxOf(0, total - packetsAtStart); refused = maxOf(0, discarded - refusedAtStart); watchedMs = now - since
        if (watchedMs > WATCH_FOR_MS) { settled = true; return false }
        if (watchedMs < DEAD_AFTER_MS) return false
        settled = true
        val silent = voiceExpected && judgesSilence && packets < MINIMUM_PACKETS
        val refusing = refused >= MINIMUM_PACKETS && bytesSentAtStart != null && pairBytesSent == bytesSentAtStart
        if ((!silent && !refusing) || reported >= MAXIMUM_PER_CALL) return false
        reported++; sendsRefused = refusing
        return true
    }
    companion object {
        const val DEAD_AFTER_MS = 3_000L
        const val WATCH_FOR_MS = 10_000L
        const val MINIMUM_PACKETS = 10L
        const val MAXIMUM_PER_CALL = 2
        const val ARRIVING_WITHIN_MS = 2_000L
    }
}

/** A dead-pair nudge is not an outage or a failed recovery round. */
class CallRecoveryPolicy(var caller: Boolean = true, var peerResolvesGlare: Boolean = false) {
    enum class Action { IDLE, WAIT, RESTART, RESEND_OFFER, ABANDON_OFFER, FAIL }
    var startedAt: Long? = null; private set
    var mediaUp = true; private set
    var negotiations = 0; private set
    var lastNegotiationAt: Long? = null; private set
    var urgent = false; private set
    var offerPendingSince: Long? = null; private set
    private var offerRequestedAt: Long? = null
    private var pendingOfferIsRestart = false
    private var stallOnly = false
    val earlierRoundFailed get() = startedAt != null && !mediaUp && negotiations > 0
    private fun begin(now: Long) { if (startedAt == null) { startedAt = now; negotiations = 0; lastNegotiationAt = null; urgent = false } }
    private fun settle() { if (mediaUp && offerPendingSince == null) { startedAt = null; negotiations = 0; lastNegotiationAt = null; urgent = false; stallOnly = false } }
    private fun reanchor(now: Long) { if (stallOnly) { stallOnly = false; startedAt = now; negotiations = 0; lastNegotiationAt = null } }
    fun mediaDisconnected(now: Long) { reanchor(now); begin(now); mediaUp = false }
    fun mediaFailed(now: Long) { mediaDisconnected(now); if (caller && offerPendingSince == null) urgent = true }
    fun interfaceChanged(now: Long) { if (peerResolvesGlare) { begin(now); if (offerPendingSince == null) urgent = true } }
    fun mediaStalled(now: Long): Boolean {
        if (!caller || !peerResolvesGlare) return false
        if (startedAt == null) stallOnly = true
        begin(now); if (offerPendingSince == null) urgent = true
        return true
    }
    fun mediaConnected(now: Long) { mediaUp = true; urgent = false; settle() }
    fun offerRequested(now: Long, restart: Boolean) {
        offerRequestedAt = now
        offerPendingSince = now; pendingOfferIsRestart = restart
        if (restart) { negotiations++; lastNegotiationAt = now; urgent = false }
    }
    fun offerSent(now: Long) { if (offerPendingSince != null) offerPendingSince = now }
    fun offerResolved(now: Long) {
        if (pendingOfferIsRestart && startedAt != null) lastNegotiationAt = now
        offerPendingSince = null; offerRequestedAt = null; pendingOfferIsRestart = false; settle()
    }
    fun peerNegotiated(now: Long) { if (startedAt != null) { negotiations++; lastNegotiationAt = now; urgent = false } }
    fun restartDeferred(now: Long) { urgent = false; if (startedAt != null) lastNegotiationAt = now; settle() }
    fun next(now: Long): Action {
        val outageDeadline = startedAt?.plus(BUDGET_MS)
        offerPendingSince?.let {
            if (!mediaUp && outageDeadline != null && now >= outageDeadline) return Action.FAIL
            // Resends move only the answer timer, never either failure budget.
            if (now >= (offerRequestedAt ?: it) + BUDGET_MS ||
                mediaUp && outageDeadline != null && now >= outageDeadline) return Action.ABANDON_OFFER
            return if (now >= it + ANSWER_TIMEOUT_MS) Action.RESEND_OFFER else Action.WAIT
        }
        val started = startedAt ?: return Action.IDLE
        val deadline = started + BUDGET_MS
        val mayOffer = caller || peerResolvesGlare
        if (mediaUp) return if (urgent && mayOffer) Action.RESTART else Action.IDLE
        if (now >= deadline) return Action.FAIL
        if (!mayOffer) return Action.WAIT
        val due = if (urgent) started else lastNegotiationAt?.let {
            it + BACKOFF_MS[minOf(maxOf(negotiations - 1, 0), BACKOFF_MS.lastIndex)] + if (caller) 0 else 4_000
        } ?: (started + if (caller) { if (peerResolvesGlare) 0 else 3_000 } else 6_000)
        return if (due < deadline && now >= due) Action.RESTART else Action.WAIT
    }
    companion object {
        const val BUDGET_MS = 30_000L
        const val ANSWER_TIMEOUT_MS = 5_000L
        val BACKOFF_MS = listOf(4_000L, 8_000L, 12_000L)
    }
}

object CallAudioCodecPreference {
    fun order(names: List<String>, preferRed: Boolean): List<Int> {
        val red = names.indices.filter { names[it].equals("red", ignoreCase = true) }
        val others = names.indices.filterNot { it in red }
        return if (preferRed) red + others else others
    }
}

object CallSdpMunger {
    /** usedtx is a receive preference. Only Opus format lines change; an explicit value wins. */
    fun requestingOpusDtx(sdp: String): String {
        val separator = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val lines = sdp.split(separator).toMutableList()
        val opus = lines.filter { it.startsWith("a=rtpmap:") }.mapNotNull {
            val fields = it.removePrefix("a=rtpmap:").split(' ', limit = 2)
            fields.firstOrNull()?.takeIf { fields.size == 2 && fields[1].startsWith("opus/", true) }
        }.toSet()
        if (opus.isEmpty()) return sdp
        val formats = mutableSetOf<String>()
        for (index in lines.indices) {
            val line = lines[index]
            if (!line.startsWith("a=fmtp:")) continue
            val fields = line.removePrefix("a=fmtp:").split(' ', limit = 2)
            val type = fields[0]
            if (type !in opus) continue
            formats += type
            val params = fields.getOrNull(1).orEmpty()
            if (params.split(';').any { it.trim().startsWith("usedtx=") }) continue
            lines[index] = "a=fmtp:$type " + if (params.isEmpty()) "usedtx=1" else "$params;usedtx=1"
        }
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.startsWith("a=rtpmap:")) {
                val type = line.removePrefix("a=rtpmap:").substringBefore(' ')
                if (type in opus && formats.add(type)) { lines.add(index + 1,"a=fmtp:$type usedtx=1"); index++ }
            }
            index++
        }
        return lines.joinToString(separator)
    }
}

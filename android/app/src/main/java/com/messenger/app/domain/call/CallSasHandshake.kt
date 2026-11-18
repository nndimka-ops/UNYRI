package com.messenger.app.domain.call

import java.security.MessageDigest
import java.security.SecureRandom

/** SAS-2 wire protocol shared with iOS. Never derive a code from fingerprints alone. */
class CallSasHandshake {
    private var call: String? = null
    private var offerer = false
    private var commit: String? = null
    private var offerNonce: String? = null
    private var answerNonce: String? = null
    private var offerFp: String? = null
    private var answerFp: String? = null
    fun reset() { call = null; offerer = false; commit = null; offerNonce = null; answerNonce = null; offerFp = null; answerFp = null }
    private fun attribute(sdp: String, name: String): String? = sdp.lineSequence().filter { it.startsWith("a=x-unyri-sas-$name:") }
        .toList().singleOrNull()?.substringAfter("a=x-unyri-sas-$name:")
    fun offer(sdp: String, id: String, nonce: String = newNonce()): String {
        reset(); call = id; offerer = true
        val fp = fingerprint(sdp) ?: return sdp
        val commitment = commitment(fp, nonce) ?: return sdp
        offerFp = fp; offerNonce = nonce; commit = commitment
        return sdp.trimEnd() + "\r\na=x-unyri-sas-commit:$commitment\r\n"
    }
    fun receiveOffer(sdp: String, id: String) {
        reset(); call = id
        val value = attribute(sdp, "commit")?.takeIf(::hex32) ?: return
        offerFp = fingerprint(sdp) ?: return
        commit = value
    }
    fun answer(sdp: String, id: String, nonce: String = newNonce()): String {
        if (call != id || offerer || commit == null || !hex32(nonce)) return sdp
        answerFp = fingerprint(sdp) ?: return sdp
        answerNonce = nonce
        return sdp.trimEnd() + "\r\na=x-unyri-sas-answer:$commit:$nonce\r\n"
    }
    fun receiveAnswer(sdp: String, id: String): String? {
        answerNonce = null
        if (call != id || !offerer || commit == null || offerNonce == null) return null
        val parts = attribute(sdp, "answer")?.split(':') ?: return null
        if (parts.size != 2 || parts[0] != commit || !hex32(parts[1])) return null
        answerFp = fingerprint(sdp) ?: return null
        answerNonce = parts[1]
        return "$REVEAL$commit:$offerNonce"
    }
    fun receiveReveal(candidate: String, id: String) {
        if (!candidate.startsWith(REVEAL) || call != id || offerer || answerNonce == null) return
        val parts = candidate.removePrefix(REVEAL).split(':')
        if (parts.size == 2 && parts[0] == commit && commitment(offerFp ?: return, parts[1]) == commit) offerNonce = parts[1]
    }
    fun indices(id: String, local: String?, remote: String?): List<Int>? {
        if (call != id || local == null || remote == null || offerFp != (if (offerer) local else remote) || answerFp != (if (offerer) remote else local)) return null
        val first = offerNonce ?: return null; val second = answerNonce ?: return null
        if (commitment(offerFp ?: return null, first) != commit || !hex32(second)) return null
        val hash = digest(listOf(DOMAIN, offerFp, answerFp, first, second).joinToString("\n"))
        val bits = ((hash[0].toInt() and 255) shl 16) or ((hash[1].toInt() and 255) shl 8) or (hash[2].toInt() and 255)
        return listOf(18, 12, 6, 0).map { (bits ushr it) and 63 }
    }
    companion object {
        const val DOMAIN = "UNYRI-CALL-SAS-2"
        const val REVEAL = "unyri-sas-2:"
        val symbols = listOf("⭐", "♥", "🌙", "☀", "☁", "⚡", "❄", "💧", "🔥", "🍃", "🌐", "🔔", "🔑", "🏠", "🚗", "✈", "🚲", "🎟", "🚌", "☂", "🎁", "👑", "⚑", "📖", "🔖", "📎", "✏", "✂", "🖌", "🔨", "🔍", "👁", "✋", "🎵", "🎸", "🎮", "🧩", "🎲", "🐢", "🐇", "🐜", "🐞", "🐾", "☕", "🍴", "🛒", "🛍", "🏷", "💡", "✉", "◉◉", "🗺", "⛰", "✨", "⌛", "⏰", "📣", "🎭", "🎞", "⚛", "⚙", "♠", "👕", "👓")
        fun hex32(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
        fun newNonce(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        fun commitment(fp: String, nonce: String): String? = if (hex32(nonce)) digest("$DOMAIN commit\n$fp\n$nonce").joinToString("") { "%02x".format(it) } else null
        fun fingerprint(sdp: String): String? {
            var result: String? = null
            for (raw in sdp.lineSequence()) {
                val line = raw.trim()
                if (!line.startsWith("a=fingerprint:", ignoreCase = true)) continue
                val parts = line.substringAfter(':').lowercase(java.util.Locale.ROOT).split(' ').filter { it.isNotEmpty() }
                if (parts.size != 2 || !parts[0].matches(Regex("[a-z0-9-]+"))) return null
                val pairs = parts[1].split(':')
                if (pairs.size < 16 || pairs.any { !it.matches(Regex("[0-9a-f]{2}")) }) return null
                val value = parts.joinToString(" ")
                if (result != null && result != value) return null
                result = value
            }
            return result
        }
    }
}

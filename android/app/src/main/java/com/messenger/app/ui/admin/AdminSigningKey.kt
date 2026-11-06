package com.messenger.app.ui.admin

import com.google.crypto.tink.subtle.Ed25519Sign
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Paper code: 150 random bits + 10 checksum bits. No network or persistent secret storage. */
object AdminSigningKey {
    fun acceptsPin(previous: String?, pending: String?, server: String, public: String?): Boolean {
        val observed="$server|${public ?: "null"}"
        return (previous==null && pending==null) || previous==observed || pending==observed ||
            (previous==null && public==null && pending?.substringBefore('|')==server)
    }
    private const val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    private fun checksum(value: String): String {
        val bytes = hash("COCO-ADMIN-CHECK-V1\u0000$value")
        return "${alphabet[(bytes[0].toInt() and 255) ushr 3]}${alphabet[((bytes[0].toInt() and 7) shl 2) or ((bytes[1].toInt() and 255) ushr 6)]}"
    }
    fun normalize(value: String) = value.uppercase(java.util.Locale.ROOT).filterNot { it == '-' || it.isWhitespace() }
    fun valid(value: String): Boolean {
        val raw = normalize(value)
        return raw.length == 32 && raw.all { it in alphabet } && raw.takeLast(2) == checksum(raw.take(30))
    }
    fun generate(): String {
        val bytes = ByteArray(30).also { SecureRandom().nextBytes(it) }
        val raw = bytes.map { alphabet[it.toInt() and 31] }.joinToString("")
        bytes.fill(0)
        return (raw + checksum(raw)).chunked(8).joinToString("-")
    }
    private fun seed(value: String): ByteArray {
        require(valid(value)) { "Invalid administrator key" }
        return hash("COCO-ADMIN-SEED-V1\u0000${normalize(value)}")
    }
    private fun encode(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    fun publicKey(value: String): String {
        val seed = seed(value)
        return try { encode(Ed25519Sign.KeyPair.newKeyPairFromSeed(seed).publicKey) } finally { seed.fill(0) }
    }
    fun sign(value: String, payload: String): String {
        val seed = seed(value)
        return try { encode(Ed25519Sign(seed).sign(payload.toByteArray(Charsets.UTF_8))) } finally { seed.fill(0) }
    }
    fun payload(server: String, user: String, session: String, id: String, purpose: String, publicKey: String, nonce: String): String {
        for (value in listOf(server,user,session,id)) require(java.util.UUID.fromString(value).toString() == value)
        require(purpose in listOf("unlock","rotate"))
        require(listOf(publicKey,nonce).all { it.matches(Regex("[A-Za-z0-9_-]{43}")) && Base64.getUrlDecoder().decode(it).size == 32 })
        return listOf("COCO-ADMIN-PROOF-V1",server,user,session,id,purpose,publicKey,nonce).joinToString("\n")
    }
}

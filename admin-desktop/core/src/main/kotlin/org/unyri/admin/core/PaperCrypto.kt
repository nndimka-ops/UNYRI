package org.unyri.admin.core

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

object PaperCrypto {
    const val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    fun normalize(value: String) = value.uppercase(java.util.Locale.ROOT).filterNot { it == '-' || it.isWhitespace() || Character.isSpaceChar(it) }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    fun complete(first30: String): String {
        require(first30.length == 30 && first30.all { it in alphabet })
        val h = hash("COCO-ADMIN-CHECK-V1\u0000$first30")
        return first30 + alphabet[(h[0].toInt() and 255) ushr 3] +
            alphabet[((h[0].toInt() and 7) shl 2) or ((h[1].toInt() and 255) ushr 6)]
    }
    fun checked(value: String): String {
        val n = normalize(value)
        if (n.length != 32 || n.any { it !in alphabet } || complete(n.take(30)) != n) throw AdminFailure(FailureKind.INVALID)
        return n
    }
    fun generate(): String {
        val random = SecureRandom(); return complete(buildString { repeat(30) { append(alphabet[random.nextInt(32)]) } })
    }
    fun seed(paper: String) = hash("COCO-ADMIN-SEED-V1\u0000${checked(paper)}")
    fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(value: String, size: Int): ByteArray {
        val bytes = runCatching { Base64.getUrlDecoder().decode(value) }.getOrElse { throw ProtocolFailure() }
        if (bytes.size != size || b64(bytes) != value) throw ProtocolFailure()
        return bytes
    }
    fun publicKey(paper: String): String {
        val seed = seed(paper)
        return try { b64(Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded) } finally { seed.fill(0) }
    }
    fun sign(paper: String, message: String): String {
        val seed = seed(paper)
        return try {
            val signer = Ed25519Signer(); signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
            val bytes = message.toByteArray(Charsets.UTF_8); signer.update(bytes, 0, bytes.size); b64(signer.generateSignature())
        } finally { seed.fill(0) }
    }
    fun verify(public: String, message: String, signature: String): Boolean = runCatching {
        val verifier = Ed25519Signer(); verifier.init(false, Ed25519PublicKeyParameters(decode(public, 32), 0))
        val bytes = message.toByteArray(Charsets.UTF_8); verifier.update(bytes, 0, bytes.size)
        verifier.verifySignature(decode(signature, 64))
    }.getOrDefault(false)
    fun uuid(value: String): String {
        val canonical = runCatching { UUID.fromString(value).toString() }.getOrElse { throw ProtocolFailure() }
        if (canonical != value) throw ProtocolFailure()
        return canonical
    }
    fun payload(c: Challenge): String {
        listOf(c.server_id, c.user_id, c.auth_session, c.challenge_id).forEach(::uuid)
        decode(c.public_key, 32)
        if (c.purpose !in setOf("unlock", "authorize", "enroll", "rotate") || c.nonce.isEmpty() ||
            c.nonce.length > 512 || c.nonce.any { it <= ' ' || it.code > 126 }) throw ProtocolFailure()
        return listOf("COCO-ADMIN-PROOF-V1", c.server_id, c.user_id, c.auth_session, c.challenge_id,
            c.purpose, c.public_key, c.nonce).joinToString("\n")
    }
    fun proof(paper: String, c: Challenge, server: String, user: String, purpose: String): KeyProof {
        if (c.server_id != server || c.user_id != user || c.purpose != purpose || c.public_key != publicKey(paper)) throw ProtocolFailure()
        return KeyProof(c.challenge_id, sign(paper, payload(c)))
    }
}

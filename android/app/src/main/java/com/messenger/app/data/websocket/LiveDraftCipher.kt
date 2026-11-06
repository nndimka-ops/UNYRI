package com.messenger.app.data.websocket

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wire format matches CryptoKit AES.GCM.SealedBox.combined: nonce | ciphertext | tag. */
internal object LiveDraftCipher {
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray, nonce: ByteArray = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }): ByteArray {
        require(key.size == 32 && nonce.size == 12 && plaintext.size <= 12000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }
    fun open(key: ByteArray, combined: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32 && combined.size in 28..16384)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        return cipher.doFinal(combined.copyOfRange(12, combined.size))
    }
}

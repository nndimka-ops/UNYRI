package com.messenger.app.data.auth

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.DeviceKeyDto
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.UUID

/** A random alias is persisted with one workspace's session, never an installation credential. */
class DeviceSessionBinding private constructor(val alias: String, val key: DeviceKeyDto) {
    @Volatile private var clockOffsetSeconds = 0L
    fun proof(method: String, target: String, session: String, token: String, serverTime: Long? = null): String {
        val now = Instant.now().epochSecond
        serverTime?.let { require(it in 0..253402300799L); clockOffsetSeconds = it - now }
        return DeviceProofBuilder.proof(method, target, session, now + clockOffsetSeconds, token) { bytes ->
            try {
                val privateKey = keyStore().getKey(alias, null) as? PrivateKey ?: throw missingKey()
                Signature.getInstance("SHA256withECDSA").run { initSign(privateKey); update(bytes); sign() }
            } catch (_: android.security.keystore.KeyPermanentlyInvalidatedException) { throw missingKey() }
              catch (_: java.security.UnrecoverableKeyException) { throw missingKey() }
        }
    }

    fun erase() { erase(alias) }
    override fun toString() = "DeviceSessionBinding(redacted)"

    companion object {
        private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        private fun missingKey() = AuthApiException.Api("session_revoked", "Session key unavailable", 401)
        fun erase(alias: String) { keyStore().deleteEntry(alias) }

        fun create(context: Context): DeviceSessionBinding {
            val alias = "unyri.session.p256." + UUID.randomUUID()
            val strongBox = Build.VERSION.SDK_INT >= 28 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            var generatedInStrongBox = false
            try {
                if (strongBox) {
                    try { generate(alias, true); generatedInStrongBox = true }
                    catch (_: StrongBoxUnavailableException) { erase(alias); generate(alias, false) }
                } else generate(alias, false)
                return open(alias, if (generatedInStrongBox) "strongbox" else "tee")
            } catch (failure: Exception) {
                runCatching { erase(alias) }
                throw failure
            }
        }

        private fun generate(alias: String, strongBox: Boolean) {
            val specification = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
            if (Build.VERSION.SDK_INT >= 28) specification.setIsStrongBoxBacked(strongBox)
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
                initialize(specification.build()); generateKeyPair()
            }
        }

        fun open(alias: String, protectionHint: String? = null): DeviceSessionBinding =
            try { openHardware(alias, protectionHint) }
            catch (_: android.security.keystore.KeyPermanentlyInvalidatedException) { throw missingKey() }
            catch (_: java.security.UnrecoverableKeyException) { throw missingKey() }

        private fun openHardware(alias: String, protectionHint: String?): DeviceSessionBinding {
            val store = keyStore()
            val privateKey = store.getKey(alias, null) as? PrivateKey ?: throw missingKey()
            val info = KeyFactory.getInstance(privateKey.algorithm, "AndroidKeyStore").getKeySpec(privateKey, KeyInfo::class.java)
            val protection = if (Build.VERSION.SDK_INT >= 31) when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "tee"
                else -> throw HardwareDeviceKeyUnavailableException()
            } else {
                @Suppress("DEPRECATION")
                if (!info.isInsideSecureHardware) throw HardwareDeviceKeyUnavailableException()
                // API 28-30 exposes only the hardware boolean. Persist our successful generation choice.
                if (protectionHint == "strongbox") "strongbox" else "tee"
            }
            val publicKey = store.getCertificate(alias)?.publicKey as? ECPublicKey ?: throw missingKey()
            fun coordinate(bytes: ByteArray): ByteArray {
                val unsigned = if (bytes.size == 33 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, 33) else bytes
                require(unsigned.size <= 32)
                return ByteArray(32 - unsigned.size) + unsigned
            }
            val encoded = byteArrayOf(4) + coordinate(publicKey.w.affineX.toByteArray()) + coordinate(publicKey.w.affineY.toByteArray())
            return DeviceSessionBinding(alias, DeviceKeyDto(DeviceProofBuilder.base64(encoded), protection))
        }
    }
}

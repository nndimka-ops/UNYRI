package com.messenger.app.data.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.messenger.app.data.auth.InstallationCredentialStorage
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Service-only proof, encrypted by Android Keystore, excluded from OS backups.
 * No account identifiers, bearer tokens, messages or history are stored here.
 * Logout intentionally preserves it; uninstall removes file and Keystore key.
 */
class AndroidInstallationCredentialStore(context: Context) : InstallationCredentialStorage {
    private val directory = File(context.noBackupFilesDir, "installation-protection")

    @Synchronized
    override fun read(origin: String): String? {
        val atomic = atomic(origin)
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        val data = atomic.openRead().use { input ->
            require(input.available() <= MAX_FILE_BYTES) { "Invalid installation protection storage" }
            input.readBytes().also { require(it.size in 30..MAX_FILE_BYTES && it[0] == 1.toByte()) { "Invalid installation protection storage" } }
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Losing a key is an error, not a request for a new unrelated identity.
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, data.copyOfRange(1, 13)))
        cipher.updateAAD(origin.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(data.copyOfRange(13, data.size)).toString(Charsets.UTF_8)
    }

    @Synchronized
    override fun write(origin: String, credential: String) {
        require(directory.isDirectory || directory.mkdirs()) { "Cannot save installation protection" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(origin.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(credential.toByteArray(Charsets.UTF_8))
        require(cipher.iv.size == 12)
        val atomic = atomic(origin)
        val output = atomic.startWrite()
        try {
            output.write(byteArrayOf(1) + cipher.iv + encrypted)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    internal fun fileFor(origin: String): File {
        val tag = MessageDigest.getInstance("SHA-256").digest(origin.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return File(directory, "$tag.bin")
    }
    private fun atomic(origin: String) = AtomicFile(fileFor(origin))

    private fun key(create: Boolean): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        check(create) { "Installation protection key is unavailable" }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "coco.installation.protection.v1"
        private const val MAX_FILE_BYTES = 1024
        private val keyLock = Any()
    }
}

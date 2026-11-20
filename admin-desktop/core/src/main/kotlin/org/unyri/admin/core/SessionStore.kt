package org.unyri.admin.core

import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit

interface SessionStore {
    fun save(session: Session)
    fun load(origin: String): Session?
    fun delete(origin: String)
}

object AppPaths {
    val root: Path get() = if (System.getProperty("os.name").startsWith("Windows"))
        Path.of(System.getenv("LOCALAPPDATA") ?: throw AdminFailure(FailureKind.STORAGE), "UNYRI", "AdminDesktop")
    else Path.of(System.getenv("XDG_CONFIG_HOME")?.takeIf { Path.of(it).isAbsolute } ?: (System.getProperty("user.home") + "/.config"), "unyri-admin")
}
class Preferences(private val file: Path = AppPaths.root.resolve("preferences.properties")) {
    private val values = Properties().apply { if (Files.exists(file)) Files.newInputStream(file).use(::load) }
    var language: String get() = values.getProperty("language", "ru").takeIf { it in setOf("ru", "en", "uk") } ?: "ru"
        set(value) { require(value in setOf("ru", "en", "uk")); values.setProperty("language", value); save() }
    var origin: String get() = values.getProperty("origin", "")
        set(value) { values.setProperty("origin", Origin(value).value); save() }
    val installation: String get() = values.getProperty("installation") ?: UUID.randomUUID().toString().also { values.setProperty("installation", it); save() }
    private fun save() {
        Files.createDirectories(file.parent)
        Files.newOutputStream(file).use { values.store(it, "Nonsecret desktop preferences") }
    }
}

class WindowsSessionStore(private val file: Path = AppPaths.root.resolve("session.bin")) : SessionStore {
    private val entropy = "UNYRI Admin Desktop session v1".toByteArray()
    override fun save(session: Session) {
        if (!session.optedIn || !session.valid()) throw AdminFailure(FailureKind.STORAGE)
        val clear = wireJson.encodeToString(session).toByteArray(Charsets.UTF_8)
        try {
            val encrypted = Crypt32Util.cryptProtectData(clear, entropy, 1, "UNYRI Admin Desktop", null)
            Files.createDirectories(file.parent)
            val temporary = Files.createTempFile(file.parent, "session-", ".tmp")
            try { Files.write(temporary, encrypted); Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            finally { Files.deleteIfExists(temporary) }
        } catch (e: Exception) { throw AdminFailure(FailureKind.STORAGE).apply { initCause(e) } } finally { clear.fill(0) }
    }
    override fun load(origin: String): Session? {
        if (!Files.exists(file)) return null
        return try {
            if (Files.size(file) > 65536) throw AdminFailure(FailureKind.STORAGE)
            val clear = Crypt32Util.cryptUnprotectData(Files.readAllBytes(file), entropy, 1, null)
            try { wireJson.decodeFromString<Session>(clear.toString(Charsets.UTF_8)).takeIf { it.origin == origin && it.optedIn && it.valid() }
                ?: run { delete(origin); null } } finally { clear.fill(0) }
        } catch (_: Exception) { Files.deleteIfExists(file); throw AdminFailure(FailureKind.STORAGE) }
    }
    override fun delete(origin: String) { Files.deleteIfExists(file) }
}

/** secret-tool is libsecret's CLI; it talks to the desktop Secret Service over D-Bus. No plaintext fallback. */
class LinuxSessionStore(private val executable: String = "/usr/bin/secret-tool") : SessionStore {
    private fun execute(action: String, origin: String, secret: ByteArray? = null): String {
        val args = mutableListOf(executable, action)
        if (action == "store") args += listOf("--label=UNYRI Admin Desktop session")
        args += listOf("application", "org.unyri.admin", "origin", origin)
        val process = try { ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start() }
            catch (_: Exception) { throw AdminFailure(FailureKind.STORAGE) }
        try {
            process.outputStream.use { if (secret != null) it.write(secret) }
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw AdminFailure(FailureKind.STORAGE)
            val output = process.inputStream.use { it.readNBytes(65537) }
            if (output.size > 65536 || process.exitValue() !in (if (action == "lookup" || action == "clear") 0..1 else 0..0)) throw AdminFailure(FailureKind.STORAGE)
            return output.toString(Charsets.UTF_8).trimEnd('\n', '\r')
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
    override fun save(session: Session) {
        if (!session.optedIn || !session.valid()) throw AdminFailure(FailureKind.STORAGE)
        val clear = wireJson.encodeToString(session).toByteArray(Charsets.UTF_8)
        try { execute("store", session.origin, clear) } finally { clear.fill(0) }
    }
    override fun load(origin: String): Session? {
        val text = execute("lookup", origin)
        if (text.isEmpty()) return null
        return runCatching { wireJson.decodeFromString<Session>(text) }.getOrNull()?.takeIf { it.origin == origin && it.optedIn && it.valid() }
            ?: run { delete(origin); null }
    }
    override fun delete(origin: String) { execute("clear", origin) }
}
fun platformSessionStore(): SessionStore = when {
    System.getProperty("os.name").startsWith("Windows") -> WindowsSessionStore()
    System.getProperty("os.name").startsWith("Linux") -> LinuxSessionStore()
    else -> object : SessionStore {
        override fun save(session: Session) { throw AdminFailure(FailureKind.STORAGE) }
        override fun load(origin: String): Session? = null
        override fun delete(origin: String) = Unit
    }
}

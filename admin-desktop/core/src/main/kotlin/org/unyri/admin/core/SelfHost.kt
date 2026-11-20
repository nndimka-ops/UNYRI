package org.unyri.admin.core

import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.Properties

enum class HostStep { DOCKER, NAME, INTERNET, PORTS, OPTIONS, INSTALL, FINISH }
enum class FirewallState { PENDING, APPLIED, SKIPPED, FAILED }
data class HostState(
    val step: HostStep = HostStep.DOCKER, val docker: Boolean = false,
    val firewall: FirewallState = FirewallState.PENDING, val installing: Boolean = false,
    val installed: Boolean = false, val publicHealthy: Boolean = false, val failed: Boolean = false,
    val ownerCodePresent: Boolean = false, val portsChecked: Boolean = false
) {
    fun next(valid: Boolean = true): HostState = when {
        installing || !valid -> this
        step == HostStep.DOCKER && !docker -> this
        step == HostStep.PORTS && (!portsChecked || firewall == FirewallState.PENDING) -> this
        step == HostStep.INSTALL && !installed -> this
        step == HostStep.FINISH -> this
        else -> copy(step = HostStep.entries[step.ordinal + 1])
    }
    fun back(): HostState = if (installing || installed || step.ordinal == 0) this else copy(step = HostStep.entries[step.ordinal - 1])
    fun installing() = copy(step = HostStep.INSTALL, installing = true, failed = false)
    fun completed(publicHealthy: Boolean, code: Boolean) = copy(step = HostStep.FINISH, installing = false,
        installed = true, publicHealthy = publicHealthy, ownerCodePresent = code, failed = false)
    fun failure() = copy(installing = false, failed = true)
}

object HostValidation {
    fun domain(value: String): Boolean = value.length in 3..253 && value.matches(Regex("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?"))
    fun ipv4(value: String): Boolean = value.split('.').let { it.size == 4 && it.all { p -> p.matches(Regex("0|[1-9][0-9]{0,2}")) && p.toInt() <= 255 } }
    fun privateWan(value: String): Boolean {
        if (!ipv4(value)) return false
        val p = value.split('.').map(String::toInt)
        return p[0] == 10 || p[0] == 127 || p[0] == 0 || p[0] == 192 && p[1] == 168 ||
            p[0] == 172 && p[1] in 16..31 || p[0] == 100 && p[1] in 64..127 || p[0] == 169 && p[1] == 254
    }
    fun cgnat(wan: String, publicIp: String): Boolean = privateWan(wan) || ipv4(wan) && ipv4(publicIp) && wan != publicIp
    fun serverName(value: String) = value.codePointCount(0, value.length) in 1..64 && value.none { Character.getType(it) in setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt()) }
    fun publicIpv4(value: String) = ipv4(value) && !privateWan(value) && value.substringBefore('.').toInt() < 224
    fun setupCode(value: String) = value.matches(Regex("[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){3}"))
}

/** No command line or arbitrary child output is rendered, persisted or included in exceptions. */
data class HostCommand(val arguments: List<String>, val timeoutSeconds: Long = 60) {
    override fun toString() = "HostCommand(redacted)"
}
data class HostOutput(val exit: Int, val text: String) { override fun toString() = "HostOutput(redacted)" }
fun interface HostRunner { fun run(command: HostCommand, phase: (String) -> Unit): HostOutput }
object HostPhases {
    val allowed = setOf("prepare", "pull", "build", "start", "health", "policy", "public", "ready", "backup", "restore", "stop", "delete")
    fun parse(line: String): String? = line.removePrefix("UNYRI_GUI:").takeIf { line.startsWith("UNYRI_GUI:") && it in allowed }
}
/** Only bounded, structured port identities may reach the localized setup screen. */
data class HostPortConflict(val protocol: String, val ports: String, val kind: String, val holder: String)
object HostPortConflicts {
    fun parse(text: String): List<HostPortConflict> = text.lineSequence().mapNotNull { line ->
        if (!line.startsWith("UNYRI_PORT_BUSY:") || line.length > 512) return@mapNotNull null
        val fields = line.removePrefix("UNYRI_PORT_BUSY:").split('|')
        if (fields.size != 4) return@mapNotNull null
        val (protocol, ports, kind, holder) = fields
        if (protocol !in setOf("TCP", "UDP") || kind !in setOf("process", "container", "unknown")) return@mapNotNull null
        val ranges = ports.split(',')
        if (ranges.size !in 1..64 || ranges.any { range ->
            !range.matches(Regex("[0-9]{1,5}(?:-[0-9]{1,5})?")) ||
                range.split('-').map(String::toInt).let { it.any { port -> port !in 1..65535 } || it.first() > it.last() }
        }) return@mapNotNull null
        if (holder.isBlank() || holder.length > 120 || holder.any { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }) return@mapNotNull null
        HostPortConflict(protocol, ports, kind, holder)
    }.distinct().take(64).toList()
}
object HostImage {
    fun version(text: String): String = runCatching {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(text.trim())
        val obj = (if (element is kotlinx.serialization.json.JsonArray) element.firstOrNull() else element) as? kotlinx.serialization.json.JsonObject ?: return ""
        val repo = (obj["Repository"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val tag = (obj["Tag"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        "$repo:$tag".takeIf { it.length in 3..240 && it.matches(Regex("[a-zA-Z0-9_./:@-]+")) }.orEmpty()
    }.getOrDefault("")
}
class ProcessHostRunner : HostRunner {
    override fun run(command: HostCommand, phase: (String) -> Unit): HostOutput {
        val process = try { ProcessBuilder(command.arguments).redirectErrorStream(true).start() }
            catch (_: Exception) { return HostOutput(-1, "") }
        // A bounded reader drains output while the caller waits; even a malicious tool cannot grow memory.
        val output = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().use { input ->
                val line = StringBuilder()
                while (true) {
                    val c = input.read(); if (c == -1) break
                    if (output.length < 8192) output.append(c.toChar())
                    if (c == 10) { HostPhases.parse(line.toString().trim())?.let(phase); line.setLength(0) }
                    else if (line.length < 128) line.append(c.toChar())
                }
            } }
        }.apply { isDaemon = true; start() }
        try {
            process.outputStream.close()
            if (!process.waitFor(command.timeoutSeconds, TimeUnit.SECONDS)) {
                process.descendants().forEach { it.destroyForcibly() }; process.destroyForcibly(); reader.join(2000)
                return HostOutput(-2, "")
            }
            reader.join(2000)
            return HostOutput(process.exitValue(), output.toString())
        } catch (e: InterruptedException) {
            process.descendants().forEach { it.destroyForcibly() }; process.destroyForcibly()
            Thread.currentThread().interrupt(); throw e
        }
    }
}

data class HostInstall(val root: Path, val domain: String, val publicIp: String, val rooms: Boolean, val invites: Boolean, val source: Boolean = false, val serverName: String = "UNYRI", val localTrial: Boolean = false) {
    override fun toString() = "HostInstall(redacted)"
}
enum class HostInstallResult(val installed: Boolean, val publicVerified: Boolean) {
    VERIFIED(true, true), LOCAL_ONLY(true, false), FAILED(false, false);
    companion object { fun fromExit(exit: Int) = when (exit) { 0 -> VERIFIED; 3 -> LOCAL_ONLY; else -> FAILED } }
}
class HostCommands(val windows: Boolean) {
    fun script(root: Path, action: String, extra: List<String> = emptyList(), timeout: Long = 60): HostCommand {
        require(action in setOf("install", "backup", "restore", "firewall", "upnp", "manage", "preflight-ports"))
        val file = root.resolve("scripts/selfhost/${if (action in setOf("firewall", "upnp", "manage")) "desktop-$action" else action}.${if (windows) "ps1" else "sh"}")
        return HostCommand((if (windows) listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", file.toString())
            else listOf("bash", file.toString())) + extra, timeout)
    }
    fun install(config: HostInstall): HostCommand {
        require((if (config.localTrial) config.domain == "localhost" && config.publicIp == "127.0.0.1" && !config.rooms
            else HostValidation.domain(config.domain) && HostValidation.publicIpv4(config.publicIp)) && HostValidation.serverName(config.serverName))
        // Registration stays open until the first account claims ownership through SH-2.
        val args = if (windows) listOf("-NonInteractive", "-SkipFirewall", "-Domain", config.domain,
            "-ServerName", config.serverName, "-PublicIPv4", config.publicIp, "-RoomsChoice", if (config.rooms) "yes" else "no") +
            (if (config.source) listOf("-FromSource") else emptyList())
        else listOf("--non-interactive", "--domain", config.domain, "--server-name", config.serverName,
            "--public-ipv4", config.publicIp, "--rooms", if (config.rooms) "yes" else "no") +
            (if (config.source) listOf("--from-source") else emptyList())
        return script(config.root, "install", args, 3600)
    }
    fun update(root: Path): HostCommand {
        val source = Files.isRegularFile(root.resolve(".selfhost-from-source"))
        val trial = Files.isRegularFile(root.resolve(".selfhost-local-trial"))
        val args = if (windows) listOf("-NonInteractive", "-SkipFirewall") +
            (if (source) listOf("-FromSource") else emptyList()) +
            (if (trial) listOf("-Domain", "localhost", "-PublicIPv4", "127.0.0.1") else emptyList())
        else listOf("--non-interactive") + (if (source) listOf("--from-source") else emptyList()) +
            (if (trial) listOf("--domain", "localhost", "--public-ipv4", "127.0.0.1") else emptyList())
        return script(root, "install", args, 3600)
    }
    fun preflight(root: Path, rooms: Boolean, localTrial: Boolean = false) = script(root, "preflight-ports",
        if (windows) listOf("-Check") + (if (rooms) listOf("-Rooms") else emptyList()) + (if (localTrial) listOf("-LocalTrial") else emptyList())
        else listOf(if (rooms) "rooms" else "", localTrial.toString()), 180)
    fun compose(root: Path, vararg args: String) = HostCommand(listOf("docker", "compose", "--project-name", "unyri-selfhost", "--env-file",
        root.resolve(".env").toString(), "-f", root.resolve("docker-compose.selfhost.yml").toString()) +
        (if (Files.isRegularFile(root.resolve(".selfhost-from-source"))) listOf("-f", root.resolve("scripts/selfhost/compose.source.yml").toString()) else emptyList()) + args, 360)
    // backend_data is mounted at /app/data; use the installer's private redisplay path.
    fun ownerCode(root: Path) = compose(root, "exec", "-T", "backend", "cat", "/app/data/owner-setup-code")

}

class HostService(private val runner: HostRunner = ProcessHostRunner(), val commands: HostCommands = HostCommands(System.getProperty("os.name").startsWith("Windows")),
    private val healthProbe: ((String) -> Boolean)? = null) {
    fun run(command: HostCommand, phase: (String) -> Unit = {}): HostOutput = runner.run(command, phase)
    fun docker(): Boolean {
        // Reject TCP/SSH contexts before executing any daemon operation, including health and deletion.
        val context = System.getenv("DOCKER_CONTEXT")
        val endpoint = if (context.isNullOrBlank()) System.getenv("DOCKER_HOST").orEmpty() else ""
        val host = endpoint.ifBlank { run(HostCommand(listOf("docker", "context", "inspect") +
            (if (context.isNullOrBlank()) emptyList() else listOf(context)) + listOf("--format", "{{.Endpoints.docker.Host}}"))).let { if (it.exit == 0) it.text.trim() else "" } }
        if (!(host.startsWith("unix:") || commands.windows && host.startsWith("npipe:"))) return false
        val info = run(HostCommand(listOf("docker", "info", "--format", "{{.OSType}}|{{.OperatingSystem}}")))
        val version = run(HostCommand(listOf("docker", "compose", "version", "--short")))
        val parts = Regex("v?(\\d+)\\.(\\d+)").find(version.text)?.groupValues ?: return false
        if (commands.windows) {
            val wsl = run(HostCommand(listOf("wsl.exe", "--list", "--running", "--verbose")))
            if (wsl.exit != 0 || !Regex("(?im)^.*docker-desktop\\s+\\S+\\s+2\\s*$").containsMatchIn(wsl.text.replace("\u0000", "")) ||
                !info.text.contains("Docker Desktop")) return false
        }
        return info.exit == 0 && info.text.trim().substringBefore('|') == "linux" && version.exit == 0 &&
            (parts[1].toInt() > 2 || parts[1].toInt() == 2 && parts[2].toInt() >= 24)
    }
    fun requireLocal() { check(docker()) { "Local Docker unavailable" } }
    fun claim(root: Path) {
        requireLocal()
        require(Files.isDirectory(root) && !Files.exists(root.resolve(".git")))
        require(Files.isRegularFile(root.resolve("scripts/selfhost/install.${if (commands.windows) "ps1" else "sh"}")))
        require(Files.isRegularFile(root.resolve("docker-compose.selfhost.yml")))
        val marker = root.resolve("desktop-install.marker")
        if (!Files.exists(marker)) {
            val containers = run(HostCommand(listOf("docker", "ps", "-a", "--filter", "label=com.docker.compose.project=unyri-selfhost", "--format", "{{.ID}}")))
            val volumes = run(HostCommand(listOf("docker", "volume", "ls", "--format", "{{.Name}}")))
            check(containers.exit == 0 && containers.text.isBlank() && volumes.exit == 0 &&
                volumes.text.lineSequence().none { it.startsWith("unyri-selfhost_") })
            // Never adopt a preexisting configuration, including a failed terminal installation.
            check(!Files.exists(root.resolve(".env")))
            Files.writeString(marker, root.toRealPath().toString(), StandardOpenOption.CREATE_NEW)
        }
        assertOwnership(root)
    }
    fun assertOwnership(root: Path) {
        requireLocal()
        check(Files.readString(root.resolve("desktop-install.marker")).trim() == root.toRealPath().toString())
        val result = run(HostCommand(listOf("docker", "ps", "-a", "--filter", "label=com.docker.compose.project=unyri-selfhost", "--format", "{{.Label \"com.docker.compose.project.working_dir\"}}")))
        check(result.exit == 0)
        check(result.text.lineSequence().filter(String::isNotBlank).all { Path.of(it.trim()).toAbsolutePath().normalize() == root.toAbsolutePath().normalize() })
    }
    fun ownerCode(root: Path): String? = run(commands.ownerCode(root)).takeIf { it.exit == 0 }?.text?.trim()?.takeIf(HostValidation::setupCode)
    fun addresses(root: Path): ConnectionDoctorAddresses {
        // OS-local ownership suffices for the read-only snapshot, including while Docker is stopped.
        check(Files.readString(root.resolve("desktop-install.marker")).trim() == root.toRealPath().toString())
        return DoctorEnvironment.read(root)
    }
    fun publicIp(consent: Boolean): String {
        require(consent)
        val body = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()
            .send(java.net.http.HttpRequest.newBuilder(java.net.URI("https://api.ipify.org")).timeout(java.time.Duration.ofSeconds(8)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream())
        val value = body.body().use { String(it.readNBytes(65), Charsets.US_ASCII).trim() }
        check(body.statusCode() == 200 && HostValidation.publicIpv4(value)); return value
    }
    fun health(domain: String): Boolean = healthProbe?.invoke(domain) ?: runCatching {
        require(HostValidation.domain(domain))
        val data = JvmTransport(Origin("https://$domain")).send(WireRequest("GET", "/health", null, null, null))
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(data) as kotlinx.serialization.json.JsonObject
        obj["status"].toString() == "\"ok\"" && obj["database"].toString() == "true" && obj["redis"].toString() == "true"
    }.getOrDefault(false)
    fun network(): Pair<String, String> {
        val command = if (commands.windows) HostCommand(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "\$r=Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' | Sort-Object RouteMetric | Select-Object -First 1; if(\$r){ (Get-NetIPAddress -AddressFamily IPv4 -InterfaceIndex \$r.InterfaceIndex | Where-Object { \$_.IPAddress -notlike '169.254.*' } | Select-Object -First 1).IPAddress; \$r.NextHop }"))
        else HostCommand(listOf("ip", "-4", "route", "get", "192.0.2.1"))
        val result = run(command)
        val addresses = Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b").findAll(result.text).map { it.value }.filter(HostValidation::ipv4).toList()
        val lan = if (commands.windows) addresses.firstOrNull() else Regex("\\bsrc\\s+([0-9.]+)").find(result.text)?.groupValues?.get(1)
        val gateway = if (commands.windows) addresses.getOrNull(1) else Regex("\\bvia\\s+([0-9.]+)").find(result.text)?.groupValues?.get(1)
        return lan.orEmpty() to gateway.orEmpty()
    }
}

/** Nonsecret location record only. Never discover installations by reading environment files. */
class HostRecord(private val file: Path) {
    fun load(): Pair<Path, String>? = runCatching {
        val p = Properties().apply { Files.newBufferedReader(file).use(::load) }
        Path.of(p.getProperty("root")) to p.getProperty("domain").also { require(HostValidation.domain(it) || it == "localhost") }
    }.getOrNull()
    fun save(root: Path, domain: String) {
        require(HostValidation.domain(domain) || domain == "localhost"); Files.createDirectories(file.parent)
        Properties().apply { setProperty("root", root.toAbsolutePath().toString()); setProperty("domain", domain) }
            .let { p -> Files.newBufferedWriter(file).use { p.store(it, "Local server location; no credentials") } }
    }
    fun forget() { Files.deleteIfExists(file) }
}

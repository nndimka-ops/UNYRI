package org.unyri.admin

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.unyri.admin.core.*
import java.nio.file.*

class HostModel(private val scope: CoroutineScope, val service: HostService = HostService(),
    private val record: HostRecord = HostRecord(Path.of(System.getProperty("user.home"), ".unyri-admin", "local-server.properties")),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO, val demo: Boolean = false) {
    var state by mutableStateOf(HostState())
    var root by mutableStateOf<Path?>(null)
    var domain by mutableStateOf("")
    var serverName by mutableStateOf("UNYRI")
    val localTrial get() = root?.resolve(".selfhost-local-trial")?.let(Files::isRegularFile) == true
    val origin get() = if (demo) "https://home.example.test" else "https://$domain" + if (localTrial) ":18443" else ""
    var publicIp by mutableStateOf("")
    var wan by mutableStateOf("")
    var lan by mutableStateOf("")
    var router by mutableStateOf("")
    var rooms by mutableStateOf(false)
    var invites by mutableStateOf(false)
    var duck by mutableStateOf(false)
    var duckToken by mutableStateOf("")
    var duckConsent by mutableStateOf(false)
    private val ddns = DuckDns(Path.of(System.getProperty("user.home"), ".unyri-admin", "selfhost", "duckdns-token"))
    fun saveDuckToken() = task { try { io { ddns.save(duckToken) }; message = "sh.tokenSaved" } finally { duckToken = "" } }
    fun updateDuck() = task { check(!localTrial); check(io { ddns.update(domain, publicIp, duckConsent) }); message = "sh.ddnsUpdated" }
    var ipConsent by mutableStateOf(false)
    var upnpConsent by mutableStateOf(false)
    var sourceConsent by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var phases by mutableStateOf<List<String>>(emptyList())
    var ownerCode by mutableStateOf<String?>(null)
    var healthy by mutableStateOf<Boolean?>(null)
    var version by mutableStateOf("")
    var created by mutableStateOf(false)
    var confirmation by mutableStateOf<String?>(null)
    var typed by mutableStateOf("")
    var eraseBackup by mutableStateOf(false)
    var restorePath by mutableStateOf<Path?>(null)
    var doctor by mutableStateOf<DoctorViewData?>(null); private set
    var doctorLoading by mutableStateOf(false); private set
    private var doctorJob: Job? = null
    private var doctorGeneration = 0L
    private var doctorOrigin: String? = null
    fun openAddresses(activeOrigin: String?) { doctorOrigin = activeOrigin; loadAddresses() }
    fun closeAddresses() { doctorGeneration++; doctorJob?.cancel(); doctor = null; doctorLoading = false; doctorOrigin = null }
    private fun loadAddresses() {
        val generation = ++doctorGeneration
        doctorJob?.cancel(); doctorLoading = true; doctor = null
        val path = root; val active = doctorOrigin
        doctorJob = scope.launch {
            try {
                val now = java.time.Instant.now()
                val addresses = if (demo) DoctorDemo.addresses(now) else io { service.addresses(path ?: error("No installation")) }
                if (doctorGeneration == generation) doctor = DoctorViewData(addresses, now, active)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (doctorGeneration == generation) doctor = DoctorViewData(null, java.time.Instant.now(), error = "Could not load addresses. Try again.") }
            finally { if (doctorGeneration == generation) doctorLoading = false }
        }
    }
    val windows get() = service.commands.windows
    val cgnat get() = HostValidation.cgnat(wan, publicIp)
    val imageAvailable get() = root?.resolve("SELFHOST_VERSION")?.let(Files::isRegularFile) == true
    init { if (demo) { created = true; domain = "home.example.test"; healthy = true }
        else record.load()?.let { (path, name) -> root = path; domain = name; created = Files.isRegularFile(path.resolve("desktop-local-ready")) } }
    fun clearCodes() { ownerCode = null; duckToken = "" }
    private fun task(operation: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; message = null
        scope.launch {
            try { operation() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "sh.failed"; if (state.installing) state = state.failure() }
            finally { busy = false }
        }
    }
    private suspend fun <T> io(block: () -> T): T = runInterruptible(ioDispatcher, block)
    fun checkDocker() = task { val ready = io { service.docker() }; state = state.copy(docker = ready); if (!ready) error = "sh.dockerMissing" }
    fun network() = task { val n = io { service.network() }; lan = n.first; router = n.second }
    fun detectIp() = task { check(!localTrial); publicIp = io { service.publicIp(ipConsent) } }
    fun next() {
        state = state.next(when (state.step) {
            HostStep.NAME -> (HostValidation.domain(domain) || localTrial && domain == "localhost") && HostValidation.serverName(serverName) && (!duck || domain.endsWith(".duckdns.org"))
            HostStep.INTERNET -> HostValidation.publicIpv4(publicIp) || localTrial && publicIp == "127.0.0.1"
            else -> true
        })
        if (state.step == HostStep.INTERNET && lan.isBlank()) network()
    }
    fun selectRoot(path: Path) {
        // Selecting a directory is not authorization to execute arbitrary scripts from it.
        if (Files.exists(path.resolve(".git")) || !Files.isRegularFile(path.resolve("scripts/selfhost/install.${if (windows) "ps1" else "sh"}"))) {
            error = "sh.bundleInvalid"; return
        }
        root = path.toAbsolutePath().normalize(); sourceConsent = false; error = null
        if (localTrial) { domain = "localhost"; publicIp = "127.0.0.1"; rooms = false; state = state.copy(firewall = FirewallState.SKIPPED) }
    }
    fun checkPorts() = task {
        val path = root ?: error("No release")
        val result = io { service.requireLocal(); service.run(service.commands.preflight(path, rooms, localTrial)) }
        state = state.copy(portsChecked = result.exit == 0)
        message = if (result.exit == 0) "sh.portsReady" else "sh.portsBusy"
    }
    fun firewall() = task {
        check(!localTrial)
        val path = root ?: error("No release")
        val result = io { service.requireLocal(); service.run(service.commands.script(path, "firewall", if (rooms) listOf("-Rooms") else emptyList(), 180)) }
        state = state.copy(firewall = if (result.exit == 0) FirewallState.APPLIED else FirewallState.FAILED)
        if (result.exit != 0) error = "sh.firewallFailed"
    }
    fun upnp() = task {
        check(upnpConsent && !localTrial)
        val path = root ?: error("No release")
        val result = io { service.requireLocal(); service.run(service.commands.script(path, "upnp", if (windows) listOf("-RoomsChoice", if (rooms) "yes" else "no") else listOf(if (rooms) "yes" else "no"), 180)) }
        message = if (result.exit == 0) "sh.upnpRequested" else "sh.upnpFailed"
    }
    private suspend fun execute(command: HostCommand) = io {
        service.requireLocal()
        service.run(command) { phase -> scope.launch { phases = (phases + phase).takeLast(80) } }.also { check(it.exit == 0) }
    }
    fun install() = task {
        val path = root ?: error("No release")
        check(imageAvailable || sourceConsent)
        state = state.installing(); phases = emptyList()
        io { service.claim(path) }
        // Save the location before installation, so failed/partially installed stacks can be retried after restart.
        io { record.save(path, domain) }
        val result = io {
            service.requireLocal()
            service.run(service.commands.install(HostInstall(path, domain, publicIp, rooms, invites, sourceConsent, serverName, localTrial))) { phase ->
                scope.launch { phases = (phases + phase).takeLast(80) }
            }
        }
        val outcome = HostInstallResult.fromExit(result.exit)
        check(outcome.installed)
        io { Files.writeString(path.resolve("desktop-local-ready"), "Local installer health passed") }
        created = true
        healthy = true // Both accepted installer exits guarantee local readiness.
        state = state.completed(outcome.publicVerified, false)
        message = if (outcome.publicVerified) "sh.ready" else "sh.publicFailed"
    }
    fun revealCodes() = task {
        val path = root ?: error("No release")
        io { service.requireLocal(); service.assertOwnership(path) }
        ownerCode = io { service.ownerCode(path) }
        state = state.copy(ownerCodePresent = ownerCode != null)
        if (ownerCode == null) message = "sh.noCode"
    }
    fun refresh() {
        loadAddresses()
        if (demo) return
        task {
            val path = root ?: error("No release")
            io { service.requireLocal(); service.assertOwnership(path) }
            healthy = io { service.run(service.commands.compose(path, "exec", "-T", "backend", "curl", "--fail", "--silent", "http://127.0.0.1:8080/health")) }.let {
                it.exit == 0 && runCatching { val obj = kotlinx.serialization.json.Json.parseToJsonElement(it.text) as kotlinx.serialization.json.JsonObject
                    obj["status"].toString() == "\"ok\"" && obj["database"].toString() == "true" && obj["redis"].toString() == "true" }.getOrDefault(false)
            }
            version = io { service.run(service.commands.compose(path, "images", "backend", "--format", "json")) }.let {
                // Image identity is bounded and restricted; never render arbitrary Docker output.
                HostImage.version(it.text)
            }
        }
    }
    fun ask(action: String) { confirmation = action; typed = ""; eraseBackup = false }
    fun manage() = task {
        val path = root ?: error("No release"); val action = confirmation ?: error("No action")
        check(action in setOf("start", "stop", "update", "backup", "restore", "delete"))
        if (action in setOf("delete", "restore")) check(typed == domain)
        confirmation = null; clearCodes()
        io { service.assertOwnership(path) }
        if (action == "restore") message = "sh.restoring"
        if (action == "backup" || action == "update" || action == "delete") execute(service.commands.script(path, "backup", timeout = 1800))
        when (action) {
            "start" -> execute(service.commands.compose(path, "up", "-d", "--wait", "--wait-timeout", "300"))
            "stop" -> execute(service.commands.compose(path, "stop"))
            "update" -> {
                val result = io { service.requireLocal(); service.run(service.commands.update(path)) }
                check(HostInstallResult.fromExit(result.exit).installed)
            }
            "restore" -> { val backup = restorePath ?: error("No backup"); execute(service.commands.script(path, "restore",
                if (windows) listOf("-BackupPath", backup.toString()) else listOf(backup.toString()), 1800)) }
            "delete" -> {
                execute(service.commands.compose(path, "--profile", "rooms", "down", "--volumes"))
                if (eraseBackup) execute(service.commands.script(path, "manage", if (windows) listOf("-Action", "erase-backups") else listOf("erase-backups"), 180))
                // Installer deletes only its credential/config files, retaining the reviewed release sources.
                execute(service.commands.script(path, "manage", if (windows) listOf("-Action", "retire") else listOf("retire")))
                io { record.forget() }; created = false; healthy = false; state = HostState()
            }
        }
        message = "sh.done"
    }
}

package org.unyri.admin.core

import org.junit.Test
import kotlin.test.*
import java.nio.file.Files
import java.nio.file.Path

class SelfHostTest {
    @Test fun missingDockerCannotAdvance() {
        assertEquals(HostStep.DOCKER, HostState().next().step)
        assertEquals(HostStep.NAME, HostState(docker = true).next().step)
    }
    @Test fun wanAndCarrierNatHints() {
        listOf("10.0.0.1", "192.168.1.1", "172.16.0.1", "172.31.1.1", "100.64.0.1", "100.127.255.254").forEach {
            assertTrue(HostValidation.cgnat(it, "8.8.8.8"))
        }
        assertFalse(HostValidation.cgnat("8.8.8.8", "8.8.8.8"))
        assertTrue(HostValidation.cgnat("8.8.8.8", "9.9.9.9"))
        assertFalse(HostValidation.privateWan("100.128.0.1"))
        assertFalse(HostValidation.privateWan("172.32.0.1"))
        assertFalse(HostValidation.publicIpv4("192.168.1.2"))
        assertFalse(HostValidation.ipv4("01.2.3.4"))
        assertFalse(HostValidation.ipv4("8.8.8.999"))
    }
    @Test fun firewallCanFailOrBeSkippedWithoutDeadEnd() {
        val pending = HostState(step = HostStep.PORTS, portsChecked = true)
        assertEquals(HostStep.PORTS, pending.next().step)
        for (status in listOf(FirewallState.SKIPPED, FirewallState.FAILED, FirewallState.APPLIED))
            assertEquals(HostStep.OPTIONS, pending.copy(firewall = status).next().step)
    }
    @Test fun installationSuccessFailureAndOwnerCodeVariants() {
        val running = HostState(step = HostStep.INSTALL).installing()
        assertEquals(running, running.next())
        val failed = running.failure()
        assertTrue(failed.failed); assertFalse(failed.installing); assertFalse(failed.installed)
        assertEquals(HostStep.INSTALL, failed.next().step)
        for (code in listOf(true, false)) {
            val done = failed.installing().completed(publicHealthy = true, code = code)
            assertEquals(HostStep.FINISH, done.step); assertTrue(done.installed)
            assertEquals(code, done.ownerCodePresent); assertFalse(done.failed)
        }
        val localOnly = running.completed(false, false)
        assertTrue(localOnly.installed); assertFalse(localOnly.publicHealthy)
    }
    @Test fun commandsKeepPathsAndInputInSeparateArguments() {
        val root = Path.of("release with spaces & dollar")
        for (windows in listOf(true, false)) {
            val commands = HostCommands(windows)
            val command = commands.install(HostInstall(root, "family.example.org", "8.8.8.8", true, true))
            assertTrue(command.arguments.contains("family.example.org"))
            assertTrue(command.arguments.contains("8.8.8.8"))
            assertFalse(command.arguments.contains("invite_only"))
            assertFalse(command.arguments.any { it.contains("desktop-install") })
            assertTrue(command.arguments.contains(if (windows) "-NonInteractive" else "--non-interactive"))
            assertFalse(command.arguments.contains(if (windows) "-FromSource" else "--from-source"))
            val source = commands.install(HostInstall(root, "family.example.org", "8.8.8.8", false, false, true, "Family & $ room"))
            assertTrue(source.arguments.contains(if (windows) "-FromSource" else "--from-source"))
            assertTrue(source.arguments.contains("Family & $ room"))
            assertTrue(source.arguments.contains(if (windows) "-SkipFirewall" else "--rooms"))
            assertFalse(command.arguments.contains("-Command"))
            assertFalse(command.arguments.contains("-c"))
            assertFailsWith<IllegalArgumentException> { commands.install(HostInstall(root, "x.org;whoami", "8.8.8.8", false, false)) }
            val restore = commands.script(root, "manage", listOf("restore", "path with spaces;literal"))
            assertEquals("path with spaces;literal", restore.arguments.last())
            val code = commands.ownerCode(root)
            assertEquals(listOf("cat", "/app/data/owner-setup-code"), code.arguments.takeLast(2))
            assertTrue(command.toString().contains("redacted"))
        }
    }
    @Test fun logsAcceptOnlyExactPhaseMarkers() {
        assertEquals("pull", HostPhases.parse("UNYRI_GUI:pull"))
        assertNull(HostPhases.parse("FIRST OWNER SETUP CODE: synthetic"))
        assertNull(HostPhases.parse("UNYRI_GUI:pull token=synthetic"))
        assertNull(HostPhases.parse("UNYRI_GUI:secret"))
    }
    @Test fun codeFilesAreStrictlyParsedAndAbsentIsSafe() {
        val valid = "ABCD-EFGH-JKLM-NPQR"
        val service = HostService(HostRunner { _, _ -> HostOutput(0, "$valid\n") }, HostCommands(false))
        assertEquals(valid, service.ownerCode(Path.of("unused")))
        val absent = HostService(HostRunner { _, _ -> HostOutput(1, "") })
        assertNull(absent.ownerCode(Path.of("unused")))
        val untrusted = HostService(HostRunner { _, _ -> HostOutput(0, "token=synthetic") })
        assertNull(untrusted.ownerCode(Path.of("unused")))
    }
    @Test fun remoteDockerContextIsRejectedBeforeDaemonAccess() {
        val called = mutableListOf<List<String>>()
        val service = HostService(HostRunner { cmd, _ -> called += cmd.arguments; HostOutput(0, "ssh://remote.invalid") }, HostCommands(false))
        assertFalse(service.docker())
        assertFalse(called.any { "info" in it })
    }
    @Test fun recordContainsOnlyLocationAndName() {
        val folder = Files.createTempDirectory("host-record")
        try {
            val file = folder.resolve("server.properties"); val record = HostRecord(file)
            record.save(folder, "family.example.org")
            assertEquals(folder, record.load()!!.first)
            assertEquals("family.example.org", record.load()!!.second)
            assertFalse(Files.readString(file).contains("token"))
            record.forget(); assertNull(record.load())
        } finally { Files.deleteIfExists(folder) }
    }
    @Test fun consentIsRequiredBeforePublicIpOrDdnsRequest() {
        assertFailsWith<IllegalArgumentException> { HostService().publicIp(false) }
        assertFailsWith<IllegalArgumentException> { DuckDns(Path.of("unused")).update("x.duckdns.org", "8.8.8.8", false) }
    }
    @Test fun versionUsesOnlyTheImageFields() {
        assertEquals("unyri-server:1.2.3", HostImage.version("""[{"Repository":"unyri-server","Tag":"1.2.3","Other":"synthetic"}]"""))
        assertEquals("", HostImage.version("""{"Repository":"token=synthetic","Tag":"unsafe value"}"""))
        assertEquals("", HostImage.version("not JSON"))
    }
    @Test fun lifecycleCommandsKeepCanonicalSourceOverlay() {
        val folder = Files.createTempDirectory("host-overlay")
        try {
            Files.writeString(folder.resolve(".selfhost-from-source"), "synthetic overlay")
            val command = HostCommands(false).compose(folder, "up", "-d")
            assertTrue(command.arguments.contains(folder.resolve("scripts/selfhost/compose.source.yml").toString()))
        } finally { Files.deleteIfExists(folder.resolve(".selfhost-from-source")); Files.deleteIfExists(folder) }
    }
    @Test fun installerExitCodesDistinguishPublicVerificationFromInstallation() {
        assertEquals(HostInstallResult.VERIFIED, HostInstallResult.fromExit(0))
        assertEquals(HostInstallResult.FAILED, HostInstallResult.fromExit(1))
        assertEquals(HostInstallResult.LOCAL_ONLY, HostInstallResult.fromExit(3))
        for (exit in listOf(-2, -1, 2, 4, 255)) assertFalse(HostInstallResult.fromExit(exit).installed)
        assertTrue(HostInstallResult.fromExit(3).installed)
        assertFalse(HostInstallResult.fromExit(3).publicVerified)
    }
    @Test fun ownerCodeUsesBackendVolumeRedisplayAndNeverLogsOrBootstraps() {
        val calls = mutableListOf<HostCommand>()
        val code = "ABCD-EFGH-JKLM-NPQR"
        val service = HostService(HostRunner { cmd, _ -> calls += cmd; HostOutput(0, code) }, HostCommands(false))
        assertEquals(code, service.ownerCode(Path.of("release with spaces")))
        assertEquals(listOf("exec", "-T", "backend", "cat", "/app/data/owner-setup-code"), calls.single().arguments.takeLast(5))
        assertFalse(calls.single().toString().contains(code))
        assertNull(HostService(HostRunner { _, _ -> HostOutput(1, code) }).ownerCode(Path.of("release")))
        assertNull(HostService(HostRunner { _, _ -> HostOutput(0, code + "\nextra") }).ownerCode(Path.of("release")))
    }
    @Test fun portsMustBeCheckedAndTrialUsesExplicitLoopbackInputs() {
        assertEquals(HostStep.PORTS, HostState(step = HostStep.PORTS, firewall = FirewallState.SKIPPED).next().step)
        val root = Path.of("trial")
        for (windows in listOf(true, false)) {
            val commands = HostCommands(windows)
            assertTrue(commands.preflight(root, true).arguments.any { it.endsWith(if (windows) "preflight-ports.ps1" else "preflight-ports.sh") })
            val trial = commands.install(HostInstall(root, "localhost", "127.0.0.1", false, false, localTrial = true))
            assertTrue(trial.arguments.contains("127.0.0.1"))
            assertFailsWith<IllegalArgumentException> { commands.install(HostInstall(root, "localhost", "127.0.0.1", true, false, localTrial = true)) }
        }
    }

}

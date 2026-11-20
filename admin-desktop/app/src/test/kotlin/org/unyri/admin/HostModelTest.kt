@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.unyri.admin

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.unyri.admin.core.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class HostModelTest {
    private fun release(): Path = Files.createTempDirectory(Path.of("build"), "host-model-").also {
        Files.createDirectories(it.resolve("scripts/selfhost"))
        Files.writeString(it.resolve("scripts/selfhost/install.sh"), "synthetic installer; never executed")
        Files.writeString(it.resolve("docker-compose.selfhost.yml"), "synthetic compose; never executed")
    }.toAbsolutePath()
    private fun remove(dir: Path) {
        check(dir.startsWith(Path.of("build").toAbsolutePath()))
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
    private fun fake(commands: MutableList<HostCommand>, installExit: Int = 0, publicReady: Boolean = true, code: Boolean = true) = HostService(
        HostRunner { cmd, phase ->
            commands += cmd
            when {
                "context" in cmd.arguments -> HostOutput(0, "unix:///var/run/docker.sock")
                "info" in cmd.arguments -> HostOutput(0, "linux|Synthetic engine")
                "version" in cmd.arguments -> HostOutput(0, "2.24.0")
                "cat" in cmd.arguments -> if (code && cmd.arguments.last().endsWith("owner-setup-code")) HostOutput(0, "ABCD-EFGH-JKLM-NPQR") else HostOutput(1, "")
                "--non-interactive" in cmd.arguments -> { phase("prepare"); HostOutput(installExit, "synthetic raw output is never shown") }
                else -> HostOutput(0, "")
            }
        }, HostCommands(false), { publicReady })
    @Test fun installSuccessUsesLocalHealthThenExplicitCodeRevealAndCanResume() = runTest {
        val dir = release(); val calls = mutableListOf<HostCommand>(); val dispatcher = StandardTestDispatcher(testScheduler)
        val record = HostRecord(dir.resolve("location.properties")); val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val model = HostModel(scope, fake(calls), record, dispatcher)
            model.selectRoot(dir); model.domain = "family.example.org"; model.publicIp = "8.8.8.8"; model.sourceConsent = true
            model.install(); runCurrent()
            assertTrue(model.created); assertFalse(model.busy); assertEquals(HostStep.FINISH, model.state.step)
            assertTrue(model.state.publicHealthy); assertNull(model.ownerCode)
            assertFalse(calls.any { "cat" in it.arguments })
            model.revealCodes(); runCurrent(); assertTrue(model.state.ownerCodePresent)
            model.clearCodes(); assertNull(model.ownerCode)
            assertTrue(HostModel(scope, fake(calls), record, dispatcher).created)
        } finally { scope.cancel(); remove(dir) }
    }
    @Test fun installFailureKeepsLocationForRetryWithoutClaimingReadiness() = runTest {
        val dir = release(); val calls = mutableListOf<HostCommand>(); val dispatcher = StandardTestDispatcher(testScheduler)
        val record = HostRecord(dir.resolve("location.properties")); val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val model = HostModel(scope, fake(calls, installExit = 1), record, dispatcher)
            model.selectRoot(dir); model.domain = "family.example.org"; model.publicIp = "8.8.8.8"; model.sourceConsent = true
            model.install(); runCurrent()
            assertTrue(model.state.failed); assertFalse(model.created); assertFalse(model.busy)
            assertEquals("sh.failed", model.error)
            val restarted = HostModel(scope, fake(calls), record, dispatcher)
            assertFalse(restarted.created); assertEquals(dir, restarted.root)
            assertFalse(Files.exists(dir.resolve("desktop-local-ready")))
        } finally { scope.cancel(); remove(dir) }
    }
    @Test fun publicFailureAndAbsentCodeKeepTheLocalServerReady() = runTest {
        val dir = release(); val calls = mutableListOf<HostCommand>(); val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val model = HostModel(scope, fake(calls, installExit = 3, publicReady = false, code = false), HostRecord(dir.resolve("location.properties")), dispatcher)
            model.selectRoot(dir); model.domain = "family.example.org"; model.publicIp = "8.8.8.8"; model.sourceConsent = true
            model.install(); runCurrent()
            assertTrue(model.created); assertEquals(true, model.healthy); assertFalse(model.state.publicHealthy); assertEquals("sh.publicFailed", model.message)
            model.revealCodes(); runCurrent(); assertNull(model.ownerCode); assertEquals("sh.noCode", model.message)
        } finally { scope.cancel(); remove(dir) }
    }
    @Test fun destructiveCommandsRequireTheTypedName() = runTest {
        val dir = release(); val calls = mutableListOf<HostCommand>(); val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val model = HostModel(scope, fake(calls), HostRecord(dir.resolve("location.properties")), dispatcher)
            model.root = dir; model.domain = "family.example.org"
            for (action in listOf("delete", "restore")) {
                model.ask(action); model.typed = "wrong.example.org"; model.manage(); runCurrent()
                assertTrue(calls.isEmpty()); assertEquals("sh.failed", model.error)
            }
        } finally { scope.cancel(); remove(dir) }
    }
}

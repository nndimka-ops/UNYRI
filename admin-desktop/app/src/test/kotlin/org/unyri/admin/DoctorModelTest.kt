@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.unyri.admin

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.unyri.admin.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class DoctorModelTest {
    private val id = "00000000-0000-0000-0000-000000000000"
    private class MemoryStore : SessionStore {
        override fun save(session: Session) = Unit
        override fun load(origin: String): Session? = null
        override fun delete(origin: String) = Unit
    }
    @Test fun onlyOwnersFetchAddressesAndOldServerDoesNotBreakOverview() = runTest {
        for (role in listOf("owner", "moderator")) {
            val dir = Files.createTempDirectory(Path.of("build"), "doctor-model-")
            val requests = mutableListOf<String>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            try {
                val m = AdminModel(scope, false, Preferences(dir.resolve("prefs")), MemoryStore(), { origin ->
                    AdminApi(origin, Transport { r ->
                        requests += r.path
                        when (r.path) {
                            "/auth/login" -> """{"access_token":"synthetic","access_token_expires_at":"${Instant.now().plusSeconds(3600)}","device_id":"$id","user":{"id":"$id","username":"fixture"}}"""
                            "/users/me" -> """{"id":"$id","admin_role":"$role"}"""
                            "/admin/status" -> """{"configured":true,"key_protocol":1,"auth_protocol":2,"auth_policy":"authenticator","server_id":"$id"}"""
                            "/admin/unlock" -> """{"token":"${PaperCrypto.b64(ByteArray(32))}","auth_policy":"authenticator"}"""
                            "/server-info" -> """{"name":"Fixture"}"""
                            "/health" -> """{"status":"ok","database":true,"redis":true}"""
                            "/admin/summary" -> """{"open_reports":0,"in_progress_reports":0,"unresolved_feedback":0,"active_account_suspensions":0,"active_installation_suspensions":0,"reports_last_24h":0}"""
                            else -> "{}"
                        }
                    })
                }, dispatcher)
                m.origin = "https://fixture.example.test"; m.login(); runCurrent()
                m.code = "123456"; m.unlock(); runCurrent()
                assertNotNull(m.dashboard)
                assertEquals(if (role == "owner") 1 else 0, requests.count { it == "/admin/self-check" })
                if (role == "owner") {
                    assertNotNull(m.dashboard!!.doctor); assertNull(m.dashboard!!.doctor!!.addresses)
                    m.origin = "https://unrelated-login.example.test"
                    assertEquals("https://fixture.example.test", m.dashboard!!.doctor!!.activeOrigin)
                    m.leaveOverview(); assertNull(m.dashboard!!.doctor)
                    m.refresh(); runCurrent(); assertEquals(2, requests.count { it == "/admin/self-check" })
                    m.signOut(); runCurrent(); assertNull(m.dashboard)
                } else assertNull(m.dashboard!!.doctor)
            } finally {
                scope.cancel()
                check(dir.toAbsolutePath().startsWith(Path.of("build").toAbsolutePath()))
                Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }
    @Test fun demoHostRefreshReadsNoInstallationOrCommandsAndRetiresSnapshot() = runTest {
        val dir = Files.createTempDirectory(Path.of("build"), "doctor-demo-")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val h = HostModel(scope, HostService(HostRunner { _, _ -> error("Demo must not run local tools") }),
                HostRecord(dir.resolve("absent-record")), dispatcher, demo = true)
            assertTrue(h.created); assertNull(h.root)
            h.openAddresses(null); runCurrent()
            assertEquals("home.example.test", h.doctor!!.addresses!!.publicAddress!!.host)
            assertNull(h.doctor!!.activeOrigin)
            assertEquals(180, h.doctor!!.capturedAt.epochSecond - h.doctor!!.addresses!!.lan.observedAt!!.epochSecond)
            h.refresh(); runCurrent(); assertFalse(h.doctorLoading)
            h.closeAddresses(); assertNull(h.doctor)
            h.openAddresses(null); h.closeAddresses(); runCurrent(); assertNull(h.doctor)
        } finally {
            scope.cancel()
            check(dir.toAbsolutePath().startsWith(Path.of("build").toAbsolutePath()))
            Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.unyri.admin

import kotlinx.coroutines.test.*
import kotlinx.coroutines.*
import org.junit.Test
import org.unyri.admin.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class ModelTest {
    private val id="00000000-0000-0000-0000-000000000000"
    private class MemoryStore : SessionStore {
        var saved: Session? = null; var deleted=false
        override fun save(session: Session) { saved=session }
        override fun load(origin: String) = saved
        override fun delete(origin: String) { saved=null; deleted=true }
    }
    @Test fun loginUnlockLockClearsPrivilegedStateAndRevokes() = runTest {
        val directory=Files.createTempDirectory(Path.of("build"),"model-")
        val memory=MemoryStore(); val requests=mutableListOf<String>()
        val scope=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        try {
            val model=AdminModel(scope,false,Preferences(directory.resolve("preferences.properties")),memory,{ origin ->
                AdminApi(origin,Transport { r -> requests+=r.path; when(r.path) {
                    "/auth/login" -> """{"access_token":"synthetic-access","access_token_expires_at":"${Instant.now().plusSeconds(3600)}","device_id":"$id","user":{"id":"$id","username":"synthetic"}}"""
                    "/users/me" -> """{"id":"$id","admin_role":"owner"}"""
                    "/admin/status" -> """{"configured":true,"key_protocol":1,"auth_protocol":2,"auth_policy":"authenticator","server_id":"$id"}"""
                    "/admin/unlock" -> """{"token":"${PaperCrypto.b64(ByteArray(32))}","auth_policy":"authenticator"}"""
                    "/health" -> """{"status":"ok","database":true,"redis":true}"""
                    "/server-info" -> """{"name":"Synthetic server"}"""
                    "/admin/summary" -> """{"open_reports":1,"in_progress_reports":0,"unresolved_feedback":0,"active_account_suspensions":0,"active_installation_suspensions":0,"reports_last_24h":1}"""
                    else -> "{}"
                } })
            },StandardTestDispatcher(testScheduler))
            model.origin="https://test.invalid"; model.username="synthetic"; model.password="synthetic-password"
            model.login(); runCurrent(); assertEquals(2,model.stage)
            model.code="123456"; model.rememberSession=true; model.unlock(); runCurrent()
            assertTrue(model.signedIn); assertNotNull(model.dashboard); assertNotNull(memory.saved)
            assertEquals("",model.password); assertEquals("",model.code)
            advanceTimeBy(15L*60*1000); runCurrent()
            assertFalse(model.signedIn); assertNull(model.dashboard); assertTrue(memory.deleted)
            assertEquals(listOf("/admin/lock","/auth/logout"),requests.takeLast(2))
            assertEquals("locked",model.toast)
        } finally { scope.cancel(); Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
    @Test fun demoPagingOnlyCommitsOnSuccessAndSearchResetsHistory() = runTest {
        val directory=Files.createTempDirectory(Path.of("build"),"model-demo-")
        val scope=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        try {
            val model=AdminModel(scope,true,Preferences(directory.resolve("preferences.properties")),MemoryStore(),
                { error("Offline demo must not create an API client") },StandardTestDispatcher(testScheduler))
            runCurrent(); model.navigate(Section.USERS); runCurrent(); assertEquals(25,model.userPage.items.size)
            model.next(); runCurrent(); assertEquals(1,model.history.size)
            model.query="demo009"; model.filter="suspended"; model.search(); runCurrent()
            assertTrue(model.history.isEmpty()); assertEquals(1,model.userPage.items.size)
            model.signOut(); runCurrent(); assertTrue(model.userPage.items.isEmpty())
        } finally { scope.cancel(); Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

package com.messenger.app.data.identity

import com.messenger.app.data.api.*
import com.messenger.app.domain.model.*
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import kotlin.test.*

class IdentityContractsTest {
    @Test fun signInSignUpAndNameAreExplicitSteps() {
        val signIn = AuthForm()
        assertEquals(AuthStep.SignIn, signIn.step)
        val signUp = signIn.toggle()
        assertEquals(AuthStep.SignUp, signUp.step)
        // "password123" advanced to the name step before B-048 and the server then refused it there.
        val good = "river-lamp-42"
        assertEquals(signUp, signUp.next("secret_login", good, "different", true))
        assertEquals(signUp, signUp.next("secret_login", good, good, false))
        assertEquals(signUp, signUp.next("@public", good, good, true))
        assertEquals(AuthStep.DisplayName, signUp.next("secret_login", good, good, true).step)
        assertEquals(signIn, signUp.toggle())
    }

    @Test fun commonPasswordsStopAtThePasswordStepAndRefusalsReturnThere() {
        val signUp = AuthForm(AuthStep.SignUp)
        for (common in listOf("password123", "12345678", "Password1", "  QWERTY123 ", "пароль123")) {
            assertTrue(IdentityPolicy.isCommonPassword(common), common)
            assertEquals(signUp, signUp.next("secret_login", common, common, true), common)
        }
        assertFalse(IdentityPolicy.isCommonPassword("river-lamp-42"))
        val name = AuthForm(AuthStep.DisplayName)
        assertEquals(signUp, name.back(), "back from the name step returns to the password step")
        assertEquals(AuthForm(AuthStep.SignIn), signUp.back())
        assertEquals(signUp, name.afterRefusal("common_password"))
        assertEquals(signUp, name.afterRefusal("username_taken"))
        assertEquals(name, name.afterRefusal("display_name_reserved"), "name refusals stay on the name step")
        assertEquals(name, name.afterRefusal(null))
        assertEquals(signUp, signUp.afterRefusal("common_password"))
    }

    @Test fun commonPasswordListEqualsTheServerDenylist() {
        val server = java.io.File("../../server/src/auth/common_passwords.txt").readLines(Charsets.UTF_8)
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        assertTrue(server.size >= 30, "server list read")
        assertEquals(server, IdentityPolicy.commonPasswords)
    }
    @Test fun passwordStrengthIsGuidanceAndRespectsServerBounds() {
        assertFalse(IdentityPolicy.passwordAllowed("a".repeat(7)))
        assertTrue(IdentityPolicy.passwordAllowed("a".repeat(8)))
        assertTrue(IdentityPolicy.passwordAllowed("a".repeat(256)))
        assertFalse(IdentityPolicy.passwordAllowed("a".repeat(257)))
        assertEquals(1, IdentityPolicy.strength("password"))
        assertEquals(2, IdentityPolicy.strength("longpassword"))
        assertEquals(3, IdentityPolicy.strength("Longpassword1"))
        assertEquals(3, IdentityPolicy.strength("a long safe passphrase"))
        assertEquals(0, IdentityPolicy.strength("a".repeat(257)))
    }
    @Test fun recoveryRequiresAllThreeGroupsOfActualServerKey() {
        val key = "R1-AAAAA-BBBBB-CCCCC-DDDDD-EEEEE-FFFFF-12345-67890"
        assertTrue(IdentityPolicy.confirms(key, listOf("bbbbb", "DDDDD", "12345")))
        assertFalse(IdentityPolicy.confirms(key, listOf("AAAAA", "DDDDD", "12345")))
        assertFalse(IdentityPolicy.confirms(key, listOf("BBBBB", "DDDDD")))
        assertNull(IdentityPolicy.recoveryParts("R1-AAAAA"))
        assertNull(IdentityPolicy.recoveryParts(key.replace("AAAAA", "ZZZZZ")))
    }
    @Test fun usernameGrammarMatchesRustAndNeverLocallyGuessesReservations() {
        for (raw in listOf("Alice_1", "abcde", "a".repeat(32), "support")) assertEquals(raw.lowercase(), IdentityPolicy.username(raw))
        for (raw in listOf("", "abcd", "1abcd", "_abcd", "ab__cd", "a.bcd", "ab-cd", " abcd", "abcd ",
            "@alice", "аlice", "aliсe", "Ａlice", "alicé", "alice\u200d", "a".repeat(33))) assertNull(IdentityPolicy.username(raw), raw)
    }
    @Test fun lookupAllowsOnlyExactPublicIdentifiersAndOriginBoundLinks() {
        assertEquals(IdentityPolicy.Target("number", "123456789"), IdentityPolicy.input("123 456 789"))
        assertEquals(IdentityPolicy.Target("username", "alice_1"), IdentityPolicy.input("@Alice_1"))
        assertNull(IdentityPolicy.input("private_login"))
        assertNull(IdentityPolicy.input("012345678"))
        assertNotNull(IdentityPolicy.input("1")) // Permanent short alias.
        val link = IdentityPolicy.link("123456789", "https://self.example:8443")!!
        assertEquals("https://self.example:8443", IdentityPolicy.parse(link)?.origin)
        assertNotNull(IdentityPolicy.lookup(link, "https://self.example:8443"))
        assertNull(IdentityPolicy.lookup(link, "https://other.example"))
        for (raw in listOf(link + "&extra=1", link + "#x", link.replace("/n/", "/n/%"),
            "unyri://u/n/123456789?server=https%3A%2F%2Fuser%40evil.example", "unyri://u/n/123456789")) assertNull(IdentityPolicy.parse(raw))
        assertNull(IdentityPolicy.origin("https://example.com/path"))
        assertNull(IdentityPolicy.origin("https://example.com:"))
        assertNull(IdentityPolicy.origin("http://127.1"))
        assertEquals("http://server01:8080", IdentityPolicy.origin("http://server01:8080"))
    }
    @Test fun conflictMappingUsesBothStatusAndCode() {
        assertTrue(IdentityPolicy.unavailable(409, "unavailable"))
        assertFalse(IdentityPolicy.unavailable(503, "unavailable"))
        assertFalse(IdentityPolicy.unavailable(409, "conflict"))
        assertFalse(IdentityPolicy.legacy(500))
        assertFalse(IdentityPolicy.legacy(401))
    }
    @Test fun preAuthHintNeverSendsCredentialsAndDoesNotTurnOutagesIntoLegacy() = runTest {
        MockWebServer().use { server ->
            val api = IdentityApi(OkHttpClient())
            val origin = server.url("/").toString().trimEnd('/')
            server.enqueue(error(401, "unauthorized"))
            assertEquals(true, api.supports(origin))
            assertNull(server.takeRequest().getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(404))
            assertEquals(false, api.supports(origin))
            server.enqueue(error(500, "internal_error"))
            assertFails { api.supports(origin) }
        }
    }
    private fun identity(origin: String, policy: String = "everyone", revision: Int = 0, username: String = "null") =
        """{"user_id":"owner","unyri_number":"123456789","public_username":$username,"discoverability":"$policy","revision":$revision,"public_origin":"$origin"}"""
    private fun error(status: Int, code: String) = MockResponse().setResponseCode(status)
        .setBody("""{"error":{"code":"$code","message":"opaque"}}""")
    @Test fun identityRoundTripPolicyNullClearAndRevision() = runTest {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val api = IdentityApi(OkHttpClient())
            server.enqueue(MockResponse().setBody(identity(origin, username = "\"alice\"")))
            val old = api.get(origin, "token")!!
            server.takeRequest()
            for ((index, policy) in Discoverability.entries.withIndex()) {
                val wire = listOf("everyone", "contacts", "nobody")[index]
                server.enqueue(MockResponse().setBody(identity(origin, wire, index + 1)))
                val result = api.update(origin, "token", old, null, policy, true)
                assertEquals(policy, result.discoverability)
                assertEquals(index + 1L, result.revision)
                val request = server.takeRequest()
                assertEquals("PUT", request.method)
                val body = ApiJson.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals("0", body["expected_revision"]!!.jsonPrimitive.content)
                assertEquals(wire, body["discoverability"]!!.jsonPrimitive.content)
                assertEquals(JsonNull, body["public_username"])
                assertNull(body["username"])
            }
        }
    }
    @Test fun takenFrozenReservedAllHaveIdentical409MappingAnd503IsFailure() = runTest {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val api = IdentityApi(OkHttpClient())
            val current = ApiJson.decodeFromString<OwnerIdentity>(identity(origin))
            for (name in listOf("taken", "frozen", "support")) {
                server.enqueue(error(409, "unavailable"))
                val thrown = assertFailsWith<AuthApiException.Api> { api.update(origin, "token", current, name, Discoverability.Everyone, true) }
                assertTrue(IdentityPolicy.unavailable(thrown.httpStatus, thrown.code))
            }
            server.enqueue(error(503, "unavailable"))
            assertFailsWith<AuthApiException.Api> { api.get(origin, "token") }
        }
    }
    @Test fun oldServerFallbackRequiresExplicitUnsupportedAndIsSessionScoped() = runTest {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val auth = FakeAuth()
            val repository = IdentityRepository(IdentityApi(OkHttpClient()), auth) { origin }
            server.enqueue(error(500, "internal_error"))
            assertFails { repository.search("private_login", 0) }
            assertFalse(repository.legacy.value)
            server.enqueue(error(404, "not_found"))
            assertNull(repository.search("private_login", 0))
            assertTrue(repository.legacy.value)
            auth.epoch++
            server.enqueue(MockResponse().setBody(identity(origin)))
            assertEquals(emptyList(), repository.search("private_login", 0)!!.items)
            assertFalse(repository.legacy.value)
            assertEquals(3, server.requestCount) // No directory/search or lookup for a login.
        }
    }
    @Test fun exactLookup404NeverFallsBackAndForeignQrNeverSendsCredentials() = runTest {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val repository = IdentityRepository(IdentityApi(OkHttpClient()), FakeAuth()) { origin }
            server.enqueue(MockResponse().setBody(identity(origin)))
            assertEquals(emptyList(), repository.search(IdentityPolicy.link("123456789", "https://foreign.example")!!, 0)!!.items)
            server.enqueue(error(404, "not_found"))
            assertEquals(emptyList(), repository.search("@alice", 0)!!.items)
            assertFalse(repository.legacy.value)
            server.takeRequest()
            assertEquals("/users/lookup?username=alice", server.takeRequest().path)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun legacyDetectionReallyUsesExistingSearchAndDirectoryRoutes() = runTest {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val auth = FakeAuth()
            val identity = IdentityRepository(IdentityApi(OkHttpClient()), auth) { origin }
            val repository = com.messenger.app.data.profile.ProfileRepositoryImpl(
                UsersApi(OkHttpClient(), ApiJson, origin), auth, serverOrigin = { origin }, identity = identity)
            server.enqueue(MockResponse().setResponseCode(404)) // Old routers may have no JSON body.
            server.enqueue(MockResponse().setBody("""{"items":[{"id":"peer","username":"old_login","display_name":"Peer"}]}"""))
            val legacyPeer = repository.searchUsers("@old_login").getOrThrow().items.single()
            assertEquals("", legacyPeer.username)
            assertEquals("Peer", legacyPeer.visibleName)
            assertEquals("/users/me/identity", server.takeRequest().path)
            val oldPath = server.takeRequest().path!!
            assertTrue(oldPath.startsWith("/users/search?"))
            assertTrue("q=old_login" in oldPath)
            server.enqueue(MockResponse().setBody("""{"items":[]}"""))
            assertTrue(repository.directory("").isSuccess)
            assertTrue(server.takeRequest().path!!.startsWith("/users/directory?"))
        }
    }
    @Test fun privacyOnlyMutationOmitsUsernameAndRedirectsNeverCarryBearer() = runTest {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val api = IdentityApi(OkHttpClient())
            val current = ApiJson.decodeFromString<OwnerIdentity>(identity(origin))
            server.enqueue(MockResponse().setBody(identity(origin, "contacts", 1)))
            api.update(origin, "token", current, null, Discoverability.Contacts, false)
            assertFalse(ApiJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.containsKey("public_username"))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", origin + "/unexpected"))
            assertFails { api.get(origin, "token") }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun oldIdentityIsSynchronouslyHiddenWhenAccountChanges() = runTest {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val auth = FakeAuth()
            val repository = IdentityRepository(IdentityApi(OkHttpClient()), auth) { origin }
            server.enqueue(MockResponse().setBody(identity(origin)))
            repository.refresh()
            assertNotNull(repository.identity.value)
            auth.epoch++
            assertNull(repository.identity.value)
            assertFalse(repository.legacy.value)
        }
    }
    @Test fun exactResponsesMustMatchWhileShortAliasesReturnCanonicalNumber() = runTest {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val repository = IdentityRepository(IdentityApi(OkHttpClient()), FakeAuth()) { origin }
            server.enqueue(MockResponse().setBody(identity(origin)))
            val profile = """{"id":"peer","unyri_number":"987654321","public_username":"alice","display_name":"Alice"}"""
            server.enqueue(MockResponse().setBody(profile))
            assertFails { repository.search("123456789", 0) }
            server.enqueue(MockResponse().setBody(profile))
            assertEquals("987654321", repository.search("1", 0)!!.items.single().publicNumber)
            server.enqueue(MockResponse().setBody(profile))
            assertFails { repository.search("@bobby", 0) }
            assertFalse(repository.legacy.value)
        }
    }
    @Test fun missingIdentityRoutesHideIdentityAndKeepLegacyDirectoryWithoutErrors() = runTest {
        for (code in listOf("route_not_found", "http_error")) MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val identity = IdentityRepository(IdentityApi(OkHttpClient()), FakeAuth()) { origin }
            server.enqueue(if (code == "http_error") MockResponse().setResponseCode(404) else error(404, code))
            assertNull(identity.refresh())
            assertNull(identity.identity.value)
            assertTrue(identity.legacy.value)
            assertNull(identity.search("old_login", 0))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun missingLookupRouteClearsIdentityAndFallsBackButMissingPersonNeverDoes() = runTest {
        for (missing in listOf(error(404, "route_not_found"), MockResponse().setResponseCode(404), error(405, "method_not_allowed"))) {
            MockWebServer().use { server ->
                val origin = server.url("/").toString().trimEnd('/')
                val auth = FakeAuth()
                val identity = IdentityRepository(IdentityApi(OkHttpClient()), auth) { origin }
                val repository = com.messenger.app.data.profile.ProfileRepositoryImpl(
                    UsersApi(OkHttpClient(), ApiJson, origin), auth, serverOrigin = { origin }, identity = identity)
                server.enqueue(MockResponse().setBody(identity(origin)))
                assertNotNull(identity.refresh())
                server.enqueue(error(404, "not_found"))
                assertTrue(repository.searchUsers("@alice").getOrThrow().items.isEmpty())
                assertNotNull(identity.identity.value)
                assertFalse(identity.legacy.value)
                server.enqueue(missing)
                server.enqueue(MockResponse().setBody("""{"items":[]}"""))
                assertTrue(repository.searchUsers("@alice").isSuccess)
                assertNull(identity.identity.value)
                assertTrue(identity.legacy.value)
                server.enqueue(MockResponse().setBody("""{"items":[]}"""))
                assertTrue(repository.directory("").isSuccess)
                assertEquals(listOf("/users/me/identity", "/users/lookup", "/users/lookup", "/users/search", "/users/directory"),
                    List(5) { server.takeRequest().requestUrl!!.encodedPath })
                assertTrue(repository.searchUsers(IdentityPolicy.link("123456789", origin)!!).getOrThrow().items.isEmpty())
                assertEquals(5, server.requestCount)
            }
        }
    }

    @Test fun lookupOutagesAndAuthenticationFailuresKeepIdentityMode() = runTest {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val repository = IdentityRepository(IdentityApi(OkHttpClient()), FakeAuth()) { origin }
            server.enqueue(MockResponse().setBody(identity(origin)))
            repository.refresh()
            for (status in listOf(401, 403, 429, 500, 503)) {
                server.enqueue(error(status, "untrusted_message"))
                assertFails { repository.search("@alice", 0) }
                assertNotNull(repository.identity.value)
                assertFalse(repository.legacy.value)
            }
        }
    }

    private class FakeAuth : AuthRepository {
        var epoch = 1
        override val authState = MutableStateFlow<AuthSessionState>(AuthSessionState.LoggedIn(AuthUser("owner", "private_login", "Owner")))
        override suspend fun register(username: String, password: String, displayName: String) = Result.success(Unit)
        override suspend fun login(username: String, password: String) = Result.success(Unit)
        override suspend fun logout() { epoch++; authState.value = AuthSessionState.LoggedOut }
        override suspend fun currentAccessToken() = "token"
        override fun currentDeviceId() = "device"
        override fun currentSessionEpoch() = epoch
        override suspend fun <T> withSessionWrite(epochAtStart: Int, block: suspend () -> T): T? = if (epochAtStart == epoch) block() else null
        override suspend fun clearRoom(block: suspend () -> Unit) = block()
    }
}

package com.messenger.app.data.config

import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.ClientConfigApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class ClientConfigApiTest {
    @Test fun configIsPublicUsesAndroidAndDoesNotFollowRedirects() = runTest {
        MockWebServer().use { server ->
            val api = ClientConfigApi(OkHttpClient())
            server.enqueue(MockResponse().setBody("""{"schema_version":1,"platforms":{"ios":{"minimum_version":"99"},"android":{"minimum_version":"2.1","recommended_version":"2.2","update_url":"https://play.google.com/store/apps/details?id=com.messenger.app"}}}"""))
            val origin = server.url("/").toString()
            val policy = api.fetch(origin)
            assertEquals("2.1", policy?.minimum)
            assertEquals("2.2", policy?.recommended)
            val request = server.takeRequest()
            assertEquals("/client/config", request.path)
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/redirected")))
            assertFailsWith<AuthApiException> { api.fetch(origin) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun onlyOldEndpointErrorsClearTheGate() = runTest {
        MockWebServer().use { server ->
            val api = ClientConfigApi(OkHttpClient())
            val origin = server.url("/").toString()
            for (status in listOf(404, 405)) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("<html>old proxy</html>"))
                assertNull(api.fetch(origin))
            }
            for (code in listOf("not_found", "route_not_found")) {
                server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"$code","message":"old"}}"""))
                assertNull(api.fetch(origin))
            }
            server.enqueue(MockResponse().setResponseCode(503))
            assertFailsWith<AuthApiException> { api.fetch(origin) }
            server.enqueue(MockResponse().setBody("{malformed"))
            assertFails { api.fetch(origin) }
        }
    }
}

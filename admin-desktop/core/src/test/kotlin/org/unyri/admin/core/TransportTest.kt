package org.unyri.admin.core

import com.sun.net.httpserver.HttpServer
import org.junit.Test
import java.net.InetSocketAddress
import java.time.Instant
import kotlinx.serialization.json.*
import kotlin.test.*

class TransportTest {
    private val id="00000000-0000-0000-0000-000000000000"
    @Test fun loopbackHeadersRedirectAndPayloadBounds() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        var redirected=false
        server.createContext("/ok") { e ->
            assertEquals("Bearer synthetic",e.requestHeaders.getFirst("Authorization"))
            assertEquals("synthetic-admin",e.requestHeaders.getFirst("x-coco-admin"))
            val bytes="{}".toByteArray(); e.responseHeaders.add("Content-Type","application/json"); e.sendResponseHeaders(200,bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.createContext("/redirect") { e -> e.responseHeaders.add("Location","/target"); e.sendResponseHeaders(302,-1); e.close() }
        server.createContext("/target") { e -> redirected=true; e.sendResponseHeaders(204,-1); e.close() }
        server.createContext("/large") { e ->
            e.responseHeaders.add("Content-Type","application/json"); e.sendResponseHeaders(200,0)
            runCatching { e.responseBody.use { it.write(ByteArray(2*1024*1024+1)) } }; e.close()
        }
        server.start()
        try {
            val transport=JvmTransport(Origin("http://127.0.0.1:${server.address.port}"))
            assertEquals("{}",transport.send(WireRequest("GET","/ok",null,"synthetic","synthetic-admin")))
            assertFailsWith<AdminFailure> { transport.send(WireRequest("GET","/redirect",null,null,null)) }
            assertFalse(redirected)
            assertFailsWith<AdminFailure> { transport.send(WireRequest("GET","/large",null,null,null)) }
        } finally { server.stop(0) }
    }
    @Test fun allFactorPoliciesUseExactBodiesAndDoNotTransmitPaper() {
        for (policy in Policy.entries) {
            val paper=PaperCrypto.complete("0123456789ABCDEFGHJKMNPQRSTVWXYZ".take(30))
            val public=PaperCrypto.publicKey(paper)
            val requests=mutableListOf<WireRequest>()
            val transport=Transport { r ->
                requests+=r
                when (r.path) {
                    "/auth/login" -> """{"access_token":"synthetic-access","access_token_expires_at":"${Instant.now().plusSeconds(3600)}","device_id":"$id","user":{"id":"$id","username":"synthetic"}}"""
                    "/users/me" -> """{"id":"$id","admin_role":"owner"}"""
                    "/admin/status" -> """{"configured":true,"key_protocol":1,"auth_protocol":2,"auth_policy":"${policy.wire}","server_id":"$id","signing_public_key":"$public"}"""
                    "/admin/key-challenge" -> """{"challenge_id":"$id","server_id":"$id","user_id":"$id","auth_session":"$id","purpose":"unlock","public_key":"$public","nonce":"synthetic-nonce"}"""
                    "/admin/unlock" -> """{"token":"${PaperCrypto.b64(ByteArray(32))}","recovery_codes":[],"auth_policy":"${policy.wire}"}"""
                    else -> "{}"
                }
            }
            val api=AdminApi(Origin("https://test.invalid"),transport)
            val status=api.login("synthetic","synthetic-password",id)
            api.unlock("synthetic-password","123456",paper,policy,status)
            val body=wireJson.parseToJsonElement(requests.last().body!!).jsonObject
            assertEquals(policy.totp,body.containsKey("code"))
            assertEquals(policy.paper,body.containsKey("key_proof"))
            assertFalse(requests.any { it.body.orEmpty().contains(paper) })
            assertNull(requests.last().admin)
            if (policy.paper) {
                val proof=body["key_proof"]!!.jsonObject
                val c=Challenge(id,id,id,id,"unlock",public,"synthetic-nonce")
                assertTrue(PaperCrypto.verify(public,PaperCrypto.payload(c),proof["signature"]!!.jsonPrimitive.content))
            }
        }
    }
}

package org.unyri.admin.core

import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpsConfigurator
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Test
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.*

class TlsTest {
    @Test fun untrustedLoopbackCertificateIsRejected() {
        val key=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name=X500Name("CN=localhost")
        val holder=JcaX509v3CertificateBuilder(name,BigInteger.ONE,Date(System.currentTimeMillis()-1000),Date(System.currentTimeMillis()+60000),name,key.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(key.private))
        val cert=JcaX509CertificateConverter().getCertificate(holder)
        val store=KeyStore.getInstance("PKCS12").apply { load(null,null); setKeyEntry("synthetic",key.private,charArrayOf(),arrayOf(cert)) }
        val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store,charArrayOf()) }
        val tls=SSLContext.getInstance("TLS").apply { init(km.keyManagers,null,null) }
        val server=HttpsServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.httpsConfigurator=HttpsConfigurator(tls)
        var received=false
        server.createContext("/health") { received=true; it.sendResponseHeaders(204,-1); it.close() }
        server.start()
        try {
            val client=JvmTransport(Origin("https://localhost:${server.address.port}"))
            assertFailsWith<AdminFailure> { client.send(WireRequest("GET","/health",null,null,null)) }
            assertFalse(received)
        } finally { server.stop(0) }
    }
}

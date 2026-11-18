package com.messenger.app.domain.call

import kotlin.test.*

class CallSasHandshakeTest {
    @Test fun `shared table labels match iOS wire indices in every locale`() {
        val root = java.io.File("src/main/res")
        root.listFiles()!!.filter { java.io.File(it,"hostile.xml").isFile }.forEach { folder ->
            val xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(java.io.File(folder,"hostile.xml"))
            val items = xml.getElementsByTagName("item")
            assertEquals(64,items.length,folder.name)
            if (folder.name == "values") {
                assertEquals("Gamepad",items.item(35).textContent); assertEquals("Hare",items.item(39).textContent)
                assertEquals("Binoculars",items.item(50).textContent); assertEquals("Cup",items.item(43).textContent)
            }
        }
    }
    private val local = "sha-256 " + (0..31).joinToString(":") { "%02x".format(it) }
    private val remote = "sha-256 " + (0..31).joinToString(":") { "%02x".format(255 - it) }
    private val offerNonce = (0..31).joinToString("") { "%02x".format(16 + it) }
    private val answerNonce = (0..31).joinToString("") { "%02x".format(160 + it) }
    private fun sdp(fp: String) = "v=0\r\na=fingerprint:$fp\r\n"
    @Test fun `iOS SAS-2 vector and commit reveal produce identical four shared symbols`() {
        assertEquals("bdcf0e319bf9584572e077d90d8da79ee4087657ed80570628d62b49118e1610", CallSasHandshake.commitment(local, offerNonce))
        val caller = CallSasHandshake(); val callee = CallSasHandshake()
        val offer = caller.offer(sdp(local), "call", offerNonce); callee.receiveOffer(offer, "call")
        val answer = callee.answer(sdp(remote), "call", answerNonce)
        assertNull(callee.indices("call", remote, local))
        val reveal = assertNotNull(caller.receiveAnswer(answer, "call"))
        callee.receiveReveal(reveal, "call")
        assertEquals(listOf(35,39,50,43), caller.indices("call", local, remote))
        assertEquals(caller.indices("call", local, remote), callee.indices("call", remote, local))
        assertEquals(64, CallSasHandshake.symbols.size)
        assertNull(caller.indices("other-call", local, remote))
        assertNull(caller.indices("call", remote, local))
    }
    @Test fun `legacy missing duplicate malformed and mismatched exchanges remain unverified`() {
        val caller = CallSasHandshake(); val callee = CallSasHandshake()
        val offer = caller.offer(sdp(local), "call", offerNonce); callee.receiveOffer(offer, "call")
        val answer = callee.answer(sdp(remote), "call", answerNonce)
        assertNull(caller.receiveAnswer(sdp(remote), "call")); assertNull(caller.indices("call", local, remote))
        assertNull(caller.receiveAnswer(answer + answer.substringAfter("v=0\r\n"), "call"))
        val reveal = assertNotNull(caller.receiveAnswer(answer, "call"))
        callee.receiveReveal(reveal.dropLast(1) + "0", "call"); assertNull(callee.indices("call", remote, local))
        callee.receiveReveal(reveal, "other-call"); assertNull(callee.indices("call", remote, local))
        assertNull(CallSasHandshake.fingerprint(sdp(local) + "a=fingerprint:$remote\r\n"))
        assertNull(CallSasHandshake.fingerprint("a=fingerprint:sha-256 ff:00"))
        caller.reset(); assertNull(caller.indices("call", local, remote))
    }
}

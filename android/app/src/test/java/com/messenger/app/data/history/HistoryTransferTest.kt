package com.messenger.app.data.history
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.Base64

class HistoryTransferTest {
    @Test fun `temporary polling errors back off but expiry auth and changed transcripts stop`() {
        assertEquals(3000L,HistoryTransferRetryPolicy.delay(null,true,0,1000,100000))
        assertEquals(6000L,HistoryTransferRetryPolicy.delay(503,false,1,1000,100000))
        assertEquals(30000L,HistoryTransferRetryPolicy.delay(429,false,0,1000,100000))
        assertEquals(30000L,HistoryTransferRetryPolicy.delay(500,false,999,1000,100000))
        assertEquals(500L,HistoryTransferRetryPolicy.delay(null,true,0,99500,100000))
        for(status in listOf(400,401,403,404,409,410))assertNull(HistoryTransferRetryPolicy.delay(status,false,0,1000,100000))
        assertNull(HistoryTransferRetryPolicy.delay(null,false,0,1000,100000))
        assertNull(HistoryTransferRetryPolicy.delay(503,false,0,100000,100000))
    }
    private fun vector():JsonObject {
        val paths=listOf(File("../../shared/test-vectors/history-transfer-v1.json"),File("../shared/test-vectors/history-transfer-v1.json"),File("shared/test-vectors/history-transfer-v1.json"))
        return Json.parseToJsonElement(paths.first { it.isFile }.readText()).jsonObject
    }
    @Test fun `QR names account server and expiry rejection without accepting any invalid transcript`() {
        val q=Json.decodeFromString<HistoryTransferQR>(vector().getValue("qr").toString());val now=q.expires-60_000
        fun reason(server:String=q.server,owner:String=q.owner,at:Long=now):HistoryTransferValidationFailure.Reason =
            assertThrows(HistoryTransferValidationFailure::class.java){q.validate(server,owner,at)}.reason
        assertEquals(HistoryTransferValidationFailure.Reason.WRONG_SERVER,reason(server="11111111-1111-4111-8111-111111111111"))
        assertEquals(HistoryTransferValidationFailure.Reason.WRONG_ACCOUNT,reason(owner="22222222-2222-4222-8222-222222222222"))
        assertEquals(HistoryTransferValidationFailure.Reason.EXPIRED,reason(at=q.expires))
        assertThrows(Exception::class.java){q.copy(v=2).validate(q.server,q.owner,now)}
    }
    @Test fun `public independent vector interoperates with exact encryption and comparison`() {
        val v=vector();fun value(name:String)=v.getValue(name).jsonPrimitive.content
        val raw=v.getValue("qr").toString();val q=Json.decodeFromString<HistoryTransferQR>(raw);val now=q.expires-60_000
        assertEquals(q,HistoryTransferCrypto.parse(raw,q.server,q.owner,now))
        val decode=Base64.getDecoder();val source=decode.decode(value("sender_private"));val target=decode.decode(value("recipient_private"));val key=decode.decode(value("recovery_key"))
        assertEquals(value("sender_public_key"),HistoryTransferCrypto.public(source))
        assertEquals(value("aad"),String(HistoryTransferCrypto.aad(q,value("sender_public_key"),value("backup_id"))))
        val cipher=HistoryTransferCrypto.encrypt(key,source,q,value("backup_id"),decode.decode(value("nonce")))
        assertEquals(value("ciphertext"),cipher)
        val record=HistoryTransferRecord(q.transfer,q.server,q.owner,q.owner,q.recipient,q.expires,"ready",value("sender_public_key"),value("backup_id"),cipher)
        assertEquals(value("comparison_code"),HistoryTransferCrypto.comparison(q,record,now))
        assertArrayEquals(key,HistoryTransferCrypto.decrypt(target,q,record,now))
        assertThrows(Exception::class.java){HistoryTransferCrypto.decrypt(source,q,record,now)}
        assertThrows(Exception::class.java){HistoryTransferCrypto.decrypt(target,q,record.copy(backup_id=q.transfer),now)}
        assertThrows(Exception::class.java){HistoryTransferCrypto.parse(raw.replace("\"v\":1,",""),q.server,q.owner,now)}
        assertThrows(Exception::class.java){HistoryTransferCrypto.parse(raw,q.server,q.owner,q.expires)}
        assertThrows(Exception::class.java){HistoryTransferCrypto.publicKey(Base64.getEncoder().encodeToString(ByteArray(32)))}
    }
    @Test fun `automatic policy defaults and clock changes are bounded by daily scheduling`() {
        assertTrue(HistoryAutoPolicy.due(1000,0));assertFalse(HistoryAutoPolicy.due(1000,999))
        assertTrue(HistoryAutoPolicy.due(HistoryAutoPolicy.DAY+1000,1000));assertTrue(HistoryAutoPolicy.due(500,1000))
    }
}

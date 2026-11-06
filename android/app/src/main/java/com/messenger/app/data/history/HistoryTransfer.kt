package com.messenger.app.data.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import com.google.crypto.tink.subtle.X25519
import com.google.crypto.tink.subtle.Hkdf
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object HistoryTransferRetryPolicy {
    fun delay(status:Int?,networkFailure:Boolean,attempt:Int,now:Long,expires:Long):Long? {
        if(now>=expires || !(networkFailure || status==429 || (status!=null && status in 500..599))) return null
        val seconds=if(status==429)30L else (3L*(1L shl attempt.coerceIn(0,4))).coerceAtMost(30L)
        return (seconds*1000).coerceAtMost(expires-now)
    }
}

@Serializable data class HistoryTransferQR(val v:Int=1,val type:String="coco-history-transfer",val server:String,val owner:String,val transfer:String,val recipient:String,val expires:Long) {
    fun validate(server:String,owner:String,now:Long=System.currentTimeMillis()) {
        require(v==1 && type=="coco-history-transfer")
        listOf(server,owner,transfer).forEach { requireHistoryId(it); require(it!="00000000-0000-0000-0000-000000000000") }
        if(this.server!=server)throw HistoryTransferValidationFailure(HistoryTransferValidationFailure.Reason.WRONG_SERVER)
        if(this.owner!=owner)throw HistoryTransferValidationFailure(HistoryTransferValidationFailure.Reason.WRONG_ACCOUNT)
        if(expires<=now)throw HistoryTransferValidationFailure(HistoryTransferValidationFailure.Reason.EXPIRED)
        require(expires-now<=11*60*1000)
        HistoryTransferCrypto.publicKey(recipient)
    }
    fun text()=HistoryTransferCrypto.json.encodeToString(this)
}
class HistoryTransferValidationFailure(val reason: Reason) : IllegalArgumentException() {
    enum class Reason { WRONG_SERVER, WRONG_ACCOUNT, EXPIRED }
}
@Serializable data class HistoryTransferRecord(val transfer_id:String,val server_id:String,val owner_id:String,val recipient_device_id:String,val recipient_public_key:String,val expires_at_ms:Long,val status:String,val sender_public_key:String?=null,val backup_id:String?=null,val ciphertext:String?=null) {
    fun pin(qr:HistoryTransferQR,now:Long=System.currentTimeMillis()) {
        qr.validate(qr.server,qr.owner,now)
        require(transfer_id==qr.transfer && server_id==qr.server && owner_id==qr.owner && recipient_public_key==qr.recipient && expires_at_ms==qr.expires)
        require(status in setOf("waiting","ready","consumed","cancelled"))
    }
    fun qr()=HistoryTransferQR(server=server_id,owner=owner_id,transfer=transfer_id,recipient=recipient_public_key,expires=expires_at_ms)
}
@Serializable data class HistoryTransferCreate(val recipient_public_key:String)
@Serializable data class HistoryTransferPublish(val expected_revision:Long,val backup_id:String,val sender_public_key:String,val ciphertext:String)
@Serializable data class HistoryTransferAck(val backup_id:String)
interface HistoryTransferTransport {
    suspend fun createTransfer(publicKey:String):HistoryTransferRecord
    suspend fun transfer(id:String):HistoryTransferRecord
    suspend fun publishTransfer(id:String,body:HistoryTransferPublish):HistoryTransferRecord
    suspend fun ackTransfer(id:String,backup:String):HistoryTransferRecord
    suspend fun cancelTransfer(id:String)
}
object HistoryTransferCrypto {
    internal val json=Json { encodeDefaults=true; ignoreUnknownKeys=false }
    private const val domain="COCO-HISTORY-TRANSFER-V1"
    fun parse(text:String,server:String,owner:String,now:Long=System.currentTimeMillis()):HistoryTransferQR {
        require(text.toByteArray(Charsets.UTF_8).size<=1024)
        require(json.parseToJsonElement(text).jsonObject.keys==setOf("v","type","server","owner","transfer","recipient","expires"))
        return json.decodeFromString<HistoryTransferQR>(text).also { it.validate(server,owner,now) }
    }
    fun privateKey()=X25519.generatePrivateKey()
    fun public(private:ByteArray)=Base64.getEncoder().encodeToString(X25519.publicFromPrivate(private))
    fun publicKey(text:String):ByteArray=Base64.getDecoder().decode(text).also { require(it.size==32 && it.any { b -> b.toInt()!=0 } && Base64.getEncoder().encodeToString(it)==text) }
    fun aad(qr:HistoryTransferQR,sender:String,backup:String):ByteArray {
        publicKey(qr.recipient); publicKey(sender); requireHistoryId(backup)
        require(backup!="00000000-0000-0000-0000-000000000000")
        return listOf(domain,qr.server,qr.owner,qr.transfer,qr.expires.toString(),qr.recipient,sender,backup).joinToString("\n").toByteArray(Charsets.UTF_8)
    }
    private fun derived(private:ByteArray,peer:String,aad:ByteArray):ByteArray {
        val shared=X25519.computeSharedSecret(private,publicKey(peer))
        try { require(shared.any { it.toInt()!=0 }); return Hkdf.computeHkdf("HmacSHA256",shared,MessageDigest.getInstance("SHA-256").digest(aad),domain.toByteArray(),32) }
        finally { shared.fill(0) }
    }
    fun encrypt(key:ByteArray,private:ByteArray,qr:HistoryTransferQR,backup:String,nonce:ByteArray=ByteArray(12).also { SecureRandom().nextBytes(it) }):String {
        require(key.size==32 && nonce.size==12); val sender=public(private); val aad=aad(qr,sender,backup); val derived=derived(private,qr.recipient,aad)
        try { val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(derived,"AES"),GCMParameterSpec(128,nonce)); cipher.updateAAD(aad); return Base64.getEncoder().encodeToString(nonce+cipher.doFinal(key)) }
        finally { derived.fill(0) }
    }
    fun decrypt(private:ByteArray,qr:HistoryTransferQR,record:HistoryTransferRecord,now:Long=System.currentTimeMillis()):ByteArray {
        record.pin(qr,now); require(record.status=="ready" && public(private)==qr.recipient)
        val bytes=Base64.getDecoder().decode(requireNotNull(record.ciphertext)); require(bytes.size==60 && Base64.getEncoder().encodeToString(bytes)==record.ciphertext)
        val aad=aad(qr,requireNotNull(record.sender_public_key),requireNotNull(record.backup_id)); val derived=derived(private,record.sender_public_key,aad)
        try { val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(derived,"AES"),GCMParameterSpec(128,bytes.copyOfRange(0,12))); cipher.updateAAD(aad); return cipher.doFinal(bytes.copyOfRange(12,60)).also { require(it.size==32) } }
        finally { derived.fill(0) }
    }
    fun comparison(qr:HistoryTransferQR,record:HistoryTransferRecord,now:Long=System.currentTimeMillis()):String {
        record.pin(qr,now); val bytes=Base64.getDecoder().decode(requireNotNull(record.ciphertext)); require(bytes.size==60 && Base64.getEncoder().encodeToString(bytes)==record.ciphertext)
        return MessageDigest.getInstance("SHA-256").digest(aad(qr,requireNotNull(record.sender_public_key),requireNotNull(record.backup_id))+bytes).take(8).joinToString(""){"%02X".format(it)}.chunked(4).joinToString("-")
    }
}

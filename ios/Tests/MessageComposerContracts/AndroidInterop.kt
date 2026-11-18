import com.messenger.app.data.attachment.MessageContentCodec
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.ui.calls.chatTimeline
import java.io.File
import kotlinx.serialization.json.*

/** Consumes bytes from the actual Swift sender, with the actual Android serializer
 * and production timeline. No JSON DTO/album grouping implementation is copied. */
fun main(args: Array<String>) {
    val input = Json.parseToJsonElement(File(args[0]).readText()).jsonArray
    val messages = input.mapIndexed { index, entry ->
        val item = entry.jsonObject
        fun value(key: String) = item[key]?.jsonPrimitive?.contentOrNull
        val id = value("id")!!
        val content = MessageContentCodec.decode(value("plaintext")!!.toByteArray(Charsets.UTF_8))
        check(content.kind == "attachment" && content.attachment?.attachmentId == id) { "$id attachment identity" }
        check(content.albumId == value("album")) { "$id iOS-to-Android album_id lost" }
        check(content.text == value("caption")) { "$id iOS-to-Android caption lost" }
        check(content.attachment?.mediaType == value("media")) { "$id media type lost" }
        check(content.attachment?.mimeType == value("mime")) { "$id MIME type lost" }
        if (value("mime")!!.startsWith("video/")) check(content.attachment?.video?.durationMs == 1234L)
        check(content.forwarded == item.getValue("forwarded").jsonPrimitive.boolean)
        check((content.replySnapshot != null) == item.getValue("reply").jsonPrimitive.boolean)
        check(MessageContentCodec.authenticates(content, "wire-chat", id, "content", if (content.replySnapshot != null) "target" else null))
        Message(id, id, "wire-chat", "sender", "attachment", index.toLong() + 1,
            "2026-10-27T10:00:00Z", MessageStatus.SENT, caption = content.text, albumId = content.albumId)
    }
    val rows = chatTimeline(messages, emptyList())
    check(rows.flatMap { it.messages }.map { it.clientMessageId } == messages.map { it.clientMessageId }) { "Album member order changed" }
    val expectedGroups = messages.filter { it.albumId != null }.groupBy { it.albumId }
    check(rows.size == expectedGroups.size + messages.count { it.albumId == null }) { "Album fragmented into separate rows" }
    for ((album, expected) in expectedGroups) {
        val row = rows.single { it.messages.first().albumId == album }
        check(row.messages.map { it.clientMessageId } == expected.map { it.clientMessageId }) { "$album must be a complete 1/2/6/10-item album" }
    }
    val output = input.map { entry ->
        val item = entry.jsonObject
        val content = MessageContentCodec.decode(item.getValue("plaintext").jsonPrimitive.content.toByteArray())
        val encoded = MessageContentCodec.encodeAttachment(content.attachment!!, replySnapshot = content.replySnapshot,
            forwarded = content.forwarded, caption = content.text, albumId = content.albumId)
        JsonObject(item + ("plaintext" to JsonPrimitive(String(encoded, Charsets.UTF_8))))
    }
    File(args[1]).writeText(JsonArray(output).toString())
    println("PASS: actual Android kotlinx.serialization codec accepted ${input.size} authenticated Swift sender envelopes; production timeline kept ${expectedGroups.size} whole albums; all member order/captions; Android return bytes exported")
}

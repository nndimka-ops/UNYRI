package com.messenger.app.data.history

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HistoryArchiveTest {
    @Test fun `old peer logins are readable but never enter new archives or restored peer state`() {
        val peer = HistoryArchivePeer(id(90), "private_chat_login", "Alice", "123456789", "alice")
        val legacy = archive().copy(format = 2, media = emptyList(),
            chats = listOf(HistoryArchiveChat(id(91), "direct", 0, peer)),
            calls = listOf(HistoryArchiveCall(id(92), id(2), id(90), "audio", 1000,
                endedAtMs = 2000, chat = id(91), peer = peer.copy(username = "private_call_login"))))
        // Bypass the new writer to represent a historical encrypted payload.
        val historicalJson = kotlinx.serialization.json.Json { encodeDefaults = true }
        val historical = historicalJson.encodeToString(HistoryArchive.serializer(), legacy).toByteArray()
        val read = HistoryArchive.decode(historical, id(1), id(2))
        assertEquals("", read.chats!!.single().peer!!.username)
        assertEquals("", read.calls.single().peer!!.username)
        val bytes = HistoryArchive.encode(legacy)
        val text = bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains("private_chat_login")); assertFalse(text.contains("private_call_login"))
        assertTrue(text.contains("alice")); assertTrue(text.contains("123456789"))
        assertEquals(read, HistoryArchive.decode(bytes, id(1), id(2)))
        assertEquals("private_chat_login", legacy.chats!!.single().peer!!.username)
        val oversized = legacy.copy(chats = legacy.chats!!.map { it.copy(peer = peer.copy(username = "x".repeat(513))) })
        val hostile = historicalJson.encodeToString(HistoryArchive.serializer(), oversized).toByteArray()
        assertThrows(Exception::class.java) { HistoryArchive.decode(hostile, id(1), id(2)) }
    }

    @kotlin.test.Test fun publicPeerArchiveAcceptsEmptyLegacyUsernameAndPreservesPublicIdentity() {
        val peer = HistoryArchivePeer(id(90), "", "Alice", "123456789", "alice")
        peer.validate()
        val encoded = kotlinx.serialization.json.Json.encodeToString(HistoryArchivePeer.serializer(), peer)
        assertEquals(peer, kotlinx.serialization.json.Json.decodeFromString(HistoryArchivePeer.serializer(), encoded))
        val old = kotlinx.serialization.json.Json.decodeFromString(HistoryArchivePeer.serializer(), """{"id":"${id(90)}","username":"old_peer","displayName":"Alice"}""")
        old.validate()
        assertEquals(null, old.unyriNumber)
        assertEquals(null, old.publicUsername)
    }

    @Test fun `legacy call only archive is repaired without losing call policies`() {
        val old = HistoryArchive.decode(File("../../shared/test-vectors/history-archive-v2-call-only-legacy.json").readBytes(),id(1),id(2))
        val repaired = old.restoringCallChatDescriptors()
        assertEquals(old.calls,repaired.calls); assertEquals(old.callDeletedIds,repaired.callDeletedIds)
        assertEquals(old.callChatClears,repaired.callChatClears); assertEquals(old.callClearedBeforeMs,repaired.callClearedBeforeMs)
        assertEquals(2,repaired.chats!!.size); repaired.validatePreparedChatDescriptors()
        assertEquals(repaired,HistoryArchive.decode(HistoryArchive.encode(repaired),id(1),id(2)))
        assertTrue(old.copy(calls=old.calls.map { it.copy(chatVisible=false) }).restoringCallChatDescriptors().chats!!.isEmpty())
    }
    @Test fun `legacy text archive gains safe chat shells without changing history`() {
        val original = archive(row).copy(reactions=listOf(HistoryArchiveReaction(row.id,id(9),11,"👍")),
            chatClears=mapOf(id(12) to 50L),deletedChats=setOf(id(13)))
        val restored = original.restoringTextChatDescriptors()
        assertEquals(original.messages,restored.messages); assertEquals(original.reactions,restored.reactions)
        assertEquals(original.chatClears,restored.chatClears); assertEquals(original.deletedChats,restored.deletedChats)
        assertEquals("group",restored.chats!!.single().type)
        val known = HistoryArchiveChat(row.chat,"direct",500,HistoryArchivePeer(id(14),"known","Known"))
        assertEquals(known,original.restoringTextChatDescriptors(listOf(known)).chats!!.single())
        val attachment = HistoryArchive.decode(File("../../shared/test-vectors/history-archive-v2.json").readBytes(),id(1),id(2)).withoutMedia()
        assertThrows(HistoryMediaUnavailable::class.java) { attachment.restoringTextChatDescriptors() }
    }
    @Test fun `complete cross platform archive preserves reactions media calls and clear policies`() {
        val fixture = File("../../shared/test-vectors/history-archive-v2-complete.json")
        val original = HistoryArchive.decode(fixture.readBytes(),id(1),id(2))
        assertEquals(original,HistoryArchive.decode(HistoryArchive.encode(original),id(1),id(2)))
        assertEquals(original,HistoryArchiveMerge.merge(original,original))
        assertTrue(original.reactions.isNotEmpty()); assertTrue(original.media!!.isNotEmpty())
        assertTrue(original.calls.isNotEmpty()); assertTrue(original.callDeletedIds.isNotEmpty())
        assertTrue(original.callChatClears.isNotEmpty())
        File("build/history-archive-v2-complete-kotlin.json").writeBytes(HistoryArchive.encode(original))
        // Optional independently emitted Swift artifact for this audit run.
        val swift = System.getenv("COCO_HISTORY_SWIFT_ARCHIVE")?.let(::File)
        if (swift != null) assertEquals(original,HistoryArchive.decode(swift.readBytes(),id(1),id(2)))
    }
    @Test fun `visible call only chats require a portable descriptor`() {
        val call = HistoryArchiveCall(id(20),id(2),id(21),"audio",1000,endedAtMs=2000,chat=id(22))
        val incomplete = archive().copy(format=2,media=emptyList(),chats=emptyList(),calls=listOf(call))
        assertThrows(Exception::class.java) { incomplete.validatePreparedChatDescriptors() }
        assertEquals(incomplete,HistoryArchive.decode(HistoryArchive.encode(incomplete),id(1),id(2)))
        val complete = incomplete.copy(chats=listOf(HistoryArchiveChat(id(22),"direct",0,HistoryArchivePeer(id(21),"","Peer"))))
        assertEquals(complete,HistoryArchive.decode(HistoryArchive.encode(complete),id(1),id(2)))
        assertEquals(id(21),incomplete.restoringCallChatDescriptors().chats!!.single().peer!!.id)
        assertThrows(Exception::class.java) { incomplete.copy(calls=listOf(call,call.copy(id=id(23),callee=id(24)))).restoringCallChatDescriptors() }
        // Calls whose chat was explicitly hidden do not recreate a chat shell.
        HistoryArchive.encode(incomplete.copy(calls=listOf(call.copy(chatVisible=false))))
    }
    @Test fun `v2 authenticates manifest and chat coverage across clients`() {
        val a = HistoryArchive.decode(File("../../shared/test-vectors/history-archive-v2.json").readBytes(), id(1), id(2))
        assertEquals(a, HistoryArchive.decode(HistoryArchive.encode(a), id(1), id(2)))
        assertThrows(Exception::class.java) { HistoryArchive.encode(a.copy(media = emptyList())) }
        assertThrows(Exception::class.java) { HistoryArchive.encode(a.copy(chats = emptyList())) }
        assertThrows(Exception::class.java) { HistoryArchive.encode(a.copy(format = 1)) }
        assertThrows(Exception::class.java) { HistoryArchive.encode(a.copy(media = a.media!!.map { it.copy(bytes = 4) })) }
        val merged = HistoryArchiveMerge.merge(a.withoutMedia().copy(messages=emptyList()), a)
        assertEquals(a.media, merged.media); assertEquals(a.chats, merged.chats)
        val hidden = a.messages.single().copy(hidden=true,content=null)
        val cleared = HistoryArchiveMerge.merge(a.withoutMedia().copy(messages=listOf(hidden)), a)
        assertTrue(cleared.media!!.isEmpty())
        val deleted = a.applyingServerDeletions(mapOf(a.messages.single().id to 99L))
        assertNull(deleted.messages.single().content); assertEquals(99L,deleted.messages.single().deletedThrough)
        assertTrue(deleted.media!!.isEmpty())
        assertThrows(Exception::class.java) { a.applyingServerDeletions(mapOf(id(99) to 100L)) }
        assertThrows(Exception::class.java) { a.applyingServerDeletions(mapOf(a.messages.single().id to 1L)) }
        File("build/history-archive-v2-kotlin.json").writeBytes(HistoryArchive.encode(a))
    }
    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
    private val row = HistoryArchiveMessage(id(4), id(5), id(6), id(2), 10, 1000, "{\"kind\":\"text\",\"text\":\"Привет\"}")
    private fun archive(vararg rows: HistoryArchiveMessage) = HistoryArchive(server=id(1), owner=id(2), snapshot=id(3), messages=rows.toList())
    @Test fun `round trip and repeated import are idempotent`() {
        val original = archive(row)
        assertEquals(original, HistoryArchive.decode(HistoryArchive.encode(original), id(1), id(2)))
        assertEquals(original, HistoryArchiveMerge.merge(original, original))
    }
    @Test fun `reads Swift archive and writes Kotlin interoperability sample`() {
        val fixture = File("../../shared/test-vectors/history-archive-v1.json")
        val restored = HistoryArchive.decode(fixture.readBytes(), id(1), id(2))
        assertEquals(archive(row), restored)
        val output = File("build/history-archive-kotlin.json")
        requireNotNull(output.parentFile).mkdirs()
        output.writeBytes(HistoryArchive.encode(restored))
    }
    @Test fun `old backup cannot resurrect deletion or overwrite new edit`() {
        val newer = row.copy(content="new", editedThrough=12)
        assertEquals(newer, HistoryArchiveMerge.merge(archive(newer), archive(row)).messages.single())
        val deleted = newer.copy(content=null, deletedThrough=15)
        assertNull(HistoryArchiveMerge.merge(archive(deleted), archive(row)).messages.single().content)
        assertNull(HistoryArchiveMerge.merge(archive(row), archive(deleted)).messages.single().content)
        assertNull(HistoryArchiveMerge.merge(archive(row.copy(content=null, hidden=true)), archive(newer)).messages.single().content)
    }
    @Test fun `local and restored clear boundaries both win`() {
        assertTrue(HistoryArchiveMerge.merge(archive().copy(clearedThrough=10), archive(row)).messages.isEmpty())
        assertTrue(HistoryArchiveMerge.merge(archive(row), archive().copy(chatClears=mapOf(id(6) to 10L))).messages.isEmpty())
    }
    @Test fun `deleted chats reactions and content key ordering survive merging`() {
        val deleted = archive().copy(deletedChats=setOf(id(6)))
        assertTrue(HistoryArchiveMerge.merge(archive(row),deleted).messages.isEmpty())
        val removed = HistoryArchiveReaction(row.id,id(8),30,null)
        val active = removed.copy(sequence=20,emoji="👍")
        val merged = HistoryArchiveMerge.merge(archive(row).copy(reactions=listOf(removed)),archive(row).copy(reactions=listOf(active)))
        assertEquals(listOf(removed),merged.reactions)
        val reordered = row.copy(content="{\"text\":\"Привет\",\"kind\":\"text\"}")
        assertEquals(row,HistoryArchiveMerge.merge(archive(row),archive(reordered)).messages.single())
        assertThrows(Exception::class.java) { HistoryArchiveMerge.merge(archive(row),archive(row.copy(replyTo=id(9)))) }
    }
    @Test fun `reject conflicts duplicate ids wrong account and secret fields`() {
        assertThrows(Exception::class.java) { HistoryArchiveMerge.merge(archive(row), archive(row.copy(chat=id(9)))) }
        assertThrows(Exception::class.java) { HistoryArchiveMerge.merge(archive(row), archive(row.copy(content="conflict"))) }
        assertThrows(Exception::class.java) { HistoryArchive.encode(archive(row, row.copy(client=id(9)))) }
        val data = HistoryArchive.encode(archive(row))
        assertThrows(Exception::class.java) { HistoryArchive.decode(data, id(1), id(9)) }
        val extra = data.toString(Charsets.UTF_8).dropLast(1) + ",\"access_token\":\"secret\"}"
        assertThrows(Exception::class.java) { HistoryArchive.decode(extra.toByteArray(), id(1), id(2)) }
    }
    @Test fun `tombstones cannot contain text and unsequenced messages cannot be backed up`() {
        assertThrows(Exception::class.java) { HistoryArchive.encode(archive(row.copy(hidden=true))) }
        assertThrows(Exception::class.java) { HistoryArchive.encode(archive(row.copy(sequence=0))) }
        assertThrows(Exception::class.java) { HistoryArchive.encode(archive(row.copy(editedThrough=5))) }
        assertThrows(Exception::class.java) { HistoryArchive.decode(ByteArray(HistoryArchive.MAX_BYTES+1),id(1),id(2)) }
        val payload = "a".repeat(1024 * 1024)
        assertThrows(Exception::class.java) { HistoryArchive.encode(archive(row.copy(content=payload), row.copy(client=id(7), id=id(8), sequence=11, content=payload))) }
    }
}

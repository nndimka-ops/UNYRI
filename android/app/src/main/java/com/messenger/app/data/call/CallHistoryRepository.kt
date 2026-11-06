package com.messenger.app.data.call

import com.messenger.app.data.api.CallDto
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.AuthRepository
import java.io.File
import java.time.OffsetDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.messenger.app.data.history.*

/** Separate from Signal messages. Authoritative call IDs deduplicate pages and restarts. */
class CallHistoryRepository(
    root: File, private val server: String, private val api: CallApiImpl,
    private val auth: AuthRepository, private val scope: CoroutineScope,
    private val localPersonalClears: suspend () -> List<com.messenger.app.data.api.ChatsApi.PersonalClear> = { emptyList() },
    private val restoredCallDeletions: suspend (List<String>) -> Set<String> = { ids -> check(ids.isEmpty()) { "Restored call validation unavailable" }; emptySet() },
) {
    // Resolve Android's OS-owned /data/user/0 alias, while the restore staging
    // still rejects symlinks within the private call-history directory.
    private val root = File(requireNotNull(root.parentFile).canonicalFile,root.name)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private val deleteFlushMutex = Mutex()
    private val readFlushMutex = Mutex()
    @Volatile private var restoredCheckEpoch = -1
    @Volatile private var restoredCheckAtNanos = 0L
    private val mutableCalls = MutableStateFlow<List<CallDto>>(emptyList())
    val calls = mutableCalls.asStateFlow()
    @Volatile var snapshotEpoch: Int = auth.currentSessionEpoch()
        private set

    init {
        scope.launch {
            combine(auth.authState, auth.historyContext) { state, _ -> state }.collectLatest { state ->
                snapshotEpoch = auth.currentSessionEpoch(); mutableCalls.value = emptyList()
                val owner = (state as? AuthSessionState.LoggedIn)?.user?.id ?: return@collectLatest
                val epoch = auth.currentSessionEpoch()
                mutex.withLock {
                    applyLocalChatClearsLocked(localPersonalClears(), owner, epoch)
                    val cached = withContext(Dispatchers.IO) { runCatching { read(file(owner)) }.getOrDefault(emptyList()) }
                    auth.withSessionWrite(epoch) { snapshotEpoch = auth.currentSessionEpoch(); mutableCalls.value = cached }
                }
            }
        }
    }

    suspend fun refresh(peer: String? = null, before: String? = null): String? = refreshMutex.withLock {
        val owner = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id ?: return@withLock null
        val epoch = auth.currentSessionEpoch()
        val device = auth.currentDeviceId().orEmpty()
        val endpoint = com.messenger.app.data.api.ServerEndpoint.resolve(server)
        val restoredIds = mutex.withLock { withContext(Dispatchers.IO) { readCache(file(owner)).restoredIds.intersect(read(file(owner)).map { it.id }.toSet()).toList() } }
        if (restoredIds.isNotEmpty() && (restoredCheckEpoch != epoch || System.nanoTime() - restoredCheckAtNanos >= 30_000_000_000L)) {
            val removed = restoredCallDeletions(restoredIds)
            check(removed.all { it in restoredIds })
            mutex.withLock { auth.withSessionWrite(epoch) { withContext(Dispatchers.IO) {
                val target = file(owner)
                writeDeletions(target, readDeletions(target).let { it.copy(ids = it.ids + removed) })
                snapshotEpoch = epoch; mutableCalls.value = read(target)
            } } }
            restoredCheckEpoch = epoch; restoredCheckAtNanos = System.nanoTime()
        }
        mutex.withLock { applyLocalChatClearsLocked(localPersonalClears(), owner, epoch) }
        flushDeletes(owner, epoch, device, endpoint)
        try {
            val clears = api.personalClears(epoch)
            mutex.withLock { applyPersonalClearsLocked(clears, owner, epoch) }
        }
        catch (error: com.messenger.app.data.api.AuthApiException.Api) {
            if (error.code != "not_found" && !(error.code == "http_error" && error.message == "HTTP 404")) throw error
        }
        flushReads(owner, epoch)
        try { syncDeletions(owner, epoch) }
        catch (error: com.messenger.app.data.api.AuthApiException.Api) {
            // Rolling upgrade: older servers do not have the deletion feed yet.
            if (error.code != "not_found" && !(error.code == "http_error" && error.message == "HTTP 404")) throw error
        }
        requireDeleteOwner(owner, epoch, device, endpoint)
        val page = api.history(peer, before, epoch)
        mutex.withLock {
        requireDeleteOwner(owner, epoch, device, endpoint)
        auth.withSessionWrite(epoch) {
            val merged = withContext(Dispatchers.IO) {
                val file = file(owner)
                file.parentFile?.mkdirs()
                // Server `history_restored` is not proof of client decryption.
                // Only IDs imported from an authenticated archive may cross the live boundary.
                page.visibleSince?.let { date ->
                    java.time.Instant.parse(date)
                    val boundary = File(file.path + ".visible-since")
                    val tempBoundary = File(boundary.path + ".tmp")
                    tempBoundary.writeText(auth.currentDeviceId().orEmpty() + "|" + date)
                    replaceAtomically(tempBoundary, boundary)
                }
                val removed = readDeletions(file).ids + readPendingDeletes(file).keys
                val localCallClears = readLocalCallClears(file)
                val incomingCutoff = maxOf(readCutoff(file), visibilityCutoff(file))
                val incoming = CallActivityPolicy.applyClears(CallActivityPolicy.suppressPending(page.items, readReadState(file).seen), readChatClears(file))
                    .filter { it.id !in removed && !isLocallyClearedCall(it, localCallClears) && (it.callerUserId == owner || it.calleeUserId == owner) && callTimestamp(it) >= incomingCutoff }
                val merged = read(file).associateBy { it.id }.toMutableMap()
                incoming.forEach { next ->
                    // A delayed pre-terminal snapshot cannot resurrect an ended call.
                    if (merged[next.id]?.state != "ended" || next.state == "ended") merged[next.id] = retainCallPeerIdentity(next, merged[next.id])
                }
                val rows = merged.values.sortedWith(compareByDescending<CallDto> { callTimestamp(it) }.thenByDescending { it.id })
                file.parentFile?.mkdirs()
                val temp = File(file.path + ".tmp")
                java.io.FileOutputStream(temp).use { output ->
                    output.write(json.encodeToString(readCache(file).copy(rows = rows)).toByteArray()); output.fd.sync()
                }
                replaceAtomically(temp, file)
                rows
            }
            snapshotEpoch = auth.currentSessionEpoch(); mutableCalls.value = merged
        }
        }
        page.nextCursor
    }

    suspend fun deleteHistory(id: String, everyone: Boolean) {
        require(runCatching { java.util.UUID.fromString(id) }.isSuccess)
        val owner = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id ?: error("Not authenticated")
        val epoch = auth.currentSessionEpoch()
        val device = auth.currentDeviceId().orEmpty()
        val endpoint = com.messenger.app.data.api.ServerEndpoint.resolve(server)
        mutex.withLock {
            requireDeleteOwner(owner, epoch, device, endpoint)
            val committed = auth.withSessionWrite(epoch) {
                withContext(Dispatchers.IO) {
                    val target = file(owner)
                    val pending = readPendingDeletes(target)
                    writePendingDeletes(target, pending + (id to (everyone || pending[id] == true)))
                    writePending(target, readPending(target) - id)
                }
                snapshotEpoch = auth.currentSessionEpoch(); mutableCalls.value = mutableCalls.value.filterNot { it.id == id }
                true
            }
            if (committed != true) throw CancellationException("Session changed")
            scope.launch {
                try { flushDeletes(owner, epoch, device, endpoint) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Durable command remains for refresh/reconnect. */ }
            }
        }
    }

    private fun requireDeleteOwner(owner: String, epoch: Int, device: String, endpoint: String) {
        if (epoch != auth.currentSessionEpoch() || device != auth.currentDeviceId().orEmpty() ||
            endpoint != com.messenger.app.data.api.ServerEndpoint.resolve(server) ||
            (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id != owner) throw CancellationException("Session changed")
    }

    private fun pendingDeleteFile(file: File): File {
        val device = java.security.MessageDigest.getInstance("SHA-256").digest(auth.currentDeviceId().orEmpty().toByteArray()).joinToString("") { "%02x".format(it) }
        return File(file.path + ".pending-delete-" + device)
    }
    private fun readPendingDeletes(file: File): Map<String, Boolean> = pendingDeleteFile(file).takeIf { it.exists() }
        ?.let { json.decodeFromString<Map<String, Boolean>>(it.readText()) } ?: emptyMap()
    private fun writePendingDeletes(file: File, commands: Map<String, Boolean>) {
        val target = pendingDeleteFile(file); target.parentFile?.mkdirs()
        val temp = File(target.path + ".tmp")
        java.io.FileOutputStream(temp).use { it.write(json.encodeToString(commands).toByteArray()); it.fd.sync() }
        replaceAtomically(temp, target)
    }
    private suspend fun flushDeletes(owner: String, epoch: Int, device: String, endpoint: String = com.messenger.app.data.api.ServerEndpoint.resolve(server)) = deleteFlushMutex.withLock {
        requireDeleteOwner(owner, epoch, device, endpoint)
        val commands = mutex.withLock { withContext(Dispatchers.IO) { readPendingDeletes(file(owner)) } }
        for ((id, everyone) in commands) {
            requireDeleteOwner(owner, epoch, device, endpoint)
            try { api.deleteHistory(id, everyone, epoch) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: com.messenger.app.data.api.AuthApiException.Api) {
                if (error.code != "not_found" && error.message != "HTTP 404") return@withLock
            }
            catch (_: Exception) { return@withLock }
            requireDeleteOwner(owner, epoch, device, endpoint)
            mutex.withLock {
            requireDeleteOwner(owner, epoch, device, endpoint)
            auth.withSessionWrite(epoch) {
                withContext(Dispatchers.IO) {
                    val target = file(owner)
                    val tombstones = readDeletions(target)
                    writeDeletions(target, tombstones.copy(ids = tombstones.ids + id))
                    writePending(target, readPending(target) - id)
                    val latest = readPendingDeletes(target)
                    // An older personal-delete response must not acknowledge a newer everyone-delete.
                    if (latest[id] == everyone) writePendingDeletes(target, latest - id)
                }
            }
            }
        }
    }

    private suspend fun syncDeletions(owner: String, epoch: Int) {
        while (true) {
            val previous = mutex.withLock { withContext(Dispatchers.IO) { readDeletions(file(owner)) } }
            val page = api.historyDeletions(previous.cursor, epoch)
            if (page.items.isEmpty()) return
            val committed = mutex.withLock { auth.withSessionWrite(epoch) {
                val state = withContext(Dispatchers.IO) {
                    val latest = readDeletions(file(owner))
                    val next = CallDeletionState(maxOf(latest.cursor, page.items.maxOfOrNull { it.sequence } ?: 0), latest.ids + page.items.map { it.callId })
                    writeDeletions(file(owner), next)
                    next
                }
                snapshotEpoch = auth.currentSessionEpoch(); mutableCalls.value = mutableCalls.value.filterNot { it.id in state.ids }
                true
            }
            }
            if (committed != true) throw CancellationException("Session changed")
            if (!page.hasMore || page.items.isEmpty()) return
        }
    }
    suspend fun applyPersonalClears(clears: List<com.messenger.app.data.api.ChatsApi.PersonalClear>, epoch: Int) = mutex.withLock {
        val owner = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id ?: return@withLock
        applyPersonalClearsLocked(clears, owner, epoch)
    }
    suspend fun applyLocalChatClears(clears: List<com.messenger.app.data.api.ChatsApi.PersonalClear>, epoch: Int) = mutex.withLock {
        val owner = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id ?: return@withLock
        applyLocalChatClearsLocked(clears, owner, epoch)
    }
    private fun readLocalCallClears(file: File): Map<String, Long> = File(file.path + ".local-chat-clears").takeIf { it.exists() }
        ?.let { json.decodeFromString<Map<String, Long>>(it.readText()) } ?: emptyMap()
    private fun isLocallyClearedCall(call: CallDto, cutoffs: Map<String, Long>): Boolean = call.state == "ended" &&
        cutoffs[call.chatId]?.let { callTimestamp(call) <= it } == true
    private suspend fun applyLocalChatClearsLocked(clears: List<com.messenger.app.data.api.ChatsApi.PersonalClear>, owner: String, epoch: Int) {
        if (clears.isEmpty()) return
        auth.withSessionWrite(epoch) {
            withContext(Dispatchers.IO) {
                val target = file(owner)
                val cutoffs = readLocalCallClears(target).toMutableMap()
                clears.forEach { c -> c.calls_before?.let { cutoffs[c.chat_id] = maxOf(cutoffs[c.chat_id] ?: Long.MIN_VALUE, java.time.Instant.parse(it).toEpochMilli()) } }
                val marker = File(target.path + ".local-chat-clears"); marker.parentFile?.mkdirs()
                val temp = File(marker.path + ".tmp")
                java.io.FileOutputStream(temp).use { it.write(json.encodeToString(cutoffs).toByteArray()); it.fd.sync() }
                replaceAtomically(temp, marker)
                snapshotEpoch = auth.currentSessionEpoch()
                mutableCalls.value = mutableCalls.value.filterNot { isLocallyClearedCall(it, cutoffs) }
            }
        }
        applyPersonalClearsLocked(clears, owner, epoch)
    }
    private fun readChatClears(file: File, cache: CallCache = readCache(file)): Map<String, Long> {
        val saved = File(file.path + ".chat-clears").takeIf { it.exists() }?.let { json.decodeFromString<Map<String, Long>>(it.readText()) } ?: emptyMap()
        val restored = cache.restoredChatClears
        return (saved.keys + restored.keys).associateWith { maxOf(saved[it] ?: 0, restored[it] ?: 0) }
    }
    private suspend fun applyPersonalClearsLocked(clears: List<com.messenger.app.data.api.ChatsApi.PersonalClear>, owner: String, epoch: Int) {
        auth.withSessionWrite(epoch) {
            withContext(Dispatchers.IO) {
                val target = file(owner)
                val cutoffs = readChatClears(target).toMutableMap()
                clears.forEach { clear -> clear.calls_before?.let { raw ->
                    val time = java.time.Instant.parse(raw).toEpochMilli()
                    cutoffs[clear.chat_id] = maxOf(cutoffs[clear.chat_id] ?: Long.MIN_VALUE, time)
                } }
                val marker = File(target.path + ".chat-clears"); marker.parentFile?.mkdirs()
                val temp = File(marker.path + ".tmp")
                java.io.FileOutputStream(temp).use { it.write(json.encodeToString(cutoffs).toByteArray()); it.fd.sync() }
                replaceAtomically(temp, marker)
                if (target.exists()) {
                    val rows = read(target)
                    val historyTemp = File(target.path + ".tmp")
                    java.io.FileOutputStream(historyTemp).use { it.write(json.encodeToString(readCache(target).copy(rows = rows)).toByteArray()); it.fd.sync() }
                    replaceAtomically(historyTemp, target)
                }
                snapshotEpoch = auth.currentSessionEpoch()
                mutableCalls.value = CallActivityPolicy.applyClears(mutableCalls.value, cutoffs)
            }
        }
    }

    suspend fun markViewed(ids: Set<String>) {
        val owner = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id ?: return
        val epoch = auth.currentSessionEpoch()
        mutex.withLock { auth.withSessionWrite(epoch) {
            withContext(Dispatchers.IO) {
                val target = file(owner)
                val eligible = mutableCalls.value.filter { it.id in ids && it.unread && it.state == "ended" }.map { it.id }.toSet()
                if (eligible.isNotEmpty()) {
                    val restored = readCache(target).restoredIds
                    writePending(target, readPending(target) + (eligible - restored))
                    val rows = CallActivityPolicy.suppressPending(read(target), eligible)
                    val temp = File(target.path + ".tmp")
                    java.io.FileOutputStream(temp).use { it.write(json.encodeToString(readCache(target).copy(rows = rows)).toByteArray()); it.fd.sync() }
                    replaceAtomically(temp, target)
                    snapshotEpoch = auth.currentSessionEpoch(); mutableCalls.value = CallActivityPolicy.suppressPending(mutableCalls.value, eligible)
                }
            }
        }
        }
        scope.launch {
            try { flushReads(owner, epoch) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Durable read intent remains for reconnect/refresh. */ }
        }
    }
    @kotlinx.serialization.Serializable
    private data class CallReadState(val pending: Set<String> = emptySet(), val seen: Set<String> = emptySet())
    private fun readReadState(file: File): CallReadState {
        val target = File(file.path + ".read-state")
        if (target.exists()) return json.decodeFromString(target.readText())
        val legacy = File(file.path + ".pending-read").takeIf { it.exists() }
            ?.let { json.decodeFromString<Set<String>>(it.readText()) } ?: emptySet()
        return CallReadState(legacy, legacy)
    }
    private fun readPending(file: File): Set<String> = readReadState(file).pending
    private fun writePending(file: File, ids: Set<String>) {
        // One atomic journal commits local read knowledge and its network intent together.
        // Successful acknowledgement removes pending only; late GETs still see `seen`.
        val state = readReadState(file)
        val target = File(file.path + ".read-state"); target.parentFile?.mkdirs()
        val temp = File(target.path + ".tmp")
        java.io.FileOutputStream(temp).use { it.write(json.encodeToString(CallReadState(ids, state.seen + ids)).toByteArray()); it.fd.sync() }
        replaceAtomically(temp, target)
    }
    private suspend fun flushReads(owner: String, epoch: Int) = readFlushMutex.withLock {
        val pending = mutex.withLock { withContext(Dispatchers.IO) { readPending(file(owner)) - readPendingDeletes(file(owner)).keys - readDeletions(file(owner)).ids } }
        for (id in pending) {
            if (epoch != auth.currentSessionEpoch()) throw CancellationException("Session changed")
            try { api.markRead(id, epoch) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: com.messenger.app.data.api.AuthApiException.Api) {
                if (error.code !in setOf("not_found", "forbidden") && error.message !in setOf("HTTP 404", "HTTP 403")) return@withLock
                // Removed call or no longer visible on this device: discard only its obsolete acknowledgement.
            }
            catch (_: Exception) { return@withLock } // durable queue remains, including when offline
            mutex.withLock { auth.withSessionWrite(epoch) { withContext(Dispatchers.IO) { writePending(file(owner), readPending(file(owner)) - id) } } }
        }
    }

    private fun readDeletions(file: File, cache: CallCache = readCache(file)): CallDeletionState {
        val marker = File(file.path + ".deleted")
        val saved = if (marker.exists()) json.decodeFromString<CallDeletionState>(marker.readText()) else CallDeletionState()
        return saved.copy(ids = saved.ids + cache.restoredDeletedIds)
    }
    private fun writeDeletions(file: File, state: CallDeletionState) {
        val marker = File(file.path + ".deleted")
        marker.parentFile?.mkdirs()
        val temp = File(marker.path + ".tmp")
        java.io.FileOutputStream(temp).use { output -> output.write(json.encodeToString(state).toByteArray()); output.fd.sync() }
        replaceAtomically(temp, marker)
    }

    suspend fun applyHistoryClear(before: Long, epoch: Int): Boolean = mutex.withLock {
        require(before >= 0)
        val owner = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id ?: return@withLock false
        auth.withSessionWrite(epoch) {
            val rows = withContext(Dispatchers.IO) {
                val file = file(owner)
                val marker = File(file.path + ".cleared-before")
                val cutoff = maxOf(before, readCutoff(file))
                marker.parentFile?.mkdirs()
                val temp = File(marker.path + ".tmp")
                java.io.FileOutputStream(temp).use { output ->
                    output.write(cutoff.toString().toByteArray()); output.fd.sync()
                }
                replaceAtomically(temp, marker)
                val remaining = read(file)
                val historyTemp = File(file.path + ".tmp")
                java.io.FileOutputStream(historyTemp).use { output ->
                    output.write(json.encodeToString(readCache(file).copy(rows = remaining)).toByteArray()); output.fd.sync()
                }
                replaceAtomically(historyTemp, file)
                remaining
            }
            snapshotEpoch = auth.currentSessionEpoch(); mutableCalls.value = rows
            true
        } == true
    }
    private fun replaceAtomically(source: File, destination: File) {
        try {
            java.nio.file.Files.move(source.toPath(), destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) { source.delete(); throw error }
    }
    private fun visibilityCutoff(file: File): Long {
        val device = auth.currentDeviceId()
        val boundary = File(file.path + ".visible-since")
        val saved = if (!device.isNullOrEmpty() && boundary.exists()) boundary.readText().takeIf { it.startsWith("$device|") }?.substringAfter('|') else null
        return (saved ?: auth.historyContext.value?.beforeDate)?.let {
            runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
        } ?: Long.MAX_VALUE
    }
    private fun readCutoff(file: File, cache: CallCache = readCache(file)): Long {
        val marker = File(file.path + ".cleared-before")
        return maxOf(if (marker.exists()) marker.readText().toLong() else 0, cache.restoredClearedBefore)
    }

    private fun file(owner: String): File {
        val key = java.security.MessageDigest.getInstance("SHA-256").digest("${com.messenger.app.data.api.ServerEndpoint.resolve(server)}:$owner".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(root, "$key.json")
    }
    private fun read(file: File): List<CallDto> {
        if (!file.exists()) return emptyList()
        val cache = readCache(file)
        val cleared = readCutoff(file, cache)
        val cutoff = maxOf(cleared, visibilityCutoff(file))
        val deleted = readDeletions(file, cache).ids + readPendingDeletes(file).keys
        val localCalls = readLocalCallClears(file)
        return CallActivityPolicy.applyClears(CallActivityPolicy.suppressPending(cache.rows, readReadState(file).seen), readChatClears(file, cache)).filter {
            (callTimestamp(it) >= cutoff || (it.id in cache.restoredIds && callTimestamp(it) >= cleared)) && it.id !in deleted && !isLocallyClearedCall(it, localCalls)
        }
    }

    @kotlinx.serialization.Serializable
    private data class CallCache(val rows: List<CallDto> = emptyList(), val restoredIds: Set<String> = emptySet(),
        val restoredDeletedIds: Set<String> = emptySet(), val restoredClearedBefore: Long = 0,
        val restoredChatClears: Map<String, Long> = emptyMap())
    private fun readCache(file: File): CallCache {
        if (!file.exists()) return CallCache()
        val text = file.readText()
        return if (text.trimStart().startsWith("[")) CallCache(json.decodeFromString(text)) else json.decodeFromString(text)
    }

    suspend fun exportHistory(archive: HistoryArchive, epoch: Int): HistoryArchive = mutex.withLock {
        auth.withSessionWrite(epoch) { withContext(Dispatchers.IO) {
            check((auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id == archive.owner)
            val target = file(archive.owner)
            val deleted = readDeletions(target).ids + readPendingDeletes(target).keys
            val localClears = readLocalCallClears(target); val chatClears = readChatClears(target)
            val clears = (localClears.keys + chatClears.keys).associateWith { maxOf(localClears[it] ?: 0, chatClears[it] ?: 0) }
            val rows = read(target).filter { it.state == "ended" }.map(::archiveCall).map { if (it.chat in archive.deletedChats) it.copy(chatVisible = false) else it }
            archive.copy(calls = rows.sortedWith(compareBy({ it.createdAtMs }, { it.id })), callDeletedIds = deleted,
                callClearedBeforeMs = readCutoff(target), callChatClears = clears).also { it.validate(it.server, it.owner) }
        } } ?: error("History account changed")
    }
    suspend fun prepareBackupHistory() {
        var cursor: String? = null
        val seen = HashSet<String>()
        do {
            cursor = refresh(before = cursor)
            check(calls.value.size <= 50_000) { "Call history is too large" }
            if (cursor != null) check(seen.add(cursor)) { "Call history pagination repeated" }
        } while (cursor != null)
    }

    suspend fun restoreHistory(archive: HistoryArchive, epoch: Int,
        importMessages: suspend (commitCalls: suspend () -> Unit) -> Unit = { commit -> commit() }) = mutex.withLock {
        archive.validate(archive.server, archive.owner)
        auth.withSessionWrite(epoch) { withContext(Dispatchers.IO) {
            check((auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id == archive.owner)
            val target = file(archive.owner)
            val cache = readCache(target)
            val deleted = readDeletions(target, cache).ids + readPendingDeletes(target).keys + archive.callDeletedIds
            val cutoff = maxOf(readCutoff(target, cache), archive.callClearedBeforeMs)
            val chatClears = readChatClears(target, cache)
            val clears = (chatClears.keys + archive.callChatClears.keys).associateWith { maxOf(chatClears[it] ?: 0, archive.callChatClears[it] ?: 0) }
            val rows = read(target).associateBy { it.id }.toMutableMap()
            archive.calls.forEach { call ->
                val old = rows[call.id]
                if (old != null) check(old.callerUserId == call.caller && old.calleeUserId == call.callee && old.mediaKind == call.mediaKind && callTimestamp(old) == call.createdAtMs)
                if (old == null) rows[call.id] = callDto(call)
            }
            val retained = CallActivityPolicy.applyClears(rows.values.toList(), clears).filter { it.id !in deleted && callTimestamp(it) >= cutoff && !isLocallyClearedCall(it, readLocalCallClears(target)) }
                .map { if (it.chatId in archive.deletedChats) it.copy(chatVisible = false, unread = false) else it }
                .sortedWith(compareByDescending<CallDto> { callTimestamp(it) }.thenByDescending { it.id })
            // Restored rows and their deletion/clear policies share one atomic
            // projection. Stage all disk allocation before changing messages.
            val next = CallCache(retained, (cache.restoredIds + archive.calls.map { it.id }).intersect(retained.map { it.id }.toSet()),
                deleted, cutoff, clears)
            HistoryRestoreFileTransaction(target, json.encodeToString(next).toByteArray()).apply { commit ->
                importMessages {
                    check(auth.currentSessionEpoch() == epoch && (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id == archive.owner)
                    commit()
                }
            }
            restoredCheckAtNanos = 0
            snapshotEpoch = epoch; mutableCalls.value = retained
        } } ?: error("History account changed")
    }
    private fun archiveCall(call: CallDto) = HistoryArchiveCall(call.id, call.callerUserId, call.calleeUserId, call.mediaKind,
        callTimestamp(call), call.acceptedAt?.let { java.time.Instant.parse(it).toEpochMilli() }, call.connectedAt?.let { java.time.Instant.parse(it).toEpochMilli() },
        call.endedAt?.let { java.time.Instant.parse(it).toEpochMilli() }, call.endReason, call.chatId, call.chatVisible != false,
        call.peer?.let { HistoryArchivePeer(it.id, it.username, it.displayName) })
    private fun callDto(call: HistoryArchiveCall): CallDto {
        fun date(ms: Long?) = ms?.let { java.time.Instant.ofEpochMilli(it).toString() }
        return CallDto(call.id, call.caller, call.callee, call.mediaKind, "ended", call.endReason, createdAt = requireNotNull(date(call.createdAtMs)),
            acceptedAt = date(call.acceptedAtMs), connectedAt = date(call.connectedAtMs), endedAt = date(call.endedAtMs), chatId = call.chat,
            chatVisible = call.chatVisible, unread = false, peer = call.peer?.let { com.messenger.app.data.api.PublicUserDto(it.id, it.username, it.displayName) })
    }
}

fun callTimestamp(call: CallDto): Long = runCatching { OffsetDateTime.parse(call.createdAt).toInstant().toEpochMilli() }.getOrDefault(0)

enum class CallHistoryOutcome { INCOMING, OUTGOING, MISSED, CANCELLED, REJECTED, FAILED }

fun callOutcome(call: CallDto, owner: String): CallHistoryOutcome = when {
    call.connectedAt != null -> if (call.callerUserId == owner) CallHistoryOutcome.OUTGOING else CallHistoryOutcome.INCOMING
    call.endReason == "rejected" || call.endReason == "busy" -> CallHistoryOutcome.REJECTED
    call.endReason == "failed" || call.endReason == "timeout" -> CallHistoryOutcome.FAILED
    call.callerUserId == owner -> CallHistoryOutcome.CANCELLED
    else -> CallHistoryOutcome.MISSED
}

fun callDurationSeconds(call: CallDto): Long? = runCatching {
    val start = call.connectedAt ?: return null
    val end = call.endedAt ?: return null
    java.time.Duration.between(OffsetDateTime.parse(start), OffsetDateTime.parse(end)).seconds.coerceAtLeast(0)
}.getOrNull()

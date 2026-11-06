package com.messenger.app.data.directtransfer

import android.content.Context
import android.net.Uri
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.data.local.chat.ChatWorkspaceDao
import com.messenger.app.data.local.chat.ChatWorkspaceValue
import com.messenger.app.data.local.message.MessageDao
import com.messenger.app.data.signal.SignalSessionManager
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.AuthRepository
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Account-owned, durable coordinator. Never uses AttachmentsAPI or the call engine. */
class DirectTransferCoordinator internal constructor(
    private val context: Context,
    private val auth: AuthRepository,
    private val workspace: ChatWorkspaceDao,
    private val messages: MessageDao,
    private val signal: SignalSessionManager,
    private val api: DirectTransferApi,
    private val enqueueOffer: suspend (String, DirectFileOffer, Int) -> Unit,
    private val resolveRecipient: suspend (String) -> String,
    private val stunUrls: suspend () -> List<String>,
    private val canStream: () -> Boolean = { true },
    private val background: DirectTransferBackgroundController? = null,
) {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val gate = Mutex()
    private val negotiationLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val manualStopLatches = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val executionWriteStops = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private data class ManualWriteStop(val generation: Long, val needsRead: Boolean, val requestedPaused: Boolean, val pending: Boolean = true, val saveFailed: Boolean = false)
    private val manualWriteStops = java.util.concurrent.ConcurrentHashMap<String, ManualWriteStop>()
    private val publicationReadNeeded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var manualWriteOwner = ""
    private data class ClosingNative(val transport: DirectPeerTransport?, val attempt: String?, val done: kotlinx.coroutines.CompletableDeferred<Unit>)
    private val closingNativeOwners = java.util.concurrent.ConcurrentHashMap<String, ClosingNative>()
    private data class ManualDrainSnapshot(
        val preparation: Job?, val transport: DirectPeerTransport?, val activeAttempt: String?,
        val closing: kotlinx.coroutines.CompletableDeferred<Unit>?, val reservation: DirectSlotLease?,
    )
    private val operationGenerations = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val controlLeases = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private data class CapacityControlLease(val generation: Long, val ordinal: Long, val count: Int)
    private val capacityControlLeases = java.util.concurrent.ConcurrentHashMap<String, CapacityControlLease>()
    private val preparationJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val resourceSlots = DirectTransferSlots()
    private val retiringResources = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()
    private class OwnedConnection(val lease: DirectSlotLease, val transport: DirectPeerTransport) { @Volatile var job: Job? = null }
    private val activeByRole = java.util.concurrent.ConcurrentHashMap<DirectTransferDirection, OwnedConnection>()
    private val workLanes = DirectFileWorkLanes()
    // Disabled until durable admission, API pacing and both-role native resource gates are approved.
    private val allowDuplex = true
    @Volatile private var admissionLedger: DirectFileAdmissionLedger? = null
    private var admissionReady = false
    private var recoveryInventory: DirectRecoveryInventoryStore? = null
    private var recoveryReady = false
    private val admissionChanged = MutableStateFlow(0L)
    private val sourceAllocated = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val sourceReservationBase = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private class SourcePreparation(val id: String, val chat: String, val epoch: Int, val uri: Uri, val executionSession: String?) {
        var selection: StagingExecution? = null
        val handle = DirectSourceReadHandle()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var job: Job? = null
    }
    private val sourcePreparations = java.util.concurrent.ConcurrentHashMap<String, SourcePreparation>()
    private class StagingExecution(val chat: String, val epoch: Int) {
        val explicitCancel = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var executionSession: String? = null
        @Volatile var job: Job? = null
    }
    private val stagingExecutions = java.util.concurrent.ConcurrentHashMap<String, StagingExecution>()
    private val SOURCE_DISK_WINDOW = 8L * 1024 * 1024
    private var accountOutbox: DirectControlOutbox? = null
    private val closingStops = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()
    private val _localStops = MutableStateFlow<Map<String, DirectLocalTransferStop>>(emptyMap())
    val localStops = _localStops.asStateFlow()
    private val _managerItems = MutableStateFlow<List<DirectTransferManagerItem>>(emptyList())
    val managerItems = _managerItems.asStateFlow()
    private val managerPublishLock = Any()
    private var managerPublishedAt = 0L
    private var managerProgressJob: Job? = null
    private val records = MutableStateFlow<Map<String, DirectTransferRecord>>(emptyMap())
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors = _errors.asSharedFlow()
    private val _staging = MutableStateFlow<Set<String>>(emptySet())
    val staging = _staging.asStateFlow()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var loadedEpoch = -1
    @Volatile private var accountStorage: DirectFileStorage? = null




    private var adaptivePollEpoch = -1
    private var coldPollReserveUntil = 0L
    private var coldPollReadReserve = 0
    private var heldPollCadenceMillis = 30_000L
    private val pollAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val pollWakeVersions = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private data class PollTask(val job: Job, val auxiliary: Boolean)
    private val pollTasks = java.util.concurrent.ConcurrentHashMap<String, PollTask>()
    private val failures = mutableMapOf<String, Int>()
    private val offsetAt = mutableMapOf<String, Long>()
    private val latestOffsets = mutableMapOf<String, Long>()
    private val stagingJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val negotiating = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var serviceEpoch = -1

    init {
        api.executionAllowed = { id, epoch ->
            background == null || epoch == loadedEpoch && auth.currentSessionEpoch() == epoch &&
                if (id == null) background.mayStartCapability(epoch) else records.value[id]?.let(::mayPollExecution) == true
        }
        background?.interrupt = { marker, epoch, ids ->
            withContext(dispatcher) { interruptExecution(marker, epoch, ids) }
        }
        background?.disarm = { marker, epoch, idle -> withContext(dispatcher) { disarmExecution(marker, epoch, idle) } }
        background?.changed = { scope.launch { checkpointDisallowedExecution() } }
        scope.launch {
            auth.authState.map { (it as? AuthSessionState.LoggedIn)?.user?.id to auth.currentSessionEpoch() }
                .distinctUntilChanged().collectLatest { (user, epoch) ->
                    stopAllForAccountChange()
                    val previousStorage = accountStorage
                    records.value.values.filterNot { it.completed }.forEach { runCatching { previousStorage?.deleteIncomplete(it.offer.transferId) } }
                    gate.withLock { records.value = emptyMap(); admissionLedger = null; admissionReady = false; recoveryInventory = null; recoveryReady = false; loadedEpoch = -1; accountStorage = null; manualWriteOwner = ""; accountOutbox = null; publicationReadNeeded.clear(); resetManagerPublication() }
                    pollAt.clear(); pollWakeVersions.clear(); failures.clear(); offsetAt.clear(); latestOffsets.clear(); serviceEpoch = -1
                    if (user == null) return@collectLatest
                    val device = auth.currentDeviceId() ?: return@collectLatest
                    val storage = DirectFileStorage(context, ServerEndpoint.current() + "|" + user + "|" + device) { ensureEpoch(epoch) }
                    val ownerKey = DirectFileStorage.digest((ServerEndpoint.current() + "|" + user + "|" + device).toByteArray(Charsets.UTF_8))
                    val inventory = DirectRecoveryInventoryStore(workspace, auth, epoch, ownerKey, ::decodeRecord) { ensureEpoch(epoch) }
                    var covered = true
                    val loaded = try { loadRecoveryRecords(epoch, inventory) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { covered = false; _errors.tryEmit("storage_error"); loadRecentRecoveryRecords(epoch) }

                    gate.withLock {
                        ensureEpoch(epoch); accountStorage = storage; manualWriteOwner = ownerKey; recoveryInventory = inventory; recoveryReady = covered; loadedEpoch = epoch; accountOutbox = createOutbox(epoch)
                        records.value = loaded.mapValues { (_, record) -> record.afterProcessRestart(System.currentTimeMillis()) }
                        // An interrupted unresolved local action requires explicit recovery after same-owner reauthentication.
                        records.value.keys.forEach { id -> manualWriteStops.computeIfPresent(manualWriteKey(id)) { _, stop -> stop.copy(pending = false, saveFailed = true) } }
                        records.value.values.filter { !DirectManualRuntime.state(it).manualByteEligible }.forEach { manualStopLatches.add(it.offer.transferId) }
                        if (recoveryReady) loadAdmissionStateLocked(epoch)
                        publishManager()
                    }
                    coroutineScope {
                    val discovery = launch {
                        admissionChanged.map { recoveryReady }.distinctUntilChanged().collectLatest { ready ->
                            if (ready) {
                                workspace.observeAllDirectTransfers().collect { values ->
                                    if (!recoveryReady) return@collect
                                    gate.withLock {
                                        ensureEpoch(epoch)
                                        val additions = values.mapNotNull { value -> decodeRecord(value.value)?.takeIf {
                                            it.chatId == value.chatId && value.name == key(it.offer.transferId) && it.offer.transferId !in records.value
                                        } }.associateBy { it.offer.transferId }
                                        if (additions.isNotEmpty()) {
                                            try { recoveryInventory?.requireCovered(additions.values) ?: throw DirectStorageError("storage_error") }
                                            catch (_: Exception) { admissionReady = false; _errors.tryEmit("storage_error"); return@withLock }
                                            records.value += additions; publishManager()
                                        }
                                    }
                                    wake.trySend(Unit)
                                }
                            }
                        }
                    }
                    try {
                    while (currentCoroutineContext().isActive && auth.currentSessionEpoch() == epoch) {
                        if (recoveryReady) {
                            if (!canStream() && (activeByRole.isNotEmpty() || preparationJobs.isNotEmpty() || resourceSlots.snapshot().isNotEmpty())) stopForLocalCall(epoch)
                            dispatchPollPass(epoch, pollTasks)
                        }
                        withTimeoutOrNull(1000) { wake.receive() }
                    }
                    } finally { discovery.cancel(); pollTasks.values.forEach { it.job.cancel() } }
                    }
                }
        }
    }

    private fun runtimeNow(): Long = System.nanoTime() / 1_000_000

    private suspend fun loadRecentRecoveryRecords(epoch: Int): Map<String, DirectTransferRecord> {
        ensureEpoch(epoch)
        val recent = auth.withWorkspaceWrite(epoch) { workspace.allDirectTransfers() }
            ?: throw CancellationException("Direct recovery account changed")
        ensureEpoch(epoch)
        return recent.mapNotNull { value -> decodeRecord(value.value)?.takeIf { record ->
            value.chatId == record.chatId && value.name == key(record.offer.transferId)
        } }.associateBy { it.offer.transferId }
    }

    private suspend fun loadRecoveryRecords(epoch: Int, inventory: DirectRecoveryInventoryStore): Map<String, DirectTransferRecord> {
        val recovered = inventory.recover()
        val recent = loadRecentRecoveryRecords(epoch)
        inventory.requireCovered(recent.values)
        ensureEpoch(epoch)
        return recent + recovered.records
    }

    /** Local stop/save is already complete; promote only this owner's captured immutable predecessor. */
    private fun prioritizePollForIntent(id: String, epoch: Int) {
        val predecessor = pollTasks[id]?.job
        val generation = operationGeneration(id)
        scope.launch(DirectHttpContext(DirectHttpPriority.CONTROL, true)) {
            try {
                ensureEpoch(epoch)
                api.prioritizing(id, epoch) {
                    predecessor?.takeIf { it !== currentCoroutineContext()[Job] }?.join()
                    ensureEpoch(epoch)
                    if (operationGeneration(id) == generation) wakeNow(id)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Priority is best effort; no durable intent or frozen body is changed. */ }
        }
    }

    private fun pollPlan(record: DirectTransferRecord): DirectPollPlan = DirectPollPolicy.plan(record,
        ownsRole = ownedActive(record.offer.transferId) != null || reservationFor(record.offer.transferId) != null,
        preparing = preparationJobs.containsKey(record.offer.transferId), outboundCapacityAvailable = outboundCapacityAvailable(), heldCadenceMillis = heldPollCadenceMillis)

    private fun refreshHeldPollCadence(epoch: Int) {
        ensureEpoch(epoch)
        val now = runtimeNow()
        val pollable = records.value.values.filter { it.role != "other" && !(it.mailboxClosed && it.pending.isEmpty()) &&
            !(it.offer.expiresAt <= System.currentTimeMillis() / 1000 && (it.completed || it.state == "expired")) }
        if (adaptivePollEpoch != epoch) {
            adaptivePollEpoch = epoch
            coldPollReserveUntil = if (now > Long.MAX_VALUE - 60_000) Long.MAX_VALUE else now + 60_000
            coldPollReadReserve = (pollable.count { ownedActive(it.offer.transferId) == null && reservationFor(it.offer.transferId) == null }.coerceAtMost(32) + 1)
        }
        val previousCadence = heldPollCadenceMillis
        heldPollCadenceMillis = DirectPollPolicy.heldCadence(pollable,
            ownsRole = { ownedActive(it.offer.transferId) != null || reservationFor(it.offer.transferId) != null },
            preparing = { preparationJobs.containsKey(it.offer.transferId) }, outboundCapacityAvailable = outboundCapacityAvailable(),
            coldReadReserve = if (now < coldPollReserveUntil) coldPollReadReserve else 0)
        if (heldPollCadenceMillis < previousCadence) {
            val earlierDue = if (now > Long.MAX_VALUE - heldPollCadenceMillis) Long.MAX_VALUE else now + heldPollCadenceMillis
            for (record in pollable) {
                val id = record.offer.transferId
                // Only adaptive held deadlines may shorten. Recovery/error delays and
                // live/control/probe schedules retain their independently owned timing.
                if (failures[id] == null && pollPlan(record).held)
                    pollAt.computeIfPresent(id) { _, due -> minOf(due, earlierDue) }
            }
        }
    }

    /** Bounded per-transfer chains; background/terminal repair cannot occupy the live task reserve. */
    private suspend fun dispatchPollPass(epoch: Int, tasks: MutableMap<String, PollTask>) {
        ensureEpoch(epoch)
        val now = runtimeNow()
        refreshHeldPollCadence(epoch)
        val candidates = records.value.values.filter { record ->
            val id = record.offer.transferId
            mayPollExecution(record) && record.role != "other" && !(record.mailboxClosed && record.pending.isEmpty()) &&
                !(record.offer.expiresAt <= System.currentTimeMillis() / 1000 && (record.completed || record.state == "expired")) &&
                id !in tasks && now >= (pollAt[id] ?: 0)
        }.sortedWith(compareBy<DirectTransferRecord> { pollPlan(it).priority.ordinal }
            .thenBy { pollAt[it.offer.transferId] ?: 0L }.thenBy { it.offer.transferId })
        for (record in candidates) {
            if (tasks.size >= 32) break
            val id = record.offer.transferId
            val auxiliary = ownedActive(id) == null && reservationFor(id) == null && !preparationJobs.containsKey(id) && record.requestId == null
            if (auxiliary && tasks.values.count { it.auxiliary } >= 16) continue
            val version = pollWakeVersions[id]?.get() ?: 0
            val plan = pollPlan(record)
            lateinit var job: Job
            job = CoroutineScope(currentCoroutineContext()).launch(DirectHttpContext(plan.priority, plan.urgentWrite), start = CoroutineStart.LAZY) {
                try { pollOne(id, epoch) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    // A recovery journal write can itself fail. Isolate that transfer and retain
                    // bounded retry timing instead of cancelling every other account poll chain.
                    if (auth.currentSessionEpoch() == epoch) {
                        pollAt[id] = nextPollBackoff(id, failure)
                        _errors.tryEmit(errorReason(failure))
                    }
                }
                finally {
                    if (tasks[id]?.job === job) tasks.remove(id)
                    if ((pollWakeVersions[id]?.get() ?: 0) != version) pollAt.remove(id)
                    wake.trySend(Unit)
                }
            }
            tasks[id] = PollTask(job, auxiliary); job.start()
        }
    }

    private suspend fun pollOne(id: String, epoch: Int) {
        val initial = records.value[id] ?: return
        val startedAt = runtimeNow()
        try {
            if (initial.offer.expiresAt <= System.currentTimeMillis() / 1000) {
                if (!initial.completed && initial.state != "expired") {
                    stopTransferResources(id)
                    mutate(id, epoch) { if (it.completed) it else it.copy(state = "expired", closePending = false, pending = emptyList()) }
                    if (records.value[id]?.completed != true) storage(epoch).deleteIncomplete(id)
                }
                return
            }
            tick(id, epoch)
            failures.remove(id)
            records.value[id]?.let { pollAt[id] = maxOf(runtimeNow(), startedAt + pollPlan(it).cadenceMillis) }
        } catch (_: DirectOperationRetired) {
            records.value[id]?.let { pollAt[id] = maxOf(runtimeNow(), startedAt + pollPlan(it).cadenceMillis) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val current = records.value[id] ?: return
            val registrationPending = error is DirectTransferApiError && current.isAwaitingRegistration(error.status, ownedActive(id) != null)
            if (registrationPending) mutate(id, epoch) { it.registrationWaiting() }
            else if (error is DirectTransferApiError && error.status in setOf(401, 403, 404, 409, 410)) {
                if (ownedActive(id) != null) stopTransferResources(id)
                mutate(id, epoch) { if (it.terminal) it else it.copy(state = "paused", reason = "access_unavailable", requestId = null, connectionFailures = 5) }
            }
            pollAt[id] = nextPollBackoff(id, error)
            val reason = when {
                !registrationPending && error is DirectTransferApiError && error.status in setOf(401, 403, 404, 409, 410) -> "access_unavailable"
                error is DirectTransferApiError && error.status == 410 -> "expired"
                error is DirectTransferApiError && error.status in setOf(401, 403) -> "access_unavailable"
                error is DirectTransferApiError && error.status == 429 && current.role == "sender" && !current.registered -> "server_capacity"
                else -> "network_waiting"
            }
            if (!current.terminal && ownedActive(id) == null) mutate(id, epoch) { pollFailureRecord(it, reason) }
        }
    }

    private fun pollFailureRecord(record: DirectTransferRecord, reason: String): DirectTransferRecord {
        // A transient API/journal failure must not erase the durable capacity-waiting queue kind.
        return if (DirectManualRuntime.safetyBlocked(record) || record.reason == "queued" && reason == "network_waiting") record else record.copy(reason = reason)
    }

    private fun nextPollBackoff(id: String, error: Exception): Long {
        val count = ((failures[id] ?: 0) + 1).coerceAtMost(8); failures[id] = count
        val retry = (error as? DirectTransferApiError)?.retryAfterSeconds?.times(1000) ?: 0L
        return runtimeNow() + maxOf(retry, minOf(300_000L, 2000L shl count))
    }

    /** Called only after exact Signal sender/authentication binding validation in ingestion. */
    internal suspend fun receiveOffer(chat: String, offer: DirectFileOffer, epoch: Int) = withContext(dispatcher) {
        offer.validated(); ensureEpoch(epoch)
        awaitLoaded(epoch)
        val user = currentUser() ?: throw CancellationException()
        require(user == offer.recipientUserId || user == offer.senderUserId)
        val role = if (user == offer.recipientUserId) "receiver" else if (auth.currentDeviceId() == offer.senderDeviceId) "sender" else "other"
        gate.withLock {
            val existing = records.value[offer.transferId]
            if (existing != null) { require(existing.offer == offer && existing.chatId == chat); return@withLock }
            val record = DirectTransferRecord(offer, chat, role, if (role == "other") "other_device" else "offered", peerDeviceId = if (role == "receiver") offer.senderDeviceId else null, queue = null)
            saveLocked(record, epoch)
        }
        wake.trySend(Unit)
    }

    fun offerOriginal(chat: String, uri: Uri, expectedEpoch: Int): Boolean = offerOriginals(chat, listOf(uri), expectedEpoch)

    fun offerOriginals(chat: String, uris: List<Uri>, expectedEpoch: Int, finished: () -> Unit = {}, onCommitted: (List<Uri>) -> Unit = {}): Boolean {
        val unique = uris.distinct()
        if (unique.isEmpty() || unique.size > 8 || expectedEpoch != auth.currentSessionEpoch() || !canonicalUuid(chat)) return false
        val owned = mutableListOf<SourcePreparation>()
        val finishedOnce = java.util.concurrent.atomic.AtomicBoolean(false)
        val finishSelection = { if (finishedOnce.compareAndSet(false, true)) runCatching(finished); Unit }
        val stagingExecution = StagingExecution(chat, expectedEpoch)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val execution = stagingExecution.executionSession
            try {
                stagingCurrent(stagingExecution)
                awaitLoaded(expectedEpoch); stagingCurrent(stagingExecution)
                requireService(expectedEpoch); stagingCurrent(stagingExecution)
                val user = currentUser() ?: throw CancellationException()
                val device = auth.currentDeviceId() ?: throw CancellationException()
                val peer = resolveRecipient(chat)
                stagingCurrent(stagingExecution)
                require(peer != user && canonicalUuid(peer)) { "A direct conversation is required" }
                val store = storage(expectedEpoch)
                val descriptions = withContext(Dispatchers.IO) { unique.map(store::describe) }
                stagingCurrent(stagingExecution)
                admitOutgoing(chat, descriptions, expectedEpoch, execution, stagingExecution) { tickets ->
                    // Capture before the journal write so a cancelled/ambiguous return still has an owner.
                    for ((index, ticket) in tickets.withIndex()) {
                        val preparation = SourcePreparation(ticket.id, chat, expectedEpoch, unique[index], execution).also { it.selection = stagingExecution }
                        sourcePreparations[ticket.id] = preparation; owned += preparation
                        sourceAllocated[ticket.id] = 0; sourceReservationBase[ticket.id] = 0
                    }
                }
                admissionChanged.value += 1; publishManager()
                for (preparation in owned) {
                    if (preparation.cancelled.get()) continue
                    try {
                        awaitSourceTurn(preparation)
                        coroutineScope {
                            val child = async(start = CoroutineStart.LAZY) {
                                prepareAdmittedSource(preparation, user, device, peer, store)
                            }
                            preparation.job = child; child.start()
                            val committed = child.await()
                            if (committed && !preparation.cancelled.get()) withContext(Dispatchers.Main.immediate) {
                                ensureEpoch(expectedEpoch); onCommitted(listOf(preparation.uri))
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        if (!preparation.cancelled.get()) throw cancelled
                    } catch (error: Exception) { _errors.tryEmit(errorReason(error)) }
                    finally {
                        retireSourcePreparation(preparation, expectedEpoch, sourceExplicitlyCancelled(preparation))
                        admissionChanged.value += 1; publishManager()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (auth.currentSessionEpoch() == expectedEpoch) _errors.tryEmit(errorReason(error)) }
            finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    // Source handle close and owned worker join precede releasing any uncommitted ticket.
                    owned.forEach { preparation ->
                        if (sourcePreparations[preparation.id] === preparation) {
                            val explicitCancel = sourceExplicitlyCancelled(preparation)
                            preparation.cancelled.set(true)
                            runCatching { withContext(Dispatchers.IO) { preparation.handle.cancel() } }
                            preparation.job?.let { if (it !== currentCoroutineContext()[Job]) it.cancelAndJoin() }
                            retireSourcePreparation(preparation, expectedEpoch, explicitCancel)
                        }
                    }
                }
                releaseStagingExecution(stagingExecution, requireNotNull(stagingExecution.job))
                admissionChanged.value += 1; publishManager(); finishSelection()
            }
        }
        stagingExecution.job = job
        if (!claimStagingExecution(stagingExecution, job)) { job.cancel(); return false }
        // Cancellation before the first dispatched instruction skips the coroutine body's finally.
        job.invokeOnCompletion {
            if (releaseStagingExecution(stagingExecution, job)) { admissionChanged.value += 1; publishManager() }
            finishSelection()
        }
        val requestedExecution = requestUserExecution(expectedEpoch) { marker -> registerStagingExecution(stagingExecution, job, marker) }
        if (requestedExecution == null && !registerStagingExecution(stagingExecution, job, null)) {
            job.cancel()
            releaseStagingExecution(stagingExecution, job)
            return false
        }
        if (!job.start()) {
            releaseStagingExecution(stagingExecution, job)
            admissionChanged.value += 1; publishManager(); finishSelection()
            return false
        }
        return true
    }

    fun cancelPreparation(chat: String) {
        val epoch = auth.currentSessionEpoch()
        if (loadedEpoch != epoch) return
        val selection = synchronized(stagingExecutions) {
            stagingExecutions[chat]?.takeIf { it.epoch == epoch && auth.currentSessionEpoch() == epoch &&
                it.job != null && stagingJobs[chat] === it.job }?.also { it.explicitCancel.set(true) }
        } ?: return
        val owned = sourcePreparations.values.filter { it.selection === selection && it.epoch == epoch }
        owned.forEach { it.cancelled.set(true); it.job?.cancel() }
        selection.job?.cancel()
        scope.launch { owned.forEach { cancelAdmissionTicket(it.id, epoch) } }
    }

    /** A real foreground or connected-network epoch permits a new bounded recovery cycle. */
    fun recoveryEvent() {
        val epoch = auth.currentSessionEpoch()
        scope.launch {
            if (loadedEpoch != epoch) return@launch
            refreshAdmission(epoch)
            if (!recoveryReady || !admissionReady) return@launch
            for (record in records.value.values.toList()) {
                val now = System.currentTimeMillis()
                val capacityRecovery = capacityAfterRecovery(record, now)
                if (capacityRecovery != null && capacityRecovery.queue != record.queue)
                    mutate(record.offer.transferId, epoch) { live -> capacityAfterRecovery(live, now) ?: live }
                val manual = DirectManualRuntime.state(record)
                if (!record.terminal && DirectManualTiming.canResetCycle(now, manual.syncCycleStartedAt))
                    mutate(record.offer.transferId, epoch) { live -> live.copy(manualPause = DirectManualRuntime.state(live).copy(syncNeeded = true,
                        syncProbeCount = 0, nextSyncAt = 0, syncCycleStartedAt = now)) }
                if (!record.terminal && !record.mailboxClosed && ownedActive(record.offer.transferId) == null && record.requestId == null) {
                    mutate(record.offer.transferId, epoch) { it.copy(requestRetryCount = 0, connectionFailures = 0, nextAttemptAt = 0) }
                    wakeNow(record.offer.transferId)
                }
            }
        }
    }

    fun accept(id: String) { val execution = requestUserExecution(); action(id) { epoch ->
        requireService(epoch)
        val record = records.value[id] ?: return@action
        require(record.role == "receiver" && !record.terminal)
        armExecution(id, execution, epoch)
        if (record.consent) { refreshAdmission(epoch); wakeNow(id); return@action }
        admitIncoming(id, epoch)
        val beforeClaim = api.get(id, epoch)
        checkMetadata(records.value.getValue(id), beforeClaim, epoch)
        if (beforeClaim.recipientDeviceId == null) mutate(id, epoch) { live ->
            // Positive unclaimed metadata means no recipient-bound POST existed before our first claim.
            if (!live.registered && live.cursor == 0L && live.pending.isEmpty() && live.sentControlKeys.isEmpty() && live.signalBudget?.identities?.isNotEmpty() != true) live.copy(signalBudget = DirectSignalBudget.fresh()) else live
        }
        if (!manualRequestEligible(records.value[id] ?: return@action)) return@action
        val claimGeneration = operationGeneration(id)
        val metadata = api.claim(id, epoch) { requireReadyClaim(id, claimGeneration, epoch) }
        ensureEpoch(epoch)
        checkMetadata(records.value.getValue(id), metadata, epoch)
        require(metadata.recipientDeviceId == auth.currentDeviceId()) { "Transfer claim did not select this device" }
        mutate(id, epoch) { it.copy(registered = true, peerDeviceId = it.offer.senderDeviceId) }
        requestResume(id, epoch, first = true)
    } }

    fun decline(id: String) = action(id) { epoch ->
        val record = records.value[id] ?: return@action
        if (record.completed || record.terminal) return@action
        require(record.role == "receiver")
        mutate(id, epoch) { if (it.terminal) it else it.copy(state = "rejected", consent = false, closePending = true, reason = null) }
        if (records.value[id]?.let { it.completed || it.state != "rejected" } != false) return@action
        invalidateOperation(id); preparationJobs[id]?.cancel()
        stopTransferResources(id); storage(epoch).deleteIncomplete(id)
        prioritizePollForIntent(id, epoch)
        wakeNow(id)
    }

    fun cancel(id: String) = action(id) { epoch ->
        val record = records.value[id]
        if (record == null) { cancelAdmissionTicket(id, epoch); return@action }
        if (record.completed || record.terminal) return@action
        mutate(id, epoch) { if (it.terminal) it else it.copy(state = "cancelled", closePending = true, reason = null) }
        if (records.value[id]?.let { it.completed || it.state != "cancelled" } != false) return@action
        invalidateOperation(id); preparationJobs[id]?.cancel()
        stopTransferResources(id); storage(epoch).deleteIncomplete(id); prioritizePollForIntent(id, epoch); wakeNow(id)
    }

    fun retry(id: String, restartFromZero: Boolean = false) { val execution = requestUserExecution(); action(id) { epoch ->
        requireService(epoch)
        var record = records.value[id] ?: return@action
        if (record.terminal || !record.consent && record.role == "receiver") return@action
        refreshAdmission(epoch)
        if (!recoveryReady || !admissionReady) return@action
        record = records.value[id] ?: return@action
        if (record.terminal || !record.consent && record.role == "receiver" || ownedActive(id) != null || preparationJobs.containsKey(id)) return@action
        if (record.mailboxClosed) { _errors.tryEmit("transfer_unavailable"); return@action }
        armExecution(id, execution, epoch)
        record = records.value[id] ?: return@action
        if (!DirectManualRuntime.state(record).manualByteEligible) { wakeNow(id); return@action }
        val now = System.currentTimeMillis()
        if (capacityAfterRecovery(record, now) == null) { _errors.tryEmit("manual_sync_cooldown"); return@action }
        mutate(id, epoch) { live -> (capacityAfterRecovery(live, now) ?: live).copy(connectionFailures = 0, nextAttemptAt = 0, requestRetryCount = 0, requestCreatedAt = 0, requestAcknowledgedAt = 0) }
        if (restartFromZero) {
            require(record.role == "receiver" && record.reason in setOf("prefix_mismatch", "integrity_failed", "integrity_mismatch", "verification_failed"))
            stopTransferResources(id)
            storage(epoch).file(id, "partial").takeIf { it.exists() }?.let { file -> java.io.RandomAccessFile(file, "rw").use { it.setLength(0); it.fd.sync() } }
            mutate(id, epoch) { it.copy(durableOffset = 0, reason = null, state = "accepted") }
        }
        if (record.role == "receiver") requestResume(id, epoch) else {
            mutate(id, epoch) { it.copy(state = "waiting", reason = null) }
            if (queueControl(id, "pause", epoch, reason = "reconnect_required") == DirectControlFreezeResult.BLOCKED) {
                mutate(id, epoch) { it.copy(state = "paused", reason = "manual_sync_limit", connectionFailures = 5) }
                _errors.tryEmit(if (records.value[id]?.signalBudget?.known == true) "manual_sync_limit" else "manual_sync_unavailable")
            }
        }
        wakeNow(id)
    } }

    private fun manualWriteKey(id: String): String = "$manualWriteOwner:$id"
    private fun manualWriteStopped(id: String): Boolean = manualWriteStops.containsKey(manualWriteKey(id))
    private fun mayPollExecution(record: DirectTransferRecord): Boolean = background == null ||
        record.terminal && background.foregroundVisible() || executionEligible(record)
    private fun executionEligible(record: DirectTransferRecord): Boolean = !executionWriteStops.containsKey(record.offer.transferId) &&
        !record.executionInterrupted && (background?.mayRun(record.executionSession, record.executionInterrupted) ?: true)
    private fun manualByteEligible(record: DirectTransferRecord): Boolean = executionEligible(record) && !manualWriteStopped(record.offer.transferId) && DirectManualRuntime.byteEligible(record)
    private fun manualRequestEligible(record: DirectTransferRecord): Boolean = executionEligible(record) && !manualWriteStopped(record.offer.transferId) && DirectManualRuntime.requestEligible(record)
    private fun manualPacketMayArm(record: DirectTransferRecord, control: DirectFileControl): Boolean = executionEligible(record) && !manualWriteStopped(record.offer.transferId) && DirectManualRuntime.packetMayArm(record, control)

    private fun requestUserExecution(epoch: Int = auth.currentSessionEpoch(), register: ((String) -> Boolean)? = null): String? {
        val marker = if (register == null) background?.request(epoch) else background?.request(epoch, register)
        background?.failureReason?.let { _errors.tryEmit(it) }
        return marker
    }
    private fun claimStagingExecution(owner: StagingExecution, job: Job): Boolean = synchronized(stagingExecutions) {
        if (auth.currentSessionEpoch() != owner.epoch || currentUser() == null || stagingExecutions.containsKey(owner.chat) || stagingJobs.containsKey(owner.chat)) return@synchronized false
        stagingExecutions[owner.chat] = owner; stagingJobs[owner.chat] = job
        _staging.update { it + owner.chat }
        true
    }
    private fun releaseStagingExecution(owner: StagingExecution, job: Job): Boolean = synchronized(stagingExecutions) {
        if (stagingExecutions[owner.chat] !== owner || stagingJobs[owner.chat] !== job) return@synchronized false
        stagingJobs.remove(owner.chat, job); stagingExecutions.remove(owner.chat, owner)
        _staging.update { it - owner.chat }
        true
    }
    private fun registerStagingExecution(owner: StagingExecution, job: Job, marker: String?): Boolean = synchronized(stagingExecutions) {
        if (auth.currentSessionEpoch() != owner.epoch || stagingExecutions[owner.chat] !== owner || stagingJobs[owner.chat] !== job || owner.explicitCancel.get()) return@synchronized false
        owner.executionSession = marker
        true
    }
    private fun ownsStaging(marker: String, epoch: Int): Boolean = stagingExecutions.values.any {
        it.epoch == epoch && it.executionSession == marker && it.job?.isCompleted != true
    }
    private fun stagingCurrent(owner: StagingExecution) {
        ensureEpoch(owner.epoch)
        if (stagingExecutions[owner.chat] !== owner || owner.job?.isActive != true || owner.explicitCancel.get() || background?.mayRun(owner.executionSession, false) == false) {
            throw CancellationException("Source selection execution stopped")
        }
    }
    private suspend fun armExecution(id: String, marker: String?, epoch: Int) {
        if (background != null && (marker == null || background.currentSession()?.let { it.id == marker && it.epoch == epoch && !it.revoked } != true)) return
        val before = records.value[id] ?: return
        if (before.executionSession == marker && !before.executionInterrupted && !executionWriteStops.containsKey(id)) return
        val generation = invalidateOperation(id)
        executionWriteStops[id] = generation
        // Ambiguous writes stay fenced until another explicit action reads the canonical journal.
        mutatePublished(id, epoch) { if (it.terminal) it else it.copy(executionSession = marker, executionInterrupted = false,
            reason = if (it.reason == "background_interrupted") null else it.reason) }
        executionWriteStops.remove(id, generation)
        publishManager()
    }

    private suspend fun checkpointDisallowedExecution() {
        val epoch = loadedEpoch
        if (background == null || epoch < 0 || epoch != auth.currentSessionEpoch()) return
        val ids = records.value.values.filter { !it.terminal && !it.executionInterrupted && !executionEligible(it) &&
            (it.executionSession != null || ownedActive(it.offer.transferId) != null || reservationFor(it.offer.transferId) != null || preparationJobs.containsKey(it.offer.transferId)) }
            .mapTo(mutableSetOf()) { it.offer.transferId }
        if (ids.isNotEmpty()) interruptExecution(null, epoch, ids)
        val pending = stagingExecutions.values.filter { it.epoch == epoch && !background.mayRun(it.executionSession, false) }.mapNotNull { it.job }
        pending.forEach(Job::cancel)
        sourcePreparations.values.filter { it.epoch == epoch && !background.mayRun(it.executionSession, false) }.forEach { preparation ->
            preparation.job?.cancel()
            runCatching { withContext(Dispatchers.IO) { preparation.handle.cancel() } }
        }
        pending.forEach { if (it !== currentCoroutineContext()[Job]) it.cancelAndJoin() }
    }
    private suspend fun disarmExecution(marker: String, epoch: Int, idle: Boolean): Boolean {
        if (epoch != loadedEpoch || epoch != auth.currentSessionEpoch()) return false
        val members = records.value.values.filter { it.executionSession == marker }
        if (ownsStaging(marker, epoch) || sourcePreparations.values.any { it.epoch == epoch && it.executionSession == marker } || members.any { record ->
            val id = record.offer.transferId
            ownedActive(id) != null || reservationFor(id) != null || preparationJobs.containsKey(id) ||
                !idle && !record.terminal && !DirectExecutionPolicy.settledHold(record)
        }) return false
        if (members.any { manualWriteStopped(it.offer.transferId) || executionWriteStops.containsKey(it.offer.transferId) }) {
            if (!idle) return false
            // An unresolved journal operation after the bounded wait is ambiguous, unlike a settled hold.
            interruptExecution(marker, epoch)
            return true
        }
        val stopped = members.associate { record ->
            val id = record.offer.transferId
            val generation = invalidateOperation(id)
            executionWriteStops[id] = generation
            pollTasks[id]?.job?.cancel()
            id to generation
        }
        for ((id, generation) in stopped) {
            pollTasks[id]?.job?.takeIf { it !== currentCoroutineContext()[Job] }?.cancelAndJoin()
            try {
                mutatePublished(id, epoch) { if (it.executionSession == marker) DirectExecutionPolicy.disarmed(it) else it }
                executionWriteStops.remove(id, generation)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _errors.tryEmit("storage_error"); throw failure }
        }
        publishManager()
        stopped.keys.forEach(::wakeNow)
        return true
    }
    private suspend fun interruptExecution(marker: String?, epoch: Int, onlyIds: Set<String>? = null) {
        if (epoch != loadedEpoch || epoch != auth.currentSessionEpoch()) return
        val pending = if (onlyIds == null) stagingExecutions.values.filter { it.epoch == epoch && (marker == null || it.executionSession == marker) }.mapNotNull { it.job } else emptyList()
        pending.forEach(Job::cancel)
        data class Drain(val marker: String?, val generation: Long, val resources: ManualDrainSnapshot)
        val affected = records.value.values.filter { !it.terminal && (marker == null || it.executionSession == marker) &&
            (onlyIds == null || it.offer.transferId in onlyIds) }.associate { record ->
                val id = record.offer.transferId
                val generation = invalidateOperation(id)
                executionWriteStops[id] = generation
                val resources = captureManualDrain(id)
                pollTasks[id]?.job?.cancel()
                preparationJobs[id]?.cancel(); ownedActive(id)?.job?.cancel()
                id to Drain(record.executionSession, generation, resources)
            }
        // Native and bulk worker drain precede acknowledging a graceful notification stop to the OS.
        affected.forEach { (id, captured) ->
            stopTransferResources(id, captured.resources)
            pollTasks[id]?.job?.takeIf { it !== currentCoroutineContext()[Job] }?.cancelAndJoin()
            runCatching {
                mutatePublished(id, epoch) {
                    if (it.executionSession == captured.marker && operationGeneration(id) == captured.generation) DirectExecutionPolicy.interrupted(it) else it
                }
                executionWriteStops.remove(id, captured.generation)
                if (operationGeneration(id) == captured.generation) notifyPause(id, "background", epoch)
            }.onFailure { if (it is CancellationException) throw it; _errors.tryEmit("storage_error") }
        }
        val sources = sourcePreparations.values.filter { it.epoch == epoch && (marker == null || it.executionSession == marker) && (onlyIds == null || it.id in onlyIds) }
        sources.forEach { it.job?.cancel() }
        sources.forEach { runCatching { withContext(Dispatchers.IO) { it.handle.cancel() } } }
        sources.forEach { it.job?.let { job -> if (job !== currentCoroutineContext()[Job]) job.cancelAndJoin() } }
        pending.forEach { if (it !== currentCoroutineContext()[Job]) it.cancelAndJoin() }
        publishManager()
    }

    fun manualPause(id: String) { beginManualIntent(id, paused = true) }
    fun manualContinue(id: String) {
        val record = records.value[id] ?: return
        if (record.executionInterrupted && !DirectManualRuntime.state(record).localPaused) { retry(id); return }
        beginManualIntent(id, paused = false, execution = requestUserExecution())
    }
    private fun beginManualIntent(id: String, paused: Boolean, execution: String? = null) {
        val epoch = auth.currentSessionEpoch()
        val record = records.value[id] ?: return
        if (!canonicalUuid(id) || loadedEpoch != epoch || !DirectManualRuntime.mayChange(record)) return
        val key = manualWriteKey(id)
        val recovering = manualWriteStops.containsKey(key)
        if (DirectManualRuntime.state(record).localPaused == paused && !recovering) return
        val generation = invalidateOperation(id)
        // This is an immediate process fence, independent of the durable hold/ACK latch.
        manualWriteStops[key] = ManualWriteStop(generation, needsRead = true, requestedPaused = paused)
        publishManager()
        manualStopLatches.add(id)
        preparationJobs[id]?.cancel()
        ownedActive(id)?.job?.cancel()
        changeManualIntent(id, paused, epoch, key, generation, recovering, execution)
    }
    private fun currentManualAction(key: String, generation: Long, epoch: Int): Boolean =
        auth.currentSessionEpoch() == epoch && manualWriteStops[key]?.generation == generation

    private fun changeManualIntent(id: String, paused: Boolean, epoch: Int, key: String, generation: Long, recovering: Boolean, execution: String?) {
        scope.launch {
            var saved = false
            try {
                awaitLoaded(epoch)
                if (!currentManualAction(key, generation, epoch)) return@launch
                // Public callers install only the immediate stop fence; this coherent resource snapshot
                // is captured on our serialized dispatcher before the first journal mutation await.
                val drain = captureManualDrain(id)
                if (!paused) armExecution(id, execution, epoch)
                mutate(id, epoch) { live ->
                    if (!currentManualAction(key, generation, epoch)) return@mutate live
                    val changed = DirectManualRuntime.changedLocal(live, paused, System.currentTimeMillis())
                    // A final prefix can commit while the UI's older Pause is already stopping native.
                    // Keep the verified-prefix checkpoint and reuse fresh-resume verification; the old
                    // request must not strand a full file with no worker and no available manual action.
                    if (changed == live && !live.terminal && live.durableOffset == live.offer.size &&
                        !DirectManualRuntime.state(live).localPaused) {
                        return@mutate DirectManualRuntime.retiringNegotiation(live).copy(
                            reason = if (DirectManualRuntime.safetyBlocked(live)) live.reason else "reconnect_required")
                    }
                    // Retrying an ambiguously committed intent confirms that same revision; never mint another.
                    if (recovering && changed == live && DirectManualRuntime.mayChange(live)) DirectManualRuntime.retiringNegotiation(live) else changed
                }
                if (!currentManualAction(key, generation, epoch)) return@launch
                saved = true
                drainManualResources(id, drain, key, generation, epoch)
                ensureEpoch(epoch)
                if (!currentManualAction(key, generation, epoch)) return@launch
                // Only the latest explicit successful action, after local drain, may clear its fence.
                manualWriteStops.computeIfPresent(key) { _, stop -> if (stop.generation == generation) null else stop }
                publishManager()
                if (!paused && records.value[id]?.signalBudget?.known != true) _errors.tryEmit("manual_sync_unavailable")
                prioritizePollForIntent(id, epoch)
                wakeNow(id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!currentManualAction(key, generation, epoch)) return@launch
                // Keep both failed/ambiguous Pause and Continue fenced. Peer/recovery cannot clear this.
                manualStopLatches.add(id)
                manualWriteStops.computeIfPresent(key) { _, stop -> if (stop.generation == generation) stop.copy(pending = false, saveFailed = true) else stop }
                publishManager()
                _errors.tryEmit(if (saved) errorReason(error) else if (paused) "manual_pause_save_failed" else "manual_continue_save_failed")
            }
        }
    }

    private suspend fun mutate(id: String, epoch: Int, change: (DirectTransferRecord) -> DirectTransferRecord) = gate.withLock {
        reconcileUncertainRecoveryLocked(epoch)
        val memory = records.value[id] ?: return@withLock
        val key = manualWriteKey(id)
        val publicationDirty = key in publicationReadNeeded
        if (!manualWriteStops.containsKey(key) && !publicationDirty) {
            val after = DirectManualJournalPolicy.transition(memory, change(memory))
            if (memory != after) saveLocked(after, epoch)
            return@withLock
        }
        // ONLY this transfer's unresolved local journal operation is noncancellable.
        // No Signal/HTTP/source copy/native-close wait occurs inside this section.
        try { withContext(kotlinx.coroutines.NonCancellable) {
            auth.withWorkspaceWrite(epoch) {
                ensureEpoch(epoch)
                var before = records.value[id] ?: return@withWorkspaceWrite true
                val stop = manualWriteStops[key]
                if (stop?.needsRead == true || publicationDirty) {
                    val raw = workspace.get(before.chatId, key(id)) ?: throw DirectStorageError("storage_error")
                    ensureEpoch(epoch)
                    val durable = decodeRecord(raw) ?: throw DirectStorageError("storage_error")
                    before = DirectManualJournalPolicy.reconciled(before, durable)
                    records.value = records.value + (id to before)
                    publishManager()
                    publicationReadNeeded.remove(key)
                    // Successful reads are not repeated for every peer packet. An ambiguous save dirties it again.
                    if (stop != null) manualWriteStops.computeIfPresent(key) { _, current -> if (current.generation == stop.generation) current.copy(needsRead = false) else current }
                }
                val after = DirectManualJournalPolicy.transition(before, change(before))
                if (before != after) saveLocked(after, epoch)
                ensureEpoch(epoch)
                true
            } ?: throw CancellationException("Transfer account changed")
        } } catch (failure: Throwable) {
            if (publicationDirty && auth.currentSessionEpoch() == epoch && manualWriteKey(id) == key) publicationReadNeeded.add(key)
            throw failure
        }
    }
    private suspend fun saveLocked(record: DirectTransferRecord, epoch: Int) {
        ensureEpoch(epoch)
        val localStopKey = manualWriteKey(record.offer.transferId)
        try {
            auth.withWorkspaceWrite(epoch) {
                ensureEpoch(epoch)
                val inventory = recoveryInventory ?: throw DirectStorageError("storage_error")
                inventory.put(record, DirectFileOffer.codec.encodeToString(DirectTransferRecord.serializer(), record))
                ensureEpoch(epoch)
                val previous = records.value[record.offer.transferId]
                records.value = records.value + (record.offer.transferId to record)
                val progressOnly = previous != null && previous.copy(durableOffset = record.durableOffset,
                    ackSampleId = record.ackSampleId, speedBytesPerSecond = record.speedBytesPerSecond) == record
                publishManager(progressOnly)
                true
            } ?: throw CancellationException("Transfer account changed")
        } catch (failure: Throwable) {
            if (recoveryInventory?.needsRecovery == true) admissionReady = false
            manualWriteStops.computeIfPresent(localStopKey) { _, stop -> stop.copy(needsRead = true) }
            throw failure
        }
    }

    private suspend fun mutatePublished(id: String, epoch: Int, change: (DirectTransferRecord) -> DirectTransferRecord) {
        val key = manualWriteKey(id)
        try { mutate(id, epoch, change) }
        catch (failure: Throwable) {
            // An unresolved protocol publication requires one canonical read, never a raw-record cache.
            if (auth.currentSessionEpoch() == epoch && manualWriteKey(id) == key) publicationReadNeeded.add(key)
            throw failure
        }
    }

    fun retryManualSync(id: String) = action(id) { epoch ->
        val record = records.value[id] ?: return@action
        if (record.terminal || record.mailboxClosed) return@action
        if (record.signalBudget?.known != true) { _errors.tryEmit("manual_sync_unavailable"); return@action }
        val now = System.currentTimeMillis()
        val manual = DirectManualRuntime.state(record)
        if (!DirectManualTiming.canResetCycle(now, manual.syncCycleStartedAt)) { _errors.tryEmit("manual_sync_cooldown"); return@action }
        mutate(id, epoch) { live -> live.copy(manualPause = DirectManualRuntime.state(live).copy(syncNeeded = true,
            syncProbeCount = 0, nextSyncAt = 0, syncCycleStartedAt = now)) }
        wakeNow(id)
    }

    private suspend fun drainManualState(id: String, epoch: Int) {
        val record = records.value[id] ?: return
        if (!DirectManualRuntime.mayPublishState(record)) return
        val manual = DirectManualRuntime.state(record)
        val now = System.currentTimeMillis()
        val genuine = manual.localRevision > manual.publishedLocalRevision
        val response = manual.pendingResponseTo
        val repair = !genuine && response == null && manual.syncNeeded && manual.syncProbeCount < DirectManualTiming.MAX_PROBES && now >= manual.nextSyncAt
        if (!genuine && response == null && !repair) return
        if (record.signalBudget?.mayFreezeOptional(record.pending.size) != true) {
            if (genuine || manual.acknowledgedLocalRevision < manual.localRevision) _errors.tryEmit(if (record.signalBudget?.known == true) "manual_sync_limit" else "manual_sync_unavailable")
            return
        }
        val requestAck = genuine || repair
        val logical = if (genuine) "pause_state:intent:${manual.localRevision}" else if (response != null) "pause_state:reply:$response" else "pause_state:probe:${manual.syncCycleStartedAt}:${manual.syncProbeCount}"
        queueControl(id, "pause_state", epoch, responseTo = response, manualRequestAck = requestAck, manualLogicalKey = logical, manualRepairProbe = repair)
        flushPending(id, epoch)
    }

    private fun createOutbox(epoch: Int): DirectControlOutbox {
        val publication = DirectReadyPublication()
        fun eligible(id: String, frozen: FrozenDirectSignal, generation: Long): Boolean {
            if (auth.currentSessionEpoch() != epoch) return false
            val record = records.value[id] ?: return false
            return readyControlEligible(record, frozen.control, generation)
        }
        return DirectControlOutbox(
        read = { records.value[it] },
        mutate = { id, change -> mutatePublished(id, epoch, change) },
        checkCurrent = { ensureEpoch(epoch) },
        mayCommit = { record, control -> mayCommitControl(record, control) },
        encrypt = { control, bytes ->
            val token = auth.currentAccessToken() ?: throw CancellationException()
            ensureEpoch(epoch)
            val encrypted = signal.withSendingAccount(epoch) {
                signal.buildEnvelopeForDevice(control.recipientUserId, control.recipientDeviceId, token, bytes)
            }
            DirectSignalEnvelope(clientSignalId = control.clientSignalId, recipientDeviceId = control.recipientDeviceId,
                messageType = encrypted.messageType, ciphertext = encrypted.ciphertext)
        },
        beforeFreezePublication = { control ->
            controlLeases[control.clientSignalId]?.let { publication.beforeFreezePublication(control, it) }
        },
        post = { id, envelope ->
            val frozen = records.value[id]?.pending?.firstOrNull { it.envelope == envelope } ?: throw DirectOperationRetired()
            api.post(id, envelope, epoch) { publication.beforeHttpStart(frozen) { eligible(id, frozen, it) } }
        },
        retireBeforePost = { id, frozen -> publication.mayRetire(frozen) { eligible(id, frozen, it) } },
        onRemoved = publication::removed,
        )
    }
    private fun outbox(epoch: Int): DirectControlOutbox { ensureEpoch(epoch); check(loadedEpoch == epoch); return requireNotNull(accountOutbox) }

    private fun mayCommitControl(record: DirectTransferRecord, control: DirectFileControl): Boolean {
        if (record.mailboxClosed || record.peerDeviceId != control.recipientDeviceId) return false
        capacityControlLeases[control.clientSignalId]?.let { expected ->
            val queue = record.queue ?: return false
            if (operationGeneration(record.offer.transferId) != expected.generation || queue.ordinal != expected.ordinal ||
                queue.capacityHintCount != expected.count || capacityHintCandidate(System.currentTimeMillis())?.offer?.transferId != record.offer.transferId) return false
        }
        val terminal = control.type in setOf("cancel", "reject", "complete", "complete_ack")
        val completedProbe = record.completed && record.role == "sender" && record.consent && !record.completionReceived &&
            record.attemptId == control.attemptId && control.type == "pause" && control.reason == "reconnect_required"
        if (record.completed && control.type in setOf("cancel", "reject")) return false
        if (record.terminal && !terminal && !completedProbe) return false
        // Never expose the offer nonce to an outer-metadata-only claimed device.
        if (record.role == "sender" && !record.consent) return false
        if (control.type == "pause_state") return DirectManualRuntime.mayPublishState(record)
        if (control.type in setOf("accept", "resume", "offer", "answer"))
            return controlLeases[control.clientSignalId]?.let { readyControlEligible(record, control, it) } == true
        return true
    }

    private fun readyControlEligible(record: DirectTransferRecord, control: DirectFileControl, generation: Long): Boolean {
        if (record.terminal || record.mailboxClosed || record.peerDeviceId != control.recipientDeviceId || generation != operationGeneration(record.offer.transferId)) return false
        if (record.role == "sender" && !record.consent) return false
        if (record.requestId != control.attemptId) return false
        val snapshot = control.validatedManualSnapshot() ?: return false
        val current = DirectManualRuntime.state(record)
        if (snapshot.pauseRevision != current.localRevision || snapshot.paused != current.localPaused || snapshot.ackPauseRevision != current.peerRevision) return false
        if (control.type in setOf("accept", "resume")) return manualRequestEligible(record)
        val owned = ownedActive(record.offer.transferId) ?: return false
        return owned.lease.attemptId == control.attemptId && owned.lease.generation == generation && resourceSlots.usable(owned.lease) &&
            !retiringResources.containsKey(record.offer.transferId) && manualByteEligible(record) && record.offer.transferId !in manualStopLatches
    }

    private fun requireReadyClaim(id: String, generation: Long, epoch: Int) {
        ensureEpoch(epoch)
        val record = records.value[id] ?: throw DirectOperationRetired()
        if (operationGeneration(id) != generation || !manualRequestEligible(record)) throw DirectOperationRetired()
    }

    private suspend fun queueControl(id: String, type: String, epoch: Int, sdp: String? = null, offset: Long? = null, prefix: String? = null,
                                     reason: String? = null, responseTo: String? = null, attemptOverride: String? = null,
                                     manualRequestAck: Boolean = false, manualLogicalKey: String? = null, manualRepairProbe: Boolean = false,
                                     capacityHint: Boolean = false): DirectControlFreezeResult? {
        val record = records.value[id] ?: return null
        val recipient = record.peerDeviceId ?: return null
        if (capacityHint && (type != "pause" || reason != "reconnect_required" || record.queue == null)) return null
        if (record.role == "sender" && !record.consent) return null
        if (type == "pause_state" && !DirectManualRuntime.mayPublishState(record)) return null
        val attempt = attemptOverride ?: record.attemptId ?: record.requestId ?: UUID.randomUUID().toString()
        val logical = manualLogicalKey ?: "$type:$attempt" + (responseTo?.let { ":$it" } ?: "")
        val idempotenceKey = if (type == "pause" && responseTo == null) "$logical:${UUID.randomUUID()}" else logical
        val snapshot = if (type in setOf("pause_state", "accept", "resume", "offer", "answer")) DirectManualRuntime.state(record).snapshot() else null
        val signalId = UUID.randomUUID().toString()
        val control = DirectFileControl(transferId = id, chatId = record.chatId, offerClientMessageId = record.offer.offerClientMessageId,
            clientSignalId = signalId, senderUserId = currentUser() ?: throw CancellationException(), senderDeviceId = auth.currentDeviceId() ?: throw CancellationException(),
            recipientUserId = if (record.role == "sender") record.offer.recipientUserId else record.offer.senderUserId, recipientDeviceId = recipient,
            offerNonce = record.offer.offerNonce, attemptId = attempt, type = type, sdp = sdp, offset = offset, prefixSha256 = prefix, reason = reason,
            pauseRevision = snapshot?.pauseRevision, paused = snapshot?.paused, ackPauseRevision = snapshot?.ackPauseRevision,
            requestAck = if (type == "pause_state") manualRequestAck else null)
        control.validatedManualSnapshot()
        controlLeases[signalId] = operationGeneration(id)
        if (capacityHint) {
            val queue = requireNotNull(record.queue)
            capacityControlLeases[signalId] = CapacityControlLease(operationGeneration(id), queue.ordinal, queue.capacityHintCount)
        }
        val completedRepair = record.completed && record.role == "sender" && record.consent && !record.completionReceived &&
            record.attemptId == attempt && type == "pause" && reason == "reconnect_required" && responseTo != null
        val optionalWork = type == "pause_state" || type == "pause" && reason in setOf("queued", "reconnect_required") && !completedRepair
        return try {
            outbox(epoch).freeze(control, idempotenceKey, optional = optionalWork) { live, frozen ->
                val counted = if (capacityHint) {
                    val queue = requireNotNull(live.queue)
                    live.copy(queue = queue.copy(capacityHintCount = queue.capacityHintCount + 1,
                        nextCapacityHintAt = System.currentTimeMillis() + DirectManualTiming.probeDelayMillis((queue.capacityHintCount + 1).coerceAtMost(5))))
                } else live
                val state = DirectManualRuntime.state(counted)
                val frozenState = frozen.validatedManualSnapshot()
                if (frozenState == null) counted else {
                    val responseSatisfied = responseTo != null && state.pendingResponseTo == responseTo && frozenState.ackPauseRevision >= state.peerRevision
                    val count = if (manualRepairProbe) minOf(DirectManualTiming.MAX_PROBES, state.syncProbeCount + 1) else state.syncProbeCount
                    val delay = if (manualRepairProbe) DirectManualTiming.probeDelayMillis((count).coerceAtMost(DirectManualTiming.MAX_PROBES - 1)) else 0L
                    counted.copy(manualPause = state.copy(publishedLocalRevision = maxOf(state.publishedLocalRevision, frozenState.pauseRevision),
                        pendingResponseTo = if (responseSatisfied) null else state.pendingResponseTo,
                        syncNeeded = state.acknowledgedLocalRevision < state.localRevision || state.syncNeeded && !responseSatisfied,
                        syncProbeCount = count, nextSyncAt = if (manualRepairProbe) System.currentTimeMillis() + delay else state.nextSyncAt))
                }
            }
        } finally { controlLeases.remove(signalId); capacityControlLeases.remove(signalId) }
    }

    /** Advisory capacity only. It never reserves OUT, clears a hold or bootstraps an unverified peer. */
    private fun outboundCapacityAvailable(): Boolean = admissionReady && canStream() &&
        resourceSlots.current(DirectTransferDirection.OUTBOUND) == null && (allowDuplex || resourceSlots.snapshot().isEmpty())

    private fun capacityHintCandidate(now: Long): DirectTransferRecord? {
        if (!outboundCapacityAvailable()) return null
        // Wait for the oldest eligible peer's bounded repair window before moving to the next one.
        val candidate = records.value.values.asSequence().filter { record ->
            record.role == "sender" && record.registered && record.consent && record.peerDeviceId != null && !record.terminal &&
                record.reason == "queued" && record.requestId == null && record.queue?.capacityHintCount?.let { it < 6 } == true &&
                manualByteEligible(record) && !preparationJobs.containsKey(record.offer.transferId) && !retiringResources.containsKey(record.offer.transferId)
        }.sortedWith(compareBy({ it.queue!!.ordinal }, { it.offer.transferId })).firstOrNull() ?: return null
        return candidate.takeIf { now >= it.queue!!.nextCapacityHintAt }
    }

    private suspend fun drainCapacityHint(id: String, epoch: Int) {
        val candidate = capacityHintCandidate(System.currentTimeMillis())?.takeIf { it.offer.transferId == id } ?: return
        val queue = requireNotNull(candidate.queue)
        if (candidate.signalBudget?.mayFreezeOptional(candidate.pending.size) != true) return
        queueControl(id, "pause", epoch, reason = "reconnect_required", attemptOverride = candidate.lastRequestAttempt ?: candidate.attemptId,
            manualLogicalKey = "capacity_hint:${queue.ordinal}:${queue.capacityHintCount}", capacityHint = true)
        flushPending(id, epoch)
    }

    private fun mayObserveReconnectHint(record: DirectTransferRecord, control: DirectFileControl): Boolean =
        !record.terminal && control.reason == "reconnect_required" && record.role == "receiver" && record.consent &&
            record.requestId == null && ownedActive(record.offer.transferId) == null && reservationFor(record.offer.transferId) == null &&
            !preparationJobs.containsKey(record.offer.transferId) && record.queue?.lastCapacityHintId != control.clientSignalId

    /** Only an explicit retry or real foreground/network event may reset this advisory cache/cycle. */
    private fun capacityAfterRecovery(record: DirectTransferRecord, now: Long): DirectTransferRecord? {
        val queue = record.queue ?: return record
        if (!queue.remoteCapacityBlocked && queue.capacityHintCount < 6) return record
        if (now < 0 || now < queue.nextCapacityHintAt) return null
        return record.copy(queue = queue.copy(remoteCapacityBlocked = false, capacityHintCount = 0,
            nextCapacityHintAt = if (record.role == "receiver") capacityCooldownUntil(now) else 0))
    }

    private fun capacityCooldownUntil(now: Long): Long = if (now < 0) 0 else if (now > Long.MAX_VALUE - DirectManualTiming.RESET_COOLDOWN_MILLIS)
        Long.MAX_VALUE else now + DirectManualTiming.RESET_COOLDOWN_MILLIS

    private fun retiringAfterPeerPause(record: DirectTransferRecord, control: DirectFileControl, observedCapacity: Boolean): DirectTransferRecord =
        record.copy(state = "paused", reason = if (DirectManualRuntime.safetyBlocked(record)) record.reason else control.reason, requestId = null,
            queue = if (observedCapacity && record.queue?.lastCapacityHintId != control.clientSignalId)
                record.queue?.copy(remoteCapacityBlocked = false, lastCapacityHintId = control.clientSignalId) else record.queue,
            pausedForLocalCall = record.pausedForLocalCall && !canStream())

    private fun wakeRoleQueue(direction: DirectTransferDirection) {
        val role = if (direction == DirectTransferDirection.OUTBOUND) "sender" else "receiver"
        records.value.values.filter { it.role == role && !it.terminal && (it.role == "sender" || it.consent) }
            .forEach { wakeNow(it.offer.transferId) }
    }

    private suspend fun flushPending(id: String, epoch: Int) {
        if (!canStream()) {
            // Reuse the owned local drain barriers: lifecycle notifications wait for BOTH roles.
            // Already-started HTTP is immutable; this admits no new notification before the drains finish.
            retiringResources.values.toList().forEach { it.await(); ensureEpoch(epoch) }
        }
        outbox(epoch).flush(id)
    }

    private fun operationGeneration(id: String): Long = operationGenerations.computeIfAbsent(id) { java.util.concurrent.atomic.AtomicLong() }.get()
    private fun invalidateOperation(id: String): Long { return operationGenerations.computeIfAbsent(id) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet() }




    private suspend fun pauseOwnedOperation(id: String, attempt: String, generation: Long, reason: String, epoch: Int) {
        if (auth.currentSessionEpoch() != epoch || operationGeneration(id) != generation) return
        val live = records.value[id] ?: return
        val ownsPreparation = reservationFor(id)?.attemptId == attempt
        if (live.terminal || live.requestId != attempt && live.attemptId != attempt && !ownsPreparation) return
        stopTransferResources(id)
        if (auth.currentSessionEpoch() != epoch || operationGeneration(id) != generation) return
        releaseReservation(id, attempt)
        mutate(id, epoch) { current ->
            if (current.terminal || current.requestId != attempt && current.attemptId != attempt && !ownsPreparation || operationGeneration(id) != generation) current
            else current.copy(state = "paused", reason = if (DirectManualRuntime.safetyBlocked(current)) current.reason else reason, requestId = null, pausedForLocalCall = reason == "call_in_progress",
                connectionFailures = (current.connectionFailures + 1).coerceAtMost(5),
                nextAttemptAt = System.currentTimeMillis() + minOf(300_000L, 2000L shl (current.connectionFailures + 1).coerceAtMost(7)))
        }
        if (operationGeneration(id) == generation) notifyPause(id, reason, epoch, attempt)
        wakeNow(id)
    }

    private suspend fun stopForControlBudget(id: String, attempt: String, generation: Long, epoch: Int) {
        if (auth.currentSessionEpoch() != epoch || operationGeneration(id) != generation) return
        val record = records.value[id] ?: return
        if (record.terminal || record.requestId != attempt && record.attemptId != attempt) return
        val drain = captureManualDrain(id)
        val retiringGeneration = invalidateOperation(id)
        ownedActive(id)?.job?.cancel()
        mutatePublished(id, epoch) { live ->
            if (live.terminal || operationGeneration(id) != retiringGeneration || live.requestId != attempt && live.attemptId != attempt) live
            else live.copy(state = "paused", reason = if (DirectManualRuntime.safetyBlocked(live)) live.reason else "manual_sync_limit",
                requestId = null, acceptedRequest = null, connectionFailures = 5, speedBytesPerSecond = 0)
        }
        if (auth.currentSessionEpoch() != epoch || operationGeneration(id) != retiringGeneration) return
        stopTransferResources(id, drain)
        if (auth.currentSessionEpoch() == epoch && operationGeneration(id) == retiringGeneration) {
            _errors.tryEmit(if (records.value[id]?.signalBudget?.known == true) "manual_sync_limit" else "manual_sync_unavailable")
            wakeNow(id)
        }
    }

    /** Scalars only, no lease, revision, consent, peer proof or retry authority. */
    private fun publishLocalStops() {
        val current = records.value
        val owner = manualWriteOwner
        if (owner.isEmpty() || loadedEpoch != auth.currentSessionEpoch()) { _localStops.value = emptyMap(); return }
        val prefix = "$owner:"
        // Current-account deletion/terminal completion retires only that exact transfer's display/failure entry.
        manualWriteStops.entries.forEach { (key, stop) ->
            if (key.startsWith(prefix)) {
                val id = key.removePrefix(prefix)
                if (current[id]?.terminal != false) manualWriteStops.remove(key, stop)
            }
        }
        _localStops.value = current.values.asSequence().filter { !it.terminal && it.role in setOf("sender", "receiver") }
            .mapNotNull { record -> manualWriteStops["$prefix${record.offer.transferId}"]?.let { stop ->
                record.offer.transferId to DirectLocalTransferStop(record.offer.transferId, stop.requestedPaused, stop.pending, stop.saveFailed)
            } }.toMap()
    }
    private fun resetManagerPublication() = synchronized(managerPublishLock) {
        managerProgressJob?.cancel(); managerProgressJob = null; managerPublishedAt = 0
        _localStops.value = emptyMap(); _managerItems.value = emptyList()
    }
    private fun publishManager(progressOnly: Boolean = false): Unit = synchronized(managerPublishLock) {
        val epoch = loadedEpoch
        val owner = manualWriteOwner
        if (epoch != auth.currentSessionEpoch() || owner.isEmpty()) return@synchronized
        val now = runtimeNow()
        val remaining = 250L - (now - managerPublishedAt)
        if (progressOnly && remaining > 0) {
            if (managerProgressJob?.isActive != true) managerProgressJob = scope.launch {
                delay(remaining)
                if (auth.currentSessionEpoch() == epoch && loadedEpoch == epoch && manualWriteOwner == owner) publishManager()
            }
            return@synchronized
        }
        managerProgressJob?.cancel(); managerProgressJob = null
        publishLocalStops()
        val stops = _localStops.value
        // One display projection supplies both the group and the rendered row; no duplicated eligibility.
        val recordItems = records.value.values.filter { it.role in setOf("sender", "receiver") }.map { record ->
            val base = record.presentation()
            val presentation = base.withLocalStop(stops[record.offer.transferId])
            val localStop = stops[record.offer.transferId]?.takeIf { presentation !== base }
            val manual = DirectManualRuntime.state(record)
            val group = when {
                record.completed -> DirectTransferManagerGroup.COMPLETED
                record.terminal || record.closedUnavailable -> DirectTransferManagerGroup.ATTENTION
                record.role == "receiver" && !record.consent -> DirectTransferManagerGroup.ATTENTION
                !admissionReady -> DirectTransferManagerGroup.ATTENTION
                record.executionInterrupted || executionWriteStops.containsKey(record.offer.transferId) -> DirectTransferManagerGroup.HELD
                localStop?.saveFailed == true -> DirectTransferManagerGroup.ATTENTION
                localStop != null -> DirectTransferManagerGroup.HELD
                ownedActive(record.offer.transferId) != null -> DirectTransferManagerGroup.ACTIVE
                manual.localPaused || manual.peerPaused || !manual.manualByteEligible -> DirectTransferManagerGroup.HELD
                record.connectionFailures >= 5 -> DirectTransferManagerGroup.ATTENTION
                record.reason == "manual_sync_limit" || record.role == "sender" && record.reason == "queued" && record.queue?.capacityHintCount == 6 -> DirectTransferManagerGroup.ATTENTION
                record.reason in setOf("security_error", "storage_error", "source_missing", "source_changed", "prefix_mismatch", "access_unavailable") -> DirectTransferManagerGroup.ATTENTION
                else -> DirectTransferManagerGroup.QUEUED
            }
            DirectTransferManagerItem(record.offer.transferId, record.chatId, record.role == "sender", presentation, record.queue?.ordinal ?: Long.MAX_VALUE,
                group, canCancel = !record.terminal, canOpen = record.completed && record.role == "receiver",
                canRetry = stops[record.offer.transferId] == null && !record.terminal && !record.mailboxClosed && record.state == "paused",
                canRestartFromZero = stops[record.offer.transferId] == null && !record.mailboxClosed && record.role == "receiver" && record.reason in setOf("prefix_mismatch", "integrity_failed", "integrity_mismatch", "verification_failed"))
        }
        val ticketItems = admissionLedger?.tickets.orEmpty().filter { it.id !in records.value }.map { ticket ->
            val source = sourcePreparations[ticket.id]
            val attention = ticket.needsReselection || source == null || !ticket.outgoing || ticket.chatId == null || !admissionReady
            DirectTransferManagerItem(ticket.id, ticket.chatId.orEmpty(), ticket.outgoing, null, ticket.ordinal,
                if (attention) DirectTransferManagerGroup.ATTENTION else DirectTransferManagerGroup.QUEUED,
                canCancel = true, preparing = source?.job?.isActive == true && source.cancelled.get() == false,
                displayFilename = ticket.displayFilename, declaredBytes = ticket.declaredBytes)
        }
        val items = recordItems + ticketItems
        // Display queue numbers count exactly the displayed QUEUED rows, separately for OUT and IN.
        val queued = items.filter { it.group == DirectTransferManagerGroup.QUEUED }.sortedWith(compareBy({ it.ordinal }, { it.id }))
        val projected = items.map { item ->
            if (item.group != DirectTransferManagerGroup.QUEUED) item
            else item.copy(queuePosition = queued.filter { it.outgoing == item.outgoing }.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }?.plus(1))
        }.sortedWith(compareBy({ it.ordinal }, { it.id }))
        if (auth.currentSessionEpoch() == epoch && loadedEpoch == epoch && manualWriteOwner == owner) {
            _managerItems.value = projected; managerPublishedAt = now
            publishExecutionProgress(epoch)
        }
    }

    private fun publishExecutionProgress(epoch: Int) {
        val session = background?.currentSession()?.takeIf { it.epoch == epoch && !it.revoked } ?: return
        val entries = records.value.values.filter { it.executionSession == session.id }.map { record ->
            DirectExecutionProgressEntry(record.offer.transferId, record.role == "sender", record.offer.filename, record.durableOffset, record.offer.size,
                ownedActive(record.offer.transferId) != null || preparationJobs.containsKey(record.offer.transferId) || reservationFor(record.offer.transferId) != null,
                record.terminal && (!record.completed || record.mailboxClosed),
                record.executionInterrupted || DirectExecutionPolicy.settledHold(record))
        }
        background.update(DirectExecutionProgress(epoch, session.id, entries,
            ownsStaging(session.id, epoch) || sourcePreparations.values.any { it.epoch == epoch && it.executionSession == session.id }))
    }

    private suspend fun admitOutgoing(chat: String, descriptions: List<DirectFileSourceDisplay>, epoch: Int, execution: String?, owner: StagingExecution,
        allocated: (List<DirectFileAdmissionTicket>) -> Unit): List<DirectFileAdmissionTicket> = gate.withLock {
        stagingCurrent(owner)
        val ledger = readAdmissionLocked(epoch)
        if (!DirectAdmissionPolicy.mayAdmit(records.value.values, ledger, outgoing = true, count = descriptions.size)) throw DirectStorageError("too_many")
        require(ledger.nextOutboundOrdinal <= Long.MAX_VALUE - descriptions.size)
        val tickets = descriptions.mapIndexed { index, description ->
            val future = description.declaredBytes?.takeIf { it > 0 } ?: SOURCE_DISK_WINDOW
            DirectFileAdmissionTicket(UUID.randomUUID().toString(), true, ledger.nextOutboundOrdinal + index, future,
                displayFilename = description.displayFilename, declaredBytes = description.declaredBytes, chatId = chat, executionSession = execution)
        }
        if (!DirectAdmissionPolicy.mayReserveDisk(storage(epoch).availableBytes(), reservedFutureExcept(), tickets.sumOf { it.reservedFutureBytes })) throw DirectStorageError("disk_full")
        allocated(tickets)
        stagingCurrent(owner)
        val updated = ledger.copy(nextOutboundOrdinal = ledger.nextOutboundOrdinal + tickets.size, tickets = ledger.tickets + tickets)
        try { saveAdmissionLocked(updated, epoch) { stagingCurrent(owner) } }
        catch (failure: Throwable) {
            // The whole selection is one small row. A postcommit return may confirm it; no partial consent/copy.
            val durable = withContext(kotlinx.coroutines.NonCancellable) { readAdmissionLocked(epoch) }
            if (tickets.any { ticket -> durable.tickets.none { it == ticket } }) throw failure
        }
        stagingCurrent(owner)
        tickets
    }
    private fun sourceCurrent(preparation: SourcePreparation) {
        ensureEpoch(preparation.epoch)
        if (background?.mayRun(preparation.executionSession, false) == false) throw CancellationException("Source background execution stopped")
        if (sourceExplicitlyCancelled(preparation) || sourcePreparations[preparation.id] !== preparation) throw CancellationException("Source preparation cancelled")
        preparation.handle.checkCurrent()
    }
    private suspend fun awaitSourceTurn(preparation: SourcePreparation) {
        admissionChanged.first {
            sourceCurrent(preparation)
            val first = admissionLedger?.tickets.orEmpty().filter { it.outgoing && !it.needsReselection && sourcePreparations[it.id]?.cancelled?.get() == false }
                .minWithOrNull(compareBy({ it.ordinal }, { it.id }))
            first?.id == preparation.id
        }
        sourceCurrent(preparation)
    }
    private suspend fun beforeSourceWrite(preparation: SourcePreparation, targetCount: Long) {
        sourceCurrent(preparation)
        val allocated = sourceAllocated[preparation.id] ?: 0L
        val base = sourceReservationBase[preparation.id] ?: 0L
        val ticket = admissionLedger?.tickets?.firstOrNull { it.id == preparation.id } ?: throw CancellationException("Source ticket ended")
        val remaining = (ticket.reservedFutureBytes - allocated + base).coerceAtLeast(0)
        if (remaining >= targetCount - allocated) return
        withContext(dispatcher) { gate.withLock {
            sourceCurrent(preparation)
            val ledger = readAdmissionLocked(preparation.epoch)
            val current = ledger.tickets.firstOrNull { it.id == preparation.id && !it.needsReselection } ?: throw CancellationException("Source ticket ended")
            val actual = sourceAllocated[preparation.id] ?: 0L
            val future = minOf(SOURCE_DISK_WINDOW, DirectFileWire.MAX_FILE_BYTES - actual)
            if (future < targetCount - actual) throw DirectStorageError("too_large")
            if (!DirectAdmissionPolicy.mayReserveDisk(storage(preparation.epoch).availableBytes(), reservedFutureExcept(preparation.id), future)) throw DirectStorageError("disk_full")
            saveAdmissionLocked(ledger.copy(tickets = ledger.tickets.map { if (it.id == current.id) it.copy(reservedFutureBytes = future) else it }), preparation.epoch)
            sourceReservationBase[preparation.id] = actual
        } }
    }
    private suspend fun prepareAdmittedSource(preparation: SourcePreparation, user: String, device: String, peer: String, store: DirectFileStorage): Boolean {
        var offered: DirectFileOffer? = null
        var committed = false
        try {
            val staged = workLanes.runStaging { store.stage(preparation.id, preparation.uri, preparation.handle,
                beforeWrite = { beforeSourceWrite(preparation, it) }, afterWrite = { sourceAllocated[preparation.id] = it }) }
            sourceCurrent(preparation); currentCoroutineContext().ensureActive()
            val offer = DirectFileOffer(transferId = preparation.id, offerClientMessageId = UUID.randomUUID().toString(), senderUserId = user,
                senderDeviceId = device, recipientUserId = peer, filename = staged.filename, mimeType = staged.mimeType, offerNonce = DirectOfferNonce.create(),
                size = staged.size, sha256 = staged.sha256, expiresAt = System.currentTimeMillis() / 1000 + 7 * 24 * 60 * 60).validated()
            offered = offer
            withContext(dispatcher) { gate.withLock {
                sourceCurrent(preparation)
                val ticket = admissionLedger?.tickets?.firstOrNull { it.id == preparation.id } ?: throw CancellationException("Source ticket ended")
                require(records.value[preparation.id] == null) { "Transfer id already owned" }
                saveLocked(DirectTransferRecord(offer, preparation.chat, "sender", state = "waiting", signalBudget = DirectSignalBudget.fresh(), queue = DirectFileQueueState(ticket.ordinal, 0), executionSession = preparation.executionSession), preparation.epoch)
            } }
            committed = true
            removeAdmissionTicket(preparation.id, preparation.epoch)
            sourceCurrent(preparation)
            enqueueOffer(preparation.chat, offer, preparation.epoch); wakeNow(preparation.id)
            return true
        } catch (failure: Throwable) {
            if (!committed && offered != null && auth.currentSessionEpoch() == preparation.epoch) {
                // Adopt only this exact immutable offer after ambiguous commit; never delete a potentially owned original.
                committed = withContext(kotlinx.coroutines.NonCancellable) { withContext(dispatcher) { gate.withLock {
                    auth.withWorkspaceWrite(preparation.epoch) {
                        val raw = workspace.get(preparation.chat, key(preparation.id)); ensureEpoch(preparation.epoch)
                        val durable = raw?.let(::decodeRecord)
                        if (durable != null) {
                            require(durable.offer == offered && durable.chatId == preparation.chat && durable.role == "sender")
                            val memory = records.value[preparation.id]
                            records.value = records.value + (preparation.id to if (memory == null) durable else DirectManualJournalPolicy.reconciled(memory, durable))
                            publishManager(); true
                        } else false
                    } ?: throw CancellationException("Transfer account changed")
                } } }
            }
            if (committed && auth.currentSessionEpoch() == preparation.epoch) runCatching { removeAdmissionTicket(preparation.id, preparation.epoch) }
            throw failure
        } finally {
            if (!committed && auth.currentSessionEpoch() == preparation.epoch) withContext(kotlinx.coroutines.NonCancellable) {
                abandonSource(preparation, preparation.epoch, sourceExplicitlyCancelled(preparation), expectedOffer = offered)
            }
        }
    }
    private suspend fun removeAdmissionTicket(id: String, epoch: Int) = gate.withLock {
        val ledger = readAdmissionLocked(epoch)
        if (ledger.tickets.any { it.id == id }) saveAdmissionLocked(ledger.copy(tickets = ledger.tickets.filterNot { it.id == id }), epoch)
    }
    private fun sourceExplicitlyCancelled(preparation: SourcePreparation): Boolean = preparation.cancelled.get() || preparation.selection?.explicitCancel?.get() == true
    private suspend fun retireSourcePreparation(preparation: SourcePreparation, epoch: Int, explicitCancel: Boolean) = withContext(kotlinx.coroutines.NonCancellable) {
        if (sourcePreparations[preparation.id] !== preparation) return@withContext
        if (auth.currentSessionEpoch() == epoch) runCatching { abandonSource(preparation, epoch, explicitCancel || preparation.selection?.explicitCancel?.get() == true) }
            .onFailure { _errors.tryEmit("storage_error") }
        if (sourcePreparations.remove(preparation.id, preparation)) {
            sourceAllocated.remove(preparation.id); sourceReservationBase.remove(preparation.id)
        }
    }
    private suspend fun abandonSource(preparation: SourcePreparation, epoch: Int, cancelled: Boolean, expectedOffer: DirectFileOffer? = null) = withContext(dispatcher) { gate.withLock {
        ensureEpoch(epoch)
        val currentOwner = sourcePreparations[preparation.id]
        if (currentOwner != null && currentOwner !== preparation) return@withLock
        // Positive absent-row proof before deleting any app-owned staging copy. Unknown/corrupt means preserve.
        val raw = auth.withWorkspaceWrite(epoch) { workspace.get(preparation.chat, key(preparation.id)) }
        ensureEpoch(epoch)
        if (raw != null) {
            val durable = decodeRecord(raw) ?: throw DirectStorageError("storage_error")
            require(durable.offer.transferId == preparation.id && durable.chatId == preparation.chat && durable.role == "sender" && (expectedOffer == null || durable.offer == expectedOffer))
            val memory = records.value[preparation.id]
            records.value = records.value + (preparation.id to if (memory == null) durable else DirectManualJournalPolicy.reconciled(memory, durable))
            val ledger = readAdmissionLocked(epoch)
            saveAdmissionLocked(ledger.copy(tickets = ledger.tickets.filterNot { it.id == preparation.id }), epoch)
            return@withLock
        }
        storage(epoch).deleteStagingSource(preparation.id)
        val ledger = readAdmissionLocked(epoch)
        val next = if (cancelled) ledger.tickets.filterNot { it.id == preparation.id }
            else ledger.tickets.map { if (it.id == preparation.id) it.copy(needsReselection = true, reservedFutureBytes = 0) else it }
        saveAdmissionLocked(ledger.copy(tickets = next), epoch)
    } }
    private suspend fun cancelAdmissionTicket(id: String, epoch: Int) {
        val preparation = sourcePreparations[id]?.takeIf { it.epoch == epoch }
        preparation?.cancelled?.set(true)
        preparation?.job?.cancel()
        if (preparation != null) {
            withContext(Dispatchers.IO) { preparation.handle.cancel() }
            preparation.job?.let { if (it !== currentCoroutineContext()[Job]) it.cancelAndJoin() }
            abandonSource(preparation, epoch, cancelled = true)
            sourcePreparations.remove(id, preparation); sourceAllocated.remove(id); sourceReservationBase.remove(id)
        } else removeAdmissionTicket(id, epoch)
        admissionChanged.value += 1; publishManager()
        if (records.value[id]?.terminal == false) cancel(id)
    }
    private suspend fun stopAllSourcePreparations(epoch: Int) {
        val owned = sourcePreparations.values.filter { it.epoch == epoch }
        owned.forEach { it.cancelled.set(true); it.job?.cancel() }
        // Provider close runs outside the source lane, which may be blocked inside that very provider read.
        owned.forEach { runCatching { withContext(Dispatchers.IO) { it.handle.cancel() } } }
        owned.forEach { it.job?.let { job -> if (job !== currentCoroutineContext()[Job]) job.cancelAndJoin() } }
        sourcePreparations.entries.removeAll { it.value.epoch == epoch }
        sourceAllocated.clear(); sourceReservationBase.clear(); admissionChanged.value += 1
    }

    private fun admissionKey(): String = "direct_admission:$manualWriteOwner"
    /** Reconcile only unresolved membership saves, before any caller can transform stale memory. */
    private suspend fun reconcileUncertainRecoveryLocked(epoch: Int) {
        val inventory = recoveryInventory ?: return
        if (!inventory.hasUncertainWrites) return
        inventory.recover { snapshot -> mergeRecoveredRecordsLocked(snapshot, epoch) }
    }

    private fun mergeRecoveredRecordsLocked(snapshot: DirectRecoverySnapshot, epoch: Int) {
        ensureEpoch(epoch)
        val previous = records.value
        var merged = previous
        for ((id, durable) in snapshot.records) {
            val before = merged[id]
            merged = merged + (id to if (before == null) durable else DirectManualJournalPolicy.reconciled(before, durable))
        }
        ensureEpoch(epoch); records.value = merged
        if (merged != previous) publishManager()
    }

    private suspend fun readAdmissionLocked(epoch: Int): DirectFileAdmissionLedger {
        ensureEpoch(epoch)
        val inventory = try {
            val inventory = recoveryInventory ?: throw DirectStorageError("storage_error")
            if (!recoveryReady) {
                val loaded = loadRecoveryRecords(epoch, inventory); ensureEpoch(epoch)
                for ((id, record) in loaded) {
                    val durable = record.afterProcessRestart(System.currentTimeMillis())
                    val before = records.value[id]
                    records.value += id to if (before == null) durable else DirectManualJournalPolicy.reconciled(before, durable)
                }
                inventory.requireCovered(records.value.values)
                recoveryReady = true
                admissionChanged.value += 1
                wake.trySend(Unit)
            }
            if (inventory.needsRecovery) {
                inventory.recover { snapshot -> mergeRecoveredRecordsLocked(snapshot, epoch) }
            }
            inventory.requireCovered(records.value.values)
            inventory
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (auth.currentSessionEpoch() == epoch) admissionReady = false; throw failure }
        val result = try { auth.withWorkspaceWrite(epoch) {
            val raw = workspace.get("__direct_transfer_admission__", admissionKey())
            ensureEpoch(epoch)
            val decoded = if (raw == null) DirectFileAdmissionLedger() else {
                require(raw.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Admission journal too large" }
                DirectFileOffer.codec.decodeFromString(DirectFileAdmissionLedger.serializer(), raw).validated()
            }
            val bounds = inventory.ordinalBounds()
            DirectAdmissionPolicy.recovered(decoded.copy(nextOutboundOrdinal = maxOf(decoded.nextOutboundOrdinal, bounds.first + 1),
                nextInboundOrdinal = maxOf(decoded.nextInboundOrdinal, bounds.second + 1)), records.value.values)
        } ?: throw CancellationException("Transfer account changed") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (auth.currentSessionEpoch() == epoch) admissionReady = false; throw failure }
        admissionLedger = result; admissionReady = true
        return result
    }
    private suspend fun saveAdmissionLocked(value: DirectFileAdmissionLedger, epoch: Int, beforeWrite: () -> Unit = {}) {
        value.validated(); ensureEpoch(epoch)
        val encoded = DirectFileOffer.codec.encodeToString(DirectFileAdmissionLedger.serializer(), value)
        require(encoded.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        auth.withWorkspaceWrite(epoch) {
            beforeWrite()
            workspace.put(ChatWorkspaceValue("__direct_transfer_admission__", admissionKey(), encoded)); ensureEpoch(epoch)
            admissionLedger = value; admissionReady = true; admissionChanged.value += 1; publishManager(); true
        } ?: throw CancellationException("Transfer account changed")
    }
    private suspend fun loadAdmissionStateLocked(epoch: Int) {
        try {
            var ledger = readAdmissionLocked(epoch)
            val kept = mutableListOf<DirectFileAdmissionTicket>()
            for (ticket in ledger.tickets) {
                if (ticket.id in records.value) continue // Canonical record wins a crash between record commit and ticket removal.
                if (sourcePreparations[ticket.id]?.epoch == epoch) { kept += ticket; continue }
                val chat = ticket.chatId
                val raw = if (chat == null) null else auth.withWorkspaceWrite(epoch) { workspace.get(chat, key(ticket.id)) }
                ensureEpoch(epoch)
                if (raw != null) {
                    val durable = decodeRecord(raw) ?: throw DirectStorageError("storage_error")
                    require(durable.offer.transferId == ticket.id && durable.chatId == chat && durable.role == "sender")
                    records.value = records.value + (ticket.id to durable)
                } else {
                    // Only known-chat positive absence authorizes deleting this unadvertised app-owned copy.
                    if (chat != null) storage(epoch).deleteStagingSource(ticket.id)
                    kept += ticket.copy(needsReselection = true, reservedFutureBytes = 0)
                }
            }
            if (kept != ledger.tickets) { ledger = ledger.copy(tickets = kept); saveAdmissionLocked(ledger, epoch) }
            // Counters recover against ALL records/tickets. Old accepted records obtain a durable order once.
            val legacy = records.value.values.filter { DirectAdmissionPolicy.unfinished(it) && it.queue == null }
                .sortedWith(compareBy({ it.offer.expiresAt }, { it.offer.transferId }))
            for (record in legacy) {
                val outgoing = record.role == "sender"
                val ordinal = if (outgoing) ledger.nextOutboundOrdinal else ledger.nextInboundOrdinal
                require(ordinal < Long.MAX_VALUE)
                ledger = if (outgoing) ledger.copy(nextOutboundOrdinal = ordinal + 1) else ledger.copy(nextInboundOrdinal = ordinal + 1)
                saveAdmissionLocked(ledger, epoch) // A crash may leave a harmless gap, never an ordinal reuse.
                saveLocked(record.copy(queue = DirectFileQueueState(ordinal, if (outgoing) 0 else record.offer.size - record.durableOffset)), epoch)
            }
            admissionLedger = ledger; admissionReady = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { admissionReady = false; admissionLedger = null; _errors.tryEmit("storage_error") }
    }
    private suspend fun refreshAdmission(epoch: Int) = gate.withLock { loadAdmissionStateLocked(epoch); publishManager() }
    private fun reservedFutureExcept(id: String? = null): Long {
        val current = records.value
        val receiverFuture = current.values.filter { it.offer.transferId != id && it.role == "receiver" && DirectAdmissionPolicy.unfinished(it) }
            .sumOf { (it.offer.size - it.durableOffset).coerceAtLeast(0) }
        val ticketFuture = admissionLedger?.tickets.orEmpty().filter { it.id != id && it.id !in current }
            .sumOf { ticket -> (ticket.reservedFutureBytes - (sourceAllocated[ticket.id] ?: 0L) + (sourceReservationBase[ticket.id] ?: 0L)).coerceAtLeast(0) }
        return receiverFuture + ticketFuture
    }
    private suspend fun admitIncoming(id: String, epoch: Int) = gate.withLock {
        val before = records.value[id] ?: return@withLock
        if (before.terminal || before.role != "receiver" || before.consent) return@withLock
        try {
            val ledger = readAdmissionLocked(epoch)
            if (!DirectAdmissionPolicy.mayAdmit(records.value.values, ledger, outgoing = false)) throw DirectStorageError("too_many")
            val remaining = before.offer.size - before.durableOffset
            if (!DirectAdmissionPolicy.mayReserveDisk(storage(epoch).availableBytes(), reservedFutureExcept(id), remaining)) throw DirectStorageError("disk_full")
            val ordinal = ledger.nextInboundOrdinal; require(ordinal < Long.MAX_VALUE)
            saveAdmissionLocked(ledger.copy(nextInboundOrdinal = ordinal + 1), epoch)
            val accepted = before.copy(consent = true, state = "accepted", reason = null, requestRetryCount = 0, connectionFailures = 0,
                nextAttemptAt = 0, requestCreatedAt = 0, requestAcknowledgedAt = 0, queue = DirectFileQueueState(ordinal, remaining))
            try { saveLocked(accepted, epoch) }
            catch (failure: Throwable) {
                // A cancelled/postcommit Room return must not turn durable user consent back into false memory.
                withContext(kotlinx.coroutines.NonCancellable) {
                    auth.withWorkspaceWrite(epoch) {
                        val raw = workspace.get(before.chatId, key(id)); ensureEpoch(epoch)
                        val durable = raw?.let(::decodeRecord)
                        if (durable != null && durable.offer == before.offer && durable.chatId == before.chatId && durable.role == before.role) {
                            records.value = records.value + (id to DirectManualJournalPolicy.reconciled(before, durable)); publishManager()
                        }
                        true
                    }
                }
                throw failure
            }
        } catch (failure: Exception) {
            if (failure !is CancellationException && failure !is DirectStorageError) admissionReady = false
            throw failure
        }
    }
    private fun nextEligible(role: String): String? = records.value.values.asSequence().filter { record ->
        record.role == role && !record.terminal && !record.mailboxClosed && record.queue != null && !retiringResources.containsKey(record.offer.transferId) &&
            !manualWriteStopped(record.offer.transferId) && record.connectionFailures < 5 &&
            (record.requestId != null || record.requestRetryCount < DirectTransferTiming.MAX_REQUESTS) &&
            System.currentTimeMillis() >= record.nextAttemptAt && if (role == "receiver") {
                manualRequestEligible(record) && record.queue.remoteCapacityBlocked == false
            } else {
                val request = record.acceptedRequest
                request != null && record.requestId == request.attemptId && manualPacketMayArm(record, request)
            }
    }.sortedWith(compareBy({ it.queue?.ordinal ?: Long.MAX_VALUE }, { it.offer.transferId })).firstOrNull()?.offer?.transferId

    private fun ownedActive(id: String): OwnedConnection? = activeByRole.values.firstOrNull { it.lease.transferId == id }
    private fun allActive(): List<OwnedConnection> = activeByRole.values.toList()
    private fun reservationFor(id: String): DirectSlotLease? = resourceSlots.snapshot().firstOrNull { it.transferId == id }
    private fun slotAvailable(record: DirectTransferRecord): Boolean {
        if (record.role !in setOf("sender", "receiver") || retiringResources.containsKey(record.offer.transferId)) return false
        val lease = resourceSlots.current(DirectAdmissionPolicy.direction(record.role))
        return lease == null || lease.transferId == record.offer.transferId && resourceSlots.usable(lease)
    }
    private suspend fun reserveSlot(id: String, attempt: String, replaceOwned: Boolean = true): Boolean = gate.withLock {
        val record = records.value[id] ?: return@withLock false
        if (!admissionReady || !executionEligible(record) || record.role !in setOf("sender", "receiver") || retiringResources.containsKey(id) || closingStops.containsKey(id) || manualWriteStopped(id)) return@withLock false
        val role = DirectAdmissionPolicy.direction(record.role)
        if (!allowDuplex && resourceSlots.snapshot().any { it.direction != role }) return@withLock false
        val existing = resourceSlots.current(role)
        if (!replaceOwned && existing != null) return@withLock false
        if (existing != null && existing.transferId == id && existing.attemptId == attempt && existing.accountEpoch == loadedEpoch &&
            existing.generation == operationGeneration(id) && resourceSlots.usable(existing)) return@withLock true
        if (nextEligible(record.role) != id) return@withLock false
        resourceSlots.reserve(role, id, attempt, loadedEpoch, operationGeneration(id)) != null
    }
    private fun releaseReservation(id: String, attempt: String? = null, expected: DirectSlotLease? = null, afterDrain: Boolean = false) {
        val lease = expected ?: reservationFor(id) ?: return
        if (lease.transferId != id || attempt != null && lease.attemptId != attempt || !resourceSlots.owns(lease)) return
        if (ownedActive(id) != null || closingStops.containsKey(id) || !afterDrain && retiringResources.containsKey(id)) return
        resourceSlots.beginClosing(lease)
        releaseSlotAfterDrain(lease)
    }
    private fun releaseSlotAfterDrain(lease: DirectSlotLease) {
        if (resourceSlots.releaseAfterDrain(lease)) wakeRoleQueue(lease.direction)
    }
    private fun requireOperation(id: String, generation: Long, epoch: Int, allowBootstrap: Boolean = false, expected: DirectSlotLease? = null) {
        ensureEpoch(epoch)
        val record = records.value[id] ?: throw DirectOperationRetired()
        if (expected != null && !resourceSlots.usable(expected)) throw DirectOperationRetired()
        if (operationGeneration(id) != generation || record.terminal || retiringResources.containsKey(id) ||
            (if (allowBootstrap) !manualRequestEligible(record) else !manualByteEligible(record) || id in manualStopLatches)) throw DirectOperationRetired()
    }
    /** Captures one immutable role-owned context; public UI callers never build an ownership tuple. */
    private fun captureManualDrain(id: String): ManualDrainSnapshot {
        val owned = ownedActive(id)
        return ManualDrainSnapshot(preparationJobs[id], owned?.transport, owned?.lease?.attemptId, closingStops[id], reservationFor(id))
    }
    private suspend fun drainManualResources(id: String, drain: ManualDrainSnapshot, key: String, generation: Long, epoch: Int) {
        if (!currentManualAction(key, generation, epoch)) return
        stopTransferResources(id, drain)
    }
    private fun requireOwned(owned: OwnedConnection, epoch: Int) {
        ensureEpoch(epoch)
        val lease = owned.lease
        val record = records.value[lease.transferId] ?: throw DirectOperationRetired()
        if (!executionEligible(record)) throw DirectOperationRetired()
        if (activeByRole[lease.direction] !== owned || !resourceSlots.usable(lease) || lease.accountEpoch != epoch ||
            operationGeneration(lease.transferId) != lease.generation || record.attemptId != lease.attemptId ||
            retiringResources.containsKey(lease.transferId) || record.state in setOf("cancelled", "rejected", "expired", "other_device")) throw DirectOperationRetired()
        if (!record.completed && (lease.transferId in manualStopLatches || !manualByteEligible(record))) throw DirectOperationRetired()
        if (!canStream()) throw DirectStorageError("call_in_progress")
    }
    private suspend fun stopTransferConnection(id: String, expected: OwnedConnection? = null) {
        closingStops[id]?.let { it.await(); return }
        val owned = ownedActive(id) ?: return
        if (expected != null && owned !== expected) return
        val lease = owned.lease
        if (!resourceSlots.beginClosing(lease)) { closingStops[id]?.await(); return }
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val nativeOwner = ClosingNative(owned.transport, lease.attemptId, done)
        closingStops[id] = done; closingNativeOwners[id] = nativeOwner
        activeByRole.remove(lease.direction, owned)
        val caller = currentCoroutineContext()[Job]
        publishManager()
        withContext(kotlinx.coroutines.NonCancellable) {
            var failure: Throwable? = null
            try { owned.transport.close() } catch (error: Throwable) { failure = error }
            try { owned.job?.takeIf { it !== caller }?.cancelAndJoin() } catch (error: Throwable) { if (failure == null) failure = error }
            if (failure == null) {
                closingNativeOwners.remove(id, nativeOwner); closingStops.remove(id, done)
                if (!retiringResources.containsKey(id) && preparationJobs[id]?.isCompleted != false) releaseSlotAfterDrain(lease)
                done.complete(Unit)
            } else done.completeExceptionally(failure!!)
            publishManager(); failure?.let { throw it }
        }
    }
    /** Native close + the CAPTURED preparation/checkpoint drain own the role until both finish. */
    private suspend fun stopTransferResources(id: String, snapshot: ManualDrainSnapshot? = null) {
        retiringResources[id]?.let { it.await(); return }
        val drain = snapshot ?: captureManualDrain(id)
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val raced = retiringResources.putIfAbsent(id, done)
        if (raced != null) { raced.await(); return }
        val caller = currentCoroutineContext()[Job]
        drain.preparation?.takeIf { it !== caller }?.cancel()
        withContext(kotlinx.coroutines.NonCancellable) {
            try {
                val owned = ownedActive(id)
                if (drain.transport != null && owned?.transport === drain.transport && owned.lease == drain.reservation)
                    stopTransferConnection(id, owned)
                else if (drain.transport != null) closingNativeOwners[id]?.takeIf { it.transport === drain.transport && it.attempt == drain.activeAttempt }?.done?.await()
                else drain.closing?.await()
                drain.preparation?.takeIf { it !== caller }?.let { job -> job.cancelAndJoin(); preparationJobs.remove(id, job) }
                drain.reservation?.let { releaseReservation(id, expected = it, afterDrain = true) }
                retiringResources.remove(id, done); done.complete(Unit)
            } catch (error: Throwable) { done.completeExceptionally(error); throw error }
            finally { publishManager() }
        }
    }
    private suspend fun stopAllResources() = kotlinx.coroutines.supervisorScope {
        val ids = (preparationJobs.keys + resourceSlots.snapshot().map { it.transferId } + allActive().map { it.lease.transferId } + retiringResources.keys).distinct()
        val captured = ids.associateWith(::captureManualDrain)
        ids.forEach { invalidateOperation(it); preparationJobs[it]?.cancel() }
        // Each UNDIDSPATCHED stop reaches its native close before awaiting either role's result.
        val jobs = ids.map { id -> async(start = CoroutineStart.UNDISPATCHED) { runCatching { stopTransferResources(id, captured.getValue(id)) } } }
        val results = jobs.map { it.await() }
        results.firstOrNull { it.isFailure }?.exceptionOrNull()?.let { throw it }
    }
    private suspend fun stopConnection(id: String? = null) {
        if (id != null) stopTransferConnection(id)
        else kotlinx.coroutines.supervisorScope {
            val ids = (allActive().map { it.lease.transferId } + closingStops.keys).distinct()
            val jobs = ids.map { item -> async(start = CoroutineStart.UNDISPATCHED) { runCatching { stopTransferConnection(item) } } }
            val results = jobs.map { it.await() }; results.firstOrNull { it.isFailure }?.exceptionOrNull()?.let { throw it }
        }
    }
    private fun requireAttempt(id: String, attempt: String, epoch: Int) {
        val owned = ownedActive(id) ?: throw DirectOperationRetired()
        if (owned.lease.attemptId != attempt) throw DirectOperationRetired()
        requireOwned(owned, epoch)
    }
    private suspend fun setConnection(id: String, attempt: String, transport: DirectPeerTransport, epoch: Int) {
        val record = records.value[id] ?: throw DirectOperationRetired()
        val lease = reservationFor(id) ?: throw DirectOperationRetired()
        if (!manualByteEligible(record) || id in manualStopLatches || retiringResources.containsKey(id) || lease.attemptId != attempt ||
            lease.accountEpoch != epoch || lease.generation != operationGeneration(id)) { transport.close(); throw DirectOperationRetired() }
        val owned = OwnedConnection(lease, transport)
        gate.withLock {
            if (retiringResources.containsKey(id) || !resourceSlots.attach(lease)) throw DirectOperationRetired()
            activeByRole[lease.direction] = owned
        }
        if (transport is WebRtcDirectPeerTransport) transport.bindExecutionGuard { requireOwned(owned, epoch) }
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                DirectFilePump(record, storage(epoch), transport, { requireOwned(owned, epoch) },
                    { offset, stats -> withContext(dispatcher) { updateOwnedOffset(owned, offset, stats, epoch) } },
                    { withContext(dispatcher) {
                        requireOwned(owned, epoch)
                        mutate(id, epoch) { live -> if (live.state in setOf("cancelled", "rejected", "expired") || live.attemptId != attempt) live
                            else live.copy(completed = true, state = "completed", durableOffset = live.offer.size, reason = null, completionRecoveryAt = System.currentTimeMillis() + 10_000) }
                        if (records.value[id]?.completed == true && record.role == "receiver") queueControl(id, "complete", epoch, offset = record.offer.size, prefix = record.offer.sha256)
                        wakeNow(id)
                    } }, verifyFile = { file, check -> workLanes.runInboundHash { storage(epoch).hash(file, checkCurrent = check) } }).run()
            } catch (_: DirectOperationRetired) { }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (auth.currentSessionEpoch() == epoch) withContext(dispatcher) {
                    if (activeByRole[lease.direction] !== owned) return@withContext
                    withContext(kotlinx.coroutines.NonCancellable) { transport.close() }
                    if (activeByRole[lease.direction] !== owned) return@withContext
                    activeByRole.remove(lease.direction, owned); resourceSlots.beginClosing(lease)
                    if (!retiringResources.containsKey(id) && preparationJobs[id]?.isCompleted != false) releaseSlotAfterDrain(lease)
                    mutate(id, epoch) { live -> if (live.terminal || live.attemptId != attempt) live else live.copy(state = "paused",
                        reason = if (DirectManualRuntime.safetyBlocked(live)) live.reason else errorReason(error), requestId = null,
                        pausedForLocalCall = errorReason(error) == "call_in_progress", connectionFailures = (live.connectionFailures + 1).coerceAtMost(5)) }
                    publishManager(); wakeRoleQueue(lease.direction); notifyPause(id, errorReason(error), epoch)
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    var closed = true
                    try { transport.close() } catch (_: Exception) { closed = false }
                    withContext(dispatcher) {
                        if (activeByRole.remove(lease.direction, owned)) {
                            resourceSlots.beginClosing(lease)
                            if (closed && !retiringResources.containsKey(id) && preparationJobs[id]?.isCompleted != false) releaseSlotAfterDrain(lease)
                            else if (!closed && !closingStops.containsKey(id)) {
                                val failed = kotlinx.coroutines.CompletableDeferred<Unit>()
                                failed.completeExceptionally(java.io.IOException("Native transfer close failed")); closingStops[id] = failed
                                _errors.tryEmit("connection_lost")
                            }
                        }
                        publishManager(); wakeNow(id)
                    }
                }
            }
        }
        owned.job = job
        publishManager()
        try { requireOwned(owned, epoch); job.start() }
        catch (error: Exception) { job.cancel(); stopTransferConnection(id, owned); throw error }
    }
    private suspend fun updateOwnedOffset(owned: OwnedConnection, offset: Long, stats: DirectAckStats?, epoch: Int) {
        requireOwned(owned, epoch)
        val id = owned.lease.transferId
        latestOffsets[id] = offset
        val now = System.currentTimeMillis()
        if (stats != null || records.value[id]?.role == "receiver" || offset == records.value[id]?.offer?.size || now - (offsetAt[id] ?: 0L) >= 500) {
            mutate(id, epoch) { live -> if (live.attemptId != owned.lease.attemptId || live.terminal || !resourceSlots.owns(owned.lease)) live
                else live.acknowledgeProgress(offset, stats).let { next ->
                    if (id in manualStopLatches || !DirectManualRuntime.state(live).manualByteEligible || DirectManualRuntime.safetyBlocked(live)) next.copy(state = live.state, reason = live.reason, speedBytesPerSecond = 0) else next
                } }
            offsetAt[id] = now
        }
    }

    fun completedFile(id: String): Pair<java.io.File, DirectFileOffer>? {
        val record = records.value[id] ?: return null
        if (!record.completed || record.role != "receiver" || loadedEpoch != auth.currentSessionEpoch()) return null
        val file = accountStorage?.file(id, "complete")?.takeIf { it.isFile && it.length() == record.offer.size } ?: return null
        return file to record.offer
    }

    private fun action(id: String, work: suspend (Int) -> Unit) {
        if (!canonicalUuid(id)) return
        val epoch = auth.currentSessionEpoch()
        scope.launch(DirectHttpContext(DirectHttpPriority.CONTROL)) {
            try { awaitLoaded(epoch); work(epoch) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (auth.currentSessionEpoch() == epoch) {
                    val reason = if (error is DirectTransferApiError && error.status == 409) "other_device" else errorReason(error)
                    mutate(id, epoch) {
                        if (it.terminal) it
                        else if (it.role == "receiver" && !it.consent && it.state == "offered" && reason in setOf("too_many", "disk_full")) it.copy(reason = reason)
                        else it.copy(state = if (reason == "other_device") "other_device" else "paused", reason = reason)
                    }
                    _errors.tryEmit(reason)
                }
            }
        }
    }

    private suspend fun tick(id: String, epoch: Int) {
        var record = records.value[id] ?: return
        if (record.registered && !record.terminal) {
            // Deleting the offer or its chat must not leave an invisible live file
            // connection. Chat tombstones also purge this message row atomically.
            val offerMessage = messages.findByClientMessageId(record.offer.offerClientMessageId)
            if (offerMessage == null || offerMessage.hiddenLocally || offerMessage.isDeletedForEveryone) {
                stopTransferResources(id)
                mutate(id, epoch) { it.copy(state = "cancelled", closePending = true, reason = "user_cancelled") }
                storage(epoch).deleteIncomplete(id)
                record = records.value[id] ?: return
            }
        }
        if (!record.registered && record.role == "sender") {
            if (record.terminal) return
            val message = messages.findByClientMessageId(record.offer.offerClientMessageId) ?: run {
                if (!record.terminal && storage(epoch).file(id, "source").isFile) enqueueOffer(record.chatId, record.offer, epoch)
                return
            }
            if (message.hiddenLocally || message.isDeletedForEveryone) {
                mutate(id, epoch) { it.copy(state = "cancelled", closePending = false, reason = "user_cancelled") }
                storage(epoch).deleteIncomplete(id)
                return
            }
            if (message.serverId == null) return
            require(message.chatId == record.chatId && message.senderId == record.offer.senderUserId)
            val metadata = api.register(record, epoch)
            checkMetadata(record, metadata, epoch)
            mutate(id, epoch) { it.copy(registered = true) }
        }
        record = records.value[id] ?: return
        if (record.role == "receiver" && !record.registered) {
            val metadata = api.get(id, epoch)
            checkMetadata(record, metadata, epoch)
            if (metadata.status == "closed") {
                mutate(id, epoch) { if (it.terminal) it.copy(mailboxClosed = true, closePending = false)
                    else it.copy(state = "paused", reason = "transfer_unavailable", mailboxClosed = true, closePending = false) }
                return
            }
            if (metadata.recipientDeviceId != null && metadata.recipientDeviceId != auth.currentDeviceId()) {
                mutate(id, epoch) { it.copy(state = "other_device", mailboxClosed = true) }; return
            }
            if (metadata.recipientDeviceId == null) {
                if (!record.consent && record.state !in setOf("cancelled", "rejected")) return
                // This only follows a durable manual accept/decline/cancel, never a card view.
                if (record.state !in setOf("cancelled", "rejected") && !manualRequestEligible(records.value[id] ?: return)) return
                if (record.consent) mutate(id, epoch) { live ->
                    if (!live.registered && live.cursor == 0L && live.pending.isEmpty() && live.sentControlKeys.isEmpty() && live.signalBudget?.identities?.isNotEmpty() != true)
                        live.copy(signalBudget = DirectSignalBudget.fresh()) else live
                }
                val cleanupState = record.state.takeIf { it in setOf("cancelled", "rejected") }
                val claimGeneration = operationGeneration(id)
                val claimed = api.claim(id, epoch) {
                    if (cleanupState == null) requireReadyClaim(id, claimGeneration, epoch)
                    else {
                        // Explicit terminal-only cleanup grants neither consent nor READY/RTC authority.
                        ensureEpoch(epoch)
                        val current = records.value[id] ?: throw DirectOperationRetired()
                        if (current.completed || current.state != cleanupState) throw DirectOperationRetired()
                    }
                }
                checkMetadata(record, claimed, epoch)
                require(claimed.recipientDeviceId == auth.currentDeviceId()) { "Transfer claim did not select this device" }
            }
            mutate(id, epoch) { it.copy(registered = true) }
            record = records.value[id] ?: return
        }
        val page = api.signals(id, record.cursor, epoch)
        checkMetadata(record, page.transfer, epoch)
        if (record.role == "receiver" && page.transfer.recipientDeviceId != null && page.transfer.recipientDeviceId != auth.currentDeviceId()) {
            mutate(id, epoch) { it.copy(state = "other_device", pending = emptyList(), mailboxClosed = true) }; return
        }
        mutate(id, epoch) { it.copy(registered = true, peerDeviceId = if (it.role == "sender") it.peerDeviceId else it.offer.senderDeviceId) }
        for (envelope in page.signals) processSignal(id, envelope, page.transfer, epoch)
        record = records.value[id] ?: return
        if (page.hasMore) { wakeNow(id); return }
        if (page.transfer.status == "closed") {
            stopTransferResources(id)
            mutate(id, epoch) { if (it.completed) it.copy(mailboxClosed = true, closePending = false, pending = emptyList())
                else if (it.state in setOf("cancelled", "rejected")) it.copy(mailboxClosed = true, closePending = false, pending = emptyList())
                else it.copy(state = "paused", reason = "transfer_unavailable", mailboxClosed = true, closePending = false, pending = emptyList()) }
            return
        }
        if (record.state in setOf("cancelled", "rejected")) {
            if (record.role == "receiver" && page.transfer.recipientDeviceId == null) {
                val cleanupState = record.state
                val claimed = api.claim(id, epoch) {
                    ensureEpoch(epoch)
                    val current = records.value[id] ?: throw DirectOperationRetired()
                    if (current.completed || current.state != cleanupState) throw DirectOperationRetired()
                }
                checkMetadata(record, claimed, epoch)
            }
            if (record.peerDeviceId != null && record.pending.none { it.control.type in setOf("cancel", "reject") })
                queueControl(id, if (record.state == "rejected") "reject" else "cancel", epoch)
        }
        flushPending(id, epoch)
        record = records.value[id] ?: return
        if (record.closePending && record.pending.isEmpty()) {
            api.close(id, epoch); mutate(id, epoch) { it.copy(closePending = false, mailboxClosed = true) }; return
        }
        // A crash after verified promote but before completion signaling is repaired without receiving a second file.
        if (record.completed && !record.completionAcknowledged && record.role == "receiver" && record.pending.none { it.control.type == "complete" }) {
            queueControl(id, "complete", epoch, offset = record.offer.size, prefix = record.offer.sha256)
            flushPending(id, epoch)
        }
        if (record.completed && record.role == "sender" && record.completionReceived) {
            queueControl(id, "complete_ack", epoch, offset = record.offer.size, prefix = record.offer.sha256)
            mutate(id, epoch) { it.copy(closePending = true) }
            flushPending(id, epoch)
        }
        record.completionProbe(System.currentTimeMillis())?.let { probe ->
            queueControl(id, "pause", epoch, reason = "reconnect_required", attemptOverride = probe.attempt, responseTo = probe.trigger)
            mutate(id, epoch) { if (!it.completionReceived) it.copy(completionRecoveryCount = probe.nextCount, completionRecoveryAt = probe.nextAt) else it }
            flushPending(id, epoch)
        }
        drainManualState(id, epoch)
        drainCapacityHint(id, epoch)
        record = records.value[id] ?: return
        if (record.pausedForLocalCall && !record.terminal && !DirectManualRuntime.safetyBlocked(record) && canStream() && slotAvailable(record)) {
            mutate(id, epoch) { it.copy(pausedForLocalCall = false, state = "paused", reason = "reconnect_required", requestId = null,
                connectionFailures = 0, nextAttemptAt = 0, requestRetryCount = 0) }
            record = records.value[id] ?: return
        }
        if (record.role == "receiver" && record.consent && !record.terminal && record.requestId != null && ownedActive(id) == null &&
            record.requestCreatedAt > 0 && record.requestRetryCount in 1..DirectTransferTiming.MAX_REQUESTS) {
            val acknowledged = record.requestAcknowledgedAt > 0
            val baseline = if (acknowledged) record.requestAcknowledgedAt else record.requestCreatedAt
            if (System.currentTimeMillis() - baseline >= DirectTransferTiming.requestMillis(record.offer.size, record.requestRetryCount, acknowledged)) {
                val expiredRequest = requireNotNull(record.requestId)
                val expiredLease = reservationFor(id)?.takeIf { it.attemptId == expiredRequest }
                mutate(id, epoch) { if (it.requestId == expiredRequest) it.copy(requestId = null, state = "paused", reason = "connection_lost") else it }
                expiredLease?.let { releaseReservation(id, expiredRequest, expected = it) }
                record = records.value[id] ?: return
            }
        }
        if (record.role == "receiver" && record.consent && record.state in setOf("accepted", "paused") &&
            record.reason !in setOf("prefix_mismatch", "integrity_failed", "disk_full", "source_missing", "security_error", "unsupported", "call_in_progress") &&
            record.requestId == null && manualRequestEligible(record) && canStream() && slotAvailable(record) && record.connectionFailures < 5 && record.requestRetryCount < DirectTransferTiming.MAX_REQUESTS && System.currentTimeMillis() >= record.nextAttemptAt) requestResume(id, epoch)
        if (record.role == "sender" && manualByteEligible(record) && record.consent && (record.reason == "reconnect_required" || record.reason == "call_in_progress" && canStream()) && record.peerDeviceId != null && !record.terminal) {
            queueControl(id, "pause", epoch, reason = "reconnect_required")
            mutate(id, epoch) { it.copy(state = "waiting", reason = "network_waiting") }
        }
        val waiting = records.value[id]
        if (waiting != null && waiting.role == "sender" && !waiting.terminal && waiting.state in setOf("accepted", "paused") &&
            waiting.requestId != null && waiting.acceptedRequest?.attemptId == waiting.requestId && manualPacketMayArm(waiting, requireNotNull(waiting.acceptedRequest)) && slotAvailable(waiting) && canStream()) {
            beginSender(id, requireNotNull(waiting.acceptedRequest), epoch)
        }
    }

    private suspend fun processSignal(id: String, envelope: DirectSignalEnvelope, metadata: DirectTransferMetadata, epoch: Int) {
        var record = records.value[id] ?: return
        if (envelope.sequence <= record.cursor) return
        require(envelope.sequence > 0 && canonicalUuid(envelope.clientSignalId) && envelope.recipientDeviceId == auth.currentDeviceId())
        val peerUser = if (record.role == "sender") record.offer.recipientUserId else record.offer.senderUserId
        val peerDevice = if (record.role == "sender") record.peerDeviceId ?: metadata.recipientDeviceId else record.offer.senderDeviceId
        require(peerDevice != null && canonicalUuid(envelope.senderDeviceId) && envelope.senderUserId == peerUser && envelope.senderDeviceId == peerDevice)
        require(envelope.messageType in 1..2 && java.util.Base64.getDecoder().decode(envelope.ciphertext).size <= 65536)
        val recoveredJournal = record.processingSignal?.clientSignalId == envelope.clientSignalId
        // Even invalid encrypted controls consumed mailbox capacity. Sequence is ONLY a cursor.
        val debit = DirectSignalIdentity(envelope.senderDeviceId, envelope.clientSignalId)
        mutate(id, epoch) { it.copy(processingSignal = envelope, signalBudget = (it.signalBudget ?: DirectSignalBudget.unknown()).recording(debit)) }
        val plaintext = try {
            val token = auth.currentAccessToken() ?: throw CancellationException()
            ensureEpoch(epoch)
            signal.withSendingAccount(epoch) {
                signal.decryptFromDevice(peerUser, peerDevice, token, envelope.messageType, envelope.ciphertext)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: com.messenger.app.data.signal.IdentityChangedException) {
            mutate(id, epoch) { it.copy(cursor = maxOf(it.cursor, envelope.sequence), processingSignal = null,
                state = if (it.terminal) it.state else "paused", reason = "security_error", requestId = null) }
            invalidateOperation(id); stopTransferResources(id); return
        } catch (_: Exception) {
            mutate(id, epoch) { live ->
                val manual = DirectManualRuntime.state(live)
                val repairable = recoveredJournal && !live.terminal && DirectManualRuntime.peerProofObserved(live)
                live.copy(cursor = maxOf(live.cursor, envelope.sequence), processingSignal = null,
                    manualPause = if (repairable) manual.copy(syncNeeded = true) else live.manualPause)
            }
            return
        }
        val control = try {
            require(plaintext.size <= 48 * 1024 && com.messenger.app.data.message.MessageTextPolicy.allowsPlaintext(plaintext))
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plaintext)).toString()
            DirectFileOffer.codec.decodeFromString(DirectFileControl.serializer(), text).also { validateControl(records.value[id] ?: return, it, envelope, epoch) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { advance(id, envelope.sequence, epoch); return }
        record = records.value[id] ?: return
        validateControl(record, control, envelope, epoch)
        val snapshot = control.validatedManualSnapshot()
        var peerChanged = false
        mutate(id, epoch) { live ->
            var next = live
            if (!live.terminal && live.role == "receiver") next = next.copy(queue = (live.queue ?: newQueueState(live.role)).copy(identityProofObserved = true))
            if (snapshot != null && !live.terminal) {
                val reduction = DirectManualRuntime.state(next).applying(snapshot, control.requestAck == true)
                peerChanged = reduction.peerChanged
                val manual = reduction.state.copy(pendingResponseTo = if (reduction.needsResponse) control.clientSignalId else reduction.state.pendingResponseTo,
                    syncNeeded = reduction.state.acknowledgedLocalRevision < reduction.state.localRevision)
                next = next.copy(manualPause = manual)
                if (peerChanged) next = DirectManualRuntime.retiringNegotiation(next)
            }
            next
        }
        if (peerChanged) {
            manualStopLatches.add(id); invalidateOperation(id)
            preparationJobs[id]?.cancel(); stopTransferResources(id)
        }
        record = records.value[id] ?: return
        when (control.type) {
            "pause_state" -> { advance(id, envelope.sequence, epoch) }
            "cancel", "reject" -> {
                invalidateOperation(id); preparationJobs[id]?.cancel()
                mutate(id, epoch) { if (it.completed) it.copy(cursor = maxOf(it.cursor, envelope.sequence), processingSignal = null)
                    else it.copy(cursor = maxOf(it.cursor, envelope.sequence), processingSignal = null, state = if (control.type == "reject") "rejected" else "cancelled", pending = emptyList(), closePending = true) }
                stopTransferResources(id)
                if (records.value[id]?.completed == false) storage(epoch).deleteIncomplete(id)
            }
            "accept", "resume" -> {
                if (record.terminal || record.role != "sender" || record.lastRequestAttempt == control.attemptId ||
                    control.attemptId in record.usedAttempts && control.attemptId != record.requestId) { advance(id, envelope.sequence, epoch); return }
                // Only this exact nonce-authenticated request establishes the sender's peer proof.
                mutate(id, epoch) { live ->
                    if (live.terminal) return@mutate live.copy(cursor = maxOf(live.cursor, envelope.sequence), processingSignal = null)
                    val proven = live.copy(consent = true, peerDeviceId = envelope.senderDeviceId)
                    val arm = manualPacketMayArm(proven, control)
                    proven.copy(cursor = maxOf(proven.cursor, envelope.sequence), processingSignal = null,
                        requestId = if (arm) control.attemptId else null, acceptedRequest = if (arm) control else null,
                        lastRequestAttempt = control.attemptId, state = if (arm) "accepted" else "paused", reason = if (DirectManualRuntime.safetyBlocked(proven)) proven.reason else null,
                        usedAttempts = (proven.usedAttempts + control.attemptId).distinct().takeLast(256),
                        queue = proven.queue?.copy(capacityHintCount = 0, nextCapacityHintAt = 0))
                }
                // Hold/snapshot ACK is ordered before any SDP/source effect; never to outer claim alone.
                drainManualState(id, epoch)
                val fresh = records.value[id] ?: return
                if (manualPacketMayArm(fresh, control) && fresh.requestId == control.attemptId) {
                    queueControl(id, "pause", epoch, reason = "busy", attemptOverride = control.attemptId)
                    flushPending(id, epoch)
                    beginSender(id, control, epoch)
                }
            }
            "offer" -> {
                if (record.terminal || record.role != "receiver" || record.requestId != control.attemptId || !manualPacketMayArm(record, control)) { advance(id, envelope.sequence, epoch); return }
                mutate(id, epoch) { it.copy(cursor = maxOf(it.cursor, envelope.sequence), processingSignal = null, state = "connecting", attemptId = control.attemptId, reason = null,
                    requestAcknowledgedAt = if (it.requestAcknowledgedAt > 0) it.requestAcknowledgedAt else System.currentTimeMillis()) }
                beginReceiver(id, control, epoch)
            }
            "answer" -> {
                advance(id, envelope.sequence, epoch)
                if (manualPacketMayArm(record, control) && record.role == "sender" && record.attemptId == control.attemptId && ownedActive(id)?.lease?.attemptId == control.attemptId)
                    ownedActive(id)?.let { owned -> requireOwned(owned, epoch); owned.transport.applyAnswer(requireNotNull(control.sdp)) }
            }
            "pause" -> {
                advance(id, envelope.sequence, epoch)
                if (record.completed && record.role == "receiver" && record.attemptId == control.attemptId && control.reason == "reconnect_required") {
                    val complete = storage(epoch).file(id, "complete")
                    if (complete.isFile && complete.length() == record.offer.size && withContext(Dispatchers.IO) { storage(epoch).hash(complete) } == record.offer.sha256)
                        queueControl(id, "complete", epoch, offset = record.offer.size, prefix = record.offer.sha256, responseTo = control.clientSignalId)
                    return
                }
                val currentAttempt = if (reservationFor(id) != null) reservationFor(id)?.attemptId == control.attemptId else record.attemptId == control.attemptId || record.requestId == control.attemptId
                if (!record.terminal && record.role == "receiver" && record.requestId == control.attemptId && ownedActive(id) == null && control.reason == "busy") {
                    mutate(id, epoch) { it.copy(state = if (DirectManualRuntime.state(it).localPaused || DirectManualRuntime.state(it).peerPaused) "paused" else "accepted", reason = if (DirectManualRuntime.safetyBlocked(it)) it.reason else "busy",
                        requestAcknowledgedAt = if (it.requestAcknowledgedAt > 0) it.requestAcknowledgedAt else System.currentTimeMillis()) }
                    return
                }
                if (!record.terminal && record.role == "receiver" && record.requestId == control.attemptId && control.reason == "queued") {
                    invalidateOperation(id); stopTransferResources(id)
                    mutate(id, epoch) {
                        val queue = it.queue ?: newQueueState(it.role)
                        it.copy(state = "paused", reason = if (DirectManualRuntime.safetyBlocked(it)) it.reason else "queued", requestId = null, acceptedRequest = null,
                            queue = queue.copy(remoteCapacityBlocked = true, nextCapacityHintAt = maxOf(queue.nextCapacityHintAt, capacityCooldownUntil(System.currentTimeMillis()))))
                    }
                    return
                }
                val reconnectHint = mayObserveReconnectHint(record, control)
                if (!record.terminal && (currentAttempt || reconnectHint)) {
                    invalidateOperation(id); stopTransferResources(id)
                    mutate(id, epoch) { retiringAfterPeerPause(it, control, reconnectHint) }
                }
            }
            "complete" -> {
                if (record.role != "sender" || record.attemptId != control.attemptId || record.state in setOf("cancelled", "rejected", "expired")) { advance(id, envelope.sequence, epoch); return }
                mutate(id, epoch) { it.copy(cursor = maxOf(it.cursor, envelope.sequence), processingSignal = null, completed = true, state = "completed", durableOffset = it.offer.size, completionReceived = true) }
                if (records.value[id]?.let { it.completed && it.completionReceived } != true) return
                stopTransferResources(id)
                queueControl(id, "complete_ack", epoch, offset = record.offer.size, prefix = record.offer.sha256)
                mutate(id, epoch) { it.copy(closePending = true) }; storage(epoch).deleteSource(id)
            }
            "complete_ack" -> {
                if (record.completed && record.role == "receiver" && record.attemptId == control.attemptId) {
                    mutate(id, epoch) { it.copy(cursor = maxOf(it.cursor, envelope.sequence), processingSignal = null, completionAcknowledged = true, closePending = true) }
                    stopTransferResources(id)
                } else advance(id, envelope.sequence, epoch)
            }
        }
    }

    private suspend fun beginSender(id: String, control: DirectFileControl, epoch: Int) {
        val initial = records.value[id] ?: return
        if (!manualPacketMayArm(initial, control) || initial.requestId != control.attemptId) return
        if (!reserveSlot(id, control.attemptId)) {
            queueControl(id, "pause", epoch, reason = "queued", attemptOverride = control.attemptId)
            mutate(id, epoch) { if (it.requestId == control.attemptId) it.copy(requestId = null, acceptedRequest = null, state = "paused", reason = "queued") else it }
            wakeNow(id); return
        }
        val lease = reservationFor(id)?.takeIf { it.attemptId == control.attemptId } ?: return
        if (manualWriteStopped(id)) { releaseReservation(id, expected = lease); return }
        manualStopLatches.remove(id)
        val generation = lease.generation
        val ticket = "$id:${control.attemptId}:sender"
        if (!negotiating.add(ticket)) return
        val job = scope.launch(start = CoroutineStart.LAZY) { try { negotiationLocks.getOrPut(id) { Mutex() }.withLock {
            try {
                val record = records.value[id] ?: return@withLock
                requireOperation(id, generation, epoch, expected = lease)
                if (record.requestId != control.attemptId || !manualPacketMayArm(record, control)) return@withLock
                if (ownedActive(id)?.lease?.attemptId == control.attemptId) return@withLock
                if (!canStream()) throw DirectStorageError("call_in_progress")
                stopConnection(id)
                if (!reserveSlot(id, control.attemptId)) throw DirectOperationRetired()
                val storage = storage(epoch)
                val source = storage.file(id, "source")
                if (!source.isFile || source.length() != record.offer.size) throw DirectStorageError("source_missing")
                val stillRequested = {
                    requireOperation(id, generation, epoch, expected = lease)
                    if (records.value[id]?.requestId != control.attemptId || !resourceSlots.usable(lease)) throw DirectOperationRetired()
                    if (!canStream()) throw DirectStorageError("call_in_progress")
                }
                if (workLanes.runOutboundHash { storage.hash(source, checkCurrent = stillRequested) } != record.offer.sha256) throw DirectStorageError("source_changed")
                val offset = requireNotNull(control.offset)
                if (workLanes.runOutboundHash { storage.hash(source, offset, stillRequested) } != control.prefixSha256) throw DirectStorageError("prefix_mismatch")
                stillRequested()
                val urls = stunUrls().filter(DirectPeerPolicy::isStunUrl).distinct().take(8)
                stillRequested()
                mutate(id, epoch) { live ->
                    if (live.requestId != control.attemptId || !manualPacketMayArm(live, control)) throw DirectOperationRetired()
                    live.copy(attemptId = control.attemptId, usedAttempts = (live.usedAttempts + control.attemptId).distinct().takeLast(256),
                        state = "connecting", durableOffset = offset, reason = null, ackSampleId = 0, speedBytesPerSecond = 0)
                }
                // Persist negotiation ownership before constructing even the transport's coroutine scope.
                // A journal failure leaves no unattached transport for the preparation job to leak.
                stillRequested()
                val transport = WebRtcDirectPeerTransport(context, urls)
                try {
                    stillRequested(); setConnection(id, control.attemptId, transport, epoch)
                    val sdp = transport.createOffer()
                    requireAttempt(id, control.attemptId, epoch)
                    val frozen = queueControl(id, "offer", epoch, sdp = sdp)
                    if (frozen == DirectControlFreezeResult.BLOCKED) { stopForControlBudget(id, control.attemptId, generation, epoch); return@withLock }
                    if (frozen !in setOf(DirectControlFreezeResult.FROZEN, DirectControlFreezeResult.DUPLICATE)) throw DirectOperationRetired()
                    wakeNow(id)
                } catch (error: Exception) { withContext(kotlinx.coroutines.NonCancellable) { transport.close() }; throw error }
            } catch (_: DirectOperationRetired) { }
            catch (_: TimeoutCancellationException) { pauseOwnedOperation(id, control.attemptId, generation, "direct_unavailable", epoch) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { pauseOwnedOperation(id, control.attemptId, generation, errorReason(error), epoch) }
        } } finally {
            negotiating.remove(ticket)
            preparationJobs.remove(id, currentCoroutineContext()[Job])
            if (ownedActive(id) == null && !closingStops.containsKey(id)) releaseReservation(id, control.attemptId, expected = lease)
        } }
        val previous = preparationJobs.putIfAbsent(id, job)
        job.invokeOnCompletion { preparationJobs.remove(id, job); negotiating.remove(ticket) }
        if (previous == null) job.start() else { job.cancel(); negotiating.remove(ticket) }
    }

    private suspend fun beginReceiver(id: String, control: DirectFileControl, epoch: Int) {
        val initial = records.value[id] ?: return
        if (!manualPacketMayArm(initial, control) || initial.requestId != control.attemptId) return
        if (!reserveSlot(id, control.attemptId)) return
        val lease = reservationFor(id)?.takeIf { it.attemptId == control.attemptId } ?: return
        if (manualWriteStopped(id)) { releaseReservation(id, expected = lease); return }
        manualStopLatches.remove(id)
        val generation = lease.generation
        val job = scope.launch(start = CoroutineStart.LAZY) { try { negotiationLocks.getOrPut(id) { Mutex() }.withLock {
            try {
                requireOperation(id, generation, epoch, expected = lease)
                val record = records.value[id] ?: return@withLock
                if (record.requestId != control.attemptId || !manualPacketMayArm(record, control) || !canStream()) return@withLock
                if (ownedActive(id)?.lease?.attemptId == control.attemptId) return@withLock
                stopConnection(id)
                if (!reserveSlot(id, control.attemptId)) throw DirectOperationRetired()
                val urls = stunUrls().filter(DirectPeerPolicy::isStunUrl).distinct().take(8)
                requireOperation(id, generation, epoch, expected = lease)
                val transport = WebRtcDirectPeerTransport(context, urls)
                try {
                    setConnection(id, control.attemptId, transport, epoch)
                    val answer = transport.acceptOffer(requireNotNull(control.sdp))
                    requireAttempt(id, control.attemptId, epoch)
                    val frozen = queueControl(id, "answer", epoch, sdp = answer)
                    if (frozen == DirectControlFreezeResult.BLOCKED) { stopForControlBudget(id, control.attemptId, generation, epoch); return@withLock }
                    if (frozen !in setOf(DirectControlFreezeResult.FROZEN, DirectControlFreezeResult.DUPLICATE)) throw DirectOperationRetired()
                    wakeNow(id)
                } catch (error: Exception) { withContext(kotlinx.coroutines.NonCancellable) { transport.close() }; throw error }
            } catch (_: DirectOperationRetired) { }
            catch (_: TimeoutCancellationException) { pauseOwnedOperation(id, control.attemptId, generation, "direct_unavailable", epoch) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { pauseOwnedOperation(id, control.attemptId, generation, errorReason(error), epoch) }
        } } finally {
            preparationJobs.remove(id, currentCoroutineContext()[Job])
            if (ownedActive(id) == null && !closingStops.containsKey(id)) releaseReservation(id, control.attemptId, expected = lease)
        } }
        val previous = preparationJobs.putIfAbsent(id, job)
        job.invokeOnCompletion { preparationJobs.remove(id, job) }
        if (previous == null) job.start() else job.cancel()
    }



    private suspend fun requestResume(id: String, epoch: Int, first: Boolean = false) {
        val record = records.value[id] ?: return
        if (!manualRequestEligible(record) || record.queue?.remoteCapacityBlocked == true || ownedActive(id) != null || preparationJobs.containsKey(id) ||
            record.requestRetryCount >= DirectTransferTiming.MAX_REQUESTS || record.requestId != null) return
        if (!canStream()) { mutate(id, epoch) { it.copy(state = "paused", reason = "call_in_progress", requestId = null, pausedForLocalCall = true) }; return }
        val attempt = UUID.randomUUID().toString()
        if (!reserveSlot(id, attempt, replaceOwned = false)) return
        val lease = reservationFor(id)?.takeIf { it.attemptId == attempt } ?: return
        val generation = lease.generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
        var retainedRequest = false
        try {
            val storage = storage(epoch)
            val stillCurrent = { requireOperation(id, generation, epoch, allowBootstrap = true, expected = lease) }
            val complete = storage.file(id, "complete")
            if (complete.isFile && complete.length() == record.offer.size && workLanes.runInboundHash { storage.hash(complete, checkCurrent = stillCurrent) } == record.offer.sha256) {
                mutate(id, epoch) { it.copy(completed = true, state = "completed", durableOffset = it.offer.size) }
                queueControl(id, "complete", epoch, offset = record.offer.size, prefix = record.offer.sha256); return@launch
            }
            val partial = storage.file(id, "partial")
            if (record.durableOffset > (partial.takeIf { it.isFile }?.length() ?: 0L)) throw DirectStorageError("prefix_mismatch")
            val offset = record.durableOffset
            val prefix = if (offset == 0L) DirectFileStorage.digest(byteArrayOf()) else workLanes.runInboundHash { storage.hash(partial, offset, stillCurrent) }
            stillCurrent(); storage.requireSpace(record.offer.size - offset)
            mutate(id, epoch) { live ->
                if (live.durableOffset != offset || !manualRequestEligible(live) || operationGeneration(id) != generation) throw DirectOperationRetired()
                live.copy(requestId = attempt, attemptId = attempt, state = "accepted", usedAttempts = (live.usedAttempts + attempt).distinct().takeLast(256),
                    reason = null, ackSampleId = 0, speedBytesPerSecond = 0, requestCreatedAt = System.currentTimeMillis(), requestAcknowledgedAt = 0,
                    requestRetryCount = live.requestRetryCount + 1, pausedForLocalCall = false)
            }
            val frozen = queueControl(id, if (first) "accept" else "resume", epoch, offset = offset, prefix = prefix)
            if (frozen !in setOf(DirectControlFreezeResult.FROZEN, DirectControlFreezeResult.DUPLICATE)) {
                // No HTTP-ready body exists. Do not strand the only IN role waiting for an unsent request.
                val blocked = frozen == DirectControlFreezeResult.BLOCKED
                if (blocked) { stopForControlBudget(id, attempt, generation, epoch); return@launch }
                mutate(id, epoch) { live ->
                    if (live.terminal || live.requestId != attempt || operationGeneration(id) != generation) live
                    else live.copy(requestId = null, acceptedRequest = null, state = "paused",
                        reason = if (DirectManualRuntime.safetyBlocked(live)) live.reason else "reconnect_required")
                }
                return@launch
            }
            retainedRequest = true
            wakeNow(id)
        } catch (_: DirectOperationRetired) { }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { pauseOwnedOperation(id, attempt, generation, errorReason(error), epoch) }
        finally {
            preparationJobs.remove(id, currentCoroutineContext()[Job])
            if (!retainedRequest && !closingStops.containsKey(id)) releaseReservation(id, attempt, expected = lease)
        }
        }
        val previous = preparationJobs.putIfAbsent(id, job)
        job.invokeOnCompletion { preparationJobs.remove(id, job) }
        if (previous == null) job.start() else { job.cancel(); releaseReservation(id, attempt, expected = lease) }
    }

    private fun validateControl(record: DirectTransferRecord, value: DirectFileControl, envelope: DirectSignalEnvelope, epoch: Int) {
        ensureEpoch(epoch)
        DirectControlPolicy.validate(record, value, envelope, currentUser(), auth.currentDeviceId())
    }

    private fun checkMetadata(record: DirectTransferRecord, value: DirectTransferMetadata, epoch: Int) {
        ensureEpoch(epoch)
        require(value.id == record.offer.transferId && value.chatId == record.chatId && value.offerClientMessageId == record.offer.offerClientMessageId &&
            value.senderUserId == record.offer.senderUserId && value.senderDeviceId == record.offer.senderDeviceId && value.recipientUserId == record.offer.recipientUserId &&
            value.expiresAt == record.offer.expiresAt && value.status in setOf("active", "closed") && (value.recipientDeviceId == null || canonicalUuid(value.recipientDeviceId)))
        if (record.role == "sender" && record.peerDeviceId != null) require(value.recipientDeviceId == record.peerDeviceId) { "Transfer device changed" }
        if (record.role == "receiver" && record.registered && record.consent) require(value.recipientDeviceId == auth.currentDeviceId()) { "Transfer claim changed" }
    }

    private suspend fun pause(id: String, reason: String, epoch: Int) {
        stopTransferResources(id)
        mutate(id, epoch) { if (it.terminal) it else it.copy(state = "paused", reason = reason, requestId = null, pausedForLocalCall = reason == "call_in_progress",
            connectionFailures = (it.connectionFailures + 1).coerceAtMost(5), nextAttemptAt = System.currentTimeMillis() + minOf(300_000L, 2000L shl (it.connectionFailures + 1).coerceAtMost(7))) }
        notifyPause(id, reason, epoch)
        wakeNow(id)
    }
    private suspend fun notifyPause(id: String, reason: String, epoch: Int, attempt: String? = null) {
        notifyDirectPauseBestEffort({ queueControl(id, "pause", epoch, reason = reason, attemptOverride = attempt) }) {
            // No fallback encryption or new peer lookup: retain the safe paused state.
            if (auth.currentSessionEpoch() == epoch) _errors.tryEmit(errorReason(it))
        }
    }



    private suspend fun advance(id: String, cursor: Long, epoch: Int) { mutate(id, epoch) { it.copy(cursor = cursor, processingSignal = null) } }
    private suspend fun awaitLoaded(epoch: Int) {
        withTimeoutOrNull(15_000) { while (loadedEpoch != epoch) { ensureEpoch(epoch); delay(10) } } ?: throw CancellationException("Transfer account is not ready")
    }
    private suspend fun requireService(epoch: Int) { if (serviceEpoch != epoch) { api.requireSupported(epoch); ensureEpoch(epoch); serviceEpoch = epoch } }

    private fun newQueueState(role: String): DirectFileQueueState {
        val ordinal = records.value.values.filter { it.role == role }.mapNotNull { it.queue?.ordinal }.maxOrNull() ?: 0L
        require(ordinal < Long.MAX_VALUE)
        return DirectFileQueueState(ordinal + 1, 0)
    }
    private suspend fun stopAllForAccountChange() {
        stopAllResources()
        val previousEpoch = loadedEpoch
        if (previousEpoch >= 0) stopAllSourcePreparations(previousEpoch)
        val currentEpoch = auth.currentSessionEpoch()
        val sourceBatches = synchronized(stagingExecutions) {
            stagingExecutions.values.filter { it.epoch != currentEpoch }.mapNotNull { owner -> owner.job?.let { owner to it } }
        }
        sourceBatches.forEach { (_, job) -> job.cancel() }
        sourceBatches.forEach { (_, job) -> if (job !== currentCoroutineContext()[Job]) job.cancelAndJoin() }
        sourceBatches.forEach { (owner, job) -> releaseStagingExecution(owner, job) }
        preparationJobs.clear(); manualStopLatches.clear(); executionWriteStops.clear(); operationGenerations.clear(); controlLeases.clear()
    }
    private suspend fun stopForLocalCall(epoch: Int) {
        val ids = (preparationJobs.keys + resourceSlots.snapshot().map { it.transferId } + allActive().map { it.lease.transferId }).distinct()
        stopAllResources()
        ids.forEach { id -> mutate(id, epoch) { if (it.terminal) it else it.copy(state = "paused", reason = if (DirectManualRuntime.safetyBlocked(it)) it.reason else "call_in_progress", requestId = null,
            acceptedRequest = null, pausedForLocalCall = true) } }
        ids.forEach { notifyPause(it, "call_in_progress", epoch) }
    }

    private fun ensureEpoch(epoch: Int) { if (auth.currentSessionEpoch() != epoch || currentUser() == null) throw CancellationException("Transfer account changed") }
    private fun currentUser(): String? = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id
    private fun storage(epoch: Int): DirectFileStorage { ensureEpoch(epoch); check(loadedEpoch == epoch); return requireNotNull(accountStorage) }
    private fun wakeNow(id: String) { pollWakeVersions.computeIfAbsent(id) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet(); pollAt.remove(id); wake.trySend(Unit) }
    private fun errorReason(error: Exception): String = when (error) {
        is DirectStorageError -> error.reason
        is DirectTransferApiError -> if (error.status == 404) { if (serviceEpoch == auth.currentSessionEpoch()) "network_waiting" else "unsupported" }
            else if (error.status == 410) "transfer_unavailable" else if (error.status == 409) "other_device" else "network_waiting"
        is java.io.IOException -> "storage_error"
        is IllegalArgumentException -> "security_error"
        else -> "connection_lost"
    }
    companion object {
        internal fun key(id: String) = "direct_transfer:$id"
        internal fun decodeRecord(raw: String): DirectTransferRecord? = runCatching {
            require(raw.toByteArray(Charsets.UTF_8).size <= 1024 * 1024)
            DirectFileOffer.codec.decodeFromString(DirectTransferRecord.serializer(), raw).also {
                it.offer.validated(); it.manualPause?.validated(); it.signalBudget?.validated(); it.queue?.validated(); require((it.executionSession == null || canonicalUuid(it.executionSession)) && canonicalUuid(it.chatId) && it.role in setOf("sender", "receiver", "other") && it.durableOffset in 0..it.offer.size && it.pending.size <= 8 && it.cursor >= 0)
            }
        }.getOrNull()
    }
}

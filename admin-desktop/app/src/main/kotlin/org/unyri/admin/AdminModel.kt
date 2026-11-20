package org.unyri.admin

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.unyri.admin.core.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

enum class Section(val key: String) { DASHBOARD("dashboard"), USERS("users"), REPORTS("reports"), FEEDBACK("feedback"), DIAGNOSTICS("diagnostics"), SETTINGS("settings") }
data class Dashboard(val health: Health, val policy: ServerPolicy, val summary: Summary, val doctor: DoctorViewData? = null)
data class Action(val key: String, val id: String, val target: String, val expected: String? = null)

class AdminModel(private val scope: CoroutineScope, val demo: Boolean,
    private val preferences: Preferences = Preferences(), private val store: SessionStore = platformSessionStore(),
    private val apiFactory: (Origin) -> AdminApi = { AdminApi(it) }, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private var api: AdminApi? = null
    private var work: Job? = null
    private val epoch = SessionEpoch()
    private var request = 0L
    var language by mutableStateOf(preferences.language)
    val strings get() = Strings(language)
    var origin by mutableStateOf(if (demo) Demo.session.origin else preferences.origin)
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var code by mutableStateOf("")
    var paper by mutableStateOf("")
    var rememberSession by mutableStateOf(false)
    var policy by mutableStateOf(Policy.BOTH)
    var status by mutableStateOf<AdminStatus?>(null)
    var setup by mutableStateOf<Setup?>(null)
    var recovery by mutableStateOf<List<String>>(emptyList())
    var session by mutableStateOf<Session?>(if (demo) Demo.session else null)
    var section by mutableStateOf(Section.DASHBOARD)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    var dashboard by mutableStateOf<Dashboard?>(null)
    var query by mutableStateOf("")
    var filter by mutableStateOf("")
    private var appliedQuery = ""
    private var appliedFilter = ""
    var userPage by mutableStateOf<Page<UserRow>>(Page(emptyList()))
    var queuePage by mutableStateOf<Page<QueueRow>>(Page(emptyList()))
    var auditPage by mutableStateOf<Page<JsonObject>>(Page(emptyList()))
    var performance by mutableStateOf<Performance?>(null)
    var user by mutableStateOf<UserDetail?>(null)
    var queueItem by mutableStateOf<QueueRow?>(null)
    var feedback by mutableStateOf<FeedbackDetail?>(null)
    var image by mutableStateOf<ImageBitmap?>(null)
    var detailText by mutableStateOf<List<JsonObject>>(emptyList())
    var action by mutableStateOf<Action?>(null)
    var reason by mutableStateOf("")
    var confirmation by mutableStateOf("")
    var reviewStatus by mutableStateOf("resolved")
    var note by mutableStateOf("")
    var glass by mutableStateOf(true)
    var hostChecks by mutableStateOf<Map<String, Boolean>>(emptyMap())
    var hostServerVersion by mutableStateOf("")
    fun hostCheck(domain: String) {
        if (demo || session?.origin != "https://$domain") { error = "sh.signInLocal"; return }
        task({ api!!.selfCheck() }) { result ->
            hostServerVersion = (result["server"] as? JsonObject)?.get("version")?.jsonPrimitive?.content.orEmpty()
                .takeIf { it.matches(Regex("[0-9][a-zA-Z0-9.+-]{0,60}")) }.orEmpty()
            fun flag(section: String, field: String) = (result[section] as? JsonObject)?.get(field)?.jsonPrimitive?.booleanOrNull == true
            hostChecks = mapOf("public" to flag("public_url", "configured"), "turn" to flag("turn", "sane"),
                "storage" to flag("storage", "reachable"), "rooms" to flag("rooms", "configured"),
                "push" to ((result["push"] as? JsonObject)?.get("status")?.jsonPrimitive?.content == "configured"))
        }
    }
    var cursor by mutableStateOf<String?>(null)
    var history by mutableStateOf<List<String?>>(emptyList())
    private var lastInput = System.nanoTime()
    private var deadline: Job? = null
    val owner get() = session?.role == "owner"
    val signedIn get() = session != null
    val stage get() = when { recovery.isNotEmpty() -> 3; session != null -> 4; status == null -> 0; !status!!.configured && setup == null -> 1; else -> 2 }

    init { if (demo) refresh() }

    fun input() {
        if (!signedIn || demo) return
        val now = System.nanoTime()
        // A queued input after a suspended window cannot extend an already expired deadline.
        if (now - lastInput >= 15L * 60 * 1_000_000_000) { signOut("locked"); return }
        lastInput = now
        deadline?.cancel()
        deadline = scope.launch { delay(15L * 60 * 1000); signOut("locked") }
    }
    fun checkFocus() {
        if (signedIn && !demo && (System.nanoTime() - lastInput >= 15L * 60 * 1_000_000_000 || session?.valid() == false)) signOut("locked")
    }
    private fun <T> task(operation: () -> T, apply: (T) -> Unit) {
        if (busy) return
        busy = true; error = null
        val generation = epoch.value; val number = ++request
        work = scope.launch {
            try {
                val result = runInterruptible(io) { operation() }
                if (epoch.accepts(generation) && request == number) apply(result)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (epoch.accepts(generation) && request == number) {
                    if ((e as? AdminFailure)?.kind == FailureKind.AUTH) signOut("expired")
                    else if ((e as? AdminFailure)?.kind == FailureKind.MISSING && session != null) {
                        val valid = runCatching { runInterruptible(io) { api?.validateAdmin() } }.isSuccess
                        if (valid) error = "error.MISSING" else signOut("expired")
                    } else error = "error.${(e as? AdminFailure)?.kind ?: FailureKind.SERVER}"
                }
            } finally { if (epoch.accepts(generation) && request == number) busy = false }
        }
    }
    fun login() {
        if (busy) return
        val next = try { apiFactory(Origin(origin)) } catch (_: Exception) { error = "error.INVALID"; return }
        api = next
        task({
            next.login(username, password, preferences.installation)
        }) { status = it; policy = it.policy(); preferences.origin = origin; code = ""; paper = "" }
    }
    fun enroll() = task({ api!!.setup(password, policy, status!!) }) { setup = it }
    fun newPaper() { paper = PaperCrypto.generate() }
    fun unlock() = task({
        val unlocked = api!!.unlock(password, code, paper, policy, status!!)
        val saved = api!!.session!!.copy(optedIn = rememberSession)
        var storageFailed = false
        if (rememberSession) runCatching { store.save(saved) }.onFailure { storageFailed = true; runCatching { store.delete(saved.origin) } }
        else runCatching { store.delete(saved.origin) }.onFailure { storageFailed = true }
        Triple(saved, unlocked.recovery_codes, storageFailed)
    }) { (saved, codes, storageFailed) ->
        session = saved.copy(optedIn = saved.optedIn && !storageFailed); rememberSession = saved.optedIn && !storageFailed
        recovery = codes; password = ""; paper = ""; code = ""; setup = null
        if (storageFailed) toast = "error.STORAGE"
        lastInput = System.nanoTime(); input()
        if (codes.isEmpty()) { busy = false; refresh() }
    }
    fun acknowledgeRecovery() { recovery = emptyList(); refresh() }
    fun restore() = task({
        val selected = Origin(origin)
        val saved = store.load(selected.value) ?: throw AdminFailure(FailureKind.AUTH)
        val next = apiFactory(selected); api = next
        try { next.restore(saved) } catch (e: Exception) { runCatching { store.delete(selected.value) }; throw e }
        next.session!!
    }) { session = it; rememberSession = true; lastInput = System.nanoTime(); input(); busy = false; refresh() }
    fun chooseLanguage(value: String) { language = value; preferences.language = value }
    fun optOut() {
        rememberSession = false
        task({ store.delete(Origin(origin).value) }) { toast = "savedRemoved" }
    }
    fun signOut(message: String = "signedOut", onFinished: (() -> Unit)? = null) {
        val old = api; val oldOrigin = session?.origin ?: origin
        epoch.clear(); request++; work?.cancel(); deadline?.cancel(); api = null
        session = null; status = null; setup = null; recovery = emptyList(); image = null
          hostChecks = emptyMap(); hostServerVersion = ""
        password = ""; code = ""; paper = ""; user = null; queueItem = null; feedback = null; action = null
        dashboard = null; userPage = Page(emptyList()); queuePage = Page(emptyList()); auditPage = Page(emptyList())
        performance = null; detailText = emptyList(); history = emptyList(); cursor = null; reason = ""; note = ""; confirmation = ""
        busy = false; error = null; toast = message
        val generation = epoch.value
        scope.launch {
            val storageOk = runCatching { runInterruptible(io) { if (!demo && oldOrigin.isNotBlank()) store.delete(Origin(oldOrigin).value) } }.isSuccess
            val revoked = runCatching { runInterruptible(io) { old?.logout() } }.isSuccess
            if (epoch.accepts(generation) && (!revoked || !storageOk)) toast = if (!revoked) "revokeFailed" else "error.STORAGE"
            onFinished?.invoke()
        }
    }
    fun leaveOverview() {
        if (section != Section.DASHBOARD) return
        work?.cancel(); request++; busy = false
        dashboard = dashboard?.copy(doctor = null)
    }
    fun navigate(value: Section) {
        if (busy) { work?.cancel(); request++; busy = false }
        section = value; cursor = null; history = emptyList(); user = null; queueItem = null; feedback = null; image = null
        detailText = emptyList(); query = ""; filter = ""; appliedQuery = ""; appliedFilter = ""
        if (value != Section.DASHBOARD) dashboard = dashboard?.copy(doctor = null)
        refresh()
    }
    fun search() { appliedQuery = query; appliedFilter = filter; cursor = null; history = emptyList(); refresh() }
    fun refresh(targetCursor: String? = cursor, targetHistory: List<String?> = history) {
        if (!signedIn || recovery.isNotEmpty() || busy || section == Section.SETTINGS) return
        checkFocus(); if (!signedIn) return
        val sectionAtStart = section; val at = targetCursor; val languageAtStart = language
        task<Any>({
            if (demo) Demo.load(sectionAtStart, at, appliedQuery, appliedFilter, languageAtStart)
            else when (sectionAtStart) {
                Section.DASHBOARD -> {
                    val current = api!!
                    val policy = current.serverInfo()
                    Dashboard(current.health(), policy, current.summary(), if (owner) {
                        val now = java.time.Instant.now()
                        try { DoctorViewData(ConnectionDoctorAddresses.decode(current.selfCheck()["addresses"]), now, current.origin.value, policy.name) }
                        catch (e: AdminFailure) {
                            if (e.kind == FailureKind.AUTH || e.kind == FailureKind.FORBIDDEN) throw AdminFailure(FailureKind.AUTH)
                            if (e.kind == FailureKind.MISSING) DoctorViewData(null, now, current.origin.value, policy.name)
                            else DoctorViewData(null, now, error = "Could not load addresses. Try again.")
                        }
                    } else null)
                }
                Section.USERS -> api!!.users(appliedQuery, appliedFilter, at)
                Section.REPORTS, Section.FEEDBACK -> api!!.queue(sectionAtStart == Section.FEEDBACK, appliedFilter, at)
                Section.DIAGNOSTICS -> if (owner) api!!.audit(at) to api!!.performance() else throw AdminFailure(FailureKind.FORBIDDEN)
                Section.SETTINGS -> Unit
            }
        }) { result ->
            cursor = targetCursor; history = targetHistory
            @Suppress("UNCHECKED_CAST")
            when (sectionAtStart) {
                Section.DASHBOARD -> dashboard = result as Dashboard
                Section.USERS -> userPage = result as Page<UserRow>
                Section.REPORTS, Section.FEEDBACK -> queuePage = result as Page<QueueRow>
                Section.DIAGNOSTICS -> { val pair = result as Pair<Page<JsonObject>, Performance>; auditPage = pair.first; performance = pair.second }
                else -> Unit
            }
        }
    }
    private fun currentNext() = when (section) { Section.USERS -> userPage.next_cursor; Section.DIAGNOSTICS -> auditPage.next_cursor; else -> queuePage.next_cursor }
    fun next() { if (busy) return; val next = currentNext() ?: return; if (next == cursor || next in history || history.size >= 1000) { error = "error.PROTOCOL"; return }; refresh(next, history + listOf(cursor)) }
    fun back() { if (busy || history.isEmpty()) return; refresh(history.last(), history.dropLast(1)) }
    fun canNext() = currentNext() != null
    fun openUser(id: String) {
        val languageAtStart = language
        task({ if (demo) Demo.user(id, languageAtStart) else api!!.user(id) }) { user = it; detailText = emptyList() }
    }
    fun loadImage(id: String, index: Int? = null) = task({
        if (demo) throw AdminFailure(FailureKind.MISSING)
        val body = if (index == null) api!!.avatar(id) else api!!.screenshot(id,index)
        // Check dimensions before Skia allocates the decoded bitmap.
        javax.imageio.ImageIO.createImageInputStream(java.io.ByteArrayInputStream(body.bytes)).use { input ->
            val readers = javax.imageio.ImageIO.getImageReaders(input)
            if (!readers.hasNext()) throw ProtocolFailure()
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width.toLong()*height > 4_194_304) throw ProtocolFailure()
            } finally { reader.dispose() }
        }
        // The Compose bitmap owns the Skia image reference; do not close it before rendering.
        Image.makeFromEncoded(body.bytes).toComposeImageBitmap()
    }) { image = it }
    fun openQueue(id: String) {
        val row = queuePage.items.first { it.id == id }; queueItem = row; note = row.review_note.orEmpty(); reason = ""; reviewStatus = "resolved"
        if (section == Section.FEEDBACK) task({ if (demo) FeedbackDetail(id, row.description) else api!!.feedback(id) }) { feedback = it }
    }
    fun userHistory(notes: Boolean) = task({ if (demo) Page<JsonObject>(emptyList()) else if (notes) api!!.notes(user!!.profile.user_id) else api!!.timeline(user!!.profile.user_id) }) { detailText = it.items }
    fun prepare(key: String, id: String, target: String, expected: String? = null) { action = Action(key, id, target, expected); reason = ""; confirmation = "" }
    fun perform() {
        val selected = action ?: return
        if (demo) return
        task({
            when (selected.key) {
                "suspend", "unsuspend" -> api!!.suspend(selected.id, selected.key == "suspend", reason)
                "readOnly", "clearReadOnly" -> api!!.readOnly(selected.id, selected.key == "readOnly", reason)
                "logoutDevice" -> api!!.logoutDevice(selected.id, reason)
                "deleteUser" -> { if (confirmation != selected.expected) throw AdminFailure(FailureKind.INVALID); val fresh = api!!.user(selected.id); if (fresh.profile.username != selected.expected || !fresh.moderation_allowed) throw AdminFailure(FailureKind.CONFLICT); api!!.deleteUser(selected.id, confirmation, reason) }
                "addNote" -> api!!.addNote(selected.id, reason)
                "review" -> api!!.review(section == Section.FEEDBACK, selected.id, reviewStatus, note, reason)
            }
        }) { action = null; user = null; queueItem = null; feedback = null; detailText = emptyList(); reason = ""; note = ""; toast = "actionDone"; busy = false; refresh() }
    }
}

object Demo {
    private const val id = "00000000-0000-0000-0000-000000000001"
    val session = Session("https://home.example.test", "demo", "demo", id, "demo-owner", id, "owner", id,
        "2099-01-01T00:00:00Z", "2026-11-14T00:00:00Z")
    private val users = (1..73).map { UserRow("00000000-0000-0000-0000-${it.toString().padStart(12, '0')}", "demo${it.toString().padStart(3, '0')}", null, if (it % 9 == 0) "suspended" else "active") }
    private fun <T> page(rows: List<T>, cursor: String?): Page<T> {
        val start = cursor?.toIntOrNull() ?: 0; val batch = rows.drop(start).take(25)
        return Page(batch, if (rows.size > start + 25) (start + 25).toString() else null, rows.size > start + 25)
    }
    private fun localizedUser(row: UserRow, t: Strings) = row.copy(description = t["demo.userName"].replace("%@", row.reason.removePrefix("demo").toInt().toString()))
    fun load(section: Section, cursor: String?, query: String, filter: String, language: String = "en"): Any {
        val t = Strings(language)
        return when (section) {
            Section.DASHBOARD -> java.time.Instant.now().let { now -> Dashboard(Health("ok", true, true), ServerPolicy(t["demo.serverName"], t["demo.operator"]), Summary(3, 1, 2, 8, 0, 4, Storage(1048576, 0, 1073741824)), DoctorViewData(DoctorDemo.addresses(now), now, session.origin, t["demo.serverName"])) }
            Section.USERS -> page(users.map { localizedUser(it, t) }.filter { (it.reason.contains(query, true) || it.description.orEmpty().contains(query, true)) && (filter.isEmpty() || it.status == filter) }, cursor)
            Section.REPORTS, Section.FEEDBACK -> page(listOf(QueueRow(id, id, t["demo.report"], "open", "spam", "demo001", "demo002", "desktop", "0.1.0", "2026-11-14T12:00:00Z")).filter { filter.isEmpty() || filter == it.status }, cursor)
            Section.DIAGNOSTICS -> Page<JsonObject>(emptyList()) to Performance(emptyList(), 24)
            else -> Unit
        }
    }
    fun user(id: String, language: String = "en"): UserDetail {
        val t = Strings(language); val row = localizedUser(users.first { it.user_id == id }, t)
        return UserDetail(Profile(id, row.reason, row.description, t["demo.profile"]), Moderation(row.status), suspend_allowed = true, moderation_allowed = true)
    }
}

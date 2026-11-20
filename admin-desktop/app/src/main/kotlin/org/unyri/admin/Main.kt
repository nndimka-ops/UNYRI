@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.unyri.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import androidx.compose.ui.res.painterResource
import org.unyri.admin.core.*
import org.unyri.desktop.design.*
import kotlinx.serialization.json.*
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

fun main(args: Array<String>) = application {
    val scope = rememberCoroutineScope()
    val model = remember { AdminModel(scope, args.contains("--demo")) }
    val host = remember { HostModel(scope, demo = model.demo) }
    var entry by remember { mutableStateOf(if (model.demo) "connect" else "home") }
    val t = model.strings
    Window(onCloseRequest = {
        if (!model.demo && (!model.rememberSession || !model.signedIn)) model.signOut(onFinished = { exitApplication() })
        else exitApplication()
    }, title = "UNYRI · ${t["admin"]}", state = rememberWindowState(width = 1280.dp, height = 820.dp),
        icon = painterResource("unyri.png")) {
        DisposableEffect(window) {
            window.minimumSize = java.awt.Dimension(900, 640)
            val inputListener = AWTEventListener { if (it.source is java.awt.Component && javax.swing.SwingUtilities.getWindowAncestor(it.source as java.awt.Component) == window) model.input() }
            val focusListener = object : WindowAdapter() {
                override fun windowGainedFocus(e: WindowEvent) { model.checkFocus() }
                override fun windowLostFocus(e: WindowEvent) { host.clearCodes() }
            }
            Toolkit.getDefaultToolkit().addAWTEventListener(inputListener, AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK)
            window.addWindowFocusListener(focusListener)
            onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(inputListener); window.removeWindowFocusListener(focusListener) }
        }
        DesktopTheme {
            Surface(Modifier.fillMaxSize().onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.isCtrlPressed && it.key == Key.R) {
                    if (entry == "server") host.refresh() else model.refresh(); true
                }
                else if (it.type == KeyEventType.KeyDown && it.isCtrlPressed && it.key == Key.L) { model.signOut("locked"); true }
                else if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                    if (!model.busy) { model.action = null; model.user = null; model.queueItem = null; model.feedback = null; model.detailText = emptyList() }; true
                } else false
            }, color = Ink.background) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (model.demo) StateCard(t["demoBanner"])
                    model.toast?.let { key -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(t[key], Modifier.weight(1f)); TextButton({ model.toast = null }) { Text(t["dismiss"]) }
                    } }
                    model.error?.let { StateCard(t[it], t["dismiss"]) { model.error = null } }
                    if (model.busy) Text(t["loading"], color = Ink.secondary)
                    if (model.stage >= 4) {
                        if (host.created) TextButton({
                            host.clearCodes()
                            if (entry == "server") { entry = "connect"; model.refresh() }
                            else { model.leaveOverview(); entry = "server" }
                        }) { Text(t[if (entry == "server") "sh.connect" else "sh.myServer"]) }
                        if (entry == "server") HostPanel(host, model) { host.clearCodes(); entry = "connect"; model.refresh() } else Workspace(model)
                    } else when (entry) {
                        "home" -> HostHome(host, model, { entry = "connect" }, { entry = if (host.created) "server" else "wizard" })
                        "wizard" -> HostWizard(host, model) { host.clearCodes(); entry = "home" }
                        "server" -> HostPanel(host, model) { host.clearCodes(); entry = "home" }
                        else -> { if (!model.demo) TextButton({ entry = "home" }) { Text(t["sh.home"]) }; SignIn(model) }
                    }
                }
                if (model.user != null) UserCard(model)
                if (model.queueItem != null) QueueCard(model)
                if (model.action != null) ConfirmAction(model)
                model.image?.let { bitmap -> Dialog(onDismissRequest = { model.image = null }) {
                    GlassCard(Modifier.widthIn(max = 900.dp).heightIn(max = 700.dp), false) {
                        Image(bitmap, contentDescription = t["imagePreview"], modifier = Modifier.fillMaxWidth().heightIn(max = 580.dp))
                        TextButton({ model.image = null }) { Text(t["close"]) }
                    }
                } }
            }
        }
    }
}

@Composable fun LanguagePicker(model: AdminModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("ru" to "Русский", "en" to "English", "uk" to "Українська").forEach { (code, label) ->
            if (model.language == code) Button({}) { Text(label) } else TextButton({ model.chooseLanguage(code) }) { Text(label) }
        }
    }
}
@Composable fun Entry(label: String, value: String, update: (String) -> Unit, secret: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier) {
    if (secret) OutlinedTextField(value, update, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        enabled = enabled, visualTransformation = PasswordVisualTransformation())
    else OutlinedTextField(value, update, modifier.fillMaxWidth(), label = { Text(label) }, enabled = enabled, singleLine = true)
}
@Composable fun SignIn(m: AdminModel) {
    val t = m.strings
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        GlassCard(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()), glass = m.glass) {
            Text("UNYRI", style = MaterialTheme.typography.headlineLarge)
            Text(t["admin"], color = Ink.secondary)
            LanguagePicker(m)
            when (m.stage) {
                0 -> {
                    Text(t["signInHint"])
                    Entry(t["serverUrl"], m.origin, { m.origin = it }, enabled = !m.busy)
                    Entry(t["username"], m.username, { m.username = it }, enabled = !m.busy)
                    Entry(t["password"], m.password, { m.password = it }, true, !m.busy,
                        Modifier.onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && !m.busy) { m.login(); true } else false })
                    Toolbar {
                        Button({ m.login() }, enabled = !m.busy && m.origin.isNotBlank() && m.username.isNotBlank() && m.password.isNotEmpty()) { Text(t["signIn"]) }
                        OutlinedButton({ m.restore() }, enabled = !m.busy && m.origin.isNotBlank()) { Text(t["restore"]) }
                    }
                }
                1 -> {
                    Text(t["enrollHint"])
                    if (m.status?.auth_protocol == 2 && m.status?.paper_key_configured != true && m.status?.totp_configured != true) {
                        Policy.entries.forEach { policy -> Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(m.policy == policy, { m.policy = policy }, enabled = !m.busy); Text(t["policy.${policy.wire}"])
                        } }
                    } else Text(t["policy.${m.policy.wire}"])
                    Button({ m.enroll() }, enabled = !m.busy) { Text(t["prepare"]) }
                }
                2 -> {
                    Text(t["policy.${m.policy.wire}"])
                    m.setup?.secret?.let { Text(t["totpSetup"]); TechnicalText(it) }
                    if (m.policy.totp) Entry(t["otp"], m.code, { m.code = it }, true, !m.busy)
                    if (m.policy.paper) {
                        Entry(t["paperKey"], m.paper, { m.paper = it }, true, !m.busy)
                        if (m.setup != null && m.status?.paper_key_configured != true && m.status?.signing_public_key == null) {
                            var show by remember { mutableStateOf(false) }
                            Button({ m.newPaper(); show = true }, enabled = !m.busy) { Text(t["generateKey"]) }
                            if (show) { Text(t["paperBackup"]); TechnicalText(m.paper); TextButton({ show = false }) { Text(t["hide"]) } }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(m.rememberSession, { m.rememberSession = it }, enabled = !m.busy); Text(t["remember"]) }
                    Text(t["rememberHint"], color = Ink.secondary)
                    Button({ m.unlock() }, enabled = !m.busy && (!m.policy.totp || m.code.isNotBlank()) && (!m.policy.paper || m.paper.isNotBlank())) { Text(t["unlock"]) }
                }
                3 -> {
                    Text(t["recoveryHint"])
                    m.recovery.forEach { TechnicalText(it) }
                    Button({ m.acknowledgeRecovery() }, enabled = !m.busy) { Text(t["savedCodes"]) }
                }
            }
            if (m.stage > 0) TextButton({ m.signOut() }, enabled = !m.busy) { Text(t["cancel"]) }
        }
    }
}

@Composable fun Workspace(m: AdminModel) {
    val t = m.strings
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        GlassCard(Modifier.width(220.dp).fillMaxHeight().verticalScroll(rememberScrollState()), m.glass) {
            Text("UNYRI", style = MaterialTheme.typography.titleLarge)
            Text(t["admin"], color = Ink.secondary)
            Section.entries.forEach { section -> SidebarItem(t[section.key], m.section == section) { m.navigate(section) } }
            HorizontalDivider()
            Text(m.session?.origin.orEmpty(), color = Ink.secondary)
            Text(t["role.${m.session?.role ?: "moderator"}"])
            TextButton({ m.signOut("locked") }) { Text(t["lock"]) }
            TextButton({ m.signOut() }) { Text(t["signOut"]) }
            Text("0.1.0", color = Ink.secondary)
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageHeader(t[m.section.key], if (m.demo) t["demoBanner"] else t["manualRefresh"]) {
                if (m.section != Section.SETTINGS) OutlinedButton({ m.refresh() }, enabled = !m.busy) { Text(t["refresh"]) }
            }
            when (m.section) {
                Section.DASHBOARD -> DashboardPage(m)
                Section.USERS -> UsersPage(m)
                Section.REPORTS, Section.FEEDBACK -> QueuePage(m)
                Section.DIAGNOSTICS -> DiagnosticsPage(m)
                Section.SETTINGS -> SettingsPage(m)
            }
        }
    }
}

@Composable fun DashboardPage(m: AdminModel) {
    val t = m.strings; val data = m.dashboard
    if (data == null) { StateCard(t[if (m.busy) "loading" else "empty"], t["refresh"]) { m.refresh() }; return }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (m.owner) DoctorCards(m, data.doctor, m.busy)
        GlassCard(Modifier.fillMaxWidth(), m.glass) {
            Text(data.policy.name, style = MaterialTheme.typography.titleLarge)
            Text(t[if (data.health.database && data.health.redis && data.health.status == "ok") "healthy" else "degraded"])
            Text("${t["database"]}: ${t[if (data.health.database) "available" else "unavailable"]} · ${t["messageQueue"]}: ${t[if (data.health.redis) "available" else "unavailable"]}")
            Text(t["serverVersionUnknown"], color = Ink.secondary)
            Text(data.policy.contact)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric(t["openReports"], data.summary.open_reports.toString())
            Metric(t["inProgress"], data.summary.in_progress_reports.toString())
            Metric(t["openFeedback"], data.summary.unresolved_feedback.toString())
            Metric(t["suspendedAccounts"], data.summary.active_account_suspensions.toString())
            Metric(t["blockedInstalls"], data.summary.active_installation_suspensions.toString())
        }
        data.summary.storage?.let { storage -> GlassCard(Modifier.fillMaxWidth(), m.glass) {
            Text(t["storage"]); Text("${storage.used_bytes} / ${storage.budget_bytes} ${t["bytes"]}")
            Text("${t["reserved"]}: ${storage.reserved_bytes} ${t["bytes"]}", color = Ink.secondary)
        } }
    }
}
@Composable fun SearchTools(m: AdminModel, users: Boolean) {
    val t = m.strings
    Toolbar {
        if (users) OutlinedTextField(m.query, { m.query = it }, Modifier.width(260.dp).onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && !m.busy) { m.search(); true } else false
        }, label = { Text(t["search"]) }, singleLine = true, enabled = !m.busy)
        var expanded by remember { mutableStateOf(false) }
        val values = if (users) listOf("", "active", "suspended", "read_only", "restricted")
            else if (m.section == Section.FEEDBACK) listOf("", "open", "resolved", "dismissed") else listOf("", "open", "in_progress", "resolved", "dismissed")
        Box {
            OutlinedButton({ expanded = true }, enabled = !m.busy) { Text(t["status.${m.filter.ifEmpty { "all" }}"]) }
            DropdownMenu(expanded, { expanded = false }) { values.forEach { value -> DropdownMenuItem(text = { Text(t["status.${value.ifEmpty { "all" }}"]) }, onClick = { m.filter = value; expanded = false }) } }
        }
        Button({ m.search() }, enabled = !m.busy) { Text(t["search"]) }
        TextButton({ m.query = ""; m.filter = ""; m.search() }, enabled = !m.busy) { Text(t["clear"]) }
    }
}
@Composable fun Pagination(m: AdminModel) {
    val t = m.strings
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton({ m.back() }, enabled = m.history.isNotEmpty() && !m.busy) { Text(t["previous"]) }
        Text("${t["page"]} ${m.history.size + 1}")
        OutlinedButton({ m.next() }, enabled = m.canNext() && !m.busy) { Text(t["next"]) }
    }
}
@Composable fun ColumnScope.UsersPage(m: AdminModel) {
    val t = m.strings
    SearchTools(m, true)
    DataTable(listOf(t["username"], t["displayName"], t["status"]), m.userPage.items.map {
        TableRow(it.user_id, listOf("@${it.reason}", it.description.orEmpty(), t.status(it.status)))
    }, t["open"], t["empty"], t["pageSort"], m::openUser, Modifier.weight(1f))
    Pagination(m)
}
@Composable fun ColumnScope.QueuePage(m: AdminModel) {
    val t = m.strings
    SearchTools(m, false)
    DataTable(listOf(t["created"], t["description"], t["status"]), m.queuePage.items.map {
        TableRow(it.id, listOf(it.created_at.orEmpty(), it.description.orEmpty(), t.status(it.status)))
    }, t["open"], t["empty"], t["pageSort"], m::openQueue, Modifier.weight(1f))
    Pagination(m)
}
private fun Strings.status(value: String): String = if (value in setOf("open", "in_progress", "resolved", "dismissed", "active", "suspended", "read_only", "restricted")) this["status.$value"] else this["unknown"]
internal fun Strings.reportReason(value: String): String =
    if (value in setOf("spam", "harassment", "pornography", "scam", "self_harm", "copyright", "other")) this["reportReason.$value"] else this["unknown"]
internal fun Strings.platform(value: String): String = when (value.lowercase()) {
    "ios" -> "iOS"
    "android" -> "Android"
    "windows" -> "Windows"
    "linux" -> "Linux"
    "macos", "mac" -> "macOS"
    "desktop" -> this["platform.desktop"]
    "web" -> this["platform.web"]
    "" -> ""
    else -> this["unknown"]
}
internal fun Strings.auditAction(value: String): String {
    val group = value.substringBefore(':')
    if (group in setOf("report", "feedback") && value.contains(':'))
        return "${this["audit.$group"]} · ${status(value.substringAfter(':'))}"
    val key = when (value) {
        "enroll" -> "enroll"
        "unlock" -> "unlock"
        "rotate-signing-key" -> "rotateKey"
        "operator:authentication-policy" -> "authenticationPolicy"
        "notice:send" -> "notice"
        "diagnostics:export" -> "diagnosticsExport"
        "profile:moderate" -> "profileModeration"
        "read_only:enable" -> "readOnly"
        "read_only:disable" -> "clearReadOnly"
        "device_logout" -> "logoutDevice"
        "note:add" -> "note"
        "device_suspend" -> "suspendDevice"
        "device_unsuspend" -> "restoreDevice"
        "operations:controls" -> "controls"
        "announcement:publish" -> "publishAnnouncement"
        "announcement:disable" -> "removeAnnouncement"
        "operator:role" -> "role"
        "operator:reset_2fa" -> "resetProtection"
        "credential:recovery_code" -> "recoveryCode"
        "suspend" -> "suspend"
        "unsuspend" -> "restore"
        "suspend_with_installations" -> "suspendWithDevices"
        "unsuspend_with_installations" -> "restoreWithDevices"
        "setup:claim-invalid" -> "claimInvalid"
        "setup:claim-owner" -> "claimOwner"
        "registration:mode" -> "registration"
        "invite:create" -> "inviteCreate"
        "invite:revoke" -> "inviteRevoke"
        "invite:register-refused" -> "inviteRefused"
        "invite:register" -> "inviteRegister"
        "account_delete" -> "deleteUser"
        "console:handoff-issued" -> "handoff"
        "console:session-open" -> "consoleOpen"
        "console:session-close" -> "consoleClose"
        "local:clear-message-call-history" -> "clearHistory"
        "security:factor-lockout" -> "factorLockout"
        "server:policy" -> "serverSettings"
        "storage:cleanup_scheduled" -> "storageCleanup"
        else -> "other"
    }
    return this["audit.$key"]
}
private fun JsonObject.value(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

@Composable fun ColumnScope.DiagnosticsPage(m: AdminModel) {
    val t = m.strings
    if (!m.owner) { StateCard(t["ownerOnly"]); return }
    Text(t["diagnosticsHint"], color = Ink.secondary)
    Text("${t["samples"]}: ${m.performance?.items?.size ?: 0} · ${t["retention24"]}")
    m.performance?.items?.take(3)?.forEach { sample -> TechnicalText(sample.toString().take(400)) }
    var auditDetail by remember { mutableStateOf<JsonObject?>(null) }
    DataTable(listOf(t["created"], t["action"], t["reason"]), m.auditPage.items.mapIndexed { index, item ->
        TableRow(item.value("id").ifEmpty { index.toString() }, listOf(item.value("created_at"), t.auditAction(item.value("action")), item.value("reason")))
    }, t["open"], t["empty"], t["pageSort"], { id -> auditDetail = m.auditPage.items.firstOrNull { it.value("id") == id } }, Modifier.weight(1f))
    Pagination(m)
    auditDetail?.let { detail -> Dialog(onDismissRequest = { auditDetail = null }) { GlassCard(Modifier.width(640.dp).heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
        Text(t["auditDetails"]); TechnicalText(detail.toString()); TextButton({ auditDetail = null }) { Text(t["close"]) }
    } } }
}
@Composable fun SettingsPage(m: AdminModel) {
    val t = m.strings
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassCard(Modifier.fillMaxWidth(), m.glass) {
            Text(t["language"]); LanguagePicker(m)
            Text(t["serverUrl"]); TechnicalText(m.session?.origin.orEmpty()); Text(t["changeServerHint"])
            Text(t["rememberHint"])
            Text(t[if (m.rememberSession) "rememberEnabled" else "rememberDisabled"])
            OutlinedButton({ m.optOut() }, enabled = !m.busy && !m.demo) { Text(t["removeSaved"]) }
            Text(t["lockHint"])
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(m.glass, { m.glass = it }); Text(t["glass"]) }
        }
        StateCard(t["futureFeatures"])
    }
}

@Composable fun UserCard(m: AdminModel) {
    val t = m.strings; val user = m.user ?: return
    Dialog(onDismissRequest = { if (!m.busy) m.user = null }) {
        GlassCard(Modifier.widthIn(max = 900.dp).fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()), m.glass) {
            PageHeader(user.profile.display_name ?: user.profile.username, "@${user.profile.username}")
            Text(user.profile.bio.orEmpty())
            if (user.profile.has_avatar && !m.demo) OutlinedButton({ m.loadImage(user.profile.user_id) }, enabled = !m.busy) { Text(t["viewAvatar"]) }
            Text(t.status(user.moderation.status)); Text(user.moderation.reason.orEmpty())
            Text("${t["openReports"]}: ${user.moderation.reports_open} / ${user.moderation.reports_total}")
            Text("${t["readOnly"]}: ${t[if (user.moderation.read_only) "yes" else "no"]}")
            Toolbar {
                OutlinedButton({ m.userHistory(true) }, enabled = !m.busy) { Text(t["notes"]) }
                OutlinedButton({ m.userHistory(false) }, enabled = !m.busy) { Text(t["timeline"]) }
                if (m.owner && !m.demo) {
                    OutlinedButton({ m.prepare("addNote", user.profile.user_id, "@${user.profile.username}") }, enabled = !m.busy) { Text(t["addNote"]) }
                    if (user.moderation_allowed) {
                        val suspend = if (user.moderation.status == "suspended") "unsuspend" else "suspend"
                        val readOnly = if (user.moderation.read_only) "clearReadOnly" else "readOnly"
                        OutlinedButton({ m.prepare(suspend, user.profile.user_id, "@${user.profile.username}") }, enabled = !m.busy) { Text(t[suspend]) }
                        OutlinedButton({ m.prepare(readOnly, user.profile.user_id, "@${user.profile.username}") }, enabled = !m.busy) { Text(t[readOnly]) }
                        TextButton({ m.prepare("deleteUser", user.profile.user_id, "@${user.profile.username}", user.profile.username) }, enabled = !m.busy) { Text(t["deleteUser"], color = Ink.danger) }
                    }
                }
            }
            Text(t["devices"], style = MaterialTheme.typography.titleMedium)
            if (user.devices.isEmpty()) Text(t["empty"], color = Ink.secondary)
            user.devices.forEach { device ->
                GlassCard(Modifier.fillMaxWidth(), false) {
                    Text(device.value("display_name").ifBlank { t["unknown"] })
                    Text((listOf(t.platform(device.value("platform"))) + listOf("app_version", "os_version", "last_active_at").map(device::value)).filter(String::isNotBlank).joinToString(" · "))
                    Text("${t["blockedInstall"]}: ${t[if (device.value("restriction_active") == "true") "yes" else "no"]}")
                    if (m.owner && !m.demo && user.moderation_allowed && device.value("revoked") != "true") OutlinedButton({ m.prepare("logoutDevice", device.value("id"), device.value("display_name")) }, enabled = !m.busy) { Text(t["logoutDevice"]) }
                }
            }
            m.detailText.forEach { TechnicalText(it.toString()) }
            TextButton({ m.user = null; m.detailText = emptyList() }, enabled = !m.busy) { Text(t["close"]) }
        }
    }
}
@Composable fun QueueCard(m: AdminModel) {
    val t = m.strings; val item = m.queueItem ?: return
    Dialog(onDismissRequest = { if (!m.busy) { m.queueItem = null; m.feedback = null } }) {
        GlassCard(Modifier.widthIn(max = 760.dp).fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()), m.glass) {
            Text(t[if (m.section == Section.FEEDBACK) "feedback" else "reports"], style = MaterialTheme.typography.titleLarge)
            Text(item.description.orEmpty()); Text(t.status(item.status))
            Text(listOfNotNull(item.reported_username, item.reporter_username, item.reason?.let { t.reportReason(it) }, item.platform?.let { t.platform(it) }, item.app_version, item.created_at).joinToString(" · "))
            item.decision_reason?.takeIf { it.isNotEmpty() }?.let { Text("${t["reason"]}: $it") }
            m.feedback?.let { detail ->
                Text("${t["screenshots"]}: ${detail.screenshots.size}")
                Toolbar { detail.screenshots.forEach { screenshot ->
                    OutlinedButton({ m.loadImage(detail.id, screenshot.index) }, enabled = !m.busy && !m.demo) { Text("${t["open"]} ${screenshot.index+1}") }
                } }
                detail.performance?.let { TechnicalText(it.toString().take(16000)) }
            }
            if (m.owner && !m.demo) {
                var expanded by remember { mutableStateOf(false) }
                Box { OutlinedButton({ expanded = true }, enabled = !m.busy) { Text(t.status(m.reviewStatus)) }
                    DropdownMenu(expanded, { expanded = false }) {
                        (if (m.section == Section.FEEDBACK) listOf("open", "resolved", "dismissed") else listOf("open", "in_progress", "resolved", "dismissed")).forEach { value ->
                            DropdownMenuItem(text = { Text(t.status(value)) }, onClick = { m.reviewStatus = value; expanded = false })
                        }
                    }
                }
                Entry(t["note"], m.note, { m.note = it }, enabled = !m.busy)
                Button({ m.prepare("review", item.id, item.description.orEmpty().take(120)) }, enabled = !m.busy) { Text(t["review"]) }
            }
            TextButton({ m.queueItem = null; m.feedback = null }, enabled = !m.busy) { Text(t["close"]) }
        }
    }
}
@Composable fun ConfirmAction(m: AdminModel) {
    val t = m.strings; val action = m.action ?: return
    Dialog(onDismissRequest = { if (!m.busy) m.action = null }) {
        GlassCard(Modifier.width(580.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState()), false) {
            Text(t[action.key], style = MaterialTheme.typography.titleLarge)
            Text(action.target)
            Text(t[if (action.key == "deleteUser") "deleteHint" else "auditHint"])
            Entry(t[if (action.key == "addNote") "note" else "reason"], m.reason, { m.reason = it }, enabled = !m.busy)
            action.expected?.let { Entry("${t["typeUsername"]}: $it", m.confirmation, { m.confirmation = it }, enabled = !m.busy) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton({ m.action = null }, enabled = !m.busy) { Text(t["cancel"]) }
                Button({ m.perform() }, enabled = !m.busy && m.reason.isNotBlank() && m.reason.length <= 2000 && (action.expected == null || action.expected == m.confirmation)) { Text(t[action.key]) }
            }
        }
    }
}

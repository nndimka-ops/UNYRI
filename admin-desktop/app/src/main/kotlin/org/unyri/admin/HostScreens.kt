@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.unyri.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.unyri.admin.core.*
import org.unyri.desktop.design.*
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import javax.swing.JFileChooser
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

private fun browse(url: String) { runCatching { Desktop.getDesktop().browse(URI(url)) } }
private fun chooseDirectory(done: (Path) -> Unit) {
    val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) done(chooser.selectedFile.toPath())
}
@Composable private fun HostCard(m: AdminModel, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        GlassCard(Modifier.widthIn(max = 900.dp).fillMaxWidth().verticalScroll(rememberScrollState()), m.glass, content)
    }
}
@Composable fun HostHome(h: HostModel, m: AdminModel, connect: () -> Unit, create: () -> Unit) = HostCard(m) {
    val t = m.strings
    Text("UNYRI", style = MaterialTheme.typography.headlineLarge)
    LanguagePicker(m)
    Text(t["sh.homeHint"])
    Button(connect) { Text(t["sh.connect"]) }
    Button(create, enabled = !h.created) { Text(t["sh.create"]) }
    if (h.created) Button(create) { Text(t["sh.myServer"]) }
}
@Composable private fun Feedback(h: HostModel, t: Strings) {
    h.error?.let { Text(t[it], color = Ink.danger) }
    h.message?.let { Text(t[it]) }
    if (h.busy) Text(t["loading"], color = Ink.secondary)
}
@Composable private fun Toggle(text: String, value: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(value, change, enabled = enabled); Text(text) }
}
@Composable private fun HostDetails(t: Strings, id: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember(id) { mutableStateOf(false) }
    TextButton({ expanded = !expanded }) { Text(t["sh.details"]) }
    if (expanded) Column(content = content)
}
@Composable private fun ReleasePicker(h: HostModel, t: Strings) {
    Text(t["sh.bundleHint"])
    HostDetails(t, "release") {
        Text(t["sh.technical.bundle"])
        h.root?.let { SelectionContainer { TechnicalText(it.toString()) } }
    }
    OutlinedButton({ chooseDirectory(h::selectRoot) }, enabled = !h.busy) { Text(t["sh.chooseBundle"]) }
}
@Composable fun HostWizard(h: HostModel, m: AdminModel, home: () -> Unit) = HostCard(m) {
    val t = m.strings; val step = h.state.step; val key = step.name.lowercase()
    Text(t["sh.create"], style = MaterialTheme.typography.headlineMedium)
    LanguagePicker(m)
    Text("${step.ordinal + 1} / 7 · ${t["sh.step.$key"]}", style = MaterialTheme.typography.titleLarge)
    Text(t["sh.explain.$key"])
    Text(t["sh.why"] + " " + t["sh.why.$key"], color = Ink.secondary)
    Feedback(h, t)
    when (step) {
        HostStep.DOCKER -> {
            Text(t[if (h.windows) "sh.dockerWindows" else "sh.dockerLinux"])
            HostDetails(t, "docker") { Text(t[if (h.windows) "sh.technical.dockerWindows" else "sh.technical.dockerLinux"]) }
            if (h.state.docker) Text(t["sh.dockerReady"])
            Toolbar {
                OutlinedButton({ browse(if (h.windows) "https://www.docker.com/products/docker-desktop/" else "https://docs.docker.com/engine/install/") }) { Text(t["sh.downloadDocker"]) }
                Button(h::checkDocker, enabled = !h.busy) { Text(t["sh.checkAgain"]) }
            }
            ReleasePicker(h, t)
        }
        HostStep.NAME -> {
            Toggle(t["sh.domain"], !h.duck) { h.duck = !it }
            Toggle(t["sh.duck"], h.duck) { h.duck = it }
            Entry(t["sh.serverName"], h.serverName, { h.serverName = it }, enabled = !h.busy)
            Entry(t["sh.dnsName"], h.domain, { h.domain = it.trim().lowercase() }, enabled = !h.busy)
            if (h.duck) {
                Text(t["sh.duckHint"])
                Entry(t["sh.duckToken"], h.duckToken, { h.duckToken = it }, secret = true, enabled = !h.busy)
                Button(h::saveDuckToken, enabled = !h.busy && h.duckToken.isNotBlank()) { Text(t["sh.saveToken"]) }
                OutlinedButton({ browse("https://www.duckdns.org/install.jsp") }) { Text(t["sh.duckSetup"]) }
            }
            OutlinedButton({}, enabled = false) { Text(t["sh.unyriSoon"]) }
        }
        HostStep.INTERNET -> {
            Text("${t["sh.lan"]}: ${h.lan.ifBlank { t["sh.unknown"] }}")
            Text("${t["sh.router"]}: ${h.router.ifBlank { t["sh.unknown"] }}")
            OutlinedButton(h::network, enabled = !h.busy) { Text(t["sh.checkAgain"]) }
            Entry(t["sh.wan"], h.wan, { h.wan = it.trim() }, enabled = !h.busy)
            Entry(t["sh.publicIp"], h.publicIp, { h.publicIp = it.trim() }, enabled = !h.busy)
            Toggle(t["sh.ipConsent"], h.ipConsent, !h.busy) { h.ipConsent = it }
            OutlinedButton(h::detectIp, enabled = h.ipConsent && !h.busy && !h.localTrial) { Text(t["sh.detectIp"]) }
            if (h.cgnat) Text(t["sh.cgnat"], color = Ink.danger)
            if (h.duck) {
                Toggle(t["sh.duckConsent"], h.duckConsent, !h.busy) { h.duckConsent = it }
                OutlinedButton(h::updateDuck, enabled = h.duckConsent && HostValidation.publicIpv4(h.publicIp) && !h.busy) { Text(t["sh.updateDdns"]) }
            }
            Text(t["sh.dynamicIp"], color = Ink.secondary)
            HostDetails(t, "internet") { Text(t["sh.technical.dynamicIp"]) }
        }
        HostStep.PORTS -> {
            if (h.localTrial) Text(t["sh.trialHint"])
            Toggle(t["sh.rooms"], h.rooms, !h.busy && !h.localTrial) { h.rooms = it; h.state = h.state.copy(portsChecked = false, firewall = FirewallState.PENDING) }
            OutlinedButton(h::checkPorts, enabled = !h.busy && h.root != null) { Text(t["sh.checkPorts"]) }
            if (h.state.portsChecked) Text(t["sh.portsReady"])
            h.portConflicts.forEach { conflict ->
                Text(t.format("sh.portBusy.${conflict.kind}", conflict.ports, conflict.holder), color = Ink.danger)
            }
            HostDetails(t, "ports") {
                Text(t["sh.technical.ports"])
                Row { Text(t["sh.port"], Modifier.width(130.dp)); Text(t["sh.protocol"], Modifier.width(90.dp)); Text(t["sh.portPurpose"]) }
                val portRows = if (h.localTrial) listOf("18080" to "TCP", "18443" to "TCP", "13478" to "UDP", "13478" to "TCP", "59152–59200" to "UDP")
                    else listOf("80" to "TCP", "443" to "TCP", "3478" to "UDP", "3478" to "TCP", "49152–49200" to "UDP", "17881" to "TCP", "17882" to "UDP")
                portRows.forEachIndexed { i, (port, proto) ->
                    Row { Text(port, Modifier.width(130.dp)); Text(proto, Modifier.width(90.dp)); Text(t["sh.port.$i"]) }
                }
                h.portConflicts.forEach { conflict ->
                    SelectionContainer { TechnicalText("${conflict.protocol} ${conflict.ports}: ${conflict.holder}") }
                }
                Text(t["sh.noInternalPorts"], color = Ink.secondary)
                Text(t["sh.technical.router"])
                if (h.localTrial) Text(t["sh.technical.trial"])
            }
            Text(t["sh.routerHelp"])
            if (h.windows && !h.localTrial) {
                Text(t["sh.firewallHint"])
                Button(h::firewall, enabled = !h.busy && h.root != null) { Text(t["sh.firewall"]) }
            } else if (!h.localTrial) HostDetails(t, "firewall") { SelectionContainer { TechnicalText("sudo ufw allow 80/tcp\nsudo ufw allow 443/tcp\nsudo ufw allow 3478/tcp\nsudo ufw allow 3478/udp\nsudo ufw allow 49152:49200/udp\n# ${t["sh.roomsOnly"]}\nsudo ufw allow 17881/tcp\nsudo ufw allow 17882/udp\nsudo ufw status verbose") } }
            Text(t["sh.firewall.${h.state.firewall.name.lowercase()}"])
            TextButton({ h.state = h.state.copy(firewall = FirewallState.SKIPPED) }, enabled = !h.busy) { Text(t["sh.manualFirewall"]) }
            Toggle(t["sh.upnpConsent"], h.upnpConsent, !h.busy) { h.upnpConsent = it }
            OutlinedButton(h::upnp, enabled = h.upnpConsent && h.root != null && !h.busy && !h.localTrial) { Text(t["sh.upnp"]) }
        }
        HostStep.OPTIONS -> {
            Toggle(t["sh.inviteOnly"], h.invites, !h.busy) { h.invites = it }
            Text(t[if (h.invites) "sh.inviteHint" else "sh.openHint"])
        }
        HostStep.INSTALL -> {
            ReleasePicker(h, t)
            if (!h.imageAvailable) {
                Text(t["sh.sourceWarning"], color = Ink.danger)
                Toggle(t["sh.sourceConsent"], h.sourceConsent, !h.busy) { h.sourceConsent = it }
            } else {
                Text(t["sh.imageReady"])
                if (h.state.failed) { Text(t["sh.sourceWarning"]); Toggle(t["sh.sourceConsent"], h.sourceConsent, !h.busy) { h.sourceConsent = it } }
            }
            Text(t["sh.installConsent"])
            if (h.busy) {
                val fraction = (h.phases.distinct().size / 8f).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            h.phases.lastOrNull()?.let { Text(t["sh.phase.$it"]) }
            HostLog(h, t)
            Button(h::install, enabled = !h.busy && h.root != null && (h.imageAvailable || h.sourceConsent)) { Text(t[if (h.state.failed) "sh.retry" else "sh.install"]) }
        }
        HostStep.FINISH -> HostFinish(h, t)
    }
    Toolbar {
        TextButton(home, enabled = !h.busy) { Text(t["sh.home"]) }
        if (step != HostStep.FINISH) {
            OutlinedButton({ h.state = h.state.back() }, enabled = !h.busy && step != HostStep.DOCKER) { Text(t["previous"]) }
            if (step != HostStep.INSTALL) Button(h::next, enabled = !h.busy && when (step) {
                HostStep.DOCKER -> h.state.docker && h.root != null
                HostStep.NAME -> (HostValidation.domain(h.domain) || h.localTrial && h.domain == "localhost") && HostValidation.serverName(h.serverName) && (!h.duck || h.domain.endsWith(".duckdns.org"))
                HostStep.INTERNET -> HostValidation.publicIpv4(h.publicIp) || h.localTrial && h.publicIp == "127.0.0.1"
                HostStep.PORTS -> h.state.portsChecked && h.state.firewall != FirewallState.PENDING
                else -> true
            }) { Text(t["next"]) }
        }
    }
}
@Composable private fun HostLog(h: HostModel, t: Strings) {
    val clipboard = LocalClipboardManager.current
    val log = h.phases.joinToString("\n") { t["sh.phase.$it"] }
    var expanded by remember { mutableStateOf(false) }
    TextButton({ expanded = !expanded }) { Text(t["sh.log"]) }
    if (expanded) {
        SelectionContainer { TechnicalText(log) }
        OutlinedButton({ clipboard.setText(AnnotatedString(log)) }, enabled = log.isNotBlank()) { Text(t["sh.copyLog"]) }
    }
}
@Composable private fun HostFinish(h: HostModel, t: Strings) {
    SelectionContainer { TechnicalText(h.origin) }
    val matrix = remember(h.origin) { QRCodeWriter().encode(h.origin, BarcodeFormat.QR_CODE, 200, 200) }
    Canvas(Modifier.size(200.dp).background(Color.White)) {
        val pixel = size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x,y]) drawRect(Color.Black, Offset(x * pixel, y * pixel), Size(pixel, pixel))
    }
    Text(t[if (h.localTrial) "sh.trialHint" else "sh.phone"])
    if (!h.localTrial) Text(t["sh.phoneChecks"], color = Ink.secondary)
    Toolbar {
        Button(h::revealCodes, enabled = !h.busy) { Text(t["sh.revealCode"]) }
        TextButton(h::clearCodes) { Text(t["hide"]) }
    }
    h.ownerCode?.let { Text(t["sh.ownerCode"]); SelectionContainer { TechnicalText(it) } }
    Text(t["sh.inviteHint"])
    Text(t["sh.fallback"])
    HostDetails(t, "owner") {
        Text(t["sh.technical.fallback"])
        SelectionContainer { TechnicalText("docker compose --project-name unyri-selfhost --env-file .env -f docker-compose.selfhost.yml exec backend coco-admin grant-owner USERNAME") }
    }
}
@Composable fun HostPanel(h: HostModel, m: AdminModel, home: () -> Unit = {}) = HostCard(m) {
    val t = m.strings
    DisposableEffect(h.root, m.session?.origin) {
        h.openAddresses(m.session?.origin?.takeIf { it == h.origin })
        onDispose { h.closeAddresses() }
    }
    Text(t["sh.myServer"], style = MaterialTheme.typography.headlineMedium)
    SelectionContainer { TechnicalText(h.origin) }
    DoctorCards(m, h.doctor, h.doctorLoading)
    Feedback(h, t)
    Text("${t["sh.health"]}: ${t[when (h.healthy) { true -> "healthy"; false -> "unavailable"; null -> "sh.unknown" }]}")
    HostDetails(t, "server") {
        h.root?.let { SelectionContainer { TechnicalText(it.toString()) } }
        Text("${t["sh.version"]}: ${(if (m.session?.origin == h.origin) m.hostServerVersion else "").ifBlank { h.version }.ifBlank { t["sh.unknown"] }}")
    }
    Text(t["sh.selfCheckHint"])
    Toolbar {
        OutlinedButton(h::refresh, enabled = h.created && !h.busy) { Text(t["refresh"]) }
        OutlinedButton({ m.hostCheck(h.domain) }, enabled = m.signedIn && !m.demo && !m.busy) { Text(t["sh.selfCheck"]) }
        OutlinedButton({ browse("${h.origin}/administration") }, enabled = h.created && !h.demo) { Text(t["sh.invites"]) }
    }
    if (m.session?.origin == h.origin) m.hostChecks.forEach { (key, ok) -> Text("${t["sh.check.$key"]}: ${t[if (ok) "available" else "unavailable"]}") }
    Toolbar {
        listOf("start", "stop", "update", "backup", "restore", "delete").forEach { action ->
            OutlinedButton({ if (action == "restore") chooseDirectory { h.restorePath = it; h.ask(action) } else h.ask(action) }, enabled = h.created && !h.busy && !h.demo) { Text(t["sh.action.$action"]) }
        }
        TextButton(home, enabled = !h.busy) { Text(t["sh.home"]) }
    }
    if (h.created && !h.demo) HostFinish(h, t)
    h.confirmation?.let { action -> AlertDialog(onDismissRequest = { h.confirmation = null }, title = { Text(t["sh.action.$action"]) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(t["sh.confirm.$action"])
            if (action in setOf("backup", "restore", "update")) HostDetails(t, action) {
                Text(t["sh.technical.$action"])
                if (action == "restore") SelectionContainer { TechnicalText(h.restorePath.toString()) }
            }
            if (action in setOf("delete", "restore")) {
                Text(t["sh.typeDomain"] + " " + h.domain)
                Entry(t["sh.dnsName"], h.typed, { h.typed = it })
                if (action == "delete") Toggle(t["sh.eraseBackup"], h.eraseBackup) { h.eraseBackup = it }
            }
        }
    }, confirmButton = { Button(h::manage, enabled = action !in setOf("delete", "restore") || h.typed == h.domain) { Text(t["sh.confirm"]) } }, dismissButton = { TextButton({ h.confirmation = null }) { Text(t["cancel"]) } }) }
}

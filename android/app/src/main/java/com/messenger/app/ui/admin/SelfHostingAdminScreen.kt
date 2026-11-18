package com.messenger.app.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.messenger.app.R
import com.messenger.app.data.selfhost.ServerInviteLink
import kotlinx.serialization.json.*

@Composable
internal fun SelfHostingAdminScreen(role: String, base: String, busy: Boolean, transport: AdminTransport,
    run: (suspend () -> Unit) -> Unit, onBack: () -> Unit) {
    val actions = remember(role, transport) { SelfHostingAdminActions(role, transport) }
    val owner = AdminNavigation.isOwner(role)
    var page by remember { mutableStateOf<InvitePage?>(null) }
    var mode by remember { mutableStateOf<RegistrationMode?>(null) }
    var check by remember { mutableStateOf<JsonObject?>(null) }
    var created by remember { mutableStateOf<CreatedInvite?>(null) }
    var foreground by remember { mutableStateOf(true) }
    var revoke by remember { mutableStateOf<InviteRow?>(null) }
    var uses by remember { mutableStateOf("1") }
    var days by remember { mutableStateOf("7") }
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val clipLabel = remember { "UNYRI-invite-" + java.util.UUID.randomUUID() }
    fun copy(value: String) {
        val clip = ClipData.newPlainText(clipLabel, value)
        clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        clipboard.setPrimaryClip(clip)
    }
    fun share(value: String) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value), null))
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) foreground = true
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) { foreground = false; created = null }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { created = null; lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        run {
            page = actions.invites()
            if (owner) { mode = actions.registration(); check = actions.selfCheck() }
        }
    }
    AdminListScreen(stringResource(R.string.sh_tools), onBack) {
      LazyColumn(contentPadding = PaddingValues(com.messenger.app.ui.theme.CocoSpacing.lg),
          verticalArrangement = Arrangement.spacedBy(com.messenger.app.ui.theme.CocoSpacing.md)) {
        if (owner) item("registration") { AdminCard {
            Text(stringResource(R.string.sh_registration))
            if (mode == null) AdminActionRow(stringResource(R.string.admin_refresh), enabled = !busy,
                onClick = { run { mode = actions.registration() } })
            mode?.let { value ->
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sh_invite_only), Modifier.weight(1f))
                    Switch(value.mode == "invite_only", enabled = !busy, onCheckedChange = { required ->
                        run { actions.setMode(if (required) "invite_only" else "open"); mode = actions.registration() }
                    })
                }
                if (!value.open) Text(stringResource(R.string.sh_registration_closed))
            }
        } }
        item("create") { AdminCard {
            Text(stringResource(R.string.sh_invites))
            OutlinedTextField(uses, { uses = it.take(5) }, label = { Text(stringResource(R.string.sh_uses)) },
                enabled = !busy, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            OutlinedTextField(days, { days = it.take(3) }, label = { Text(stringResource(R.string.sh_days)) },
                enabled = !busy, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            Text(stringResource(R.string.sh_invite_bounds))
            AdminActionRow(stringResource(R.string.sh_create_invite), enabled = !busy && uses.toIntOrNull() in 1..10000 && days.toIntOrNull() in 1..365,
                onClick = { run {
                    val invitation = actions.create(uses.toInt(), days.toInt())
                    if (foreground) created = invitation
                    page = actions.invites()
                } })
            AdminActionRow(stringResource(R.string.admin_refresh), enabled = !busy, onClick = { run { page = actions.invites() } })
        } }
        if (page?.items?.isEmpty() == true) item("empty") { Text(stringResource(R.string.admin_section_empty)) }
        items(page?.items.orEmpty(), key = { it.id }) { row -> AdminCard {
            Text(stringResource(when (row.status) {
                "active" -> R.string.sh_active
                "revoked" -> R.string.sh_revoked
                "expired" -> R.string.sh_expired
                "exhausted" -> R.string.sh_exhausted
                else -> R.string.sh_unknown
            }))
            AdminKeyValue(stringResource(R.string.sh_uses), "${row.uses} / ${row.maxUses}")
            AdminKeyValue(stringResource(R.string.sh_expiry), AdminTitles.time(row.expiresAt))
            AdminKeyValue(stringResource(R.string.sh_creator), row.creator?.take(128) ?: row.createdBy?.let { AdminPresentation.shortId(it) } ?: "—")
            if (row.status == "active") AdminActionRow(stringResource(R.string.sh_revoke), enabled = !busy, onClick = { revoke = row })
        } }
        page?.nextBefore?.let { cursor -> item("more") { AdminActionRow(stringResource(R.string.sh_more), enabled = !busy, onClick = { run {
            val next = actions.invites(cursor)
            check(next.nextBefore != cursor)
            page = InvitePage((page?.items.orEmpty() + next.items).distinctBy { it.id }, next.nextBefore)
        } }) } }
        if (owner) item("self-check") { AdminCard {
            Text(stringResource(R.string.sh_self_check))
            AdminActionRow(stringResource(R.string.admin_refresh), enabled = !busy, onClick = { run { check = actions.selfCheck() } })
            check?.let { SelfCheckSentences(it) }
        } }
      }
    }
    revoke?.let { row -> AdminConfirmDialog(stringResource(R.string.sh_revoke), stringResource(R.string.sh_revoke_confirm),
        stringResource(R.string.sh_revoke), destructive = true, enabled = true, busy = busy,
        onConfirm = { run { actions.revoke(row.id); revoke = null; page = actions.invites() } }, onDismiss = { revoke = null }) }
    created?.let { value ->
        val link = remember(value, base) { runCatching { ServerInviteLink.create(base, value.code) }.getOrNull() }
        AlertDialog(onDismissRequest = { created = null }, title = { Text(stringResource(R.string.sh_create_invite)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.sh_code_once))
                Text(value.code)
                TextButton(onClick = { copy(value.code) }) { Text(stringResource(R.string.sh_copy_code)) }
                TextButton(onClick = { share(value.code) }) { Text(stringResource(R.string.sh_share_code)) }
                if (link != null) {
                    TextButton(onClick = { copy(link) }) { Text(stringResource(R.string.sh_copy_link)) }
                    TextButton(onClick = { share(link) }) { Text(stringResource(R.string.sh_share_link)) }
                    InviteLinkQr(link)
                }
            } }, confirmButton = { TextButton(onClick = { created = null }) { Text(stringResource(R.string.dismiss)) } })
    }
}

@Composable
private fun InviteLinkQr(link: String) {
    val matrix by produceState<com.google.zxing.common.BitMatrix?>(null, link) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                com.google.zxing.qrcode.QRCodeWriter().encode(link, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0)
            }.getOrNull()
        }
    }
    matrix?.let { bits -> androidx.compose.foundation.Canvas(Modifier.size(androidx.compose.ui.unit.Dp(200f))) {
        drawRect(androidx.compose.ui.graphics.Color.White)
        val unit = size.width / bits.width
        for (y in 0 until bits.height) for (x in 0 until bits.width) if (bits[x, y])
            drawRect(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.geometry.Offset(x * unit, y * unit), androidx.compose.ui.geometry.Size(unit, unit))
    } }
}

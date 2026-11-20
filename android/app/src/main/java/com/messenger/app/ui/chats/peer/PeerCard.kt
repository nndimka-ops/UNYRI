package com.messenger.app.ui.chats.peer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.identity.IdentityPolicy
import com.messenger.app.data.identity.IdentityProfile
import com.messenger.app.domain.model.CallMediaKind
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.ui.ProfileQrSheet
import com.messenger.app.ui.components.CocoBackButton
import com.messenger.app.ui.components.CocoPageHeader
import com.messenger.app.ui.components.UserAvatar
import com.messenger.app.ui.settings.SettingsSecondaryButton
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.launch

/** Opaque profile layer: the chat remains mounted and its read gate stays closed underneath. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PeerCard(peer: PublicUser, self: Boolean, blocked: Boolean, connected: Boolean,
    onClose: () -> Unit, onCall: (CallMediaKind) -> Unit, onVerify: () -> Unit) {
    val app = LocalContext.current.applicationContext as MessengerApp
    val colors = LocalCocoColors.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val owner = app.contactsRepository.ownerKey()
    val contacts = remember(owner) { app.contactsRepository.store }
    val ids by contacts.ids.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val nonMembers by contacts.nonMembers.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val changing by contacts.changing.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val revision by contacts.revision.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val own by app.identityRepository.identity.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    var identity by remember(peer.id) { mutableStateOf<IdentityProfile?>(null) }
    var unavailable by remember(peer.id) { mutableStateOf(false) }
    var failed by remember(peer.id) { mutableStateOf(false) }
    var qr by rememberSaveable(peer.id) { mutableStateOf(false) }
    var confirmRemove by rememberSaveable(peer.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(contacts, peer.id, revision) { contacts.check(peer) }
    LaunchedEffect(peer.id, blocked, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            identity = null; unavailable = false
            if (!blocked) {
                try { identity = app.identityRepository.profile(peer.id) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { unavailable = true }
            }
            try { kotlinx.coroutines.awaitCancellation() } finally { identity = null }
        }
    }
    val member = peer.id in ids
    val policy = peerCardActions(self, blocked, connected, identity != null && own != null,
        member || peer.id in nonMembers, member, peer.id in changing)
    fun changeContact(add: Boolean) {
        failed = false
        scope.launch { failed = !contacts.set(peer, add) }
    }
    BackHandler { if (confirmRemove) confirmRemove = false else if (qr) qr = false else onClose() }
    val title = stringResource(R.string.nav_profile)
    Surface(Modifier.fillMaxSize().testTag("peer-card").semantics { paneTitle = title }, color = colors.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            CocoPageHeader(title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), start = {
                CocoBackButton(onClick = onClose, modifier = Modifier.testTag("peer-card-back"))
            })
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                UserAvatar(peer.id, identity?.avatarVersion ?: peer.avatarVersion,
                    !blocked && (identity?.hasAvatar ?: peer.hasAvatar), peer.visibleName,
                    colors.surfaceVariant, colors.primaryText, size = 96.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(peer.visibleName, style = MaterialTheme.typography.headlineSmall, color = colors.primaryText)
                    identity?.let { profile ->
                        Text(IdentityPolicy.formattedNumber(profile.number), style = MaterialTheme.typography.bodyLarge, color = colors.secondaryText)
                        profile.username?.let { Text("@$it", color = colors.secondaryText) }
                    }
                    if (unavailable) Text(stringResource(R.string.search_no_results), color = colors.secondaryText)
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PeerAction(stringResource(R.string.contacts_write), CocoIcons.Chat, policy.message, "message", onClose)
                    PeerAction(stringResource(R.string.contacts_call), CocoIcons.AudioCall, policy.call, "call") { onCall(CallMediaKind.AUDIO) }
                    PeerAction(stringResource(R.string.contacts_video), CocoIcons.VideoCall, policy.call, "video") { onCall(CallMediaKind.VIDEO) }
                }
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surface) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        (identity?.bio ?: peer.bio.takeUnless { blocked })?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.primaryText) }
                        SettingsSecondaryButton(onClick = { qr = true }, enabled = policy.qr, modifier = Modifier.fillMaxWidth().testTag("peer-card-qr")) {
                            Text(stringResource(R.string.peer_qr_title))
                        }
                        SettingsSecondaryButton(onClick = onVerify, enabled = policy.verify, modifier = Modifier.fillMaxWidth().testTag("peer-card-trust")) {
                            Text(stringResource(R.string.verify_title))
                        }
                    }
                }
                if (!self) SettingsSecondaryButton(onClick = {
                    if (policy.confirmRemoval) confirmRemove = true else changeContact(true)
                }, enabled = policy.changeContact, destructive = member, modifier = Modifier.fillMaxWidth().testTag("peer-card-contact")) {
                    Text(stringResource(if (member) R.string.contacts_remove else R.string.contacts_add))
                }
                if (failed) Text(stringResource(R.string.contacts_change_failed), color = colors.danger)
            }
        }
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false },
        title = { Text(stringResource(R.string.peer_remove_confirm_title)) },
        text = { Text(stringResource(R.string.peer_remove_confirm_detail)) },
        confirmButton = { TextButton(onClick = { confirmRemove = false; changeContact(false) }) { Text(stringResource(R.string.contacts_remove), color = colors.danger) } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } })
    identity?.takeIf { qr && policy.qr }?.let { profile ->
        ProfileQrSheet(profile.displayName, profile.username, {}, { qr = false }, profile.number, own?.publicOrigin,
            title = stringResource(R.string.peer_qr_title), instruction = stringResource(R.string.peer_qr_instruction))
    }
}

@Composable
private fun PeerAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean,
    action: String, onClick: () -> Unit) {
    SettingsSecondaryButton(onClick, enabled = enabled, modifier = Modifier.testTag("peer-card-action-$action")) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

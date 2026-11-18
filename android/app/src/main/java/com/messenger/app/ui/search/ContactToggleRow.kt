package com.messenger.app.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.ui.theme.CocoHaptics
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.launch

/** The only observer on a peer profile; no address-book request is made here. */
@Composable
fun ContactToggleRow(user: PublicUser) {
    val app = LocalContext.current.applicationContext as MessengerApp
    val auth by app.authRepository.authState.collectAsState()
    val owner = app.contactsRepository.ownerKey()
    val store = remember(owner, auth) { app.contactsRepository.store }
    val ids by store.ids.collectAsState()
    val nonMembers by store.nonMembers.collectAsState()
    val changing by store.changing.collectAsState()
    val checking by store.checking.collectAsState()
    val revision by store.revision.collectAsState()
    val member = user.id in ids
    val busy = user.id in changing || (user.id !in ids && user.id !in nonMembers && user.id in checking)
    var failed by remember(store, user.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val colors = LocalCocoColors.current
    val added = stringResource(R.string.contacts_added)
    val removed = stringResource(R.string.contacts_removed)
    LaunchedEffect(store, user.id, revision) { store.check(user) }
    Column {
        Surface(color = colors.inputSurface, shape = com.messenger.app.ui.theme.CocoShapes.radiusCard,
            modifier = Modifier.fillMaxWidth().testTag("peer-profile-contact"), enabled = !busy,
            onClick = {
                failed = false
                val adding = !member
                scope.launch {
                    if (store.set(user, adding)) {
                        CocoHaptics.select(view)
                        view.announceForAccessibility(if (adding) added else removed)
                    } else failed = true
                }
            }) {
            Row(Modifier.heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp)) {
                    Icon(com.messenger.app.ui.settings.AuxiliaryIcons.Account, null, tint = colors.secondaryText, modifier = Modifier.size(22.dp))
                    Text(if (member) "−" else "+", color = colors.primaryText,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.BottomEnd))
                }
                Text(stringResource(if (member) R.string.contacts_remove else R.string.contacts_add), Modifier.weight(1f), color = colors.primaryText)
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        if (failed) Text(stringResource(R.string.contacts_change_failed), color = colors.danger,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

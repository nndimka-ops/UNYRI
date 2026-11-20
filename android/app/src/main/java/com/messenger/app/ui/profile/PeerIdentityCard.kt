package com.messenger.app.ui.profile

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.identity.IdentityProfile
import com.messenger.app.domain.model.PublicUser

/** A chat is not permission to reveal a profile. Hidden/missing/blocked all stay unavailable. */
@Composable
internal fun PeerIdentityCard(peer: PublicUser) {
    val app = LocalContext.current.applicationContext as MessengerApp
    val legacy by app.identityRepository.legacy.collectAsState()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var profile by remember(peer.id) { mutableStateOf<IdentityProfile?>(null) }
    var failed by remember(peer.id) { mutableStateOf(false) }
    var qr by remember(peer.id) { mutableStateOf(false) }
    LaunchedEffect(peer.id, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            profile = null; failed = false
            try { profile = app.identityRepository.profile(peer.id) }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { failed = true }
            try { kotlinx.coroutines.awaitCancellation() } finally { profile = null; qr = false }
        }
    }
    Column {
        if (legacy) Text(com.messenger.app.ui.publicHandle(peer))
        profile?.takeUnless { legacy }?.let { current ->
            Text(com.messenger.app.data.identity.IdentityPolicy.formattedNumber(current.number))
            com.messenger.app.data.identity.PeerIdentityPolicy.handle(current.username, null).takeIf { it.isNotEmpty() }?.let { Text(it) }
            TextButton(onClick = { qr = true }) { Text(stringResource(R.string.sidebar_qr_title)) }
            val own by app.identityRepository.identity.collectAsState()
            if (qr && own != null) com.messenger.app.ui.ProfileQrSheet(current.displayName, current.username, {}, { qr = false },
                current.number, own!!.publicOrigin)
        }
        if (failed) Text(stringResource(R.string.search_no_results))
    }
}

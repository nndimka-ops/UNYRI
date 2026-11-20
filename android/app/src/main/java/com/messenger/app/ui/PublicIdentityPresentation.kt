package com.messenger.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.messenger.app.MessengerApp
import com.messenger.app.domain.model.PublicUser

internal fun publicHandleText(number: String?, username: String?, oldLogin: String, legacy: Boolean): String =
    com.messenger.app.data.identity.PeerIdentityPolicy.handle(username, number, oldLogin, legacy)

@Composable
internal fun publicHandle(number: String?, username: String?, oldUsername: String = ""): String {
    val legacy by (LocalContext.current.applicationContext as MessengerApp).identityRepository.legacy.collectAsState()
    return publicHandleText(number, username, oldUsername, legacy)
}

/** B-051: the Contacts row caption (grouped number, then the public @username). */
@Composable
internal fun contactCaption(user: PublicUser): String {
    val legacy by (LocalContext.current.applicationContext as MessengerApp).identityRepository.legacy.collectAsState()
    return com.messenger.app.data.identity.PeerIdentityPolicy.contactCaption(user.publicUsername, user.publicNumber, legacy)
}

/** The legacy compatibility username is never a peer identifier, on any server. */
@Composable
internal fun publicHandle(user: PublicUser): String {
    val legacy by (LocalContext.current.applicationContext as MessengerApp).identityRepository.legacy.collectAsState()
    return publicHandleText(user.publicNumber, user.publicUsername, user.username, legacy)
}

@Composable
internal fun publicHandle(user: com.messenger.app.data.api.PublicUserDto): String {
    val legacy by (LocalContext.current.applicationContext as MessengerApp).identityRepository.legacy.collectAsState()
    return publicHandleText(user.publicNumber, user.publicUsername, user.username, legacy)
}

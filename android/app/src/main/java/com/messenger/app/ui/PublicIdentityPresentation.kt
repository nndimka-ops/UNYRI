package com.messenger.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.messenger.app.MessengerApp
import com.messenger.app.domain.model.PublicUser

internal fun publicHandleText(number: String?, username: String?, oldLogin: String, legacy: Boolean): String = when {
    !legacy && number != null -> username?.let { "@$it" } ?: com.messenger.app.data.identity.IdentityPolicy.formattedNumber(number)
    legacy && oldLogin.isNotEmpty() -> "@$oldLogin"
    else -> ""
}

/** Old chat/contact payloads still contain logins in step 2. Never render them on a new server. */
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

package com.messenger.app.ui

import com.messenger.app.data.auth.SavedLocalAccount

internal fun drawerBadge(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

internal sealed interface DrawerAccountAction {
    data class Switch(val account: SavedLocalAccount) : DrawerAccountAction
    data object Add : DrawerAccountAction
    data object None : DrawerAccountAction
}

/** The card is the active account. Never render an add control beside another account. */
internal fun drawerAccountAction(accounts: List<SavedLocalAccount>, activeKey: String, canAdd: Boolean): DrawerAccountAction {
    val other = accounts.distinctBy { it.key }.firstOrNull { it.key != activeKey }
    return if (other != null) DrawerAccountAction.Switch(other)
    else if (accounts.distinctBy { it.key }.size < 2 && canAdd) DrawerAccountAction.Add
    else DrawerAccountAction.None
}

internal data class DrawerBadges(val chats: Int = 0, val archive: Int = 0, val missed: Int = 0, val transfers: Int = 0)

package com.messenger.app.ui.chats

object ChatReadPolicy {
    fun readable(resumed: Boolean, allowed: Boolean, covered: Boolean): Boolean = resumed && allowed && !covered
}

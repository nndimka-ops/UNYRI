package com.messenger.app.data.chat

class ChatBindingException : IllegalStateException("Conversation identity changed; sending is disabled")

/** Absence is privacy redaction, never a contradictory identity. */
internal object DirectChatBinding {
    fun contradicts(oldType: String?, oldPeer: String?, type: String, peer: String?): Boolean =
        (oldType != null && oldType != type) || (oldPeer != null && peer != null && oldPeer != peer)
}

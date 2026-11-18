package com.messenger.app.security

/** The repository checks live Activity/lock state, independent of Compose scheduling. */
class MessageReadVisibility {
    private val monitor = Any()
    private var owner: Any? = null
    private var visible: () -> Boolean = { false }
    private var transport: () -> Boolean = { false }
    private var chatOwner: Any? = null
    private var chatReadable: () -> Boolean = { true }
    fun bind(owner: Any, transport: (() -> Boolean)? = null, visible: () -> Boolean) = synchronized(monitor) {
        this.owner = owner; this.visible = visible; this.transport = transport ?: visible
    }
    fun release(owner: Any) = synchronized(monitor) { if (this.owner === owner) { this.owner = null; visible = { false }; transport = { false } } }
    fun bindChat(owner: Any, readable: () -> Boolean) = synchronized(monitor) { chatOwner = owner; chatReadable = readable }
    fun releaseChat(owner: Any) = synchronized(monitor) { if (chatOwner === owner) { chatOwner = null; chatReadable = { true } } }
    fun allowsViewedCursor(): Boolean = synchronized(monitor) { visible() && chatReadable() }
    fun allowsReadTransport(): Boolean = synchronized(monitor) { owner != null && transport() }
}

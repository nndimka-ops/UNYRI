package com.messenger.app.security

/** The repository checks live Activity/lock state, independent of Compose scheduling. */
class MessageReadVisibility {
    private val monitor = Any()
    private var owner: Any? = null
    private var visible: () -> Boolean = { false }
    fun bind(owner: Any, visible: () -> Boolean) = synchronized(monitor) { this.owner = owner; this.visible = visible }
    fun release(owner: Any) = synchronized(monitor) { if (this.owner === owner) { this.owner = null; visible = { false } } }
    fun allowsViewedCursor(): Boolean = synchronized(monitor) { visible() }
}

package com.messenger.app.ui.chats

/** Defer timeline changes until the viewer has finished its return transition. */
internal class MediaPresentationHold<T : Any> {
    private var value: T? = null
    fun display(covered: Boolean, update: () -> T): T {
        if (covered) value?.let { return it }
        return update().also { value = it }
    }
}

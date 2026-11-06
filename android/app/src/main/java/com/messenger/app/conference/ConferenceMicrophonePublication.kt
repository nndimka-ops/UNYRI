package com.messenger.app.conference

/** Acquire a disabled source only with permission. Revalidate ownership/encryption after
 * the SDK await, before treating a concurrent owner restriction as a muted success. */
internal suspend fun <T> publishConferenceMicrophoneIfAllowed(
    validate: suspend () -> Unit,
    blocked: () -> Boolean,
    createDisabled: () -> T,
    publish: suspend (T) -> Boolean,
    onPublished: (T) -> Unit,
    silence: () -> Unit,
    isOwned: (T) -> Boolean,
    dispose: (T) -> Unit,
    requested: () -> Boolean = { true },
) {
    validate()
    if (blocked()) { silence(); return }
    // Muted entry/recovery needs no publisher or microphone source at all.
    if (!requested()) return
    val source = createDisabled()
    try {
        val published = publish(source)
        validate()
        if (blocked()) { silence(); return }
        check(published) { "Microphone publication failed" }
        onPublished(source)
    } finally {
        if (!isOwned(source)) dispose(source)
    }
}

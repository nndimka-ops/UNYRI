package com.messenger.app.data.attachment

/** File URIs can be temporary too. Only an owned draft reference may bypass durable staging. */
internal suspend fun <T> durableQueueSource(source: T, owned: Boolean, stage: suspend (T) -> T): T =
    if (owned) source else stage(source)

/** Cleanup checks persistent references, so even a cancellation after DB commit cannot erase an outbox source. */
internal suspend fun <T> queueDurableMedia(source: T, owned: Boolean, stage: suspend (T) -> T,
    discardUnreferenced: suspend (T) -> Unit, commit: suspend (T) -> Unit) {
    val durable = durableQueueSource(source, owned, stage)
    try { commit(durable) }
    catch (failure: Throwable) {
        if (!owned) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            try { discardUnreferenced(durable) } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
        }
        throw failure
    }
}

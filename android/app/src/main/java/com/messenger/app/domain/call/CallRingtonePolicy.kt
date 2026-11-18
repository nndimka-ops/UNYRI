package com.messenger.app.domain.call

/** A platform default URI is symbolic even when its actual default is None.
 * Notification admission and foreground playback must use the resolved sound.
 * The lookups are injected so the platform-derived silent-default case runs on the JVM. */
object CallRingtonePolicy {
    fun <T : Any> resolve(sound: T?, defaultType: (T) -> Int, actualDefault: (Int) -> T?): T? {
        sound ?: return null
        val type = defaultType(sound)
        return if (type >= 0) actualDefault(type) else sound
    }
}

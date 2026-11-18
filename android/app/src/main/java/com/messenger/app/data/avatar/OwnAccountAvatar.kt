package com.messenger.app.data.avatar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.messenger.app.domain.model.UserProfile
import java.io.ByteArrayOutputStream

/** Called only for canonical own profiles and bytes from the existing avatar loader. */
class OwnAccountAvatar(
    private val store: AccountAvatarStore,
    private val slot: String,
    private val current: (String, Long) -> Boolean,
    private val encode: (ByteArray) -> ByteArray? = ::accountAvatarThumbnail,
) {
    private var ticket: AccountAvatarStore.Ticket? = null
    private var version = -1L
    private var user = ""
    private var present = false
    private var completed: AccountAvatarStore.Ticket? = null

    @Synchronized fun retire() {
        ticket = null; completed = null
        store.purge(slot)
    }

    @Synchronized fun profile(profile: UserProfile, origin: String) {
        if (!current(profile.id, profile.avatarVersion)) return
        val owner = AccountAvatarStore.owner(origin, profile.id)
        val revision = "${profile.avatarVersion}|${profile.hasAvatar}"
        if (ticket?.owner == owner && ticket?.revision == revision) return
        user = profile.id; version = profile.avatarVersion; present = profile.hasAvatar
        completed = null
        ticket = store.begin(slot, owner, revision)
        if (!present) store.write(null, ticket!!)
        else if (store.read(slot, owner)?.revision == revision) completed = ticket
    }

    @Synchronized fun resolved(id: String, avatarVersion: Long, bytes: ByteArray?) {
        val captured = ticket ?: return
        if (id != user || avatarVersion != version || !present || completed == captured || bytes == null || !current(id, avatarVersion)) return
        val jpeg = encode(bytes) ?: return
        if (!current(id, avatarVersion)) return
        store.write(jpeg, captured)
        completed = captured
    }
}

private fun accountAvatarThumbnail(bytes: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val scale = minOf(1f, 128f / maxOf(bitmap.width, bitmap.height))
    val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    return try { ByteArrayOutputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it); it.toByteArray() } }
    finally { if (small !== bitmap) small.recycle(); bitmap.recycle() }
}

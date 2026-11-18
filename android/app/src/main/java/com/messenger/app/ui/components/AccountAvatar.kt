package com.messenger.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.messenger.app.data.auth.SavedLocalAccount
import com.messenger.app.data.avatar.AccountAvatarStore
import com.messenger.app.data.avatar.AccountAvatarMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object AccountAvatarImages {
    private val images = AccountAvatarMemory<Bitmap>()

    fun clear() = images.clear()
    fun peek(slot: String, owner: String): Bitmap? = images.peek(slot, owner)

    fun load(store: AccountAvatarStore, slot: String, owner: String): Bitmap? =
        images.load(slot, owner, read = { store.read(slot, owner) }, decode = ::decode)

    private fun decode(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** No session, repository or network access for an inactive account. */
@Composable
internal fun AccountAvatar(account: SavedLocalAccount, store: AccountAvatarStore, size: Dp, ink: Color) {
    val owner = AccountAvatarStore.owner(account.origin, account.userId)
    key(account.key, owner) {
        val bitmap by produceState(AccountAvatarImages.peek(account.key, owner), store, account.key, owner) {
            if (value == null) value = withContext(Dispatchers.IO) { runCatching { AccountAvatarImages.load(store, account.key, owner) }.getOrNull() }
        }
        Box(Modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
            val loaded = bitmap
            if (loaded != null) Image(loaded.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
            else Text(account.displayName.take(1).uppercase(), color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

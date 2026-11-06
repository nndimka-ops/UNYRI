package com.messenger.app.ui.chats

import androidx.compose.ui.text.font.FontFamily

/** Native system families only: no downloaded fonts, disk access or per-message typeface creation. */
internal fun savedChatFontFamily(id: String): FontFamily = when (id) {
    "serif" -> FontFamily.Serif
    "monospace" -> FontFamily.Monospace
    // Older 83-key exports have no family setting and keep the existing device font.
    else -> FontFamily.Default
}

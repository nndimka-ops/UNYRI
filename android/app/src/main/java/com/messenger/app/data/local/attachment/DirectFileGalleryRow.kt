package com.messenger.app.data.local.attachment

import androidx.room.Embedded
import com.messenger.app.data.local.message.MessageEntity

data class DirectFileGalleryRow(@Embedded val message: MessageEntity, val directRecord: String, val directName: String)

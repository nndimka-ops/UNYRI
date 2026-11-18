package com.messenger.app.data.attachment
import kotlin.test.*
class MediaDownloadPolicyTest {
    @Test fun gatesUnknownZeroOversizeAndDisabledButIncludesExactLimit() {
        assertFalse(MediaDownloadPolicy.allows(DownloadNetwork.UNAVAILABLE, 1, 100))
        assertFalse(MediaDownloadPolicy.allows(DownloadNetwork.WIFI, -1, 100))
        assertFalse(MediaDownloadPolicy.allows(DownloadNetwork.WIFI, 0, 100))
        assertFalse(MediaDownloadPolicy.allows(DownloadNetwork.CELLULAR, 101, 100))
        assertFalse(MediaDownloadPolicy.allows(DownloadNetwork.WIFI, 1, 0))
        assertTrue(MediaDownloadPolicy.allows(DownloadNetwork.WIFI, 100, 100))
        assertTrue(MediaDownloadPolicy.allows(DownloadNetwork.CELLULAR, 100, 100))
    }
    @Test fun recommendedDefaultsMatchIOSAndKeepVideosManual() {
        assertEquals(50, MediaDownloadPolicy.defaultLimitMb(DownloadNetwork.WIFI, DownloadKind.PHOTO))
        assertEquals(50, MediaDownloadPolicy.defaultLimitMb(DownloadNetwork.WIFI, DownloadKind.VOICE))
        assertEquals(25, MediaDownloadPolicy.defaultLimitMb(DownloadNetwork.WIFI, DownloadKind.FILE))
        assertEquals(5, MediaDownloadPolicy.defaultLimitMb(DownloadNetwork.CELLULAR, DownloadKind.PHOTO))
        assertEquals(5, MediaDownloadPolicy.defaultLimitMb(DownloadNetwork.CELLULAR, DownloadKind.VOICE))
        assertEquals(0, MediaDownloadPolicy.defaultLimitMb(DownloadNetwork.CELLULAR, DownloadKind.FILE))
        DownloadNetwork.entries.forEach { assertEquals(0, MediaDownloadPolicy.defaultLimitMb(it, DownloadKind.VIDEO)) }
        DownloadKind.entries.forEach { assertEquals(0, MediaDownloadPolicy.defaultLimitMb(DownloadNetwork.UNAVAILABLE, it)) }
    }
    @Test fun distinguishesVoicePhotoVideoAndFilesFromMetadata() {
        assertEquals(DownloadKind.VOICE, MediaDownloadPolicy.kind("voice", "audio/ogg"))
        assertEquals(DownloadKind.PHOTO, MediaDownloadPolicy.kind("image", "image/jpeg"))
        assertEquals(DownloadKind.VIDEO, MediaDownloadPolicy.kind("file", "video/mp4"))
        assertEquals(DownloadKind.FILE, MediaDownloadPolicy.kind("file", "application/pdf"))
    }
}

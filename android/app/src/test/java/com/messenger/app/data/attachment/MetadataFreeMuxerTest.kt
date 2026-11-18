package com.messenger.app.data.attachment

import android.media.MediaCodec
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4LocationData
import androidx.media3.muxer.Muxer
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

@androidx.annotation.OptIn(UnstableApi::class)
class MetadataFreeMuxerTest {
    private class Recording : Muxer {
        val calls = mutableListOf<String>()
        val token = object : Muxer.TrackToken {}
        override fun addTrack(format: Format): Muxer.TrackToken { calls += "track:${format.sampleMimeType}"; return token }
        override fun writeSampleData(trackToken: Muxer.TrackToken, byteBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) { calls += "sample" }
        override fun addMetadataEntry(metadataEntry: Metadata.Entry) { calls += "metadata:${metadataEntry.javaClass.simpleName}" }
        override fun close() { calls += "close" }
    }

    @Test
    fun `a converted video never receives the source location or any other container metadata`() {
        val recording = Recording()
        val muxer = MetadataFreeMuxer(recording)
        val token = muxer.addTrack(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())
        muxer.addMetadataEntry(Mp4LocationData(48.8584f, 2.2945f))
        muxer.writeSampleData(token, ByteBuffer.allocate(4), MediaCodec.BufferInfo())
        muxer.close()

        assertSame(recording.token, token)
        assertEquals(listOf("track:${MimeTypes.VIDEO_H264}", "sample", "close"), recording.calls)
    }

    @Test
    fun `the factory wraps every muxer it creates and keeps the codec support of the default one`() {
        val created = Recording()
        val delegate = object : Muxer.Factory {
            override fun create(path: String): Muxer { assertEquals("/out.mp4", path); return created }
            override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = ImmutableList.of(MimeTypes.VIDEO_H264)
        }
        val factory = MetadataFreeMuxer.Factory(delegate)

        val muxer = factory.create("/out.mp4")
        muxer.addMetadataEntry(Mp4LocationData(1f, 2f))

        assertTrue(muxer is MetadataFreeMuxer)
        assertTrue(created.calls.isEmpty())
        assertEquals(listOf(MimeTypes.VIDEO_H264), factory.getSupportedSampleMimeTypes(2))
    }
}

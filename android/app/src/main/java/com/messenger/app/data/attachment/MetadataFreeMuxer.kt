package com.messenger.app.data.attachment

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.DefaultMuxer
import com.google.common.collect.ImmutableList

/**
 * Privacy boundary for converted videos. Media3's MuxerWrapper forwards every
 * container metadata entry of the source track to the muxer, and FrameworkMuxer
 * turns `Mp4LocationData` into `MediaMuxer.setLocation`: a video shared from an
 * app that hands over unredacted bytes would carry where it was filmed. A sent
 * video keeps only its tracks; orientation travels in the track format, not here.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class MetadataFreeMuxer(private val delegate: Muxer) : Muxer by delegate {
    override fun addMetadataEntry(metadataEntry: Metadata.Entry) = Unit

    class Factory(private val delegate: Muxer.Factory = DefaultMuxer.Factory()) : Muxer.Factory {
        override fun create(path: String): Muxer = MetadataFreeMuxer(delegate.create(path))
        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> =
            delegate.getSupportedSampleMimeTypes(trackType)
    }
}

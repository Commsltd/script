package com.commercialoutcomes.uktelevision

import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.net.URI

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object PlaybackMediaSources {
    fun create(http: DataSource.Factory, item: MediaItem, policy: LoadErrorHandlingPolicy): MediaSource {
        val host = runCatching { URI(item.localConfiguration!!.uri.toString()).host }.getOrNull()
        // Publica declares HE-AAC in the master while the segments initially
        // identify LC-AAC. Chunkless preparation then yields video with no
        // selected audio. Probe actual segments on this provider only.
        return if (host == "getpublica.com" || host?.endsWith(".getpublica.com") == true)
            HlsMediaSource.Factory(http).setAllowChunklessPreparation(false)
                .setLoadErrorHandlingPolicy(policy).createMediaSource(item)
        else DefaultMediaSourceFactory(http).setLoadErrorHandlingPolicy(policy).createMediaSource(item)
    }
}

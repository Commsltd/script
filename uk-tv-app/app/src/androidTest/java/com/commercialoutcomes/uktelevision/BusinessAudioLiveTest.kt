package com.commercialoutcomes.uktelevision

import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File

/** Opt-in network diagnostic: confirms actual audio output, not merely a declared track. */
class BusinessAudioLiveTest {
    @Test fun reportsAudioOutputForPublicaAndAlternative() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("businessAudio") == "true")
        val context = instrumentation.targetContext
        val report = StringBuilder()
        for (url in listOf(
            "https://stream-us-east-1.getpublica.com/playlist.m3u8?network_id=2220",
            "https://stream-us-east-1.getpublica.com/playlist.m3u8?network_id=2506",
            "https://a-cdn.klowdtv.com/live3/entrepreneurtv_720p/playlist.m3u8"
        )) for (chunkless in listOf(true, false)) {
            val audio = CountDownLatch(1)
            var player: ExoPlayer? = null
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build().also { exo ->
                    exo.addAnalyticsListener(object : AnalyticsListener {
                        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) { audio.countDown() }
                    })
                    exo.setMediaSource(if (chunkless) HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
                        .createMediaSource(MediaItem.fromUri(url)) else PlaybackMediaSources.create(
                            DefaultHttpDataSource.Factory(), MediaItem.fromUri(url), DefaultLoadErrorHandlingPolicy()))
                    exo.prepare(); exo.play()
                }
            }
            val advancing = audio.await(30, TimeUnit.SECONDS)
            instrumentation.runOnMainSync {
                report.append("$url chunkless=$chunkless audio=$advancing format=${player?.audioFormat} error=${player?.playerError}\n")
                player?.release()
            }
            File(context.getExternalFilesDir(null), "business-audio.txt").writeText(report.toString())
            if (!chunkless) assertTrue("No audio output with app source factory: $report", advancing)
        }
    }
}

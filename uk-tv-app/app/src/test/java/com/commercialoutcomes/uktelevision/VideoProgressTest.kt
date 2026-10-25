package com.commercialoutcomes.uktelevision

import org.junit.Assert.*
import org.junit.Test

class VideoProgressTest {
    @Test fun advancingAudioCannotHideFrozenVideo() {
        val progress = VideoProgress()
        assertNull(progress.sample(0, 100, true, true))
        assertNull(progress.sample(5000, 100, true, true))
        assertEquals("VIDEO_STALLED", progress.sample(6000, 100, true, true))
    }
    @Test fun missingVideoTriggersRecovery() {
        val progress = VideoProgress()
        progress.sample(0, 0, true, false)
        assertEquals("VIDEO_MISSING", progress.sample(6000, 0, true, false))
        assertFalse(RecoveryRules.retryable("VIDEO_MISSING"))
        assertFalse(RecoveryRules.retryable("VIDEO_STALLED"))
    }
    @Test fun pausedOrBufferingVideoDoesNotFailAndResumeGetsFreshGrace() {
        val progress = VideoProgress()
        progress.sample(0, 100, true, true)
        assertNull(progress.sample(20000, 100, false, true))
        assertNull(progress.sample(21000, 100, true, true))
        assertNull(progress.sample(22000, 125, true, true))
        assertNull(progress.sample(27000, 250, true, true))
    }
    @Test fun DecoderResetAndContinuingFramesAreNotAStall() {
        val progress = VideoProgress()
        progress.sample(0, 100, true, true)
        assertNull(progress.sample(7000, 200, true, true))
        assertNull(progress.sample(14000, 1, true, true))
        assertNull(progress.sample(15000, 25, true, true))
    }
}

package com.commercialoutcomes.uktelevision
import org.junit.Assert.*
import org.junit.Test
class RecoveryRulesTest {
    @Test fun retryBudgetIsBoundedAndRejectsPermanentErrors() {
        assertEquals(listOf(1500L,4000L,8000L,null), (0..3).map { RecoveryRules.retryDelay(it) })
        assertFalse(RecoveryRules.retryable("HTTP_403")); assertFalse(RecoveryRules.retryable("HTTP_404"))
        assertFalse(RecoveryRules.retryable("DNS_UNAVAILABLE")); assertTrue(RecoveryRules.retryable("HTTP_503"))
        assertTrue(RecoveryRules.retryable("START_OR_BUFFER_TIMEOUT"))
    }
    @Test fun failurePenaltyExpiresWithoutPermanentlyExcludingSource() {
        val now=1800000000000L
        val recent=StreamHealth("s","p",failures=4,lastFailure=now-1000)
        assertTrue(GuideRules.healthScore(recent,now)<0)
        assertEquals(0L,GuideRules.healthScore(recent,now+GuideRules.FAILURE_PENALTY_WINDOW+1))
    }
    @Test fun youtubeIdentityRejectsRecordedOrUnrelatedVideos() {
        val details="\"videoDetails\":{\"videoId\":\"ABCDEFGHIJK\"}"
        assertNull(YouTubeIdentity.currentLiveId("var ytInitialPlayerResponse = {$details};"))
        val live="\"microformat\":{\"playerMicroformatRenderer\":{\"liveBroadcastDetails\":{\"isLiveNow\":true}}}"
        assertEquals("ABCDEFGHIJK",YouTubeIdentity.currentLiveId("var ytInitialPlayerResponse = {$details,$live}; more"))
        assertNull(YouTubeIdentity.currentLiveId("{\"videoId\":\"ABCDEFGHIJK\"}"))
    }
}

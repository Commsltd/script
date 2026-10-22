package com.commercialoutcomes.uktelevision

object RecoveryRules {
    fun retryDelay(attempt: Int): Long? = listOf(1500L, 4000L, 8000L).getOrNull(attempt)
    fun retryable(reason: String): Boolean = when {
        reason.startsWith("HTTP_5") -> true
        reason == "HTTP_408" || reason == "HTTP_429" -> true
        reason.startsWith("HTTP_") -> false
        reason.contains("PARSING") || reason.contains("DECODING") || reason.contains("DRM") -> false
        reason == "DNS_UNAVAILABLE" || reason == "VIDEO_STALLED" || reason == "VIDEO_MISSING" -> false
        else -> true
    }
    fun explain(reason: String): String = when {
        reason == "DNS_UNAVAILABLE" -> "The stream address cannot be found."
        reason == "HTTP_401" || reason == "HTTP_403" -> "The stream provider refused playback (access or regional restriction)."
        reason == "HTTP_404" || reason == "HTTP_410" -> "The stream link is no longer available."
        reason.startsWith("HTTP_5") -> "The stream provider is temporarily unavailable."
        reason.contains("PARSING") -> "The source returned an invalid video playlist."
        reason.contains("DECOD") -> "This stream could not be decoded on this device."
        reason.contains("DRM") -> "This source requires supported provider authentication."
        reason == "START_OR_BUFFER_TIMEOUT" -> "The stream stopped responding."
        reason == "NETWORK_UNAVAILABLE" -> "The network or VPN connection was interrupted."
        reason == "STREAM_ENDED" -> "The live stream ended."
        reason == "VIDEO_STALLED" -> "The picture stopped updating."
        reason == "VIDEO_MISSING" -> "This feed is playing sound without a picture."
        else -> "Playback was interrupted."
    }
}

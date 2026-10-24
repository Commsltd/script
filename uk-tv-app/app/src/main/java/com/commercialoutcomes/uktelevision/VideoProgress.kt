package com.commercialoutcomes.uktelevision

/** Uses rendered frames, not the audio clock, to recognise a picture failure. */
class VideoProgress(private val timeoutMs: Long = 6000) {
    private var frames = -1
    private var lastProgress: Long? = null
    fun sample(now: Long, rendered: Int, playing: Boolean, videoSelected: Boolean): String? {
        if (!playing || lastProgress == null || rendered != frames) {
            frames = rendered
            lastProgress = now
            return null
        }
        if (now - lastProgress!! < timeoutMs) return null
        return if (videoSelected) "VIDEO_STALLED" else "VIDEO_MISSING"
    }
}

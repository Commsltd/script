package com.commercialoutcomes.uktelevision

/** Only complete, unencrypted feature/episode playlists can replace a web player.
 * A trailer or short advert must never be mistaken for the selected title. */
object VodHls {
    fun matches(playlist:String,duration:Double):Boolean {
        if(!duration.isFinite()||duration<600 || !playlist.startsWith("#EXTM3U") ||
            !playlist.contains("#EXT-X-ENDLIST") || playlist.contains("#EXT-X-KEY") ||
            playlist.contains("#EXT-X-SESSION-KEY") || playlist.contains("#EXT-X-DATERANGE"))return false
        val lengths=Regex("(?m)^#EXTINF:([0-9.]+)").findAll(playlist).mapNotNull{it.groupValues[1].toDoubleOrNull()}.toList()
        return lengths.isNotEmpty() && kotlin.math.abs(lengths.sum()-duration)<10
    }
}

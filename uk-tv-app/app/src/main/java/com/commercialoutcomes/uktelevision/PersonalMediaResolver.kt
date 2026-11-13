package com.commercialoutcomes.uktelevision

import org.json.JSONObject
import org.jsoup.Jsoup

/** Resolves fresh public player routes; never stores expiring camera tokens in preferences. */
object PersonalMediaResolver {
    fun parse(kind:String,body:String):String {
        val url=when(kind){
            "pixelcaster"->JSONObject(body).getString("signed_url")
            "hdontap"->JSONObject(Jsoup.parse(body).selectFirst("script#player-data")?.data() ?: error("Missing camera data")).getString("streamSrc")
            else->error("Unsupported camera resolver")
        }
        require(WebAddressPolicy.publicHttps(url)){"Camera returned an unsafe URL"}
        return url
    }
}

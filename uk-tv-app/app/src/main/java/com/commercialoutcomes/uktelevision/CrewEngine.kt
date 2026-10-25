package com.commercialoutcomes.uktelevision

import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import kotlinx.coroutines.*
import org.json.JSONObject

/** Kodi needs foreground initialisation on this Firestick; reuse a warm engine. */
object CrewEngine {
    suspend fun start(activity: Activity, token: String): JSONObject {
        suspend fun status(): JSONObject? = try {
            withContext(Dispatchers.IO) { CrewBridgeClient.call(token, "/status", JSONObject()) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        status()?.let { return it }
        val launch=activity.packageManager.getLaunchIntentForPackage("org.xbmc.kodi")
            ?: error("Kodi is not installed on this Firestick.")
        require(token.length>=32){"The Crew is not configured on this Firestick."}
        // This installed Kodi exposes Main. Starting it directly avoids Splash
        // launching a second activity after we have returned to UK TV.
        val main=ComponentName("org.xbmc.kodi","org.xbmc.kodi.Main")
        val direct=runCatching { activity.packageManager.getActivityInfo(main,0).exported }.getOrDefault(false)
        if(direct)launch.component=main
        activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        try {
            return withTimeout(40000) {
                while(true) { status()?.let { return@withTimeout it }; delay(600) }
                @Suppress("UNREACHABLE_CODE") error("Unreachable")
            }
        } finally {
            if(!activity.isFinishing)activity.startActivity(Intent(activity,activity.javaClass)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
    }
}

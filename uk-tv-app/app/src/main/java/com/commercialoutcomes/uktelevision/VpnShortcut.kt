package com.commercialoutcomes.uktelevision

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.Toast

object VpnShortcut {
    const val PACKAGE="com.expressvpn.vpn"
    fun country(id:String):String? = when(id.substringBefore('@').substringAfterLast('.')) {
        "uk"->"United Kingdom";"us"->if(id.substringAfter('@')=="Europe")null else "United States"
        "ca"->"Canada";"au"->"Australia";"nz"->"New Zealand";"ie"->"Ireland";"tt"->"Trinidad and Tobago";else->null
    }
    fun open(activity:Activity,label:String,id:String="",onLeaving:()->Unit={}) {
        val country=country(id)
        AlertDialog.Builder(activity).setTitle("VPN / country · $label")
            .setMessage((country?.let{"This channel’s home country is $it. A regional restriction has not been confirmed.\n\n"}?:"")+"Choose a location in ExpressVPN and wait until it is connected. Select the UK Television icon in ExpressVPN’s shortcut row to return. If it is missing, use ExpressVPN’s + button to add UK Television. Your selection is kept.\n\nA VPN cannot fix an off-air broadcast, a broken source or a provider sign-in requirement. Some services also block VPN addresses.")
            .setPositiveButton("Open ExpressVPN"){_,_->
                val launch=activity.packageManager.getLeanbackLaunchIntentForPackage(PACKAGE)
                    ?:activity.packageManager.getLaunchIntentForPackage(PACKAGE)
                if(launch==null)Toast.makeText(activity,"ExpressVPN is not installed",Toast.LENGTH_LONG).show()
                else runCatching { launch.flags=0;onLeaving();activity.startActivity(launch) }.onFailure {Toast.makeText(activity,"ExpressVPN could not be opened",Toast.LENGTH_LONG).show()}
            }.setNegativeButton("Back",null).show()
    }
}

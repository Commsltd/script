package com.commercialoutcomes.uktelevision

/** Presentation only: keeps the same channel and source identities. */
object SportsSections {
    private val sections = listOf(
        "General sport" to "FASTfoxsportsfast.us FuboSportsNetwork.us NBCSportsNOW.us FASTrokusportschannel.us ScrippsSportsNetwork.us SportsGrid.us talkSPORT.uk TNTSports1.uk TNTSports2.uk TNTSports3.uk TNTSports4.uk WomensSportsNetwork.us",
        "Football" to "FASTcbssportsgolazonetwork.us FIFAPlus.uk FIFAPlusWomen.uk FASTgfnfootball.uk MUTV.uk FASTuefachampionsleaguefast.us",
        "Rugby" to "RugbyPassTV.uk",
        "American & college sports" to "ACCDigitalNetwork.us FASTbig12studios.us FASTmlbchannel.us NFLChannel.us Pac12Insider.us SkySportsNFL.uk FASTthenbachannel.us",
        "Motor racing & motoring" to "FASTdonutmedia.us FASThagerty.us FASTmotogpchannel.us NASCARChannel.us NHRATV.us FASTpowernation.us RacerSelect.us FASTrallytvfastplus.uk SkySportsF1.uk FASTtorque.us",
        "Golf" to "FASTgolflife.uk FASTgolfpass.us PGATour.us",
        "Tennis" to "TennisChannel2.us",
        "Combat & wrestling" to "DAZNCombat.uk FASTdaznringside.uk PFLMMA.us FASTtnawrestling.us FASTtoprankclassics.us FASTwrestlinglegendstv.uk",
        "Riding, rodeo & bullfighting" to "HorseCountryTV.uk PBRRidePass.us TDTextremaduratoroscanalextremadura.es",
        "Poker & cue sports" to "BilliardTV.us PokerGo.us FASTtritonpoker.us WorldPokerTour.us",
        "Strength & adventure" to "StrongmanChampionsLeague.uk Strongman.us RedBullTV.at"
    )
    private val membership = sections.flatMapIndexed { index, entry ->
        entry.second.split(" ").map { it to index }
    }.toMap()
    fun rank(id: String): Int = membership[id.substringBefore('@')] ?: sections.size
    fun label(id: String): String = sections.getOrNull(rank(id))?.first ?: "Other sport"
}

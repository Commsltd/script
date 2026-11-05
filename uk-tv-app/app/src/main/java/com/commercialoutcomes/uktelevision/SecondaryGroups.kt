package com.commercialoutcomes.uktelevision

/** Extra shelves share the original station, favourites and source health. */
object SecondaryGroups {
    fun group(id: String): String? = when (id) {
        "TDTextremaduratoroscanalextremadura.es@Spain" -> "09 SPORT"
        else -> CountryGroups.group(id)
    }
}

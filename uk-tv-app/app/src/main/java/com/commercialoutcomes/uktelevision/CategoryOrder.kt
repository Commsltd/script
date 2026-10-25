package com.commercialoutcomes.uktelevision

/** Accept old cached group numbers while presenting the current viewing order. */
object CategoryOrder {
    private val labels = listOf(
        "01 MAIN UK",
        "02 DRAMA & COMEDY",
        "03 FILMS",
        "04 NEWS & POLITICS",
        "05 BUSINESS & FINANCE",
        "06 DOCUMENTARIES",
        "07 CRIME & MYSTERY",
        "08 ENTERTAINMENT",
        "09 SPORT",
        "10 LIVE EVENTS",
        "11 FOOD & LIFESTYLE",
        "12 MUSIC",
        "13 REGIONAL & LOCAL",
        "14 KIDS",
        "15 WORLD & LANGUAGES",
        "16 FAITH",
        "17 SHOPPING",
        "18 MORE CHANNELS",
        "19 AUSTRALIA",
        "20 CANADA",
        "21 IRELAND",
        "22 NEW ZEALAND",
        "23 SPAIN",
        "24 TRINIDAD & TOBAGO",
        "25 USA"
    ).associateBy { it.substringAfter(" ") }
    fun label(value: String): String = labels[value.substringAfter(" ")] ?: value
}

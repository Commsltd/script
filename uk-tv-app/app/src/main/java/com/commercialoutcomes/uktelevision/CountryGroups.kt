package com.commercialoutcomes.uktelevision

/** Additional shelves reuse station identities, so favourites and health remain
 * shared with genre views. UK-specific editions keep their UK classification. */
object CountryGroups {
    val labels=linkedMapOf("au" to "19 AUSTRALIA", "ca" to "20 CANADA",
        "ie" to "21 IRELAND", "nz" to "22 NEW ZEALAND",
        "tt" to "24 TRINIDAD & TOBAGO", "us" to "25 USA")
    fun group(id:String):String? {
        val variant=id.substringAfter('@',"")
        if(variant in setOf("UK","UKHD","GB","GBHD","Europe","EuropeHD","Asia","Australia","Canada","International"))return null
        return labels[id.substringBefore('@').substringAfterLast('.')]
    }
}

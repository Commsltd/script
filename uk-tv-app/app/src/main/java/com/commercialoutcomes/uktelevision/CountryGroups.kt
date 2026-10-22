package com.commercialoutcomes.uktelevision

/** Additional shelves reuse station identities, so favourites and health remain
 * shared with genre views. UK-specific editions keep their UK classification. */
object CountryGroups {
    val labels=linkedMapOf("tt" to "17 TRINIDAD & TOBAGO","ie" to "18 IRELAND","us" to "19 USA","ca" to "20 CANADA","au" to "21 AUSTRALIA","nz" to "22 NEW ZEALAND")
    fun group(id:String):String? {
        val variant=id.substringAfter('@',"")
        if(variant in setOf("UK","UKHD","GB","GBHD","Europe","EuropeHD","Asia","Australia","Canada","International"))return null
        return labels[id.substringBefore('@').substringAfterLast('.')]
    }
}

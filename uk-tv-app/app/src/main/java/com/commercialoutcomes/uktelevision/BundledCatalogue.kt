package com.commercialoutcomes.uktelevision

import android.content.Context
import java.io.IOException

/** AAPT can unpack a .gz asset and store it without the gzip suffix in the APK. */
object BundledCatalogue {
    fun read(context: Context): Catalogue {
        val names = context.assets.list("").orEmpty()
        val name = when {
            "catalogue.json.gz" in names -> "catalogue.json.gz"
            "catalogue.json" in names -> "catalogue.json"
            else -> throw IOException("The bundled channel catalogue is missing")
        }
        return context.assets.open(name).use { CatalogueParser.parse(it) }
    }
}

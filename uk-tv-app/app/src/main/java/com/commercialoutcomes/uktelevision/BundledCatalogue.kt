package com.commercialoutcomes.uktelevision

import android.content.Context
import java.io.IOException

/** AAPT can unpack a .gz asset and store it without the gzip suffix in the APK. */
object BundledCatalogue {
    fun open(context: Context): java.io.InputStream {
        val names = context.assets.list("").orEmpty()
        val name = when {
            "catalogue.json.gz" in names -> "catalogue.json.gz"
            "catalogue.json" in names -> "catalogue.json"
            else -> throw IOException("The bundled channel catalogue is missing")
        }
        return context.assets.open(name)
    }
    fun read(context: Context): Catalogue = open(context).use { CatalogueParser.parse(it) }
}

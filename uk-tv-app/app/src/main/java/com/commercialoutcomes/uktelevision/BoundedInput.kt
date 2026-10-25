package com.commercialoutcomes.uktelevision

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

fun InputStream.readBounded(limit: Int): ByteArray {
    require(limit >= 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count < 0) return output.toByteArray()
        if (output.size() + count > limit) throw IOException("Response exceeds size limit")
        output.write(buffer, 0, count)
    }
}

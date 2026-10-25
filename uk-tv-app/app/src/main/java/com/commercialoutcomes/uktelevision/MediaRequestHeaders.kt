package com.commercialoutcomes.uktelevision

object MediaRequestHeaders {
    private val name = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}$")
    private val reserved = setOf("host", "content-length", "transfer-encoding", "connection", "upgrade", "expect",
        "proxy-authorization", "proxy-authenticate", "keep-alive", "te", "trailer")
    fun sanitise(headers: Map<String, String>): Map<String, String> {
        var bytes = 0
        return headers.entries.take(32).filter { (key, value) ->
            val valid = name.matches(key) && key.lowercase() !in reserved && value.length <= 8192 &&
                value.all { it in ' '..'~' } && bytes + key.length + value.length <= 16384
            if (valid) bytes += key.length + value.length
            valid
        }.associate { it.key to it.value }
    }
    fun crew(raw: String): Map<String, String> {
        require(raw.length <= 16384) { "Source headers too large" }
        return sanitise(raw.split('&', limit=33).take(32).mapNotNull { item ->
            val pair = item.split('=', limit=2)
            if (pair.size != 2) null else pair[0] to java.net.URLDecoder.decode(pair[1], "UTF-8")
        }.toMap())
    }
}

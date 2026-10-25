package com.commercialoutcomes.uktelevision

import java.net.InetAddress
import java.net.URI

/** Applies to internet web players, not the user's explicitly imported LAN playlists. */
object WebAddressPolicy {
    fun httpsUri(raw: String): URI? = runCatching {
        require(raw.length <= 16384 && raw.none { it <= ' ' || it == '\\' || it == '\u007f' })
        val uri = URI(raw)
        require(uri.scheme.equals("https", true) && uri.rawUserInfo == null)
        require(uri.port == -1 || uri.port == 443)
        require(!uri.host.isNullOrBlank() && '%' !in uri.rawAuthority)
        uri
    }.getOrNull()

    fun ownedBy(raw: String, domain: String): Boolean =
        httpsUri(raw)?.host?.equals(domain, true) == true

    fun publicHttps(raw: String): Boolean {
        val host = httpsUri(raw)?.host?.removeSurrounding("[", "]")?.lowercase() ?: return false
        if (':' in host) return runCatching { publicAddress(InetAddress.getByName(host)) }.getOrDefault(false)
        if (host.all { it.isDigit() || it == '.' }) {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 || (it.length > 1 && it[0] == '0') }) return false
            return publicAddress(InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray()))
        }
        if (host.endsWith('.') || '.' !in host || host.split('.').any { label ->
                label.isEmpty() || label.length > 63 || !label.first().isLetterOrDigit() ||
                    !label.last().isLetterOrDigit() || label.any { it !in 'a'..'z' && !it.isDigit() && it != '-' }
            }) return false
        // Reject numeric/hex legacy IP spellings before a browser can canonicalise them.
        if (host.substringAfterLast('.').toIntOrNull() != null || host.split('.').all { it.startsWith("0x") }) return false
        return listOf("localhost", "local", "internal", "lan", "home", "home.arpa").none { host == it || host.endsWith(".$it") }
    }

    fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) return when {
            b[0] in setOf(0, 10, 127) || b[0] >= 224 -> false
            b[0] == 100 && b[1] in 64..127 -> false
            b[0] == 169 && b[1] == 254 -> false
            b[0] == 172 && b[1] in 16..31 -> false
            b[0] == 192 && (b[1] == 168 || b[1] == 0 && b[2] in setOf(0, 2) || b[1] == 88 && b[2] == 99) -> false
            b[0] == 198 && (b[1] in 18..19 || b[1] == 51 && b[2] == 100) -> false
            b[0] == 203 && b[1] == 0 && b[2] == 113 -> false
            else -> true
        }
        // Global IPv6 unicast only; exclude documentation and transition tunnels.
        return b.size == 16 && (b[0] and 0xe0) == 0x20 &&
            !(b[0] == 0x20 && b[1] == 0x01 && (b[2] == 0 && b[3] == 0 || b[2] == 0x0d && b[3] == 0xb8)) &&
            !(b[0] == 0x20 && b[1] == 0x02)
    }

    fun frame(raw: String): Boolean = raw in setOf("about:blank", "about:srcdoc") ||
        publicHttps(raw) || (raw.startsWith("blob:") && publicHttps(raw.removePrefix("blob:")))

    fun resource(raw: String): Boolean = frame(raw) || raw.startsWith("data:", true)
}

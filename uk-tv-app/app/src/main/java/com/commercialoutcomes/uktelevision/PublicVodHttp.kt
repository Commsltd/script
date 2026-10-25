package com.commercialoutcomes.uktelevision

import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Public internet VOD and built-in stream metadata; never local Kodi or account feeds. */
object PublicVodHttp {
    fun publicDns(delegate: Dns): Dns = object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val addresses = delegate.lookup(hostname)
            if (addresses.isEmpty() || addresses.any { !WebAddressPolicy.publicAddress(it) })
                throw UnknownHostException("VOD destination is not a public address")
            return addresses
        }
    }

    // An application interceptor runs BEFORE each connection. A network interceptor
    // would be too late to stop a redirect connecting to a local device.
    val redirects = Interceptor { chain ->
        var request = chain.request()
        var result: okhttp3.Response? = null
        for (hop in 0..5) {
            if (!WebAddressPolicy.publicHttps(request.url.toString())) throw IOException("Unsafe VOD destination")
            if (request.method !in setOf("GET", "HEAD")) throw IOException("Unsupported VOD request")
            val response = chain.proceed(request)
            if (response.code !in setOf(301, 302, 303, 307, 308)) { result = response; break }
            val location = response.header("Location")
            val next = location?.let { response.request.url.resolve(it) }
            response.close()
            if (hop == 5 || next == null || !WebAddressPolicy.publicHttps(next.toString())) throw IOException("Unsafe VOD redirect")
            request = request.newBuilder().url(next)
                .removeHeader("Authorization").removeHeader("Cookie").build()
        }
        result ?: throw IOException("Too many VOD redirects")
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .dns(publicDns(Dns.SYSTEM)).addInterceptor(redirects).build()
    }

    fun referer(page: String): String? = WebAddressPolicy.httpsUri(page)?.let { "https://${it.host}/" }
}

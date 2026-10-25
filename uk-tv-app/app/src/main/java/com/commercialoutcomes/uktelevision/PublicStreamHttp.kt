package com.commercialoutcomes.uktelevision

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Built-in public streams may require HTTP, but never a private network destination. */
object PublicStreamHttp {
    fun allowed(raw: String): Boolean = runCatching {
        require(raw.length <= 16384 && raw.none { it <= ' ' || it == '\\' || it == '\u007f' })
        val uri = URI(raw)
        require(uri.scheme in setOf("http", "https") && uri.rawUserInfo == null && !uri.host.isNullOrBlank())
        require(uri.port == -1 || uri.port in 1..65535)
        require('%' !in uri.rawAuthority)
        WebAddressPolicy.publicHttps(URI("https", null, uri.host, -1, "/", null, null).toASCIIString())
    }.getOrDefault(false)

    private fun origin(raw: String): Triple<String?, String?, Int> {
        val uri = URI(raw)
        return Triple(uri.scheme?.lowercase(), uri.host?.lowercase(),
            if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80)
    }
    fun headers(values: Map<String, String>, original: String, target: String): Map<String, String> =
        MediaRequestHeaders.sanitise(values).filterKeys {
            origin(original) == origin(target) || it.lowercase() in setOf("user-agent", "referer", "origin")
        }

    private fun create(strict: Boolean, publicOnly: Boolean): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .apply { if (publicOnly) dns(PublicVodHttp.publicDns(okhttp3.Dns.SYSTEM)) }
        .addInterceptor(Interceptor { chain ->
            var request = chain.request()
            var result: okhttp3.Response? = null
            for (hop in 0..5) {
                if ((publicOnly && !allowed(request.url.toString())) || (strict && !request.url.isHttps))
                    throw IOException("Stream destination blocked")
                val response = chain.proceed(request)
                if (response.code !in setOf(301,302,303,307,308)) { result = response; break }
                val next = response.header("Location")?.let { response.request.url.resolve(it) }
                response.close()
                if (hop == 5 || next == null) throw IOException("Invalid stream redirect")
                val builder = request.newBuilder().url(next)
                if (origin(request.url.toString()) != origin(next.toString())) {
                    request.headers.names().filter { it.lowercase() !in setOf("user-agent", "referer", "origin", "range", "accept", "accept-encoding") }
                        .forEach(builder::removeHeader)
                }
                request = builder.build()
            }
            result ?: throw IOException("Too many stream redirects")
        }).build()
    private val compatibility by lazy { create(false, true) }
    private val strictClient by lazy { create(true, true) }
    private val userCompatibility by lazy { create(false, false) }
    private val userStrict by lazy { create(true, false) }
    fun client(strict: Boolean, publicOnly: Boolean = true): OkHttpClient =
        if (publicOnly) { if (strict) strictClient else compatibility }
        else { if (strict) userStrict else userCompatibility }

    fun playbackClient(strict: Boolean, source: StreamSource): OkHttpClient = client(strict).newBuilder()
        // Media3 applies default headers to child playlists, segments and keys too.
        .addInterceptor { chain ->
            val request = chain.request()
            val keep = headers(source.headers, source.url, request.url.toString()).keys
            val builder = request.newBuilder()
            source.headers.keys.filter { it !in keep }.forEach(builder::removeHeader)
            chain.proceed(builder.build())
        }.callTimeout(0, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
}

package cc.fplj.magpiemobile.core

import java.net.Proxy
import okhttp3.Authenticator
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/** Shared socket pools, explicit local routing and a non-replayable billable POST body. */
internal object ChatHttpTransport {
    private val base = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .build()

    fun client(config: GatewayConfig): OkHttpClient = base.newBuilder()
        .connectTimeout(config.connectTimeout)
        .readTimeout(config.readTimeout)
        .writeTimeout(config.readTimeout)
        .build()

    fun jsonBody(json: String): RequestBody {
        val delegate = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        return object : RequestBody() {
            override fun contentType(): MediaType? = delegate.contentType()
            override fun contentLength(): Long = delegate.contentLength()
            override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
            // Covers HTTP follow-ups (for example 503 + Retry-After: 0) as well as I/O retries.
            override fun isOneShot(): Boolean = true
        }
    }
}

package cc.fplj.magpiemobile.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.time.Duration
import javax.net.SocketFactory
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
        .socketFactory(DirectSocketFactory)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .build()

    fun client(config: GatewayConfig): OkHttpClient = client(config.connectTimeout, config.readTimeout)

    fun client(connectTimeout: Duration, readTimeout: Duration): OkHttpClient = base.newBuilder()
        .connectTimeout(connectTimeout)
        .readTimeout(readTimeout)
        .writeTimeout(readTimeout)
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

    /** OkHttp's direct route alone does not disable the JDK Socket's system SOCKS selector. */
    private object DirectSocketFactory : SocketFactory() {
        override fun createSocket(): Socket = Socket(Proxy.NO_PROXY)

        override fun createSocket(host: String?, port: Int): Socket =
            connected(address(host, port))

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            connected(InetSocketAddress(host ?: InetAddress.getLoopbackAddress(), port))

        override fun createSocket(
            host: String?,
            port: Int,
            localHost: InetAddress?,
            localPort: Int,
        ): Socket = connected(address(host, port), InetSocketAddress(localHost, localPort))

        override fun createSocket(
            host: InetAddress?,
            port: Int,
            localHost: InetAddress?,
            localPort: Int,
        ): Socket = connected(
            InetSocketAddress(host ?: InetAddress.getLoopbackAddress(), port),
            InetSocketAddress(localHost, localPort),
        )

        private fun address(host: String?, port: Int): InetSocketAddress =
            if (host == null) InetSocketAddress(InetAddress.getLoopbackAddress(), port)
            else InetSocketAddress(host, port)

        private fun connected(remote: InetSocketAddress, local: InetSocketAddress? = null): Socket {
            val socket = createSocket()
            try {
                if (local != null) socket.bind(local)
                socket.connect(remote)
                return socket
            } catch (failure: Throwable) {
                try { socket.close() } catch (_: Exception) { /* Preserve the connection failure. */ }
                throw failure
            }
        }
    }
}

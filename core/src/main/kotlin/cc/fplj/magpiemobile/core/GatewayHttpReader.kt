package cc.fplj.magpiemobile.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import okhttp3.Request

internal data class GatewayHttpResponse(val code: Int, val text: String = "")

internal class GatewayResponseLimitException : IOException("Gateway response exceeds receive limit")

/** Bounded gateway reads; the caller owns the request control's scoped deadline.
 * Error statuses are intercepted before OkHttp's HTTP follow-up handling, so even a GET with
 * 503/Retry-After: 0 cannot replay. Error bodies and response headers never enter diagnostics.
 */
internal class GatewayHttpReader(config: GatewayConfig) {
    private class GatewayHttpStatusException(val code: Int) : RuntimeException("Gateway HTTP error")

    private val client = ChatHttpTransport.client(config).newBuilder()
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (!response.isSuccessful) {
                chain.call().cancel()
                try { response.close() } catch (_: IOException) { /* The socket is already cancelled. */ }
                throw GatewayHttpStatusException(response.code)
            }
            response
        }
        .build()

    fun read(request: Request, maxResponseBytes: Int, control: ChatRequestControl): GatewayHttpResponse {
        require(maxResponseBytes > 0) { "Gateway response limit must be positive" }
        control.ensureActive()
        val call = client.newCall(request)
        var registration: Closeable? = null
        try {
            registration = control.register(call::cancel)
            control.ensureActive()
            val response = call.execute()
            try {
                control.ensureActive()
                if (!response.isSuccessful) return GatewayHttpResponse(response.code)
                val body = response.body ?: throw IOException("Gateway response body missing")
                if (body.contentLength() > maxResponseBytes.toLong()) throw GatewayResponseLimitException()
                val input = body.byteStream()
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    control.ensureActive()
                    val requestBytes = minOf(buffer.size - 1, maxResponseBytes - output.size()) + 1
                    val count = input.read(buffer, 0, requestBytes)
                    control.ensureActive()
                    if (count == -1) break
                    if (count > maxResponseBytes - output.size()) throw GatewayResponseLimitException()
                    output.write(buffer, 0, count)
                }
                val text = output.toString(Charsets.UTF_8.name())
                control.ensureActive()
                return GatewayHttpResponse(response.code, text)
            } finally {
                // Cancel before close, including after success, so cleanup cannot drain an
                // unfinished body beyond a deadline or resource boundary for connection reuse.
                call.cancel()
                try { response.close() } catch (_: IOException) { /* Preserve the terminal result. */ }
            }
        } catch (status: GatewayHttpStatusException) {
            control.ensureActive()
            return GatewayHttpResponse(status.code)
        } catch (error: IOException) {
            control.ensureActive()
            throw error
        } finally {
            call.cancel()
            registration?.close()
        }
    }
}

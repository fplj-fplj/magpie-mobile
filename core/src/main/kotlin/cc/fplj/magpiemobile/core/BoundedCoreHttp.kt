package cc.fplj.magpiemobile.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.time.Duration
import okhttp3.Request

/** Direct loopback management transport. Every request has a bounded lifetime and receive size.
 * A session guard runs immediately before sending and before accepting a response. Cancellation
 * closes an active socket, including a peer that has not sent headers or has stopped mid-body.
 */
internal class BoundedCoreHttp(
    private val endpoint: LoopbackEndpoint,
    timeout: Duration,
    private val requestControl: ChatRequestControl? = null,
    private val ensureActive: () -> Unit = {},
    private val managerKey: String? = null,
) {
    private val client = run {
        val millis = try { timeout.toMillis() } catch (_: ArithmeticException) { -1L }
        require(millis in 1..Int.MAX_VALUE.toLong()) { "核心请求超时无效" }
        val boundedTimeout = Duration.ofMillis(millis)
        ChatHttpTransport.client(boundedTimeout, boundedTimeout).newBuilder()
            .callTimeout(boundedTimeout)
            .addNetworkInterceptor { chain ->
                checkCurrent()
                val response = chain.proceed(chain.request())
                if (!response.isSuccessful) {
                    // Intercept before OkHttp's follow-up handling, including GET 503/Retry-After.
                    // Only the whitelisted model-error header is retained; never read error bodies.
                    val failure = ManagementHttpException(
                        response.code, safeModelErrorCode(response.header("X-Magpie-Mobile-Error-Code")),
                    )
                    chain.call().cancel()
                    try { response.close() } catch (_: IOException) { /* Already cancelled. */ }
                    throw failure
                }
                response
            }
            .build()
    }

    private fun checkCurrent() {
        requestControl?.ensureActive()
        ensureActive()
        requestControl?.ensureActive()
    }

    fun request(method: String, path: String, payload: ByteArray? = null): String {
        checkCurrent()
        val control = requestControl ?: ChatRequestControl()
        val request = Request.Builder().url(endpoint.base + path)
            .header("Accept", "application/json")
            .header("Accept-Encoding", "identity")
            .apply { if (managerKey != null) header(ANDROID_MANAGER_HEADER, managerKey) }
            .method(method, payload?.let { ChatHttpTransport.jsonBody(it.toString(Charsets.UTF_8)) })
            .build()
        val call = client.newCall(request)
        var registration: Closeable? = null
        try {
            registration = control.register(call::cancel)
            checkCurrent()
            val response = call.execute()
            try {
                checkCurrent()
                val body = response.body ?: throw ManagementProtocolException()
                if (body.contentLength() > MAX_RESPONSE_BYTES) throw ManagementProtocolException()
                val input = body.byteStream()
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkCurrent()
                    val read = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES + 1 - output.size()))
                    checkCurrent()
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    if (output.size() > MAX_RESPONSE_BYTES) throw ManagementProtocolException()
                }
                val text = output.toString(Charsets.UTF_8.name())
                checkCurrent()
                return text
            } finally {
                // Cancel before close to prevent a response-body drain after cancellation/limits.
                call.cancel()
                try { response.close() } catch (_: IOException) { /* Preserve the terminal result. */ }
            }
        } catch (_: IOException) {
            checkCurrent() // Session cancellation takes priority over the resulting socket error.
            throw ManagementExecutionUnknown() // No raw platform messages or original cause.
        } catch (failure: ManagementProtocolException) {
            checkCurrent()
            if (method == "POST") throw ManagementExecutionUnknown()
            throw failure
        } catch (failure: ManagementHttpException) {
            checkCurrent()
            throw failure
        } finally {
            call.cancel()
            registration?.close()
        }
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        const val ANDROID_MANAGER_HEADER = "X-Magpie-Android-Manager"
    }
}

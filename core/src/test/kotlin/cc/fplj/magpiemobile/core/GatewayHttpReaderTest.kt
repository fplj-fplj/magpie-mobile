package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Request

class GatewayHttpReaderTest {
    private lateinit var server: HttpServer
    private lateinit var config: GatewayConfig
    private val maxBytes = 4 * 1024 * 1024

    @BeforeEach fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        config = GatewayConfig(gatewayKey = "reader-fixture-key", gatewayBaseUrl = "http://127.0.0.1:${server.address.port}",
            readTimeout = Duration.ofSeconds(10))
    }

    @AfterEach fun cleanup() { server.stop(0) }

    private fun request() = Request.Builder().url(config.modelsPath()).get().build()

    @Test fun exactFourMiBUtf8ResponseIsReturnedWithoutTruncation() {
        val prefix = "{\"reply\":\"你好🙂"
        val suffix = "\"}"
        val text = prefix + "x".repeat(maxBytes - (prefix + suffix).toByteArray(Charsets.UTF_8).size) + suffix
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(maxBytes, bytes.size)
        server.createContext("/v1/models") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val response = GatewayHttpReader(config).read(request(), maxBytes, ChatRequestControl())
        assertEquals(200, response.code)
        assertEquals(text, response.text)
    }

    @Test fun chunkedBodyOverFourMiBIsRejectedWithFixedFailure() {
        val requests = AtomicInteger()
        server.createContext("/v1/models") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200, 0)
            try { exchange.responseBody.write(ByteArray(maxBytes + 1) { 'x'.code.toByte() }) }
            catch (_: IOException) { /* The limit cancels the response immediately. */ }
            finally { exchange.close() }
        }
        val failure = assertFailsWith<GatewayResponseLimitException> {
            GatewayHttpReader(config).read(request(), maxBytes, ChatRequestControl())
        }
        assertEquals("Gateway response exceeds receive limit", failure.message)
        assertNull(failure.cause)
        assertEquals(1, requests.get())
    }

    @Test fun declaredOverFourMiBFailsWithoutWaitingForBodyOrEof() {
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val control = ChatRequestControl()
        server.createContext("/v1/models") { exchange ->
            exchange.sendResponseHeaders(200, maxBytes.toLong() + 1)
            try { release.await(10, TimeUnit.SECONDS) } finally { exchange.close() }
        }
        val result = executor.submit<GatewayResponseLimitException> {
            assertFailsWith<GatewayResponseLimitException> { GatewayHttpReader(config).read(request(), maxBytes, control) }
        }
        try {
            assertEquals("Gateway response exceeds receive limit", result.get(3, TimeUnit.SECONDS).message)
            assertEquals(1L, release.count)
        } finally {
            control.cancel()
            release.countDown()
            result.cancel(true)
            executor.shutdownNow()
        }
    }

    @Test fun chunkedExactBudgetUsesBytesAcrossSplitUtf8Writes() {
        val text = "你好🙂"
        val bytes = text.toByteArray(Charsets.UTF_8)
        server.createContext("/v1/models") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                bytes.forEach { byte -> output.write(byte.toInt() and 0xff); output.flush() }
            }
        }
        val reader = GatewayHttpReader(config)
        assertEquals(text, reader.read(request(), bytes.size, ChatRequestControl()).text)
        assertFailsWith<GatewayResponseLimitException> { reader.read(request(), bytes.size - 1, ChatRequestControl()) }
    }

    @Test fun nonSuccessfulGetCannotRedirectAuthenticateOrRetryAfterZero() {
        val code = AtomicInteger(401)
        val requests = AtomicInteger()
        server.createContext("/v1/models") { exchange ->
            requests.incrementAndGet()
            exchange.responseHeaders.add("Retry-After", "0")
            exchange.responseHeaders.add("Location", "/redirected-models")
            exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=fixture")
            exchange.sendResponseHeaders(code.get(), -1)
            exchange.close()
        }
        server.createContext("/redirected-models") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        val reader = GatewayHttpReader(config)
        for ((index, status) in listOf(401, 307, 408, 503).withIndex()) {
            code.set(status)
            assertEquals(GatewayHttpResponse(status), reader.read(request(), 64, ChatRequestControl()))
            assertEquals(index + 1, requests.get())
        }
    }

    @Test fun unfinishedErrorBodyIsNeverReadOrDrained() {
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val control = ChatRequestControl()
        val requests = AtomicInteger()
        server.createContext("/v1/models") { exchange ->
            requests.incrementAndGet()
            exchange.responseHeaders.add("Retry-After", "0")
            exchange.sendResponseHeaders(503, 0)
            try {
                exchange.responseBody.write("never-return-this-body".toByteArray())
                exchange.responseBody.flush()
                release.await(10, TimeUnit.SECONDS)
            } catch (_: IOException) { /* The status interceptor cancels before reading the body. */ }
            finally { exchange.close() }
        }
        val result = executor.submit<GatewayHttpResponse> { GatewayHttpReader(config).read(request(), 1, control) }
        try {
            assertEquals(GatewayHttpResponse(503, ""), result.get(3, TimeUnit.SECONDS))
            assertEquals(1L, release.count)
            assertEquals(1, requests.get())
        } finally {
            control.cancel()
            release.countDown()
            result.cancel(true)
            executor.shutdownNow()
        }
    }

    @Test fun cancelClosesSilentResponseHeadersWithoutWaitingForTenSecondReadTimeout() {
        assertSilentCancellation(sendHeaders = false)
    }

    @Test fun cancelClosesSilentBodyWithoutWaitingForTenSecondReadTimeout() {
        assertSilentCancellation(sendHeaders = true)
    }

    private fun assertSilentCancellation(sendHeaders: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val control = ChatRequestControl()
        server.createContext("/v1/models") { exchange ->
            try {
                if (sendHeaders) {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write('x'.code)
                    exchange.responseBody.flush()
                }
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            } catch (_: IOException) { /* Cancellation closes the socket. */ }
            finally { exchange.close() }
        }
        val result = executor.submit<CancellationException> {
            assertFailsWith<CancellationException> { GatewayHttpReader(config).read(request(), 64, control) }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            control.cancel()
            assertEquals("Chat request cancelled", result.get(3, TimeUnit.SECONDS).message)
            assertEquals(1L, release.count)
        } finally {
            control.cancel()
            release.countDown()
            result.cancel(true)
            executor.shutdownNow()
        }
    }

    @Test fun ordinarySocketTimeoutKeepsItsTypeWhileControlIsActive() {
        val release = CountDownLatch(1)
        val control = ChatRequestControl()
        server.createContext("/v1/models") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.write('x'.code)
                exchange.responseBody.flush()
                release.await(10, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        try {
            assertFailsWith<SocketTimeoutException> {
                GatewayHttpReader(config.copy(readTimeout = Duration.ofMillis(500))).read(request(), 64, control)
            }
            control.ensureActive()
        } finally {
            control.cancel()
            release.countDown()
        }
    }

    @Test fun invalidBudgetIsRejectedBeforeAnyHttpRequest() {
        val requests = AtomicInteger()
        server.createContext("/v1/models") { requests.incrementAndGet(); it.close() }
        assertFailsWith<IllegalArgumentException> { GatewayHttpReader(config).read(request(), 0, ChatRequestControl()) }
        assertEquals(0, requests.get())
    }
}

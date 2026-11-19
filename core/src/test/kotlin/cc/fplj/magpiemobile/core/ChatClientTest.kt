package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatClientTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        port = server.address.port
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun config() = GatewayConfig(
        gatewayKey = "test-key",
        gatewayBaseUrl = "http://127.0.0.1:" + port,
    )

    @Test
    fun completeSendsAuthAndModel() {
        var seenAuth: String? = null
        var seenBody = ""
        server.createContext("/v1/chat/completions") { ex ->
            seenAuth = ex.requestHeaders.getFirst("Authorization")
            seenBody = ex.requestBody.bufferedReader().readText()
            val body = """{"id":"x","choices":[{"message":{"role":"assistant","content":"hi"}}]}"""
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val out = ChatClient(config()).complete(
            "deepseek/deepseek-v4",
            listOf(ChatClient.Message("user", "hello")),
        )
        assertEquals("Bearer test-key", seenAuth)
        assertTrue(seenBody.contains("\"model\":\"deepseek/deepseek-v4\""))
        assertTrue(out.contains("hi"))
    }

    @Test
    fun upstreamErrorPropagatesCode() {
        server.createContext("/v1/chat/completions") { ex ->
            ex.sendResponseHeaders(429, -1)
        }
        val e = assertFailsWith<ChatException> {
            ChatClient(config()).complete("m", listOf(ChatClient.Message("user", "x")))
        }
        assertEquals(429, e.httpCode)
    }

    @Test
    fun upstreamStatusDoesNotWaitForUnfinishedErrorBody() {
        val release = CountDownLatch(1)
        server.createContext("/v1/chat/completions") { ex ->
            ex.requestBody.close()
            ex.sendResponseHeaders(503, 0)
            try {
                ex.responseBody.write('x'.code)
                ex.responseBody.flush()
                release.await(10, TimeUnit.SECONDS)
            } finally {
                ex.close()
            }
        }
        try {
            val error = assertFailsWith<ChatException> {
                ChatClient(config().copy(readTimeout = Duration.ofSeconds(2)))
                    .complete("m", listOf(ChatClient.Message("user", "x")))
            }
            assertEquals(503, error.httpCode)
            assertEquals("chat -> HTTP 503", error.message)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun rejectsDeclaredOversizeResponseBeforeReadingItsBody() {
        val release = CountDownLatch(1)
        server.createContext("/v1/chat/completions") { ex ->
            ex.requestBody.close()
            ex.sendResponseHeaders(200, ChatClient.MAX_RESPONSE_BYTES.toLong() + 1)
            try {
                release.await(10, TimeUnit.SECONDS)
            } finally {
                ex.close()
            }
        }
        try {
            val error = assertFailsWith<IOException> {
                ChatClient(config().copy(readTimeout = Duration.ofSeconds(2)))
                    .complete("m", listOf(ChatClient.Message("user", "x")))
            }
            assertEquals("Chat response exceeds the 4 MiB size limit", error.message)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun rejectsChunkedOversizeResponseWithoutReturningPartialJson() {
        server.createContext("/v1/chat/completions") { ex ->
            ex.requestBody.close()
            ex.sendResponseHeaders(200, 0)
            try {
                ex.responseBody.use { it.write(ByteArray(ChatClient.MAX_RESPONSE_BYTES + 1) { 120 }) }
            } catch (_: IOException) {
                // The client may close immediately once its limit is exceeded.
            } finally {
                ex.close()
            }
        }
        val error = assertFailsWith<IOException> {
            ChatClient(config()).complete("m", listOf(ChatClient.Message("user", "x")))
        }
        assertEquals("Chat response exceeds the 4 MiB size limit", error.message)
    }

    @Test
    fun exactLimitUtf8ResponseIsPreserved() {
        val prefix = "{\"choices\":[{\"message\":{\"content\":\"哈哈哈"
        val suffix = "\"}}]}"
        val padding = ChatClient.MAX_RESPONSE_BYTES - (prefix + suffix).toByteArray(Charsets.UTF_8).size
        val body = prefix + "x".repeat(padding) + suffix
        val bytes = body.toByteArray(Charsets.UTF_8)
        assertEquals(ChatClient.MAX_RESPONSE_BYTES, bytes.size)
        server.createContext("/v1/chat/completions") { ex ->
            ex.requestBody.close()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        val output = ChatClient(config()).complete("m", listOf(ChatClient.Message("user", "x")))

        assertEquals(body, output)
    }

    @Test
    fun unavailableWithImmediateRetryAfterNeverReplaysThePost() {
        assertSinglePostResponse(503, retryAfter = "0")
    }

    @Test
    fun requestTimeoutWithImmediateRetryAfterNeverReplaysThePost() {
        assertSinglePostResponse(408, retryAfter = "0")
    }

    @Test
    fun temporaryRedirectNeverReplaysOrForwardsThePost() {
        assertSinglePostResponse(307)
    }

    @Test
    fun sharedControlCancelsASilentResponseHeaderWaitPromptly() {
        assertSilentRequestCancelled(sendHeaders = false)
    }

    @Test
    fun sharedControlCancelsASilentSuccessfulResponseBodyPromptly() {
        assertSilentRequestCancelled(sendHeaders = true)
    }

    @Test
    fun responseHeadersTakingMoreThanOneSecondStillUseConfiguredTimeout() {
        val holdHeaders = CountDownLatch(1)
        val body = """{"choices":[{"message":{"content":"slow headers are valid"}}]}"""
        val calls = AtomicInteger()
        server.createContext("/v1/chat/completions") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.readBytes()
            // A latch timeout simulates an upstream which takes longer than a one-second poll
            // interval. A receive timeout must not terminate this otherwise valid request.
            holdHeaders.await(1_250, TimeUnit.MILLISECONDS)
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val output = ChatClient(config().copy(readTimeout = Duration.ofSeconds(5)))
            .complete("m", listOf(ChatClient.Message("user", "x")))

        assertEquals(body, output)
        assertEquals(1, calls.get())
    }

    @Test
    fun continuouslyArrivingOrdinaryBodyStillStopsAtItsTotalDeadline() {
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        val calls = AtomicInteger()
        server.createContext("/v1/chat/completions") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(200, 0)
            try {
                while (!release.await(30, TimeUnit.MILLISECONDS)) {
                    exchange.responseBody.write('x'.code)
                    exchange.responseBody.flush()
                    writes.incrementAndGet()
                }
            } catch (_: IOException) {
                // Deadline cancellation closes this intentionally endless, tiny response.
            } finally {
                exchange.close()
            }
        }
        val worker = Executors.newSingleThreadExecutor()
        val result = worker.submit(Callable<Throwable?> {
            try {
                ChatClient(
                    config().copy(readTimeout = Duration.ofSeconds(5)),
                    maxDuration = Duration.ofMillis(700),
                ).complete("m", listOf(ChatClient.Message("user", "x")))
                null
            } catch (failure: Throwable) { failure }
        })
        try {
            val failure = assertIs<StreamingChatException>(result.get(3, TimeUnit.SECONDS))

            assertEquals(StreamingChatFailure.DEADLINE_EXCEEDED, failure.failure)
            assertTrue(writes.get() >= 3, "Body data should keep arriving before the total deadline")
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            worker.shutdownNow()
            worker.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun assertSinglePostResponse(status: Int, retryAfter: String? = null) {
        val posts = AtomicInteger()
        val redirected = AtomicInteger()
        server.createContext("/v1/chat/completions") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("Bearer test-key", exchange.requestHeaders.getFirst("Authorization"))
            val requestBody = exchange.requestBody.bufferedReader().readText()
            assertTrue(requestBody.contains("\"model\":\"m\""))
            posts.incrementAndGet()
            if (retryAfter != null) exchange.responseHeaders.add("Retry-After", retryAfter)
            if (status == 307) {
                exchange.responseHeaders.add("Location", "http://127.0.0.1:$port/redirected")
            }
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.createContext("/redirected") { exchange ->
            redirected.incrementAndGet()
            exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }

        val failure = assertFailsWith<ChatException> {
            ChatClient(config()).complete("m", listOf(ChatClient.Message("user", "x")))
        }

        assertEquals(status, failure.httpCode)
        assertEquals("chat -> HTTP $status", failure.message)
        assertEquals(1, posts.get())
        assertEquals(0, redirected.get())
    }

    private fun assertSilentRequestCancelled(sendHeaders: Boolean) {
        val requestArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        server.createContext("/v1/chat/completions") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.readBytes()
            if (sendHeaders) {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.flush()
            }
            requestArrived.countDown()
            try {
                release.await(10, TimeUnit.SECONDS)
            } finally {
                exchange.close()
            }
        }
        val control = ChatRequestControl()
        val worker = Executors.newSingleThreadExecutor()
        val result = worker.submit(Callable<Throwable?> {
            try {
                ChatClient(config().copy(readTimeout = Duration.ofSeconds(15)), control)
                    .complete("m", listOf(ChatClient.Message("user", "x")))
                null
            } catch (failure: Throwable) {
                failure
            }
        })
        try {
            assertTrue(requestArrived.await(5, TimeUnit.SECONDS), "The request must reach the upstream")

            control.cancel()
            val failure = assertIs<CancellationException>(result.get(2, TimeUnit.SECONDS))

            assertEquals("Chat request cancelled", failure.message)
            assertEquals(1, calls.get())
        } finally {
            control.cancel()
            release.countDown()
            worker.shutdownNow()
            worker.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}

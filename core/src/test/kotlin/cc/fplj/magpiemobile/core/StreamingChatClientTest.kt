package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingChatClientTest {

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

    private fun config() = GatewayConfig(gatewayKey = "k", gatewayBaseUrl = "http://127.0.0.1:" + port)

    private fun respond(ex: HttpExchange, sse: String) {
        val bytes = sse.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test
    fun streamCollectsDeltasUntilDone() {
        server.createContext("/v1/chat/completions") { ex ->
            respond(ex, "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n" +
                "data: [DONE]\n\n")
        }
        val deltas = mutableListOf<String>()
        val full = StreamingChatClient(config()).stream(
            "m", listOf(ChatClient.Message("user", "hi")), deltas::add,
        )
        assertEquals(listOf("你", "好"), deltas)
        assertEquals("你好", full)
    }

    @Test
    fun streamSeparatesReasoningEventsFromContent() {
        server.createContext("/v1/chat/completions") { ex ->
            respond(
                ex,
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null,\"reasoning_content\":null}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":null,\"reasoning_content\":\"think 1\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":null,\"reasoning_content\":\"think 2\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"哈\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"哈\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n",
            )
        }
        val reasonings = mutableListOf<String>()
        val deltas = mutableListOf<String>()
        val full = StreamingChatClient(config()).streamWithReasoning(
            model = "deepseek-v4.1-flash",
            messages = listOf(ChatClient.Message("user", "哈哈哈")),
            onReasoning = reasonings::add,
            onDelta = deltas::add,
        )
        assertEquals(listOf("哈", "哈"), deltas)
        assertEquals(listOf("think 1", "think 2"), reasonings)
        assertEquals("哈哈", full)
    }

    @Test
    fun streamWithoutDoneFailsAndKeepsPartialText() {
        server.createContext("/v1/chat/completions") { ex ->
            respond(ex, "data: {\"choices\":[{\"delta\":{\"content\":\"part\"}}]}\n\n")
        }
        val deltas = mutableListOf<String>()
        val e = assertFailsWith<StreamingChatException> {
            StreamingChatClient(config()).stream("m", listOf(ChatClient.Message("user", "x")), deltas::add)
        }
        assertEquals("part", e.partialText)
        assertEquals(listOf("part"), deltas)
        assertEquals(200, e.httpCode)
        assertEquals(StreamingChatFailure.EOF_BEFORE_DONE, e.failure)
        assertEquals("Stream EOF before [DONE]", e.message)
        assertNull(e.cause)
    }

    @Test
    fun emptyStreamWithoutDoneFails() {
        server.createContext("/v1/chat/completions") { ex -> respond(ex, ": keep-alive\n\n") }
        val e = assertFailsWith<StreamingChatException> {
            StreamingChatClient(config()).stream("m", listOf(ChatClient.Message("user", "x")))
        }
        assertEquals("", e.partialText)
        assertEquals(StreamingChatFailure.EOF_BEFORE_DONE, e.failure)
        assertEquals("Stream EOF before [DONE]", e.message)
        assertNull(e.cause)
    }

    @Test
    fun doneReturnsBeforeLongConnectionCloses() {
        val sentDone = CountDownLatch(1)
        val closeResponse = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        server.createContext("/v1/chat/completions") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0) // chunked；DONE 后既不追加空行也不立即关闭
            try {
                ex.responseBody.write(("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n" +
                    "data: [DONE]\n").toByteArray(Charsets.UTF_8))
                ex.responseBody.flush()
                sentDone.countDown()
                closeResponse.await(5, TimeUnit.SECONDS)
            } finally {
                ex.close()
            }
        }
        val result = executor.submit<String> {
            StreamingChatClient(config().copy(readTimeout = Duration.ofSeconds(10)))
                .stream("m", listOf(ChatClient.Message("user", "x")))
        }
        try {
            assertTrue(sentDone.await(2, TimeUnit.SECONDS))
            // 服务端仍等 closeResponse；若 DONE 后又 readLine，这里超时而不是返回。
            assertEquals("ok", result.get(2, TimeUnit.SECONDS))
            assertEquals(1L, closeResponse.count)
        } finally {
            closeResponse.countDown()
            result.cancel(true)
            executor.shutdownNow()
        }
    }

    @Test
    fun readIOExceptionKeepsPartialText() {
        val closeResponse = CountDownLatch(1)
        server.createContext("/v1/chat/completions") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            try {
                ex.responseBody.write("data: {\"choices\":[{\"delta\":{\"content\":\"part\"}}]}\n\n"
                    .toByteArray(Charsets.UTF_8))
                ex.responseBody.flush()
                closeResponse.await(5, TimeUnit.SECONDS)
            } finally {
                ex.close()
            }
        }
        try {
            val e = assertFailsWith<StreamingChatException> {
                StreamingChatClient(config().copy(readTimeout = Duration.ofSeconds(1)))
                    .stream("m", listOf(ChatClient.Message("user", "x")))
            }
            assertEquals("part", e.partialText)
            assertEquals(StreamingChatFailure.IO_FAILURE, e.failure)
            assertEquals("Stream I/O failure", e.message)
            assertNull(e.cause)
        } finally {
            closeResponse.countDown()
        }
    }

    @Test
    fun nonSseJsonResponseIsNotSuccessfulEmptyText() {
        server.createContext("/v1/chat/completions") { ex ->
            val bytes = "{\"choices\":[{\"message\":{\"content\":\"json reply\"}}]}".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        val e = assertFailsWith<StreamingChatException> {
            StreamingChatClient(config()).stream("m", listOf(ChatClient.Message("user", "x")))
        }
        assertEquals("", e.partialText)
        assertEquals(200, e.httpCode)
        assertEquals(StreamingChatFailure.UNEXPECTED_CONTENT_TYPE, e.failure)
        assertEquals("Stream expected text/event-stream", e.message)
        assertNull(e.cause)
    }

    @Test
    fun httpErrorPropagates() {
        server.createContext("/v1/chat/completions") { ex -> ex.sendResponseHeaders(503, -1); ex.close() }
        val e = assertFailsWith<ChatException> {
            StreamingChatClient(config()).stream("m", listOf(ChatClient.Message("user", "x")))
        }
        assertEquals(503, e.httpCode)
    }

    @Test
    fun sseErrorEventFailsAndKeepsPartialText() {
        server.createContext("/v1/chat/completions") { ex ->
            respond(ex, "data: {\"choices\":[{\"delta\":{\"content\":\"part\"}}]}\n\n" +
                "data: {\"error\":{\"message\":\"billing\"}}\n\n")
        }
        val e = assertFailsWith<StreamingChatException> {
            StreamingChatClient(config()).stream("m", listOf(ChatClient.Message("user", "x")))
        }
        assertEquals("part", e.partialText)
        assertEquals(200, e.httpCode)
        assertEquals(StreamingChatFailure.SSE_ERROR, e.failure)
        assertEquals("Stream SSE error", e.message)
        assertNull(e.cause)
        assertFalse(e.stackTraceToString().contains("billing"))
    }

    @Test
    fun sseErrorMessageDoesNotExposeGatewayOrArbitraryProviderKeys() {
        val key = "opaque-gateway-secret-123456"
        val token = "ordinary.provider.key/123+opaque-value"
        server.createContext("/v1/chat/completions") { ex ->
            respond(ex, "data: {\"error\":{\"message\":\"billing $key Bearer $token\"}}\n\n")
        }
        val e = assertFailsWith<StreamingChatException> {
            StreamingChatClient(config().copy(gatewayKey = key))
                .stream("m", listOf(ChatClient.Message("user", "x")))
        }
        assertFalse(e.message!!.contains(key))
        assertFalse(e.message!!.contains(token))
        assertEquals(StreamingChatFailure.SSE_ERROR, e.failure)
        assertEquals("Stream SSE error", e.message)
        assertNull(e.cause)
        for (secret in listOf("billing", key, token)) {
            assertFalse(e.stackTraceToString().contains(secret))
        }
    }

    @Test
    fun requestHasStreamTrueAndAcceptSse() {
        var seenBody = ""
        var seenAccept: String? = null
        server.createContext("/v1/chat/completions") { ex ->
            seenAccept = ex.requestHeaders.getFirst("Accept")
            seenBody = ex.requestBody.bufferedReader().readText()
            respond(ex, "data: [DONE]\n\n")
        }
        StreamingChatClient(config()).stream("m", listOf(ChatClient.Message("user", "x")))
        assertTrue(seenBody.contains("\"stream\":true"))
        assertEquals("text/event-stream", seenAccept)
    }

    @Test
    fun arbitraryErrorJsonShapesNeverLeakMessagesOrCauseAndNeverReplay() {
        val key = "ordinary.provider.key/123+opaque-value"
        val errors = listOf(
            """{"message":"billing $key","APIKey":"$key","code":"$key","status":501}""",
            """["$key",{"nested":{"APIKey":"$key"}}]""",
            "\"$key\"",
        )
        val n = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { ex ->
            val error = errors[n.getAndIncrement() % errors.size]
            respond(ex, """data: {"choices":[{"delta":{"content":"part"}}]}""" + "\n\n" +
                "data: {\"error\":$error}\n\n")
        }
        for (i in errors.indices) {
            val e = assertFailsWith<StreamingChatException> {
                StreamingChatClient(config()).stream("m", listOf(ChatClient.Message("user", "x")))
            }
            assertEquals("part", e.partialText)
            assertEquals(StreamingChatFailure.SSE_ERROR, e.failure)
            assertEquals("Stream SSE error", e.message)
            assertEquals(200, e.httpCode)
            assertNull(e.cause)
            assertFalse(e.stackTraceToString().contains(key))
            assertFalse(e.stackTraceToString().contains("billing"))
            assertEquals(i + 1, n.get())
        }
    }

    @Test
    fun callbackErrorsUseRealResponseStatusAndNeverExposeRawFailureOrReplay() {
        val key = "ordinary.callback.key/123+opaque-value"
        val n = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { ex ->
            n.incrementAndGet()
            respond(ex, """data: {"choices":[{"delta":{"content":"part"}}]}""" + "\n\ndata: [DONE]\n\n")
        }
        val failures = listOf(
            ChatException("billing $key", 405),
            ChatException("billing $key", 501),
            IOException("billing $key"),
            IllegalStateException("billing $key"),
            StreamingChatException(StreamingChatFailure.SSE_ERROR, key, 501),
        )
        for ((i, failure) in failures.withIndex()) {
            val e = assertFailsWith<StreamingChatException> {
                StreamingChatClient(config()).stream("m", listOf(ChatClient.Message("user", "x"))) {
                    throw failure
                }
            }
            assertEquals("part", e.partialText)
            assertEquals(StreamingChatFailure.CALLBACK_FAILURE, e.failure)
            assertEquals("Stream callback failure", e.message)
            assertEquals(200, e.httpCode)
            assertNull(e.cause)
            assertFalse(e.stackTraceToString().contains(key))
            assertFalse(e.stackTraceToString().contains("billing"))
            assertEquals(i + 1, n.get())
        }
    }

    @Test
    fun oversizedUnterminatedCommentFailsBeforeEofAndKeepsAcceptedDeltas() {
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        server.createContext("/v1/chat/completions") { ex ->
            requests.incrementAndGet()
            ex.requestBody.close()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            try {
                ex.responseBody.write((
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\",\"reasoning_content\":\"plan\"}}]}\n" +
                        ":" + "x".repeat(128)
                    ).toByteArray(Charsets.UTF_8))
                ex.responseBody.flush()
                release.await(10, TimeUnit.SECONDS)
            } catch (_: IOException) {
                // The bounded client may close before the handler finishes flushing.
            } finally { ex.close() }
        }
        val result = executor.submit<StreamingChatException> {
            assertFailsWith<StreamingChatException> {
                StreamingChatClient(config().copy(readTimeout = Duration.ofSeconds(10)),
                    StreamingChatLimits(maxLineBytes = 128)).stream("m", listOf(ChatClient.Message("user", "x")))
            }
        }
        try {
            val failure = result.get(3, TimeUnit.SECONDS)
            assertEquals(StreamingChatFailure.RESPONSE_LIMIT, failure.failure)
            assertEquals("ok", failure.partialText)
            assertEquals("plan", failure.partialReasoning)
            assertEquals(200, failure.httpCode)
            assertNull(failure.cause)
            assertEquals(1, requests.get())
            assertEquals(1L, release.count)
        } finally {
            release.countDown()
            result.cancel(true)
            executor.shutdownNow()
        }
    }

    @Test
    fun ignoredCommentsAndUnknownEventsCountTowardResponseBudget() {
        val first = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\r\n"
        val ignored = ": heartbeat\r\nevent: ping\ndata: {\"unknown\":true}\n\n"
        val body = first + ignored + "data: [DONE]\n"
        val requests = AtomicInteger()
        server.createContext("/v1/chat/completions") { ex ->
            requests.incrementAndGet()
            respond(ex, body)
        }
        val failure = assertFailsWith<StreamingChatException> {
            StreamingChatClient(config(), StreamingChatLimits(
                maxResponseBytes = (first + ignored).toByteArray(Charsets.UTF_8).size.toLong() - 1,
            )).stream("m", listOf(ChatClient.Message("user", "x")))
        }
        assertEquals(StreamingChatFailure.RESPONSE_LIMIT, failure.failure)
        assertEquals("ok", failure.partialText)
        assertEquals(1, requests.get())
    }

    @Test
    fun exactUtf8ResponseBudgetAndAllLineEndingsRemainSuccessful() {
        val event = "data: {\"choices\":[{\"delta\":{\"content\":\"你🙂\"}}]}"
        val body = ": comment\r\n" + event + "\rdata: [DONE]\n"
        server.createContext("/v1/chat/completions") { ex -> respond(ex, body) }
        val output = StreamingChatClient(config(), StreamingChatLimits(
            maxLineBytes = event.toByteArray(Charsets.UTF_8).size,
            maxResponseBytes = body.toByteArray(Charsets.UTF_8).size.toLong(),
        )).stream("m", listOf(ChatClient.Message("user", "x")))
        assertEquals("你🙂", output)
    }

    @Test
    fun ongoingHeartbeatsCannotOutliveOverallDeadline() {
        val release = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        val heartbeats = AtomicInteger()
        val requests = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        server.createContext("/v1/chat/completions") { ex ->
            requests.incrementAndGet()
            ex.requestBody.close()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            try {
                ex.responseBody.write("data: {\"choices\":[{\"delta\":{\"content\":\"part\"}}]}\n\n".toByteArray())
                ex.responseBody.flush()
                while (!release.await(25, TimeUnit.MILLISECONDS)) {
                    ex.responseBody.write(": heartbeat\n\n".toByteArray())
                    ex.responseBody.flush()
                    heartbeats.incrementAndGet()
                }
            } catch (_: IOException) { /* Deadline closes the socket. */ }
            finally { ex.close() }
        }
        val result = executor.submit<StreamingChatException> {
            assertFailsWith<StreamingChatException> {
                StreamingChatClient(config().copy(readTimeout = Duration.ofSeconds(10)),
                    StreamingChatLimits(maxDuration = Duration.ofSeconds(2)))
                    .stream("m", listOf(ChatClient.Message("user", "x"))) { accepted.countDown() }
            }
        }
        try {
            assertTrue(accepted.await(3, TimeUnit.SECONDS))
            val failure = result.get(4, TimeUnit.SECONDS)
            assertEquals(StreamingChatFailure.DEADLINE_EXCEEDED, failure.failure)
            assertEquals("part", failure.partialText)
            assertEquals(200, failure.httpCode)
            assertTrue(heartbeats.get() > 0)
            assertEquals(1, requests.get())
            assertEquals(1L, release.count)
        } finally {
            release.countDown()
            result.cancel(true)
            executor.shutdownNow()
        }
    }

    @Test
    fun cancelClosesSilentBodyReadWithoutWaitingForReadTimeoutOrEof() {
        val release = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        val control = ChatRequestControl()
        val requests = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        server.createContext("/v1/chat/completions") { ex ->
            requests.incrementAndGet()
            ex.requestBody.close()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            try {
                ex.responseBody.write("data: {\"choices\":[{\"delta\":{\"content\":\"part\"}}]}\n\n".toByteArray())
                ex.responseBody.flush()
                release.await(10, TimeUnit.SECONDS)
            } catch (_: IOException) { /* Cancellation closes the socket. */ }
            finally { ex.close() }
        }
        val result = executor.submit<CancellationException> {
            assertFailsWith<CancellationException> {
                StreamingChatClient(config().copy(readTimeout = Duration.ofSeconds(10)), requestControl = control)
                    .stream("m", listOf(ChatClient.Message("user", "x"))) { accepted.countDown() }
            }
        }
        try {
            assertTrue(accepted.await(3, TimeUnit.SECONDS))
            control.cancel()
            assertEquals("Chat request cancelled", result.get(3, TimeUnit.SECONDS).message)
            assertEquals(1L, release.count)
            assertEquals(1, requests.get())
        } finally {
            release.countDown()
            control.cancel()
            result.cancel(true)
            executor.shutdownNow()
        }
    }

    @Test
    fun deadlineClosesSilentResponseHeadersAndNeverReplays() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        server.createContext("/v1/chat/completions") { ex ->
            requests.incrementAndGet()
            ex.requestBody.close()
            entered.countDown()
            try { release.await(10, TimeUnit.SECONDS) } finally { ex.close() }
        }
        val result = executor.submit<StreamingChatException> {
            assertFailsWith<StreamingChatException> {
                StreamingChatClient(config().copy(readTimeout = Duration.ofSeconds(10)),
                    StreamingChatLimits(maxDuration = Duration.ofSeconds(2)))
                    .stream("m", listOf(ChatClient.Message("user", "x")))
            }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val failure = result.get(4, TimeUnit.SECONDS)
            assertEquals(StreamingChatFailure.DEADLINE_EXCEEDED, failure.failure)
            assertEquals("", failure.partialText)
            assertNull(failure.httpCode)
            assertEquals(1, requests.get())
            assertEquals(1L, release.count)
        } finally {
            release.countDown()
            result.cancel(true)
            executor.shutdownNow()
        }
    }

    @Test
    fun headersMayTakeMoreThanOneSecondWithinConfiguredReadTimeout() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        server.createContext("/v1/chat/completions") { ex ->
            ex.requestBody.close()
            entered.countDown()
            try {
                if (release.await(10, TimeUnit.SECONDS)) respond(ex, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\ndata: [DONE]\n")
            } finally { ex.close() }
        }
        val result = executor.submit<String> {
            StreamingChatClient(config().copy(readTimeout = Duration.ofSeconds(5)))
                .stream("m", listOf(ChatClient.Message("user", "x")))
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { result.get(1200, TimeUnit.MILLISECONDS) }
            release.countDown()
            assertEquals("ok", result.get(3, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            result.cancel(true)
            executor.shutdownNow()
        }
    }

    @Test
    fun retryAfterErrorsAndRedirectNeverReplayBillablePost() {
        val status = AtomicInteger(503)
        val requests = AtomicInteger()
        server.createContext("/v1/chat/completions") { ex ->
            requests.incrementAndGet()
            ex.requestBody.close()
            ex.responseHeaders.add("Retry-After", "0")
            ex.responseHeaders.add("Location", "/redirected-chat")
            ex.sendResponseHeaders(status.get(), -1)
            ex.close()
        }
        server.createContext("/redirected-chat") { ex ->
            requests.incrementAndGet()
            respond(ex, "data: [DONE]\n")
        }
        for ((index, code) in listOf(503, 408, 307).withIndex()) {
            status.set(code)
            val failure = assertFailsWith<ChatException> {
                StreamingChatClient(config()).stream("m", listOf(ChatClient.Message("user", "x")))
            }
            assertEquals(code, failure.httpCode)
            assertEquals(index + 1, requests.get())
        }
    }

    @Test
    fun errorStatusDoesNotReadOrWaitForUnfinishedResponseBody() {
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        server.createContext("/v1/chat/completions") { ex ->
            ex.requestBody.close()
            ex.sendResponseHeaders(503, 0)
            try {
                ex.responseBody.write('x'.code)
                ex.responseBody.flush()
                release.await(10, TimeUnit.SECONDS)
            } catch (_: IOException) { /* The client cancels the error response immediately. */ }
            finally { ex.close() }
        }
        val result = executor.submit<ChatException> {
            assertFailsWith<ChatException> {
                StreamingChatClient(config().copy(readTimeout = Duration.ofSeconds(10)))
                    .stream("m", listOf(ChatClient.Message("user", "x")))
            }
        }
        try {
            val failure = result.get(3, TimeUnit.SECONDS)
            assertEquals(503, failure.httpCode)
            assertEquals("stream -> HTTP 503", failure.message)
            assertEquals(1L, release.count)
        } finally {
            release.countDown()
            result.cancel(true)
            executor.shutdownNow()
        }
    }
}

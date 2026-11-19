package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingChatDeadlineTest {
    @TempDir lateinit var tmp: File

    @Test fun fallbackSlowDripUsesRemainingSessionDeadlineAndCannotRestartIt() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val streamEntered = CountDownLatch(1)
        val allow405 = CountDownLatch(1)
        val fallbackEntered = CountDownLatch(1)
        val closeFallback = CountDownLatch(1)
        val requests = AtomicInteger()
        val fallbackStarted = AtomicLong()
        val dripped = AtomicInteger()
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.close()
            try {
                when (requests.incrementAndGet()) {
                    1 -> {
                        streamEntered.countDown()
                        if (allow405.await(10, TimeUnit.SECONDS)) exchange.sendResponseHeaders(405, -1)
                    }
                    2 -> {
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.write("{\"choices\":[{\"message\":{\"content\":\"".toByteArray(Charsets.UTF_8))
                        exchange.responseBody.flush()
                        fallbackStarted.set(System.nanoTime())
                        fallbackEntered.countDown()
                        // Frequent bytes defeat idle read timeouts. The response stays open and
                        // incomplete, so only the original session deadline can end the request.
                        while (!closeFallback.await(20, TimeUnit.MILLISECONDS)) {
                            exchange.responseBody.write('x'.code)
                            exchange.responseBody.flush()
                            dripped.incrementAndGet()
                        }
                    }
                    else -> exchange.sendResponseHeaders(500, -1)
                }
            } catch (_: IOException) {
                // The shared deadline cancels the fallback socket while this handler is active.
            } finally {
                exchange.close()
            }
        }
        server.start()

        val executor = Executors.newSingleThreadExecutor()
        val control = ChatRequestControl()
        val budget = Duration.ofSeconds(2)
        val config = GatewayConfig(gatewayKey = "deadline-fixture-key",
            gatewayBaseUrl = "http://127.0.0.1:${server.address.port}", readTimeout = Duration.ofSeconds(5))
        val store = SessionStore(File(tmp, "shared-deadline.jsonl"))
        // Initialize the HTTP clients before starting the deadline to avoid measuring class-load
        // overhead. Deliberately leave the ordinary client's own deadline at its ten-minute default.
        val session = StreamingChatSession(
            StreamingChatClient(config, StreamingChatLimits(maxDuration = budget)),
            ChatClient(config), store, "fixture-model", requestControl = control,
        )
        val result = executor.submit<StreamingChatException> {
            assertFailsWith<StreamingChatException> { session.send("synthetic deadline prompt") }
        }
        try {
            assertTrue(streamEntered.await(3, TimeUnit.SECONDS))
            // The first HTTP request spends most of the two-second session allowance waiting
            // behind a latch. No sleeping or reliance on slow DNS/response headers is needed.
            assertFailsWith<TimeoutException> { result.get(1200, TimeUnit.MILLISECONDS) }
            allow405.countDown()
            assertTrue(fallbackEntered.await(3, TimeUnit.SECONDS))

            // A renewed two-second fallback budget would still be running after this wait.
            val failure = result.get(1500, TimeUnit.MILLISECONDS)
            val fallbackElapsed = System.nanoTime() - fallbackStarted.get()
            assertEquals(StreamingChatFailure.DEADLINE_EXCEEDED, failure.failure)
            assertEquals("Stream deadline exceeded", failure.message)
            assertNull(failure.cause)
            assertTrue(fallbackElapsed < budget.toNanos(), "Fallback received a renewed session budget")
            assertTrue(dripped.get() > 0)
            assertEquals(2, requests.get())
            assertEquals(1L, closeFallback.count)
            assertTrue(session.messages().isEmpty())
            val records = store.readAll()
            assertEquals(listOf("user", "error"), records.map { it.role })
            assertEquals("chat:fixture-model:fallback", records.last().engine)
            assertEquals("Stream deadline exceeded; partial characters=0", records.last().text)
        } finally {
            control.cancel()
            allow405.countDown()
            closeFallback.countDown()
            result.cancel(true)
            executor.shutdownNow()
            server.stop(0)
        }
    }
}

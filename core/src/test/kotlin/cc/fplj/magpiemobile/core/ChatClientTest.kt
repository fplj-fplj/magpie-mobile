package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}

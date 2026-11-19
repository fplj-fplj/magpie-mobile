package cc.fplj.magpiemobile.core

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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsClientTest {

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

    @Test
    fun parsesKnownFieldsAndIgnoresUnknown() {
        server.createContext("/v1/models") { ex ->
            val body = """
                {"object":"list","data":[
                  {"id":"deepseek/deepseek-v4","display_name":"DeepSeek V4","owned_by":"deepseek",
                   "context_window":200000,"reasoning_levels":[{"effort":"low"}],"future_field":1},
                  {"id":"group/fast","display_name":"Fast","owned_by":"magpie"}
                ]}
            """.trimIndent()
            ex.getResponseHeaders().add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val models = ModelsClient(config()).list()
        assertEquals(2, models.size)
        assertEquals("deepseek/deepseek-v4", models[0].id)
        assertEquals("DeepSeek V4", models[0].displayName)
        assertEquals(200000L, models[0].contextWindow)
        assertNull(models[1].contextWindow) // 缺失记 null，不猜默认值
    }

    @Test
    fun missingOptionalFieldsBecomeNull() {
        server.createContext("/v1/models") { ex ->
            val body = """{"data":[{"id":"m1"}]}"""
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val models = ModelsClient(config()).list()
        assertEquals("m1", models[0].id)
        assertNull(models[0].displayName)
        assertNull(models[0].ownedBy)
    }

    @Test
    fun missingDataArrayThrows() {
        server.createContext("/v1/models") { ex ->
            val body = """{"object":"list"}"""
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val e = assertFailsWith<ModelsException> { ModelsClient(config()).list() }
        assertTrue(e.message!!.contains("data"))
    }

    @Test
    fun errorPropagatesCode() {
        server.createContext("/v1/models") { ex -> ex.sendResponseHeaders(401, -1) }
        val e = assertFailsWith<ModelsException> { ModelsClient(config()).list() }
        assertEquals(401, e.httpCode)
    }

    @Test fun nullAndMissingMetadataStayUnknownAndBlankIdsAreSkipped() {
        server.createContext("/v1/models") { exchange ->
            val body = """{"data":[{"id":"m","display_name":null,"owned_by":null,"context_window":null},{"id":""},{"id":null},{}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        assertEquals(listOf(ModelEntry("m", null, null, null)), ModelsClient(config()).list())
    }

    @Test fun malformedCatalogShapesExposeOnlyFixedSafeFailure() {
        val marker = "synthetic-untrusted-catalog-marker"
        val bodies = listOf(
            "{broken $marker",
            "\"$marker\"",
            """{"data":["$marker"]}""",
            """{"data":[{"id":{"value":"$marker"}}]}""",
            """{"data":[{"id":"m","display_name":["$marker"]}]}""",
            """{"data":[{"id":"m","owned_by":{"value":"$marker"}}]}""",
            """{"data":[{"id":"m","context_window":{"value":"$marker"}}]}""",
        )
        val selected = AtomicReference(bodies.first())
        server.createContext("/v1/models") { exchange ->
            val bytes = selected.get().toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        for (body in bodies) {
            selected.set(body)
            val failure = assertFailsWith<ModelsException> { ModelsClient(config()).list() }
            assertEquals(200, failure.httpCode)
            assertEquals("models: invalid model catalog", failure.message)
            assertNull(failure.cause)
            assertTrue(!failure.stackTraceToString().contains(marker))
        }
    }

    @Test fun defaultFourMiBCatalogRetainsKnownMetadataAndUtf8() {
        val maxBytes = 4 * 1024 * 1024
        val prefix = """{"data":[{"id":"m","display_name":"你好🙂","owned_by":"fixture","context_window":"128000"}],"padding":""""
        val suffix = "\"}"
        val body = prefix + "x".repeat(maxBytes - (prefix + suffix).toByteArray(Charsets.UTF_8).size) + suffix
        val bytes = body.toByteArray(Charsets.UTF_8)
        assertEquals(maxBytes, bytes.size)
        server.createContext("/v1/models") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        assertEquals(listOf(ModelEntry("m", "你好🙂", "fixture", 128000L)), ModelsClient(config()).list())
    }

    @Test fun oversizedCatalogFailsWithoutReturningItsValidLeadingRows() {
        val body = """{"data":[{"id":"must-not-be-returned"}],"padding":"""" + "x".repeat(64) + "\"}"
        server.createContext("/v1/models") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            try { exchange.responseBody.write(body.toByteArray(Charsets.UTF_8)) }
            catch (_: IOException) { /* The bounded reader cancels immediately. */ }
            finally { exchange.close() }
        }
        assertFailsWith<GatewayResponseLimitException> { ModelsClient(config(), maxResponseBytes = 48).list() }
    }

    @Test fun callerCancellationInterruptsSilentHeadersAndKeepsItsException() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val control = ChatRequestControl()
        server.createContext("/v1/models") { exchange ->
            entered.countDown()
            try { release.await(10, TimeUnit.SECONDS) } finally { exchange.close() }
        }
        val result = executor.submit<CancellationException> {
            assertFailsWith<CancellationException> {
                ModelsClient(config().copy(readTimeout = Duration.ofSeconds(10)), requestControl = control).list()
            }
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

    @Test fun ongoingCatalogBytesCannotExtendTotalDeadline() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val control = ChatRequestControl()
        val requests = AtomicInteger()
        val dripped = AtomicInteger()
        server.createContext("/v1/models") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.write("{\"data\":[{\"id\":\"".toByteArray())
                exchange.responseBody.flush()
                entered.countDown()
                while (!release.await(20, TimeUnit.MILLISECONDS)) {
                    exchange.responseBody.write('x'.code)
                    exchange.responseBody.flush()
                    dripped.incrementAndGet()
                }
            } catch (_: IOException) { /* The deadline interrupts the active response. */ }
            finally { exchange.close() }
        }
        val client = ModelsClient(config().copy(readTimeout = Duration.ofSeconds(10)), requestControl = control,
            maxDuration = Duration.ofSeconds(2))
        val result = executor.submit<StreamingChatException> {
            assertFailsWith<StreamingChatException> { client.list() }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val failure = result.get(4, TimeUnit.SECONDS)
            assertEquals(StreamingChatFailure.DEADLINE_EXCEEDED, failure.failure)
            assertNull(failure.cause)
            assertTrue(dripped.get() > 0)
            assertEquals(1, requests.get())
            assertEquals(1L, release.count)
        } finally {
            control.cancel()
            release.countDown()
            result.cancel(true)
            executor.shutdownNow()
        }
    }

    @Test fun invalidPublicBudgetsAreRejectedBeforeNetwork() {
        val requests = AtomicInteger()
        server.createContext("/v1/models") { requests.incrementAndGet(); it.close() }
        assertFailsWith<IllegalArgumentException> { ModelsClient(config(), maxResponseBytes = 0) }
        assertFailsWith<IllegalArgumentException> { ModelsClient(config(), maxResponseBytes = -1) }
        for (duration in listOf(Duration.ZERO, Duration.ofNanos(-1), Duration.ofDays(2))) {
            assertFailsWith<IllegalArgumentException> { ModelsClient(config(), maxDuration = duration) }
        }
        assertEquals(0, requests.get())
    }
}

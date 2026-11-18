package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderModelCapabilityClientTest {

    private lateinit var server: HttpServer
    private var port: Int = 0
    private val chatRequests = CopyOnWriteArrayList<String>()

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

    private fun client() = ProviderModelCapabilityClient(
        GatewayConfig(
            gatewayKey = "gateway-test-key",
            gatewayBaseUrl = "http://127.0.0.1:$port",
        ),
    )

    private fun catalog(vararg rows: String) {
        server.createContext("/v1/models") { exchange ->
            respondJson(exchange, """{"object":"list","data":[${rows.joinToString(",")}]}""")
        }
    }

    @Test
    fun exactProviderNamespaceRunsAllThreeRealChecks() {
        catalog(
            """{"id":"other/vendor/model","owned_by":"other","display_name":"Wrong owner"}""",
            """{"id":"saved/vendor/model","owned_by":"saved","display_name":"Saved Model"}""",
        )
        server.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            chatRequests += body
            when {
                body.contains("\"stream\":true") -> respondSse(
                    exchange,
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"stream thought\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"stream ok\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                )
                body.contains("\"tools\"") -> respondJson(
                    exchange,
                    """
                        {"choices":[{"message":{
                          "role":"assistant","content":null,
                          "tool_calls":[{"id":"call-1","type":"function","function":{
                            "name":"get_current_time","arguments":"{}"
                          }}]
                        }}]}
                    """.trimIndent(),
                )
                else -> respondJson(
                    exchange,
                    """
                        {"choices":[{"message":{
                          "role":"assistant","content":"plain ok",
                          "reasoning_content":"plain thought"
                        }}]}
                    """.trimIndent(),
                )
            }
        }

        val progress = CopyOnWriteArrayList<ProviderCapabilityResult>()
        val report = client().run("saved", "vendor/model", progress::add)

        val route = assertNotNull(report.route)
        assertEquals("saved/vendor/model", route.gatewayModelId)
        assertEquals(
            listOf(
                ProviderCapabilityCheck.NON_STREAMING,
                ProviderCapabilityCheck.STREAMING,
                ProviderCapabilityCheck.TOOL_CALL,
            ),
            report.results.map { it.check },
        )
        assertTrue(report.results.all { it.status == ProviderCapabilityStatus.PASSED })
        assertEquals("plain ok", report.results[0].preview)
        assertEquals("plain thought", report.results[0].reasoning)
        assertEquals("stream ok", report.results[1].preview)
        assertEquals("stream thought", report.results[1].reasoning)
        assertEquals("get_current_time {}", report.results[2].preview)
        assertEquals(ProviderCapabilityCheck.entries.size * 2, progress.size)
        assertEquals(
            listOf(
                ProviderCapabilityStatus.RUNNING,
                ProviderCapabilityStatus.PASSED,
                ProviderCapabilityStatus.RUNNING,
                ProviderCapabilityStatus.PASSED,
                ProviderCapabilityStatus.RUNNING,
                ProviderCapabilityStatus.PASSED,
            ),
            progress.map { it.status },
        )
        assertEquals(3, chatRequests.size)
        assertTrue(chatRequests.all { it.contains("\"model\":\"saved/vendor/model\"") })
        assertFalse(chatRequests.any { it.contains("\"model\":\"vendor/model\"") })
    }

    @Test
    fun wrongOwnerIsUnavailableAndSendsNoCheck() {
        catalog(
            """{"id":"saved/vendor/model","owned_by":"magpie","display_name":"Global decoy"}""",
        )
        val calls = AtomicInteger()
        server.createContext("/v1/chat/completions") { exchange ->
            calls.incrementAndGet()
            respondJson(exchange, """{"choices":[{"message":{"content":"unexpected"}}]}""")
        }

        val progress = CopyOnWriteArrayList<ProviderCapabilityResult>()
        val report = client().run("saved", "vendor/model", progress::add)

        assertNull(report.route)
        assertEquals(ProviderCapabilityCheck.entries.size, report.results.size)
        assertTrue(report.results.all { it.status == ProviderCapabilityStatus.UNAVAILABLE })
        assertTrue(report.results.all { it.safeMessage.contains("没有归属于此提供商") })
        assertEquals(ProviderCapabilityCheck.entries.size, progress.size)
        assertEquals(0, calls.get())
    }

    @Test
    fun duplicateProviderRoutesAreRejectedBeforeAnyNetworkCheck() {
        catalog(
            """{"id":"saved/vendor/model","owned_by":"saved","display_name":"One"}""",
            """{"id":"saved/vendor/model","owned_by":"saved","display_name":"Two"}""",
        )
        val calls = AtomicInteger()
        server.createContext("/v1/chat/completions") { exchange ->
            calls.incrementAndGet()
            respondJson(exchange, """{"choices":[{"message":{"content":"unexpected"}}]}""")
        }

        val report = client().run("saved", "vendor/model")

        assertNull(report.route)
        assertTrue(report.results.all { it.status == ProviderCapabilityStatus.UNAVAILABLE })
        assertTrue(report.results.all { it.safeMessage.contains("多个相同模型路由") })
        assertEquals(0, calls.get())
    }

    @Test
    fun cancellationAfterARequestDoesNotStartTheNextCheck() {
        catalog(
            """{"id":"saved/vendor/model","owned_by":"saved","display_name":"Saved Model"}""",
        )
        val calls = AtomicInteger()
        server.createContext("/v1/chat/completions") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.readBytes()
            respondJson(
                exchange,
                """{"choices":[{"message":{"role":"assistant","content":"plain ok"}}]}""",
            )
        }
        var cancelRequested = false

        assertFailsWith<CancellationException> {
            client().run(
                providerId = "saved",
                savedModelId = "vendor/model",
                onProgress = { result ->
                    if (result.check == ProviderCapabilityCheck.NON_STREAMING &&
                        result.status == ProviderCapabilityStatus.RUNNING
                    ) {
                        cancelRequested = true
                    }
                },
                ensureActive = {
                    if (cancelRequested) throw CancellationException("test cancellation")
                },
            )
        }

        assertEquals(1, calls.get())
    }

    @Test
    fun malformedToolArgumentsFailWithoutReturningGatewayKey() {
        catalog(
            """{"id":"saved/vendor/model","owned_by":"saved","display_name":"Saved Model"}""",
        )
        server.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            chatRequests += body
            when {
                body.contains("\"stream\":true") -> respondSse(
                    exchange,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"stream ok\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                )
                body.contains("\"tools\"") -> respondJson(
                    exchange,
                    """
                        {"choices":[{"message":{
                          "role":"assistant","content":"tool arguments were invalid",
                          "tool_calls":[{"id":"call-1","type":"function","function":{
                            "name":"get_current_time","arguments":"not-json"
                          }}]
                        }}]}
                    """.trimIndent(),
                )
                else -> respondJson(
                    exchange,
                    """{"choices":[{"message":{"role":"assistant","content":"plain ok"}}]}""",
                )
            }
        }

        val report = client().run("saved", "vendor/model")
        val tool = report.results.single { it.check == ProviderCapabilityCheck.TOOL_CALL }

        assertEquals(ProviderCapabilityStatus.FAILED, tool.status)
        assertTrue(tool.safeMessage.contains("格式或参数无效"))
        assertFalse(tool.toString().contains("gateway-test-key"))
        assertFalse(tool.preview.contains("gateway-test-key"))
    }

    private fun respondJson(
        exchange: HttpExchange,
        body: String,
        status: Int = 200,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respondSse(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

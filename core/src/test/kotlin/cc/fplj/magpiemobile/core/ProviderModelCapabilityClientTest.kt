package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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

    @Test
    fun emptyOrNonStringOrdinaryMessagesFailWithoutBreakingOtherChecks() {
        val message = AtomicReference("{}")
        capabilityResponses(ordinary = { exchange ->
            respondJson(exchange, """{"choices":[{"message":${message.get()}}]}""")
        })

        for (invalid in listOf(
            """{"role":"assistant"}""",
            """{"content":null,"reasoning_content":null}""",
            """{"content":42,"reasoning_content":false,"reasoning":{}}""",
            """{"content":[],"reasoning_content":[]}""",
            """{"content":"\u0000\u0007 \n\t","reasoning_content":" \n\t"}""",
        )) {
            message.set(invalid)
            val report = client().run("saved", "vendor/model")

            assertEquals(ProviderCapabilityStatus.FAILED, report.results[0].status)
            assertEquals("上游返回了无法验证的响应", report.results[0].safeMessage)
            assertEquals("", report.results[0].preview)
            assertEquals("", report.results[0].reasoning)
            assertTrue(report.results.drop(1).all { it.status == ProviderCapabilityStatus.PASSED })
        }
    }

    @Test
    fun emptySseAndNullDeltasDoNotPassJustBecauseDoneArrived() {
        val stream = AtomicReference("data: [DONE]\n\n")
        capabilityResponses(stream = { stream.get() })

        for (invalid in listOf(
            "data: [DONE]\n\n",
            "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n" +
                "data: [DONE]\n\n",
            "data: {\"choices\":[{\"delta\":{\"content\":null,\"reasoning_content\":null}}]}\n\n" +
                "data: [DONE]\n\n",
            "data: {\"choices\":[{\"delta\":{\"content\":\"\\u0000 \\n\\t\",\"reasoning_content\":\" \\n\"}}]}\n\n" +
                "data: [DONE]\n\n",
        )) {
            stream.set(invalid)
            val report = client().run("saved", "vendor/model")

            assertEquals(ProviderCapabilityStatus.PASSED, report.results[0].status)
            assertEquals(ProviderCapabilityStatus.FAILED, report.results[1].status)
            assertEquals("上游返回了无法验证的响应", report.results[1].safeMessage)
            assertEquals(ProviderCapabilityStatus.PASSED, report.results[2].status)
        }
    }

    @Test
    fun reasoningOnlyResponsesAreValidAndKeepTheirThinkingDetails() {
        capabilityResponses(
            ordinary = { exchange ->
                respondJson(
                    exchange,
                    """{"choices":[{"message":{"content":null,"reasoning_content":null,"reasoning":"  plain thought  "}}]}""",
                )
            },
            stream = {
                "data: {\"choices\":[{\"delta\":{\"content\":null,\"reasoning_content\":\"  stream thought  \"}}]}\n\n" +
                    "data: [DONE]\n\n"
            },
        )

        val report = client().run("saved", "vendor/model")

        assertTrue(report.results.all { it.status == ProviderCapabilityStatus.PASSED })
        assertEquals("", report.results[0].preview)
        assertEquals("plain thought", report.results[0].reasoning)
        assertEquals("", report.results[1].preview)
        assertEquals("stream thought", report.results[1].reasoning)
    }

    @Test
    fun leadingWhitespaceBeyondPreviewLimitDoesNotHideRealStreamContent() {
        capabilityResponses(stream = {
            "data: {\"choices\":[{\"delta\":{\"content\":\"${" ".repeat(400)}\",\"reasoning_content\":\"${" ".repeat(2_100)}\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"stream ok\",\"reasoning_content\":\"stream thought\"}}]}\n\n" +
                "data: [DONE]\n\n"
        })

        val report = client().run("saved", "vendor/model")

        assertEquals(ProviderCapabilityStatus.PASSED, report.results[1].status)
        assertEquals("stream ok", report.results[1].preview)
        assertEquals("stream thought", report.results[1].reasoning)
    }

    @Test
    fun oversizedDeclaredOrdinaryResponseFailsAndOtherChecksStillRun() {
        capabilityResponses(ordinary = { exchange ->
            respondJson(exchange, """{"choices":[{"message":{"content":"${"x".repeat(256 * 1024)}"}}]}""")
        })

        assertOrdinaryResponseLimit(client().run("saved", "vendor/model"))
    }

    @Test
    fun oversizedChunkedOrdinaryResponseFailsWithoutTrustingContentLength() {
        capabilityResponses(ordinary = { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0) // chunked, no declared length
            exchange.responseBody.use { output ->
                runCatching {
                    output.write("{\"choices\":[{\"message\":{\"content\":\"".toByteArray(Charsets.UTF_8))
                    val chunk = "x".repeat(8 * 1024).toByteArray(Charsets.UTF_8)
                    repeat(34) { output.write(chunk) }
                    output.write("\"}}]}".toByteArray(Charsets.UTF_8))
                } // the bounded reader may disconnect before the fixture finishes writing
            }
        })

        assertOrdinaryResponseLimit(client().run("saved", "vendor/model"))
    }

    @Test
    fun oversizedUnterminatedStreamCommentFailsSafelyAndToolCheckStillRuns() {
        val secret = "fixture-comment-and-partial-secret"
        capabilityResponses(stream = {
            "data: {\"choices\":[{\"delta\":{\"content\":\"$secret\",\"reasoning_content\":\"$secret\"}}]}\n\n" +
                ": $secret" + "x".repeat(256 * 1024) // no newline, and no DONE
        })
        val progress = CopyOnWriteArrayList<ProviderCapabilityResult>()

        val report = client().run("saved", "vendor/model", progress::add)

        assertStreamResponseLimit(report, progress, secret)
    }

    @Test
    fun mixedStreamEventOverCombinedBudgetFailsWithoutPublishingEitherPartial() {
        val secret = "fixture-mixed-event-secret"
        val content = secret + "c".repeat(8 * 1024)
        val reasoning = secret + "r".repeat(8 * 1024)
        assertTrue(content.length <= 16 * 1024)
        assertTrue(reasoning.length <= 16 * 1024)
        assertTrue(content.length + reasoning.length > 16 * 1024)
        capabilityResponses(stream = {
            "data: {\"choices\":[{\"delta\":{\"content\":\"$content\",\"reasoning_content\":\"$reasoning\"}}]}\n\n" +
                "data: [DONE]\n\n"
        })
        val progress = CopyOnWriteArrayList<ProviderCapabilityResult>()

        val report = client().run("saved", "vendor/model", progress::add)

        assertStreamResponseLimit(report, progress, secret)
    }

    private fun assertStreamResponseLimit(
        report: ProviderCapabilityReport,
        progress: List<ProviderCapabilityResult>,
        secret: String,
    ) {
        assertEquals(
            listOf(ProviderCapabilityStatus.PASSED, ProviderCapabilityStatus.FAILED, ProviderCapabilityStatus.PASSED),
            report.results.map { it.status },
        )
        val stream = report.results.single { it.check == ProviderCapabilityCheck.STREAMING }
        assertEquals("响应超过测试安全上限", stream.safeMessage)
        assertEquals("", stream.preview)
        assertEquals("", stream.reasoning)
        assertEquals(3, chatRequests.size)
        assertFalse(chatRequests[0].contains("\"stream\":true"))
        assertTrue(chatRequests[1].contains("\"stream\":true"))
        assertTrue(chatRequests[2].contains("\"tools\""))
        assertEquals(stream, progress.last { it.check == ProviderCapabilityCheck.STREAMING })
        (report.results + progress).forEach { result ->
            assertFalse(result.safeMessage.contains(secret))
            assertFalse(result.preview.contains(secret))
            assertFalse(result.reasoning.contains(secret))
            assertFalse(result.toString().contains(secret))
            assertFalse(result.safeMessage.contains("gateway-test-key"))
        }
    }

    private fun assertOrdinaryResponseLimit(report: ProviderCapabilityReport) {
        assertEquals(ProviderCapabilityStatus.FAILED, report.results[0].status)
        assertEquals("响应超过测试安全上限", report.results[0].safeMessage)
        assertTrue(report.results.drop(1).all { it.status == ProviderCapabilityStatus.PASSED })
        assertEquals(3, chatRequests.size)
        assertTrue(chatRequests[0].contains("\"content\":\"hello\""))
        assertTrue(chatRequests[0].contains("\"max_tokens\":32"))
        assertFalse(chatRequests[0].contains("\"tools\""))
    }

    private fun capabilityResponses(
        ordinary: (HttpExchange) -> Unit = { exchange ->
            respondJson(exchange, """{"choices":[{"message":{"content":"plain ok"}}]}""")
        },
        stream: () -> String = {
            "data: {\"choices\":[{\"delta\":{\"content\":\"stream ok\"}}]}\n\n" +
                "data: [DONE]\n\n"
        },
    ) {
        catalog("""{"id":"saved/vendor/model","owned_by":"saved","display_name":"Saved Model"}""")
        server.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            chatRequests += body
            when {
                body.contains("\"stream\":true") -> respondSse(exchange, stream())
                body.contains("\"tools\"") -> respondJson(
                    exchange,
                    """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"get_current_time","arguments":"{}"}}]}}]}""",
                )
                else -> ordinary(exchange)
            }
        }
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

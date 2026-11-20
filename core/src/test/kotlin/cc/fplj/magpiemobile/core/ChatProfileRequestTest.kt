package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatProfileRequestTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var server: HttpServer
    private val bodies = Collections.synchronizedList(mutableListOf<JsonObject>())
    private val requests = AtomicInteger()
    private val confirmedModel = "confirmed-provider-model"
    private val prompt = "Keep this profile instruction only in outbound requests.\nPreserve this second line."
    private val thought = "private model reasoning"
    private val answer = "visible answer"

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun config() = GatewayConfig(
        gatewayKey = "test-key",
        gatewayBaseUrl = "http://127.0.0.1:${server.address.port}",
        readTimeout = Duration.ofSeconds(5),
    )

    private fun session(
        requestOptions: ChatRequestOptions = ChatRequestOptions(),
        store: SessionStore? = null,
        turnStore: ChatTurnStore? = null,
        initialMessages: List<ChatClient.Message> = emptyList(),
    ): StreamingChatSession {
        val cfg = config()
        return StreamingChatSession(
            StreamingChatClient(cfg), ChatClient(cfg), store, confirmedModel,
            turnStore = turnStore,
            initialMessages = initialMessages,
            requestOptions = requestOptions,
        )
    }

    private fun profile() = ChatProfile(
        id = "request-test",
        name = "Request profile",
        systemPrompt = prompt,
        temperature = 0.25,
        topP = 0.8,
        maxTokens = 2048,
        modelAlias = "unconfirmed-alias-must-not-replace-model",
    )

    private fun serve(block: (HttpExchange, Int) -> Unit = { ex, _ -> respond(ex, sse()) }) {
        server.createContext("/v1/chat/completions") { ex ->
            try {
                val body = ex.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                bodies.add(Json.parseToJsonElement(body).jsonObject)
                block(ex, requests.incrementAndGet())
            } finally {
                ex.close()
            }
        }
    }

    private fun respond(ex: HttpExchange, body: String, type: String = "text/event-stream") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "$type; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun sse(): String =
        "data: {\"choices\":[{\"delta\":{\"content\":null,\"reasoning_content\":null}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":null,\"reasoning_content\":\"$thought\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"$answer\"}}]}\n\n" +
            "data: [DONE]\n\n"

    private fun messages(body: JsonObject): List<ChatClient.Message> =
        body.getValue("messages").jsonArray.map {
            ChatClient.Message(it.jsonObject.getValue("role").jsonPrimitive.content,
                it.jsonObject.getValue("content").jsonPrimitive.content)
        }

    private fun assertOptions(body: JsonObject, options: ChatRequestOptions) {
        assertEquals(options.temperature, body["temperature"]?.jsonPrimitive?.double)
        assertEquals(options.topP, body["top_p"]?.jsonPrimitive?.double)
        assertEquals(options.maxTokens, body["max_tokens"]?.jsonPrimitive?.int)
    }

    private fun assertNoSampling(body: JsonObject) {
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
        assertFalse(body.containsKey("max_tokens"))
    }

    @Test
    fun customProfileReachesSseWithoutLeakingPromptOrReasoningIntoHistory() {
        serve()
        val store = SessionStore(File(tmp, "profile.jsonl"))
        val options = ChatRequestOptions.fromProfile(profile())
        val chat = session(options, store)
        val thoughts = mutableListOf<String>()
        val deltas = mutableListOf<String>()

        assertEquals(answer, chat.sendWithReasoning("first", onReasoning = thoughts::add, onDelta = deltas::add))
        assertEquals(listOf(thought), thoughts)
        assertEquals(listOf(answer), deltas)
        assertEquals(answer, chat.send("second"))

        val firstTurn = listOf(ChatClient.Message("user", "first"), ChatClient.Message("assistant", answer))
        val system = ChatClient.Message("system", prompt)
        assertEquals(listOf(system, firstTurn.first()), messages(bodies[0]))
        assertEquals(listOf(system) + firstTurn + ChatClient.Message("user", "second"), messages(bodies[1]))
        bodies.forEach { body ->
            assertOptions(body, options)
            assertTrue(body.getValue("stream").jsonPrimitive.boolean)
            assertEquals(confirmedModel, body.getValue("model").jsonPrimitive.content)
            assertEquals(1, messages(body).count { it.role == "system" })
            assertFalse(body.toString().contains(thought))
            assertFalse(body.containsKey("reasoning_content"))
        }
        assertEquals(firstTurn + ChatClient.Message("user", "second") + ChatClient.Message("assistant", answer),
            chat.messages())
        val records = store.readAll()
        assertEquals(listOf("user", "assistant", "user", "assistant"), records.map { it.role })
        assertEquals(listOf(thought, thought), records.filter { it.role == "assistant" }.map { it.reasoning })
        assertTrue(records.none { it.text == prompt || it.text.contains(thought) })
        assertFalse(File(tmp, "profile.jsonl").readText().contains("Keep this profile instruction"))
        assertEquals(2, requests.get())
    }

    @Test
    fun http405FallbackUsesExactlyTheSameProfileMessagesAndSampling() = assertProfileFallback(405)

    @Test
    fun http501FallbackUsesExactlyTheSameProfileMessagesAndSampling() = assertProfileFallback(501)

    private fun assertProfileFallback(status: Int) {
        serve { ex, request ->
            when (request) {
                1 -> ex.sendResponseHeaders(status, -1)
                2 -> respond(ex,
                    """{"choices":[{"message":{"content":"$answer","reasoning_content":"$thought"}}]}""",
                    "application/json")
                else -> respond(ex, sse())
            }
        }
        val options = ChatRequestOptions.fromProfile(profile().copy(temperature = 0.0, topP = 0.0, maxTokens = 131072))
        val store = SessionStore(File(tmp, "fallback-$status.jsonl"))
        val chat = session(options, store)
        val thoughts = mutableListOf<String>()
        assertEquals(answer, chat.sendWithReasoning("first", onReasoning = thoughts::add))
        assertEquals(listOf(thought), thoughts)
        assertEquals(2, requests.get())
        assertTrue(bodies[0].getValue("stream").jsonPrimitive.boolean)
        assertFalse(bodies[1].containsKey("stream"))
        // The fallback body differs only in the stream flag, including the exact system text.
        assertEquals(bodies[0].filterKeys { it != "stream" }, bodies[1].toMap())
        assertOptions(bodies[1], options)
        assertEquals(confirmedModel, bodies[1].getValue("model").jsonPrimitive.content)
        assertEquals(listOf(ChatClient.Message("system", prompt), ChatClient.Message("user", "first")),
            messages(bodies[1]))
        assertEquals(thought, store.readAll().last().reasoning)
        assertEquals("chat:$confirmedModel:fallback", store.readAll().last().engine)

        assertEquals(answer, chat.send("second"))
        assertEquals(3, requests.get())
        assertOptions(bodies[2], options)
        assertEquals(listOf(ChatClient.Message("system", prompt), ChatClient.Message("user", "first"),
            ChatClient.Message("assistant", answer), ChatClient.Message("user", "second")), messages(bodies[2]))
        assertFalse(bodies[2].toString().contains(thought))
        assertTrue(chat.messages().none { it.role == "system" || it.content.contains(thought) })
        assertTrue(store.readAll().none { it.role == "system" || it.text == prompt })
    }

    @Test
    fun untouchedDefaultAndLegacySessionOmitPromptAndSampling() {
        serve()
        val cfg = config()
        val legacy = StreamingChatSession(StreamingChatClient(cfg), ChatClient(cfg), null, confirmedModel)
        assertEquals(answer, legacy.send("legacy"))
        for (profile in listOf(ChatProfile.DEFAULT, ChatProfile.DEFAULT.copy())) {
            val options = ChatRequestOptions.fromProfile(profile)
            assertEquals(ChatRequestOptions(), options)
            assertEquals(answer, session(options).send("default"))
        }
        assertEquals(3, requests.get())
        bodies.forEach { body ->
            assertNoSampling(body)
            assertEquals(listOf("user"), messages(body).map { it.role })
            assertEquals(confirmedModel, body.getValue("model").jsonPrimitive.content)
        }
    }

    @Test
    fun everyModificationToDefaultUsesProfileFieldsInsteadOfTreatingDefaultIdAsAnOptOut() {
        val original = ChatProfile.DEFAULT
        val profiles = listOf(
            original.copy(id = "another-id"),
            original.copy(name = "Renamed default"),
            original.copy(systemPrompt = prompt),
            original.copy(temperature = 0.0),
            original.copy(topP = 0.0),
            original.copy(maxTokens = 1),
            original.copy(modelAlias = "a-different-alias"),
        )
        profiles.forEach { profile ->
            assertEquals(ChatRequestOptions(profile.systemPrompt, profile.temperature, profile.topP, profile.maxTokens),
                ChatRequestOptions.fromProfile(profile))
        }
    }

    @Test
    fun modifiedDefaultIdIsEffectiveAndAliasNeverOverridesConfirmedModelOrExplicitModel() {
        serve()
        val profile = ChatProfile.DEFAULT.copy(
            systemPrompt = prompt, temperature = 0.0, topP = 0.0, maxTokens = 1,
            modelAlias = "unconfirmed-alias-must-not-replace-model",
        )
        val options = ChatRequestOptions.fromProfile(profile)
        val chat = session(options)
        assertEquals(answer, chat.send("use constructor model"))
        assertEquals(answer, chat.send("use explicit model", model = "another-confirmed-model"))
        assertEquals(confirmedModel, bodies[0].getValue("model").jsonPrimitive.content)
        assertEquals("another-confirmed-model", bodies[1].getValue("model").jsonPrimitive.content)
        bodies.forEach { body ->
            assertOptions(body, options)
            assertEquals(ChatClient.Message("system", prompt), messages(body).first())
            assertEquals(1, messages(body).count { it.role == "system" })
            assertFalse(body.toString().contains(profile.modelAlias))
        }
        assertEquals(2, requests.get())
    }

    @Test
    fun blankSystemPromptIsOmittedAndZeroSamplingIsNotMistakenForAnAbsentValue() {
        serve()
        val options = ChatRequestOptions.fromProfile(profile().copy(
            systemPrompt = " \n\t ", temperature = 0.0, topP = 0.0, maxTokens = 1,
        ))
        assertEquals(answer, session(options).send("hello"))
        assertOptions(bodies.single(), options)
        assertEquals(listOf(ChatClient.Message("user", "hello")), messages(bodies.single()))
    }

    @Test
    fun roomTurnStoreBoundaryNeverReceivesSystemMessagesAndRestoredHistoryGetsOneFreshPrompt() {
        serve()
        val options = ChatRequestOptions.fromProfile(profile())
        val turns = RecordingTurnStore()
        val decoyFile = File(tmp, "room-must-not-use-jsonl.jsonl")
        val initial = listOf(ChatClient.Message("user", "restored user"), ChatClient.Message("assistant", "restored reply"))
        val chat = session(options, SessionStore(decoyFile), turns, initial)
        assertEquals(answer, chat.send("first"))
        val restored = session(options, turnStore = turns, initialMessages = chat.messages())
        assertEquals(answer, restored.send("second"))

        assertEquals(listOf("user", "assistant", "user", "assistant"), turns.records.map { it.role })
        assertTrue(turns.records.none { it.text == prompt || it.text.contains(thought) })
        assertEquals(listOf(thought, thought), turns.records.filter { it.role == "assistant" }.map { it.reasoning })
        assertFalse(decoyFile.exists())
        assertEquals(listOf(ChatClient.Message("system", prompt)) + initial + ChatClient.Message("user", "first"),
            messages(bodies[0]))
        assertEquals(listOf(ChatClient.Message("system", prompt)) + chat.messages() + ChatClient.Message("user", "second"),
            messages(bodies[1]))
        assertTrue(restored.messages().none { it.role == "system" || it.content.contains(thought) })
        assertEquals(2, requests.get())
    }

    @Test
    fun legacyClientSignaturesKeepTrailingLambdaCompatibilityAndOmitUnspecifiedSampling() {
        serve()
        val client = StreamingChatClient(config())
        val messages = listOf(ChatClient.Message("user", "hello"))
        val deltas = mutableListOf<String>()
        assertEquals(answer, client.stream(confirmedModel, messages) { deltas.add(it) })
        assertEquals(answer, client.streamWithReasoning(confirmedModel, messages) { deltas.add(it) })
        assertEquals(answer, client.streamWithOptions(confirmedModel, messages) { deltas.add(it) })
        assertEquals(listOf(answer, answer, answer), deltas)
        bodies.forEach { assertNoSampling(it) }

        assertEquals(answer, client.streamWithOptions(confirmedModel, messages, 17, 0.0, 0.0) { deltas.add(it) })
        assertOptions(bodies.last(), ChatRequestOptions(temperature = 0.0, topP = 0.0, maxTokens = 17))
        assertEquals(4, requests.get())
    }

    @Test
    fun optionsOverloadKeepsReasoningCallbacksAndOmitsUnspecifiedSamplingFields() {
        serve()
        val requestMessages = listOf(ChatClient.Message("user", "hello"))
        val thoughts = mutableListOf<String>()
        val deltas = mutableListOf<String>()
        assertEquals(answer, StreamingChatClient(config()).streamWithReasoning(
            confirmedModel, requestMessages, ChatRequestOptions(temperature = 0.0), onReasoning = thoughts::add,
        ) { deltas.add(it) })
        assertEquals(listOf(thought), thoughts)
        assertEquals(listOf(answer), deltas)
        assertOptions(bodies.single(), ChatRequestOptions(temperature = 0.0))
        assertFalse(bodies.single().containsKey("top_p"))
        assertFalse(bodies.single().containsKey("max_tokens"))
        assertEquals(requestMessages, messages(bodies.single()))
    }

    @Test
    fun requestOptionBoundariesAreAccepted() {
        assertEquals(32768, ChatRequestOptions(systemPrompt = "p".repeat(32768)).systemPrompt.length)
        for (temperature in listOf(0.0, 2.0)) {
            for (topP in listOf(0.0, 1.0)) {
                for (maxTokens in listOf(1, 131072)) {
                    assertEquals(temperature, ChatRequestOptions(temperature = temperature,
                        topP = topP, maxTokens = maxTokens).temperature)
                }
            }
        }
    }

    @Test
    fun invalidOptionsAreRejectedBeforeAnyHttpRequestOrPersistence() {
        serve()
        val storageFile = File(tmp, "invalid-options.jsonl")
        val store = SessionStore(storageFile)
        val invalid = mutableListOf<() -> ChatRequestOptions>(
            { ChatRequestOptions(systemPrompt = "p".repeat(32769)) },
            { ChatRequestOptions(maxTokens = 0) },
            { ChatRequestOptions(maxTokens = -1) },
            { ChatRequestOptions(maxTokens = 131073) },
            { ChatRequestOptions(maxTokens = Int.MAX_VALUE) },
            { ChatRequestOptions().copy(temperature = Double.NaN) },
            { ChatRequestOptions().copy(topP = Double.POSITIVE_INFINITY) },
        )
        for (value in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -0.001, 2.001)) {
            invalid.add { ChatRequestOptions(temperature = value) }
        }
        for (value in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -0.001, 1.001)) {
            invalid.add { ChatRequestOptions(topP = value) }
        }
        invalid.forEach { create ->
            assertFailsWith<IllegalArgumentException> { session(create(), store).send("never sent") }
        }
        assertEquals(0, requests.get())
        assertTrue(bodies.isEmpty())
        assertFalse(storageFile.exists())
    }

    @Test
    fun profileOptionsDoNotEnableFallbackForAnyOtherHttpFailure() {
        val code = AtomicInteger(400)
        serve { ex, _ -> ex.sendResponseHeaders(code.get(), -1) }
        val options = ChatRequestOptions.fromProfile(profile())
        val statuses = listOf(400, 401, 403, 404, 429, 500, 502, 503)
        statuses.forEach { status ->
            code.set(status)
            val before = requests.get()
            val chat = session(options)
            val failure = assertFailsWith<ChatException> { chat.send("no replay") }
            assertEquals(status, failure.httpCode)
            assertEquals(before + 1, requests.get())
            assertTrue(chat.messages().isEmpty())
            assertOptions(bodies.last(), options)
        }
        assertEquals(statuses.size, bodies.size)
    }

    private class RecordingTurnStore : ChatTurnStore {
        val records = mutableListOf<SessionRecord>()

        override fun begin(user: SessionRecord): String {
            records.add(user)
            return "turn-${records.size}"
        }

        override fun complete(turnId: String, assistant: SessionRecord) {
            records.add(assistant)
        }

        override fun fail(turnId: String, failure: SessionRecord) {
            error("Successful profile requests must not fail")
        }
    }
}

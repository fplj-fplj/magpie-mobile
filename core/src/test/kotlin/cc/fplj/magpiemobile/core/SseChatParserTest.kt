package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SseChatParserTest {

    @Test
    fun parsesDeltasAndDone() {
        val deltas = mutableListOf<String>()
        val p = SseChatParser { deltas.add(it) }
        assertFalse(p.feedLine(": keep-alive"))
        assertFalse(p.feedLine("event: ping"))
        assertFalse(p.feedLine("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}"))
        assertFalse(p.feedLine(""))
        assertFalse(p.feedLine("data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}"))
        assertTrue(p.feedLine("data: [DONE]"))
        assertEquals(listOf("你", "好"), deltas)
        assertEquals("你好", p.fullText())
    }

    @Test
    fun finalChunkWithoutDeltaContentIgnored() {
        val p = SseChatParser { }
        p.feedLine("data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}")
        // 结尾块：只有 finish_reason
        assertFalse(p.feedLine("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"))
        assertTrue(p.feedLine("data: [DONE]")) // [DONE] 终止流
        assertEquals("a", p.fullText())
    }

    @Test
    fun reasoningNullAndRoleOnlyDeltasNeverBecomeLiteralNull() {
        val deltas = mutableListOf<String>()
        val reasonings = mutableListOf<String>()
        val p = SseChatParser(onDelta = deltas::add, onReasoning = reasonings::add)
        // aapi.ai DeepSeek-V4.1 Flash 的典型顺序：先发 role/推理空正文，再发真正回答。
        assertFalse(p.feedLine(
            """data: {"choices":[{"delta":{"role":"assistant","content":null,"reasoning_content":null}}]}""",
        ))
        assertFalse(p.feedLine(
            """data: {"choices":[{"delta":{"content":null,"reasoning_content":"thinking"}}]}""",
        ))
        assertFalse(p.feedLine("""data: {"choices":[{"delta":{"reasoning_content":"still thinking"}}]}"""))
        assertFalse(p.feedLine("""data: {"choices":[{"delta":{"content":42}}]}"""))
        assertFalse(p.feedLine("""data: {"choices":[{"delta":{"content":"哈哈"}}]}"""))
        assertFalse(p.feedLine("""data: {"choices":[{"delta":{"content":"笑"}}]}"""))
        assertTrue(p.feedLine("data: [DONE]"))
        assertEquals(listOf("哈哈", "笑"), deltas)
        assertEquals(listOf("thinking", "still thinking"), reasonings)
        assertEquals("哈哈笑", p.fullText())
        assertEquals("thinkingstill thinking", p.fullReasoningText())
    }

    @Test
    fun reasoningAndContentInSameEventUseIndependentCallbacks() {
        val content = mutableListOf<String>()
        val wasReasoning = mutableListOf<String>()
        val events = mutableListOf<String>()
        val p = SseChatParser(
            onDelta = {
                events.add("content:$it")
                content.add(it)
            },
            onReasoning = {
                events.add("reasoning:$it")
                wasReasoning.add(it)
            },
        )
        p.feedLine(
            """data: {"choices":[{"delta":{"reasoning_content":"plan","content":"answer"}}]}""",
        )
        assertEquals(listOf("reasoning:plan", "content:answer"), events)
        assertEquals(listOf("plan"), wasReasoning)
        assertEquals(listOf("answer"), content)
        assertEquals("plan", p.fullReasoningText())
        assertEquals("answer", p.fullText())
    }

    @Test
    fun errorEventThrows() {
        val p = SseChatParser { }
        val e = assertFailsWith<ChatException> {
            p.feedLine("data: {\"error\":{\"message\":\"quota\"}}")
        }
        assertEquals("Stream SSE error", e.message)
        assertEquals(200, e.httpCode)
        assertNull(e.cause)
        assertFalse(e.stackTraceToString().contains("quota"))
    }

    @Test
    fun unknownEventsSkippedNotFatal() {
        val seen = mutableListOf<String>()
        val p = SseChatParser { seen.add(it) }
        assertFalse(p.feedLine("data: {\"weird_shape\":123}"))
        assertTrue(p.feedLine("data: [DONE]")) // [DONE] 终止流
        assertTrue(seen.isEmpty())
    }

    @Test
    fun partialStreamKeepsReceivedText() {
        // 模拟断流：只收到一半增量，fullText 保留已收内容（plan.md §5）
        val p = SseChatParser { }
        p.feedLine("data: {\"choices\":[{\"delta\":{\"content\":\"part1\"}}]}")
        // 流中断（没有 [DONE]）
        assertEquals("part1", p.fullText())
    }

    @Test
    fun errorJsonInAnyShapeNeverExposesArbitraryApiKey() {
        val key = "ordinary.provider.key/123+opaque-value"
        val errors = listOf(
            """{"message":"billing $key","APIKey":"$key","code":"$key","type":"$key","status":501}""",
            """["$key",{"nested":{"APIKey":"$key"}}]""",
            "\"$key\"",
        )
        for (error in errors) {
            val p = SseChatParser { }
            p.feedLine("""data: {"choices":[{"delta":{"content":"part"}}]}""")
            val e = assertFailsWith<ChatException> { p.feedLine("data: {\"error\":$error}") }
            assertEquals("Stream SSE error", e.message)
            assertEquals(200, e.httpCode)
            assertNull(e.cause)
            assertEquals("part", p.fullText())
            assertFalse(e.stackTraceToString().contains(key))
            assertFalse(e.stackTraceToString().contains("billing"))
            assertFalse(e.message!!.contains(error))
        }
    }

    @Test
    fun callbackExceptionsAreFixedFailuresWithPartialNotRawCauseOrHttp501() {
        val key = "ordinary.callback.key/123+opaque-value"
        for (failure in listOf(
            ChatException("billing $key", 501),
            IOException("billing $key"),
            IllegalStateException("billing $key"),
            StreamingChatException(StreamingChatFailure.IO_FAILURE, key, 501),
            StreamingChatException(StreamingChatFailure.RESPONSE_LIMIT, key, 501),
        )) {
            val p = SseChatParser { throw failure }
            val e = assertFailsWith<StreamingChatException> {
                p.feedLine("""data: {"choices":[{"delta":{"content":"part"}}]}""")
            }
            assertEquals(StreamingChatFailure.CALLBACK_FAILURE, e.failure)
            assertEquals("Stream callback failure", e.message)
            assertEquals("part", e.partialText)
            assertEquals("part", p.fullText())
            assertNull(e.httpCode)
            assertNull(e.cause)
            assertFalse(e.stackTraceToString().contains(key))
        }
    }

    @Test
    fun originalPositionalCallbacksAndCustomLimitsTrailingLambdaStayCompatible() {
        val events = mutableListOf<String>()
        val reasoning: (String) -> Unit = { events.add("reasoning:$it") }
        val content: (String) -> Unit = { events.add("content:$it") }
        val original = SseChatParser(reasoning, content)
        original.feedLine(deltaLine(content = "answer", reasoning = "thought"))
        val bounded = SseChatParser(StreamingChatLimits(maxContentChars = 4)) {
            events.add("bounded:$it")
        }
        bounded.feedLine(deltaLine(content = "okay"))

        assertEquals(listOf("reasoning:thought", "content:answer", "bounded:okay"), events)
    }

    @Test
    fun contentBudgetAllowsExactLimitAndPreservesBoundedPartialOnOverflow() {
        val deltas = mutableListOf<String>()
        val p = SseChatParser(
            limits = StreamingChatLimits(maxContentChars = 4),
            onDelta = deltas::add,
        )
        p.feedLine(deltaLine(content = "ab"))
        p.feedLine(deltaLine(content = "cd"))

        val failure = assertFailsWith<StreamingChatException> { p.feedLine(deltaLine(content = "e")) }

        assertLimitFailure(failure, content = "abcd", reasoning = "")
        assertEquals("abcd", p.fullText())
        assertEquals(listOf("ab", "cd"), deltas)
    }

    @Test
    fun reasoningBudgetAllowsExactLimitAndPreservesBoundedPartialOnOverflow() {
        val reasonings = mutableListOf<String>()
        val p = SseChatParser(
            limits = StreamingChatLimits(maxReasoningChars = 4),
            onReasoning = reasonings::add,
            onDelta = {},
        )
        p.feedLine(deltaLine(reasoning = "ab"))
        p.feedLine(deltaLine(reasoning = "cd"))

        val failure = assertFailsWith<StreamingChatException> { p.feedLine(deltaLine(reasoning = "e")) }

        assertLimitFailure(failure, content = "", reasoning = "abcd")
        assertEquals("abcd", p.fullReasoningText())
        assertEquals(listOf("ab", "cd"), reasonings)
    }

    @Test
    fun combinedBudgetAcceptsExactTotalAcrossBothChannelsAndRejectsTheNextEvent() {
        val events = mutableListOf<String>()
        val p = SseChatParser(
            limits = StreamingChatLimits(maxCombinedChars = 6),
            onReasoning = { events.add("reasoning:$it") },
            onDelta = { events.add("content:$it") },
        )
        p.feedLine(deltaLine(content = "ab", reasoning = "cd"))
        p.feedLine(deltaLine(content = "e", reasoning = "f"))

        val failure = assertFailsWith<StreamingChatException> {
            p.feedLine(deltaLine(content = "g", reasoning = "h"))
        }

        assertLimitFailure(failure, content = "abe", reasoning = "cdf")
        assertEquals("abe", p.fullText())
        assertEquals("cdf", p.fullReasoningText())
        assertEquals(listOf("reasoning:cd", "content:ab", "reasoning:f", "content:e"), events)
    }

    @Test
    fun mixedOverBudgetEventNeverAppendsOrDispatchesEitherChannel() {
        for (limits in listOf(
            StreamingChatLimits(maxContentChars = 3),
            StreamingChatLimits(maxReasoningChars = 3),
            StreamingChatLimits(maxCombinedChars = 7),
        )) {
            val events = mutableListOf<String>()
            val p = SseChatParser(
                limits = limits,
                onReasoning = { events.add("reasoning:$it") },
                onDelta = { events.add("content:$it") },
            )
            p.feedLine(deltaLine(content = "a", reasoning = "r"))

            val failure = assertFailsWith<StreamingChatException> {
                p.feedLine(deltaLine(content = "bbb", reasoning = "ttt"))
            }

            assertLimitFailure(failure, content = "a", reasoning = "r")
            assertEquals("a", p.fullText())
            assertEquals("r", p.fullReasoningText())
            assertEquals(listOf("reasoning:r", "content:a"), events)
        }
    }

    @Test
    fun lineLimitRejectsOversizedCommentsWhitespaceMalformedJsonAndDoneBeforeTrimming() {
        for (line in listOf(
            ":" + "x".repeat(100),
            " ".repeat(101),
            "data: {" + "x".repeat(100),
            " ".repeat(90) + "data: [DONE]",
            "data: " + "x".repeat(95),
        )) {
            val p = SseChatParser(limits = StreamingChatLimits(maxLineBytes = 100), onDelta = {})
            p.feedLine(deltaLine(content = "prior", reasoning = "thought"))

            val failure = assertFailsWith<StreamingChatException> { p.feedLine(line) }

            assertLimitFailure(failure, content = "prior", reasoning = "thought")
            assertEquals("prior", p.fullText())
            assertEquals("thought", p.fullReasoningText())
        }
    }

    @Test
    fun lineCharacterLimitAllowsExactBoundaryAndRejectsOneMoreCharacter() {
        val line = deltaLine(content = "😀")
        val p = SseChatParser(
            limits = StreamingChatLimits(maxLineBytes = line.length),
            onDelta = {},
        )
        assertFalse(p.feedLine(line))
        assertFalse(p.feedLine(":" + "x".repeat(line.length - 1)))

        val failure = assertFailsWith<StreamingChatException> { p.feedLine(line + " ") }

        assertLimitFailure(failure, content = "😀", reasoning = "")
    }

    @Test
    fun utf16BudgetsCountLiteralAndEscapedEmojiWithoutSplittingAcceptedDeltas() {
        val p = SseChatParser(
            limits = StreamingChatLimits(maxContentChars = 4, maxReasoningChars = 4, maxCombinedChars = 8),
            onDelta = {},
        )
        p.feedLine(deltaLine(content = "😀", reasoning = "🦉"))
        p.feedLine("""data: {"choices":[{"delta":{"content":"\ud83d\ude00","reasoning_content":"\ud83e\udd89"}}]}""")
        assertEquals(4, p.fullText().length)
        assertEquals(4, p.fullReasoningText().length)

        val failure = assertFailsWith<StreamingChatException> { p.feedLine(deltaLine(content = "x")) }

        assertLimitFailure(failure, content = "😀😀", reasoning = "🦉🦉")
    }

    @Test
    fun callbackCancellationIsPropagatedUnchangedFromEitherChannel() {
        for (reasoningCancels in listOf(false, true)) {
            val cancellation = CancellationException("fixture cancellation")
            var cancel = false
            val p = SseChatParser(
                onReasoning = { if (cancel && reasoningCancels) throw cancellation },
                onDelta = { if (cancel && !reasoningCancels) throw cancellation },
            )
            p.feedLine(deltaLine(content = "prior", reasoning = "thought"))
            cancel = true

            val actual = assertFailsWith<CancellationException> {
                p.feedLine(if (reasoningCancels) deltaLine(reasoning = "next") else deltaLine(content = "next"))
            }

            assertSame(cancellation, actual)
            assertNull(actual.cause)
        }
    }

    private fun deltaLine(content: String? = null, reasoning: String? = null): String =
        "data: " + buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("delta", buildJsonObject {
                        if (content != null) put("content", content)
                        if (reasoning != null) put("reasoning_content", reasoning)
                    })
                })
            })
        }

    private fun assertLimitFailure(error: StreamingChatException, content: String, reasoning: String) {
        assertEquals(StreamingChatFailure.RESPONSE_LIMIT, error.failure)
        assertEquals(StreamingChatFailure.RESPONSE_LIMIT.safeMessage, error.message)
        assertEquals(content, error.partialText)
        assertEquals(reasoning, error.partialReasoning)
        assertNull(error.httpCode)
        assertNull(error.cause)
    }
}

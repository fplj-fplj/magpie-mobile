package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Request

/**
 * 流式聊天的 HTTP 层；SSE 事件语义交给 SseChatParser。
 *
 * 只有收到 [DONE] 才成功；EOF/IO/SSE/回调错误保留增量并安全上抛，不自动重发。
 * 仅真正的非 2xx HTTP 响应抛 ChatException；回调不得伪造不支持流式的状态。
 */
class StreamingChatClient(
    private val config: GatewayConfig,
    internal val limits: StreamingChatLimits = StreamingChatLimits(),
    private val requestControl: ChatRequestControl? = null,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = ChatHttpTransport.client(config)

    internal val maxDuration get() = limits.maxDuration

    internal fun withRequestControl(control: ChatRequestControl) = StreamingChatClient(config, limits, control)

    /** 流式一轮：onDelta 每增量回调一次；成功时返回完整拼接文本。保持原始签名以兼容尾随 lambda。 */
    fun stream(
        model: String,
        messages: List<ChatClient.Message>,
        onDelta: (String) -> Unit = {},
    ): String = streamWithOptions(model, messages, null, null, null, onDelta)

    /**
     * 同时收集正文和 reasoning_content。
     * onReasoning 与 onDelta 各自接收增量；返回值仍是完整正文，保持既有调用方兼容。
     */
    fun streamWithReasoning(
        model: String,
        messages: List<ChatClient.Message>,
        onReasoning: (String) -> Unit = {},
        onDelta: (String) -> Unit = {},
    ): String = streamInternal(model, messages, null, null, null, onDelta, onReasoning)

    /** 流式一轮（带采样与长度控制参数）。 */
    fun streamWithOptions(
        model: String,
        messages: List<ChatClient.Message>,
        maxTokens: Int? = null,
        temperature: Double? = null,
        topP: Double? = null,
        onDelta: (String) -> Unit = {},
    ): String = streamInternal(model, messages, maxTokens, temperature, topP, onDelta, {})

    private fun streamInternal(
        model: String,
        messages: List<ChatClient.Message>,
        maxTokens: Int?,
        temperature: Double?,
        topP: Double?,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit,
    ): String {
        val control = requestControl ?: ChatRequestControl()
        val started = System.nanoTime()
        val durationNanos = limits.maxDuration.toNanos()
        fun checkActive() {
            if (System.nanoTime() - started >= durationNanos) control.deadlineExceeded()
            control.ensureActive()
        }
        checkActive()
        val body = buildJsonObject {
            put("model", model)
            put("messages", json.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(ChatClient.Message.serializer()), messages))
            put("stream", true)
            if (maxTokens != null) put("max_tokens", maxTokens)
            if (temperature != null) put("temperature", temperature)
            if (topP != null) put("top_p", topP)
        }.toString()
        val parser = SseChatParser(limits = limits, onReasoning = onReasoning, onDelta = onDelta)
        var httpCode: Int? = null
        var registration: Closeable? = null
        var watchdog: ScheduledFuture<*>? = null
        var activeCall: Call? = null
        try {
            checkActive()
            // The watchdog covers response headers and silent blocking reads too. The monotonic
            // checks also cover busy streams and avoid relying on scheduler timing for success.
            watchdog = deadlines.schedule(Runnable { control.deadlineExceeded() },
                (durationNanos - (System.nanoTime() - started)).coerceAtLeast(0L), TimeUnit.NANOSECONDS)
            val request = Request.Builder()
                .url(config.chatCompletionsPath())
                .header("Authorization", "Bearer " + config.gatewayKey)
                .header("Accept", "text/event-stream")
                .post(ChatHttpTransport.jsonBody(body))
                .build()
            val call = client.newCall(request)
            activeCall = call
            registration = control.register(call::cancel)
            checkActive()
            call.execute().use { response ->
                try {
                    val code = response.code
                    httpCode = code
                    checkActive()
                    if (code !in 200..299) {
                        // Status is sufficient; do not read error bodies or drain them on close.
                        call.cancel()
                        throw ChatException("stream -> HTTP " + code, code)
                    }
                    val mediaType = response.header("Content-Type")?.substringBefore(';')?.trim()
                    if (!mediaType.equals("text/event-stream", ignoreCase = true)) {
                        call.cancel()
                        throw StreamingChatException(StreamingChatFailure.UNEXPECTED_CONTENT_TYPE, "")
                    }
                    try {
                        val input = response.body?.byteStream()
                            ?: throw StreamingChatException(StreamingChatFailure.EOF_BEFORE_DONE, "")
                        // Call cancellation closes the socket before response/reader close can try
                        // draining unread bytes after DONE, a failed callback or a receive limit.
                        val reader = BoundedSseReader(input, limits, ::checkActive)
                        try {
                            while (true) {
                                checkActive()
                                val line = reader.readLine()
                                    ?: throw StreamingChatException(StreamingChatFailure.EOF_BEFORE_DONE, "")
                                checkActive()
                                if (parser.feedLine(line)) break
                            }
                        } finally {
                            call.cancel()
                            try { reader.close() } catch (_: IOException) { /* The terminal socket is already cancelled. */ }
                        }
                    } catch (_: ChatException) {
                        // An SSE error is not a real HTTP 405/501 and must never trigger replay.
                        throw StreamingChatException(StreamingChatFailure.SSE_ERROR, "")
                    }
                    checkActive()
                    return parser.fullText()
                } finally {
                    call.cancel()
                }
            }
        } catch (_: IOException) {
            // A disconnect caused by cancel/deadline is authoritative over its socket exception.
            try { checkActive() } catch (e: StreamingChatException) {
                throw StreamingChatException(e.failure, parser.fullText(), httpCode, parser.fullReasoningText())
            }
            // Throwable cause/类型名也可能夹带自由文本，安全边界只保留固定分类。
            throw StreamingChatException(
                StreamingChatFailure.IO_FAILURE,
                parser.fullText(),
                httpCode,
                parser.fullReasoningText(),
            )
        } catch (e: StreamingChatException) {
            // Reader/parser/control failures all carry only bounded accepted deltas and the
            // actual HTTP status; a callback cannot invent 405/501 to trigger fallback.
            throw StreamingChatException(e.failure, parser.fullText(), httpCode, parser.fullReasoningText())
        } finally {
            watchdog?.cancel(false)
            activeCall?.cancel()
            registration?.close()
        }
    }

    companion object {
        private val deadlines = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "magpie-stream-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}

/** 固定异常分类；不得将上游 message、JSON、provider key 或原始 cause 加入诊断。 */
enum class StreamingChatFailure(val safeMessage: String) {
    SSE_ERROR("Stream SSE error"),
    CALLBACK_FAILURE("Stream callback failure"),
    IO_FAILURE("Stream I/O failure"),
    EOF_BEFORE_DONE("Stream EOF before [DONE]"),
    UNEXPECTED_CONTENT_TYPE("Stream expected text/event-stream"),
    CHAT_FAILURE("Chat failed"),
    RESPONSE_LIMIT("Stream response exceeds receive limits"),
    DEADLINE_EXCEEDED("Stream deadline exceeded"),
}

/** 失败回答只留在内存供 UI 展示；异常 message/日志不包含 partialText 或原始 cause。 */
class StreamingChatException(
    val failure: StreamingChatFailure,
    val partialText: String,
    val httpCode: Int? = null,
    val partialReasoning: String = "",
) : RuntimeException(failure.safeMessage)

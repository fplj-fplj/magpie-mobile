package cc.fplj.magpiemobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.time.Duration
import okhttp3.Request

/**
 * 应用内聊天测试客户端（plan.md §3「聊天测试（新增）」的传输层）。
 *
 * 契约：OpenAI Chat Completions（/v1/chat/completions，plan.md §4.2）。
 * 模型列表必须从 GET /v1/models 动态读取（plan.md §4.2：从列表读，不内置）。
 * 本客户端只做非流式请求；SSE 流式由上层用同一端点 + "stream":true 处理。
 *
 * 安全：gatewayKey 来自 GatewayConfig（构造期已强制非空）；
 * 本类不记录请求体/响应体（可能含用户对话内容，plan.md §4.3）。
 */
class ChatClient(
    private val config: GatewayConfig,
    private val requestControl: ChatRequestControl? = null,
    private val maxDuration: Duration = StreamingChatLimits().maxDuration,
) {

    private val json = Json { ignoreUnknownKeys = true }

    internal fun withRequestControl(control: ChatRequestControl) = ChatClient(config, control, maxDuration)

    /** 非流式一轮对话。 */
    fun complete(
        model: String,
        messages: List<Message>,
        maxTokens: Int? = null,
        temperature: Double? = null,
        topP: Double? = null,
    ): String {
        val control = requestControl ?: ChatRequestControl()
        control.ensureActive()
        val body = buildJsonObject {
            put("model", model)
            put("messages", json.encodeToJsonElement(ListSerializer(Message.serializer()), messages))
            if (maxTokens != null) put("max_tokens", maxTokens)
            if (temperature != null) put("temperature", temperature)
            if (topP != null) put("top_p", topP)
        }.toString()

        val request = Request.Builder()
            .url(config.chatCompletionsPath())
            .header("Authorization", "Bearer " + config.gatewayKey)
            .post(ChatHttpTransport.jsonBody(body))
            .build()
        val call = ChatHttpTransport.client(config).newCall(request)
        val deadline = control.startDeadline(maxDuration)
        var registration: Closeable? = null
        try {
            registration = control.register(call::cancel)
            control.ensureActive()
            return call.execute().use { response ->
                control.ensureActive()
                // Cancel before close: do not let close drain a slow/error response for reuse.
                if (!response.isSuccessful) {
                    call.cancel()
                    throw ChatException("chat -> HTTP " + response.code, response.code)
                }
                val responseBody = response.body ?: throw IOException("Chat response body missing")
                if (responseBody.contentLength() > MAX_RESPONSE_BYTES) {
                    call.cancel()
                    throw responseLimitExceeded()
                }
                val text = responseBody.byteStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        control.ensureActive()
                        val count = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES + 1 - output.size()))
                        control.ensureActive()
                        if (count == -1) break
                        if (output.size() + count > MAX_RESPONSE_BYTES) {
                            call.cancel()
                            throw responseLimitExceeded()
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                // The response stays raw JSON; callers validate the completion shape.
                control.ensureActive()
                text
            }
        } catch (error: IOException) {
            control.ensureActive()
            throw error
        } finally {
            registration?.close()
            deadline.close()
            call.cancel()
        }
    }

    @Serializable
    data class Message(val role: String, val content: String)

    private fun responseLimitExceeded() = IOException("Chat response exceeds the 4 MiB size limit")

    companion object {
        // Ordinary chat may contain long answers and reasoning; this is larger than capability
        // probes, but caps memory for both Content-Length and chunked/unknown-length responses.
        internal const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    }
}

class ChatException(message: String, val httpCode: Int) : RuntimeException(message)

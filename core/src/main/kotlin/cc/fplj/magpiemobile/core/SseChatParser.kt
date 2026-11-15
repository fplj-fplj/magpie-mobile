package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * SSE 流式聊天解析器（plan.md §3 聊天测试「流式」的纯协议层，无 HTTP/Android 依赖）。
 *
 * 输入：按行送入 OpenAI Chat Completions SSE（data: {...} / data: [DONE] / 空行分隔）。
 * 输出：正文与 reasoning_content 分别回调并分别拼接；[DONE] 结束返回完整正文。
 * 错误事件只上抛固定安全分类，不携带上游自由文本/JSON；失败保留已收增量。
 */
class SseChatParser(
    private val onDelta: (String) -> Unit,
    private val onReasoning: (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val sb = StringBuilder()
    private val reasoningSb = StringBuilder()

    /** 喂入一行（不带换行符）；返回 true 表示流结束（收到 [DONE]）。 */
    fun feedLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (!trimmed.startsWith("data:")) return false // 忽略 event:/comment 行
        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") return true
        val event = extractEvent(payload) ?: return false
        event.reasoning?.let { reasoning ->
            reasoningSb.append(reasoning)
            dispatchCallback { onReasoning(reasoning) }
        }
        event.content?.let { delta ->
            sb.append(delta)
            dispatchCallback { onDelta(delta) }
        }
        return false
    }

    /** 流结束后的完整正文。 */
    fun fullText(): String = sb.toString()

    /** 流结束后的完整思考内容；与正文严格分离。 */
    fun fullReasoningText(): String = reasoningSb.toString()

    private data class StreamEvent(val content: String?, val reasoning: String?)

    private fun dispatchCallback(callback: () -> Unit) {
        try {
            callback()
        } catch (_: Exception) {
            // 回调的异常/HTTP code 不属于服务端响应，不得伪造 405/501 触发重放。
            // 不保留原始 cause，避免 UI 或异常日志泄漏任意形状凭据。
            throw StreamingChatException(
                StreamingChatFailure.CALLBACK_FAILURE,
                fullText(),
                partialReasoning = fullReasoningText(),
            )
        }
    }

    private fun extractEvent(dataPayload: String): StreamEvent? = try {
        val root = json.parseToJsonElement(dataPayload).jsonObject
        if (root["error"] != null) {
            // 不提取 raw message/code/type：任意字符串字段都可能是渠道 API key。
            throw ChatException(StreamingChatFailure.SSE_ERROR.safeMessage, 200)
        }
        val choices = root["choices"] as? kotlinx.serialization.json.JsonArray
        val choice0 = choices?.firstOrNull()?.jsonObject
        val delta = choice0?.get("delta")?.jsonObject
        // JsonNull 也是 JsonPrimitive；只接受真正的 JSON 字符串，避免把 null/数字
        // 误拼进正文或思考内容。两类增量分别保存，顺序为 reasoning → content。
        val content = delta?.get("content").stringOrNull()
        val reasoning = delta?.get("reasoning_content").stringOrNull()
        if (content == null && reasoning == null) null else StreamEvent(content, reasoning)
    } catch (e: ChatException) {
        throw e
    } catch (e: Exception) {
        null // 无法解析的心跳/未知事件：跳过不炸
    }

    private fun JsonElement?.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}

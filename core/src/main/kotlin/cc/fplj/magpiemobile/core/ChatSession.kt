package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 应用内聊天会话（plan.md §3「聊天测试（新增）」的行为层）。
 *
 * 组合 ChatClient（网关传输）与 SessionStore（本地记录）：
 * 每轮对话把 user/assistant 消息写入会话记录；上游错误原样抛出并
 * 把失败轮次记入 store（不静默吞错，plan.md §5：保留部分回答并显示失败）。
 * 非流式起步；SSE 在 ChatClient 加流式入口后无缝替换。
 */
class ChatSession(
    private val chat: ChatClient,
    private val store: SessionStore,
    private val defaultModel: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val history = mutableListOf<ChatClient.Message>()

    /** 历史快照（只读副本）。 */
    fun messages(): List<ChatClient.Message> = history.toList()

    /** 发送一轮：记录 user → 请求 → 记录 assistant；失败记录 error 标记并抛出。 */
    fun send(text: String, model: String = defaultModel): String {
        store.append(SessionRecord(now(), "user", engineId(model), text))
        history.add(ChatClient.Message("user", text))
        try {
            val raw = chat.complete(model, history.toList())
            val completion = extractCompletion(raw)
            history.add(ChatClient.Message("assistant", completion.content))
            store.append(SessionRecord(now(), "assistant", engineId(model), completion.content,
                reasoning = completion.reasoning))
            return completion.content
        } catch (e: ChatException) {
            store.append(SessionRecord(now(), "error", engineId(model), "HTTP " + e.httpCode))
            throw e
        }
    }

    /** 清空上下文（新会话）；本地记录保留（会话历史不可篡改，plan.md §3 Sessions 行）。 */
    fun reset() {
        history.clear()
    }

    private fun engineId(model: String): String = "chat:" + model

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        data class Completion(val content: String, val reasoning: String)

        /** OpenAI Chat Completions 标准形状 choices[0].message.content；形状不符抛 ChatException 由上层展示。 */
        fun extractCompletion(raw: String): Completion = try {
            val root = json.parseToJsonElement(raw).jsonObject
            val choice0 = (root["choices"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonObject
                ?: throw IllegalArgumentException("choices missing")
            // JSON null/数字不是正文；仅有有效思考文本时允许空正文，两个通道不混用。
            val message = choice0["message"]?.jsonObject
                ?: throw IllegalArgumentException("message missing")
            fun stringValue(field: String): String? =
                (message[field] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content
            val reasoning = stringValue("reasoning_content").orEmpty()
                .ifBlank { stringValue("reasoning").orEmpty() }
            val content = stringValue("content")
                ?: if (reasoning.any { !it.isWhitespace() && !it.isISOControl() }) ""
                    else throw IllegalArgumentException("content missing")
            Completion(content = content, reasoning = reasoning)
        } catch (e: ChatException) {
            throw e
        } catch (_: Exception) {
            throw ChatException("Reply shape unexpected", 200)
        }

        fun extractReply(raw: String): String = extractCompletion(raw).content

        fun extractReasoning(raw: String): String = extractCompletion(raw).reasoning
    }
}

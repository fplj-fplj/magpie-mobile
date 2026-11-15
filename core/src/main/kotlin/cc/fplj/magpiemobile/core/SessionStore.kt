package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.put
import java.io.File

/**
 * 会话/消息本地记录（plan.md §4.4：Room 保存新增聊天与本机操作状态的纯 JVM 数据形状）。
 *
 * 格式：JSONL（一行一条，崩溃安全——追加写 + 损坏行跳过不炸）。
 * 字段：ts（epoch millis）、role、engine、text、reasoning。不含凭据（plan.md §4.3）。
 * reasoning 仅随成功 assistant 记录保存，旧 JSONL 缺字段时默认为空。
 * Room 迁移时此 data class 作为实体映射源，避免双份定义。
 */
data class SessionRecord(
    val ts: Long,
    val role: String,
    val engine: String,
    val text: String,
    val reasoning: String = "",
) {
    fun toJsonLine(): String {
        return kotlinx.serialization.json.buildJsonObject {
            put("ts", ts)
            put("role", role)
            put("engine", engine)
            put("text", text)
            put("reasoning", reasoning)
        }.toString()
    }

    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun fromJsonLine(line: String): SessionRecord? {
            return try {
                val o = json.parseToJsonElement(line) as? kotlinx.serialization.json.JsonObject ?: return null
                val ts = (o["ts"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                    ?: return null
                val role = o.stringValue("role") ?: return null
                val engine = o.stringValue("engine") ?: ""
                val text = o.stringValue("text") ?: ""
                val reasoning = o.stringValue("reasoning") ?: ""
                SessionRecord(ts = ts, role = role, engine = engine, text = text, reasoning = reasoning)
            } catch (_: Exception) {
                null // 损坏行跳过
            }
        }

        private fun kotlinx.serialization.json.JsonObject.stringValue(key: String): String? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
    }
}

/** JSONL 追加式存储：线程安全、追加写（不重写全文件），损坏行读取时跳过。 */
class SessionStore(private val file: File) {

    @Synchronized
    fun append(record: SessionRecord) {
        file.parentFile?.mkdirs()
        file.appendText(record.toJsonLine() + "\n")
    }

    @Synchronized
    fun readAll(): List<SessionRecord> =
        if (!file.exists()) emptyList()
        else file.readLines().mapNotNull { SessionRecord.fromJsonLine(it) }

    @Synchronized
    fun last(n: Int): List<SessionRecord> = readAll().takeLast(n)
}

package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import okhttp3.Request

/**
 * 模型目录客户端：GET /v1/models（plan.md §4.2：必须动态读取，不内置列表）。
 *
 * 契约（上游 integrate 文档钉住）：data[] 每项含 id、display_name、owned_by；
 * reasoning_levels / context_window / modalities 已知时存在、未知时缺省——
 * 全部按"存在才解析"处理，缺失记为 null 而不是猜测默认值（plan.md：价格未知显示未知）。
 */
class ModelsClient(
    private val config: GatewayConfig,
    private val requestControl: ChatRequestControl? = null,
    private val maxResponseBytes: Int = 4 * 1024 * 1024,
    private val maxDuration: Duration = Duration.ofSeconds(60),
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val reader = GatewayHttpReader(config)

    init {
        require(maxResponseBytes > 0) { "Model catalog response limit must be positive" }
        require(!maxDuration.isNegative && !maxDuration.isZero && maxDuration <= Duration.ofDays(1)) {
            "Model catalog duration must be positive and at most one day"
        }
    }

    fun list(): List<ModelEntry> {
        val control = requestControl ?: ChatRequestControl()
        control.ensureActive()
        val deadline = control.startDeadline(maxDuration)
        try {
            val request = Request.Builder().url(config.modelsPath())
                .header("Authorization", "Bearer " + config.gatewayKey).get().build()
            val response = reader.read(request, maxResponseBytes, control)
            control.ensureActive()
            if (response.code !in 200..299) throw ModelsException("models -> HTTP " + response.code, response.code)
            val result = parse(response.text, response.code)
            control.ensureActive()
            return result
        } finally {
            deadline.close()
        }
    }

    private fun parse(text: String, code: Int): List<ModelEntry> = try {
        val root = json.parseToJsonElement(text) as? JsonObject ?: error("Invalid catalog")
        val data = root["data"] as? JsonArray ?: throw ModelsException("models: missing data[]", code)
        data.map { el ->
            val o = el as? JsonObject ?: error("Invalid model")
            val context = o["context_window"].primitiveOrNull()
            ModelEntry(
                id = o["id"].stringOrNull().orEmpty(),
                displayName = o["display_name"].stringOrNull(),
                ownedBy = o["owned_by"].stringOrNull(),
                contextWindow = context?.content?.toLongOrNull(),
            )
        }.filter { it.id.isNotBlank() }
    } catch (error: ModelsException) {
        throw error
    } catch (_: Exception) {
        throw ModelsException("models: invalid model catalog", code)
    }

    private fun JsonElement?.primitiveOrNull(): JsonPrimitive? = when (this) {
        null, JsonNull -> null
        is JsonPrimitive -> this
        else -> error("Invalid field")
    }

    private fun JsonElement?.stringOrNull(): String? {
        val value = primitiveOrNull() ?: return null
        check(value.isString) { "Invalid field" }
        return value.content
    }
}

data class ModelEntry(
    val id: String,
    val displayName: String?,
    val ownedBy: String?,
    val contextWindow: Long?,
)

class ModelsException(message: String, val httpCode: Int) : RuntimeException(message)

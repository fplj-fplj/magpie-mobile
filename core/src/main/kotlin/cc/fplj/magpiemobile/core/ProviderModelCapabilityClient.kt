package cc.fplj.magpiemobile.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Runs RikkaHub-style model capability checks through the official Magpie gateway.
 *
 * A provider-scoped check must never rely on a bare model id: Magpie publishes chat models as
 * `<providerId>/<vendorModelId>` and reports `owned_by` as that provider id. This client reads a
 * fresh `/v1/models` catalog for every run and refuses to send any check unless exactly one live
 * entry has both exact identities. Provider keys are never accepted by this class; the gateway's
 * private app key applies the saved provider configuration on-device.
 */
class ProviderModelCapabilityClient(private val config: GatewayConfig) {

    private val json = Json { ignoreUnknownKeys = false }

    /**
     * Run all three checks in a deterministic order. The callback receives a RUNNING row before
     * each request and its terminal row afterwards. It is invoked synchronously on the caller's
     * worker thread; Compose callers should marshal UI updates onto their main dispatcher.
     * [ensureActive] is checked before every request and immediately after the previous request
     * returns. A cancellation exception is propagated and never converted into a check failure.
     */
    fun run(
        providerId: String,
        savedModelId: String,
        onProgress: (ProviderCapabilityResult) -> Unit = {},
        ensureActive: () -> Unit = {},
    ): ProviderCapabilityReport {
        require(validProviderExistingId(providerId)) { "提供商标识格式无效" }
        val modelId = providerModelIds(listOf(savedModelId)).single()
        ensureActive()

        val route = try {
            resolveRoute(providerId, modelId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: ModelsException) {
            return unavailableReport(
                "无法读取实时模型目录，三项检查均未发送。",
                onProgress,
            )
        } catch (_: Exception) {
            return unavailableReport(
                "实时模型目录暂时不可用，三项检查均未发送。",
                onProgress,
            )
        }
        if (route is RouteLookup.Unavailable) {
            return unavailableReport(route.reason, onProgress)
        }
        val available = route as RouteLookup.Available

        val results = ProviderCapabilityCheck.entries.map { check ->
            ensureActive()
            onProgress(ProviderCapabilityResult(check, ProviderCapabilityStatus.RUNNING))
            val result = runOne(check, available.route)
            ensureActive()
            onProgress(result)
            result
        }
        return ProviderCapabilityReport(available.route, results)
    }

    private fun runOne(
        check: ProviderCapabilityCheck,
        route: ProviderCapabilityRoute,
    ): ProviderCapabilityResult {
        val started = System.nanoTime()
        return try {
            val result = when (check) {
                ProviderCapabilityCheck.NON_STREAMING -> ordinaryChat(route)
                ProviderCapabilityCheck.STREAMING -> streamingChat(route)
                ProviderCapabilityCheck.TOOL_CALL -> toolCall(route)
            }
            result.copy(latencyMs = elapsedMs(started))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failedResult(check, error, elapsedMs(started))
        }
    }

    private fun ordinaryChat(route: ProviderCapabilityRoute): ProviderCapabilityResult {
        val raw = ChatClient(config).complete(
            model = route.gatewayModelId,
            messages = TEST_MESSAGES,
            maxTokens = 32,
            temperature = 0.0,
        )
        val message = parseChatMessage(raw)
        return ProviderCapabilityResult(
            check = ProviderCapabilityCheck.NON_STREAMING,
            status = ProviderCapabilityStatus.PASSED,
            safeMessage = "非流式请求已通过",
            preview = safeText(message.content, MAX_PREVIEW_CHARS),
            reasoning = safeText(message.reasoning, MAX_REASONING_CHARS),
        )
    }

    private fun streamingChat(route: ProviderCapabilityRoute): ProviderCapabilityResult {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        var streamedCharacters = 0
        var exceeded = false
        try {
            StreamingChatClient(config).streamWithReasoning(
                model = route.gatewayModelId,
                messages = TEST_MESSAGES,
                onReasoning = { delta ->
                    streamedCharacters += delta.length
                    if (streamedCharacters > MAX_STREAM_CHARS) {
                        exceeded = true
                        throw StreamingLimitReached()
                    }
                    if (reasoning.length < MAX_REASONING_CHARS) reasoning.append(delta)
                },
                onDelta = { delta ->
                    streamedCharacters += delta.length
                    if (streamedCharacters > MAX_STREAM_CHARS) {
                        exceeded = true
                        throw StreamingLimitReached()
                    }
                    if (content.length < MAX_PREVIEW_CHARS) content.append(delta)
                },
            )
        } catch (error: StreamingChatException) {
            if (exceeded || error.failure == StreamingChatFailure.CALLBACK_FAILURE) {
                throw CapabilityFailureException(CapabilityFailure.RESPONSE_LIMIT)
            }
            throw error
        }
        return ProviderCapabilityResult(
            check = ProviderCapabilityCheck.STREAMING,
            status = ProviderCapabilityStatus.PASSED,
            safeMessage = "流式响应已完整收到 [DONE]",
            preview = safeText(content.toString(), MAX_PREVIEW_CHARS),
            reasoning = safeText(reasoning.toString(), MAX_REASONING_CHARS),
        )
    }

    private fun toolCall(route: ProviderCapabilityRoute): ProviderCapabilityResult {
        val raw = postBoundedToolRequest(route.gatewayModelId)
        val message = parseChatMessage(raw)
        return when (val lookup = firstToolCall(message.objectValue)) {
            is ToolCallLookup.Valid -> ProviderCapabilityResult(
                check = ProviderCapabilityCheck.TOOL_CALL,
                status = ProviderCapabilityStatus.PASSED,
                safeMessage = "模型调用了 get_current_time，参数已校验且未执行",
                preview = safeText("${lookup.name} ${lookup.arguments}", MAX_PREVIEW_CHARS),
                reasoning = safeText(message.reasoning, MAX_REASONING_CHARS),
            )
            ToolCallLookup.NotCalled -> ProviderCapabilityResult(
                check = ProviderCapabilityCheck.TOOL_CALL,
                status = ProviderCapabilityStatus.FAILED,
                safeMessage = "请求成功，但模型没有返回 get_current_time 工具调用",
                preview = safeText(message.content, MAX_PREVIEW_CHARS),
                reasoning = safeText(message.reasoning, MAX_REASONING_CHARS),
            )
            ToolCallLookup.Invalid -> ProviderCapabilityResult(
                check = ProviderCapabilityCheck.TOOL_CALL,
                status = ProviderCapabilityStatus.FAILED,
                safeMessage = "get_current_time 工具调用格式或参数无效",
                preview = safeText(message.content, MAX_PREVIEW_CHARS),
                reasoning = safeText(message.reasoning, MAX_REASONING_CHARS),
            )
        }
    }

    private fun resolveRoute(providerId: String, savedModelId: String): RouteLookup {
        val prefix = "$providerId/"
        val entries = ModelsClient(config).list()
        val providerEntries = entries.filter { it.ownedBy == providerId }
        if (providerEntries.isEmpty()) {
            return RouteLookup.Unavailable(
                "实时目录中没有归属于此提供商的模型；不会回退到全局同名模型。",
            )
        }
        val matches = providerEntries.filter { entry ->
            entry.id.startsWith(prefix) && entry.id.substring(prefix.length) == savedModelId
        }
        return when (matches.size) {
            1 -> {
                val entry = matches.single()
                RouteLookup.Available(
                    ProviderCapabilityRoute(
                        providerId = providerId,
                        savedModelId = savedModelId,
                        gatewayModelId = entry.id,
                        displayName = safeText(
                            entry.displayName?.takeIf { it.isNotBlank() } ?: savedModelId,
                            160,
                        ),
                    ),
                )
            }
            0 -> RouteLookup.Unavailable(
                "所选模型未以该提供商的命名空间出现在实时目录中。",
            )
            else -> RouteLookup.Unavailable(
                "实时目录返回了多个相同模型路由，无法保证命中当前提供商。",
            )
        }
    }

    private fun unavailableReport(
        reason: String,
        onProgress: (ProviderCapabilityResult) -> Unit,
    ): ProviderCapabilityReport {
        val results = ProviderCapabilityCheck.entries.map { check ->
            ProviderCapabilityResult(
                check = check,
                status = ProviderCapabilityStatus.UNAVAILABLE,
                safeMessage = reason,
            ).also(onProgress)
        }
        return ProviderCapabilityReport(route = null, results = results)
    }

    private fun postBoundedToolRequest(gatewayModelId: String): String {
        val body = buildJsonObject {
            put("model", gatewayModelId)
            put("messages", TEST_MESSAGES_JSON)
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", TEST_TOOL_NAME)
                        put("description", "Get the current date and time.")
                        put("parameters", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {})
                        })
                    })
                })
            })
            put("tool_choice", "auto")
            put("stream", false)
            put("max_tokens", 64)
            put("temperature", 0.0)
        }.toString()

        val conn = URL(config.chatCompletionsPath())
            .openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = config.connectTimeout.toMillis().toInt()
        conn.readTimeout = config.readTimeout.toMillis().toInt()
        conn.doOutput = true
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Authorization", "Bearer " + config.gatewayKey)
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw CapabilityFailureException(CapabilityFailure.HTTP, code)
            }
            if (conn.contentLengthLong > MAX_RESPONSE_BYTES) {
                throw CapabilityFailureException(CapabilityFailure.RESPONSE_LIMIT)
            }
            return readBoundedUtf8(conn)
        } catch (error: CapabilityFailureException) {
            throw error
        } catch (_: SocketTimeoutException) {
            throw CapabilityFailureException(CapabilityFailure.TIMEOUT)
        } catch (_: IOException) {
            throw CapabilityFailureException(CapabilityFailure.IO)
        } finally {
            conn.disconnect()
        }
    }

    private fun readBoundedUtf8(conn: HttpURLConnection): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        conn.inputStream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) {
                    throw CapabilityFailureException(CapabilityFailure.RESPONSE_LIMIT)
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun parseChatMessage(raw: String): ParsedChatMessage {
        val root = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            throw CapabilityFailureException(CapabilityFailure.INVALID_RESPONSE)
        }
        val choices = root["choices"] as? JsonArray
            ?: throw CapabilityFailureException(CapabilityFailure.INVALID_RESPONSE)
        val choice = choices.firstOrNull() as? JsonObject
            ?: throw CapabilityFailureException(CapabilityFailure.INVALID_RESPONSE)
        val message = choice["message"] as? JsonObject
            ?: throw CapabilityFailureException(CapabilityFailure.INVALID_RESPONSE)
        return ParsedChatMessage(
            objectValue = message,
            content = stringValue(message["content"]),
            reasoning = stringValue(message["reasoning_content"])
                .ifBlank { stringValue(message["reasoning"]) },
        )
    }

    private fun firstToolCall(message: JsonObject): ToolCallLookup {
        val calls = message["tool_calls"] as? JsonArray ?: return ToolCallLookup.NotCalled
        if (calls.isEmpty()) return ToolCallLookup.NotCalled
        var sawMalformed = false
        for (element in calls) {
            val call = element as? JsonObject
            val function = call?.get("function") as? JsonObject
            if (function == null) {
                sawMalformed = true
                continue
            }
            val name = stringValue(function["name"])
            val argumentsText = stringValue(function["arguments"])
            if (name != TEST_TOOL_NAME) {
                sawMalformed = true
                continue
            }
            if (argumentsText.isBlank()) {
                sawMalformed = true
                continue
            }
            val arguments = try {
                json.parseToJsonElement(argumentsText) as? JsonObject
            } catch (_: Exception) {
                null
            }
            if (arguments == null) {
                sawMalformed = true
                continue
            }
            return ToolCallLookup.Valid(name, arguments)
        }
        return if (sawMalformed) ToolCallLookup.Invalid else ToolCallLookup.NotCalled
    }

    private fun stringValue(value: kotlinx.serialization.json.JsonElement?): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private fun safeText(raw: String, limit: Int): String =
        raw
            .filter { it == '\n' || it == '\t' || !it.isISOControl() }
            .trim()
            .take(limit)

    private fun elapsedMs(started: Long): Long =
        ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)

    private fun failedResult(
        check: ProviderCapabilityCheck,
        error: Exception,
        latencyMs: Long,
    ): ProviderCapabilityResult {
        val message = when (error) {
            is ChatException -> httpFailureMessage(error.httpCode)
            is StreamingChatException -> when (error.failure) {
                StreamingChatFailure.IO_FAILURE -> "流式连接中断"
                StreamingChatFailure.EOF_BEFORE_DONE -> "流式响应未以 [DONE] 完整结束"
                StreamingChatFailure.UNEXPECTED_CONTENT_TYPE -> "上游没有返回 SSE 流"
                StreamingChatFailure.SSE_ERROR -> "上游返回了流式错误"
                StreamingChatFailure.CALLBACK_FAILURE -> "流式测试处理失败"
                StreamingChatFailure.CHAT_FAILURE -> "流式请求失败"
            }
            is CapabilityFailureException -> when (error.failure) {
                CapabilityFailure.HTTP -> httpFailureMessage(error.httpCode)
                CapabilityFailure.TIMEOUT -> "请求超时"
                CapabilityFailure.IO -> "网关连接失败"
                CapabilityFailure.INVALID_RESPONSE -> "上游返回了无法验证的响应"
                CapabilityFailure.RESPONSE_LIMIT -> "响应超过测试安全上限"
            }
            else -> "测试未能安全完成"
        }
        return ProviderCapabilityResult(
            check = check,
            status = ProviderCapabilityStatus.FAILED,
            safeMessage = message,
            latencyMs = latencyMs,
        )
    }

    private fun httpFailureMessage(httpCode: Int?): String = when {
        httpCode == 401 || httpCode == 403 -> "上游拒绝凭据或权限"
        httpCode == 404 -> "模型或聊天端点不可用"
        httpCode == 408 || httpCode == 504 -> "上游响应超时"
        httpCode == 429 -> "上游暂时限流"
        httpCode != null && httpCode in 400..499 -> "上游拒绝了测试请求"
        httpCode != null && httpCode in 500..599 -> "上游服务不可用"
        else -> "请求失败"
    }

    private sealed interface RouteLookup {
        data class Available(val route: ProviderCapabilityRoute) : RouteLookup
        data class Unavailable(val reason: String) : RouteLookup
    }

    private data class ParsedChatMessage(
        val objectValue: JsonObject,
        val content: String,
        val reasoning: String,
    )

    private sealed interface ToolCallLookup {
        data class Valid(val name: String, val arguments: JsonObject) : ToolCallLookup
        data object NotCalled : ToolCallLookup
        data object Invalid : ToolCallLookup
    }

    private class StreamingLimitReached : RuntimeException()

    private enum class CapabilityFailure {
        HTTP,
        TIMEOUT,
        IO,
        INVALID_RESPONSE,
        RESPONSE_LIMIT,
    }

    private class CapabilityFailureException(
        val failure: CapabilityFailure,
        val httpCode: Int? = null,
    ) : RuntimeException(failure.name)

    private companion object {
        const val TEST_TOOL_NAME = "get_current_time"
        const val MAX_RESPONSE_BYTES = 256 * 1024
        const val MAX_STREAM_CHARS = 16 * 1024
        const val MAX_PREVIEW_CHARS = 360
        const val MAX_REASONING_CHARS = 2_000
        val TEST_MESSAGES = listOf(
            ChatClient.Message("system", "You are a helpful assistant"),
            ChatClient.Message("user", "hello"),
        )
        val TEST_MESSAGES_JSON = buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", "You are a helpful assistant")
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", "Use the get_current_time tool.")
            })
        }
    }
}

enum class ProviderCapabilityCheck(val label: String) {
    NON_STREAMING("非流式响应"),
    STREAMING("流式响应"),
    TOOL_CALL("工具调用"),
}

enum class ProviderCapabilityStatus {
    PENDING,
    RUNNING,
    PASSED,
    FAILED,
    UNAVAILABLE,
}

data class ProviderCapabilityRoute(
    val providerId: String,
    val savedModelId: String,
    val gatewayModelId: String,
    val displayName: String,
)

data class ProviderCapabilityResult(
    val check: ProviderCapabilityCheck,
    val status: ProviderCapabilityStatus,
    val safeMessage: String = "",
    val latencyMs: Long? = null,
    val preview: String = "",
    val reasoning: String = "",
) {
    override fun toString(): String =
        "ProviderCapabilityResult(check=$check,status=$status,latencyMs=$latencyMs)"
}

data class ProviderCapabilityReport(
    val route: ProviderCapabilityRoute?,
    val results: List<ProviderCapabilityResult>,
)

package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.time.Duration
import java.util.concurrent.CancellationException

/** A read never became a definite response, or its answer did not match the management contract. */
class PluginReadException : ManagementException("插件状态或市场读取失败；请重新读取，未改变插件配置")

/**
 * A plugin POST either did not finish or returned an unusable answer. The core may have changed
 * state, so callers must reconcile with reads; the client never replays the request.
 */
class PluginWriteUnknownException : ManagementException("插件写入结果未知；不得重放，请重新只读核对")

/**
 * Official plugin management client. All calls use the management cookie on the GUI port and the
 * shared loopback-bound transport. Writes are serialized for this client and are never retried.
 */
class PluginManagementClient(
    private val credential: ManagerCredential,
    baseUrl: String = "http://127.0.0.1:3430",
    private val requestControl: ChatRequestControl? = null,
    private val ensureActive: () -> Unit = {},
) {
    private val endpoint = LoopbackEndpoint(baseUrl)
    private val readTransport = BoundedCoreHttp(endpoint, READ_TIMEOUT, credential.cookieHeader(), requestControl, ensureActive)
    private val writeTransport = BoundedCoreHttp(endpoint, WRITE_TIMEOUT, credential.cookieHeader(), requestControl, ensureActive)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    init {
        require(endpoint.port != 3425) { "插件管理 cookie 不得发送到网关" }
        require(endpoint.port == credential.port) { "插件管理 cookie 端口不匹配" }
    }

    fun plugins(): PluginSnapshot = readRequest("/api/plugins") { parseSnapshot(it) }

    /**
     * Blank query uses the official curated market; nonblank query uses the official npm search.
     * Installed entries are matched only by official identifiers, never by an invented manifest.
     */
    fun market(query: String = ""): PluginMarketSnapshot {
        checkCurrent()
        val cleaned = query.trim()
        require(cleaned.length <= 256 && cleaned.none { it.isISOControl() }) { "插件搜索词无效" }
        val installedSnapshot: PluginSnapshot
        val listings: List<PluginMarketListing>
        if (cleaned.isEmpty()) {
            val root = readRequest("/api/plugins/market") { json.parseToJsonElement(it).jsonObject }
            installedSnapshot = readSafe { readStateObject(root["state"] as? JsonObject) }
            listings = readSafe { parseListings(root["listings"] as? JsonArray) }
        } else {
            installedSnapshot = plugins()
            checkCurrent() // A superseded first GET must never authorize the search GET.
            val path = "/api/plugins/search?q=" + URLEncoder.encode(cleaned, Charsets.UTF_8)
            val root = readRequest(path) { json.parseToJsonElement(it).jsonObject }
            listings = readSafe { parseListings(root["hits"] as? JsonArray) }
        }
        return readSafe {
            val matched = installedSnapshot.entries.filter { entry ->
                listings.any { listing -> entry.matchesListing(listing) }
            }.map { it.spec }
            PluginMarketSnapshot(
                query = cleaned,
                source = if (cleaned.isEmpty()) PluginMarketSource.MARKET else PluginMarketSource.SEARCH,
                listings = listings,
                installed = installedSnapshot.entries,
                matchedSpecs = matched,
            )
        }
    }

    @Synchronized fun add(spec: String): PluginSnapshot {
        checkCurrent()
        return writePlugin("add", buildJsonObject { put("spec", requireSpec(spec)) })
    }
    @Synchronized fun remove(spec: String): PluginSnapshot {
        checkCurrent()
        return writePlugin("remove", buildJsonObject { put("spec", requireSpec(spec)) })
    }
    @Synchronized fun upgrade(spec: String): PluginSnapshot {
        checkCurrent()
        return writePlugin("upgrade", buildJsonObject { put("spec", requireSpec(spec)) })
    }
    @Synchronized fun updateAll(): PluginSnapshot {
        checkCurrent()
        return writePlugin("update", JsonObject(emptyMap()))
    }

    @Synchronized
    fun restoreGatewayAccess(): PluginSnapshot {
        checkCurrent()
        return writeRequest(
            "/api/androidhost/plugin-gateway-access/restore", JsonObject(emptyMap()),
        ) { raw ->
            val response = json.parseToJsonElement(raw).jsonObject
            check(response["ok"]?.jsonPrimitive?.booleanOrNull == true)
            checkCurrent() // A completed POST never permits reads through a replacement session.
            plugins()
        }
    }

    @Synchronized
    fun setOff(spec: String, off: Boolean): PluginSnapshot {
        checkCurrent()
        return writePlugin("off", buildJsonObject { put("spec", requireSpec(spec)); put("off", off) })
    }

    @Synchronized
    fun saveOptions(spec: String, options: JsonObject): PluginSnapshot {
        checkCurrent()
        requireSpec(spec)
        val request = buildJsonObject { put("spec", spec); put("options", options) }
        require(request.toString().toByteArray(Charsets.UTF_8).size <= MAX_WRITE_BODY_BYTES) { "插件 options 超过安全上限" }
        return writePlugin("options", request)
    }

    @Synchronized
    fun prefer(spec: String, provider: String): PluginSnapshot {
        checkCurrent()
        requireSpec(spec)
        require(validGatewayId(provider)) { "插件 provider 标识无效" }
        return writePlugin("prefer", buildJsonObject { put("spec", spec); put("provider", provider) })
    }

    /** Providers are listed by /api/providers; sign-in methods must use [PluginProvider.pid]. */
    fun providers(): List<PluginProvider> = readRequest("/api/providers") { raw ->
        val root = json.parseToJsonElement(raw).jsonObject
        root.objectList("plugins").map(::parseProvider)
    }

    @Synchronized
    fun nextPrompt(
        pid: String,
        method: Int,
        inputs: Map<String, String>,
        answerKey: String = "",
        answerValue: String = "",
    ): PluginPromptResult {
        checkCurrent()
        require(validGatewayId(pid)) { "插件 provider pid 无效" }
        require(method >= 0) { "插件登录方式无效" }
        if (answerKey.isNotEmpty()) require(validGatewayId(answerKey)) { "插件登录输入项无效" }
        val request = buildJsonObject {
            put("provider", pid)
            put("method", method)
            put("inputs", JsonObject(inputs.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
            if (answerKey.isNotEmpty()) {
                put("key", answerKey)
                put("value", answerValue)
            }
        }
        return writeRequest("/api/plugin-signin/prompt", request) { raw ->
            val root = json.parseToJsonElement(raw).jsonObject
            // Official success is {"prompt": q, "inputs": {...}} or a bare {"error": msg} validation
            // answer; prompt itself may be null. Anything else is not a definite answer, so the
            // write stays unknown and the caller reconciles with a read instead of repeating.
            val error = root.text("error")
            val inputs = if (error.isEmpty()) {
                val provided = root["inputs"] as? JsonObject
                    ?: throw IllegalStateException("官方登录提示缺少 inputs")
                provided.entries.associate { (key, value) ->
                    key to ((value as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw IllegalStateException("官方登录提示的 inputs 含非字符串项"))
                }
            } else emptyMap()
            PluginPromptResult(
                prompt = (root["prompt"] as? JsonObject)?.let(::parsePrompt),
                inputs = inputs,
                error = error,
            )
        }
    }

    @Synchronized
    fun signIn(pid: String, method: Int, inputs: Map<String, String>, key: String = ""): PluginSignInState {
        checkCurrent()
        require(validGatewayId(pid)) { "插件 provider pid 无效" }
        require(method >= 0) { "插件登录方式无效" }
        val request = buildJsonObject {
            put("provider", pid)
            put("method", method)
            put("inputs", JsonObject(inputs.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
            put("key", key)
        }
        return writeRequest("/api/plugin-signin", request) { parseSignIn(it) }
    }

    fun signInState(id: String): PluginSignInState {
        checkCurrent()
        requirePathSegment(id, "插件登录标识无效")
        return readRequest("/api/signin/" + encodedPath(id)) { parseSignIn(it) }
    }

    @Synchronized
    fun submitCallback(id: String, value: String) {
        checkCurrent()
        requirePathSegment(id, "插件登录标识无效")
        val request = buildJsonObject { put("url", value) }
        writeRequest("/api/signin/" + encodedPath(id) + "/callback", request) { rootExpectingSuccess(it) }
    }

    @Synchronized
    fun cancelSignIn(id: String) {
        checkCurrent()
        requirePathSegment(id, "插件登录标识无效")
        writeRequest("/api/signin/" + encodedPath(id) + "/cancel", JsonObject(emptyMap())) { rootExpectingSuccess(it) }
    }

    private fun rootExpectingSuccess(raw: String) {
        // Callback and cancel normally return HTTP 204. Some core versions
        // return an empty JSON object; neither form carries a credential.
        if (raw.isBlank()) return
        val response = json.parseToJsonElement(raw).jsonObject
        check(response.text("error").isBlank())
    }

    private fun checkCurrent() {
        requestControl?.ensureActive()
        ensureActive()
        requestControl?.ensureActive()
    }

    private inline fun <T> readSafe(block: () -> T): T = try {
        checkCurrent()
        block().also { checkCurrent() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        checkCurrent()
        throw PluginReadException()
    }

    private inline fun <T> readRequest(path: String, block: (String) -> T): T {
        checkCurrent()
        return try {
            val body = readTransport.request("GET", path)
            readSafe { block(body) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ManagementProtocolException) {
            checkCurrent()
            throw PluginReadException()
        } catch (_: ManagementExecutionUnknown) {
            checkCurrent()
            throw PluginReadException()
        }
    }

    private inline fun <T> writeRequest(path: String, payload: JsonObject, block: (String) -> T): T {
        checkCurrent()
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_WRITE_BODY_BYTES) { "插件管理请求超过安全上限" }
        return try {
            val body = writeTransport.request("POST", path, bytes)
            try {
                checkCurrent()
                block(body).also { checkCurrent() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                checkCurrent()
                throw PluginWriteUnknownException()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ManagementProtocolException) {
            checkCurrent()
            throw PluginWriteUnknownException()
        } catch (_: ManagementExecutionUnknown) {
            checkCurrent()
            throw PluginWriteUnknownException()
        }
    }

    private fun writePlugin(op: String, payload: JsonObject): PluginSnapshot =
        writeRequest("/api/plugins/$op", payload) { parseSnapshot(it) }

    private fun parseSnapshot(raw: String): PluginSnapshot {
        val root = json.parseToJsonElement(raw).jsonObject
        val entries = root.objectList("plugins")
        return PluginSnapshot(entries = entries.map(::parsePlugin), bun = root.bool("bun"), bunVersion = root.text("bunVersion"))
    }

    private fun readStateObject(value: JsonObject?): PluginSnapshot {
        if (value == null) throw PluginReadException()
        return parseSnapshot(value.toString())
    }

    private fun parsePlugin(value: JsonObject): ManagedPlugin {
        val spec = value.text("spec")
        if (spec.isEmpty()) throw PluginReadException()
        val packageId = value.text("package").ifEmpty { packageName(spec) }
        val tools = value.objectListOrEmpty("tools").map { tool ->
            PluginTool(
                name = tool.text("name"),
                description = tool.text("description"),
                schema = tool["schema"] as? JsonObject,
            )
        }
        return ManagedPlugin(
            spec = spec,
            name = value.text("ownName").ifEmpty { packageId },
            packageId = packageId,
            off = value.bool("off"),
            options = value["options"] as? JsonObject ?: JsonObject(emptyMap()),
            error = value.text("error"),
            providerNames = value.strings("providers"),
            toolNames = tools.map { it.name },
            tools = tools,
            isMiddleware = value.bool("isMiddleware"),
            middlewareOnly = value.bool("middlewareOnly"),
            isGateway = value.bool("isGateway"),
            gatewayOnly = value.bool("gatewayOnly"),
            middleware = (value["middleware"] as? JsonObject)?.let(::parseMiddleware),
            gateway = (value["gateway"] as? JsonObject)?.let(::parseGateway),
            version = value.text("version"),
            latest = value.text("latest"),
            optionsExample = value["optionsExample"] as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    private fun parseMiddleware(value: JsonObject) = PluginMiddlewareDiagnostics(
        hooks = value.strings("hooks"),
        events = value.strings("events"),
        error = value.text("error"),
        calls = value.long("calls"),
        avgMicros = value.double("avgMicros"),
        failures = value.long("failures"),
        lastError = value.text("lastError"),
        strategies = value.strings("strategies"),
        orders = value.long("orders"),
        usage = value.bool("usage"),
        rows = value.long("rows"),
        dropped = value.long("dropped"),
    )

    private fun parseGateway(value: JsonObject) = PluginGatewayDiagnostics(
        calls = value.long("calls"),
        failures = value.long("failures"),
        avgMillis = value.double("avgMillis"),
        lastOutcome = value.text("lastOutcome"),
        lastBytes = value.long("lastBytes"),
    )

    private fun parseListings(values: JsonArray?): List<PluginMarketListing> {
        if (values == null) throw PluginReadException()
        return values.mapNotNull { value -> value as? JsonObject }.map { value ->
            val packageId = value.text("package")
            if (packageId.isEmpty()) throw PluginReadException()
            PluginMarketListing(
                packageId = packageId,
                name = value.text("name").ifEmpty { packageId },
                kind = value.text("kind"),
                providers = value.strings("providers"),
                community = value.bool("community"),
                replaces = value.text("replaces"),
                icon = value.text("icon"),
                version = value.text("version"),
                description = value.text("description"),
                publisher = value.text("publisher"),
                homepage = value.text("homepage"),
                repository = value.text("repository"),
                weekly = value.long("weekly"),
                summary = value.summary(),
                raw = value,
            )
        }
    }

    private fun JsonObject.summary(): String {
        val byLanguage = this["summary"] as? JsonObject
        val chosen = byLanguage?.text("en")?.ifEmpty { byLanguage.text("zh") } ?: ""
        return chosen.ifEmpty { text("description") }
    }

    private fun parseProvider(value: JsonObject): PluginProvider {
        val pid = value.text("pid")
        if (pid.isEmpty()) throw PluginReadException()
        return PluginProvider(
            id = value.text("id"),
            pid = pid,
            name = value.text("name"),
            icon = value.text("icon"),
            spec = value.text("spec"),
            methods = value.objectListOrEmpty("methods").map { method ->
                PluginSignInMethod(
                    type = method.text("type"),
                    label = method.text("label"),
                    labels = method.stringMap("labels"),
                    placeholder = method.text("placeholder"),
                    keyless = method.bool("keyless"),
                    keyAsked = method.bool("keyAsked"),
                )
            },
            signedIn = value.bool("signedIn"),
            single = value.bool("single"),
            models = value.int("models"),
            options = value.objectListOrEmpty("options").map { option ->
                PluginProviderOption(
                    key = option.text("key"),
                    label = option.text("label"),
                    description = option.text("description"),
                    default = option.bool("default"),
                    on = option.bool("on"),
                )
            },
        )
    }

    private fun parsePrompt(value: JsonObject) = PluginPrompt(
        type = value.text("type"),
        key = value.text("key"),
        message = value.text("message"),
        messages = value.stringMap("messages"),
        placeholder = value.text("placeholder"),
        options = value.objectListOrEmpty("options").map { option ->
            PluginPromptOption(
                label = option.text("label"),
                value = option.text("value"),
                hint = option.text("hint"),
                labels = option.stringMap("labels"),
                hints = option.stringMap("hints"),
            )
        },
    )

    private fun parseSignIn(raw: String): PluginSignInState {
        val root = json.parseToJsonElement(raw).jsonObject
        // Upstream serializes state without omitempty (provider/signin.go), so a reply without
        // it is not a definite sign-in answer and must not read as "waiting" or "done".
        val state = (root["state"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalStateException("官方登录响应缺少 state")
        return PluginSignInState(
            id = root.text("id"),
            agent = root.text("agent"),
            url = root.text("url"),
            code = root.text("code"),
            state = state,
            pasteCallback = root.bool("pasteCallback"),
            pasteCode = root.bool("pasteCode"),
            pasteKey = root.bool("pasteKey"),
            keysUrl = root.text("keysURL"),
            instructions = root.text("instructions"),
            opened = root.bool("opened"),
            installing = root.text("installing"),
            user = root.text("user"),
            plan = root.text("plan"),
            using = root.bool("using"),
            again = root.bool("again"),
            error = root.text("error"),
            callbackBlocked = root.text("callbackBlocked"),
            callbackPort = root.int("callbackPort"),
            proxy = root.text("proxy"),
            openError = root.text("openError"),
            linkOnly = root.bool("linkOnly"),
        )
    }

    private fun requireSpec(spec: String): String = validateSpec(spec)

    private fun requirePathSegment(value: String, message: String) {
        require(validGatewayId(value) && value.none { it == '/' || it == '\\' }) { message }
    }

    private fun encodedPath(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private fun JsonObject.text(name: String): String =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

    private fun JsonObject.bool(name: String): Boolean =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject.int(name: String): Int =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 0

    private fun JsonObject.long(name: String): Long =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull ?: 0L

    private fun JsonObject.double(name: String): Double =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull ?: 0.0

    private fun JsonObject.strings(name: String): List<String> =
        (this[name] as? JsonArray)?.map { item ->
            (item as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalStateException("官方响应的 $name 含非字符串项")
        } ?: emptyList()

    /** Official arrays are either absent or entirely objects; one bad row fails the read so an installed plugin is never silently hidden. */
    private fun JsonObject.objectList(name: String): List<JsonObject> =
        (this[name] as? JsonArray)?.map { value ->
            value as? JsonObject ?: throw IllegalStateException("官方响应的 $name 含非对象项")
        } ?: throw IllegalStateException("官方响应缺少 $name")

    private fun JsonObject.objectListOrEmpty(name: String): List<JsonObject> {
        val array = this[name] as? JsonArray ?: return emptyList()
        return array.map { value -> value as? JsonObject ?: throw IllegalStateException("官方响应的 $name 含非对象项") }
    }

    private fun JsonObject.stringMap(name: String): Map<String, String> =
        (this[name] as? JsonObject)?.entries?.mapNotNull { (key, value) ->
            (value as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.let { key to it }
        }?.toMap() ?: emptyMap()

    private fun packageName(spec: String): String {
        val value = spec.removePrefix("file://")
        val at = value.lastIndexOf('@')
        return if (at > 0) value.substring(0, at) else value
    }

    companion object {
        private val specUrlCredentials = Regex("[a-zA-Z][a-zA-Z0-9+.-]*://[^/?#\\s]*@")

        /** Validate before retaining a spec in saved state as well as before sending it to the core. */
        fun validateSpec(spec: String): String {
            val value = spec.trim()
            require(value.isNotEmpty() && value.length <= 4096 && value.none { it.isISOControl() }) { "插件 spec 无效" }
            // URL userinfo is a credential, and must never reach plugins.json or saved state.
            require(!specUrlCredentials.containsMatchIn(value)) { "插件 spec 不得携带 URL 凭据；请用网页编辑器配置私有访问令牌" }
            return value
        }

        internal val READ_TIMEOUT: Duration = Duration.ofSeconds(90)
        internal val WRITE_TIMEOUT: Duration = Duration.ofMinutes(6)
        private const val MAX_WRITE_BODY_BYTES = 64 * 1024
    }
}

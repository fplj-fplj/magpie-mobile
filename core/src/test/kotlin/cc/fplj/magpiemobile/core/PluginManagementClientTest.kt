package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginManagementClientTest {
    private lateinit var api: PluginApiFixture

    @BeforeEach fun start() { api = PluginApiFixture() }
    @AfterEach fun stop() { api.close() }

    @Test fun timeoutContractUsesBoundedReadAndWriteBudgets() {
        assertEquals(Duration.ofSeconds(90), PluginManagementClient.READ_TIMEOUT)
        assertEquals(Duration.ofMinutes(6), PluginManagementClient.WRITE_TIMEOUT)
    }

    @Test fun pluginsParsesOneMultiTypeEntryWithoutRawBodyInModels() {
        val snapshot = api.client().plugins()
        assertEquals(1, snapshot.entries.size)
        assertTrue(snapshot.bun)
        assertEquals("1.4.2", snapshot.bunVersion)
        val plugin = snapshot.entries.single()
        assertEquals("@magpie-community/opencode-multi", plugin.spec)
        assertEquals("Multi Plugin", plugin.name)
        assertEquals("@magpie-community/opencode-multi", plugin.packageId)
        assertFalse(plugin.off)
        assertEquals(listOf("Multi"), plugin.providerNames)
        assertEquals(listOf("look"), plugin.toolNames)
        assertTrue(plugin.isMiddleware && plugin.isGateway && !plugin.middlewareOnly && !plugin.gatewayOnly)
        assertEquals(2L, plugin.middleware?.calls)
        assertEquals(3L, plugin.gateway?.calls)
        assertEquals("0.1.0", plugin.version)
        assertEquals("0.2.0", plugin.latest)
        assertEquals("true", plugin.options["enabled"]!!.jsonPrimitive.content)
        assertTrue(plugin.toString().contains("@magpie-community/opencode-multi"))
        assertFalse(plugin.toString().contains("\"options\""))
        assertFalse(plugin.toString().contains("boom"))
    }

    @Test fun marketUsesOfficialMarketAndMatchesInstalledSpecs() {
        val market = api.client().market()
        assertEquals(PluginMarketSource.MARKET, market.source)
        assertEquals("", market.query)
        assertEquals(listOf("@magpie-community/opencode-multi"), market.listings.map { it.packageId })
        assertEquals(listOf("Multi Plugin"), market.listings.map { it.name })
        assertEquals(listOf("tools"), market.listings.map { it.kind })
        assertEquals(listOf("@magpie-community/opencode-multi"), market.matchedSpecs)
        assertEquals("/api/plugins/market", api.requests.single().path)
    }

    @Test fun searchUsesOfficialSearchAndMatchesPackageAgainstInstalledEntry() {
        val search = api.client().market("multi")
        assertEquals(PluginMarketSource.SEARCH, search.source)
        assertEquals("multi", search.query)
        assertEquals(listOf("@magpie-community/opencode-multi"), search.matchedSpecs)
        val paths = api.requests.map { it.path }
        assertEquals(2, paths.size)
        assertEquals("/api/plugins", paths.first())
        assertEquals("/api/plugins/search?q=multi", paths.last())
    }

    @Test fun pluginWritesUseOfficialOpsAndParseReturnedState() {
        val client = api.client()
        assertEquals(1, client.add("@magpie-community/opencode-multi").entries.size)
        assertEquals(1, client.setOff("@magpie-community/opencode-multi", false).entries.size)
        assertEquals(1, client.saveOptions("@magpie-community/opencode-multi", api.options).entries.size)
        assertEquals(1, client.prefer("@magpie-community/opencode-multi", "fakeco").entries.size)
        assertEquals(1, client.updateAll().entries.size)
        assertEquals(1, client.upgrade("@magpie-community/opencode-multi").entries.size)
        assertEquals(1, client.remove("@magpie-community/opencode-multi").entries.size)

        val ops = api.requests.filter { it.path.startsWith("/api/plugins/") }.map { it.path.removePrefix("/api/plugins/") }
        assertEquals(listOf("add", "off", "options", "prefer", "update", "upgrade", "remove"), ops)
        val addBody = Json.parseToJsonElement(api.requests.single { it.path == "/api/plugins/add" }.body).jsonObject
        assertEquals("@magpie-community/opencode-multi", addBody["spec"]!!.jsonPrimitive.content)
        val offBody = Json.parseToJsonElement(api.requests.single { it.path == "/api/plugins/off" }.body).jsonObject
        assertFalse(offBody["off"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun malformedPluginWriteIsUnknownAndIsNotReplayed() {
        api.on("POST", "/api/plugins/add") { _, exchange -> api.respond(exchange, 200, "not the plugin state") }
        val error = assertFailsWith<PluginWriteUnknownException> { api.client().add("@magpie-community/opencode-multi") }
        assertNull(error.cause)
        assertFalse(error.toString().contains("not the plugin state"))
        assertEquals(1, api.requests.count { it.path == "/api/plugins/add" })
    }

    @Test fun explicitGatewayRecoveryUsesManagerTransportAndReadsActualState() {
        api.responseBodies["POST /api/androidhost/plugin-gateway-access/restore"] = "{\"ok\":true}"
        assertEquals(1, api.client().restoreGatewayAccess().entries.size)
        assertEquals(listOf("/api/androidhost/plugin-gateway-access/restore", "/api/plugins"), api.requests.map { it.path })
        assertEquals("POST", api.requests.first().method)
        assertEquals("{}", api.requests.first().body)
    }

    @Test fun uncertainGatewayRecoveryCannotBeReplayed() {
        api.responseBodies["POST /api/androidhost/plugin-gateway-access/restore"] = "{\"ok\":false}"
        assertFailsWith<PluginWriteUnknownException> { api.client().restoreGatewayAccess() }
        assertEquals(1, api.requests.size)
    }

    @Test fun marketInstallStatusUsesAliasesWithoutMatchingMissingNames() {
        val listing = PluginMarketListing(packageId = "@scope/tools-calc", name = "Calculator")
        val installed = PluginMarketSnapshot(installed = listOf(ManagedPlugin("local-snapshot", name = "calc")))
        assertTrue(installed.isInstalled(listing))
        assertFalse(PluginMarketSnapshot(installed = listOf(ManagedPlugin("unrelated"))).isInstalled(listing))
        assertFalse(installed.isInstalled(PluginMarketListing()))
    }

    @Test fun malformedPluginReadIsReadFailureAndIsDistinctFromWriteUnknown() {
        api.responseBodies["GET /api/plugins"] = "untrusted plugin response"
        val readError = assertFailsWith<PluginReadException> { api.client().plugins() }
        assertFalse(readError.toString().contains("untrusted plugin response"))

        api.on("POST", "/api/plugins/add") { _, exchange -> api.respond(exchange, 200, "untrusted plugin response") }
        assertFailsWith<PluginWriteUnknownException> { api.client().add("@magpie-community/opencode-multi") }
    }

    @Test fun providersKeepMagpieIdSeparateFromSignInPid() {
        val provider = api.client().providers().single()
        assertEquals("fakeco-plugin", provider.id)
        assertEquals("fakeco", provider.pid)
        assertEquals(2, provider.methods.size)
        assertTrue(provider.methods[0].keyless)
        assertTrue(provider.options.single().on)
    }

    @Test fun pluginSignInFlowUsesPidAndCallbackContract() {
        val client = api.client()
        val prompt = client.nextPrompt("fakeco", 1, mapOf("team" to "blue"), "team", "work")
        assertEquals("team", prompt.prompt?.key)
        assertEquals("work", prompt.inputs["team"])

        val started = client.signIn("fakeco", 1, mapOf("team" to "blue"))
        assertEquals("sign-in-id", started.id)
        assertEquals("waiting", started.state)
        assertTrue(started.pasteCallback)

        val polled = client.signInState(started.id)
        assertEquals("waiting", polled.state)
        client.submitCallback(started.id, "http://127.0.0.1:1455/?code=good")
        client.cancelSignIn(started.id)

        val promptRequest = api.requests.single { it.path == "/api/plugin-signin/prompt" }
        assertEquals("fakeco", Json.parseToJsonElement(promptRequest.body).jsonObject["provider"]!!.jsonPrimitive.content)
        val signInRequest = api.requests.single { it.path == "/api/plugin-signin" }
        assertEquals("fakeco", Json.parseToJsonElement(signInRequest.body).jsonObject["provider"]!!.jsonPrimitive.content)
        val callback = api.requests.single { it.path == "/api/signin/sign-in-id/callback" }
        assertEquals("http://127.0.0.1:1455/?code=good", Json.parseToJsonElement(callback.body).jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals(1, api.requests.count { it.path == "/api/signin/sign-in-id/cancel" })
        assertFalse(started.toString().contains("untrusted"))
    }

    @Test fun invalidIdentifierQueryAndOversizedWriteAreRejectedBeforeRequest() {
        val client = api.client()
        assertFailsWith<IllegalArgumentException> { client.signInState("../other") }
        assertFailsWith<IllegalArgumentException> { client.market("line\nbreak".repeat(100)) }
        assertFailsWith<IllegalArgumentException> { client.saveOptions("spec", api.largeOptions) }
        assertTrue(api.requests.isEmpty())
    }

    @Test fun corruptPluginRowsFailTheReadInsteadOfVanishing() {
        api.responseBodies["GET /api/plugins"] = """{"plugins":[{"spec":"ok"},"junk"],"bun":true}"""
        assertFailsWith<PluginReadException> { api.client().plugins() }
        api.responseBodies["GET /api/plugins"] = """{"plugins":[{"spec":"ok","providers":["a",5]}]}"""
        assertFailsWith<PluginReadException> { api.client().plugins() }
        api.responseBodies["GET /api/providers"] = """{"plugins":[{"id":"p","pid":"q"},"junk"]}"""
        assertFailsWith<PluginReadException> { api.client().providers() }
    }

    @Test fun signInReplyWithoutStateIsNotTreatedAsDefinite() {
        api.responseBodies["POST /api/plugin-signin"] = "{}"
        assertFailsWith<PluginWriteUnknownException> { api.client().signIn("fakeco", 1, emptyMap()) }
        api.responseBodies["GET /api/signin/sign-in-id"] = "{}"
        assertFailsWith<PluginReadException> { api.client().signInState("sign-in-id") }
    }

    @Test fun promptWriteStaysUnknownWithoutInputsObject() {
        api.responseBodies["POST /api/plugin-signin/prompt"] = """{"prompt":null}"""
        assertFailsWith<PluginWriteUnknownException> { api.client().nextPrompt("fakeco", 0, emptyMap()) }
        api.responseBodies["POST /api/plugin-signin/prompt"] = """{"prompt":null,"inputs":{}}"""
        val done = api.client().nextPrompt("fakeco", 0, emptyMap())
        assertNull(done.prompt)
        assertTrue(done.inputs.isEmpty())
        assertTrue(done.error.isEmpty())
        api.responseBodies["POST /api/plugin-signin/prompt"] = """{"error":"密钥无效"}"""
        val failed = api.client().nextPrompt("fakeco", 0, emptyMap())
        assertNull(failed.prompt)
        assertEquals("密钥无效", failed.error)
    }

    @Test fun specsWithUrlCredentialsNeverLeaveTheDevice() {
        val client = api.client()
        assertFailsWith<IllegalArgumentException> { client.add("git+https://user:ghp_token@github.com/owner/repo") }
        assertFailsWith<IllegalArgumentException> { client.add("https://user:token@github.com/owner/repo") }
        assertFailsWith<IllegalArgumentException> { client.add("git+ssh://git@host.example/owner/repo") }
        assertTrue(api.requests.isEmpty())
        client.add("github:owner/repo")
        assertEquals(1, api.requests.count { it.path == "/api/plugins/add" })
    }
}

private class PluginApiFixture {
    data class Request(
        val method: String,
        val path: String,
        val managerHeader: String?,
        val body: String,
        val contentLength: String?,
    )

    val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port: Int = server.address.port
    val baseUrl: String = "http://127.0.0.1:$port"
    val credential: ManagerCredential = ManagerCredential.fromKey(MANAGER_KEY, port)
    val requests = CopyOnWriteArrayList<Request>()
    val handlers = ConcurrentHashMap<String, (Request, HttpExchange) -> Unit>()
    val responseBodies = ConcurrentHashMap<String, String>()
    val options: JsonObject = buildJsonObject { put("enabled", true) }
    val largeOptions: JsonObject = buildJsonObject {
        put("blob", "x".repeat(70 * 1024))
    }
    private val executor = Executors.newCachedThreadPool()

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                val request = Request(
                    exchange.requestMethod,
                    exchange.requestURI.path + if (exchange.requestURI.rawQuery.isNullOrEmpty()) "" else "?" + exchange.requestURI.rawQuery,
                    exchange.requestHeaders.getFirst(BoundedCoreHttp.ANDROID_MANAGER_HEADER),
                    body,
                    exchange.requestHeaders.getFirst("Content-Length"),
                )
                requests.add(request)
                if (request.managerHeader != credential.managerHeaderValue()) {
                    respond(exchange, 401, "untrusted auth error $MANAGER_KEY")
                } else {
                    val handler = handlers[request.method + " " + exchange.requestURI.path]
                    if (handler != null) handler(request, exchange)
                    else respond(exchange, 200, responseBodies[request.method + " " + exchange.requestURI.path] ?: DEFAULT_STATE)
                }
            } catch (_: Exception) {
                exchange.close()
            }
        }
        responseBodies["GET /api/plugins"] = DEFAULT_STATE
        responseBodies["GET /api/plugins/market"] = DEFAULT_MARKET
        responseBodies["GET /api/plugins/search"] = DEFAULT_SEARCH
        responseBodies["GET /api/providers"] = DEFAULT_PROVIDERS
        responseBodies["POST /api/plugin-signin/prompt"] = DEFAULT_PROMPT
        responseBodies["POST /api/plugin-signin"] = DEFAULT_SIGN_IN
        responseBodies["GET /api/signin/sign-in-id"] = DEFAULT_SIGN_IN
        for (op in listOf("add", "remove", "upgrade", "off", "options", "prefer", "update")) {
            responseBodies["POST /api/plugins/$op"] = DEFAULT_STATE
        }
        server.start()
    }

    fun client() = PluginManagementClient(credential, baseUrl)

    fun on(method: String, path: String, handler: (Request, HttpExchange) -> Unit) {
        handlers[method + " " + path] = handler
    }

    fun respond(exchange: HttpExchange, status: Int, body: String) {
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
            return
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        const val MANAGER_KEY = "0123456789abcdef0123456789abcdef"
        private val DEFAULT_STATE = """
            {"plugins":[{
              "spec":"@magpie-community/opencode-multi",
              "package":"@magpie-community/opencode-multi",
              "ownName":"Multi Plugin",
              "off":false,
              "options":{"enabled":true},
              "providers":["Multi"],
              "tools":[{"name":"look","description":"Look around"}],
              "isMiddleware":true,"middlewareOnly":false,
              "isGateway":true,"gatewayOnly":false,
              "middleware":{"hooks":["onRequest"],"calls":2,"avgMicros":1.5,"failures":0},
              "gateway":{"calls":3,"failures":0,"avgMillis":2.5,"lastOutcome":"ready","lastBytes":4},
              "version":"0.1.0","latest":"0.2.0",
              "optionsExample":{"a":true}
            }],"bun":true,"bunVersion":"1.4.2"}
        """.trimIndent()
        private val DEFAULT_MARKET = """
            {"listings":[{"package":"@magpie-community/opencode-multi","name":"Multi Plugin","kind":"tools"}],
             "state":$DEFAULT_STATE}
        """.trimIndent()
        private val DEFAULT_SEARCH = """
            {"hits":[{"package":"@magpie-community/opencode-multi","name":"Multi Plugin",
              "version":"0.2.0","description":"Multi plugin"}]}
        """.trimIndent()
        private val DEFAULT_PROVIDERS = """
            {"plugins":[{
              "id":"fakeco-plugin","pid":"fakeco","name":"Multi","spec":"@magpie-community/opencode-multi",
              "signedIn":false,"single":true,"models":1,
              "methods":[{"type":"api","label":"API key","keyless":true},
                         {"type":"oauth","label":"Browser"}],
              "options":[{"key":"enabled","label":"Enabled","default":true,"on":true}]
            }]}
        """.trimIndent()
        private val DEFAULT_PROMPT = """
            {"prompt":{"type":"text","key":"team","message":"Team?","placeholder":"blue"},
             "inputs":{"team":"work"}}
        """.trimIndent()
        private val DEFAULT_SIGN_IN = """
            {"id":"sign-in-id","agent":"fakeco-plugin","state":"waiting",
             "url":"https://fake.invalid/auth","pasteCallback":true}
        """.trimIndent()
    }
}

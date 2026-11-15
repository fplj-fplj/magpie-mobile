package cc.fplj.magpiemobile

import android.content.Intent
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cc.fplj.magpiemobile.core.*
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the APK's own Go and Bun payloads; no fake management client is used. */
@RunWith(AndroidJUnit4::class)
class OfficialPluginsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test fun officialPluginsExecuteInInstalledAndroidApk() {
        val artifacts = File(context.getExternalFilesDir(null), "plugin-runtime").apply { mkdirs() }
        val fixtures = File(context.filesDir, "runtime-fixtures").apply { mkdirs() }
        val responseMarker = File(context.filesDir, "runtime-hook-response.jsonl")
        val toolMarker = File(context.filesDir, "runtime-tool.txt")
        responseMarker.delete()
        toolMarker.delete()
        ActivityScenario.launch(MainActivity::class.java).use {
            LocalUpstream().use { upstream ->
                val installed = mutableListOf<String>()
                context.startForegroundService(Intent(context, GatewayService::class.java).setAction(GatewayService.ACTION_START))
                try {
                    await("authenticated Android core startup", 120_000) { NativeCoreSession.status.value.ready }
                    val client = NativeCoreSession.pluginClient()
                    assertEquals("0.1.3781-androidhost.2", NativeCoreSession.managementClient().capabilities().coreVersion)
                    val provider = fixture(fixtures, "provider", PROVIDER, "\"main\":\"index.mjs\"")
                    val middleware = fixture(fixtures, "middleware", MIDDLEWARE, "\"magpie\":{\"middleware\":\"runtime.middleware.mjs\"}", "runtime.middleware.mjs")
                    val hooks = fixture(fixtures, "hooks", HOOKS, "\"magpie\":{\"hooks\":\"runtime.hooks.mjs\"}", "runtime.hooks.mjs")
                    for (entry in listOf(provider, middleware, hooks)) {
                        client.add(entry.absolutePath)
                        installed.add(entry.absolutePath)
                    }
                    client.saveOptions(provider.absolutePath, kotlinx.serialization.json.Json.parseToJsonElement("{\"base\":\"${upstream.base}\"}") as kotlinx.serialization.json.JsonObject)
                    client.saveOptions(hooks.absolutePath, kotlinx.serialization.json.Json.parseToJsonElement("{\"base\":\"${upstream.base}\"}") as kotlinx.serialization.json.JsonObject)
                    await("Bun provider and three official plugin kinds") {
                        val snapshot = client.plugins()
                        snapshot.bun && snapshot.entries.any { it.spec == middleware.absolutePath && it.isMiddleware } &&
                            snapshot.entries.any { it.spec == hooks.absolutePath && it.isGateway } &&
                            client.providers().any { it.pid == "androidfixture" }
                    }
                    val browserEntry = URL(NativeCoreSession.browserEditorLink()).openConnection(Proxy.NO_PROXY) as HttpURLConnection
                    val browserCookie = try {
                        browserEntry.instanceFollowRedirects = false
                        browserEntry.setRequestProperty("Accept", "text/html")
                        assertEquals(303, browserEntry.responseCode)
                        requireNotNull(browserEntry.getHeaderField("Set-Cookie")).substringBefore(';')
                    } finally { browserEntry.disconnect() }
                    val browserOptions = JSONObject().put("spec", provider.absolutePath)
                        .put("options", JSONObject().put("base", upstream.base).put("fromBrowser", true))
                    assertEquals(200, http("http://127.0.0.1:3430/api/plugins/options", "", browserOptions.toString(), browserCookie).first)
                    val nativeRead = client.plugins().entries.single { it.spec == provider.absolutePath }
                    assertEquals("true", nativeRead.options["fromBrowser"].toString())
                    client.saveOptions(provider.absolutePath, kotlinx.serialization.json.JsonObject(nativeRead.options +
                        ("fromNative" to kotlinx.serialization.json.JsonPrimitive(true))))
                    val browserRead = JSONObject(http("http://127.0.0.1:3430/api/plugins", "", cookie = browserCookie).second).getJSONArray("plugins")
                    val shared = (0 until browserRead.length()).map { browserRead.getJSONObject(it) }
                        .single { it.getString("spec") == provider.absolutePath }.getJSONObject("options")
                    assertTrue(shared.getBoolean("fromBrowser") && shared.getBoolean("fromNative"))
                    val pid = client.providers().single { it.pid == "androidfixture" }.pid
                    val login = client.signIn(pid, 0, emptyMap(), "android-synthetic-key")
                    assertEquals("API-key login completes synchronously", "done", login.state)
                    await("real plugin API signed-in provider") { client.providers().any { it.pid == pid && it.signedIn } }
                    val oauth = client.signIn(pid, 1, emptyMap())
                    assertEquals("waiting", oauth.state)
                    client.submitCallback(oauth.id, "good")
                    await("real plugin OAuth callback") { client.signInState(oauth.id).state == "done" }
                    val cancel = client.signIn(pid, 1, emptyMap())
                    client.cancelSignIn(cancel.id)
                    await("plugin login cancellation") { client.signInState(cancel.id).state == "canceled" }
                    NativeCoreSession.providerClient().create(ProviderCreateDraft("android-runtime", "Android runtime",
                        upstream.base + "/v1", "synthetic-upstream-key", listOf("fake-echo"), allowLoopbackHttp = true))
                    val auth = requireNotNull(NativeCoreSession.currentGatewayKey())
                    val models = JSONObject(http("http://127.0.0.1:3425/v1/models", auth).second).getJSONArray("data")
                    val nativeModel = (0 until models.length()).map { models.getJSONObject(it).getString("id") }
                        .first { it.contains("android-runtime") && it.endsWith("fake-echo") }
                    val pluginModel = (0 until models.length()).map { models.getJSONObject(it).getString("id") }
                        .first { it.contains("androidfixture") && it.endsWith("fake-echo") }
                    val body = JSONObject().put("model", nativeModel).put("stream", false)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "android-probe")))
                    val reply = http("http://127.0.0.1:3425/v1/chat/completions", auth, body.toString())
                    assertEquals(200, reply.first)
                    assertTrue(reply.second.contains("hook-rewritten") && reply.second.contains("middleware-rewritten"))
                    assertTrue(upstream.requests.any { it.contains("hook-rewritten") && it.contains("middleware-rewritten") })
                    val pluginReply = http("http://127.0.0.1:3425/v1/chat/completions", auth,
                        JSONObject(body.toString()).put("model", pluginModel).toString())
                    assertEquals("Bun provider request did not reach the local upstream", 200, pluginReply.first)
                    assertTrue(pluginReply.second.contains("hook-rewritten"))
                    await("async onResponse completion and request-owned state") {
                        responseMarker.isFile && responseMarker.readLines().any { line ->
                            val value = JSONObject(line)
                            value.optBoolean("success") && value.optString("state") == "linked" && value.optInt("bridgeStatus") == 200
                        }
                    }
                    var firstDeltaBeforeUpstreamDone = false
                    val streamed = StreamingChatClient(NativeCoreSession.gatewayConfig()).stream(nativeModel,
                        listOf(ChatClient.Message("user", "android-stream"))) {
                        if (!upstream.streamFinished) firstDeltaBeforeUpstreamDone = true
                    }
                    assertTrue(streamed.contains("hook-rewritten"))
                    assertTrue("SSE was buffered until completion", firstDeltaBeforeUpstreamDone)
                    val rejectedBody = JSONObject(body.toString()).put("messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", "forbidden-word")))
                    assertEquals(400, http("http://127.0.0.1:3425/v1/chat/completions", auth, rejectedBody.toString()).first)
                    val mcp = "http://127.0.0.1:3425/mcp/magpie/tools"
                    val tools = JSONObject(http(mcp, auth, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}").second)
                        .getJSONObject("result").getJSONArray("tools")
                    val toolName = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.first { it.endsWith("_echo") }
                    val call = JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call")
                        .put("params", JSONObject().put("name", toolName).put("arguments", JSONObject().put("text", "tool-executed")))
                    val toolReply = http(mcp, auth, call.toString())
                    assertEquals(200, toolReply.first)
                    assertTrue(toolReply.second.contains("tool-executed"))
                    assertEquals("tool-executed", toolMarker.readText())
                    val snapshot = client.plugins()
                    assertTrue(snapshot.entries.single { it.spec == middleware.absolutePath }.middleware!!.calls > 0)
                    assertTrue(snapshot.entries.single { it.spec == hooks.absolutePath }.gateway!!.calls > 0)
                    val failedHookBody = JSONObject(body.toString()).put("messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", "hook-throw")))
                    assertEquals(200, http("http://127.0.0.1:3425/v1/chat/completions", auth, failedHookBody.toString()).first)
                    assertEquals("error", client.plugins().entries.single { it.spec == hooks.absolutePath }.gateway!!.lastOutcome)
                    client.saveOptions(hooks.absolutePath, kotlinx.serialization.json.Json.parseToJsonElement(
                        "{\"base\":\"${upstream.base}\",\"gatewayTimeoutMs\":500}") as kotlinx.serialization.json.JsonObject)
                    val timeoutBody = JSONObject(failedHookBody.toString()).put("messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", "hook-timeout")))
                    val timeoutStarted = SystemClock.elapsedRealtime()
                    assertEquals(200, http("http://127.0.0.1:3425/v1/chat/completions", auth, timeoutBody.toString()).first)
                    assertTrue("hook timeout did not bound the request", SystemClock.elapsedRealtime() - timeoutStarted < 10_000)
                    assertEquals("timeout", client.plugins().entries.single { it.spec == hooks.absolutePath }.gateway!!.lastOutcome)
                    client.saveOptions(hooks.absolutePath, kotlinx.serialization.json.Json.parseToJsonElement(
                        "{\"base\":\"${upstream.base}\"}") as kotlinx.serialization.json.JsonObject)
                    client.setOff(middleware.absolutePath, true)
                    assertEquals(200, http("http://127.0.0.1:3425/v1/chat/completions", auth, rejectedBody.toString()).first)
                    client.setOff(middleware.absolutePath, false)
                    for (remote in listOf("@magpie-community/tools-calc@0.1.0",
                        "github:guotree/magpie-websearch#3ff2a8deec6742d1f74e3a65c3b006405ac27615")) {
                        val added = client.add(remote)
                        val entry = added.entries.first { it.spec == remote ||
                            (remote.startsWith("@magpie-community/tools-calc") && it.packageId == "@magpie-community/tools-calc") ||
                            (remote.startsWith("github:") && it.packageId == "@guotree/magpie-websearch") }
                        installed.add(entry.spec)
                        assertTrue("remote plugin did not load", entry.error.isBlank())
                    }
                    val calcPackage = "@magpie-community/tools-calc"
                    val calcBefore = client.plugins().entries.single { it.spec == calcPackage || it.spec.substringBeforeLast('@') == calcPackage }
                    val calcAfter = client.upgrade(calcBefore.spec).entries.single { it.spec == calcPackage || it.spec.substringBeforeLast('@') == calcPackage }
                    assertTrue("upgraded npm plugin did not load", calcAfter.error.isBlank())
                    installed.remove(calcBefore.spec)
                    installed.add(calcAfter.spec)
                    val communityTools = JSONObject(http(mcp, auth, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}").second)
                        .getJSONObject("result").getJSONArray("tools")
                    val calc = (0 until communityTools.length()).map { communityTools.getJSONObject(it) }.first {
                        it.getString("name").contains("calc") && it.getJSONObject("inputSchema").getJSONObject("properties").has("expression")
                    }
                    val calcRequest = JSONObject().put("jsonrpc", "2.0").put("id", 4).put("method", "tools/call")
                        .put("params", JSONObject().put("name", calc.getString("name"))
                            .put("arguments", JSONObject().put("expression", "0.1+0.2")))
                    val calcResult = JSONObject(http(mcp, auth, calcRequest.toString()).second).getJSONObject("result")
                    assertEquals("3/10", JSONObject(calcResult.getJSONArray("content").getJSONObject(0).getString("text")).getString("exact"))

                    val importer = PluginImporter(context)
                    val safFile = importer.importPlugin(DocumentsContract.buildDocumentUri(
                        FixturePluginDocumentsProvider.AUTHORITY, FixturePluginDocumentsProvider.DOC_SINGLE), false)
                    val safTree = importer.importPlugin(DocumentsContract.buildTreeDocumentUri(
                        FixturePluginDocumentsProvider.AUTHORITY, FixturePluginDocumentsProvider.DOC_TREE), true)
                    for (spec in listOf(safFile, safTree)) { client.add(spec); installed.add(spec) }
                    assertTrue(File(safTree, "node_modules/is-number/index.js").isFile)
                    val safReply = http("http://127.0.0.1:3425/v1/chat/completions", auth, body.toString())
                    assertEquals(200, safReply.first)
                    assertTrue("SAF single-file hook did not execute", safReply.second.contains("saf-file-rewritten"))
                    val safTools = JSONObject(http(mcp, auth, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\"}").second)
                        .getJSONObject("result").getJSONArray("tools")
                    val safName = (0 until safTools.length()).map { safTools.getJSONObject(it).getString("name") }.first { it.endsWith("_saf_describe") }
                    val safCall = JSONObject().put("jsonrpc", "2.0").put("id", 6).put("method", "tools/call")
                        .put("params", JSONObject().put("name", safName).put("arguments", JSONObject()))
                    assertTrue(http(mcp, auth, safCall.toString()).second.contains("saf-fixture isNumber(42)=true"))
                    client.setOff(safTree, true)
                    val offTools = JSONObject(http(mcp, auth, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}").second)
                        .getJSONObject("result").getJSONArray("tools")
                    assertFalse((0 until offTools.length()).any { offTools.getJSONObject(it).getString("name") == safName })
                    client.setOff(safTree, false)
                    client.remove(safTree)
                    assertTrue(client.plugins().entries.none { it.spec == safTree })
                    importer.removeImported(safTree)
                    assertFalse(File(safTree).exists())
                    installed.remove(safTree)
                    device.findObject(By.text("更多"))?.click()
                    assertTrue(device.wait(Until.hasObject(By.text("插件与工具")), 10_000))
                    device.findObject(By.text("插件与工具")).click()
                    assertTrue(device.wait(Until.hasObject(By.text("官方外部插件")), 15_000))
                    val expectedCount = client.plugins().entries.size
                    assertTrue(device.wait(Until.hasObject(By.text("$expectedCount 个已安装")), 20_000))
                    assertTrue(device.takeScreenshot(File(artifacts, "native-plugins.png")))
                    device.findObject(By.res("plugin-installed-filter")).text = provider.absolutePath
                    val providerRow = By.res(officialPluginRowTag(provider.absolutePath))
                    assertTrue(device.wait(Until.hasObject(providerRow), 10_000))
                    device.findObject(providerRow).click()
                    assertTrue(device.wait(Until.hasObject(By.text("参数")), 10_000))
                    assertTrue(device.takeScreenshot(File(artifacts, "native-plugin-detail.png")))
                    device.findObject(By.text("参数")).click()
                    val uiOptions = JSONObject().put("base", upstream.base).put("fromBrowser", true).put("fromNative", true).put("fromNativeUI", true)
                    val optionsEditor = By.res("plugin-options-json").enabled(true)
                    assertTrue(device.wait(Until.hasObject(optionsEditor), 10_000))
                    device.findObject(optionsEditor).click()
                    device.waitForIdle()
                    device.findObject(optionsEditor).text = uiOptions.toString()
                    device.pressBack() // close the input method
                    device.findObject(By.text("保存参数")).click()
                    await("native options editor save") { client.plugins().entries.single { it.spec == provider.absolutePath }.options["fromNativeUI"].toString() == "true" }
                    assertTrue(device.takeScreenshot(File(artifacts, "native-plugin-options.png")))
                    val browserUiRead = JSONObject(http("http://127.0.0.1:3430/api/plugins", "", cookie = browserCookie).second).getJSONArray("plugins")
                    assertTrue((0 until browserUiRead.length()).map { browserUiRead.getJSONObject(it) }
                        .single { it.getString("spec") == provider.absolutePath }.getJSONObject("options").getBoolean("fromNativeUI"))
                    device.findObject(By.text("连接与工具")).click()
                    assertTrue(device.wait(Until.hasObject(By.text("Test API key")), 10_000))
                    device.findObject(By.text("Test API key")).click()
                    assertTrue(device.wait(Until.hasObject(By.text("API 密钥")), 10_000))
                    assertTrue(device.takeScreenshot(File(artifacts, "native-plugin-login.png")))
                    val loginEditor = By.res("plugin-login-key").enabled(true)
                    assertTrue(device.wait(Until.hasObject(loginEditor), 10_000))
                    device.findObject(loginEditor).click()
                    device.findObject(loginEditor).text = "synthetic-ui-key"
                    device.pressBack()
                    device.findObject(By.text("保存并连接")).click()
                    assertTrue(device.wait(Until.hasObject(By.text("连接成功")), 20_000))
                    device.findObject(By.text("返回插件")).click()
                    File(artifacts, "upstream-requests.jsonl").writeText(upstream.requests.joinToString("\n"))
                    File(artifacts, "hook-response.jsonl").writeText(responseMarker.readText())
                    File(artifacts, "result.txt").writeText("PASS: real Android Go+Bun provider login, tool execution, middleware, async hooks, nonstream and live SSE\n")
                } finally {
                    try {
                        device.takeScreenshot(File(artifacts, "last-screen.png"))
                        File(artifacts, "core-status.txt").writeText(GatewayRuntime.status.value.toString())
                        installed.reversed().forEach { spec -> runCatching {
                            NativeCoreSession.pluginClient().remove(spec)
                            PluginImporter(context).removeImported(spec)
                        } }
                        context.startService(Intent(context, GatewayService::class.java).setAction(GatewayService.ACTION_STOP))
                        await("Android core and Bun shutdown", 30_000) {
                            !GatewayRuntime.stopping.value && NativeCoreSession.status.value.phase == NativeCoreSession.Phase.DISCONNECTED &&
                                device.executeShellCommand("ps -A -o ARGS").lineSequence().none { line ->
                                    line.contains("libmagpie_exec.so") || line.contains("libbun_exec.so")
                                }
                        }
                        val stoppedProcesses = device.executeShellCommand("ps -A -o ARGS")
                        assertFalse("Android process inspection failed", stoppedProcesses.contains("ps:"))
                        File(artifacts, "shutdown-processes.txt").writeText(stoppedProcesses)
                        for (port in listOf(3425, 3430)) {
                            assertFalse("core port $port still accepts connections", runCatching {
                                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
                                true
                            }.getOrDefault(false))
                        }
                        File(artifacts, "shutdown.txt").writeText("PASS: core and Bun processes stopped\n")
                    } finally {
                        // AGP removes app storage when it uninstalls the test APK. Keep the
                        // evidence under the test shell's directory before returning to AGP.
                        device.executeShellCommand("mkdir -p /data/local/tmp/magpie-plugin-runtime")
                        // UiAutomation splits arguments directly; this app-owned path has
                        // no spaces, and shell quoting would become literal filename text.
                        device.executeShellCommand("cp -R ${artifacts.absolutePath}/. /data/local/tmp/magpie-plugin-runtime/")
                        assertEquals("runtime evidence was not copied before uninstall",
                            "/data/local/tmp/magpie-plugin-runtime/core-status.txt",
                            device.executeShellCommand("ls /data/local/tmp/magpie-plugin-runtime/core-status.txt").trim())
                    }
                }
            }
        }
    }

    private fun fixture(root: File, name: String, code: String, manifest: String, entry: String = "index.mjs"): File = File(root, name).apply {
        mkdirs()
        File(this, "package.json").writeText("{\"name\":\"android-test-$name\",\"version\":\"1.0.0\",\"type\":\"module\",$manifest}")
        File(this, entry).writeText(code)
    }

    private fun await(description: String, timeout: Long = 30_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        var failure: Exception? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try { if (condition()) return } catch (e: Exception) { failure = e }
            SystemClock.sleep(200)
        }
        throw AssertionError("Timed out: $description (${failure?.javaClass?.simpleName ?: "no exception"})")
    }

    private fun http(url: String, key: String, body: String? = null, cookie: String? = null): Pair<Int, String> {
        val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            if (key.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $key")
            if (cookie != null) connection.setRequestProperty("Cookie", cookie)
            connection.setRequestProperty("Accept", "application/json, text/event-stream")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toByteArray()
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            return code to (if (code >= 400) connection.errorStream else connection.inputStream).use { it?.bufferedReader()?.readText().orEmpty() }
        } finally { connection.disconnect() }
    }

    private class LocalUpstream : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val sockets = CopyOnWriteArrayList<Socket>()
        val requests = CopyOnWriteArrayList<String>()
        val base = "http://127.0.0.1:${server.localPort}"
        @Volatile var streamFinished = false
        init {
            executor.execute {
                while (!server.isClosed) {
                    try {
                        val socket = server.accept()
                        sockets.add(socket)
                        executor.execute { runCatching { serve(socket) }; sockets.remove(socket); socket.close() }
                    } catch (_: Exception) { if (!server.isClosed) throw AssertionError("fake upstream accept failed") }
                }
            }
        }
        private fun serve(socket: Socket) {
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            fun line(): String {
                val bytes = ArrayList<Byte>()
                while (bytes.size < 16_384) {
                    val b = input.read()
                    if (b < 0 || b == 10) return bytes.toByteArray().toString(Charsets.UTF_8).trimEnd('\r')
                    bytes.add(b.toByte())
                }
                throw IllegalStateException("fake upstream header exceeded limit")
            }
            val first = line()
            var length = 0
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
            }
            require(length in 0..1_048_576)
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) { val n = input.read(bytes, offset, length - offset); require(n > 0); offset += n }
            val raw = bytes.toString(Charsets.UTF_8)
            if (raw.isNotBlank()) requests.add(raw)
            val output = socket.getOutputStream()
            fun json(value: String) {
                val body = value.toByteArray()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(body)
                output.flush()
            }
            if (first.contains("/recall")) { json("{\"context\":\"hook-rewritten\"}"); return }
            if (first.startsWith("GET")) { json("{\"data\":[{\"id\":\"fake-echo\"}]}"); return }
            val body = JSONObject(raw)
            val messages = body.getJSONArray("messages")
            val text = messages.getJSONObject(messages.length() - 1).getString("content")
            if (body.optBoolean("stream")) {
                streamFinished = false
                output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                for (part in listOf(text.take(text.length / 2), text.drop(text.length / 2))) {
                    val event = JSONObject().put("id", "android-stream").put("object", "chat.completion.chunk")
                        .put("model", "fake-echo").put("choices", JSONArray().put(JSONObject().put("index", 0)
                            .put("delta", JSONObject().put("content", part)).put("finish_reason", JSONObject.NULL)))
                    output.write("data: $event\n\n".toByteArray())
                    output.flush()
                    Thread.sleep(500)
                }
                output.write("data: [DONE]\n\n".toByteArray())
                output.flush()
                streamFinished = true
            } else {
                json(JSONObject().put("id", "android-echo").put("object", "chat.completion").put("model", "fake-echo")
                    .put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", JSONObject().put("role", "assistant").put("content", text))
                        .put("finish_reason", "stop"))).toString())
            }
        }
        override fun close() {
            server.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    companion object {
        private val PROVIDER = """
            import { writeFileSync } from "node:fs"
            export const AndroidFixture = async (_, options = {}) => ({
              config: async (cfg) => {
                cfg.provider ??= {}
                cfg.provider.androidfixture = {name:"Android fixture",npm:"@ai-sdk/openai-compatible",api:options.base || "http://127.0.0.1:9/v1",models:{"fake-echo":{name:"Fake Echo",limit:{context:4096,output:512}}}}
              },
              auth: { provider:"androidfixture",methods:[{type:"api",label:"Test API key"},
                {type:"oauth",label:"Test OAuth",authorize:async()=>({url:"https://example.invalid/authorize",method:"code",instructions:"Paste good",
                  callback:async(code)=>code==="good"?({type:"success",refresh:"synthetic-refresh",access:"synthetic-access",expires:Date.now()+3600000}):({type:"failed"})})}],
                loader: async () => ({apiKey:"synthetic",baseURL:options.base + "/v1"}) },
              tool: {echo:{description:"Android runtime echo",args:{type:"object",properties:{text:{type:"string"}},required:["text"]},
                execute: async (args) => {writeFileSync(process.env.HOME + "/runtime-tool.txt",args.text);return args.text}}}
            })
        """.trimIndent()
        private val MIDDLEWARE = """
            export function onRequest(body, ctx) {
              const message = body.messages[body.messages.length - 1]
              if (String(message.content).includes("forbidden-word")) return ctx.reject(400,"fixture-reject")
              message.content += "|middleware-rewritten"
              return body
            }
        """.trimIndent()
        private val HOOKS = """
            import { appendFileSync } from "node:fs"
            export async function onRequest(body, ctx) {
              const text = String(body.messages.at(-1).content)
              if (text.includes("hook-throw")) throw new Error("fixture hook failure")
              if (text.includes("hook-timeout")) await new Promise((resolve, reject) => {
                const timer = setTimeout(resolve, 30000)
                ctx.signal.addEventListener("abort", () => {clearTimeout(timer);reject(new DOMException("fixture aborted","AbortError"))}, {once:true})
              })
              const recalled = await fetch(ctx.options.base + "/recall").then(r => r.json())
              ctx.state.bridgeStatus = (await fetch("http://127.0.0.1:3425/v1/models")).status
              ctx.state.link = "linked"
              body.messages[body.messages.length - 1].content += "|" + recalled.context
              return body
            }
            export async function onResponse(reply, ctx) {
              appendFileSync(process.env.HOME + "/runtime-hook-response.jsonl",JSON.stringify({success:ctx.success,state:ctx.state.link,bridgeStatus:ctx.state.bridgeStatus}) + "\n")
            }
        """.trimIndent()
    }
}

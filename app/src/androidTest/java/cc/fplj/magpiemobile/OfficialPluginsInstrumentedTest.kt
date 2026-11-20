package cc.fplj.magpiemobile

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import cc.fplj.magpiemobile.core.*
import cc.fplj.magpiemobile.data.ChatRepository
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName, android.Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        val artifacts = File(context.getExternalFilesDir(null), "plugin-runtime").apply { mkdirs() }
        val fixtures = File(context.filesDir, "runtime-fixtures").apply { mkdirs() }
        val responseMarker = File(context.filesDir, "runtime-hook-response.jsonl")
        val toolMarker = File(context.filesDir, "runtime-tool.txt")
        responseMarker.delete()
        toolMarker.delete()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            LocalUpstream().use { upstream ->
                val installed = mutableListOf<String>()
                try {
                    val power = By.res("gateway-power-switch")
                    await("gateway switch visible") { dismissEmulatorLauncherAnr(); device.hasObject(power) }
                    assertFalse(device.findObject(power).isChecked)
                    assertTrue(device.takeScreenshot(File(artifacts, "gateway-off-portrait.png")))
                    device.setOrientationLeft()
                    await("landscape gateway switch") { device.hasObject(power) && context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                    device.waitForIdle()
                    assertTrue(device.takeScreenshot(File(artifacts, "gateway-off-landscape.png")))
                    device.findObject(power).click()
                    await("authenticated Android core startup", 120_000) { NativeCoreSession.status.value.ready }
                    await("gateway switch checked after startup") { device.findObject(power)?.isChecked == true }
                    assertTrue(device.takeScreenshot(File(artifacts, "gateway-on-landscape.png")))
                    device.setOrientationNatural()
                    await("portrait gateway after startup") { device.hasObject(power) && context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
                    device.waitForIdle()
                    assertTrue(device.takeScreenshot(File(artifacts, "gateway-on-portrait.png")))
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
                    verifyPendingPluginLoginCancellation(upstream, artifacts, pid)
                    NativeCoreSession.providerClient().create(ProviderCreateDraft("android-runtime", "Android runtime",
                        upstream.base + "/v1", "synthetic-upstream-key", listOf("fake-echo"), allowLoopbackHttp = true))
                    verifyModelDiscoveryPinContract(upstream, artifacts, browserCookie)
                    val capabilityReport = ProviderModelCapabilityClient(NativeCoreSession.gatewayConfig())
                        .run("android-runtime", "fake-echo")
                    assertNotNull("saved provider route missing", capabilityReport.route)
                    assertTrue("three checks must pass: ${capabilityReport.results}",
                        capabilityReport.results.all { it.status == ProviderCapabilityStatus.PASSED })
                    File(artifacts, "provider-capabilities.txt").writeText(
                        capabilityReport.results.joinToString("\n") { "${it.check}: ${it.status}" },
                    )
                    val auth = requireNotNull(NativeCoreSession.currentGatewayKey())
                    val models = JSONObject(http("http://127.0.0.1:3425/v1/models", auth).second).getJSONArray("data")
                    val nativeModel = (0 until models.length()).map { models.getJSONObject(it).getString("id") }
                        .first { it.contains("android-runtime") && it.endsWith("fake-echo") }
                    // Provider editors list provider-local IDs; bind that catalog entry to the
                    // gateway's qualified native model instead of inventing a fallback model.
                    val upstreamModels = JSONObject(http(upstream.base + "/v1/models", "").second).getJSONArray("data")
                    val providerCatalogModel = (0 until upstreamModels.length())
                        .map { upstreamModels.getJSONObject(it).getString("id") }
                        .single { nativeModel.endsWith("/$it") }
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
                    for (spec in listOf(safFile, safTree)) {
                        importer.markSubmitted(spec)
                        client.add(spec)
                        installed.add(spec)
                    }
                    importer.reconcile(client.plugins())
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
                    assertEquals(200, http("http://127.0.0.1:3430/api/plugins/remove", "",
                        JSONObject().put("spec", safTree).toString(), browserCookie).first)
                    assertTrue(client.plugins().entries.none { it.spec == safTree })
                    importer.reconcile(client.plugins())
                    assertFalse(File(safTree).exists())
                    installed.remove(safTree)
                    await("native More navigation") {
                        dismissEmulatorLauncherAnr()
                        if (device.hasObject(By.text("插件与工具"))) true
                        else { device.findObject(By.text("更多"))?.click(); false }
                    }
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
                    assertTrue(device.wait(Until.hasObject(By.text("保存参数").enabled(true)), 15_000))
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
                    await("plugin row after returning from API login") { device.hasObject(providerRow) }
                    device.findObject(providerRow).click()
                    assertTrue(device.wait(Until.hasObject(By.text("连接与工具")), 10_000))
                    device.findObject(By.text("连接与工具")).click()
                    val delayedLogin = By.text("Test delayed OAuth").enabled(true)
                    assertTrue(device.wait(Until.hasObject(delayedLogin), 10_000))
                    device.findObject(delayedLogin).click()
                    assertTrue(device.wait(Until.hasObject(By.text("开始登录").enabled(true)), 10_000))
                    device.findObject(By.text("开始登录")).click()
                    val endTracking = By.res("plugin-login-end-tracking").enabled(true)
                    await("real OAuth waiting and local tracking controls") {
                        device.hasObject(By.text("等待完成登录")) && device.hasObject(endTracking)
                    }
                    assertTrue(device.takeScreenshot(File(artifacts, "native-plugin-login-recovery.png")))
                    val confirmEndTracking = By.res("plugin-login-confirm-end-tracking")
                    await("local tracking confirmation after actual auth worker exits") {
                        if (device.hasObject(confirmEndTracking)) true
                        else { device.findObject(endTracking)?.click(); false }
                    }
                    assertTrue(device.takeScreenshot(File(artifacts, "native-plugin-login-end-confirmation.png")))
                    device.findObject(By.text("继续核对")).click()
                    await("dismiss tracking confirmation without abandoning real OAuth") {
                        !device.hasObject(confirmEndTracking) && device.hasObject(By.text("等待完成登录"))
                    }
                    device.findObject(By.text("取消登录")).click()
                    await("real OAuth UI cancellation confirmed by core") {
                        device.hasObject(By.text("登录已取消")) && device.hasObject(By.text("核心已确认登录任务取消"))
                    }
                    assertTrue(device.takeScreenshot(File(artifacts, "native-plugin-login-canceled.png")))
                    device.findObject(By.text("返回插件")).click()
                    val connectedOwner = NativeCoreSession.providerSessionId()
                    val connectedProviderSession = NativeCoreSession.providerSessionKey()
                    dismissEmulatorLauncherAnr()
                    device.pressHome()
                    assertEquals(200, http("http://127.0.0.1:3425/api/hello", auth).first)
                    device.executeShellCommand("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n cc.fplj.magpiemobile/.MainActivity")
                    scenario.recreate()
                    // ActivityScenario's background invoker can leave its EmptyActivity on top.
                    // Restore the launcher task after recreation before checking the real app UI.
                    device.executeShellCommand("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n cc.fplj.magpiemobile/.MainActivity")
                    dismissEmulatorLauncherAnr()
                    assertTrue(device.wait(Until.hasObject(By.pkg("cc.fplj.magpiemobile").depth(0)), 15_000))
                    if (!device.wait(Until.hasObject(By.text("官方外部插件")), 2000)) {
                        assertTrue(device.wait(Until.hasObject(By.text("插件与工具")), 10_000))
                        device.findObject(By.text("插件与工具")).click()
                    }
                    assertTrue(device.wait(Until.hasObject(By.text("官方外部插件")), 15_000))
                    assertEquals("activity recreation restarted the core session", connectedOwner, NativeCoreSession.providerSessionId())
                    assertEquals("activity recreation changed the provider connection", connectedProviderSession, NativeCoreSession.providerSessionKey())
                    assertEquals("true", client.plugins().entries.single { it.spec == provider.absolutePath }.options["fromNativeUI"].toString())
                    device.findObject(By.res("plugin-installed-filter")).clear()
                    await("installed filter cleared in Compose") { device.findObject(By.res("plugin-installed-filter"))?.text.orEmpty().isEmpty() }
                    val previousScale = device.executeShellCommand("settings get system font_scale").trim()
                        .takeIf { it.matches(Regex("[0-9]+(\\.[0-9]+)?")) } ?: "1.0"
                    val previousScaleValue = previousScale.toFloat()
                    try {
                        device.executeShellCommand("settings put system font_scale 1.3")
                        await("Android font scale change") { context.resources.configuration.fontScale > 1.25f }
                        device.waitForIdle()
                        SystemClock.sleep(500) // allow configuration and dialog-window animations to finish
                        device.waitForIdle()
                        assertTrue(device.takeScreenshot(File(artifacts, "native-plugins-large-font.png")))
                    } finally {
                        device.executeShellCommand("settings put system font_scale $previousScale")
                        await("Android font scale restore") {
                            kotlin.math.abs(context.resources.configuration.fontScale - previousScaleValue) < 0.05f
                        }
                        device.waitForIdle()
                    }
                    File(artifacts, "upstream-requests.jsonl").writeText(upstream.requests.joinToString("\n"))
                    File(artifacts, "hook-response.jsonl").writeText(responseMarker.readText())
                    device.pressBack()
                    await("provider tab after font scale restore") {
                        dismissEmulatorLauncherAnr()
                        device.hasObject(By.text("提供商"))
                    }
                    prepareImplicitProviderModels(upstream, browserCookie)
                    device.findObject(By.text("提供商")).click()
                    await("provider list visible") { device.hasObject(By.text("模型提供商")) }
                    await("saved provider visible") {
                        if (device.hasObject(By.text("Android runtime"))) true
                        else {
                            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                                device.displayWidth / 2, device.displayHeight / 3, 20)
                            false
                        }
                    }
                    val savedProviderRow = By.res("provider-row:android-runtime")
                    await("saved provider row actionable") { device.hasObject(savedProviderRow) }
                    refreshProviderRow("0 / 1 模型")
                    device.findObject(savedProviderRow).click()
                    val connectivityEntry = By.res("provider-connectivity-entry")
                    await("saved provider connectivity entry") { device.hasObject(connectivityEntry) }
                    val modelSheet = By.res("model-selection-sheet")
                    val providerModelSelector = By.res("provider-model-selector")
                        .enabled(true)
                        .clickable(true)
                    await("saved provider model tab") { device.hasObject(By.text("模型")) }
                    device.findObject(By.text("模型")).click()
                    await("saved provider real model catalog") {
                        device.findObject(providerModelSelector)
                            ?.findObject(By.text(providerCatalogModel)) != null
                    }
                    device.findObject(providerModelSelector).click()
                    await("provider model sheet visible") { device.hasObject(modelSheet) }
                    var providerModelOption = awaitStableModelOption(
                        providerCatalogModel,
                        "saved provider directory model",
                    )
                    assertTrue("saved provider model should start selected", providerModelOption.isChecked)
                    assertTrue(device.takeScreenshot(File(artifacts, "provider-model-picker.png")))
                    providerModelOption.click()
                    await("provider model deselected") {
                        device.findObject(By.res("model-option:$providerCatalogModel"))?.isChecked == false
                    }
                    val modelConfirm = By.res("model-selection-confirm")
                        .enabled(true)
                        .clickable(true)
                    await("provider multi-select apply action") { device.hasObject(modelConfirm) }
                    device.findObject(modelConfirm).click()
                    await("provider empty selection applied") {
                        !device.hasObject(modelSheet) &&
                            device.findObject(providerModelSelector)
                                ?.findObject(By.text("选择要启用的模型")) != null
                    }
                    device.findObject(providerModelSelector).click()
                    await("provider model sheet reopened") { device.hasObject(modelSheet) }
                    providerModelOption = awaitStableModelOption(
                        providerCatalogModel,
                        "saved provider directory model after apply",
                    )
                    assertFalse("provider deselection should survive apply", providerModelOption.isChecked)
                    providerModelOption.click()
                    await("provider model reselected") {
                        device.findObject(By.res("model-option:$providerCatalogModel"))?.isChecked == true
                    }
                    device.findObject(modelConfirm).click()
                    await("provider multi-select selection applied") {
                        !device.hasObject(modelSheet) &&
                            device.findObject(providerModelSelector)
                                ?.findObject(By.text(providerCatalogModel)) != null
                    }
                    device.findObject(connectivityEntry).click()
                    await("provider test dialog") { device.hasObject(By.text("开始测试")) }
                    val implicitProvider = NativeCoreSession.providerClient().getProviders()
                        .providers.single { it.id == "android-runtime" }
                    assertTrue("UI checks must use an actually implicit saved provider", implicitProvider.chosen.isEmpty())
                    assertEquals(listOf(providerCatalogModel), implicitProvider.models.filter { it.on }.map { it.id })
                    assertTrue("implicit saved selection must expose the actual gateway route", runtimeProviderRoutePresent(auth))
                    val connectivityStart = By.res("provider-connectivity-start")
                    await("native test action accessible") { device.hasObject(connectivityStart) }
                    assertTrue("automatic saved model must enable testing", device.findObject(connectivityStart).isEnabled)
                    device.findObject(connectivityStart).click()
                    await("three real provider checks in UI", 90_000) {
                        device.hasObject(By.text("三项模型能力检查已通过。"))
                    }
                    assertTrue(device.takeScreenshot(File(artifacts, "provider-connectivity-results.png")))
                    device.findObject(By.text("关闭")).click()
                    await("implicit provider test dialog closed") {
                        !device.hasObject(By.text("测试连接"))
                    }
                    device.findObject(By.desc("返回提供商列表")).click()
                    await("implicit provider editor closed") { !device.hasObject(By.res("provider-editor-surface")) }
                    verifyDisabledProviderModels(artifacts, browserCookie, auth)
                    await("provider add button visible") {
                        if (device.hasObject(By.text("添加"))) true
                        else {
                            device.swipe(device.displayWidth / 2, device.displayHeight / 3,
                                device.displayWidth / 2, device.displayHeight * 3 / 4, 20)
                            false
                        }
                    }
                    device.findObject(By.text("添加")).click()
                    await("provider type sheet visible") {
                        device.hasObject(By.text("自定义 OpenAI 兼容"))
                    }
                    assertTrue(device.takeScreenshot(File(artifacts, "provider-add-templates.png")))
                    device.findObject(By.text("自定义 OpenAI 兼容")).click()
                    device.waitForIdle()
                    val editorSurface = By.res("provider-editor-surface")
                    await("provider editor fills the physical display") {
                        uiBounds("provider-editor-surface") != null
                    }
                    val editorBounds = requireNotNull(uiBounds("provider-editor-surface"))
                    File(artifacts, "provider-editor-window-bounds.txt").writeText(
                        "display=${device.displayWidth}x${device.displayHeight}\nsurface=$editorBounds\n",
                    )
                    assertEquals(
                        "Provider editor does not fill the physical display: " +
                            "display=${device.displayWidth}x${device.displayHeight} surface=$editorBounds",
                        Rect(0, 0, device.displayWidth, device.displayHeight),
                        editorBounds,
                    )
                    assertTrue(device.takeScreenshot(File(artifacts, "provider-add-config.png")))
                    await("provider advanced tab visible") { device.hasObject(By.text("高级")) }
                    await("provider connectivity test visible") {
                        device.hasObject(By.text("连通测试"))
                    }
                    device.findObject(By.text("连通测试")).click()
                    await("unsaved provider test notice") {
                        device.hasObject(By.text("请先保存提供商，再运行连通测试。"))
                    }
                    device.findObject(By.text("关闭")).click()
                    device.findObject(By.text("高级")).click()
                    await("provider save button visible") { device.hasObject(By.text("保存连接")) }
                    device.waitForIdle()
                    val pinField = By.res("provider-server-pin").enabled(true)
                    await("provider TLS pin field visible") {
                        if (device.hasObject(pinField)) true
                        else {
                            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                                device.displayWidth / 2, device.displayHeight / 3, 20)
                            false
                        }
                    }
                    device.findObject(pinField).click()
                    device.findObject(pinField).text = "not-a-certificate-pin"
                    scenario.onActivity { activity ->
                        activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                            .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                    }
                    await("invalid pin inline hint") {
                        device.hasObject(By.text("证书指纹须为 64 位小写十六进制；留空使用系统 TLS 证书校验。"))
                    }
                    assertTrue(device.takeScreenshot(File(artifacts, "provider-invalid-pin.png")))
                    device.findObject(pinField).clear()
                    scenario.onActivity { activity ->
                        activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                            .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                    }
                    await("empty pin keeps normal TLS hint") {
                        device.hasObject(By.text("可选 · 64 位小写十六进制；留空使用系统 TLS 证书校验。"))
                    }
                    assertTrue(device.takeScreenshot(File(artifacts, "provider-editor-advanced.png")))
                    val footerBounds = requireNotNull(uiBounds("provider-editor-footer"))
                    val saveBounds = requireNotNull(uiBounds("provider-save"))
                    File(artifacts, "provider-save-bounds.txt").writeText(
                        "display=${device.displayWidth}x${device.displayHeight}\n" +
                            "footerBounds=$footerBounds\nsaveBounds=$saveBounds\n",
                    )
                    assertTrue(
                        "Provider footer exceeds the physical display: footer=$footerBounds",
                        footerBounds.left >= 0 && footerBounds.top >= 0 &&
                            footerBounds.right <= device.displayWidth &&
                            footerBounds.bottom <= device.displayHeight,
                    )
                    assertTrue(
                        "Provider footer does not contain the save button: footer=$footerBounds save=$saveBounds",
                        footerBounds.contains(saveBounds),
                    )
                    assertTrue(
                        "Provider save button is clipped by the system navigation area: save=$saveBounds",
                        saveBounds.bottom <= device.displayHeight - 24,
                    )
                    // The field may re-open the IME after clear(); use the real editor
                    // navigation action so Back cannot be consumed by the keyboard.
                    device.findObject(By.desc("返回提供商列表")).click()
                    await("provider editor closed") { !device.hasObject(By.text("保存连接")) }
                    verifyProviderExternalReturn(scenario, artifacts)
                    device.findObject(By.text("聊天")).click()
                    await("chat model selector visible") { device.hasObject(By.res("chat-model-selector")) }
                    device.findObject(By.res("chat-model-selector")).click()
                    await("chat model sheet visible") { device.hasObject(modelSheet) }
                    assertTrue(device.takeScreenshot(File(artifacts, "chat-model-picker.png")))
                    val modelSearch = By.res("model-search").enabled(true)
                    val search = device.wait(Until.findObject(modelSearch), 10_000)
                        ?: error("model search field missing")
                    search.text = nativeModel
                    await("model search committed") {
                        device.findObject(modelSearch)?.text == nativeModel
                    }
                    val chatModelOption = awaitStableModelOption(nativeModel, "filtered chat model")
                    assertTrue(device.hasObject(modelSheet))
                    chatModelOption.click()
                    await("chat model applied") {
                        !device.hasObject(modelSheet) &&
                            !device.hasObject(By.res("model-option:$nativeModel")) &&
                            device.findObject(By.res("chat-model-selector"))
                                ?.findObject(By.text(providerCatalogModel)) != null
                    }
                    device.waitForIdle()
                    assertTrue(device.takeScreenshot(File(artifacts, "chat-composer-portrait.png")))
                    val previousShowImeWithHardKeyboard = device
                        .executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
                    try {
                        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
                        device.findObject(By.res("chat-draft")).click()
                        val requiredStablePolls = 3
                        val pollEvidence = mutableListOf<String>()
                        var previousFrame: ChatImeFrame? = null
                        var lastFrame: ChatImeFrame? = null
                        var stablePolls = 0
                        var pollCount = 0
                        val startedAt = SystemClock.elapsedRealtime()
                        val deadline = startedAt + 30_000
                        while (SystemClock.elapsedRealtime() < deadline) {
                            pollCount++
                            val panel = chatPanelBounds()
                            val selector = uiBounds("chat-model-selector")
                            val send = uiBounds("chat-send")
                            var displayWidth = 0
                            var displayHeight = 0
                            var density = 0f
                            var densityDpi = 0
                            var scaledDensity = 0f
                            var imeVisible = false
                            var ime = Rect()
                            var systemBars = Rect()
                            scenario.onActivity { activity ->
                                val metrics = activity.resources.displayMetrics
                                displayWidth = metrics.widthPixels
                                displayHeight = metrics.heightPixels
                                density = metrics.density
                                densityDpi = metrics.densityDpi
                                scaledDensity = metrics.scaledDensity
                                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                                imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                                insets?.getInsets(WindowInsetsCompat.Type.ime())?.let {
                                    ime = Rect(it.left, it.top, it.right, it.bottom)
                                }
                                insets?.getInsets(WindowInsetsCompat.Type.systemBars())?.let {
                                    systemBars = Rect(it.left, it.top, it.right, it.bottom)
                                }
                            }
                            val frame = ChatImeFrame(
                                panel = panel,
                                selector = selector,
                                send = send,
                                displayWidth = displayWidth,
                                displayHeight = displayHeight,
                                density = density,
                                densityDpi = densityDpi,
                                scaledDensity = scaledDensity,
                                imeVisible = imeVisible,
                                ime = ime,
                                systemBars = systemBars,
                            )
                            lastFrame = frame
                            if (frame.isStableCandidate() && previousFrame?.sameStableGeometry(frame) == true) {
                                stablePolls++
                            } else {
                                stablePolls = 0
                            }
                            pollEvidence += frame.toPollEvidence(
                                poll = pollCount,
                                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                                stablePolls = stablePolls,
                            )
                            if (stablePolls >= requiredStablePolls) break
                            previousFrame = frame
                            SystemClock.sleep(200)
                        }
                        val evidenceFile = File(artifacts, "chat-composer-keyboard-layout.txt")
                        evidenceFile.writeText(buildString {
                            appendLine("requiredStablePolls=$requiredStablePolls")
                            appendLine("stablePolls=$stablePolls")
                            appendLine("pollCount=$pollCount")
                            appendLine("finalFrame=${lastFrame?.toEvidenceValue().orEmpty()}")
                            appendLine()
                            appendLine("POLLS")
                            appendLine(pollEvidence.joinToString("\n"))
                        })
                        val frame = lastFrame
                            ?: throw AssertionError("No chat IME frame captured; see $evidenceFile")
                        assertEquals(
                            "Chat composer did not reach a stable keyboard frame; see $evidenceFile\n${frame.toEvidenceValue()}",
                            requiredStablePolls,
                            stablePolls,
                        )
                        assertTrue(
                            "Chat IME did not become visible; see $evidenceFile\n${frame.toEvidenceValue()}",
                            frame.imeVisible && frame.ime.bottom > 0,
                        )
                        val panel = requireNotNull(frame.panel) {
                            "Chat panel bounds missing; see $evidenceFile"
                        }
                        val selector = requireNotNull(frame.selector) {
                            "Chat model selector bounds missing; see $evidenceFile"
                        }
                        val send = requireNotNull(frame.send) {
                            "Chat send bounds missing; see $evidenceFile"
                        }
                        assertFalse("Chat panel bounds are empty: $panel", panel.isEmpty)
                        assertFalse("Chat model selector bounds are empty: $selector", selector.isEmpty)
                        assertFalse("Chat send bounds are empty: $send", send.isEmpty)
                        assertTrue("Chat panel does not contain the model selector: panel=$panel selector=$selector", panel.contains(selector))
                        assertTrue("Chat panel does not contain the send button: panel=$panel send=$send", panel.contains(send))
                        assertTrue(
                            "Chat panel is panned outside the display or IME safe area; see $evidenceFile\n${frame.toEvidenceValue()}",
                            panel.isFullyWithin(frame.displayWidth, frame.displayHeight) &&
                                panel.bottom <= frame.imeTop,
                        )
                        assertTrue(
                            "Chat model selector is outside the display or IME safe area; see $evidenceFile\n${frame.toEvidenceValue()}",
                            selector.isFullyWithin(frame.displayWidth, frame.displayHeight) &&
                                selector.bottom <= frame.imeTop,
                        )
                        assertTrue(
                            "Chat send button is outside the display or IME safe area; see $evidenceFile\n${frame.toEvidenceValue()}",
                            send.isFullyWithin(frame.displayWidth, frame.displayHeight) &&
                                send.bottom <= frame.imeTop,
                        )
                        val keyboardGapPx = frame.imeTop - send.bottom
                        val maxKeyboardGapPx = (56f * frame.density).toInt()
                        assertTrue(
                            "Chat composer leaves ${keyboardGapPx}px (${keyboardGapPx / frame.density}dp) above the IME; " +
                                "expected at most ${maxKeyboardGapPx}px (56dp); see $evidenceFile\n${frame.toEvidenceValue()}",
                            keyboardGapPx in 0..maxKeyboardGapPx,
                        )
                        assertTrue(device.takeScreenshot(File(artifacts, "chat-composer-keyboard.png")))
                        device.pressBack()
                    } finally {
                        if (previousShowImeWithHardKeyboard == "0" || previousShowImeWithHardKeyboard == "1") {
                            device.executeShellCommand(
                                "settings put secure show_ime_with_hard_keyboard $previousShowImeWithHardKeyboard",
                            )
                        } else {
                            device.executeShellCommand("settings delete secure show_ime_with_hard_keyboard")
                        }
                    }
                    device.setOrientationLeft()
                    await("landscape chat") { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                    device.waitForIdle()
                    assertTrue(device.takeScreenshot(File(artifacts, "chat-composer-landscape.png")))
                    device.setOrientationNatural()
                    await("portrait chat restored") { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
                    verifyChatProfileUiAndRequest(scenario, upstream, nativeModel, artifacts)
                    await("gateway navigation after font scale restore") {
                        dismissEmulatorLauncherAnr()
                        if (device.findObject(power)?.isChecked == true) true
                        else { device.findObject(By.text("网关"))?.click(); false }
                    }
                    verifySameOwnerProviderReconnect(artifacts)
                    verifyChatAndCatalogReconnect(upstream, artifacts)
                    verifyLiveGatewayDiagnostics(nativeModel, artifacts)
                    val stoppedOwner = requireNotNull(NativeCoreSession.providerSessionId())
                    val stoppedProviderSession = requireNotNull(NativeCoreSession.providerSessionKey())
                    val stoppedLease = NativeCoreSession.gatewayRequest(stoppedOwner)
                    val stoppedProviderLease = NativeCoreSession.providerRequest(stoppedProviderSession)
                    val stoppedPluginLease = NativeCoreSession.pluginRequest(stoppedProviderSession)
                    val notificationStop = requireNotNull(
                        context.getSystemService(android.app.NotificationManager::class.java)
                            .activeNotifications.single { it.id == 41 }.notification.actions
                            .single { it.title.toString() == "停止网关" }.actionIntent,
                    )
                    val stopPreferences = cc.fplj.magpiemobile.preferences.AppUiPreferences.open(context)
                    val beforeStopRevision = stopPreferences.state.value.value.intentRevision
                    try {
                        stoppedLease.ensureCurrent()
                        stoppedProviderLease.ensureCurrent()
                        assertFalse(stoppedProviderLease.client.getProviders().hasFileError)
                        assertTrue(stoppedPluginLease.client.plugins().bun)
                        device.findObject(power).click()
                        await("gateway switched off", 30_000) {
                            !GatewayRuntime.stopping.value && GatewayRuntime.status.value.phase == GatewayEngineController.Phase.STOPPED &&
                                device.findObject(power)?.isChecked == false
                        }
                        assertTrue("normal stop discarded final diagnostics",
                            GatewayRuntime.status.value.outputTail.contains(LIVE_DIAGNOSTIC_MARKER))
                        assertNotNull("normal stop never observed its process exit code", GatewayRuntime.status.value.exitCode)
                        File(artifacts, "gateway-final-diagnostics.txt").writeText(
                            "PASS: normal core stop preserved marker and observed exitCode=${GatewayRuntime.status.value.exitCode}\n" +
                                GatewayRuntime.status.value.outputTail,
                        )
                        assertThrows(java.util.concurrent.CancellationException::class.java) { stoppedLease.control.ensureActive() }
                        assertThrows(java.util.concurrent.CancellationException::class.java) { stoppedLease.ensureCurrent() }
                        assertThrows(java.util.concurrent.CancellationException::class.java) { NativeCoreSession.gatewayRequest(stoppedOwner) }
                        assertThrows(java.util.concurrent.CancellationException::class.java) { stoppedProviderLease.control.ensureActive() }
                        assertThrows(java.util.concurrent.CancellationException::class.java) { stoppedProviderLease.client.getProviders() }
                        assertThrows(java.util.concurrent.CancellationException::class.java) { NativeCoreSession.providerRequest(stoppedProviderSession) }
                        assertThrows(java.util.concurrent.CancellationException::class.java) { stoppedPluginLease.control.ensureActive() }
                        assertThrows(java.util.concurrent.CancellationException::class.java) { stoppedPluginLease.client.plugins() }
                        assertThrows(java.util.concurrent.CancellationException::class.java) { NativeCoreSession.pluginRequest(stoppedProviderSession) }
                        File(artifacts, "capability-session-cancellation.txt").writeText("PASS: actual core shutdown cancelled registered requests and rejected the stale owner\n")
                        File(artifacts, "provider-session-cancellation.txt").writeText("PASS: authenticated provider GET before stop; actual shutdown cancelled management lease and rejected its client and stale owner\n")
                        File(artifacts, "plugin-session-cancellation.txt").writeText("PASS: authenticated plugin GET before stop; actual shutdown cancelled plugin lease and rejected its client and stale connection key\n")
                    } finally {
                        stoppedLease.close()
                        stoppedProviderLease.close()
                        stoppedPluginLease.close()
                    }
                    assertTrue(device.takeScreenshot(File(artifacts, "gateway-off-after-toggle.png")))
                    await("original UI STOP intent persisted before notification replays") {
                        stopPreferences.state.value.value.intentRevision > beforeStopRevision &&
                            stopPreferences.state.value.value.gatewayIntent == cc.fplj.magpiemobile.preferences.GatewayIntent.STOP
                    }
                    verifyStopReplayCleanup(scenario, notificationStop, artifacts)
                    verifyRealProcessExitDiagnostics(artifacts)
                    File(artifacts, "result.txt").writeText("PASS: real Android Go+Bun provider login, tool execution, middleware, async hooks, nonstream and live SSE\n")
                } finally {
                    try {
                        device.takeScreenshot(File(artifacts, "last-screen.png"))
                        File(artifacts, "core-status.txt").writeText(GatewayRuntime.status.value.toString())
                        installed.reversed().forEach { spec -> runCatching {
                            NativeCoreSession.pluginClient().remove(spec)
                            PluginImporter(context).removeImported(spec)
                        } }
                        if (GatewayRuntime.status.value.phase != GatewayEngineController.Phase.STOPPED || GatewayRuntime.stopping.value) {
                            context.startService(Intent(context, GatewayService::class.java).setAction(GatewayService.ACTION_STOP))
                        }
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
                        device.unfreezeRotation()
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

    /** Real Go edit and Dashboard ON_RESUME; browser return is controlled, no browser UI is opened. */
    private fun verifyProviderExternalReturn(scenario: ActivityScenario<MainActivity>, artifacts: File) {
        val row = By.res("provider-row:android-runtime")
        val nameField = By.res("provider-name").enabled(true)
        val nativeDraft = "Preserved native draft"
        val externalName = "Android runtime external"
        val pendingNotice = "外部配置已变化；当前草稿已保留，关闭编辑后将刷新提供商列表。"
        lateinit var externalEditor: ExternalEditorViewModel
        scenario.onActivity { activity ->
            externalEditor = ViewModelProvider(activity)[ExternalEditorViewModel::class.java]
        }
        val previousRevision = externalEditor.state.value.returnRevision
        await("saved provider row before external return") {
            if (device.hasObject(row)) true else {
                device.swipe(device.displayWidth / 2, device.displayHeight * 7 / 10,
                    device.displayWidth / 2, device.displayHeight * 3 / 10, 20)
                false
            }
        }
        device.findObject(row).click()
        await("existing provider name input") { device.hasObject(nameField) }
        device.findObject(nameField).text = nativeDraft
        scenario.onActivity { activity ->
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        await("native provider draft committed") { device.findObject(nameField)?.text == nativeDraft }
        // Obtain the real one-use link through the production VM. Do not retain or log it.
        // The callback deliberately does not launch a browser; STARTED -> RESUMED below
        // exercises the real Dashboard observer without depending on installed browser UI.
        scenario.onActivity { externalEditor.open { } }
        await("production external editor armed for return") {
            var armed = false
            scenario.onActivity { armed = externalEditor.awaitingReturn && !externalEditor.state.value.opening }
            armed
        }
        val session = requireNotNull(NativeCoreSession.providerSessionKey())
        runBlocking {
            withProviderRequest(session) { client ->
                val draft = ProviderEditDraft(client.editable("android-runtime"), name = externalName)
                try { client.edit(draft) } finally { draft.clearSecret() }
            }
        }
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        await("Dashboard resume consumes real external return") {
            externalEditor.state.value.returnRevision == previousRevision + 1
        }
        scenario.onActivity { assertFalse(externalEditor.awaitingReturn) }
        assertEquals(nativeDraft, device.findObject(nameField)?.text)
        runBlocking {
            withProviderRequest(session) { client ->
                assertEquals("Native draft must not autosave on return", externalName,
                    client.editable("android-runtime").name)
            }
        }
        assertTrue(device.takeScreenshot(File(artifacts, "provider-external-return-draft.png")))
        await("external return pending notice inside retained editor") {
            if (device.hasObject(By.text(pendingNotice))) true else {
                device.swipe(device.displayWidth / 2, device.displayHeight * 7 / 10,
                    device.displayWidth / 2, device.displayHeight * 3 / 10, 20)
                false
            }
        }
        assertFalse("Unconfirmed list must block saving a stale revision",
            device.findObject(By.res("provider-save")).isEnabled)
        assertTrue(device.takeScreenshot(File(artifacts, "provider-external-return-pending.png")))
        device.findObject(By.desc("返回提供商列表")).click()
        await("external return closes native editor") { !device.hasObject(By.res("provider-save")) }
        await("external return automatically refreshes the provider row") {
            device.findObject(row)?.findObject(By.text(externalName)) != null &&
                device.findObject(row)?.isEnabled == true
        }
        assertTrue(device.takeScreenshot(File(artifacts, "provider-external-return-refreshed.png")))
        device.findObject(row).click()
        // The editor retains its tab ScrollState across closing; the pending notice above
        // may have left the connection form at the bottom. Reveal the name before reading it.
        await("reopened provider name field visible") {
            if (device.hasObject(nameField)) true else {
                device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 10,
                    device.displayWidth / 2, device.displayHeight * 7 / 10, 20)
                false
            }
        }
        await("reopened provider uses fresh canonical external name") {
            device.findObject(nameField)?.text == externalName
        }
        device.findObject(By.desc("返回提供商列表")).click()
        await("reopened provider editor closed") { !device.hasObject(By.res("provider-save")) }
        File(artifacts, "provider-external-return.txt").writeText(
            "PASS: installed Go canonical provider edit; real Activity-owned ExternalEditorViewModel " +
                "obtained its one-use link without retaining it; controlled STARTED -> RESUMED " +
                "triggered the production Dashboard return observer. Native draft stayed unchanged, " +
                "pending notice blocked stale saving, closing the editor automatically refreshed " +
                "the row and reopening read the new canonical name. No browser UI was launched.\n",
        )
    }

    /** Actual profile form -> retained Activity VM -> UI send -> installed Go -> local upstream. */
    private fun verifyChatProfileUiAndRequest(
        scenario: ActivityScenario<MainActivity>,
        upstream: LocalUpstream,
        model: String,
        artifacts: File,
    ) {
        val profileId = "android-profile-probe"
        val profileName = "Runtime profile"
        val prompt = "Follow the current profile instructions."
        val marker = "android-profile-real-ui-send"
        lateinit var profiles: ChatProfileViewModel
        lateinit var chat: ChatViewModel
        scenario.onActivity {
            profiles = androidx.lifecycle.ViewModelProvider(it)[ChatProfileViewModel::class.java]
            chat = androidx.lifecycle.ViewModelProvider(it)[ChatViewModel::class.java]
        }
        fun reveal(selector: androidx.test.uiautomator.BySelector, towardsTop: Boolean = false): UiObject2 {
            await("profile control visible: $selector") {
                val control = device.findObject(selector)
                if (control != null && control.visibleBounds.height() >= 20) true else {
                    device.swipe(device.displayWidth / 2,
                        device.displayHeight * (if (towardsTop) 3 else 7) / 10,
                        device.displayWidth / 2,
                        device.displayHeight * (if (towardsTop) 7 else 3) / 10, 20)
                    false
                }
            }
            return requireNotNull(device.findObject(selector))
        }
        fun clickProfileSave() {
            val selector = By.res("chat-profile-save").enabled(true).clickable(true)
            val minimumHeight = (40 * context.resources.displayMetrics.density).toInt()
            var previousBounds: Rect? = null
            var stablePolls = 0
            await("profile save fully visible with stable bounds") {
                val bounds = device.findObject(selector)?.visibleBounds
                if (bounds != null && bounds.height() >= minimumHeight) {
                    if (bounds == previousBounds) stablePolls++ else stablePolls = 0
                    previousBounds = Rect(bounds)
                    stablePolls >= 3
                } else {
                    previousBounds = null
                    stablePolls = 0
                    device.swipe(device.displayWidth / 2, device.displayHeight * 7 / 10,
                        device.displayWidth / 2, device.displayHeight * 3 / 10, 20)
                    false
                }
            }
            // Reacquire after layout settles; never click an object located before the
            // temperature error supporting text was removed. Submit only once.
            requireNotNull(device.findObject(selector)).click()
        }
        try {
            await("profile settings after portrait restoration") { device.hasObject(By.desc("模型与对话配置")) }
            device.findObject(By.desc("模型与对话配置")).click()
            reveal(By.textContains("使用模型默认参数"))
            assertFalse(device.hasObject(By.textContains(ChatProfile.DEFAULT.systemPrompt)))
            assertTrue(device.takeScreenshot(File(artifacts, "chat-profile-default.png")))
            reveal(By.text("新建预设")).click()
            reveal(By.res("chat-profile-id")).text = profileId
            reveal(By.res("chat-profile-name")).text = profileName
            reveal(By.res("chat-profile-prompt")).text = prompt
            reveal(By.res("chat-profile-temperature")).text = "abc"
            await("invalid profile temperature input committed") {
                device.findObject(By.res("chat-profile-temperature"))?.text == "abc"
            }
            clickProfileSave()
            reveal(By.text(ChatProfileFormState.TEMPERATURE_ERROR))
            assertTrue(device.hasObject(By.res("chat-profile-form")))
            assertTrue(device.takeScreenshot(File(artifacts, "chat-profile-invalid-temperature.png")))
            assertEquals(profileId, reveal(By.res("chat-profile-id"), towardsTop = true).text)
            assertEquals(profileName, reveal(By.res("chat-profile-name")).text)
            assertEquals(prompt, reveal(By.res("chat-profile-prompt")).text)
            assertEquals("abc", reveal(By.res("chat-profile-temperature")).text)
            assertFalse(profiles.state.value.profiles.any { it.id == profileId })
            reveal(By.res("chat-profile-temperature")).text = "0"
            await("valid zero input committed and inline temperature error removed") {
                device.findObject(By.res("chat-profile-temperature"))?.text == "0" &&
                    !device.hasObject(By.text(ChatProfileFormState.TEMPERATURE_ERROR))
            }
            clickProfileSave()
            await("profile saved only after a valid form submission") {
                !device.hasObject(By.res("chat-profile-form")) && profiles.state.value.activeProfile.id == profileId
            }
            assertEquals(prompt, profiles.state.value.activeProfile.systemPrompt)
            assertEquals(0.0, profiles.state.value.activeProfile.temperature, 0.0)
            reveal(By.textContains("当前预设"), towardsTop = true)
            assertTrue(device.takeScreenshot(File(artifacts, "chat-profile-saved.png")))
            // The existing form edits prompt and temperature. Exercise the other persisted
            // options through its real VM API, without claiming those controls exist in the UI.
            scenario.onActivity {
                assertTrue(profiles.saveProfile(profileId, profileName, prompt,
                    temperature = 0.0, topP = 0.0, maxTokens = 17, modelAlias = "unconfirmed-alias"))
            }
            device.findObject(By.text("关闭")).click()
            await("profile dialog closed") { !device.hasObject(By.res("chat-profile-card")) }
            scenario.onActivity { chat.newConversation() }
            await("isolated profile conversation") { !chat.state.value.busy && chat.state.value.messages.isEmpty() }
            val conversation = requireNotNull(chat.state.value.selected).id
            device.findObject(By.res("chat-draft")).text = marker
            await("real chat draft set before profile send") { chat.state.value.draft == marker }
            device.findObject(By.res("chat-send")).click()
            await("actual profile request committed through installed core") {
                !chat.state.value.sending && chat.state.value.draft.isEmpty() && chat.state.value.messages.size == 2
            }
            val matched = upstream.requests.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.filter { body ->
                body.optJSONArray("messages")?.let { messages ->
                    messages.length() > 0 && messages.getJSONObject(messages.length() - 1)
                        .optString("content").startsWith(marker)
                } == true
            }
            assertEquals("the UI send must reach the actual upstream exactly once", 1, matched.size)
            val received = matched.single()
            assertEquals("fake-echo", received.getString("model"))
            assertEquals(0.0, received.getDouble("temperature"), 0.0)
            assertEquals(0.0, received.getDouble("top_p"), 0.0)
            assertEquals(17, received.getInt("max_tokens"))
            val messages = received.getJSONArray("messages")
            assertEquals(2, messages.length())
            assertEquals("system", messages.getJSONObject(0).getString("role"))
            assertEquals(prompt, messages.getJSONObject(0).getString("content"))
            assertEquals("user", messages.getJSONObject(1).getString("role"))
            val history = ChatRepository.open(context).context(conversation)
            assertEquals(listOf("user", "assistant"), history.map { it.role })
            assertFalse(history.any { it.content == prompt })
            assertEquals("chat:$model", chat.state.value.messages.first().engine)
            File(artifacts, "chat-profile-form-and-request.txt").writeText(
                "PASS: unchanged built-in profile displays model defaults without an unsent prompt\n" +
                    "PASS: actual form rejected abc, preserved ID/name/prompt/temperature and stayed open; valid zero saved and closed\n" +
                    "PASS: real UI send captured the retained Activity profile; installed Go forwarded system, explicit zero sampling and positive token limit exactly once\n" +
                    "PASS: alias did not replace confirmed model; Room context contains only user/assistant, never the system prompt\n",
            )
        } catch (failure: Throwable) {
            // Record the form before the cleanup closes its dialog. Fixture inputs are
            // synthetic; record only safe VM status here, never profile prompt contents.
            device.takeScreenshot(File(artifacts, "chat-profile-failure.png"))
            device.dumpWindowHierarchy(File(artifacts, "chat-profile-failure-ui.xml"))
            val state = profiles.state.value
            File(artifacts, "chat-profile-failure-state.txt").writeText(
                "activeProfile=${state.activeProfile.id}\nprofileCount=${state.profiles.size}\n" +
                    "canWriteProfiles=${state.canWriteProfiles}\nerror=${state.error}\n" +
                    "storageError=${state.storageError}\nsaveBounds=${uiBounds("chat-profile-save")}\n" +
                    "temperature=${device.findObject(By.res("chat-profile-temperature"))?.text}\n" +
                    "temperatureErrorVisible=${device.hasObject(By.text(ChatProfileFormState.TEMPERATURE_ERROR))}\n" +
                    "formVisible=${device.hasObject(By.res("chat-profile-form"))}\n" +
                    "targetProfilePresent=${state.profiles.any { it.id == profileId }}\n",
            )
            throw failure
        } finally {
            scenario.onActivity { profiles.selectProfile(ChatProfile.DEFAULT.id) }
            device.findObject(By.text("关闭"))?.click()
        }
    }

    /** Real Android STOP deliveries against a controlled, still-owned late launch; no core hooks. */
    private fun verifyStopReplayCleanup(
        scenario: ActivityScenario<MainActivity>,
        notificationStop: android.app.PendingIntent,
        artifacts: File,
    ) {
        fun serviceAbsent(): Boolean = context.getSystemService(android.app.ActivityManager::class.java)
            .getRunningServices(100).none { it.service.className == GatewayService::class.java.name }
        await("actual gateway Service destruction before controlled cleanup") { serviceAbsent() }
        assertTrue(GatewayRuntime.requestStart())
        val lease = requireNotNull(GatewayRuntime.tryClaimStart())
        val owner = lease.owner
        val releaseLaunch = CountDownLatch(1)
        val launchEntered = CountDownLatch(1)
        val launches = AtomicInteger()
        val reporter = GatewayServiceReporter(owner, object : GatewayServiceEffects {
            override fun ready() = Unit
            override fun connect() = Unit
            override fun suspend() = Unit
            override fun audit(status: GatewayEngineController.Status) = Unit
            override fun log(status: GatewayEngineController.Status) = Unit
            override fun notify(status: GatewayEngineController.Status) = Unit
            override fun stopSelf() = Unit
        })
        val controller = GatewayEngineController(
            launch = {
                launches.incrementAndGet()
                launchEntered.countDown()
                // A real ProcessBuilder can return after cancellation. Hold that boundary without
                // letting the controller's stop interrupt masquerade as completed cleanup.
                val deadline = SystemClock.elapsedRealtime() + 300_000
                while (releaseLaunch.count != 0L) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    check(remaining > 0) { "controlled launch gate timed out" }
                    try { releaseLaunch.await(remaining, TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) { /* release only through the test gate */ }
                }
                ProcessBuilder("/system/bin/sh", "-c", "exit 23").start()
            },
            ready = { false }, report = { reporter.report(it) }, pollIntervalMs = 10,
            supervisor = EngineSupervisor(maxAutoRestart = 0),
        )
        val evidence = StringBuilder()
        try {
            assertTrue(controller.start())
            assertTrue(launchEntered.await(10, TimeUnit.SECONDS))
            GatewayRuntime.publish(owner, GatewayRuntime.status.value.copy(outputTail = "controlled-cleanup-marker\n"))
            assertTrue(reporter.requestStop())
            assertTrue(reporter.retire { NativeCoreSession.disconnect(owner) })
            controller.stop()
            assertFalse(controller.awaitStopped(0))
            val waiting = GatewayRuntime.status.value
            val nativeBefore = NativeCoreSession.status.value
            val preferences = cc.fplj.magpiemobile.preferences.AppUiPreferences.open(context)
            await("loaded intent revision before STOP replay") { preferences.state.value.ready }
            repeat(3) { index ->
                val revision = preferences.state.value.value.intentRevision
                notificationStop.send()
                // This persisted revision proves the real Service handled this exact STOP,
                // rather than observing the already-absent Service before Binder delivered it.
                await("notification STOP replay $index processed") {
                    preferences.state.value.value.intentRevision > revision &&
                        preferences.state.value.value.gatewayIntent == cc.fplj.magpiemobile.preferences.GatewayIntent.STOP
                }
                await("STOP-only Service destroyed after replay $index") { serviceAbsent() }
                assertTrue(GatewayRuntime.isCurrent(owner))
                assertTrue(GatewayRuntime.stopping.value)
                assertFalse(GatewayRuntime.requestStart())
                assertNull(GatewayRuntime.tryClaimStart())
                assertFalse(controller.awaitStopped(0))
                assertEquals(waiting, GatewayRuntime.status.value)
                assertEquals(nativeBefore, NativeCoreSession.status.value)
                assertEquals(1, launches.get())
                evidence.append("PASS: captured real notification STOP replay $index reached a new Android Service and persisted its intent; original lease, diagnostics, Native state and cleanup barrier preserved\n")
            }
            assertFalse("test requires an actually absent Android Service", context.stopService(Intent(context, GatewayService::class.java)))
            scenario.onActivity { it.stopGateway() }
            assertTrue(GatewayRuntime.stopping.value)
            assertTrue(GatewayRuntime.isCurrent(owner))
            assertFalse(GatewayRuntime.requestStart())
            assertFalse(controller.awaitStopped(0))
            assertEquals(waiting, GatewayRuntime.status.value)
            evidence.append("PASS: actual Activity stop callback with no Android Service did not complete the outstanding controlled worker\n")
            releaseLaunch.countDown()
            assertTrue("late real Android shell process cleanup timed out", controller.awaitStopped(10_000))
            assertTrue("only the actual lease may complete cleanup", GatewayRuntime.cleanupCompleted(lease))
            assertFalse(GatewayRuntime.stopping.value)
            assertFalse(GatewayRuntime.isCurrent(owner))
            assertEquals(GatewayEngineController.Phase.STOPPED, GatewayRuntime.status.value.phase)
            assertEquals("controlled-cleanup-marker\n", GatewayRuntime.status.value.outputTail)
            assertNotNull(GatewayRuntime.status.value.exitCode)
            assertTrue(GatewayRuntime.requestStart())
            assertEquals(1, launches.get())
            GatewayRuntime.requestStop() // Cancel the pending start; do not launch another core.
            evidence.append("PASS: released late ProcessBuilder launch exited and worker completed; exact lease alone reopened explicit start, with final marker and observed process exit code\n")
            File(artifacts, "gateway-stop-replay-cleanup.txt").writeText(evidence.toString())
        } finally {
            releaseLaunch.countDown()
            reporter.retire { NativeCoreSession.disconnect(owner) }
            controller.stop()
            assertTrue("controlled cleanup must finish before teardown", controller.awaitStopped(10_000))
            GatewayRuntime.cleanupCompleted(lease)
            GatewayRuntime.requestStop()
        }
    }

    /** Console output travels through Bun -> actual Go stderr -> Controller -> Runtime -> UI. */
    private fun verifyLiveGatewayDiagnostics(model: String, artifacts: File) {
        val before = GatewayRuntime.status.value
        val session = requireNotNull(NativeCoreSession.providerSessionKey())
        val key = requireNotNull(NativeCoreSession.currentGatewayKey())
        val metrics = StartupBenchmark.getMetrics()
        assertEquals(GatewayEngineController.Phase.RUNNING, before.phase)
        openDiagnostics()
        revealDiagnosticText("引擎状态与脱敏输出")
        assertFalse(GatewayRuntime.status.value.outputTail.contains(LIVE_DIAGNOSTIC_MARKER))
        val body = JSONObject().put("model", model).put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", LIVE_DIAGNOSTIC_PROBE)))
        assertEquals(200, http("http://127.0.0.1:3425/v1/chat/completions", key, body.toString()).first)
        await("live output while the actual core remains RUNNING", 10_000) {
            GatewayRuntime.status.value.outputTail.contains(LIVE_DIAGNOSTIC_MARKER)
        }
        val after = GatewayRuntime.status.value
        assertEquals(GatewayEngineController.Phase.RUNNING, after.phase)
        assertEquals(before.restartCount, after.restartCount)
        assertEquals(session, NativeCoreSession.providerSessionKey())
        assertEquals(key, NativeCoreSession.currentGatewayKey())
        assertEquals(metrics, StartupBenchmark.getMetrics())
        assertFalse(after.outputTail.contains(LIVE_DIAGNOSTIC_SECRET))
        assertFalse(after.outputTail.contains(LIVE_DIAGNOSTIC_SECRET.takeLast(4)))
        assertTrue(after.outputTail.length <= 8192)
        revealDiagnosticText(LIVE_DIAGNOSTIC_MARKER)
        revealDiagnosticText("配置安全备份与恢复")
        assertTrue(device.takeScreenshot(File(artifacts, "gateway-live-diagnostics.png")))
        File(artifacts, "gateway-live-diagnostics.txt").writeText(
            "PASS: Bun console -> Go stderr -> RUNNING Runtime and visible diagnostics\n" +
                "PASS: restartCount, provider connection epoch, gateway key and startup metrics unchanged\n" +
                "PASS: synthetic credential and suffix absent from bounded public tail\n" + after.outputTail,
        )
        device.findObject(By.text("网关")).click()
        await("gateway after live diagnostics") { device.hasObject(By.res("gateway-power-switch")) }
    }

    /** Controlled Android shell processes, never signals/OOM or killing the app/core. */
    private fun verifyRealProcessExitDiagnostics(artifacts: File) {
        val auditor = PostMortemAuditor(File(context.filesDir, "config"))
        val evidence = StringBuilder()
        try {
            for (code in listOf(23, 137)) {
                assertTrue(GatewayRuntime.requestStart())
                val lease = requireNotNull(GatewayRuntime.tryClaimStart())
                val owner = lease.owner
                val reporter = GatewayServiceReporter(owner, object : GatewayServiceEffects {
                    override fun ready() = Unit
                    override fun connect() = Unit
                    override fun suspend() = Unit
                    override fun audit(status: GatewayEngineController.Status) {
                        auditor.recordAbnormalExit(status.phase.name, status.exitCode, status.message + "\n" + status.outputTail)
                    }
                    override fun log(status: GatewayEngineController.Status) = Unit
                    override fun notify(status: GatewayEngineController.Status) = Unit
                    override fun stopSelf() = Unit
                })
                val controller = GatewayEngineController(
                    launch = { ProcessBuilder("/system/bin/sh", "-c",
                        "printf 'controlled-exit-marker token=$LIVE_DIAGNOSTIC_SECRET\\n'; exit $code").start() },
                    ready = { false }, report = { reporter.report(it) }, pollIntervalMs = 10,
                    supervisor = EngineSupervisor(maxAutoRestart = 0),
                )
                try {
                    assertTrue(controller.start())
                    assertTrue("controlled process cleanup timed out", controller.awaitStopped(10_000))
                    val status = GatewayRuntime.status.value
                    assertEquals(GatewayEngineController.Phase.FAILED, status.phase)
                    assertEquals(code, status.exitCode)
                    assertTrue(status.outputTail.contains("controlled-exit-marker"))
                    val report = requireNotNull(auditor.readReport())
                    assertEquals(code, report.exitCode)
                    val json = File(context.filesDir, "config/${PostMortemAuditor.AUDIT_FILE_NAME}").readText()
                    assertFalse(json.contains(LIVE_DIAGNOSTIC_SECRET))
                    assertFalse(json.contains(LIVE_DIAGNOSTIC_SECRET.takeLast(4)))
                    evidence.append("PASS: Android shell exit $code -> Controller -> production ServiceReporter -> audit JSON; fixed full mask\n")
                } finally {
                    reporter.retire { }
                    controller.stop()
                    assertTrue(controller.awaitStopped(10_000))
                    GatewayRuntime.cleanupCompleted(lease)
                }
            }
            File(artifacts, "gateway-exit-diagnostics.txt").writeText(evidence.toString())
            // Audit is read on page entry; explicitly re-enter instead of claiming a live reread.
            openDiagnostics()
            // The shared destination scroll state can restore the bottom of the earlier live tail.
            revealDiagnosticText("版本与诊断", towardsTop = true)
            revealDiagnosticText("退出码=137")
            assertTrue(device.takeScreenshot(File(artifacts, "gateway-exit-diagnostics.png")))
            evidence.append("PASS: re-entered existing diagnostics page shows observed exitCode=137\n")
            File(artifacts, "gateway-exit-diagnostics.txt").writeText(evidence.toString())
        } finally {
            GatewayRuntime.stoppedWithoutService()
        }
    }

    private fun openDiagnostics() {
        device.findObject(By.text("更多")).click()
        // More remembers its own child destination independently of the Gateway tab.
        await("diagnostics destination") {
            val back = device.findObject(By.desc("返回更多"))
            val row = device.findObject(By.text("系统与诊断"))
            if (back != null) { back.click(); false }
            else if (row != null) { row.click(); true } else {
                device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                    device.displayWidth / 2, device.displayHeight / 3, 20)
                false
            }
        }
        await("diagnostics child entered") { device.hasObject(By.desc("返回更多")) }
    }

    private fun revealDiagnosticText(text: String, towardsTop: Boolean = false) {
        await("visible diagnostic text: $text") {
            if (device.hasObject(By.textContains(text))) true else {
                val upper = device.displayHeight / 3
                val lower = device.displayHeight * 3 / 4
                device.swipe(device.displayWidth / 2, if (towardsTop) upper else lower,
                    device.displayWidth / 2, if (towardsTop) lower else upper, 20)
                false
            }
        }
    }

    /** Uses production VM and transport with the APK's Go+Bun; the wrapper only records RPC kinds. */
    private fun verifyPendingPluginLoginCancellation(upstream: LocalUpstream, artifacts: File, pid: String) {
        val store = ViewModelStore()
        val calls = CopyOnWriteArrayList<PluginAuthStep>()
        val replies = CopyOnWriteArrayList<PluginSignInState>()
        lateinit var viewModel: PluginManagementViewModel
        instrumentation.runOnMainSync {
            viewModel = PluginManagementViewModel(capture = { key ->
                val operation = PluginOperationController().capture(key)
                object : PluginSessionOperation by operation {
                    override suspend fun auth(step: PluginAuthStep): PluginAuthReply {
                        calls.add(step)
                        return operation.auth(step).also { result -> result.state?.let { replies.add(it) } }
                    }
                }
            })
            store.put("plugin-pending-cancel", viewModel)
            viewModel.attach(requireNotNull(NativeCoreSession.providerSessionKey()))
        }
        try {
            await("real plugin provider list before pending Start") {
                !viewModel.state.value.loading && viewModel.state.value.providers.any { it.pid == pid }
            }
            val provider = viewModel.state.value.providers.single { it.pid == pid }
            val method = provider.methods.indexOfFirst { it.label == "Test delayed OAuth" }
            assertTrue("delayed OAuth fixture method missing", method >= 0)
            instrumentation.runOnMainSync { viewModel.selectLogin(provider, method) }
            await("real plugin prompt before pending Start") { !viewModel.state.value.authBusy }
            instrumentation.runOnMainSync { viewModel.startLogin() }
            assertTrue("real Bun authorize did not reach held upstream", upstream.signInGateReached.await(15, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { viewModel.cancelLogin(); viewModel.cancelLogin() }
            assertTrue("Start must remain tracked until its real response", viewModel.state.value.authBusy)
            assertTrue(viewModel.state.value.loginRecovery?.cancelRequested == true)
            assertEquals(1, calls.count { it is PluginAuthStep.Start })
            assertEquals(0, calls.count { it is PluginAuthStep.Cancel })

            upstream.releaseSignInGate.countDown()
            await("actual late Start ID cancelled and read back", 30_000) {
                !viewModel.state.value.authBusy && viewModel.state.value.loginRecovery == null &&
                    replies.any { it.state == "canceled" || it.state == "cancelled" }
            }
            assertEquals("Start must never be replayed", 1, calls.count { it is PluginAuthStep.Start })
            assertEquals("duplicate Cancel clicks must send one RPC", 1, calls.count { it is PluginAuthStep.Cancel })
            val started = replies.first { it.state == "waiting" }
            assertTrue(started.id.isNotEmpty())
            assertEquals(started.id, (calls.single { it is PluginAuthStep.Cancel } as PluginAuthStep.Cancel).id)
            assertTrue(calls.any { it is PluginAuthStep.Read && it.id == started.id })
            File(artifacts, "plugin-pending-start-cancel.txt").writeText(
                "PASS: production VM held a real Go+Bun OAuth Start before its response; duplicate cancel clicks " +
                    "retained intent, late ID received one cancel RPC in the original connection, then read back " +
                    "actual canceled state; Start was sent once and recovery cleared only after confirmation\n",
            )
        } finally {
            upstream.releaseSignInGate.countDown()
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    /** Re-authenticates the real running core, without restarting its process or Service owner. */
    private fun verifySameOwnerProviderReconnect(artifacts: File) = runBlocking {
        val beforeStatus = NativeCoreSession.status.value
        val before = requireNotNull(NativeCoreSession.providerSessionKey())
        assertEquals(before, beforeStatus.providerSession)
        val cache = ProviderListCacheViewModel(readScope = this, readDispatcher = Dispatchers.IO)
        var fetches = 0
        val oldList = cache.read(before) {
            withProviderRequest(before) { it.getProviders() }.also { fetches++ }
        }
        assertFalse(oldList.hasFileError)
        val oldName = oldList.providers.single { it.id == "android-runtime" }.name
        val updatedName = "$oldName reconnected"
        // A separate editor changes real core state while the native list has a healthy cache.
        withProviderRequest(before) { client ->
            client.edit(ProviderEditDraft(client.editable("android-runtime"), name = updatedName))
        }
        assertSame(oldList, cache.read(before) { error("same-connection cache was not reused") })
        val oldLease = NativeCoreSession.providerRequest(before)
        val oldPluginLease = NativeCoreSession.pluginRequest(before)
        val oldGatewayLease = NativeCoreSession.gatewayRequest(before)
        try {
            oldGatewayLease.ensureCurrent()
            NativeCoreSession.suspend(before.owner)
            assertNull(NativeCoreSession.providerSessionKey())
            assertNull(NativeCoreSession.status.value.providerSession)
            assertThrows(java.util.concurrent.CancellationException::class.java) { oldLease.control.ensureActive() }
            assertThrows(java.util.concurrent.CancellationException::class.java) { oldPluginLease.control.ensureActive() }
            assertThrows(java.util.concurrent.CancellationException::class.java) { oldGatewayLease.control.ensureActive() }
            NativeCoreSession.connect(before.owner, context.filesDir)
            await("same-owner real core re-authentication", 30_000) { NativeCoreSession.status.value.ready }
            val after = requireNotNull(NativeCoreSession.providerSessionKey())
            assertEquals(before.owner, after.owner)
            assertTrue("reconnection did not advance the provider epoch", after.epoch > before.epoch)
            assertEquals(after, NativeCoreSession.status.value.providerSession)
            assertNotEquals("READY snapshots coalesced across reconnect", beforeStatus, NativeCoreSession.status.value)
            assertThrows(java.util.concurrent.CancellationException::class.java) { NativeCoreSession.providerRequest(before) }
            assertThrows(java.util.concurrent.CancellationException::class.java) { oldLease.client.getProviders() }
            assertThrows(java.util.concurrent.CancellationException::class.java) { NativeCoreSession.pluginRequest(before) }
            assertThrows(java.util.concurrent.CancellationException::class.java) { oldPluginLease.client.plugins() }
            assertThrows(java.util.concurrent.CancellationException::class.java) { NativeCoreSession.gatewayRequest(before) }
            assertThrows(java.util.concurrent.CancellationException::class.java) { oldGatewayLease.ensureCurrent() }
            NativeCoreSession.connect(after.owner, context.filesDir)
            assertEquals("duplicate RUNNING changed the verified connection", after, NativeCoreSession.providerSessionKey())
            val refreshed = cache.read(after) {
                withProviderRequest(after) { it.getProviders() }.also { fetches++ }
            }
            assertFalse(refreshed.hasFileError)
            assertEquals(updatedName, refreshed.providers.single { it.id == "android-runtime" }.name)
            assertEquals(2, fetches)
            NativeCoreSession.pluginRequest(after).use { fresh -> assertTrue(fresh.client.plugins().bun) }
            NativeCoreSession.gatewayRequest(after).use { fresh ->
                fresh.ensureCurrent()
                assertTrue(ModelsClient(fresh.config, requestControl = fresh.control).list().isNotEmpty())
            }
            File(artifacts, "provider-same-owner-reconnect.txt").writeText(
                "PASS: real management edit followed by suspend/re-authentication of the same running core; " +
                    "Service owner unchanged, epoch advanced, READY snapshot changed, duplicate RUNNING stable; " +
                    "old key/client rejected and healthy old list replaced by a fresh authenticated GET\n",
            )
            File(artifacts, "plugin-same-owner-reconnect.txt").writeText(
                "PASS: same-owner real re-authentication cancelled the old plugin lease, rejected the " +
                    "old connection key/client, and allowed a fresh authenticated plugin GET\n",
            )
        } finally {
            oldLease.close()
            oldPluginLease.close()
            oldGatewayLease.close()
        }
    }

    /** Production VMs, real Room and a silent SSE through the installed Go gateway. */
    private fun verifyChatAndCatalogReconnect(upstream: LocalUpstream, artifacts: File) {
        val store = ViewModelStore()
        val application = context.applicationContext as android.app.Application
        lateinit var shell: AppShellViewModel
        lateinit var chat: ChatViewModel
        instrumentation.runOnMainSync {
            shell = AppShellViewModel(application)
            chat = ChatViewModel(application)
            store.put("reconnect-catalog", shell)
            store.put("reconnect-chat", chat)
            shell.connectionChanged()
        }
        try {
            await("production catalog and chat initialized") {
                shell.state.value.catalogValid && shell.state.value.preferences.ready &&
                    chat.state.value.ready && !chat.state.value.busy
            }
            val before = requireNotNull(shell.state.value.catalogSession)
            assertEquals(NativeCoreSession.providerSessionKey(), before)
            val model = shell.state.value.models.single {
                it.id.contains("android-runtime") && it.id.endsWith("fake-echo")
            }.id
            instrumentation.runOnMainSync { shell.selectModel(model); chat.newConversation() }
            await("isolated reconnect conversation") { !chat.state.value.busy && chat.state.value.messages.isEmpty() }
            instrumentation.runOnMainSync {
                shell.refreshModels()
                assertFalse("a stale callback must be rejected before recomposition", shell.isConfirmedModel(model, before))
            }
            await("same-key catalog refresh confirmed") {
                shell.state.value.catalogValid && shell.state.value.catalogSession == before
            }
            val conversation = requireNotNull(chat.state.value.selected).id
            val repo = ChatRepository.open(context)
            instrumentation.runOnMainSync {
                chat.editDraft(RECONNECT_PROBE)
                chat.send(model, expectedSession = before)
            }
            assertTrue("real chat never reached the controlled upstream", upstream.chatGateReached.await(15, TimeUnit.SECONDS))
            await("real SSE preview before reauthentication") { chat.state.value.partial.isNotEmpty() }
            assertTrue(chat.state.value.sending)

            NativeCoreSession.suspend(before.owner)
            NativeCoreSession.connect(before.owner, context.filesDir)
            await("same-owner reauthentication during real chat") { NativeCoreSession.status.value.ready }
            val after = requireNotNull(NativeCoreSession.providerSessionKey())
            assertEquals(before.owner, after.owner)
            assertTrue(after.epoch > before.epoch)
            assertTrue("upstream SSE socket was not actually closed", upstream.chatPeerClosed.await(10, TimeUnit.SECONDS))
            await("cancelled chat worker and Room cleanup") { !chat.state.value.sending }
            assertEquals(RECONNECT_PROBE, chat.state.value.draft)
            assertTrue(chat.state.value.notice.contains("草稿已保留"))
            val failed = repo.messages(conversation)
            assertEquals(listOf("user", "error"), failed.map { it.role })
            assertTrue(failed.all { it.state == "FAILED" })
            assertNull(repo.database.chatDao().runningOperation(conversation))
            assertTrue(repo.context(conversation).isEmpty())
            assertEquals(1, upstream.requests.count { it.contains(RECONNECT_PROBE) })

            // Deliberately do not notify this shell: simulate a click before its Compose effect.
            assertEquals(before, shell.state.value.catalogSession)
            val retry = "android-reconnect-explicit-retry"
            instrumentation.runOnMainSync {
                chat.editDraft(retry)
                chat.send(model, expectedSession = shell.state.value.catalogSession)
            }
            assertFalse("stale catalog click started a new worker", chat.state.value.sending)
            assertEquals(retry, chat.state.value.draft)
            assertEquals(failed, repo.messages(conversation))
            assertEquals(0, upstream.requests.count { it.contains(retry) })
            instrumentation.runOnMainSync { shell.connectionChanged() }
            await("fresh real catalog after same-owner epoch change") {
                shell.state.value.catalogValid && shell.state.value.catalogSession == after
            }
            assertEquals(model, shell.state.value.selectedModel)
            instrumentation.runOnMainSync {
                assertFalse("old model sheet callback must not select in a new epoch", shell.isConfirmedModel(model, before))
                assertTrue(shell.isConfirmedModel(model, after))
            }
            assertEquals(0, upstream.requests.count { it.contains(retry) })
            instrumentation.runOnMainSync { chat.send(model, expectedSession = shell.state.value.catalogSession) }
            await("explicit retry saved exactly once") { !chat.state.value.sending && chat.state.value.draft.isEmpty() }
            assertEquals(1, upstream.requests.count { it.contains(retry) })
            assertEquals(listOf("user", "error", "user", "assistant"), repo.messages(conversation).map { it.role })
            assertEquals(listOf("user", "assistant"), repo.context(conversation).map { it.role })
            File(artifacts, "chat-catalog-same-owner-reconnect.txt").writeText(
                "PASS: production Chat/Catalog VMs used the installed Go gateway and real Room; " +
                    "same-owner reauthentication closed the held SSE peer, failed the old turn, " +
                    "kept the live draft and excluded partial output from context; an old catalog click " +
                    "created no worker, HTTP or Room write; a fresh authenticated catalog kept the " +
                    "selected model, and only one explicit new click committed the retry\n",
            )
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun dismissEmulatorLauncherAnr() {
        // API35's Pixel Launcher can ANR during first boot; leave any app ANR as a failure.
        if (device.hasObject(By.text("Pixel Launcher isn't responding"))) {
            device.findObject(By.text("Close app"))?.click()
            device.waitForIdle()
        }
    }

    private fun uiBounds(resourceId: String): Rect? {
        val root = runCatching { instrumentation.uiAutomation.rootInActiveWindow }.getOrNull() ?: return null
        return try {
            val node = findVisibleAccessibilityNode(root, resourceId) ?: return null
            try {
                node.screenBounds()
            } finally {
                node.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun chatPanelBounds(): Rect? = uiBounds("chat-panel")

    private fun findVisibleAccessibilityNode(root: AccessibilityNodeInfo, resourceId: String): AccessibilityNodeInfo? {
        val display = Rect(0, 0, device.displayWidth, device.displayHeight)
        if (root.viewIdResourceName == resourceId) {
            val bounds = root.screenBounds()
            if (root.isVisibleToUser && !bounds.isEmpty && Rect.intersects(bounds, display)) {
                return AccessibilityNodeInfo.obtain(root)
            }
        }
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            try {
                findVisibleAccessibilityNode(child, resourceId)?.let { return it }
            } finally {
                child.recycle()
            }
        }
        return null
    }

    private fun AccessibilityNodeInfo.screenBounds(): Rect =
        Rect().also { getBoundsInScreen(it) }

    private fun fixture(root: File, name: String, code: String, manifest: String, entry: String = "index.mjs"): File = File(root, name).apply {
        mkdirs()
        File(this, "package.json").writeText("{\"name\":\"android-test-$name\",\"version\":\"1.0.0\",\"type\":\"module\",$manifest}")
        File(this, entry).writeText(code)
    }

    private fun awaitStableModelOption(
        modelId: String,
        description: String,
        timeout: Long = 15_000,
    ): UiObject2 {
        val selector = By.res("model-option:$modelId").clickable(true)
        val deadline = SystemClock.elapsedRealtime() + timeout
        var previousBounds: Rect? = null
        var stablePolls = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val option = device.findObject(selector)
            val bounds = option?.visibleBounds
            val onScreen = bounds != null && !bounds.isEmpty &&
                bounds.right > 0 && bounds.left < device.displayWidth &&
                bounds.bottom > 0 && bounds.top < device.displayHeight
            if (option != null && onScreen) {
                if (bounds == previousBounds) stablePolls++ else {
                    previousBounds = bounds
                    stablePolls = 0
                }
                if (stablePolls >= 3) return option
            } else {
                previousBounds = null
                stablePolls = 0
            }
            SystemClock.sleep(200)
        }
        throw AssertionError("Timed out: $description (stable clickable model option $modelId)")
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

    private data class ChatImeFrame(
        val panel: Rect?,
        val selector: Rect?,
        val send: Rect?,
        val displayWidth: Int,
        val displayHeight: Int,
        val density: Float,
        val densityDpi: Int,
        val scaledDensity: Float,
        val imeVisible: Boolean,
        val ime: Rect,
        val systemBars: Rect,
    ) {
        val imeTop: Int get() = displayHeight - ime.bottom

        fun isStableCandidate(): Boolean =
            panel != null && selector != null && send != null &&
                displayWidth > 0 && displayHeight > 0 && density > 0f &&
                imeVisible && ime.bottom > 0

        fun sameStableGeometry(other: ChatImeFrame): Boolean =
            panel == other.panel &&
                selector == other.selector &&
                send == other.send &&
                displayWidth == other.displayWidth &&
                displayHeight == other.displayHeight &&
                density == other.density &&
                densityDpi == other.densityDpi &&
                scaledDensity == other.scaledDensity &&
                imeVisible == other.imeVisible &&
                ime == other.ime &&
                systemBars == other.systemBars

        fun toPollEvidence(poll: Int, elapsedMs: Long, stablePolls: Int): String =
            "poll=$poll elapsedMs=$elapsedMs stablePolls=$stablePolls ${toEvidenceValue()}"

        fun toEvidenceValue(): String =
            "display=${displayWidth}x$displayHeight density=$density densityDpi=$densityDpi " +
                "scaledDensity=$scaledDensity imeVisible=$imeVisible imeInsets=$ime " +
                "systemBarsInsets=$systemBars imeTop=$imeTop panel=$panel selector=$selector send=$send"
    }

    private fun Rect.isFullyWithin(displayWidth: Int, displayHeight: Int): Boolean =
        left >= 0 && top >= 0 && right <= displayWidth && bottom <= displayHeight

    /** Use the real official editor API: the native empty-selection save intentionally means none. */
    private fun saveOfficialProviderModels(cookie: String, noModels: Boolean) {
        val saved = NativeCoreSession.providerClient().editable("android-runtime")
        val body = JSONObject().put("id", saved.id).put("name", saved.name)
            .put("chat", saved.chatUrl).put("models", JSONArray()).put("noModels", noModels)
        assertEquals("official editor save", 200,
            http("http://127.0.0.1:3430/api/provider/save", "", body.toString(), cookie).first)
        val actual = NativeCoreSession.providerClient().editable(saved.id)
        assertTrue(actual.modelIds.isEmpty())
        assertEquals(noModels, actual.noModels)
        assertTrue("official empty-key save must retain the existing credential", actual.keySet)
    }

    private fun prepareImplicitProviderModels(upstream: LocalUpstream, cookie: String) {
        saveOfficialProviderModels(cookie, noModels = false)
        val requestsBefore = upstream.modelRequests.get()
        // Same-key official saves do not refetch. Persist a real vendor catalog without picks.
        assertEquals("official model catalog refetch", 200,
            http("http://127.0.0.1:3430/api/provider/models", "",
                JSONObject().put("id", "android-runtime").toString(), cookie).first)
        assertTrue("official refetch must reach the actual upstream", upstream.modelRequests.get() > requestsBefore)
        val client = NativeCoreSession.providerClient()
        val editable = client.editable("android-runtime")
        assertTrue(editable.modelIds.isEmpty())
        assertFalse(editable.noModels)
        val listed = client.getProviders().providers.single { it.id == editable.id }
        assertTrue(listed.chosen.isEmpty())
        assertTrue(listed.ready && !listed.off && !listed.noModels)
        assertEquals(listOf("fake-echo"), listed.models.filter { it.on }.map { it.id })
    }

    /** Wait for a visibly fresh Panel summary; opening only a fresh editable snapshot is insufficient. */
    private fun refreshProviderRow(expectedTag: String) {
        // TextButton exposes its label on a child text node; that child is not clickable.
        val refresh = By.text("刷新").enabled(true)
        await("provider refresh visible") {
            if (device.hasObject(refresh)) true else {
                device.swipe(device.displayWidth / 2, device.displayHeight / 3,
                    device.displayWidth / 2, device.displayHeight * 3 / 4, 20)
                false
            }
        }
        device.findObject(refresh).click()
        await("provider list refreshed to $expectedTag") {
            val row = device.findObject(By.res("provider-row:android-runtime"))
            if (row?.isEnabled == true && row.findObject(By.text(expectedTag)) != null) true else {
                device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                    device.displayWidth / 2, device.displayHeight / 3, 20)
                false
            }
        }
    }

    private fun runtimeProviderRoutePresent(auth: String): Boolean {
        val response = http("http://127.0.0.1:3425/v1/models", auth)
        assertEquals(200, response.first)
        val models = JSONObject(response.second).getJSONArray("data")
        return (0 until models.length()).any {
            val model = models.getJSONObject(it)
            model.getString("id") == "android-runtime/fake-echo" &&
                model.getString("owned_by") == "android-runtime"
        }
    }

    private fun verifyDisabledProviderModels(artifacts: File, cookie: String, auth: String) {
        // This separate Dialog window exposes visible text to UiAutomator. Its Compose-only
        // test tag is used by Panel tests and is not inherited as an Android resource ID.
        val dialog = By.text("测试连接")
        val editorBack = By.desc("返回提供商列表")
        var primaryFailure: Throwable? = null
        try {
            saveOfficialProviderModels(cookie, noModels = true)
            val listed = NativeCoreSession.providerClient().getProviders().providers.single { it.id == "android-runtime" }
            assertTrue(listed.ready && !listed.off && listed.noModels && listed.chosen.isEmpty())
            assertEquals(listOf("fake-echo"), listed.models.map { it.id })
            assertTrue(listed.models.none { it.on })
            assertFalse("select-none must remove the actual gateway route", runtimeProviderRoutePresent(auth))
            refreshProviderRow("模型已禁用")
            device.findObject(By.res("provider-row:android-runtime")).click()
            await("disabled provider connectivity entry") { device.hasObject(By.res("provider-connectivity-entry")) }
            device.findObject(By.res("provider-connectivity-entry")).click()
            await("disabled provider test dialog") { device.hasObject(dialog) }
            assertTrue(device.hasObject(By.text("当前提供商没有已保存且启用的模型；保存模型选择后再测试。")))
            val start = By.res("provider-connectivity-start")
            await("disabled provider test action accessible") { device.hasObject(start) }
            assertFalse("select-none must keep Start disabled", device.findObject(start).isEnabled)
            assertTrue(device.takeScreenshot(File(artifacts, "provider-connectivity-disabled.png")))
            File(artifacts, "provider-auto-models.txt").writeText(
                "PASS: official save and real vendor Refetch persisted models=[]/noModels=false; " +
                    "fresh native listing chosen=[] exposed fake-echo; native model selection and all three " +
                    "real Go capability checks passed. Official select-none retained the catalog with on=false, " +
                    "removed its actual gateway route, and disabled Start in a freshly opened native dialog.\n",
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            runCatching { device.takeScreenshot(File(artifacts, "provider-auto-models-failure.png")) }
            throw failure
        } finally {
            try {
                if (device.hasObject(dialog)) device.findObject(By.text("关闭"))?.click()
                await("disabled provider test dialog closed") { !device.hasObject(dialog) }
                if (device.hasObject(editorBack)) device.findObject(editorBack).click()
                await("disabled provider editor closed") { !device.hasObject(By.res("provider-editor-surface")) }
                val client = NativeCoreSession.providerClient()
                val draft = ProviderEditDraft(client.editable("android-runtime"), modelIds = listOf("fake-echo"))
                try { client.edit(draft) } finally { draft.clearSecret() }
                assertEquals(listOf("fake-echo"), client.editable("android-runtime").modelIds)
                assertFalse(client.editable("android-runtime").noModels)
                assertTrue("restoring explicit picks must restore the gateway route", runtimeProviderRoutePresent(auth))
                refreshProviderRow("已启用")
            } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original == null) throw cleanupFailure
                original.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun verifyModelDiscoveryPinContract(upstream: LocalUpstream, artifacts: File, cookie: String) {
        val client = NativeCoreSession.providerClient()
        val snapshot = client.editable("android-runtime")
        val catalog = client.discoverModels(ProviderModelDiscoveryRequest(
            upstream.base + "/v1", providerId = snapshot.id, revision = snapshot.revision,
            allowLoopbackHttp = true, serverPin = "",
        ))
        assertEquals(listOf("fake-echo"), catalog.models.map { it.id })
        val before = upstream.modelRequests.get()
        for (pin in listOf("not-a-pin", "a".repeat(64))) {
            // Exercise the APK's real Go strict decoder/validation too: the Kotlin DTO
            // deliberately refuses HTTP+pin before it can emit this unsafe request.
            val body = JSONObject().put("chat", upstream.base + "/v1")
                .put("id", snapshot.id).put("revision", snapshot.revision).put("serverPin", pin)
            assertEquals(400, http("http://127.0.0.1:3430/api/mobile/providers/models", "", body.toString(), cookie).first)
        }
        assertEquals("invalid pin / HTTP+pin reached the actual upstream", before, upstream.modelRequests.get())
        assertEquals("read-only pin validation changed provider revision", snapshot.revision,
            client.editable("android-runtime").revision)
        File(artifacts, "provider-discovery-pin-contract.txt").writeText(
            "realInstalledGoRPC=true\nexplicitEmptyPinCatalog=true\n" +
                "malformedPinRejected=true\nhttpWithPinRejected=true\n" +
                "invalidRequestsReachedUpstream=0\nproviderRevisionUnchanged=true\n" +
                "TLSHandshakeMatrix=hostValidationOnly\n",
        )
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
        val modelRequests = AtomicInteger()
        val signInGateReached = CountDownLatch(1)
        val releaseSignInGate = CountDownLatch(1)
        val chatGateReached = CountDownLatch(1)
        val chatPeerClosed = CountDownLatch(1)
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
            if (first.contains("/plugin-signin-delay")) {
                signInGateReached.countDown()
                check(releaseSignInGate.await(30, TimeUnit.SECONDS))
                json("{}")
                return
            }
            if (first.startsWith("GET")) {
                if (first.startsWith("GET /models ") || first.startsWith("GET /v1/models ")) modelRequests.incrementAndGet()
                json("{\"data\":[{\"id\":\"fake-echo\"}]}")
                return
            }
            val body = JSONObject(raw)
            if (body.has("tools")) {
                json(JSONObject().put("id", "tool-probe").put("model", "fake-echo")
                    .put("choices", JSONArray().put(JSONObject().put("index", 0)
                        .put("finish_reason", "tool_calls").put("message", JSONObject()
                            .put("role", "assistant").put("content", JSONObject.NULL)
                            .put("tool_calls", JSONArray().put(JSONObject()
                                .put("id", "probe-time").put("type", "function")
                                .put("function", JSONObject().put("name", "get_current_time")
                                    .put("arguments", "{}"))))))).toString())
                return
            }
            val messages = body.getJSONArray("messages")
            val text = messages.getJSONObject(messages.length() - 1).getString("content")
            if (text.startsWith(RECONNECT_PROBE)) {
                output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                val event = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0)
                    .put("delta", JSONObject().put("content", "controlled partial"))))
                output.write("data: $event\n\n".toByteArray())
                output.flush()
                chatGateReached.countDown()
                // A read timeout is a failure: only EOF/reset proves the actual peer closed.
                try {
                    if (input.read() == -1) chatPeerClosed.countDown()
                } catch (_: java.net.SocketException) { chatPeerClosed.countDown() }
                return
            }
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
            releaseSignInGate.countDown()
            server.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    companion object {
        private const val RECONNECT_PROBE = "android-reconnect-silent-probe"
        private const val LIVE_DIAGNOSTIC_PROBE = "android-live-diagnostic-probe"
        private const val LIVE_DIAGNOSTIC_MARKER = "magpie-live-diagnostic-marker"
        private const val LIVE_DIAGNOSTIC_SECRET = "synthetic-live-credential-4837"
        private val PROVIDER = """
            import { writeFileSync } from "node:fs"
            export const AndroidFixture = async (_, options = {}) => ({
              config: async (cfg) => {
                cfg.provider ??= {}
                cfg.provider.androidfixture = {name:"Android fixture",npm:"@ai-sdk/openai-compatible",api:options.base || "http://127.0.0.1:9/v1",models:{"fake-echo":{name:"Fake Echo",limit:{context:4096,output:512}}}}
              },
              auth: { provider:"androidfixture",methods:[{type:"api",label:"Test API key"},
                {type:"oauth",label:"Test OAuth",authorize:async()=>({url:"https://example.invalid/authorize",method:"code",instructions:"Paste good",
                  callback:async(code)=>code==="good"?({type:"success",refresh:"synthetic-refresh",access:"synthetic-access",expires:Date.now()+3600000}):({type:"failed"})})},
                {type:"oauth",label:"Test delayed OAuth",authorize:async()=>{await fetch(options.base + "/plugin-signin-delay");return {
                  url:"https://example.invalid/delayed-authorize",method:"code",instructions:"Pending cancel fixture",
                  callback:async()=>({type:"failed"})}}}],
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
              if (text.startsWith("$LIVE_DIAGNOSTIC_PROBE")) console.log("$LIVE_DIAGNOSTIC_MARKER token=$LIVE_DIAGNOSTIC_SECRET")
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

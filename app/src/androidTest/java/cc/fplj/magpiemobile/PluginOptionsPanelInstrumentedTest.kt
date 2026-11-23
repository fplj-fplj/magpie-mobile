package cc.fplj.magpiemobile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import cc.fplj.magpiemobile.core.ManagerCredential
import cc.fplj.magpiemobile.theme.MagpieTheme
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Full native Panel and real VM/lease/HTTP client; no test-supplied editor or save result. */
@RunWith(AndroidJUnit4::class)
class PluginOptionsPanelInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sameDetailsCanSaveAtoBtoCWithoutTreatingItsOwnFirstSaveAsAnExternalEdit() {
        PluginOptionsHttpPeer().use { peer ->
            val first = peer.holdNextWrite(200)
            val second = peer.holdNextWrite(200)
            withPanel(peer) { vm ->
                openOptions()
                replaceOptions(B)
                save()
                awaitRequest(first, peer, 1)
                compose.onNodeWithTag(EDITOR).assertIsNotEnabled()
                first.release.countDown()
                awaitReady(vm, B)
                assertOptions(B)

                // Keep this exact dialog mounted. Reopening would hide the old baseline bug.
                replaceOptions(C)
                save()
                awaitRequest(second, peer, 2)
                second.release.countDown()
                awaitReady(vm, C)
                assertOptions(C)
                compose.onNodeWithText(CONFLICT).assertDoesNotExist()
                assertWrites(peer, B, C)
                evidence("plugin-options-consecutive", "PASS: same native detail saved A -> B -> C; two authenticated POSTs; draft C retained\n")
            }
        }
    }

    @Test fun actualExternalRefreshStillBlocksAnUnsavedDraftAfterAnEarlierNativeSave() {
        PluginOptionsHttpPeer().use { peer ->
            val first = peer.holdNextWrite(200)
            withPanel(peer) { vm ->
                openOptions()
                replaceOptions(B)
                save()
                awaitRequest(first, peer, 1)
                first.release.countDown()
                awaitReady(vm, B)
                replaceOptions(C)

                peer.externalOptions(EXTERNAL)
                compose.runOnUiThread { vm.refresh() }
                compose.waitUntil(10_000) {
                    !vm.state.value.loading && vm.state.value.snapshot.entries.singleOrNull()?.options?.toString() == EXTERNAL
                }
                compose.waitForIdle()
                assertOptions(C)
                save()
                compose.onNodeWithText(CONFLICT).performScrollTo().assertIsDisplayed()
                assertOptions(C)
                assertEquals("External conflict must not send another options POST", 1, peer.writes.size)
                assertEquals(EXTERNAL, peer.options())
                compose.onNodeWithText(CONFLICT).performScrollTo().assertIsDisplayed()
                evidence("plugin-options-external-conflict", "PASS: after native B, external D was read; unsaved C retained; conflict blocked a second POST\n")
            }
        }
    }

    @Test fun knownFailureKeepsDraftAndBaselineForAnExplicitSecondSave() {
        PluginOptionsHttpPeer().use { peer ->
            val rejected = peer.holdNextWrite(400)
            val accepted = peer.holdNextWrite(200)
            withPanel(peer) { vm ->
                openOptions()
                replaceOptions(B)
                save()
                awaitRequest(rejected, peer, 1)
                rejected.release.countDown()
                awaitReady(vm, A)
                assertTrue("The actual HTTP 400 must remain a definite rejection", vm.state.value.error.contains("HTTP 400"))
                assertEquals(A, peer.options())
                peer.assertHealthy()
                assertOptions(B)
                assertEquals(1, peer.writes.size)

                replaceOptions(C)
                save()
                awaitRequest(accepted, peer, 2)
                accepted.release.countDown()
                awaitReady(vm, C)
                assertOptions(C)
                compose.onNodeWithText(CONFLICT).assertDoesNotExist()
                assertWrites(peer, B, C)
            }
        }
    }

    private fun withPanel(peer: PluginOptionsHttpPeer, block: (PluginManagementViewModel) -> Unit) {
        val leases = PluginRequestLeases()
        val controller = PluginOperationController(acquire = { leases.acquire(peer.credential) { true } })
        val visible = mutableStateOf(true)
        lateinit var vm: PluginManagementViewModel
        compose.runOnUiThread {
            vm = PluginManagementViewModel(capture = controller::capture)
            vm.attach(SESSION)
        }
        compose.setContent {
            val state by vm.state.collectAsState()
            MagpieTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) {
                    if (visible.value) Column(Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState()).padding(12.dp)) {
                        PluginManagementPanel(vm, state, enabled = true, onBrowserEditor = {})
                    }
                }
            }
        }
        try {
            awaitReady(vm, A)
            block(vm)
            peer.assertHealthy()
        } finally {
            compose.runOnUiThread {
                visible.value = false
                vm.viewModelScope.cancel()
                leases.invalidate()
            }
            compose.waitForIdle()
        }
    }

    private fun openOptions() {
        compose.onNodeWithTag(officialPluginRowTag(SPEC)).performScrollTo().performClick()
        compose.onNodeWithText("参数").performClick()
        compose.onNodeWithTag(EDITOR).performScrollTo().assertIsEnabled()
    }

    private fun replaceOptions(value: String) {
        compose.onNodeWithTag(EDITOR).performScrollTo().performTextReplacement(value)
    }

    private fun save() {
        compose.onNodeWithText("保存参数").performScrollTo().assertIsEnabled().performClick()
    }

    private fun assertOptions(value: String) {
        compose.onNodeWithTag(EDITOR).performScrollTo().assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString(value),
        ))
    }

    private fun awaitRequest(gate: PluginOptionsHttpPeer.Gate, peer: PluginOptionsHttpPeer, count: Int) {
        compose.waitUntil(10_000) { gate.entered.count == 0L }
        assertEquals(count, peer.writes.size)
    }

    private fun awaitReady(vm: PluginManagementViewModel, expected: String) {
        try {
            compose.waitUntil(10_000) {
                val state = vm.state.value
                !state.busy && !state.loading && !state.pending &&
                    state.snapshot.entries.singleOrNull()?.options?.toString() == expected
            }
        } catch (timeout: ComposeTimeoutException) {
            val state = vm.state.value
            throw AssertionError("Options state did not settle: busy=${state.busy}, loading=${state.loading}, " +
                "pending=${state.pending}, error=${state.error}, " +
                "options=${state.snapshot.entries.singleOrNull()?.options}, expected=$expected", timeout)
        }
        compose.waitForIdle()
    }

    private fun assertWrites(peer: PluginOptionsHttpPeer, vararg expected: String) {
        assertEquals(expected.toList(), peer.writes.map { JSONObject(it).getJSONObject("options").toString() })
        assertTrue(peer.writes.all { JSONObject(it).getString("spec") == SPEC })
    }

    private fun evidence(name: String, text: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "plugin-options-editor").apply { mkdirs() }
        val device = UiDevice.getInstance(instrumentation)
        val image = File(directory, "$name.png")
        val note = File(directory, "$name.txt").apply { writeText(text) }
        assertTrue(device.takeScreenshot(image))
        device.executeShellCommand("mkdir -p /data/local/tmp/magpie-plugin-runtime")
        for (file in listOf(image, note)) {
            val exported = "/data/local/tmp/magpie-plugin-runtime/${file.name}"
            device.executeShellCommand("cp ${file.absolutePath} $exported")
            assertEquals(exported, device.executeShellCommand("ls $exported").trim())
        }
    }

    private companion object {
        const val SPEC = "@fixture/options-editor"
        const val EDITOR = "plugin-options-json"
        const val CONFLICT = "参数已被其他入口修改，请关闭详情后重新打开"
        const val A = "{\"value\":\"a\"}"
        const val B = "{\"value\":\"b\"}"
        const val C = "{\"value\":\"c\"}"
        const val EXTERNAL = "{\"value\":\"external\"}"
        val SESSION = ProviderSessionKey(61, 1)
    }
}

/** Synthetic loopback management responses, with held actual options HTTP requests. */
private class PluginOptionsHttpPeer : Closeable {
    class Gate(val status: Int) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "plugin-options-http-fixture").apply { isDaemon = true } }
    private val closing = AtomicBoolean()
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val failure = AtomicReference<Throwable?>()
    private val gates = ConcurrentLinkedQueue<Gate>()
    private val allGates = CopyOnWriteArrayList<Gate>()
    private val canonical = AtomicReference("{\"value\":\"a\"}")
    val writes = CopyOnWriteArrayList<String>()
    val credential = ManagerCredential.fromKey("plugin-options-synthetic-manager", listener.localPort)

    init {
        workers.execute {
            try {
                while (!closing.get()) {
                    val socket = listener.accept()
                    sockets.add(socket)
                    workers.execute {
                        socket.use {
                            try { serve(it) }
                            catch (error: Throwable) { if (!closing.get()) failure.compareAndSet(null, error) }
                            finally { sockets.remove(it) }
                        }
                    }
                }
            } catch (error: Throwable) { if (!closing.get()) failure.compareAndSet(null, error) }
        }
    }

    fun holdNextWrite(status: Int) = Gate(status).also {
        require(status in listOf(200, 400))
        gates.add(it)
        allGates.add(it)
    }

    fun externalOptions(value: String) { canonical.set(JSONObject(value).toString()) }
    fun options(): String = canonical.get()
    fun assertHealthy() { assertNull("Unexpected management request or fixture failure", failure.get()) }

    private fun serve(socket: Socket) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        fun line(): String {
            val bytes = ArrayList<Byte>()
            while (bytes.size < 16_384) {
                val value = input.read()
                check(value >= 0)
                if (value == 10) return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
                bytes.add(value.toByte())
            }
            error("Fixture request header exceeded limit")
        }
        val request = line().split(' ')
        check(request.size == 3)
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            check(headers.size < 40)
            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
        }
        check(headers["x-magpie-android-manager"] == credential.managerHeaderValue() && headers["cookie"] == null)
        check(headers["authorization"] == null && headers["origin"] == null && headers["transfer-encoding"] == null)
        val length = headers["content-length"]?.toInt() ?: 0
        check(length in 0..524_288)
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            check(count > 0)
            offset += count
        }
        when (request[0] + " " + request[1]) {
            "GET /api/plugins" -> respond(socket, 200, snapshot())
            "GET /api/providers" -> respond(socket, 200, "{\"plugins\":[]}")
            "POST /api/plugins/options" -> {
                val body = bytes.toString(Charsets.UTF_8)
                val parsed = JSONObject(body)
                check(parsed.getString("spec") == "@fixture/options-editor")
                writes.add(body)
                val gate = checkNotNull(gates.poll()) { "Options POST had no explicit test action" }
                gate.entered.countDown()
                check(gate.release.await(30, TimeUnit.SECONDS))
                if (gate.status == 200) canonical.set(parsed.getJSONObject("options").toString())
                respond(socket, gate.status, if (gate.status == 200) snapshot() else "{}")
            }
            else -> error("Unexpected fixture route")
        }
    }

    private fun snapshot(): String = JSONObject().put("bun", true).put("bunVersion", "fixture")
        .put("plugins", JSONArray().put(JSONObject().put("spec", "@fixture/options-editor")
            .put("ownName", "Options editor fixture").put("version", "1.0.0")
            .put("options", JSONObject(canonical.get())))).toString()

    private fun respond(socket: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = if (status == 200) "OK" else "Bad Request"
        val header = "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII) + bytes); flush() }
    }

    override fun close() {
        closing.set(true)
        allGates.forEach { it.release.countDown() }
        listener.close()
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        workers.awaitTermination(5, TimeUnit.SECONDS)
    }
}

package cc.fplj.magpiemobile

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.fplj.magpiemobile.core.CallerKeyAction
import cc.fplj.magpiemobile.core.CallerKeyChange
import cc.fplj.magpiemobile.core.RegisteredGatewayKey
import cc.fplj.magpiemobile.theme.MagpieTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GatewayKeysInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun gatewayEndpointRejectsOldCallbackAndMarksCurrentKeyAndCurlSensitive() {
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        var liveSession = FIRST
        val oldMaskedKey = "sk-synthetic-old-mask"
        val currentKey = "sk-synthetic-current-key"
        val copyForLiveSession: (ProviderSessionKey, (String) -> Unit) -> Boolean = { expected, copy ->
            if (expected != liveSession) false else { copy(currentKey); true }
        }

        compose.setContent {
            MagpieTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) {
                    GatewayEndpointCard(
                        running = true,
                        clientReady = true,
                        maskedGatewayKey = "sk-synthetic-old-mask",
                        defaultModel = "fake-echo",
                        expectedSession = FIRST,
                        copyGatewayKeyIfCurrent = copyForLiveSession,
                    )
                }
            }
        }
        compose.waitForIdle()
        val oldCopy = checkNotNull(compose.onNodeWithText("复制 API Key").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action)
        compose.runOnUiThread {
            clipboard.setPrimaryClip(ClipData.newPlainText("test sentinel", "sentinel-value"))
            liveSession = NEXT
            assertTrue("The previously displayed control should remain invokable for this race test", oldCopy())
            assertEquals("sentinel-value", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }

        compose.setContent {
            MagpieTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) {
                    GatewayEndpointCard(
                        running = true,
                        clientReady = true,
                        maskedGatewayKey = "sk-synthetic-old-mask",
                        defaultModel = "fake-echo",
                        expectedSession = NEXT,
                        copyGatewayKeyIfCurrent = copyForLiveSession,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("复制 API Key").performClick()
        compose.runOnUiThread { assertSensitiveClipboard(clipboard, currentKey) }

        compose.onNodeWithText("复制测试 cURL").performClick()
        var curl = ""
        compose.runOnUiThread {
            curl = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        }
        assertTrue(curl.contains("Bearer $currentKey"))
        assertFalse(curl.contains(oldMaskedKey))
        compose.runOnUiThread { assertSensitiveClipboard(clipboard, curl) }

        compose.onNodeWithText("复制 Base URL").performClick()
        var baseUrl = ""
        compose.runOnUiThread {
            baseUrl = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        }
        assertEquals("http://127.0.0.1:3425/v1", baseUrl)
        compose.runOnUiThread {
            assertFalse(clipboard.primaryClipDescription?.extras
                ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true)
        }
    }

    @Test fun reconnectDismissesRestrictionDraftAndRejectsItsPreviouslyCapturedSave() {
        withPanel { source ->
            compose.onNodeWithText("限制").performScrollTo().performClick()
            compose.onNodeWithText("编辑密钥限制 · NextChat").assertIsDisplayed()
            val oldSave = checkNotNull(compose.onNodeWithText("保存").fetchSemanticsNode()
                .config[SemanticsActions.OnClick].action)
            compose.runOnUiThread {
                source.session = NEXT
                source.vm.attach(NEXT)
                assertEquals(NEXT, source.vm.state.value.session)
                assertFalse("The session guard must be tested after the new read finishes", source.vm.state.value.loading)
                assertTrue(oldSave())
            }
            compose.waitForIdle()
            compose.onNodeWithText("编辑密钥限制 · NextChat").assertDoesNotExist()
            assertFalse(source.calls.any { it.startsWith("update") })
            compose.onNodeWithText("限制").performScrollTo().performClick()
            compose.onNodeWithText("保存").performClick()
            compose.waitForIdle()
            assertEquals(listOf("update:${NEXT.epoch}:TIER"), source.calls.filter { it.startsWith("update") })
        }
    }

    @Test fun disconnectClearsCreatedSecretAndNameDraftBeforeReconnection() {
        withPanel { source ->
            compose.onNodeWithText("客户端名称").performScrollTo().performTextReplacement("Personal client")
            compose.onNodeWithText("生成 Key").performScrollTo().performClick()
            compose.onNodeWithText("新密钥生成成功！").performScrollTo().assertIsDisplayed()
            assertTrue(compose.onAllNodes(hasText(SECRET, substring = true)).fetchSemanticsNodes().isNotEmpty())
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            compose.runOnUiThread {
                assertSensitiveClipboard(clipboard, SECRET)
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            compose.onNodeWithText("客户端名称").performScrollTo().performTextReplacement("Unsaved name")
            compose.runOnUiThread {
                source.ready.value = false
                source.session = null
                source.vm.attach(null)
            }
            compose.waitForIdle()
            compose.onNodeWithText("新密钥生成成功！").assertDoesNotExist()
            assertTrue(compose.onAllNodes(hasText(SECRET, substring = true)).fetchSemanticsNodes().isEmpty())
            compose.runOnUiThread {
                source.session = NEXT
                source.vm.attach(NEXT)
                source.ready.value = true
            }
            compose.waitForIdle()
            compose.onNodeWithText("Unsaved name").assertDoesNotExist()
            assertTrue(compose.onAllNodes(hasText(SECRET, substring = true)).fetchSemanticsNodes().isEmpty())
            assertEquals(1, source.calls.count { it.startsWith("create") })
        }
    }

    @Test fun copyExistingCallerKeyMarksTheExactSecretSensitive() {
        withPanel { source ->
            compose.onNodeWithText("复制 Key").performScrollTo().performClick()
            compose.waitForIdle()
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            compose.runOnUiThread {
                assertSensitiveClipboard(clipboard, SECRET)
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }

    private fun assertSensitiveClipboard(clipboard: android.content.ClipboardManager, expectedText: String) {
        val clip = clipboard.primaryClip
        assertTrue("clipboard content mismatch", expectedText == clip?.getItemAt(0)?.text?.toString())
        assertTrue(clip?.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true)
    }

    private fun withPanel(block: (Source) -> Unit) {
        val source = Source()
        val store = ViewModelStore()
        compose.runOnUiThread {
            store.put("keys", source.vm)
            source.vm.attach(FIRST)
        }
        compose.setContent {
            val state by source.vm.state.collectAsStateWithLifecycle()
            MagpieTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        GatewayKeysCard(source.ready.value, source.vm, state)
                    }
                }
            }
        }
        try {
            compose.waitForIdle()
            block(source)
        } finally { compose.runOnUiThread { store.clear() } }
    }

    private class Source {
        var session: ProviderSessionKey? = FIRST
        val ready = mutableStateOf(true)
        val calls = CopyOnWriteArrayList<String>()
        val vm = GatewayKeysViewModel(sessionReader = { session }, capture = { captured ->
            object : CallerKeySessionOperation {
                var closed = false
                var cancelled = false
                override fun ensureCurrent() {
                    if (closed || cancelled || captured != session) throw CancellationException("Fixture session changed")
                }
                override fun cancel() { cancelled = true }
                override fun close() { closed = true }
                override suspend fun keys(): List<RegisteredGatewayKey> = listOf(KEY.copy(secret = ""))
                override suspend fun create(name: String): RegisteredGatewayKey {
                    calls += "create:${captured.epoch}:$name"
                    return KEY.copy(name = name)
                }
                override suspend fun copy(id: String): RegisteredGatewayKey = KEY
                override suspend fun update(action: CallerKeyAction, change: CallerKeyChange): List<RegisteredGatewayKey> {
                    calls += "update:${captured.epoch}:$action"
                    return listOf(KEY.copy(secret = ""))
                }
            }
        })
    }

    private companion object {
        val FIRST = ProviderSessionKey(71, 1)
        val NEXT = ProviderSessionKey(71, 2)
        const val SECRET = "sk-synthetic-android-test-only"
        val KEY = RegisteredGatewayKey("synthetic-id", "NextChat", SECRET)
    }
}

package cc.fplj.magpiemobile

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
            compose.onNodeWithText("密钥（已复制）：$SECRET").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("客户端名称").performScrollTo().performTextReplacement("Unsaved name")
            compose.runOnUiThread {
                source.ready.value = false
                source.session = null
                source.vm.attach(null)
            }
            compose.waitForIdle()
            compose.onNodeWithText("新密钥生成成功！").assertDoesNotExist()
            compose.onNodeWithText("密钥（已复制）：$SECRET").assertDoesNotExist()
            compose.runOnUiThread {
                source.session = NEXT
                source.vm.attach(NEXT)
                source.ready.value = true
            }
            compose.waitForIdle()
            compose.onNodeWithText("Unsaved name").assertDoesNotExist()
            compose.onNodeWithText("密钥（已复制）：$SECRET").assertDoesNotExist()
            assertEquals(1, source.calls.count { it.startsWith("create") })
        }
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

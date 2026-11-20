package cc.fplj.magpiemobile

import android.app.Application
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import cc.fplj.magpiemobile.core.SessionRecord
import cc.fplj.magpiemobile.data.ChatDatabase
import cc.fplj.magpiemobile.data.ChatRepository
import cc.fplj.magpiemobile.data.ConversationEntity
import cc.fplj.magpiemobile.theme.MagpieTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real ChatPanel + ViewModel + Room; local history must work without a gateway or model. */
@RunWith(AndroidJUnit4::class)
class ChatPanelInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test fun landscapeCompactMenuCanCreateAndSwitchHistoryWithoutAGateway() {
        withPanel { model, _ ->
            compose.onNodeWithTag("chat-model-selector").assertIsNotEnabled()
            openMenu()
            compose.onNodeWithTag("chat-new-conversation").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("chat-history").assertIsDisplayed().assertIsEnabled()
            screenshot("chat-conversation-menu-landscape.png")
            compose.onNodeWithTag("chat-new-conversation").performClick()
            awaitIdle(model)
            val fresh = checkNotNull(model.state.value.selected).id
            assertNotEquals(PRIMARY_ID, fresh)
            assertTrue(model.state.value.messages.isEmpty())

            openMenu()
            compose.onNodeWithTag("chat-history").performClick()
            compose.onNodeWithText("历史会话（本机）").assertIsDisplayed()
            compose.onNodeWithText(PRIMARY_TITLE).assertIsDisplayed().performClick()
            awaitIdle(model)
            assertEquals(PRIMARY_ID, model.state.value.selected?.id)
        }
    }

    @Test fun landscapeCompactMenuCanReadOlderMessagesAndReturnToLatest() {
        withPanel(turns = 26) { model, _ ->
            val latestIds = model.state.value.messages.map { it.id }
            assertEquals(50, latestIds.size)
            assertTrue(model.state.value.olderMessages)
            openMenu()
            compose.onNodeWithTag("chat-older").assertIsDisplayed().assertIsEnabled().performClick()
            awaitIdle(model)
            assertTrue(model.state.value.viewingOlderMessages)
            assertEquals(listOf("question-0", "answer-0"), model.state.value.messages.map { it.text })

            openMenu()
            compose.onNodeWithTag("chat-latest").assertIsDisplayed().assertIsEnabled()
            screenshot("chat-conversation-menu-older.png")
            compose.onNodeWithTag("chat-latest").performClick()
            awaitIdle(model)
            assertFalse(model.state.value.viewingOlderMessages)
            assertEquals(PRIMARY_ID, model.state.value.selected?.id)
            assertEquals(latestIds, model.state.value.messages.map { it.id })
        }
    }

    @Test fun contextLimitStillOffersANewConversationInLandscape() {
        withPanel(turns = 40) { model, repository ->
            assertTrue(model.state.value.contextBlocked)
            compose.onNodeWithText("上下文已达到安全上限；记录仍保留，请新建会话。")
                .assertIsDisplayed()
            compose.onNodeWithTag("chat-send").assertIsNotEnabled()
            openMenu()
            compose.onNodeWithTag("chat-new-conversation").assertIsDisplayed().assertIsEnabled()
            screenshot("chat-conversation-menu-context-limit.png")
            compose.onNodeWithTag("chat-new-conversation").performClick()
            awaitIdle(model)
            assertNotEquals(PRIMARY_ID, model.state.value.selected?.id)
            assertFalse(model.state.value.contextBlocked)
            assertTrue(model.state.value.messages.isEmpty())
            runBlocking(Dispatchers.IO) {
                assertNotNull(repository.conversation(PRIMARY_ID))
                assertEquals(80, repository.messages(PRIMARY_ID, limit = 100).size)
            }
        }
    }

    @Test fun keyboardCompactMenuKeepsRefreshReachableAndPreservesTheDraft() {
        val previous = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        try {
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            withPanel(landscape = false) { model, _ ->
                compose.onNodeWithTag("chat-conversation-menu").assertDoesNotExist()
                dismissEmulatorLauncherAnr()
                awaitFixtureWindowFocus()
                compose.onNodeWithTag("chat-draft").assertIsDisplayed()
                checkNotNull(device.findObject(By.res("chat-draft"))) {
                    "The real draft field must be accessible before requesting the system IME"
                }.click()
                awaitNativeIme()
                compose.onNodeWithTag("chat-draft")
                    .performTextReplacement("键盘打开时保留的草稿")
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("chat-conversation-menu").fetchSemanticsNodes().isNotEmpty()
                }
                openMenu()
                compose.onNodeWithTag("chat-refresh").assertIsDisplayed().assertIsEnabled()
                screenshot("chat-conversation-menu-keyboard.png")
                compose.onNodeWithTag("chat-refresh").performClick()
                awaitIdle(model)
                assertEquals(PRIMARY_ID, model.state.value.selected?.id)
                assertEquals("键盘打开时保留的草稿", model.state.value.draft)
            }
        } finally {
            if (previous == "0" || previous == "1") {
                device.executeShellCommand("settings put secure show_ime_with_hard_keyboard $previous")
            } else {
                device.executeShellCommand("settings delete secure show_ime_with_hard_keyboard")
            }
        }
    }

    private fun withPanel(
        turns: Int = 0,
        landscape: Boolean = true,
        block: (ChatViewModel, ChatRepository) -> Unit,
    ) {
        dismissEmulatorLauncherAnr()
        if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
        compose.waitUntil(10_000) {
            dismissEmulatorLauncherAnr()
            compose.activity.resources.configuration.orientation == if (landscape)
                Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }
        awaitFixtureWindowFocus()
        val configuration = compose.activity.resources.configuration
        assertTrue("Fixture must exercise the real compact height", if (landscape)
            configuration.screenHeightDp < 520 else configuration.screenHeightDp >= 520)
        val application = compose.activity.application as Application
        val database = Room.inMemoryDatabaseBuilder(application, ChatDatabase::class.java).build()
        val repository = ChatRepository(database)
        runBlocking(Dispatchers.IO) {
            // Do not import or alter any history left by the full installed-core test.
            repository.initialize(File(application.cacheDir, "absent-chat-panel-${UUID.randomUUID()}"))
            database.chatDao().insertConversation(ConversationEntity(PRIMARY_ID, PRIMARY_TITLE, 1))
            val store = repository.turnStore(PRIMARY_ID)
            repeat(turns) { index ->
                val timestamp = index.toLong() * 2 + 2
                val turn = store.begin(SessionRecord(timestamp, "user", "chat:test/model", "question-$index"))
                store.complete(turn, SessionRecord(timestamp + 1, "assistant", "chat:test/model", "answer-$index"))
            }
        }
        val viewModelStore = ViewModelStore()
        val captures = AtomicInteger()
        lateinit var model: ChatViewModel
        compose.runOnUiThread {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
                    application, { repository }, acquire = {
                        captures.incrementAndGet()
                        throw CancellationException("Offline chat panel fixture")
                    },
                ) as T
            }
            model = ViewModelProvider(viewModelStore, factory)[ChatViewModel::class.java]
        }
        val visible = mutableStateOf(true)
        try {
            compose.setContent {
                MagpieTheme(darkTheme = false) {
                    // Dashboard owns the single IME inset in the installed application.
                    Surface(Modifier.fillMaxSize().imePadding()) {
                        if (visible.value) ChatPanel(
                            viewModel = model,
                            modelGroups = emptyList(),
                            selectedModel = "",
                            onSelectModel = { error("No model in offline history fixture") },
                            onSend = { _, _ -> error("Local history must not send a model request") },
                            canConnect = false,
                            catalogSession = null,
                            providerBusy = false,
                        )
                    }
                }
            }
            awaitIdle(model)
            assertTrue(model.state.value.ready)
            assertEquals(PRIMARY_ID, model.state.value.selected?.id)
            block(model, repository)
            assertEquals("Conversation actions must stay local", 0, captures.get())
        } finally {
            compose.runOnUiThread { visible.value = false; viewModelStore.clear() }
            awaitIdle(model)
            compose.waitForIdle()
            database.close()
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
    }

    private fun awaitIdle(model: ChatViewModel) {
        compose.waitUntil(10_000) { !model.state.value.busy && !model.state.value.sending }
        compose.waitForIdle()
    }

    private fun openMenu() {
        compose.onNodeWithTag("chat-conversation-menu").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat-new-conversation").assertIsDisplayed()
    }

    private fun dismissEmulatorLauncherAnr() {
        // API35's Pixel Launcher can ANR during first boot; leave any app ANR as a failure.
        if (device.hasObject(By.text("Pixel Launcher isn't responding"))) {
            checkNotNull(device.findObject(By.text("Close app"))) {
                "The identified Pixel Launcher ANR must offer its Close app action"
            }.click()
            device.waitForIdle()
            assertFalse(device.hasObject(By.text("Pixel Launcher isn't responding")))
        }
    }

    private fun awaitFixtureWindowFocus() {
        compose.waitUntil(10_000) {
            dismissEmulatorLauncherAnr()
            var focused = false
            compose.activityRule.scenario.onActivity { activity ->
                focused = activity.hasWindowFocus()
            }
            focused
        }
    }

    private fun awaitNativeIme() {
        var visible = false
        var bottom = 0
        try {
            compose.waitUntil(10_000) {
                dismissEmulatorLauncherAnr()
                compose.activityRule.scenario.onActivity { activity ->
                    val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    visible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                }
                visible && bottom > 0
            }
        } finally {
            val evidence = File(artifactDirectory(), "chat-conversation-menu-keyboard-ime.txt")
            evidence.writeText("imeVisible=$visible\nimeBottom=$bottom\n")
            exportArtifact(evidence)
        }
        assertTrue("The real system IME must be visible with a positive bottom inset",
            visible && bottom > 0)
    }

    private fun screenshot(name: String) {
        dismissEmulatorLauncherAnr()
        compose.waitForIdle()
        val image = File(artifactDirectory(), name)
        assertTrue(device.takeScreenshot(image))
        exportArtifact(image)
    }

    private fun artifactDirectory() =
        File(instrumentation.targetContext.getExternalFilesDir(null), "plugin-runtime")
            .apply { mkdirs() }

    private fun exportArtifact(file: File) {
        // Export each artifact independently of the order of the installed-core test class.
        val exported = "/data/local/tmp/magpie-plugin-runtime/${file.name}"
        device.executeShellCommand("mkdir -p /data/local/tmp/magpie-plugin-runtime")
        device.executeShellCommand("cp ${file.absolutePath} $exported")
        assertEquals(exported, device.executeShellCommand("ls $exported").trim())
    }

    private companion object {
        const val PRIMARY_ID = "chat-panel-history-fixture"
        const val PRIMARY_TITLE = "保留的本机会话"
    }
}

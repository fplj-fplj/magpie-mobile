package cc.fplj.magpiemobile

import android.content.ClipData
import android.content.ClipDescription
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cc.fplj.magpiemobile.theme.MagpieTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Panel, production lease/client and actual HTTP, without starting or replacing a core. */
@RunWith(AndroidJUnit4::class)
class ProviderPanelInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun visibleApiKeySystemCopyAndCutAreMarkedSensitiveAndPasteStillWorks() {
        ProviderPanelHttpPeer().use { peer ->
            withPanel(peer) {
                openEditor()
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                val clipboard = checkNotNull(compose.activity.getSystemService(android.content.ClipboardManager::class.java))
                try {
                    compose.onNodeWithTag(API_KEY).performScrollTo()
                    compose.onNodeWithText("显示").performClick()
                    compose.onNodeWithTag(API_KEY).performTextReplacement(SYNTHETIC_KEY)

                    selectAllAndUseSystemAction(device, API_KEY, "Copy")
                    assertSensitivePrimaryClip(SYNTHETIC_KEY)
                    compose.onNodeWithTag(API_KEY).performTextReplacement("")
                    pasteFromSystemClipboard(device)
                    assertSensitivePrimaryClip(SYNTHETIC_KEY)

                    compose.onNodeWithTag(API_KEY).performClick()
                    selectAllAndUseSystemAction(device, API_KEY, "Cut")
                    compose.onNodeWithTag(API_KEY).assert(SemanticsMatcher.expectValue(
                        SemanticsProperties.EditableText, AnnotatedString("")))
                    assertSensitivePrimaryClip(SYNTHETIC_KEY)
                    pasteFromSystemClipboard(device)
                    assertSensitivePrimaryClip(SYNTHETIC_KEY)
                    assertTrue("Clipboard exercise must not submit provider changes", peer.editRequests.isEmpty())
                    peer.assertHealthy()
                } finally {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
    }

    @Test fun pendingSaveDisablesManualModelsAndRejectsPreviouslyCapturedSetTextUntilExplicitRetry() {
        ProviderPanelHttpPeer().use { peer ->
            val first = peer.holdNextEdit(400)
            val second = peer.holdNextEdit(200)
            withPanel(peer) {
                assertProviderSummary("启用 1 / 目录 1")
                openEditor()
                compose.onNodeWithText("高级").performClick()
                compose.onNodeWithText("手动补充模型 ID").performScrollTo().performClick()
                compose.onNodeWithTag(MANUAL).performScrollTo().performTextReplacement(BEFORE_SAVE)
                assertManualText(BEFORE_SAVE)
                awaitEnabled(SAVE)

                // Hold both old semantics callbacks before the click. Keeping these two calls
                // in one main-thread task proves the mutation fence before any recomposition.
                val oldSave = checkNotNull(compose.onNodeWithTag(SAVE).fetchSemanticsNode()
                    .config[SemanticsActions.OnClick].action)
                val oldSetText = checkNotNull(compose.onNodeWithTag(MANUAL).fetchSemanticsNode()
                    .config[SemanticsActions.SetText].action)
                compose.runOnUiThread {
                    assertTrue(oldSave())
                    oldSetText(AnnotatedString(STALE_CALLBACK))
                }
                compose.waitUntil(10_000) { first.entered.count == 0L }
                compose.onNodeWithTag(MANUAL).assertIsNotEnabled()
                compose.onNodeWithTag(SAVE).assertIsNotEnabled()
                assertManualText(BEFORE_SAVE)
                assertEquals(1, peer.editRequests.size)
                assertModelWrite(peer.editRequests.single(), BEFORE_SAVE)

                first.release.countDown()
                awaitEnabled(MANUAL)
                compose.onNode(hasText("请求失败：HTTP 400") and
                    hasAnyAncestor(hasTestTag("provider-editor-surface")))
                    .performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag(MANUAL).performScrollTo().assertIsEnabled()
                assertManualText(BEFORE_SAVE)
                awaitEnabled(SAVE)
                compose.waitForIdle()
                assertEquals("A known failure must wait for another explicit save", 1, peer.editRequests.size)

                compose.onNodeWithTag(MANUAL).performTextReplacement(AFTER_FAILURE)
                assertManualText(AFTER_FAILURE)
                compose.onNodeWithTag(SAVE).performClick()
                compose.waitUntil(10_000) { second.entered.count == 0L }
                compose.onNodeWithTag(MANUAL).assertIsNotEnabled()
                assertEquals(2, peer.editRequests.size)
                assertModelWrite(peer.editRequests.last(), AFTER_FAILURE)
                second.release.countDown()
                awaitEditorClosed()
                compose.waitUntil(10_000) { peer.listCount == 2 }
                awaitEnabled(ROW)
                compose.onNodeWithTag(ROW).assertIsDisplayed()
                peer.assertHealthy()
            }
        }
    }

    @Test fun externalRevisionsWhileEditorIsOpenPreserveDraftAndTriggerOneFreshListAfterClose() {
        ProviderPanelHttpPeer().use { peer ->
            withPanel(peer) { returnedFromExternalEditor ->
                assertProviderSummary("启用 1 / 目录 1")
                openEditor()
                compose.onNodeWithTag("provider-name").performTextReplacement("Unsaved provider draft")
                peer.externalRename("External provider edit")
                returnedFromExternalEditor()
                compose.onNodeWithTag("provider-name").assert(SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText, AnnotatedString("Unsaved provider draft")))
                compose.waitForIdle()
                assertEquals("An open draft must defer the external list read", 1, peer.listCount)
                peer.externalRename("Newest external provider")
                returnedFromExternalEditor()
                compose.waitForIdle()
                assertEquals(1, peer.listCount)
                compose.onNodeWithTag("provider-name").assert(SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText, AnnotatedString("Unsaved provider draft")))

                compose.onNodeWithContentDescription("返回提供商列表").performClick()
                awaitEditorClosed()
                compose.waitUntil(10_000) { peer.listCount == 2 }
                awaitText("Newest external provider")
                awaitEnabled(ROW)
                compose.onNodeWithText("Newest external provider").assertIsDisplayed()
                compose.waitForIdle()
                assertEquals("The two deferred revisions must coalesce into one fresh GET", 2, peer.listCount)
                assertTrue(peer.editRequests.isEmpty())
                peer.assertHealthy()
            }
        }
    }

    @Test fun externalRevisionsDuringHeldListGetDoNotUseItsOldSnapshotToConfirmLatestRevision() {
        ProviderPanelHttpPeer().use { peer ->
            val firstList = peer.holdNextList()
            withPanel(peer, awaitList = false) { returnedFromExternalEditor ->
                compose.waitUntil(10_000) { firstList.entered.count == 0L }
                assertEquals(1, peer.listCount)
                peer.externalRename("External while GET was held")
                returnedFromExternalEditor()
                returnedFromExternalEditor()
                // The first actual GET still owns the slot: never race it with another read.
                assertEquals(1, peer.listCount)
                firstList.release.countDown()
                compose.waitUntil(10_000) { peer.listCount == 2 }
                awaitText("External while GET was held")
                awaitEnabled(ROW)
                assertProviderSummary("启用 1 / 目录 1")
                compose.onNodeWithText("External while GET was held").assertIsDisplayed()
                compose.onNodeWithTag(ROW).assertIsEnabled()
                compose.waitForIdle()
                assertEquals("Completion of an older GET must schedule exactly one follow-up GET", 2, peer.listCount)
                assertTrue(peer.editRequests.isEmpty())
                peer.assertHealthy()
            }
        }
    }

    @Test fun implicitAllSavedEnabledModelsCanBeSelectedForTestingWithoutUsingUnsavedDirectoryOrDraft() {
        ProviderPanelHttpPeer(
            savedModels = emptyList(),
            listedModels = linkedMapOf(AUTO_FIRST to true, AUTO_SECOND to true, INACTIVE_MODEL to false),
            discoveryModels = listOf(DIRECTORY_ONLY),
        ).use { peer ->
            withPanel(peer) {
                assertProviderSummary("启用 2 / 目录 3")
                openEditor()
                enterUnsavedManualModel(peer, expectedAutomaticSelection = DIRECTORY_ONLY)
                openConnectivityDialog()
                compose.onNode(inConnectivityDialog(AUTO_FIRST)).assertIsDisplayed().assertIsEnabled()
                compose.onNode(inConnectivityDialog("开始测试")).assertIsDisplayed().assertIsEnabled()

                // Open the actual single-select sheet. Its options must come from the saved
                // management response's on=true models, even though chosen is empty.
                compose.onNode(inConnectivityDialog(AUTO_FIRST)).performClick()
                compose.onNodeWithTag("model-option:$AUTO_FIRST").assertIsSelected()
                compose.onNodeWithTag("model-option:$AUTO_SECOND").assertIsDisplayed().assertIsEnabled()
                listOf(INACTIVE_MODEL, DIRECTORY_ONLY, UNSAVED_MODEL).forEach { excluded ->
                    // Search forces any wrongly included option into view; absence must not
                    // depend on which lazy rows happened to be composed on this device.
                    compose.onNodeWithTag("model-search").performTextReplacement(excluded)
                    compose.onNodeWithTag("model-option:$excluded").assertDoesNotExist()
                    compose.onNode(hasText("没有匹配的模型") and
                        hasAnyAncestor(hasTestTag("model-selection-sheet"))).assertIsDisplayed()
                }
                compose.onNodeWithTag("model-search").performTextReplacement("")
                compose.onNodeWithTag("model-option:$AUTO_SECOND").performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("model-selection-sheet").fetchSemanticsNodes().isEmpty()
                }
                compose.onNode(inConnectivityDialog(AUTO_SECOND)).assertIsDisplayed()
                compose.onNode(inConnectivityDialog("开始测试")).assertIsEnabled()
                assertEquals(1, peer.listCount)
                assertTrue("Changing a test model must not save the editor draft", peer.editRequests.isEmpty())
                compose.onNode(inConnectivityDialog("关闭")).performClick()
                compose.onNodeWithContentDescription("返回提供商列表").performClick()
                awaitEditorClosed()
                assertProviderSummary("启用 2 / 目录 3")
                peer.assertHealthy()
                // Do not start inference here: installed-Go instrumentation covers execution.
            }
        }
    }

    @Test fun explicitlyDisabledSavedModelsRemainUntestableDespiteUnsavedManualModels() {
        ProviderPanelHttpPeer(
            savedModels = emptyList(),
            noModels = true,
            listedModels = linkedMapOf(AUTO_FIRST to false),
            discoveryModels = listOf(DIRECTORY_ONLY),
        ).use { peer ->
            withPanel(peer) {
                assertProviderSummary("启用 0 / 目录 1")
                openEditor()
                enterUnsavedManualModel(peer, expectedAutomaticSelection = "")
                openConnectivityDialog()
                assertNoSavedModelsCanBeTested(peer)
                compose.onNode(inConnectivityDialog("关闭")).performClick()
                compose.onNodeWithContentDescription("返回提供商列表").performClick()
                awaitEditorClosed()
                assertProviderSummary("启用 0 / 目录 1")
            }
        }
    }

    @Test fun switchedOffProviderRemainsUntestableEvenWithEnabledMetadataAndUnsavedManualModels() {
        ProviderPanelHttpPeer(
            savedModels = emptyList(),
            off = true,
            // Deliberately retain on=true metadata: the provider's off gate must win.
            listedModels = linkedMapOf(AUTO_FIRST to true),
            discoveryModels = listOf(DIRECTORY_ONLY),
        ).use { peer ->
            withPanel(peer) {
                assertProviderSummary("启用 0 / 目录 1")
                openEditor()
                enterUnsavedManualModel(peer, expectedAutomaticSelection = DIRECTORY_ONLY)
                openConnectivityDialog()
                assertNoSavedModelsCanBeTested(peer)
                compose.onNode(inConnectivityDialog("关闭")).performClick()
                compose.onNodeWithContentDescription("返回提供商列表").performClick()
                awaitEditorClosed()
                assertProviderSummary("启用 0 / 目录 1")
            }
        }
    }

    @Test fun implicitAllUsesSavedExposedCountWhenTwentyFourOfTwentyFiveModelsAreEnabled() {
        val models = (0 until 25).associate { index ->
            "implicit-${index.toString().padStart(2, '0')}" to (index < 24)
        }
        ProviderPanelHttpPeer(
            savedModels = emptyList(),
            listedModels = models,
            discoveryModels = listOf(DIRECTORY_ONLY),
        ).use { peer ->
            withPanel(peer) {
                assertProviderSummary("启用 24 / 目录 25")
                openEditor()
                compose.waitUntil(10_000) { peer.modelDirectoryCount == 1 }
                openConnectivityDialog()
                compose.onNode(inConnectivityDialog("implicit-00")).assertIsDisplayed()
                compose.onNode(inConnectivityDialog("开始测试")).assertIsEnabled()
                assertEquals(1, peer.listCount)
                assertTrue(peer.editRequests.isEmpty())
                peer.assertHealthy()
            }
        }
    }

    @Test fun explicitChosenDoesNotOverrideSavedOnFlagsInProviderSummaryOrConnectivityCandidates() {
        ProviderPanelHttpPeer(
            savedModels = listOf(AUTO_FIRST),
            listedModels = linkedMapOf(AUTO_FIRST to false, AUTO_SECOND to true),
            discoveryModels = listOf(DIRECTORY_ONLY),
        ).use { peer ->
            withPanel(peer) {
                assertProviderSummary("启用 1 / 目录 2")
                openEditor()
                compose.waitUntil(10_000) { peer.modelDirectoryCount == 1 }
                openConnectivityDialog()
                compose.onNode(inConnectivityDialog(AUTO_SECOND)).assertIsDisplayed()
                compose.onNode(inConnectivityDialog("开始测试")).assertIsEnabled()
                compose.onNode(inConnectivityDialog(AUTO_FIRST)).assertDoesNotExist()
                assertEquals(1, peer.listCount)
                assertTrue(peer.editRequests.isEmpty())
                peer.assertHealthy()
            }
        }
    }

    @Test fun notReadyProviderShowsNotReadyAndCannotBeTestedDespiteEnabledMetadata() {
        ProviderPanelHttpPeer(
            listedModels = linkedMapOf(AUTO_FIRST to true),
            ready = false,
            discoveryModels = listOf(DIRECTORY_ONLY),
        ).use { peer ->
            withPanel(peer) {
                assertProviderSummary("启用 0 / 目录 1")
                assertProviderStatus("未就绪")
                openEditor()
                compose.waitUntil(10_000) { peer.modelDirectoryCount == 1 }
                openConnectivityDialog()
                compose.onNode(inConnectivityDialog(NO_SAVED_MODELS)).assertIsDisplayed()
                compose.onNode(inConnectivityDialog("开始测试")).assertIsNotEnabled()
                assertEquals(1, peer.listCount)
                assertTrue(peer.editRequests.isEmpty())
                peer.assertHealthy()
            }
        }
    }

    private fun enterUnsavedManualModel(peer: ProviderPanelHttpPeer, expectedAutomaticSelection: String) {
        compose.waitUntil(10_000) { peer.modelDirectoryCount == 1 }
        // A received POST alone does not prove its catalog has reached the editor.
        awaitEnabled(SAVE)
        compose.onNodeWithText("高级").performClick()
        compose.onNodeWithText("手动补充模型 ID").performScrollTo().performClick()
        compose.onNodeWithTag(MANUAL).performScrollTo()
        assertManualText(expectedAutomaticSelection)
        compose.onNodeWithTag(MANUAL).performTextReplacement(UNSAVED_MODEL)
        assertManualText(UNSAVED_MODEL)
        assertTrue(peer.editRequests.isEmpty())
    }

    private fun openConnectivityDialog() {
        // Text replacement can finish while Android is still moving the editor for the IME.
        // Wait for an actual user-style keyboard dismissal before tapping the next entry.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        if (device.hasObject(By.text("Pixel Launcher isn't responding"))) {
            device.findObject(By.text("Close app"))?.click()
            device.waitForIdle()
        }
        fun keyboardVisible() = instrumentation.uiAutomation.windows.any {
            it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        if (keyboardVisible()) device.pressBack()
        compose.waitUntil(10_000) { !keyboardVisible() }
        compose.waitForIdle()
        compose.onNodeWithTag("provider-connectivity-entry").assertIsEnabled().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("provider-connectivity-dialog").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("provider-connectivity-dialog").assertIsDisplayed()
    }

    private fun assertProviderSummary(summary: String) {
        compose.onNode(hasText(summary) and hasAnyAncestor(hasTestTag(ROW)), useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun assertProviderStatus(status: String) {
        compose.onNode(hasText(status) and hasAnyAncestor(hasTestTag(ROW)), useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun assertNoSavedModelsCanBeTested(peer: ProviderPanelHttpPeer) {
        compose.onNode(inConnectivityDialog(NO_SAVED_MODELS)).assertIsDisplayed()
        compose.onNode(inConnectivityDialog("开始测试")).assertIsDisplayed().assertIsNotEnabled()
        compose.onNode(inConnectivityDialog(AUTO_FIRST)).assertDoesNotExist()
        compose.onNode(inConnectivityDialog(DIRECTORY_ONLY)).assertDoesNotExist()
        compose.onNode(inConnectivityDialog(UNSAVED_MODEL)).assertDoesNotExist()
        compose.onNodeWithTag("model-selection-sheet").assertDoesNotExist()
        assertEquals(1, peer.listCount)
        assertTrue("An unsaved manual model must not trigger a provider write", peer.editRequests.isEmpty())
        peer.assertHealthy()
    }

    private fun inConnectivityDialog(text: String): SemanticsMatcher = hasText(text) and
        hasAnyAncestor(hasTestTag("provider-connectivity-dialog"))

    private fun withPanel(
        peer: ProviderPanelHttpPeer,
        awaitList: Boolean = true,
        block: (() -> Unit) -> Unit,
    ) {
        val source = FixtureConnection(peer)
        val visible = mutableStateOf(true)
        val externalRevision = mutableStateOf(0)
        val cache = ViewModelProvider(compose.activity)[ProviderListCacheViewModel::class.java]
        compose.setContent {
            MagpieTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalProviderPanelConnection provides source) {
                        // Dashboard supplies this scrolling host in the installed app. Match
                        // it here so row labels retain their natural height and can be revealed.
                        if (visible.value) Column(Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState()).padding(12.dp)) {
                            ProvidersPanel(
                                enabled = true,
                                onProvidersChanged = {},
                                externalRevision = externalRevision.value,
                                cacheViewModel = cache,
                            )
                        }
                    }
                }
            }
        }
        try {
            if (awaitList) {
                awaitEnabled(ROW)
                assertEquals("The enabled initial list must come from one GET", 1, peer.listCount)
            }
            block {
                // Dashboard's ON_RESUME awaits invalidation before publishing the return.
                runBlocking { cache.invalidate() }
                compose.runOnIdle { externalRevision.value += 1 }
            }
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            source.close()
        }
    }

    private fun openEditor() {
        compose.onNodeWithTag(ROW).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("provider-editor-surface").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("provider-name").assertIsDisplayed()
    }

    private fun awaitEditorClosed() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("provider-editor-surface").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("provider-editor-surface").assertDoesNotExist()
    }

    private fun awaitEnabled(tag: String) {
        // Semantics reads already synchronize with Compose/Espresso. Keep the
        // polling deadline outside Compose's frame-advancing waitUntil to avoid
        // nesting two idle/frame drivers while the first HTTP list is laid out.
        // Any layout/idling exception still fails immediately; none is retried.
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val node = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
            if (node != null && !node.config.contains(SemanticsProperties.Disabled)) return
            SystemClock.sleep(25)
        }
        throw AssertionError("Timed out waiting for one enabled provider control: $tag")
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertManualText(expected: String) {
        compose.onNodeWithTag(MANUAL).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString(expected)))
    }

    private fun selectAllAndUseSystemAction(device: UiDevice, tag: String, action: String) {
        clipboardMenuStep(device, action.lowercase()) {
            val field = compose.onNodeWithTag(tag)
            field.performScrollTo().performClick()
            field.assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, AnnotatedString(SYNTHETIC_KEY)))
            field.performSemanticsAction(SemanticsActions.SetSelection) {
                it(0, SYNTHETIC_KEY.length, true)
            }
            field.assert(SemanticsMatcher.expectValue(
                SemanticsProperties.TextSelectionRange, TextRange(0, SYNTHETIC_KEY.length)))
            // AndroidTextToolbar finishes ActionMode after Select all. Set a known full
            // selection, then use the field's real long-click action to open a fresh menu.
            field.performSemanticsAction(SemanticsActions.OnLongClick) { assertTrue(it()) }
            assertTrue("System $action action did not appear",
                device.wait(Until.hasObject(By.text(action)), 5_000))
            device.findObject(By.text(action)).click()
            compose.waitForIdle()
        }
    }

    private fun pasteFromSystemClipboard(device: UiDevice) {
        clipboardMenuStep(device, "paste") {
            compose.onNodeWithTag(API_KEY).performScrollTo().performClick()
                .performSemanticsAction(SemanticsActions.OnLongClick) { assertTrue(it()) }
            assertTrue("System Paste action did not appear",
                device.wait(Until.hasObject(By.text("Paste")), 5_000))
            device.findObject(By.text("Paste")).click()
            compose.waitForIdle()
            compose.onNodeWithTag(API_KEY).assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, AnnotatedString(SYNTHETIC_KEY)))
        }
    }

    private fun clipboardMenuStep(device: UiDevice, step: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            // This fixture only displays a synthetic key. Export before withPanel tears down
            // the dialog and AGP uninstalls the app, independently of test-class order.
            val artifacts = File(InstrumentationRegistry.getInstrumentation()
                .targetContext.getExternalFilesDir(null), "plugin-runtime").apply { mkdirs() }
            for (extension in listOf("png", "xml")) {
                try {
                    val file = File(artifacts, "provider-clipboard-$step-failure.$extension")
                    if (extension == "png") assertTrue(device.takeScreenshot(file))
                    else device.dumpWindowHierarchy(file)
                    val exported = "/data/local/tmp/magpie-plugin-runtime/${file.name}"
                    device.executeShellCommand("mkdir -p /data/local/tmp/magpie-plugin-runtime")
                    device.executeShellCommand("cp ${file.absolutePath} $exported")
                    assertEquals(exported, device.executeShellCommand("ls $exported").trim())
                } catch (evidenceFailure: Throwable) {
                    failure.addSuppressed(evidenceFailure)
                }
            }
            throw failure
        }
    }

    private fun assertSensitivePrimaryClip(expected: String) {
        val clipboard = checkNotNull(compose.activity.getSystemService(android.content.ClipboardManager::class.java))
        val clip = checkNotNull(clipboard.primaryClip)
        assertEquals(expected, clip.getItemAt(0).coerceToText(compose.activity).toString())
        assertTrue("System clip must carry the sensitive marker",
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
    }

    private fun assertModelWrite(request: ProviderPanelHttpPeer.Request, model: String) {
        val body = JSONObject(request.body)
        assertEquals(ProviderPanelHttpPeer.ID, body.getString("id"))
        assertEquals("a".repeat(64), body.getString("revision"))
        assertEquals(1, body.getJSONArray("models").length())
        assertEquals(model, body.getJSONArray("models").getString(0))
        assertFalse(body.getBoolean("noModels"))
        assertFalse(body.has("key"))
        assertFalse(request.body.contains(STALE_CALLBACK))
    }

    private class FixtureConnection(peer: ProviderPanelHttpPeer) : ProviderPanelConnection {
        private val leases = ProviderRequestLeases()
        private val credential = peer.credential
        private val current = MutableStateFlow(NativeCoreSession.Snapshot(
            NativeCoreSession.Phase.READY, "Synthetic loopback management connection", providerSession = SESSION,
        ))
        override val status: StateFlow<NativeCoreSession.Snapshot> = current
        override fun currentSession(): ProviderSessionKey? = current.value.providerSession
        override fun acquireLease(key: ProviderSessionKey): ProviderRequestLease {
            check(key == currentSession()) { "Unexpected provider fixture session" }
            return leases.acquire(credential) { key == currentSession() }
        }
        fun close() {
            current.value = NativeCoreSession.Snapshot(NativeCoreSession.Phase.DISCONNECTED, "Fixture closed")
            leases.invalidate()
        }
    }

    private companion object {
        val SESSION = ProviderSessionKey(701L, 1L)
        const val ROW = "provider-row:${ProviderPanelHttpPeer.ID}"
        const val API_KEY = "provider-api-key"
        const val SYNTHETIC_KEY = "sk-provider-panel-clipboard-synthetic-62"
        const val MANUAL = "provider-manual-models"
        const val SAVE = "provider-save"
        const val BEFORE_SAVE = "manual-before-save"
        const val AFTER_FAILURE = "manual-after-explicit-retry"
        const val STALE_CALLBACK = "must-not-enter-draft-or-request"
        const val AUTO_FIRST = "saved-auto-first"
        const val AUTO_SECOND = "saved-auto-second"
        const val INACTIVE_MODEL = "saved-catalog-inactive"
        const val DIRECTORY_ONLY = "discovered-directory-only"
        const val UNSAVED_MODEL = "unsaved-manual-model"
        const val NO_SAVED_MODELS = "当前提供商没有已保存且启用的模型；保存模型选择后再测试。"
    }
}

package cc.fplj.magpiemobile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.fplj.magpiemobile.theme.MagpieTheme
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

    @Test fun pendingSaveDisablesManualModelsAndRejectsPreviouslyCapturedSetTextUntilExplicitRetry() {
        ProviderPanelHttpPeer().use { peer ->
            val first = peer.holdNextEdit(400)
            val second = peer.holdNextEdit(200)
            withPanel(peer) {
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
                compose.onNodeWithText("External while GET was held").assertIsDisplayed()
                compose.onNodeWithTag(ROW).assertIsEnabled()
                compose.waitForIdle()
                assertEquals("Completion of an older GET must schedule exactly one follow-up GET", 2, peer.listCount)
                assertTrue(peer.editRequests.isEmpty())
                peer.assertHealthy()
            }
        }
    }

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
                        if (visible.value) Column(Modifier.fillMaxSize().padding(12.dp)) {
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
                compose.waitUntil(10_000) { peer.listCount == 1 }
                awaitEnabled(ROW)
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
        compose.onNodeWithTag(ROW).performClick()
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
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
                ?.config?.contains(SemanticsProperties.Disabled) == false
        }
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
        const val MANUAL = "provider-manual-models"
        const val SAVE = "provider-save"
        const val BEFORE_SAVE = "manual-before-save"
        const val AFTER_FAILURE = "manual-after-explicit-retry"
        const val STALE_CALLBACK = "must-not-enter-draft-or-request"
    }
}

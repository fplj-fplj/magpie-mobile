package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ManagedPlugin
import cc.fplj.magpiemobile.core.PluginSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the production acknowledgement gate used by the native options editor. */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginOptionsSaveConfirmationTest {
    @Test fun matchingSnapshotCannotAcknowledgeAWriteWhileItIsStillBusy() = runTest {
        val states = MutableStateFlow(state(B, busy = true))
        val acknowledgement = async { confirm(states, B) }
        runCurrent()
        assertFalse(acknowledgement.isCompleted)

        states.value = state(B)
        assertEquals(B, acknowledgement.await())
    }

    @Test fun fastCompletedWriteDoesNotRequireAnObservedBusyFrame() = runTest {
        assertEquals(B, confirm(MutableStateFlow(state(B)), B))
    }

    @Test fun knownFailureKeepsThePreviousBaselineInsteadOfAcknowledgingTheDraft() = runTest {
        val states = MutableStateFlow(state(A, busy = true))
        val acknowledgement = async { confirm(states, B) }
        runCurrent()
        states.value = state(A).copy(error = "核心请求失败：HTTP 400")

        assertNull(acknowledgement.await())
    }

    @Test fun unknownWriteWaitsForReadonlyConfirmationWithoutAcceptingAPendingSnapshot() = runTest {
        val states = MutableStateFlow(state(A, pending = true))
        val acknowledgement = async { confirm(states, B) }
        runCurrent()
        assertFalse(acknowledgement.isCompleted)

        states.value = state(B, pending = true)
        runCurrent()
        assertFalse(acknowledgement.isCompleted)
        states.value = state(B)
        assertEquals(B, acknowledgement.await())
    }

    @Test fun differentExternalOptionsCannotBecomeTheSubmittedDraftsBaseline() = runTest {
        val states = MutableStateFlow(state(A, busy = true))
        val acknowledgement = async { confirm(states, B) }
        runCurrent()
        states.value = state(C)

        assertNull(acknowledgement.await())
    }

    @Test fun removedPluginOrAnotherSpecsMatchingOptionsCannotAcknowledgeTheSave() = runTest {
        assertNull(confirm(MutableStateFlow(OfficialPluginsState()), B))
        val other = OfficialPluginsState(snapshot = PluginSnapshot(entries = listOf(ManagedPlugin("other", options = B))))
        assertNull(confirm(MutableStateFlow(other), B))
    }

    @Test fun replacementSessionCannotAcknowledgeAnOlderEditorsWrite() = runTest {
        var session: ProviderSessionKey? = SESSION
        val states = MutableStateFlow(state(A, busy = true))
        val acknowledgement = async {
            confirmedPluginOptionsSave(states, SESSION, { session }, SPEC, B)
        }
        runCurrent()
        session = ProviderSessionKey(SESSION.owner, SESSION.epoch + 1)
        states.value = state(B)

        assertNull(acknowledgement.await())
    }

    @Test fun disposedPanelStopsItsAcknowledgementWithoutChangingTheVmState() = runTest {
        val waiting = state(A, pending = true)
        val states = MutableStateFlow(waiting)
        var acknowledged: JsonObject? = null
        val observer = async { confirm(states, B).also { acknowledged = it } }
        runCurrent()
        observer.cancelAndJoin()
        states.value = state(B)
        runCurrent()

        assertTrue(observer.isCancelled)
        assertNull(acknowledged)
        assertEquals(B, states.value.snapshot.entries.single().options)
    }

    private suspend fun confirm(states: MutableStateFlow<OfficialPluginsState>, submitted: JsonObject): JsonObject? =
        confirmedPluginOptionsSave(states, SESSION, { SESSION }, SPEC, submitted)

    private fun state(options: JsonObject, busy: Boolean = false, pending: Boolean = false) = OfficialPluginsState(
        snapshot = PluginSnapshot(entries = listOf(ManagedPlugin(SPEC, options = options))),
        busy = busy,
        pending = pending,
    )

    private companion object {
        const val SPEC = "@fixture/options-editor"
        val SESSION = ProviderSessionKey(61, 1)
        val A = Json.parseToJsonElement("{\"revision\":\"a\"}") as JsonObject
        val B = Json.parseToJsonElement("{\"revision\":\"b\"}") as JsonObject
        val C = Json.parseToJsonElement("{\"revision\":\"c\"}") as JsonObject
    }
}

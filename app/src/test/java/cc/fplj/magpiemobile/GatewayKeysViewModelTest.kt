package cc.fplj.magpiemobile

import androidx.lifecycle.ViewModelStore
import cc.fplj.magpiemobile.core.CallerKeyAction
import cc.fplj.magpiemobile.core.CallerKeyChange
import cc.fplj.magpiemobile.core.ManagementExecutionUnknown
import cc.fplj.magpiemobile.core.ManagementHttpException
import cc.fplj.magpiemobile.core.RegisteredGatewayKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayKeysViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun refreshLoadsSanitizedKeysAndTheCurrentEpoch() = runTest(dispatcher) {
        val source = Source()
        source.vm.attach(FIRST)
        assertEquals(1, source.operations.size) // Synchronous capture, before launch or IO.
        advanceUntilIdle()
        assertFalse(source.vm.state.value.loading)
        assertEquals(FIRST, source.vm.state.value.session)
        assertEquals(listOf(KEY.copy(secret = "")), source.vm.state.value.keys)
        assertEquals("", source.vm.state.value.error)
        assertReleased(source)
    }

    @Test fun refreshFailureDoesNotExposeRawExceptionOrClearKnownList() = runTest(dispatcher) {
        val source = Source()
        source.vm.attach(FIRST)
        advanceUntilIdle()
        source.read = { error("synthetic-private-cookie") }
        source.vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(KEY.copy(secret = "")), source.vm.state.value.keys)
        assertFalse(source.vm.state.value.loading)
        assertFalse(source.vm.state.value.error.contains("synthetic-private-cookie"))
        assertTrue(source.vm.state.value.error.isNotEmpty())
    }

    @Test fun blankNameIsRejectedWithoutCapture() = runTest(dispatcher) {
        val source = Source()
        source.vm.createKey("   ")
        advanceUntilIdle()
        assertTrue(source.operations.isEmpty())
        assertEquals("密钥名称不能为空", source.vm.state.value.error)
    }

    @Test fun createTrimsNamePublishesPlaintextExplicitlyAndRefreshesWithoutReplay() = runTest(dispatcher) {
        val source = Source()
        source.vm.attach(FIRST)
        advanceUntilIdle()
        var received: RegisteredGatewayKey? = null
        source.vm.createKey(" NextChat ") { received = it }
        advanceUntilIdle()
        assertEquals(listOf("NextChat"), source.createdNames)
        assertEquals(KEY, received)
        assertEquals(KEY, source.vm.state.value.newlyCreatedKey)
        assertTrue(source.vm.state.value.keys.all { it.secret.isEmpty() })
        assertEquals(2, source.reads)
        source.vm.dismissNewKeyDialog()
        assertNull(source.vm.state.value.newlyCreatedKey)
        assertReleased(source)
    }

    @Test fun copyReturnsPlaintextOnlyToExplicitCallback() = runTest(dispatcher) {
        val source = Source()
        source.vm.attach(FIRST)
        advanceUntilIdle()
        var received = ""
        source.vm.copyKey(KEY.id) { received = it }
        advanceUntilIdle()
        assertEquals(KEY.secret, received)
        assertEquals(listOf(KEY.id), source.copiedIds)
        assertNull(source.vm.state.value.newlyCreatedKey)
        assertTrue(source.vm.state.value.keys.all { it.secret.isEmpty() })
        assertReleased(source)
    }

    @Test fun disconnectClearsPlaintextNoticeAndRows() = runTest(dispatcher) {
        val source = Source()
        source.vm.attach(FIRST)
        advanceUntilIdle()
        source.vm.createKey("NextChat")
        advanceUntilIdle()
        assertNotNull(source.vm.state.value.newlyCreatedKey)
        source.session = null
        source.vm.attach(null)
        assertEquals(GatewayKeysUiState(), source.vm.state.value)
        assertReleased(source)
    }

    @Test fun lateCopyCannotTouchClipboardAfterSameOwnerReauthenticates() = runTest(dispatcher) {
        val source = Source()
        source.vm.attach(FIRST)
        advanceUntilIdle()
        val reply = CompletableDeferred<RegisteredGatewayKey>()
        source.copy = { withContext(NonCancellable) { reply.await() } }
        var callbacks = 0
        source.vm.copyKey(KEY.id) { callbacks++ }
        runCurrent()
        source.session = NEXT_EPOCH
        source.vm.attach(NEXT_EPOCH)
        reply.complete(KEY)
        advanceUntilIdle()
        assertEquals(0, callbacks)
        assertEquals(NEXT_EPOCH, source.vm.state.value.session)
        assertNull(source.vm.state.value.newlyCreatedKey)
        assertEquals("", source.vm.state.value.notice)
        assertReleased(source)
    }

    @Test fun lateCreateCannotRestorePlaintextAfterOwnerReplacement() = runTest(dispatcher) {
        val source = Source()
        source.vm.attach(FIRST)
        advanceUntilIdle()
        val reply = CompletableDeferred<RegisteredGatewayKey>()
        source.create = { withContext(NonCancellable) { reply.await() } }
        var callbacks = 0
        source.vm.createKey("NextChat") { callbacks++ }
        runCurrent()
        source.session = OTHER_OWNER
        source.vm.attach(OTHER_OWNER)
        reply.complete(KEY)
        advanceUntilIdle()
        assertEquals(0, callbacks)
        assertNull(source.vm.state.value.newlyCreatedKey)
        assertEquals(OTHER_OWNER, source.vm.state.value.session)
        assertEquals("", source.vm.state.value.error)
        assertReleased(source)
    }

    @Test fun oldReadCannotOverwriteNewEpochListOrLoading() = runTest(dispatcher) {
        val source = Source()
        val reply = CompletableDeferred<List<RegisteredGatewayKey>>()
        source.read = { withContext(NonCancellable) { reply.await() } }
        source.vm.attach(FIRST)
        runCurrent()
        val replacement = KEY.copy(id = "replacement-id", name = "replacement", secret = "")
        source.read = { listOf(replacement) }
        source.session = NEXT_EPOCH
        source.vm.attach(NEXT_EPOCH)
        runCurrent()
        assertEquals(listOf(replacement), source.vm.state.value.keys)
        reply.complete(listOf(KEY))
        advanceUntilIdle()
        assertEquals(listOf(replacement), source.vm.state.value.keys)
        assertFalse(source.vm.state.value.loading)
        assertReleased(source)
    }

    @Test fun staleUiCannotCaptureReplacementSessionBeforeAttachEffectRuns() = runTest(dispatcher) {
        val source = Source()
        source.vm.attach(FIRST)
        advanceUntilIdle()
        source.session = NEXT_EPOCH
        source.vm.createKey("wrong epoch")
        source.vm.copyKey(KEY.id)
        source.vm.updateKeyRestrictions(KEY.id, listOf("model"), emptyMap(), "")
        source.vm.refresh()
        advanceUntilIdle()
        assertEquals(1, source.operations.size)
        assertTrue(source.createdNames.isEmpty())
        assertTrue(source.copiedIds.isEmpty())
        assertTrue(source.updates.isEmpty())
    }

    @Test fun rapidDuplicateActionsAreSerializedUntilFirstOperationReleases() = runTest(dispatcher) {
        val source = Source()
        source.vm.attach(FIRST)
        advanceUntilIdle()
        val reply = CompletableDeferred<RegisteredGatewayKey>()
        source.create = { reply.await() }
        source.vm.createKey("NextChat")
        source.vm.createKey("NextChat")
        source.vm.copyKey(KEY.id)
        source.vm.refresh()
        source.vm.updateKeyRestrictions(KEY.id, emptyList(), emptyMap(), "")
        runCurrent()
        assertEquals(listOf("NextChat"), source.createdNames)
        assertTrue(source.copiedIds.isEmpty())
        assertTrue(source.updates.isEmpty())
        assertEquals(2, source.operations.size)
        reply.complete(KEY)
        advanceUntilIdle()
        assertFalse(source.vm.state.value.loading)
        assertReleased(source)
    }

    @Test fun callbacksCapturedByOldUiCannotEditAnAlreadyAttachedReplacementSession() = runTest(dispatcher) {
        val source = Source()
        source.vm.attach(FIRST)
        advanceUntilIdle()
        source.session = NEXT_EPOCH
        source.vm.attach(NEXT_EPOCH)
        advanceUntilIdle()
        source.vm.createKey("stale create", expectedSession = FIRST)
        source.vm.copyKey(KEY.id, expectedSession = FIRST)
        source.vm.updateKeyRestrictions(KEY.id, listOf("model"), emptyMap(), "priority", expectedSession = FIRST)
        advanceUntilIdle()
        assertEquals(2, source.operations.size) // Only the two attach reads.
        assertTrue(source.createdNames.isEmpty())
        assertTrue(source.copiedIds.isEmpty())
        assertTrue(source.updates.isEmpty())
        assertReleased(source)
    }

    @Test fun multiFieldUpdateStopsAfterEpochChangesBeforeNextPost() = runTest(dispatcher) {
        val source = Source()
        source.vm.attach(FIRST)
        advanceUntilIdle()
        source.update = { _, _ -> source.session = NEXT_EPOCH; listOf(KEY) }
        var callbacks = 0
        source.vm.updateKeyRestrictions(KEY.id, listOf("model"), mapOf("alias" to "model"), "priority") { callbacks++ }
        advanceUntilIdle()
        assertEquals(listOf(CallerKeyAction.MODELS), source.updates.map { it.first })
        assertEquals(0, callbacks)
        assertEquals("", source.vm.state.value.notice)
        assertReleased(source)
    }

    @Test fun emptyModelsAndAliasesStayUnchangedButTierCanBeCleared() = runTest(dispatcher) {
        val source = Source()
        source.vm.attach(FIRST)
        advanceUntilIdle()
        var callbacks = 0
        source.vm.updateKeyRestrictions(KEY.id, emptyList(), emptyMap(), "") { callbacks++ }
        advanceUntilIdle()
        assertEquals(listOf(CallerKeyAction.TIER), source.updates.map { it.first })
        assertEquals("", source.updates.single().second.serviceTier)
        assertEquals(1, callbacks)
        assertTrue(source.vm.state.value.keys.all { it.secret.isEmpty() })
        assertReleased(source)
    }

    @Test fun unknownCreateRequiresSuccessfulExplicitReadBeforeAnotherWrite() = runTest(dispatcher) {
        val source = Source()
        source.vm.attach(FIRST)
        advanceUntilIdle()
        source.create = { throw ManagementExecutionUnknown() }
        source.vm.createKey("NextChat")
        advanceUntilIdle()
        assertTrue(source.vm.state.value.needsRefresh)
        assertTrue(source.vm.state.value.error.contains("不会自动重复提交"))
        source.vm.createKey("NextChat")
        source.vm.updateKeyRestrictions(KEY.id, emptyList(), emptyMap(), "priority")
        advanceUntilIdle()
        assertEquals(1, source.createdNames.size)
        assertTrue(source.updates.isEmpty())
        source.read = { throw ManagementHttpException(503) }
        source.vm.refresh()
        advanceUntilIdle()
        assertTrue(source.vm.state.value.needsRefresh)
        source.read = { listOf(KEY) }
        source.vm.refresh()
        advanceUntilIdle()
        assertFalse(source.vm.state.value.needsRefresh)
        source.vm.createKey("NextChat")
        advanceUntilIdle()
        assertEquals(2, source.createdNames.size)
        assertReleased(source)
    }

    @Test fun partialRestrictionFailureStopsWithoutAutomaticallyRepeatingPosts() = runTest(dispatcher) {
        val source = Source()
        source.vm.attach(FIRST)
        advanceUntilIdle()
        source.update = { action, _ ->
            if (action == CallerKeyAction.ALIASES) throw ManagementHttpException(409)
            listOf(KEY)
        }
        var callbacks = 0
        source.vm.updateKeyRestrictions(KEY.id, listOf("model"), mapOf("alias" to "model"), "priority") { callbacks++ }
        advanceUntilIdle()
        assertEquals(listOf(CallerKeyAction.MODELS, CallerKeyAction.ALIASES), source.updates.map { it.first })
        assertEquals(0, callbacks)
        assertTrue(source.vm.state.value.needsRefresh)
        assertTrue(source.vm.state.value.error.contains("409"))
        assertReleased(source)
    }

    @Test fun clearingViewModelBeforeLazyBodyStartsCancelsAndReleasesLease() = runTest(dispatcher) {
        val source = Source()
        val store = ViewModelStore()
        store.put("keys", source.vm)
        source.vm.attach(FIRST)
        store.clear()
        advanceUntilIdle()
        assertEquals(0, source.reads)
        assertTrue(source.operations.single().cancelled > 0)
        assertReleased(source)
    }

    private fun assertReleased(source: Source) {
        assertTrue(source.operations.isNotEmpty())
        source.operations.forEach { assertEquals(1, it.closed) }
    }

    private class Source {
        var session: ProviderSessionKey? = FIRST
        val operations = mutableListOf<Operation>()
        val createdNames = mutableListOf<String>()
        val copiedIds = mutableListOf<String>()
        val updates = mutableListOf<Pair<CallerKeyAction, CallerKeyChange>>()
        var reads = 0
        var read: suspend () -> List<RegisteredGatewayKey> = { listOf(KEY) }
        var create: suspend (String) -> RegisteredGatewayKey = { KEY }
        var copy: suspend (String) -> RegisteredGatewayKey = { KEY }
        var update: suspend (CallerKeyAction, CallerKeyChange) -> List<RegisteredGatewayKey> = { _, _ -> listOf(KEY) }
        val vm = GatewayKeysViewModel(sessionReader = { session }, capture = { expected ->
            Operation(this, expected).also { operations += it }
        })
    }

    private class Operation(private val source: Source, private val expected: ProviderSessionKey) : CallerKeySessionOperation {
        var closed = 0
        var cancelled = 0
        override fun ensureCurrent() {
            if (closed > 0 || cancelled > 0 || source.session != expected) throw CancellationException("Stale synthetic session")
        }
        override fun cancel() { cancelled++ }
        override fun close() { closed++ }
        override suspend fun keys(): List<RegisteredGatewayKey> { source.reads++; return source.read() }
        override suspend fun create(name: String): RegisteredGatewayKey { source.createdNames += name; return source.create(name) }
        override suspend fun copy(id: String): RegisteredGatewayKey { source.copiedIds += id; return source.copy(id) }
        override suspend fun update(action: CallerKeyAction, change: CallerKeyChange): List<RegisteredGatewayKey> {
            source.updates += action to change
            return source.update(action, change)
        }
    }

    private companion object {
        val FIRST = ProviderSessionKey(11, 1)
        val NEXT_EPOCH = ProviderSessionKey(11, 2)
        val OTHER_OWNER = ProviderSessionKey(12, 3)
        val KEY = RegisteredGatewayKey("test-key", "NextChat", "sk-synthetic-clipboard-secret")
    }
}

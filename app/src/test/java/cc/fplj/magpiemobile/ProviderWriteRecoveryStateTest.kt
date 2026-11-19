package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ProviderListSnapshot
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderWriteRecoveryStateTest {
    @Test fun pendingCreateUsesAFreshGetAndRetiresTheOldNewProviderDraft() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        val cache = ProviderListCacheViewModel(vmScope, dispatcher)
        val calls = AtomicInteger()
        val pending = ProviderWriteRecoveryState("created-provider")
        val fresh = ProviderListSnapshot()
        try {
            cache.read(SESSION) { calls.incrementAndGet(); ProviderListSnapshot() }
            val read = pending.read(cache, SESSION, 1L) { calls.incrementAndGet(); fresh }
            val resolved = checkNotNull(read.resolve(SESSION, 1L, pending, editorOpen = true))

            assertSame(fresh, read.snapshot)
            assertEquals(2, calls.get())
            assertTrue(resolved.recoveryChecked)
            assertTrue(resolved.retireEditor)
            assertFalse(resolved.recovery.required)
            assertEquals("", resolved.recovery.savedMarker())
        } finally { vmScope.cancel() }
    }

    @Test fun restoredPendingEditAfterRotationClearsOnlyAfterItsFreshHealthyRead() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        val oldUi = CoroutineScope(SupervisorJob() + dispatcher)
        val cache = ProviderListCacheViewModel(vmScope, dispatcher)
        val pending = ProviderWriteRecoveryState("edited-provider")
        val oldFetch = CompletableDeferred<ProviderListSnapshot>()
        val calls = AtomicInteger()
        try {
            val oldRead = oldUi.async {
                pending.read(cache, SESSION, 1L) { calls.incrementAndGet(); oldFetch.await() }
            }
            runCurrent()
            oldRead.cancelAndJoin()
            oldUi.cancel()

            val restored = ProviderWriteRecoveryState.restore(pending.savedMarker())
            assertTrue(restored.required)
            assertEquals("edited-provider", restored.pendingProviderId)
            val read = restored.read(cache, SESSION, 1L) {
                calls.incrementAndGet()
                ProviderListSnapshot()
            }
            val resolved = checkNotNull(read.resolve(SESSION, 1L, restored, editorOpen = false))
            assertEquals(2, calls.get()) // Recovery cannot join the pre-rotation GET.
            assertTrue(resolved.recoveryChecked)
            assertFalse(resolved.retireEditor) // Recreation already discarded ephemeral editor state.
            assertFalse(resolved.recovery.required)

            oldFetch.complete(ProviderListSnapshot(hasFileError = true))
            runCurrent()
            assertFalse(cache.read(SESSION) { error("Old GET replaced fresh cache") }.hasFileError)
        } finally {
            oldUi.cancel()
            vmScope.cancel()
        }
    }

    @Test fun recoveryNetworkFailureKeepsTheMarkerAndDoesNotProduceARetirement() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        val cache = ProviderListCacheViewModel(vmScope, dispatcher)
        var recovery = ProviderWriteRecoveryState("saved")
        try {
            var failure: IOException? = null
            try {
                recovery.read(cache, SESSION, 1L) { throw IOException("synthetic read failure") }
                throw AssertionError("Failed recovery produced a completion")
            } catch (expected: IOException) { failure = expected }
            assertEquals("synthetic read failure", failure?.message)
            assertTrue(recovery.required)
            assertEquals("saved", recovery.savedMarker())

            val retry = recovery.read(cache, SESSION, 2L) { ProviderListSnapshot() }
            val resolved = checkNotNull(retry.resolve(SESSION, 2L, recovery, editorOpen = true))
            assertTrue(resolved.retireEditor)
            recovery = resolved.recovery
            assertFalse(recovery.required)
        } finally { vmScope.cancel() }
    }

    @Test fun fileErrorKeepsPendingEditAndCannotAuthorizeItsOldSnapshot() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        val cache = ProviderListCacheViewModel(vmScope, dispatcher)
        val pending = ProviderWriteRecoveryState("saved")
        try {
            val bad = pending.read(cache, SESSION, 1L) { ProviderListSnapshot(hasFileError = true) }
            val failed = checkNotNull(bad.resolve(SESSION, 1L, pending, editorOpen = true))
            assertSame(pending, failed.recovery)
            assertTrue(failed.recovery.required)
            assertFalse(failed.recoveryChecked)
            assertFalse(failed.retireEditor)

            val good = failed.recovery.read(cache, SESSION, 2L) { ProviderListSnapshot() }
            val resolved = checkNotNull(good.resolve(SESSION, 2L, pending, editorOpen = true))
            assertTrue(resolved.retireEditor)
            assertFalse(resolved.recovery.required)
        } finally { vmScope.cancel() }
    }

    @Test fun canceledRecoveryAwaitDoesNotClearThePendingMarker() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        val cache = ProviderListCacheViewModel(vmScope, dispatcher)
        val pending = ProviderWriteRecoveryState("saved")
        val response = CompletableDeferred<ProviderListSnapshot>()
        var recovery = pending
        var retired = false
        try {
            val observer = async {
                val read = pending.read(cache, SESSION, 1L) { response.await() }
                val resolved = checkNotNull(read.resolve(SESSION, 1L, recovery, editorOpen = true))
                recovery = resolved.recovery
                retired = resolved.retireEditor
            }
            runCurrent()
            observer.cancelAndJoin()
            response.complete(ProviderListSnapshot())
            runCurrent()

            assertTrue(observer.isCancelled)
            assertSame(pending, recovery)
            assertTrue(recovery.required)
            assertFalse(retired)
        } finally { vmScope.cancel() }
    }

    @Test fun completedOldEpochCannotClearAMarkerBeforeTheNewEpochFreshRead() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        val cache = ProviderListCacheViewModel(vmScope, dispatcher)
        val pending = ProviderWriteRecoveryState("saved")
        val calls = AtomicInteger()
        try {
            val old = pending.read(cache, SESSION, 4L) { calls.incrementAndGet(); ProviderListSnapshot() }
            // Main may receive the old completion after a same-owner reconnect became READY.
            assertNull(old.resolve(NEXT_EPOCH, 4L, pending, editorOpen = true))
            assertTrue(pending.required)

            val current = pending.read(cache, NEXT_EPOCH, 5L) { calls.incrementAndGet(); ProviderListSnapshot() }
            val resolved = checkNotNull(current.resolve(NEXT_EPOCH, 5L, pending, editorOpen = true))
            assertEquals(2, calls.get())
            assertTrue(resolved.retireEditor)
            assertFalse(resolved.recovery.required)
        } finally { vmScope.cancel() }
    }

    @Test fun oldReadCannotClearANewerOperationOrAnotherPendingProvider() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        val cache = ProviderListCacheViewModel(vmScope, dispatcher)
        val pending = ProviderWriteRecoveryState("first")
        try {
            val old = pending.read(cache, SESSION, 4L) { ProviderListSnapshot() }
            assertNull(old.resolve(SESSION, 5L, pending, editorOpen = true))
            assertNull(old.resolve(SESSION, 4L, ProviderWriteRecoveryState("second"), editorOpen = true))
            assertNull(old.resolve(null, 4L, pending, editorOpen = true))
            assertTrue(pending.required)
        } finally { vmScope.cancel() }
    }

    @Test fun ordinaryRefreshOfAnOpenUnsavedDraftNeverRetiresIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        val cache = ProviderListCacheViewModel(vmScope, dispatcher)
        val ordinary = ProviderWriteRecoveryState.restore("")
        try {
            val read = ordinary.read(cache, SESSION, 2L, force = true) { ProviderListSnapshot() }
            val resolved = checkNotNull(read.resolve(SESSION, 2L, ordinary, editorOpen = true))
            assertFalse(resolved.recoveryChecked)
            assertFalse(resolved.retireEditor)
            assertSame(ordinary, resolved.recovery)
        } finally { vmScope.cancel() }
    }

    private companion object {
        val SESSION = ProviderSessionKey(11L, 1L)
        val NEXT_EPOCH = ProviderSessionKey(11L, 2L)
    }
}

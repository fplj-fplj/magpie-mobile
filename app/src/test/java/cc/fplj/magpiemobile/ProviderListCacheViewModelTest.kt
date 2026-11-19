package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ProviderListSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderListCacheViewModelTest {
    private val fetchCount = AtomicInteger(0)
    private var returnFileError = false

    private fun fetchSnapshot(): ProviderListSnapshot {
        fetchCount.incrementAndGet()
        return ProviderListSnapshot(emptyList(), returnFileError)
    }

    @Test
    fun cacheReusedForSameOwnerWhenHealthy() = runTest {
        val vm = ProviderListCacheViewModel(readScope = this, readDispatcher = Dispatchers.Unconfined)
        val first = vm.read(1L, force = false) { fetchSnapshot() }
        assertEquals(1, fetchCount.get())
        assertFalse(first.hasFileError)

        val second = vm.read(1L, force = false) { fetchSnapshot() }
        assertEquals(1, fetchCount.get()) // No new fetch
        assertSame(first, second)
    }

    @Test
    fun concurrentReadsJoinSingleInflight() = runTest {
        val scheduler = TestCoroutineScheduler()
        val scope = TestScope(StandardTestDispatcher(scheduler))
        val vm = ProviderListCacheViewModel(readScope = scope, readDispatcher = StandardTestDispatcher(scheduler))

        val blockFetch = CompletableDeferred<Unit>()
        val a = scope.async {
            vm.read(1L, force = false) {
                blockFetch.await()
                fetchSnapshot()
            }
        }
        val b = scope.async {
            vm.read(1L, force = false) {
                fetchSnapshot()
            }
        }

        scheduler.advanceUntilIdle()
        blockFetch.complete(Unit)
        scheduler.advanceUntilIdle()

        val resA = a.await()
        val resB = b.await()
        assertEquals(1, fetchCount.get())
        assertSame(resA, resB)
    }

    @Test
    fun forceRefreshBypassesCacheAndInvalidatesOnError() = runTest {
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined)
        val vm = ProviderListCacheViewModel(readScope = scope, readDispatcher = Dispatchers.Unconfined)
        val first = vm.read(1L, force = false) { fetchSnapshot() }
        assertEquals(1, fetchCount.get())

        val refreshed = vm.read(1L, force = true) { fetchSnapshot() }
        assertEquals(2, fetchCount.get())

        // Now simulate a failing force refresh
        try {
            vm.read(1L, force = true) {
                fetchCount.incrementAndGet()
                throw RuntimeException("Network down")
            }
            fail("Expected exception")
        } catch (_: Exception) {}

        assertEquals(3, fetchCount.get())

        // Cache must not contain the old healthy one anymore
        val third = vm.read(1L, force = false) { fetchSnapshot() }
        assertEquals(4, fetchCount.get())
    }

    @Test
    fun fileErrorSnapshotIsNotCachedAsHealthy() = runTest {
        val vm = ProviderListCacheViewModel(readScope = this, readDispatcher = Dispatchers.Unconfined)
        returnFileError = true

        val errorSnap = vm.read(1L, force = false) { fetchSnapshot() }
        assertEquals(1, fetchCount.get())
        assertTrue(errorSnap.hasFileError)

        returnFileError = false
        // Next read with force=false must not reuse the error snapshot
        val healthySnap = vm.read(1L, force = false) { fetchSnapshot() }
        assertEquals(2, fetchCount.get())
        assertFalse(healthySnap.hasFileError)
    }

    @Test
    fun ownerChangeInvalidatesOldCache() = runTest {
        val vm = ProviderListCacheViewModel(readScope = this, readDispatcher = Dispatchers.Unconfined)
        val snap1 = vm.read(1L, force = false) { fetchSnapshot() }
        assertEquals(1, fetchCount.get())

        val snap2 = vm.read(2L, force = false) { fetchSnapshot() }
        assertEquals(2, fetchCount.get())
    }

    @Test
    fun explicitInvalidateClearsCache() = runTest {
        val vm = ProviderListCacheViewModel(readScope = this, readDispatcher = Dispatchers.Unconfined)
        vm.read(1L, force = false) { fetchSnapshot() }
        assertEquals(1, fetchCount.get())

        vm.invalidate()

        vm.read(1L, force = false) { fetchSnapshot() }
        assertEquals(2, fetchCount.get())
    }

    @Test
    fun callerCancellationDoesNotAbortInflightOrCorruptCache() = runTest {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val vm = ProviderListCacheViewModel(readScope = scope, readDispatcher = Dispatchers.Default)

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val callerJob = scope.launch {
            vm.read(1L, force = false) {
                entered.complete(Unit)
                release.await()
                fetchSnapshot()
            }
        }

        entered.await()
        callerJob.cancelAndJoin() // Cancel the caller

        // Release the background fetch
        release.complete(Unit)

        // Read again with same owner - it should join or get the completed cached snapshot
        var res: ProviderListSnapshot? = null
        for (i in 0 until 50) {
            delay(20)
            try {
                res = vm.read(1L, force = false) { fetchSnapshot() }
                if (res != null) break
            } catch (_: Exception) {}
        }
        assertNotNull(res)
        assertEquals(1, fetchCount.get())

        scope.cancel()
    }

    @Test
    fun recoveryAfterRotationWaitsForActualWriteExitAndReadsFreshDespiteWarmCache() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val worker = CoroutineScope(SupervisorJob() + dispatcher)
        val vm = ProviderListCacheViewModel(worker, dispatcher)
        val beforeWrite = ProviderListSnapshot(emptyList(), false)
        val afterWrite = ProviderListSnapshot(emptyList(), false)
        val writeEntered = CompletableDeferred<Unit>()
        val releaseTransport = CompletableDeferred<Unit>()
        val freshGetEntered = CompletableDeferred<Unit>()
        val releaseFreshGet = CompletableDeferred<Unit>()
        val getCount = AtomicInteger()
        try {
            val warmed = async { vm.read(1L) { getCount.incrementAndGet(); beforeWrite } }
            runCurrent()
            assertSame(beforeWrite, warmed.await())
            assertEquals(1, getCount.get())

            val writer = worker.launch {
                val barrier = vm.beginWrite(1L)
                try {
                    // A blocking transport has not exited merely because its UI caller rotated.
                    withContext(NonCancellable) {
                        writeEntered.complete(Unit)
                        releaseTransport.await()
                    }
                } finally { barrier.finish() }
            }
            runCurrent()
            writeEntered.await()
            writer.cancel()
            runCurrent()
            assertFalse(writer.isCompleted)

            val recovery = async {
                vm.readForRecovery(1L) {
                    getCount.incrementAndGet()
                    freshGetEntered.complete(Unit)
                    releaseFreshGet.await()
                    afterWrite
                }
            }
            runCurrent()
            assertFalse(recovery.isCompleted)
            assertFalse(freshGetEntered.isCompleted)
            assertEquals(1, getCount.get())

            releaseTransport.complete(Unit)
            runCurrent()
            assertTrue(writer.isCompleted)
            assertTrue(freshGetEntered.isCompleted)
            assertEquals(2, getCount.get())
            assertFalse(recovery.isCompleted)
            releaseFreshGet.complete(Unit)
            runCurrent()
            assertSame(afterWrite, recovery.await())
            assertSame(afterWrite, vm.read(1L) { error("Expected post-write cache") })
        } finally { worker.cancel() }
    }

    @Test
    fun preWriteInflightCannotReturnHealthyConfirmationOrPopulatePostWriteCache() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val worker = CoroutineScope(SupervisorJob() + dispatcher)
        val vm = ProviderListCacheViewModel(worker, dispatcher)
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val freshStarted = CompletableDeferred<Unit>()
        val releaseFresh = CompletableDeferred<Unit>()
        val fresh = ProviderListSnapshot(emptyList(), false)
        var recoveryRequired = true
        var oldReadApplied = false
        try {
            val oldObserver = async {
                runCatching {
                    vm.read(1L) {
                        oldStarted.complete(Unit)
                        releaseOld.await()
                        ProviderListSnapshot(emptyList(), false)
                    }
                }.onSuccess {
                    oldReadApplied = true
                    recoveryRequired = false
                }
            }
            runCurrent()
            oldStarted.await()
            val barrier = vm.beginWrite(1L)
            val recovery = async {
                vm.readForRecovery(1L) {
                    freshStarted.complete(Unit)
                    releaseFresh.await()
                    fresh
                }.also { recoveryRequired = false }
            }
            runCurrent()
            releaseOld.complete(Unit)
            runCurrent()
            assertTrue(oldObserver.await().exceptionOrNull() is CancellationException)
            assertFalse(oldReadApplied)
            assertTrue(recoveryRequired)
            assertFalse(freshStarted.isCompleted)

            barrier.finish()
            runCurrent()
            assertTrue(freshStarted.isCompleted)
            assertTrue(recoveryRequired)
            releaseFresh.complete(Unit)
            runCurrent()
            assertSame(fresh, recovery.await())
            assertFalse(recoveryRequired)
            assertSame(fresh, vm.read(1L) { error("Stale GET must not overwrite fresh cache") })
        } finally { worker.cancel() }
    }

    @Test
    fun canceledRecoveryObserverDoesNotReleaseWriteOrReuseCachedSnapshot() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val worker = CoroutineScope(SupervisorJob() + dispatcher)
        val vm = ProviderListCacheViewModel(worker, dispatcher)
        val getCount = AtomicInteger()
        try {
            val warm = async { vm.read(1L) { getCount.incrementAndGet(); ProviderListSnapshot(emptyList(), false) } }
            runCurrent()
            warm.await()
            val barrier = vm.beginWrite(1L)
            val rotatedObserver = async {
                vm.readForRecovery(1L) { error("Canceled recovery must not start a GET") }
            }
            runCurrent()
            rotatedObserver.cancelAndJoin()
            val replacement = async {
                vm.readForRecovery(1L) { getCount.incrementAndGet(); ProviderListSnapshot(emptyList(), false) }
            }
            runCurrent()
            assertFalse(replacement.isCompleted)
            assertEquals(1, getCount.get())
            barrier.finish()
            barrier.finish()
            runCurrent()
            assertFalse(replacement.await().hasFileError)
            assertEquals(2, getCount.get())
        } finally { worker.cancel() }
    }

    @Test
    fun recoveryWaitsForEveryRegisteredWriteAndForANewWriteRegisteredWhileWaiting() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val worker = CoroutineScope(SupervisorJob() + dispatcher)
        val vm = ProviderListCacheViewModel(worker, dispatcher)
        val getCount = AtomicInteger()
        try {
            val first = vm.beginWrite(1L)
            val recovery = async {
                vm.readForRecovery(1L) { getCount.incrementAndGet(); ProviderListSnapshot(emptyList(), false) }
            }
            runCurrent()
            val second = vm.beginWrite(1L)
            first.finish()
            first.finish()
            runCurrent()
            assertFalse(recovery.isCompleted)
            assertEquals(0, getCount.get())
            second.finish()
            runCurrent()
            assertFalse(recovery.await().hasFileError)
            assertEquals(1, getCount.get())
        } finally { worker.cancel() }
    }

    @Test
    fun recoveryAlwaysStartsFreshAndItsFailureCannotRestorePreviousHealthyCache() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val worker = CoroutineScope(SupervisorJob() + dispatcher)
        val vm = ProviderListCacheViewModel(worker, dispatcher)
        val getCount = AtomicInteger()
        var recoveryRequired = true
        try {
            val warm = async { vm.read(1L) { getCount.incrementAndGet(); ProviderListSnapshot(emptyList(), false) } }
            runCurrent()
            warm.await()
            val failed = async {
                runCatching {
                    vm.readForRecovery(1L) {
                        getCount.incrementAndGet()
                        throw IllegalStateException("Fresh synthetic GET failed")
                    }
                }.onSuccess { recoveryRequired = false }
            }
            runCurrent()
            assertTrue(failed.await().isFailure)
            assertTrue(recoveryRequired)
            assertEquals(2, getCount.get())
            val fileError = async {
                vm.readForRecovery(1L) { getCount.incrementAndGet(); ProviderListSnapshot(emptyList(), true) }
            }
            runCurrent()
            assertTrue(fileError.await().hasFileError)
            val healthy = async {
                vm.read(1L) { getCount.incrementAndGet(); ProviderListSnapshot(emptyList(), false) }
            }
            runCurrent()
            assertFalse(healthy.await().hasFileError)
            assertEquals(4, getCount.get())
        } finally { worker.cancel() }
    }

    @Test
    fun recoveryDoesNotJoinAReadStartedAfterWriteExitButBeforeRecovery() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val worker = CoroutineScope(SupervisorJob() + dispatcher)
        val vm = ProviderListCacheViewModel(worker, dispatcher)
        val releaseOrdinary = CompletableDeferred<Unit>()
        val ordinaryEntered = CompletableDeferred<Unit>()
        val fresh = ProviderListSnapshot(emptyList(), false)
        val getCount = AtomicInteger()
        try {
            vm.beginWrite(1L).finish()
            val ordinary = async {
                runCatching {
                    vm.read(1L) {
                        getCount.incrementAndGet()
                        ordinaryEntered.complete(Unit)
                        releaseOrdinary.await()
                        ProviderListSnapshot(emptyList(), false)
                    }
                }
            }
            runCurrent()
            ordinaryEntered.await()
            val recovery = async { vm.readForRecovery(1L) { getCount.incrementAndGet(); fresh } }
            runCurrent()
            assertSame(fresh, recovery.await())
            assertEquals(2, getCount.get())
            releaseOrdinary.complete(Unit)
            runCurrent()
            assertTrue(ordinary.await().exceptionOrNull() is CancellationException)
            assertSame(fresh, vm.read(1L) { error("Fresh recovery cache should be retained") })
        } finally { worker.cancel() }
    }

    @Test
    fun switchingOwnerDoesNotWaitForOldWriteOrApplyOldRecoveryAfterItExits() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val worker = CoroutineScope(SupervisorJob() + dispatcher)
        val vm = ProviderListCacheViewModel(worker, dispatcher)
        val ownerTwo = ProviderListSnapshot(emptyList(), false)
        val oldFetchCount = AtomicInteger()
        try {
            val oldWrite = vm.beginWrite(1L)
            val oldRecovery = async {
                runCatching {
                    vm.readForRecovery(1L) {
                        oldFetchCount.incrementAndGet()
                        ProviderListSnapshot(emptyList(), false)
                    }
                }
            }
            runCurrent()
            val newOwner = async { vm.read(2L) { ownerTwo } }
            runCurrent()
            assertSame(ownerTwo, newOwner.await())
            oldWrite.finish()
            runCurrent()
            assertTrue(oldRecovery.await().exceptionOrNull() is CancellationException)
            assertEquals(0, oldFetchCount.get())
            assertSame(ownerTwo, vm.read(2L) { error("Old recovery cannot overwrite new owner") })
        } finally { worker.cancel() }
    }

    @Test
    fun ordinaryReadersWaitForWriteAndStillCoalesceTheirPostWriteGet() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val worker = CoroutineScope(SupervisorJob() + dispatcher)
        val vm = ProviderListCacheViewModel(worker, dispatcher)
        val releaseGet = CompletableDeferred<Unit>()
        val getCount = AtomicInteger()
        val snapshot = ProviderListSnapshot(emptyList(), false)
        try {
            val barrier = vm.beginWrite(1L)
            val first = async {
                vm.read(1L) {
                    getCount.incrementAndGet()
                    releaseGet.await()
                    snapshot
                }
            }
            val second = async { vm.read(1L) { error("Must join the first post-write read") } }
            runCurrent()
            assertEquals(0, getCount.get())
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            barrier.finish()
            runCurrent()
            assertEquals(1, getCount.get())
            releaseGet.complete(Unit)
            runCurrent()
            assertSame(snapshot, first.await())
            assertSame(snapshot, second.await())
        } finally { worker.cancel() }
    }

    @Test
    fun lateOldEntrypointsCannotSupersedeNewOwnerInflightOrItsHealthyCache() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val worker = CoroutineScope(SupervisorJob() + dispatcher)
        val vm = ProviderListCacheViewModel(worker, dispatcher)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fresh = ProviderListSnapshot(emptyList(), false)
        val freshGetCount = AtomicInteger()
        val staleGetCount = AtomicInteger()
        try {
            val newRead = async {
                vm.read(2L) {
                    freshGetCount.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                    fresh
                }
            }
            runCurrent()
            entered.await()

            suspend fun rejectOldEntrypoints() {
                assertTrue(runCatching { vm.beginWrite(1L) }.exceptionOrNull() is CancellationException)
                assertTrue(runCatching {
                    vm.read(1L) { staleGetCount.incrementAndGet(); ProviderListSnapshot(emptyList(), false) }
                }.exceptionOrNull() is CancellationException)
                assertTrue(runCatching {
                    vm.readForRecovery(1L) {
                        staleGetCount.incrementAndGet()
                        ProviderListSnapshot(emptyList(), false)
                    }
                }.exceptionOrNull() is CancellationException)
                assertEquals(0, staleGetCount.get())
            }

            rejectOldEntrypoints()
            assertFalse(newRead.isCompleted)
            assertEquals(1, freshGetCount.get())
            // A new-owner reader must still join the original GET after every stale entry failed.
            val joined = async { vm.read(2L) { error("Old owner must not detach new inflight") } }
            runCurrent()
            assertFalse(joined.isCompleted)
            release.complete(Unit)
            runCurrent()
            assertSame(fresh, newRead.await())
            assertSame(fresh, joined.await())

            rejectOldEntrypoints()
            assertSame(fresh, vm.read(2L) { error("Old owner must not clear new healthy cache") })
            assertEquals(1, freshGetCount.get())
        } finally { worker.cancel() }
    }
}

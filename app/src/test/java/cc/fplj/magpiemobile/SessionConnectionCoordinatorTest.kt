package cc.fplj.magpiemobile

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionConnectionCoordinatorTest {
    @Test fun lateSuccessAcrossSameOwnerSuspendCannotReplaceTheRequiredNewConnection() {
        assertSameOwnerRecovery(oldFails = false)
    }

    @Test fun lateFailureAcrossSameOwnerSuspendCannotReplaceTheRequiredNewConnection() {
        assertSameOwnerRecovery(oldFails = true)
    }

    private fun assertSameOwnerRecovery(oldFails: Boolean) {
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val newStarted = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)
        Harness(operation = { _, input, _ ->
            if (input == "old") {
                oldStarted.countDown()
                await(releaseOld)
                // Deliberately ignore the epoch guard: even an uncooperative old operation's
                // final success/failure must be rejected by the production coordinator.
                if (oldFails) throw IOException("old failure")
                "old result"
            } else {
                newStarted.countDown()
                await(releaseNew)
                "new result"
            }
        }).use { harness ->
            val binding = harness.attach(7)
            assertTrue(harness.running(binding, "old"))
            await(oldStarted)
            harness.suspend(binding)
            assertTrue(harness.running(binding, "new"))
            repeat(3) { assertFalse(harness.running(binding, "duplicate")) }
            assertEquals(listOf("old"), harness.calls.toList())
            assertEquals("connecting", harness.state())

            releaseOld.countDown()
            await(newStarted)
            assertEquals(listOf("old", "new"), harness.calls.toList())
            assertEquals("connecting", harness.state())
            assertTrue(harness.terminalEvents.isEmpty())
            // Finishing A cannot clear B's connecting marker and permit another provisioning.
            assertFalse(harness.running(binding, "duplicate after old finally"))
            releaseNew.countDown()
            harness.awaitIdle()
            assertEquals("ready:new result", harness.state())
            assertEquals(listOf("ready:new result"), harness.terminalEvents.toList())
            assertEquals(1, harness.maxConcurrent.get())
            assertFalse(harness.running(binding, "duplicate after ready"))
        }
    }

    @Test fun oldOperationFinishingDuringBackoffDoesNotRequireManualRetry() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        Harness(operation = { _, input, _ ->
            if (input == "old") {
                started.countDown()
                await(release)
                throw IOException("old failure")
            }
            input
        }).use { harness ->
            val binding = harness.attach(7)
            harness.running(binding, "old")
            await(started)
            harness.suspend(binding)
            release.countDown()
            harness.awaitIdle()
            assertEquals("disconnected", harness.state())
            assertTrue(harness.terminalEvents.isEmpty())
            assertTrue(harness.running(binding, "recovered"))
            harness.awaitIdle()
            assertEquals(listOf("old", "recovered"), harness.calls.toList())
            assertEquals("ready:recovered", harness.state())
        }
    }

    @Test fun repeatedRunningAfterFailureRequiresAnExplicitRetryAndNeverReplaysAutomatically() {
        Harness(operation = { _, input, _ ->
            if (input == "first") throw IOException("first failure")
            input
        }).use { harness ->
            val binding = harness.attach(7)
            assertTrue(harness.running(binding, "first"))
            harness.awaitIdle()
            assertEquals("error:first failure", harness.state())
            assertFalse(harness.running(binding, "automatic retry"))
            assertTrue(harness.retry(binding, "explicit retry"))
            harness.awaitIdle()
            assertEquals(listOf("first", "explicit retry"), harness.calls.toList())
            assertEquals("ready:explicit retry", harness.state())
            assertFalse(harness.retry(binding, "duplicate after ready"))
        }
    }

    @Test fun currentCancellationNeverBecomesAnErrorAndLeavesNextRunningRequestAvailable() {
        Harness(operation = { _, input, _ ->
            if (input == "cancel") throw CancellationException("synthetic cancellation")
            input
        }).use { harness ->
            val binding = harness.attach(7)
            harness.running(binding, "cancel")
            harness.awaitIdle()
            assertEquals("disconnected", harness.state())
            assertEquals(listOf("cancelled"), harness.terminalEvents.toList())
            assertTrue(harness.running(binding, "recovered"))
            harness.awaitIdle()
            assertEquals("ready:recovered", harness.state())
            assertFalse(harness.terminalEvents.any { it.startsWith("error:") })
        }
    }

    @Test fun repeatedSuspendsDropIntermediatePendingEpochsAndKeepOnlyLatestRunningRequest() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        Harness(operation = { _, input, _ ->
            if (input == "old") {
                started.countDown()
                await(release)
            }
            input
        }).use { harness ->
            val binding = harness.attach(7)
            harness.running(binding, "old")
            await(started)
            harness.suspend(binding)
            assertTrue(harness.running(binding, "intermediate"))
            harness.suspend(binding)
            assertTrue(harness.running(binding, "latest"))
            release.countDown()
            harness.awaitIdle()
            assertEquals(listOf("old", "latest"), harness.calls.toList())
            assertEquals(listOf("ready:latest"), harness.terminalEvents.toList())
        }
    }

    @Test fun stopDropsAQueuedReconnectAndRejectsLateSuccessOrFailure() {
        for (oldFails in listOf(false, true)) {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            Harness(operation = { _, input, _ ->
                started.countDown()
                await(release)
                if (oldFails) throw IOException("old failure")
                input
            }).use { harness ->
                val binding = harness.attach(7)
                harness.running(binding, "old")
                await(started)
                harness.suspend(binding)
                harness.running(binding, "pending")
                harness.disconnect(binding)
                release.countDown()
                harness.awaitIdle()
                assertEquals(listOf("old"), harness.calls.toList())
                assertEquals("disconnected", harness.state())
                assertTrue(harness.terminalEvents.isEmpty())
                assertFalse(harness.running(binding, "after stop"))
            }
        }
    }

    @Test fun replacingBindingEvenWithSameOwnerInvalidatesOldAttemptAndOldDisconnect() {
        for (newOwner in listOf(7L, 8L)) {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            Harness(operation = { _, input, _ ->
                if (input == "old") {
                    started.countDown()
                    await(release)
                }
                input
            }).use { harness ->
                val old = harness.attach(7)
                harness.running(old, "old")
                await(started)
                val current = harness.attach(newOwner)
                assertTrue(harness.running(current, "new binding"))
                harness.suspend(old)
                harness.disconnect(old)
                assertFalse(harness.running(old, "old owner"))
                release.countDown()
                harness.awaitIdle()
                assertEquals(listOf("old", "new binding"), harness.calls.toList())
                assertEquals(listOf("ready:new binding"), harness.terminalEvents.toList())
                assertEquals("ready:new binding", harness.state())
            }
        }
    }

    @Test fun epochGuardRejectsAnOldAttemptBeforeItsNextAuthenticationStep() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val staleSteps = AtomicInteger()
        Harness(operation = { _, input, ensureCurrent ->
            if (input == "old") {
                started.countDown()
                await(release)
                ensureCurrent()
                staleSteps.incrementAndGet()
            }
            input
        }).use { harness ->
            val binding = harness.attach(7)
            harness.running(binding, "old")
            await(started)
            harness.suspend(binding)
            harness.running(binding, "new")
            release.countDown()
            harness.awaitIdle()
            assertEquals(0, staleSteps.get())
            assertEquals(listOf("ready:new"), harness.terminalEvents.toList())
        }
    }

    @Test fun provisioningRemainsSerialEvenWithAParallelExecutor() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ready = CountDownLatch(1)
        Harness(
            executor = Executors.newFixedThreadPool(3),
            operation = { _, input, _ ->
                if (input == "old") {
                    started.countDown()
                    await(release)
                }
                input
            },
            onReady = { ready.countDown() },
        ).use { harness ->
            val binding = harness.attach(7)
            harness.running(binding, "old")
            await(started)
            harness.suspend(binding)
            harness.running(binding, "new")
            release.countDown()
            await(ready)
            assertEquals(listOf("old", "new"), harness.calls.toList())
            assertEquals(1, harness.maxConcurrent.get())
            assertEquals("ready:new", harness.state())
        }
    }

    @Test fun cancellationBeforeDispatchNeverStartsAuthenticationAndDuplicateRunningQueuesOnce() {
        val tasks = CopyOnWriteArrayList<Runnable>()
        val calls = AtomicInteger()
        val lock = Any()
        val binding = Any()
        var running = true
        val coordinator = SessionConnectionCoordinator<Any, String, String>(
            lock = lock,
            executor = Executor { tasks += it },
            isRunning = { running },
            authenticate = { _, input, _ -> calls.incrementAndGet(); input },
            onConnecting = {},
            onReady = { _, _ -> },
            onError = { _, _ -> },
            onCancelled = {},
        )
        coordinator.attach(binding)
        assertTrue(coordinator.connect(binding, "first"))
        assertFalse(coordinator.connect(binding, "duplicate"))
        assertEquals(1, tasks.size)
        running = false
        coordinator.suspend(binding)
        tasks.removeAt(0).run()
        assertEquals(0, calls.get())
        running = true
        assertTrue(coordinator.connect(binding, "new"))
        assertEquals(1, tasks.size)
        tasks.removeAt(0).run()
        assertEquals(1, calls.get())
        assertFalse(coordinator.connect(binding, "duplicate after ready"))
    }

    private class Binding(val owner: Long)

    /** Same coordinator and shared-lock callbacks used by NativeCoreSession; no network or key. */
    private class Harness(
        private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
        operation: (Binding, String, () -> Unit) -> String,
        onReady: () -> Unit = {},
    ) : Closeable {
        private val lock = Any()
        private var binding: Binding? = null
        private var running: Binding? = null
        private var status = "disconnected"
        private val active = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        val calls = CopyOnWriteArrayList<String>()
        val terminalEvents = CopyOnWriteArrayList<String>()
        private val coordinator = SessionConnectionCoordinator<Binding, String, String>(
            lock = lock,
            executor = executor,
            isRunning = { binding === it && running === it },
            authenticate = { current, input, ensureCurrent ->
                assertFalse("Authentication ran while holding the binding lock", Thread.holdsLock(lock))
                calls += input
                val count = active.incrementAndGet()
                maxConcurrent.updateAndGet { maxOf(it, count) }
                try { operation(current, input, ensureCurrent) } finally { active.decrementAndGet() }
            },
            onConnecting = {
                assertTrue(Thread.holdsLock(lock))
                status = "connecting"
            },
            onReady = { _, result ->
                assertTrue(Thread.holdsLock(lock))
                status = "ready:$result"
                terminalEvents += status
                onReady()
            },
            onError = { _, error ->
                assertTrue(Thread.holdsLock(lock))
                status = "error:${error.message}"
                terminalEvents += status
            },
            onCancelled = {
                assertTrue(Thread.holdsLock(lock))
                status = "disconnected"
                terminalEvents += "cancelled"
            },
        )

        fun attach(owner: Long): Binding = synchronized(lock) {
            Binding(owner).also {
                binding = it
                running = null
                coordinator.attach(it)
                status = "disconnected"
            }
        }

        fun running(current: Binding, input: String): Boolean = synchronized(lock) {
            if (binding !== current) return false
            running = current
            coordinator.connect(current, input)
        }

        fun retry(current: Binding, input: String): Boolean = synchronized(lock) {
            coordinator.connect(current, input, retry = true)
        }

        fun suspend(current: Binding) = synchronized(lock) {
            if (binding !== current) return
            running = null
            coordinator.suspend(current)
            status = "disconnected"
        }

        fun disconnect(current: Binding) = synchronized(lock) {
            if (binding !== current) return
            coordinator.disconnect(current)
            binding = null
            running = null
            status = "disconnected"
        }

        fun state(): String = synchronized(lock) { status }

        fun awaitIdle() {
            // In the default single-thread fixture this barrier follows the entire drain loop.
            executor.submit(Runnable {}).get(5, TimeUnit.SECONDS)
            assertEquals(0, active.get())
        }

        override fun close() {
            synchronized(lock) { binding?.let { disconnect(it) } }
            executor.shutdownNow()
            assertTrue("Authentication fixture did not terminate", executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private companion object {
        fun await(latch: CountDownLatch) {
            assertTrue("Authentication step did not reach its latch", latch.await(5, TimeUnit.SECONDS))
        }
    }
}

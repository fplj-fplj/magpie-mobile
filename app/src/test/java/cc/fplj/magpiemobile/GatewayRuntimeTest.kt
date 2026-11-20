package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.GatewayEngineController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class GatewayRuntimeTest {
    @Before fun reset() {
        GatewayRuntime.stoppedWithoutService()
        check(GatewayRuntime.requestStart())
    }

    @After fun cleanup() { GatewayRuntime.stoppedWithoutService() }

    @Test fun currentServiceUpdatesTheSameObservableState() {
        val lease = checkNotNull(GatewayRuntime.tryClaimStart())
        val owner = lease.owner
        assertNotNull(GatewayRuntime.publish(owner,
            GatewayEngineController.Status(GatewayEngineController.Phase.RUNNING, "ports ready")))
        assertEquals(GatewayEngineController.Phase.RUNNING, GatewayRuntime.status.value.phase)
        assertEquals("ports ready", GatewayRuntime.status.value.message)
    }

    @Test fun lateCancelledServiceCannotOverwriteNewRun() {
        val old = checkNotNull(GatewayRuntime.tryClaimStart())
        assertTrue(GatewayRuntime.beginCleanup(old.owner))
        assertTrue(GatewayRuntime.cleanupCompleted(old))
        assertTrue(GatewayRuntime.requestStart())
        val current = checkNotNull(GatewayRuntime.tryClaimStart())
        GatewayRuntime.publish(current.owner, GatewayEngineController.Status(GatewayEngineController.Phase.RUNNING, "new run"))
        assertNull(GatewayRuntime.publish(old.owner,
            GatewayEngineController.Status(GatewayEngineController.Phase.FAILED, "old failure")))
        assertEquals("new run", GatewayRuntime.status.value.message)
    }

    @Test fun failureRemainsVisibleWhenServiceCleansUp() {
        val lease = checkNotNull(GatewayRuntime.tryClaimStart())
        val owner = lease.owner
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.FAILED, "exit=1"))
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.STOPPED, "cleanup"))
        assertEquals("exit=1", GatewayRuntime.status.value.message)
        GatewayRuntime.stoppedWithoutService()
        assertEquals(GatewayEngineController.Phase.STOPPED, GatewayRuntime.status.value.phase)
    }

    @Test fun stopWaitsForCleanupAndBlocksStart() {
        val lease = checkNotNull(GatewayRuntime.tryClaimStart())
        val owner = lease.owner
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.RUNNING, "ready"))
        GatewayRuntime.requestStop()
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.STOPPED, "only cancelled"))
        assertEquals(true, GatewayRuntime.stopping.value)
        assertFalse(GatewayRuntime.requestStart())
        GatewayRuntime.cleanupCompleted(lease)
        assertEquals(false, GatewayRuntime.stopping.value)
        assertEquals(GatewayEngineController.Phase.STOPPED, GatewayRuntime.status.value.phase)
        assertEquals(true, GatewayRuntime.requestStart())
    }

    @Test fun diagnosticsRedactTheNativeWebLinkKey() {
        val lease = checkNotNull(GatewayRuntime.tryClaimStart())
        val owner = lease.owner
        val key = "secretAdminLinkValue"
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.FAILED,
            "http://127.0.0.1/?k=" + key, outputTail = "http://127.0.0.1/?k=" + key))
        assertFalse(GatewayRuntime.status.value.message.contains(key))
        assertFalse(GatewayRuntime.status.value.outputTail.contains(key))
    }

    @Test fun stoppingMergesFinalDiagnosticsWithoutMovingPhaseOrRemovingCleanupBarrier() {
        val lease = checkNotNull(GatewayRuntime.tryClaimStart())
        val owner = lease.owner
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.RUNNING,
            "ready", restartCount = 2, outputTail = "before stop\n"))
        GatewayRuntime.requestStop()
        val pending = GatewayRuntime.status.value
        assertNull(GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.STOPPED,
            "process finally exited", outputTail = "last output\n", exitCode = 137)))
        val waiting = GatewayRuntime.status.value
        assertEquals(pending.phase, waiting.phase)
        assertEquals(pending.message, waiting.message)
        assertEquals(2, waiting.restartCount)
        assertEquals("last output\n", waiting.outputTail)
        assertEquals(137, waiting.exitCode)
        assertTrue(GatewayRuntime.stopping.value)
        assertFalse(GatewayRuntime.requestStart())
        GatewayRuntime.cleanupCompleted(lease)
        val stopped = GatewayRuntime.status.value
        assertEquals(GatewayEngineController.Phase.STOPPED, stopped.phase)
        assertEquals("last output\n", stopped.outputTail)
        assertEquals(137, stopped.exitCode)
        assertFalse(GatewayRuntime.stopping.value)
        assertTrue(GatewayRuntime.requestStart())
        assertEquals("", GatewayRuntime.status.value.outputTail)
        assertNull(GatewayRuntime.status.value.exitCode)
    }

    @Test fun oldOwnerCannotMergeDiagnosticsOrCompleteANewerCleanup() {
        val old = checkNotNull(GatewayRuntime.tryClaimStart())
        assertTrue(GatewayRuntime.beginCleanup(old.owner))
        assertTrue(GatewayRuntime.cleanupCompleted(old))
        assertTrue(GatewayRuntime.requestStart())
        val lease = checkNotNull(GatewayRuntime.tryClaimStart())
        val owner = lease.owner
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.RUNNING,
            "new ready", outputTail = "new output\n"))
        GatewayRuntime.requestStop()
        val before = GatewayRuntime.status.value
        assertNull(GatewayRuntime.publish(old.owner, GatewayEngineController.Status(GatewayEngineController.Phase.STOPPED,
            "old stopped", outputTail = "old output\n", exitCode = 23)))
        assertFalse(GatewayRuntime.beginCleanup(old.owner))
        assertFalse(GatewayRuntime.cleanupCompleted(old))
        assertEquals(before, GatewayRuntime.status.value)
        assertTrue(GatewayRuntime.stopping.value)
        assertFalse(GatewayRuntime.requestStart())
        GatewayRuntime.cleanupCompleted(lease)
    }

    @Test fun failedReasonAndExitSurviveStoppedCallbacksAndExplicitCleanup() {
        val lease = checkNotNull(GatewayRuntime.tryClaimStart())
        val owner = lease.owner
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.FAILED,
            "startup timed out", outputTail = "initial failure\n", exitCode = 23))
        GatewayRuntime.requestStop()
        assertNull(GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.STOPPED,
            "cleanup message", outputTail = "final failure output\n")))
        GatewayRuntime.cleanupCompleted(lease)
        val failed = GatewayRuntime.status.value
        assertEquals(GatewayEngineController.Phase.FAILED, failed.phase)
        assertEquals("startup timed out", failed.message)
        assertEquals("final failure output\n", failed.outputTail)
        assertEquals(23, failed.exitCode)
        assertFalse(GatewayRuntime.stopping.value)
        assertTrue(GatewayRuntime.requestStart())
    }

    @Test fun retirementWithoutUserStopPreservesFailureAndFinalExitCode() {
        val lease = checkNotNull(GatewayRuntime.tryClaimStart())
        val owner = lease.owner
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.FAILED,
            "launch failure"))
        assertTrue(GatewayRuntime.beginCleanup(owner))
        assertFalse(GatewayRuntime.mustStop())
        assertNull(GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.STOPPED,
            "final cleanup", outputTail = "cleanup output\n", exitCode = 137)))
        GatewayRuntime.cleanupCompleted(lease)
        assertEquals(GatewayEngineController.Phase.FAILED, GatewayRuntime.status.value.phase)
        assertEquals("launch failure", GatewayRuntime.status.value.message)
        assertEquals(137, GatewayRuntime.status.value.exitCode)
        assertEquals("cleanup output\n", GatewayRuntime.status.value.outputTail)
    }

    @Test fun newClaimAfterRealCleanupClearsPreviousTailAndExitCode() {
        val first = checkNotNull(GatewayRuntime.tryClaimStart())
        GatewayRuntime.publish(first.owner, GatewayEngineController.Status(GatewayEngineController.Phase.FAILED,
            "old failure", outputTail = "old output\n", exitCode = 23))
        assertTrue(GatewayRuntime.beginCleanup(first.owner))
        assertTrue(GatewayRuntime.cleanupCompleted(first))
        assertTrue(GatewayRuntime.requestStart())
        val next = checkNotNull(GatewayRuntime.tryClaimStart())
        assertTrue(GatewayRuntime.isCurrent(next.owner))
        assertEquals(GatewayEngineController.Phase.STARTING, GatewayRuntime.status.value.phase)
        assertEquals("", GatewayRuntime.status.value.outputTail)
        assertNull(GatewayRuntime.status.value.exitCode)
    }

    @Test fun acceptedAndStoppingDiagnosticsUseWholeMasksBeforeTailTruncation() {
        val lease = checkNotNull(GatewayRuntime.tryClaimStart())
        val owner = lease.owner
        val secret = "sk-syntheticWholeDiagnosticEnd9"
        val diagnostic = "Bearer $secret\nhttp://127.0.0.1/?k=$secret\napi_key=$secret\n"
        val safe = checkNotNull(GatewayRuntime.publish(owner, GatewayEngineController.Status(
            GatewayEngineController.Phase.RUNNING, diagnostic, outputTail = "x".repeat(9000) + diagnostic)))
        assertTrue(safe.outputTail.length <= 8192)
        for (text in listOf(safe.message, safe.outputTail, GatewayRuntime.status.value.toString())) {
            assertFalse(text.contains(secret))
            assertFalse(text.contains(secret.takeLast(4)))
        }
        GatewayRuntime.requestStop()
        assertNull(GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.STOPPED,
            diagnostic, outputTail = diagnostic, exitCode = 23)))
        assertFalse(GatewayRuntime.status.value.outputTail.contains(secret.takeLast(4)))
        GatewayRuntime.cleanupCompleted(lease)
        assertFalse(GatewayRuntime.status.value.outputTail.contains(secret))
    }

    @Test fun requestFailureUsesStrictDiagnosticRedaction() {
        val secret = "synthetic-request-failure-End7"
        GatewayRuntime.requestFailed(IllegalStateException("token=$secret"))
        assertFalse(GatewayRuntime.status.value.message.contains(secret))
        assertFalse(GatewayRuntime.status.value.message.contains(secret.takeLast(4)))
    }

    @Test fun repeatedStopRequestsCannotReleaseTheActiveCleanupBarrier() {
        val lease = checkNotNull(GatewayRuntime.tryClaimStart())
        GatewayRuntime.publish(lease.owner, GatewayEngineController.Status(
            GatewayEngineController.Phase.RUNNING, "ready", restartCount = 2, outputTail = "running output\n"))
        assertEquals(lease.owner, GatewayRuntime.requestStop())
        GatewayRuntime.publish(lease.owner, GatewayEngineController.Status(
            GatewayEngineController.Phase.STOPPED, "real worker exited", outputTail = "final output\n", exitCode = 137))
        val pending = GatewayRuntime.status.value
        repeat(12) {
            assertEquals(lease.owner, GatewayRuntime.requestStop())
            assertNull(GatewayRuntime.tryClaimStart())
            assertFalse(GatewayRuntime.requestStart())
            assertEquals(pending, GatewayRuntime.status.value)
            assertTrue(GatewayRuntime.stopping.value)
            assertTrue(GatewayRuntime.isCurrent(lease.owner))
        }
        assertTrue(GatewayRuntime.cleanupCompleted(lease))
        assertFalse(GatewayRuntime.stopping.value)
        assertFalse(GatewayRuntime.isCurrent(lease.owner))
        assertEquals("final output\n", GatewayRuntime.status.value.outputTail)
        assertEquals(137, GatewayRuntime.status.value.exitCode)
    }

    @Test fun rejectedClaimsDoNotAdvanceGenerationOrReplaceCurrentDiagnostics() {
        val first = checkNotNull(GatewayRuntime.tryClaimStart())
        GatewayRuntime.publish(first.owner, GatewayEngineController.Status(
            GatewayEngineController.Phase.RUNNING, "current ready", restartCount = 2, outputTail = "current output\n"))
        val running = GatewayRuntime.status.value
        repeat(8) {
            assertNull(GatewayRuntime.tryClaimStart())
            assertEquals(running, GatewayRuntime.status.value)
            assertTrue(GatewayRuntime.isCurrent(first.owner))
            assertFalse(GatewayRuntime.requestStart())
        }
        assertTrue(GatewayRuntime.beginCleanup(first.owner))
        val pending = GatewayRuntime.status.value
        repeat(8) {
            assertNull(GatewayRuntime.tryClaimStart())
            assertEquals(pending, GatewayRuntime.status.value)
            assertTrue(GatewayRuntime.stopping.value)
        }
        assertTrue(GatewayRuntime.cleanupCompleted(first))
        assertTrue(GatewayRuntime.requestStart())
        val next = checkNotNull(GatewayRuntime.tryClaimStart())
        assertEquals(first.owner + 1, next.owner)
        assertTrue(GatewayRuntime.isCurrent(next.owner))
        assertFalse(GatewayRuntime.isCurrent(first.owner))
    }

    @Test fun cleanupRequiresTheExactLeaseAndOldProofCannotClearANewOwner() {
        val first = checkNotNull(GatewayRuntime.tryClaimStart())
        GatewayRuntime.requestStop()
        val firstPending = GatewayRuntime.status.value
        assertFalse(GatewayRuntime.cleanupCompleted(ServiceLease(first.owner)))
        assertEquals(firstPending, GatewayRuntime.status.value)
        assertTrue(GatewayRuntime.stopping.value)
        assertTrue(GatewayRuntime.isCurrent(first.owner))
        assertTrue(GatewayRuntime.cleanupCompleted(first))
        assertFalse(GatewayRuntime.cleanupCompleted(first))
        assertFalse(GatewayRuntime.isCurrent(first.owner))
        val completed = GatewayRuntime.status.value
        assertNull(GatewayRuntime.publish(first.owner, GatewayEngineController.Status(
            GatewayEngineController.Phase.RUNNING, "late completed owner", outputTail = "late output\n")))
        assertFalse(GatewayRuntime.beginCleanup(first.owner))
        assertEquals(completed, GatewayRuntime.status.value)

        assertTrue(GatewayRuntime.requestStart())
        val next = checkNotNull(GatewayRuntime.tryClaimStart())
        GatewayRuntime.publish(next.owner, GatewayEngineController.Status(
            GatewayEngineController.Phase.RUNNING, "new ready", outputTail = "new output\n"))
        GatewayRuntime.requestStop()
        val nextPending = GatewayRuntime.status.value
        assertFalse(GatewayRuntime.cleanupCompleted(first))
        assertFalse(GatewayRuntime.cleanupCompleted(ServiceLease(next.owner)))
        assertEquals(nextPending, GatewayRuntime.status.value)
        assertTrue(GatewayRuntime.stopping.value)
        assertTrue(GatewayRuntime.isCurrent(next.owner))
        assertFalse(GatewayRuntime.requestStart())
        assertTrue(GatewayRuntime.cleanupCompleted(next))
        assertFalse(GatewayRuntime.stopping.value)
    }

    @Test fun failedActiveLeaseCannotRestartUntilItsRealCleanupCompletes() {
        val lease = checkNotNull(GatewayRuntime.tryClaimStart())
        GatewayRuntime.publish(lease.owner, GatewayEngineController.Status(
            GatewayEngineController.Phase.FAILED, "launch failed", outputTail = "failure output\n", exitCode = 23))
        val failed = GatewayRuntime.status.value
        assertFalse(GatewayRuntime.stopping.value)
        assertFalse(GatewayRuntime.requestStart())
        assertNull(GatewayRuntime.tryClaimStart())
        assertEquals(failed, GatewayRuntime.status.value)
        assertTrue(GatewayRuntime.isCurrent(lease.owner))
        assertTrue(GatewayRuntime.beginCleanup(lease.owner))
        assertFalse(GatewayRuntime.requestStart())
        assertFalse(GatewayRuntime.cleanupCompleted(ServiceLease(lease.owner)))
        assertTrue(GatewayRuntime.stopping.value)
        assertTrue(GatewayRuntime.cleanupCompleted(lease))
        assertEquals(failed, GatewayRuntime.status.value)
        assertFalse(GatewayRuntime.stopping.value)
        assertTrue(GatewayRuntime.requestStart())
        assertNotNull(GatewayRuntime.tryClaimStart())
    }

    @Test fun stopWithoutOwnerCancelsQueuedStartWithoutCreatingCleanupBarrier() {
        assertEquals(GatewayEngineController.Phase.STARTING, GatewayRuntime.status.value.phase)
        val stoppedOwner = GatewayRuntime.requestStop()
        assertTrue(GatewayRuntime.mustStop())
        assertFalse(GatewayRuntime.stopping.value)
        assertFalse(GatewayRuntime.isCurrent(stoppedOwner))
        assertEquals(GatewayEngineController.Phase.STOPPED, GatewayRuntime.status.value.phase)
        assertNull(GatewayRuntime.tryClaimStart())
        GatewayRuntime.requestStop()
        assertFalse(GatewayRuntime.stopping.value)
        assertNull(GatewayRuntime.tryClaimStart())
        assertTrue(GatewayRuntime.requestStart())
        val lease = checkNotNull(GatewayRuntime.tryClaimStart())
        assertTrue(GatewayRuntime.isCurrent(lease.owner))
        assertFalse(GatewayRuntime.mustStop())
    }

    @Test fun stopWithoutOwnerPreservesFailureAndAllowsAnExplicitRetry() {
        GatewayRuntime.requestFailed(IllegalStateException("failed to create foreground service"))
        val failed = GatewayRuntime.status.value
        repeat(3) { GatewayRuntime.requestStop() }
        assertEquals(failed, GatewayRuntime.status.value)
        assertEquals(GatewayEngineController.Phase.FAILED, GatewayRuntime.status.value.phase)
        assertFalse(GatewayRuntime.stopping.value)
        assertTrue(GatewayRuntime.mustStop())
        assertNull(GatewayRuntime.tryClaimStart())
        assertTrue(GatewayRuntime.requestStart())
        assertNotNull(GatewayRuntime.tryClaimStart())
    }

    @Test fun stopAndClaimRacesHaveOnlyTheTwoValidLinearizedOutcomes() {
        repeat(32) { attempt ->
            GatewayRuntime.stoppedWithoutService()
            assertTrue(GatewayRuntime.requestStart())
            val ready = CountDownLatch(2)
            val release = CountDownLatch(1)
            val completed = CountDownLatch(2)
            val claimed = AtomicReference<ServiceLease?>()
            val stoppedOwner = AtomicLong(Long.MIN_VALUE)
            val failure = AtomicReference<Throwable?>()
            val claiming = thread(start = false, isDaemon = true, name = "runtime-claim-$attempt") {
                ready.countDown()
                try {
                    check(release.await(5, TimeUnit.SECONDS))
                    claimed.set(GatewayRuntime.tryClaimStart())
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally { completed.countDown() }
            }
            val stopping = thread(start = false, isDaemon = true, name = "runtime-stop-$attempt") {
                ready.countDown()
                try {
                    check(release.await(5, TimeUnit.SECONDS))
                    stoppedOwner.set(GatewayRuntime.requestStop())
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally { completed.countDown() }
            }
            claiming.start(); stopping.start()
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                release.countDown()
                assertTrue(completed.await(5, TimeUnit.SECONDS))
                claiming.join(5000); stopping.join(5000)
                assertFalse(claiming.isAlive)
                assertFalse(stopping.isAlive)
                assertNull(failure.get())
                assertFalse(stoppedOwner.get() == Long.MIN_VALUE)
                val lease = claimed.get()
                if (lease == null) {
                    // STOP won while START was still only queued: no process
                    // owner exists and no asynchronous cleanup can be pending.
                    assertFalse(GatewayRuntime.stopping.value)
                    assertFalse(GatewayRuntime.isCurrent(stoppedOwner.get()))
                } else {
                    // Claim won first: STOP must wait for that exact owner,
                    // and an unrelated empty Service cannot complete its work.
                    assertEquals(lease.owner, stoppedOwner.get())
                    assertTrue(GatewayRuntime.stopping.value)
                    assertTrue(GatewayRuntime.isCurrent(lease.owner))
                    assertFalse(GatewayRuntime.requestStart())
                    assertNull(GatewayRuntime.tryClaimStart())
                    assertFalse(GatewayRuntime.cleanupCompleted(ServiceLease(lease.owner)))
                    assertTrue(GatewayRuntime.stopping.value)
                    assertTrue(GatewayRuntime.cleanupCompleted(lease))
                }
                assertTrue(GatewayRuntime.mustStop())
                assertFalse(GatewayRuntime.stopping.value)
                assertEquals(GatewayEngineController.Phase.STOPPED, GatewayRuntime.status.value.phase)
                assertNull(GatewayRuntime.tryClaimStart())
            } finally {
                release.countDown()
                claiming.join(5000); stopping.join(5000)
            }
        }
    }

}

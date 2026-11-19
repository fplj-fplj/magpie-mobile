package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.GatewayEngineController
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
        val owner = GatewayRuntime.attach()
        assertNotNull(GatewayRuntime.publish(owner,
            GatewayEngineController.Status(GatewayEngineController.Phase.RUNNING, "ports ready")))
        assertEquals(GatewayEngineController.Phase.RUNNING, GatewayRuntime.status.value.phase)
        assertEquals("ports ready", GatewayRuntime.status.value.message)
    }

    @Test fun lateCancelledServiceCannotOverwriteNewRun() {
        val old = GatewayRuntime.attach()
        val current = GatewayRuntime.attach()
        GatewayRuntime.publish(current, GatewayEngineController.Status(GatewayEngineController.Phase.RUNNING, "new run"))
        assertNull(GatewayRuntime.publish(old,
            GatewayEngineController.Status(GatewayEngineController.Phase.FAILED, "old failure")))
        assertEquals("new run", GatewayRuntime.status.value.message)
    }

    @Test fun failureRemainsVisibleWhenServiceCleansUp() {
        val owner = GatewayRuntime.attach()
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.FAILED, "exit=1"))
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.STOPPED, "cleanup"))
        assertEquals("exit=1", GatewayRuntime.status.value.message)
        GatewayRuntime.stoppedWithoutService()
        assertEquals(GatewayEngineController.Phase.STOPPED, GatewayRuntime.status.value.phase)
    }

    @Test fun stopWaitsForCleanupAndBlocksStart() {
        val owner = GatewayRuntime.attach()
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.RUNNING, "ready"))
        GatewayRuntime.requestStop()
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.STOPPED, "only cancelled"))
        assertEquals(true, GatewayRuntime.stopping.value)
        assertFalse(GatewayRuntime.requestStart())
        GatewayRuntime.cleanupCompleted(owner)
        assertEquals(false, GatewayRuntime.stopping.value)
        assertEquals(GatewayEngineController.Phase.STOPPED, GatewayRuntime.status.value.phase)
        assertEquals(true, GatewayRuntime.requestStart())
    }

    @Test fun diagnosticsRedactTheNativeWebLinkKey() {
        val owner = GatewayRuntime.attach()
        val key = "secretAdminLinkValue"
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.FAILED,
            "http://127.0.0.1/?k=" + key, outputTail = "http://127.0.0.1/?k=" + key))
        assertFalse(GatewayRuntime.status.value.message.contains(key))
        assertFalse(GatewayRuntime.status.value.outputTail.contains(key))
    }

    @Test fun stoppingMergesFinalDiagnosticsWithoutMovingPhaseOrRemovingCleanupBarrier() {
        val owner = GatewayRuntime.attach()
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
        GatewayRuntime.cleanupCompleted(owner)
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
        val old = GatewayRuntime.attach()
        val owner = GatewayRuntime.attach()
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.RUNNING,
            "new ready", outputTail = "new output\n"))
        GatewayRuntime.requestStop()
        val before = GatewayRuntime.status.value
        assertNull(GatewayRuntime.publish(old, GatewayEngineController.Status(GatewayEngineController.Phase.STOPPED,
            "old stopped", outputTail = "old output\n", exitCode = 23)))
        assertFalse(GatewayRuntime.beginCleanup(old))
        GatewayRuntime.cleanupCompleted(old)
        assertEquals(before, GatewayRuntime.status.value)
        assertTrue(GatewayRuntime.stopping.value)
        assertFalse(GatewayRuntime.requestStart())
        GatewayRuntime.cleanupCompleted(owner)
    }

    @Test fun failedReasonAndExitSurviveStoppedCallbacksAndExplicitCleanup() {
        val owner = GatewayRuntime.attach()
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.FAILED,
            "startup timed out", outputTail = "initial failure\n", exitCode = 23))
        GatewayRuntime.requestStop()
        assertNull(GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.STOPPED,
            "cleanup message", outputTail = "final failure output\n")))
        GatewayRuntime.cleanupCompleted(owner)
        val failed = GatewayRuntime.status.value
        assertEquals(GatewayEngineController.Phase.FAILED, failed.phase)
        assertEquals("startup timed out", failed.message)
        assertEquals("final failure output\n", failed.outputTail)
        assertEquals(23, failed.exitCode)
        assertFalse(GatewayRuntime.stopping.value)
        assertTrue(GatewayRuntime.requestStart())
    }

    @Test fun retirementWithoutUserStopPreservesFailureAndFinalExitCode() {
        val owner = GatewayRuntime.attach()
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.FAILED,
            "launch failure"))
        assertTrue(GatewayRuntime.beginCleanup(owner))
        assertFalse(GatewayRuntime.mustStop())
        assertNull(GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.STOPPED,
            "final cleanup", outputTail = "cleanup output\n", exitCode = 137)))
        GatewayRuntime.cleanupCompleted(owner)
        assertEquals(GatewayEngineController.Phase.FAILED, GatewayRuntime.status.value.phase)
        assertEquals("launch failure", GatewayRuntime.status.value.message)
        assertEquals(137, GatewayRuntime.status.value.exitCode)
        assertEquals("cleanup output\n", GatewayRuntime.status.value.outputTail)
    }

    @Test fun newAttachClearsPreviousTailAndExitCode() {
        val first = GatewayRuntime.attach()
        GatewayRuntime.publish(first, GatewayEngineController.Status(GatewayEngineController.Phase.FAILED,
            "old failure", outputTail = "old output\n", exitCode = 23))
        GatewayRuntime.attach()
        assertEquals(GatewayEngineController.Phase.STARTING, GatewayRuntime.status.value.phase)
        assertEquals("", GatewayRuntime.status.value.outputTail)
        assertNull(GatewayRuntime.status.value.exitCode)
    }

    @Test fun acceptedAndStoppingDiagnosticsUseWholeMasksBeforeTailTruncation() {
        val owner = GatewayRuntime.attach()
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
        GatewayRuntime.cleanupCompleted(owner)
        assertFalse(GatewayRuntime.status.value.outputTail.contains(secret))
    }

    @Test fun requestFailureUsesStrictDiagnosticRedaction() {
        val secret = "synthetic-request-failure-End7"
        GatewayRuntime.requestFailed(IllegalStateException("token=$secret"))
        assertFalse(GatewayRuntime.status.value.message.contains(secret))
        assertFalse(GatewayRuntime.status.value.message.contains(secret.takeLast(4)))
    }

}

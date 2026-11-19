package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.GatewayEngineController.Phase
import cc.fplj.magpiemobile.core.GatewayEngineController.Status
import cc.fplj.magpiemobile.core.PostMortemAuditor
import cc.fplj.magpiemobile.core.StartupBenchmark
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GatewayServiceReporterTest {
    @Before fun reset() {
        GatewayRuntime.stoppedWithoutService()
        check(GatewayRuntime.requestStart())
        StartupBenchmark.reset()
        StartupBenchmark.recordProcessInit(1000)
    }

    @After fun cleanup() {
        GatewayRuntime.stoppedWithoutService()
        StartupBenchmark.reset()
    }

    @Test fun startingAndRunningTailRefreshesDoNotRepeatLifecycleOrReadyMetrics() {
        val owner = GatewayRuntime.attach()
        val effects = Effects()
        val reporter = GatewayServiceReporter(owner, effects)
        reporter.report(Status(Phase.STARTING, "starting", outputTail = "first startup output\n"))
        repeat(12) { index ->
            reporter.report(Status(Phase.STARTING, "starting", outputTail = "startup output $index\n"))
        }
        assertEquals(1, effects.suspends.get())
        assertEquals(0, effects.connects.get())
        assertEquals(0, effects.readies.get())
        assertEquals(1, effects.logs.size)
        assertEquals(1, effects.notifications.size)
        assertEquals(0L, StartupBenchmark.getMetrics().gatewayReadyMs)
        reporter.report(Status(Phase.RUNNING, "running", outputTail = "ready output\n"))
        val readyMetrics = StartupBenchmark.getMetrics()
        repeat(12) { index ->
            reporter.report(Status(Phase.RUNNING, "running", outputTail = "running output $index\n"))
        }
        assertEquals("running output 11\n", GatewayRuntime.status.value.outputTail)
        assertEquals(1, effects.suspends.get())
        assertEquals(1, effects.connects.get())
        assertEquals(1, effects.readies.get())
        assertEquals(2, effects.logs.size)
        assertEquals(2, effects.notifications.size)
        assertEquals(0, effects.stops.get())
        assertTrue(effects.audits.isEmpty())
        assertEquals(readyMetrics, StartupBenchmark.getMetrics())
    }

    @Test fun restartCountDistinguishesAttemptsWhileSamePhaseOutputStaysDiagnosticOnly() {
        val owner = GatewayRuntime.attach()
        val effects = Effects()
        val reporter = GatewayServiceReporter(owner, effects)
        reporter.report(Status(Phase.STARTING, "first start"))
        reporter.report(Status(Phase.RUNNING, "first ready"))
        reporter.report(Status(Phase.BACKOFF, "exit", restartCount = 1, exitCode = 23))
        repeat(8) { index ->
            reporter.report(Status(Phase.BACKOFF, "exit", restartCount = 1,
                outputTail = "exit output $index\n", exitCode = 23))
        }
        reporter.report(Status(Phase.STARTING, "retry", restartCount = 1))
        reporter.report(Status(Phase.RUNNING, "retry ready", restartCount = 1))
        repeat(8) { index ->
            reporter.report(Status(Phase.RUNNING, "retry ready", restartCount = 1,
                outputTail = "retry output $index\n"))
        }
        assertEquals(3, effects.suspends.get())
        assertEquals(2, effects.connects.get())
        assertEquals(2, effects.readies.get())
        assertEquals(5, effects.logs.size)
        assertEquals(5, effects.notifications.size)
        assertEquals(1, effects.audits.size)
        assertEquals(23, effects.audits.single().exitCode)
        assertNull(GatewayRuntime.status.value.exitCode)
    }

    @Test fun staleOwnerReportsStopAndRetirementHaveNoEffectsOnTheNewService() {
        val old = GatewayRuntime.attach()
        val effects = Effects()
        val reporter = GatewayServiceReporter(old, effects)
        val current = GatewayRuntime.attach()
        GatewayRuntime.publish(current, Status(Phase.RUNNING, "new service", outputTail = "new output\n"))
        val before = GatewayRuntime.status.value
        assertNull(reporter.report(Status(Phase.FAILED, "old failure", outputTail = "old output\n", exitCode = 137)))
        assertFalse(reporter.requestStop())
        val disconnected = AtomicBoolean()
        assertFalse(reporter.retire { disconnected.set(true) })
        assertFalse(disconnected.get())
        assertEquals(before, GatewayRuntime.status.value)
        assertFalse(GatewayRuntime.stopping.value)
        assertEquals(0, effects.suspends.get())
        assertEquals(0, effects.connects.get())
        assertEquals(0, effects.readies.get())
        assertEquals(0, effects.stops.get())
        assertTrue(effects.logs.isEmpty())
        assertTrue(effects.notifications.isEmpty())
        assertTrue(effects.audits.isEmpty())
    }

    @Test fun stopBarrierRetainsFinalDiagnosticsWithoutLateLifecycleEffectsOrEarlyRestart() {
        val owner = GatewayRuntime.attach()
        val effects = Effects()
        val reporter = GatewayServiceReporter(owner, effects)
        reporter.report(Status(Phase.RUNNING, "ready", outputTail = "before stop\n"))
        assertTrue(reporter.requestStop())
        assertTrue(reporter.requestStop())
        val stopping = GatewayRuntime.status.value
        assertNull(reporter.report(Status(Phase.RUNNING, "late ready", outputTail = "during stop\n")))
        assertNull(reporter.report(Status(Phase.STOPPED, "worker finally exited",
            outputTail = "final output\n", exitCode = 137)))
        assertEquals(stopping.phase, GatewayRuntime.status.value.phase)
        assertEquals(stopping.message, GatewayRuntime.status.value.message)
        assertEquals("final output\n", GatewayRuntime.status.value.outputTail)
        assertEquals(137, GatewayRuntime.status.value.exitCode)
        assertTrue(GatewayRuntime.stopping.value)
        assertFalse(GatewayRuntime.requestStart())
        assertEquals(1, effects.suspends.get())
        assertEquals(1, effects.connects.get())
        assertEquals(1, effects.readies.get())
        assertEquals(1, effects.logs.size)
        assertEquals(1, effects.notifications.size)
        assertEquals(0, effects.stops.get())
        assertTrue(effects.audits.isEmpty())
        GatewayRuntime.cleanupCompleted(owner)
        assertEquals(Phase.STOPPED, GatewayRuntime.status.value.phase)
        assertEquals("final output\n", GatewayRuntime.status.value.outputTail)
        assertEquals(137, GatewayRuntime.status.value.exitCode)
        assertFalse(GatewayRuntime.stopping.value)
        // Even an invalid late callback after cleanup cannot resurrect this retired Service.
        assertNull(reporter.report(Status(Phase.RUNNING, "too late", outputTail = "late final output\n")))
        assertEquals(Phase.STOPPED, GatewayRuntime.status.value.phase)
        assertEquals(1, effects.connects.get())
        assertEquals(1, effects.readies.get())
    }

    @Test fun anExternalRuntimeStopAlsoRejectsReportsBeforeServiceRetirementRuns() {
        val owner = GatewayRuntime.attach()
        val effects = Effects()
        val reporter = GatewayServiceReporter(owner, effects)
        GatewayRuntime.requestStop() // MainActivity can place the barrier before onDestroy.
        assertNull(reporter.report(Status(Phase.RUNNING, "late ready", outputTail = "last output\n")))
        assertEquals(0, effects.connects.get())
        assertEquals(0, effects.readies.get())
        assertEquals(0, effects.suspends.get())
        assertTrue(effects.logs.isEmpty())
        assertTrue(effects.notifications.isEmpty())
        assertEquals("last output\n", GatewayRuntime.status.value.outputTail)
        GatewayRuntime.cleanupCompleted(owner)
    }

    @Test fun failedLifecycleAuditsAndStopsOnceWhileRetirementKeepsReasonAndFinalEvidence() {
        val owner = GatewayRuntime.attach()
        val effects = Effects()
        val reporter = GatewayServiceReporter(owner, effects)
        reporter.report(Status(Phase.FAILED, "startup timeout", outputTail = "failure output\n", exitCode = 23))
        reporter.report(Status(Phase.FAILED, "startup timeout", outputTail = "more failure output\n", exitCode = 23))
        assertNull(reporter.report(Status(Phase.STOPPED, "early stop callback", outputTail = "drained output\n", exitCode = 23)))
        val disconnects = AtomicInteger()
        assertTrue(reporter.retire { disconnects.incrementAndGet() })
        assertTrue(GatewayRuntime.stopping.value)
        assertNull(reporter.report(Status(Phase.STOPPED, "cleanup stopped", outputTail = "final output\n", exitCode = 23)))
        GatewayRuntime.cleanupCompleted(owner)
        assertEquals(Phase.FAILED, GatewayRuntime.status.value.phase)
        assertEquals("startup timeout", GatewayRuntime.status.value.message)
        assertEquals("final output\n", GatewayRuntime.status.value.outputTail)
        assertEquals(23, GatewayRuntime.status.value.exitCode)
        assertEquals(1, disconnects.get())
        assertEquals(1, effects.suspends.get())
        assertEquals(1, effects.audits.size)
        assertEquals(1, effects.stops.get())
        assertEquals(1, effects.logs.size)
        assertEquals(1, effects.notifications.size)
    }

    @Test fun productionReporterPassesActualExitCodesAndUnknownToTheRealAuditor() {
        val directory = Files.createTempDirectory("magpie-reporter-audit").toFile()
        try {
            val auditor = PostMortemAuditor(directory)
            for (exitCode in listOf<Int?>(23, 137, null)) {
                val owner = GatewayRuntime.attach()
                val effects = Effects(auditor)
                val reporter = GatewayServiceReporter(owner, effects)
                val secret = "sk-NmBMmXsNb7RwSwK9sIuCuEc5QWxy"
                val safe = checkNotNull(reporter.report(Status(Phase.FAILED,
                    "process exited; token=$secret", outputTail = "Bearer $secret\nhttp://127.0.0.1/?k=$secret\n",
                    exitCode = exitCode)))
                val actual = checkNotNull(auditor.readReport())
                assertEquals(exitCode, actual.exitCode)
                assertEquals(Phase.FAILED.name, actual.lastKnownPhase)
                assertEquals(1, effects.audits.size)
                for (text in listOf(safe.toString(), effects.logs.single().toString(),
                    effects.notifications.single().toString(), actual.sanitizedDetail,
                    directory.resolve(PostMortemAuditor.AUDIT_FILE_NAME).readText())) {
                    assertFalse(text.contains(secret))
                    assertFalse(text.contains(secret.takeLast(4)))
                }
                val before = directory.resolve(PostMortemAuditor.AUDIT_FILE_NAME).readText()
                reporter.report(Status(Phase.FAILED, "process exited", outputTail = "tail only\n", exitCode = exitCode))
                assertEquals(before, directory.resolve(PostMortemAuditor.AUDIT_FILE_NAME).readText())
                assertEquals(1, effects.audits.size)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun concurrentStopWaitsForAcceptedReportWithoutLateConnectAndFinalReportNeedsNoMain() {
        val owner = GatewayRuntime.attach()
        val enteredConnect = CountDownLatch(1)
        val releaseConnect = CountDownLatch(1)
        val stopStarted = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val effects = Effects(onConnect = {
            enteredConnect.countDown()
            check(releaseConnect.await(5, TimeUnit.SECONDS))
        })
        val reporter = GatewayServiceReporter(owner, effects)
        val problem = AtomicReference<Throwable?>()
        val reporting = thread(isDaemon = true, name = "synthetic-controller-report") {
            try { reporter.report(Status(Phase.RUNNING, "running")) }
            catch (error: Throwable) { problem.compareAndSet(null, error) }
        }
        val stopping = thread(start = false, isDaemon = true, name = "synthetic-service-stop") {
            try {
                stopStarted.countDown()
                reporter.requestStop()
                // This models controller.stop's synchronous callback after the reporter lock is free.
                reporter.report(Status(Phase.STOPPED, "cleanup", outputTail = "final\n", exitCode = 23))
            } catch (error: Throwable) { problem.compareAndSet(null, error) }
            finally { stopped.countDown() }
        }
        try {
            assertTrue(enteredConnect.await(5, TimeUnit.SECONDS))
            stopping.start()
            assertTrue(stopStarted.await(5, TimeUnit.SECONDS))
            assertEquals(0, effects.suspends.get())
            releaseConnect.countDown()
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
            reporting.join(5000)
            stopping.join(5000)
            assertFalse(reporting.isAlive)
            assertFalse(stopping.isAlive)
            assertNull(problem.get())
            assertEquals(1, effects.connects.get())
            assertEquals(1, effects.suspends.get())
            assertEquals("final\n", GatewayRuntime.status.value.outputTail)
            assertEquals(23, GatewayRuntime.status.value.exitCode)
            assertTrue(GatewayRuntime.stopping.value)
            GatewayRuntime.cleanupCompleted(owner)
        } finally {
            releaseConnect.countDown()
            reporting.join(5000)
            if (stopping.state != Thread.State.NEW) stopping.join(5000)
        }
    }

    private class Effects(
        private val auditor: PostMortemAuditor? = null,
        private val onConnect: () -> Unit = {},
    ) : GatewayServiceEffects {
        val readies = AtomicInteger()
        val connects = AtomicInteger()
        val suspends = AtomicInteger()
        val stops = AtomicInteger()
        val audits = CopyOnWriteArrayList<Status>()
        val logs = CopyOnWriteArrayList<Status>()
        val notifications = CopyOnWriteArrayList<Status>()
        override fun ready() {
            StartupBenchmark.recordGatewayReady(1100L + readies.incrementAndGet())
        }
        override fun connect() { connects.incrementAndGet(); onConnect() }
        override fun suspend() { suspends.incrementAndGet() }
        override fun audit(status: Status) {
            audits.add(status)
            auditor?.recordAbnormalExit(status.phase.name, status.exitCode, status.message + "\n" + status.outputTail)
        }
        override fun log(status: Status) { logs.add(status) }
        override fun notify(status: Status) { notifications.add(status) }
        override fun stopSelf() { stops.incrementAndGet() }
    }
}

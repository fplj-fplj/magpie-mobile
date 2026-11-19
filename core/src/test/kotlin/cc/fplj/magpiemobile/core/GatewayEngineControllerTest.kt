package cc.fplj.magpiemobile.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayEngineControllerTest {
    @Test
    fun duplicateStartIsAsynchronousAndIdempotent() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val launches = AtomicInteger()
        val process = FakeProcess()
        val reports = Reports()
        val controller = controller(reports, launch = {
            launches.incrementAndGet()
            entered.countDown()
            release.awaitIgnoringInterrupts()
            process
        })
        try {
            assertTrue(controller.start())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(10) { assertFalse(controller.start()) }
            assertEquals(1, launches.get())
            release.countDown()
            reports.await(GatewayEngineController.Phase.RUNNING)
            assertFalse(controller.start())
            assertEquals(1, launches.get())
        } finally {
            release.countDown()
            controller.stop()
            assertTrue(controller.awaitStopped(3000))
        }
    }

    @Test
    fun firstSpawnFailureAutomaticallyRecoversWithoutResettingBudget() {
        val launches = AtomicInteger()
        val reports = Reports()
        val process = FakeProcess()
        val controller = controller(reports, maxAutoRestart = 1, launch = {
            if (launches.incrementAndGet() == 1) throw IOException("cannot exec payload")
            process
        })
        try {
            assertTrue(controller.start())
            val backoff = reports.await(GatewayEngineController.Phase.BACKOFF)
            assertTrue(backoff.message.contains("cannot exec payload"))
            assertTrue(backoff.message.contains("1000 ms"))
            assertEquals(1, backoff.restartCount)
            assertEquals(null, backoff.exitCode)
            assertFalse(controller.start()) // User double-tap in BACKOFF must not reset the count.
            val running = reports.await(GatewayEngineController.Phase.RUNNING, 3000)
            assertEquals(1, running.restartCount)
            assertEquals(null, running.exitCode)
            assertEquals(2, launches.get())
        } finally {
            controller.stop()
            assertTrue(controller.awaitStopped(3000))
        }
    }

    @Test
    fun spawnFailureWithNoRecoveryReportsFailed() {
        val reports = Reports()
        val launches = AtomicInteger()
        val controller = controller(reports, launch = {
            launches.incrementAndGet()
            throw IOException("permission denied")
        })
        try {
            assertTrue(controller.start())
            assertTrue(controller.awaitStopped(2000))
            val failed = reports.all.last()
            assertEquals(GatewayEngineController.Phase.FAILED, failed.phase)
            assertTrue(failed.message.contains("launch failed"))
            assertTrue(failed.message.contains("permission denied"))
            assertEquals(0, failed.restartCount)
            assertEquals(1, launches.get())
            assertEquals(null, failed.exitCode)
        } finally { controller.stop() }
    }

    @Test
    fun repeatedSpawnFailuresExhaustOneRecoveryRatherThanResettingCount() {
        val reports = Reports()
        val launches = AtomicInteger()
        val controller = controller(reports, maxAutoRestart = 1, launch = {
            launches.incrementAndGet()
            throw IOException("spawn failed")
        })
        try {
            assertTrue(controller.start())
            assertTrue(controller.awaitStopped(3500))
            assertEquals(2, launches.get())
            assertEquals(GatewayEngineController.Phase.FAILED, reports.all.last().phase)
            assertEquals(1, reports.all.last().restartCount)
            assertEquals(listOf(1), reports.all.filter {
                it.phase == GatewayEngineController.Phase.BACKOFF
            }.map { it.restartCount })
        } finally { controller.stop() }
    }

    @Test
    fun defaultThreeRecoveryIntegrationUsesOneTwoFourSecondsThenFails() {
        val reports = Reports()
        val launches = AtomicInteger()
        val controller = controller(reports, maxAutoRestart = 3, launch = {
            val number = launches.incrementAndGet()
            if (number == 1) throw IOException("first spawn fails")
            FakeProcess(output = "attempt "+number+" output", initialExitCode = number)
        })
        val began = System.nanoTime()
        try {
            assertTrue(controller.start())
            assertTrue(controller.awaitStopped(11000))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) >= 7000)
            assertEquals(4, launches.get())
            val backoffs = reports.all.filter { it.phase == GatewayEngineController.Phase.BACKOFF }
            assertEquals(listOf(1, 2, 3), backoffs.map { it.restartCount })
            listOf(1000, 2000, 4000).forEachIndexed { i, ms ->
                assertTrue(backoffs[i].message.contains(ms.toString()+" ms"))
            }
            val failed = reports.all.last()
            assertEquals(GatewayEngineController.Phase.FAILED, failed.phase)
            assertEquals(3, failed.restartCount)
            assertTrue(failed.message.contains("code 4"))
            assertEquals(4, failed.exitCode)
            assertTrue(failed.outputTail.contains("attempt 4 output"))
            assertTrue(reports.all.none { it.phase == GatewayEngineController.Phase.RUNNING })
        } finally { controller.stop() }
    }

    @Test
    fun aliveButNotReadyNeverReportsRunningUntilProbeSucceeds() {
        val probed = CountDownLatch(1)
        val ready = AtomicBoolean(false)
        val reports = Reports()
        val controller = controller(reports, launch = { FakeProcess() }, ready = {
            probed.countDown()
            ready.get()
        })
        try {
            assertTrue(controller.start())
            assertTrue(probed.await(2, TimeUnit.SECONDS))
            assertTrue(reports.all.none { it.phase == GatewayEngineController.Phase.RUNNING })
            ready.set(true)
            reports.await(GatewayEngineController.Phase.RUNNING)
        } finally {
            controller.stop()
            assertTrue(controller.awaitStopped(3000))
        }
    }

    @Test
    fun startupTimeoutIsPermanentFailedAndForceKillStaysOnWorker() {
        val reports = Reports()
        val process = FakeProcess(output = "waiting for bind", ignoreDestroy = true)
        val uiThread = Thread.currentThread().name
        val controller = controller(reports, maxAutoRestart = 3, timeoutMs = 50,
            launch = { process }, ready = { false })
        try {
            assertTrue(controller.start())
            assertTrue(controller.awaitStopped(3000))
            val failed = reports.all.last()
            assertEquals(GatewayEngineController.Phase.FAILED, failed.phase)
            assertTrue(failed.message.contains("timed out"))
            assertTrue(failed.message.contains("exit code 137"))
            assertEquals(137, failed.exitCode)
            assertEquals("waiting for bind", failed.outputTail)
            assertEquals(0, failed.restartCount)
            assertTrue(reports.all.none { it.phase == GatewayEngineController.Phase.RUNNING ||
                it.phase == GatewayEngineController.Phase.BACKOFF })
            assertEquals(1, process.forceKills.get())
            assertTrue(process.waitThreads.isNotEmpty())
            assertTrue(process.waitThreads.all { it != uiThread })
            assertTrue(process.forceKillThreads.all { it != uiThread })
            assertFalse(process.isAlive)
        } finally { controller.stop() }
    }

    @Test
    fun readinessProbeErrorsEventuallyTimeOutWithoutCrashRecovery() {
        val reports = Reports()
        val controller = controller(reports, maxAutoRestart = 3, timeoutMs = 50,
            launch = { FakeProcess() }, ready = { throw IOException("connection refused") })
        try {
            assertTrue(controller.start())
            assertTrue(controller.awaitStopped(3000))
            assertEquals(GatewayEngineController.Phase.FAILED, reports.all.last().phase)
            assertTrue(reports.all.last().message.contains("timed out"))
            assertTrue(reports.all.none { it.phase == GatewayEngineController.Phase.RUNNING ||
                it.phase == GatewayEngineController.Phase.BACKOFF })
        } finally { controller.stop() }
    }

    @Test
    fun stopDuringLaunchDestroysLateProcessWithoutRunningOrRecovery() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val process = FakeProcess()
        val reports = Reports()
        val launches = AtomicInteger()
        val controller = controller(reports, maxAutoRestart = 3, launch = {
            launches.incrementAndGet()
            entered.countDown()
            release.awaitIgnoringInterrupts()
            process
        })
        try {
            assertTrue(controller.start())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val began = System.nanoTime()
            controller.stop()
            assertEquals(null, reports.all.last().exitCode)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 500)
            assertFalse(controller.awaitStopped(10))
            assertFalse(controller.start()) // The late launch still owns the old generation.
            release.countDown()
            assertTrue(process.destroyed.await(2, TimeUnit.SECONDS))
            assertTrue(controller.awaitStopped(3000))
            assertFalse(process.isAlive)
            assertEquals(1, launches.get())
            assertEquals(GatewayEngineController.Phase.STOPPED, reports.all.last().phase)
            assertEquals(143, reports.all.last().exitCode)
            assertTrue(reports.all.none { it.phase == GatewayEngineController.Phase.RUNNING ||
                it.phase == GatewayEngineController.Phase.BACKOFF })
        } finally {
            release.countDown()
            controller.stop()
            assertTrue(controller.awaitStopped(3000))
        }
    }

    @Test
    fun stopWhileReadinessProbeReturnsCannotPublishStaleRunning() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reports = Reports()
        val process = FakeProcess()
        val controller = controller(reports, launch = { process }, ready = {
            entered.countDown()
            release.awaitIgnoringInterrupts()
            true
        })
        try {
            assertTrue(controller.start())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            controller.stop()
            val afterStop = reports.all.size
            release.countDown()
            assertTrue(controller.awaitStopped(3000))
            assertTrue(reports.all.drop(afterStop).all {
                it.phase == GatewayEngineController.Phase.STOPPED
            })
            assertTrue(reports.all.none { it.phase == GatewayEngineController.Phase.RUNNING })
        } finally {
            release.countDown()
            controller.stop()
            assertTrue(controller.awaitStopped(3000))
        }
    }

    @Test
    fun stopDuringBackoffDoesNotResurrect() {
        val reports = Reports()
        val launches = AtomicInteger()
        val secondLaunch = CountDownLatch(1)
        val controller = controller(reports, maxAutoRestart = 1, launch = {
            if (launches.incrementAndGet() > 1) secondLaunch.countDown()
            FakeProcess(initialExitCode = 7)
        })
        try {
            assertTrue(controller.start())
            reports.await(GatewayEngineController.Phase.BACKOFF)
            controller.stop()
            assertTrue(controller.awaitStopped(2000))
            assertFalse(secondLaunch.await(1200, TimeUnit.MILLISECONDS))
            assertEquals(1, launches.get())
            assertEquals(GatewayEngineController.Phase.STOPPED, reports.all.last().phase)
        } finally { controller.stop() }
    }

    @Test
    fun stopDoesNotWaitOrForceKillOnCallerThread() {
        val reports = Reports()
        val process = FakeProcess(ignoreDestroy = true)
        val caller = Thread.currentThread().name
        val controller = controller(reports, launch = { process })
        try {
            assertTrue(controller.start())
            reports.await(GatewayEngineController.Phase.RUNNING)
            val began = System.nanoTime()
            controller.stop()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 500)
            assertTrue(process.destroyed.await(1, TimeUnit.SECONDS))
            assertTrue(controller.awaitStopped(3000))
            assertEquals(1, process.forceKills.get())
            assertEquals(137, reports.all.last().exitCode)
            assertTrue(process.waitThreads.all { it != caller })
            assertTrue(process.forceKillThreads.all { it != caller })
        } finally {
            controller.stop()
            assertTrue(controller.awaitStopped(3000))
        }
    }

    @Test
    fun exitedProcessRetainsStdoutStderrAndExitCodeEvenWhenReadyIsTrue() {
        val reports = Reports()
        val process = FakeProcess(output = "stdout: boot\n", error = "stderr: bind failed\n", initialExitCode = 23)
        val controller = controller(reports, launch = { process })
        try {
            assertTrue(controller.start())
            assertTrue(controller.awaitStopped(3000))
            val failed = reports.all.last()
            assertEquals(GatewayEngineController.Phase.FAILED, failed.phase)
            assertTrue(failed.message.contains("code 23"))
            assertEquals(23, failed.exitCode)
            assertTrue(failed.outputTail.contains("stdout: boot"))
            assertTrue(failed.outputTail.contains("stderr: bind failed"))
            assertEquals(0, process.destroyCalls.get()) // Natural exit must not close unread output.
            assertTrue(reports.all.none { it.phase == GatewayEngineController.Phase.RUNNING })
        } finally { controller.stop() }
    }

    @Test
    fun mergedOutputDrainsFullPipeAndBoundsSingleHugeLine() {
        val pipe = PipedInputStream(256)
        val writer = PipedOutputStream(pipe)
        val written = CountDownLatch(1)
        val reports = Reports()
        val process = FakeProcess(stdout = pipe)
        val content = "x".repeat(100_000) + "\nstdout final\nstderr merged: loader error\n"
        val producer = Thread({
            try {
                writer.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                process.exit(19)
            } finally { written.countDown() }
        }, "fake-merged-output").apply { isDaemon = true }
        val controller = controller(reports, timeoutMs = 3000,
            launch = { producer.start(); process }, ready = { false })
        try {
            assertTrue(controller.start())
            assertTrue(written.await(2, TimeUnit.SECONDS), "A full pipe must not block the producer")
            assertTrue(controller.awaitStopped(4000))
            val failed = reports.all.last()
            assertTrue(failed.message.contains("code 19"))
            assertEquals(19, failed.exitCode)
            assertEquals(GatewayDiagnosticTail.OMITTED_LINE + "\nstdout final\nstderr merged: loader error\n", failed.outputTail)
            assertTrue(reports.all.all { it.outputTail.length <= 8192 })
        } finally {
            controller.stop()
            writer.close()
            assertTrue(controller.awaitStopped(3000))
            producer.join(2000)
        }
    }

    @Test
    fun explicitStartAfterFailedBeginsFreshLifecycle() {
        val reports = Reports()
        val launches = AtomicInteger()
        val controller = controller(reports, launch = {
            if (launches.incrementAndGet() == 1) FakeProcess(output = "previous failed output\n", initialExitCode = 23)
            else FakeProcess()
        })
        try {
            assertTrue(controller.start())
            assertTrue(controller.awaitStopped(2000))
            assertEquals(GatewayEngineController.Phase.FAILED, reports.all.last().phase)
            assertEquals(23, reports.all.last().exitCode)
            assertTrue(reports.all.last().outputTail.contains("previous failed output"))
            assertTrue(controller.start())
            val running = reports.await(GatewayEngineController.Phase.RUNNING)
            assertEquals(0, running.restartCount)
            assertEquals("", running.outputTail)
            assertEquals(null, running.exitCode)
            assertEquals(2, launches.get())
        } finally {
            controller.stop()
            assertTrue(controller.awaitStopped(3000))
        }
    }

    @Test
    fun runningPipePublishesNewOutputWithoutReprobingAndIdleOutputDoesNotRepeat() {
        val pipe = PipedInputStream(256)
        val writer = PipedOutputStream(pipe)
        val process = FakeProcess(stdout = pipe)
        val reports = Reports()
        val probes = AtomicInteger()
        val controller = controller(reports, launch = { process }, ready = { probes.incrementAndGet(); true })
        try {
            assertTrue(controller.start())
            val first = reports.await(GatewayEngineController.Phase.RUNNING)
            writer.write("running stdout marker\n".toByteArray())
            writer.flush()
            val live = reports.awaitWhere { it.phase == GatewayEngineController.Phase.RUNNING && it.outputTail.contains("running stdout marker") }
            assertEquals(first.message, live.message)
            assertEquals(0, live.restartCount)
            assertEquals(null, live.exitCode)
            assertTrue(process.isAlive)
            assertEquals(1, probes.get())
            reports.assertUnchangedFor(650)
            assertEquals(1, probes.get())
        } finally {
            writer.close()
            controller.stop()
            assertTrue(controller.awaitStopped(3000))
        }
    }

    @Test
    fun startingPipeAlsoPublishesWithoutInventingAnotherLifecycleTransition() {
        val pipe = PipedInputStream(256)
        val writer = PipedOutputStream(pipe)
        val process = FakeProcess(stdout = pipe)
        val reports = Reports()
        val probed = CountDownLatch(1)
        val controller = controller(reports, timeoutMs = 5000, launch = { process }, ready = { probed.countDown(); false })
        try {
            assertTrue(controller.start())
            assertTrue(probed.await(2, TimeUnit.SECONDS))
            writer.write("startup progress marker\n".toByteArray())
            writer.flush()
            val live = reports.awaitWhere { it.phase == GatewayEngineController.Phase.STARTING && it.outputTail.contains("startup progress marker") }
            assertEquals("Engine starting", live.message)
            assertEquals(0, live.restartCount)
            assertEquals(null, live.exitCode)
            assertTrue(reports.all.none { it.phase == GatewayEngineController.Phase.RUNNING })
        } finally {
            writer.close()
            controller.stop()
            assertTrue(controller.awaitStopped(3000))
        }
    }

    @Test
    fun pipeChunksCannotExposeIncompleteSecretsAcrossLivePublicationWindows() {
        val pipe = PipedInputStream(256)
        val writer = PipedOutputStream(pipe)
        val process = FakeProcess(stdout = pipe)
        val reports = Reports()
        val now = AtomicLong()
        val controller = controller(reports, launch = { process }, nanoTime = now::get)
        val secret = "controllerBearerPrivate8x9Q"
        try {
            assertTrue(controller.start())
            reports.await(GatewayEngineController.Phase.RUNNING)
            writer.write("completed diagnostic marker Authorization: Bearer ${secret.take(12)}".toByteArray())
            writer.flush()
            now.set(TimeUnit.SECONDS.toNanos(10))
            reports.assertUnchangedFor(50)
            writer.write(secret.drop(12).toByteArray())
            writer.flush()
            now.set(TimeUnit.SECONDS.toNanos(20))
            reports.assertUnchangedFor(50)
            assertTrue(reports.all.all { !it.outputTail.contains(secret.take(12)) && !it.outputTail.contains(secret.takeLast(4)) })
            writer.write("\n".toByteArray())
            writer.flush()
            reports.awaitWhere { it.phase == GatewayEngineController.Phase.RUNNING && it.outputTail.contains("completed diagnostic marker") }
            assertTrue(reports.all.all { !it.outputTail.contains(secret) && !it.outputTail.contains(secret.takeLast(4)) })
        } finally {
            writer.close()
            controller.stop()
            assertTrue(controller.awaitStopped(3000))
        }
    }

    @Test
    fun finalStoppedTailAndObservedExitBypassTheLiveThrottle() {
        val pipe = PipedInputStream(256)
        val writer = PipedOutputStream(pipe)
        val process = FakeProcess(stdout = pipe)
        val reports = Reports()
        val controller = controller(reports, launch = { process }, outputIntervalMs = 60_000)
        try {
            assertTrue(controller.start())
            reports.await(GatewayEngineController.Phase.RUNNING)
            writer.write("final stop marker\n".toByteArray())
            writer.close()
            controller.stop()
            assertTrue(controller.awaitStopped(3000))
            val stopped = reports.all.last()
            assertEquals(GatewayEngineController.Phase.STOPPED, stopped.phase)
            assertTrue(stopped.outputTail.contains("final stop marker"))
            assertEquals(143, stopped.exitCode)
            assertTrue(reports.all.filter { it.phase == GatewayEngineController.Phase.RUNNING }.all {
                !it.outputTail.contains("final stop marker")
            })
        } finally {
            writer.close()
            controller.stop()
            assertTrue(controller.awaitStopped(3000))
        }
    }

    @Test
    fun naturallyObservedExitCodesAreStructuredWithoutInferringTheirCause() {
        for (code in listOf(23, 137)) {
            val reports = Reports()
            val controller = controller(reports, launch = { FakeProcess(output = "last line without newline", initialExitCode = code) })
            try {
                assertTrue(controller.start())
                assertTrue(controller.awaitStopped(3000))
                val failed = reports.all.last()
                assertEquals(GatewayEngineController.Phase.FAILED, failed.phase)
                assertEquals(code, failed.exitCode)
                assertEquals("last line without newline", failed.outputTail)
            } finally { controller.stop() }
        }
    }

    @Test
    fun unavailableExitValueStaysUnknownInsteadOfGuessingFromTheFailureText() {
        val reports = Reports()
        val controller = controller(reports, launch = { FakeProcess(initialExitCode = 23, unreadableExitCode = true) })
        try {
            assertTrue(controller.start())
            assertTrue(controller.awaitStopped(3000))
            assertEquals(GatewayEngineController.Phase.FAILED, reports.all.last().phase)
            assertEquals(null, reports.all.last().exitCode)
        } finally { controller.stop() }
    }

    @Test
    fun automaticRestartClearsPriorExitBeforeTheNextLaunchAndRunningStatus() {
        val reports = Reports()
        val launches = AtomicInteger()
        val nextEntered = CountDownLatch(1)
        val releaseNext = CountDownLatch(1)
        val controller = controller(reports, maxAutoRestart = 1, launch = {
            if (launches.incrementAndGet() == 1) FakeProcess(output = "first exit\n", initialExitCode = 23)
            else { nextEntered.countDown(); releaseNext.awaitIgnoringInterrupts(); FakeProcess() }
        })
        try {
            assertTrue(controller.start())
            assertEquals(23, reports.await(GatewayEngineController.Phase.BACKOFF).exitCode)
            assertTrue(nextEntered.await(3, TimeUnit.SECONDS))
            val restarting = reports.awaitWhere { it.phase == GatewayEngineController.Phase.STARTING && it.message == "Engine restarting" }
            assertEquals(null, restarting.exitCode)
            assertTrue(restarting.outputTail.contains("first exit"))
            releaseNext.countDown()
            assertEquals(null, reports.await(GatewayEngineController.Phase.RUNNING).exitCode)
        } finally {
            releaseNext.countDown()
            controller.stop()
            assertTrue(controller.awaitStopped(3000))
        }
    }

    @Test
    fun lateOldDrainCannotPolluteAutomaticRecoveryAttempt() = assertLateDrainIsolation(automaticRestart = true)

    @Test
    fun lateOldDrainCannotPolluteANewExplicitUserRun() = assertLateDrainIsolation(automaticRestart = false)

    private fun assertLateDrainIsolation(automaticRestart: Boolean) {
        val old = LateOutput("old late output must be discarded\n")
        val launches = AtomicInteger()
        val reports = Reports()
        val controller = controller(reports, maxAutoRestart = if (automaticRestart) 1 else 0, launch = {
            if (launches.incrementAndGet() == 1) FakeProcess(stdout = old, initialExitCode = 23)
            else FakeProcess(output = "fresh attempt marker\n")
        })
        try {
            assertTrue(controller.start())
            assertTrue(old.entered.await(2, TimeUnit.SECONDS))
            if (!automaticRestart) {
                assertTrue(controller.awaitStopped(3000))
                assertEquals(23, reports.all.last().exitCode)
                assertTrue(controller.start())
            }
            val fresh = reports.awaitWhere(5000) {
                it.phase == GatewayEngineController.Phase.RUNNING && it.outputTail.contains("fresh attempt marker")
            }
            assertEquals(null, fresh.exitCode)
            old.release.countDown()
            assertTrue(old.eof.await(2, TimeUnit.SECONDS))
            reports.assertUnchangedFor(650)
            assertTrue(reports.all.none { it.outputTail.contains("old late output") })
            assertEquals(2, launches.get())
        } finally {
            old.release.countDown()
            controller.stop()
            assertTrue(controller.awaitStopped(3000))
        }
    }

    private fun controller(
        reports: Reports,
        maxAutoRestart: Int = 0,
        timeoutMs: Long = 2000,
        launch: () -> Process,
        ready: () -> Boolean = { true },
        outputIntervalMs: Long = 500,
        nanoTime: () -> Long = System::nanoTime,
    ) = GatewayEngineController(launch, ready, reports::accept, timeoutMs, 5,
        EngineSupervisor(maxAutoRestart = maxAutoRestart), outputIntervalMs, nanoTime)

    private class Reports {
        val all = CopyOnWriteArrayList<GatewayEngineController.Status>()
        private val changed = Object()

        fun accept(status: GatewayEngineController.Status) = synchronized(changed) {
            all += status
            changed.notifyAll()
        }

        fun await(phase: GatewayEngineController.Phase, timeoutMs: Long = 2000): GatewayEngineController.Status {
            return awaitWhere(timeoutMs) { it.phase == phase }
        }

        fun awaitWhere(timeoutMs: Long = 2000, predicate: (GatewayEngineController.Status) -> Boolean): GatewayEngineController.Status {
            val began = System.nanoTime()
            val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            synchronized(changed) {
                while (true) {
                    all.lastOrNull(predicate)?.let { return it }
                    val remaining = budget - (System.nanoTime() - began)
                    if (remaining <= 0) throw AssertionError("Missing matching status: "+all)
                    changed.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                }
            }
        }

        fun assertUnchangedFor(timeoutMs: Long) {
            val began = System.nanoTime()
            val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            synchronized(changed) {
                val count = all.size
                while (true) {
                    assertEquals(count, all.size, "Unchanged output unexpectedly republished")
                    val remaining = budget - (System.nanoTime() - began)
                    if (remaining <= 0) return
                    changed.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                }
            }
        }
    }

    private class FakeProcess(
        output: String = "",
        error: String = "",
        initialExitCode: Int? = null,
        private val ignoreDestroy: Boolean = false,
        private val stdout: InputStream = ByteArrayInputStream(output.toByteArray(Charsets.UTF_8)),
        private val stderr: InputStream = ByteArrayInputStream(error.toByteArray(Charsets.UTF_8)),
        private val unreadableExitCode: Boolean = false,
    ) : Process() {
        private val stdin = ByteArrayOutputStream()
        private val exited = CountDownLatch(if (initialExitCode == null) 1 else 0)
        @Volatile private var code = initialExitCode
        val destroyed = CountDownLatch(1)
        val destroyCalls = AtomicInteger()
        val forceKills = AtomicInteger()
        val waitThreads = CopyOnWriteArrayList<String>()
        val forceKillThreads = CopyOnWriteArrayList<String>()

        fun exit(exitCode: Int) = synchronized(this) {
            if (code == null) {
                code = exitCode
                exited.countDown()
            }
        }

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun getOutputStream(): OutputStream = stdin
        override fun isAlive(): Boolean = code == null
        override fun exitValue(): Int {
            if (unreadableExitCode) throw UnsupportedOperationException("Exit value unavailable")
            return code ?: throw IllegalThreadStateException("Still alive")
        }

        override fun waitFor(): Int {
            waitThreads += Thread.currentThread().name
            exited.await()
            return exitValue()
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitThreads += Thread.currentThread().name
            return exited.await(timeout, unit)
        }

        override fun destroy() {
            destroyCalls.incrementAndGet()
            destroyed.countDown()
            if (!ignoreDestroy) exit(143)
        }

        override fun destroyForcibly(): Process {
            forceKills.incrementAndGet()
            forceKillThreads += Thread.currentThread().name
            exit(137)
            return this
        }
    }

    /** Deliberately ignores close and interruption to simulate an inherited descendant pipe. */
    private class LateOutput(value: String) : InputStream() {
        private val bytes = value.toByteArray(Charsets.UTF_8)
        private var offset = 0
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val eof = CountDownLatch(1)

        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            if (length == 0) return 0
            entered.countDown()
            release.awaitIgnoringInterrupts()
            if (offset == bytes.size) { eof.countDown(); return -1 }
            val count = minOf(length, bytes.size - offset)
            bytes.copyInto(buffer, start, offset, offset + count)
            offset += count
            return count
        }

        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) == -1) -1 else byte[0].toInt() and 255
        }

        override fun close() = Unit

        private fun CountDownLatch.awaitIgnoringInterrupts() {
            while (true) {
                try { await(); return } catch (_: InterruptedException) { }
            }
        }
    }

    private fun CountDownLatch.awaitIgnoringInterrupts() {
        while (true) {
            try { await(); return } catch (_: InterruptedException) { }
        }
    }
}

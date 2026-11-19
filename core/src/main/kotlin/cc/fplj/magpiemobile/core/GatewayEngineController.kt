package cc.fplj.magpiemobile.core

import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Asynchronous owner of one engine lifecycle, independent of Android Service/UI.
 * [launch] must create a fresh Process (prefer ProcessBuilder.redirectErrorStream(true)).
 * [ready] must be a quick, bounded probe; a live Process alone is not readiness.
 * [report] is serialized with start/stop and must return quickly (e.g. post to a Handler).
 * Do not wait for this controller from a report callback. Output enters the bounded tail
 * only after whole-line diagnostic redaction; callers must also redact status messages.
 */
class GatewayEngineController(
    private val launch: () -> Process,
    private val ready: () -> Boolean,
    private val report: (Status) -> Unit,
    private val startupTimeoutMs: Long = 15000,
    private val pollIntervalMs: Long = 100,
    private val supervisor: EngineSupervisor = EngineSupervisor(),
    private val outputIntervalMs: Long = 500,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    enum class Phase { STOPPED, STARTING, RUNNING, BACKOFF, FAILED }

    data class Status(
        val phase: Phase,
        val message: String,
        val restartCount: Int = 0,
        val outputTail: String = "",
        val exitCode: Int? = null,
    )

    private val lock = Object()
    private var current: Run? = null
    private var lastStatus = Status(Phase.STOPPED, "Engine stopped")

    init {
        require(startupTimeoutMs > 0) { "startupTimeoutMs must be > 0" }
        require(pollIntervalMs > 0) { "pollIntervalMs must be > 0" }
        require(outputIntervalMs > 0) { "outputIntervalMs must be > 0" }
    }

    /** True only when a new user-start is accepted; including BACKOFF, duplicates are no-ops. */
    fun start(): Boolean = synchronized(lock) {
        // A cancelled launch/cleanup still owns its Process until finally completes.
        if (current != null) return@synchronized false
        if (supervisor.onUserStart() !is EngineSupervisor.Decision.StartProcess) {
            return@synchronized false
        }
        val run = Run(TimeUnit.MILLISECONDS.toNanos(outputIntervalMs))
        run.worker = Thread({ work(run) }, "gateway-engine").apply { isDaemon = true }
        current = run
        publishLocked(run, Phase.STARTING, "Engine starting")
        run.worker.start()
        true
    }

    /** Cancel immediately; only destroy runs here, never waitFor/join/force-kill. */
    fun stop() {
        val process = synchronized(lock) {
            val run = current
            if (run != null) {
                run.cancelled = true
                run.worker.interrupt()
            }
            supervisor.onProcessExited(EngineSupervisor.StopReason.USER_STOP)
            emitLocked(Status(
                Phase.STOPPED, "Engine stopped", supervisor.snapshot().restartCount,
                run?.tail?.snapshot()?.text ?: lastStatus.outputTail,
                // A still-owned attempt may not have completed cleanup/observed its exit yet.
                if (run == null) lastStatus.exitCode else null,
            ))
            run?.process
        }
        destroy(process)
    }

    /** Wait for worker/process cleanup, not for a STOPPED status (FAILED is also terminal). */
    fun awaitStopped(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0) { "timeoutMs must be >= 0" }
        val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val began = System.nanoTime()
        synchronized(lock) {
            while (current != null) {
                val remaining = budget - (System.nanoTime() - began)
                if (remaining <= 0) return false
                try {
                    lock.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return true
        }
    }

    private fun work(run: Run) {
        try {
            while (active(run)) {
                val failure = attempt(run)
                if (!active(run)) return
                val decision = synchronized(lock) {
                    if (!activeLocked(run)) return
                    if (failure.permanent) {
                        supervisor.onPermanentFailure()
                        publishLocked(run, Phase.FAILED, failure.message, failure.exitCode)
                        EngineSupervisor.Decision.None
                    } else {
                        val next = supervisor.onProcessExited(EngineSupervisor.StopReason.CRASH)
                        if (next is EngineSupervisor.Decision.ScheduleRestart) {
                            publishLocked(run, Phase.BACKOFF,
                                "${failure.message}; restart ${supervisor.snapshot().restartCount} in ${next.delayMs} ms", failure.exitCode)
                        } else {
                            publishLocked(run, Phase.FAILED,
                                "${failure.message}; automatic restarts exhausted", failure.exitCode)
                        }
                        next
                    }
                }
                if (decision !is EngineSupervisor.Decision.ScheduleRestart) return
                Thread.sleep(decision.delayMs)
                synchronized(lock) {
                    if (!activeLocked(run)) return
                    // This is recovery, NOT a user-start: never reset the retry budget here.
                    if (supervisor.onBackoffElapsed() !is EngineSupervisor.Decision.StartProcess) return
                    run.lastExitCode = null
                    publishLocked(run, Phase.STARTING, "Engine restarting")
                }
            }
        } catch (_: InterruptedException) {
            // stop interrupts both polling and BACKOFF; never replay a cancelled launch.
            if (active(run)) permanentFailure(run, "Engine worker interrupted")
        } catch (e: Exception) {
            permanentFailure(run, "Engine worker failed: ${describe(e)}")
        } finally {
            synchronized(lock) {
                if (current === run) {
                    if (run.cancelled) {
                        val exit = run.lastExitCode?.let { " (exit code $it)" } ?: ""
                        emitLocked(Status(Phase.STOPPED, "Engine stopped$exit",
                            supervisor.snapshot().restartCount, run.tail.snapshot().text, run.lastExitCode))
                    }
                    // In particular, do not replace a terminal FAILED with STOPPED.
                    current = null
                    lock.notifyAll()
                }
            }
        }
    }

    private fun attempt(run: Run): Failure {
        run.lastExitCode = null
        var process: Process? = null
        val drains = mutableListOf<Drain>()
        var message = "Engine exited"
        var permanent = false
        var exitCode: Int? = null
        try {
            val spawned = launch()
            process = spawned
            synchronized(lock) { run.process = spawned }
            // Drain even a late/cancelled spawn so its pipes cannot fill during cleanup.
            drains += drain(spawned.inputStream, run.tail, "stdout")
            drains += drain(spawned.errorStream, run.tail, "stderr")
            if (!active(run)) return Failure("Engine stopped", false, null)
            val began = System.nanoTime()
            var running = false
            while (active(run)) {
                if (!spawned.isAlive) {
                    exitCode = spawned.exitValue()
                    message = "Engine exited with code $exitCode"
                    break
                }
                if (!running) {
                    if (elapsedMs(began) >= startupTimeoutMs) {
                        permanent = true
                        message = "Engine startup timed out after $startupTimeoutMs ms (not ready)"
                        break
                    }
                    // Connection-refused/probe errors are not proof that the Process crashed.
                    val isReady = try { ready() } catch (_: Exception) { false }
                    synchronized(lock) {
                        if (activeLocked(run) && isReady && spawned.isAlive &&
                            elapsedMs(began) < startupTimeoutMs) {
                            supervisor.onProcessUp()
                            publishLocked(run, Phase.RUNNING, "Engine running")
                            running = true
                        }
                    }
                }
                publishOutputIfChanged(run)
                Thread.sleep(pollIntervalMs)
            }
        } catch (e: Exception) {
            if (active(run)) {
                message = if (process == null) "Engine launch failed: ${describe(e)}"
                    else "Engine failed: ${describe(e)}"
            }
        } finally {
            // All waits/force-kills are on the worker, including stop-during-launch.
            val cleanedExit = cleanup(process, drains)
            if (exitCode == null) exitCode = cleanedExit
            run.lastExitCode = exitCode
            synchronized(lock) {
                if (run.process === process) run.process = null
            }
        }
        if (exitCode != null && !message.contains("code $exitCode")) {
            message += " (exit code $exitCode)"
        }
        return Failure(message, permanent, exitCode)
    }

    private fun drain(stream: InputStream, tail: GatewayDiagnosticTail, name: String): Drain {
        val output = tail.openStream()
        val thread = Thread({
            try {
                stream.reader(Charsets.UTF_8).use { reader ->
                    val chunk = CharArray(1024)
                    while (true) {
                        val count = reader.read(chunk)
                        if (count < 0) { output.endOfInput(); break }
                        if (count > 0) output.append(chunk, count)
                    }
                }
            } catch (_: Exception) {
                // Closing a stopped Process stream is expected; never grow an error log here.
            }
        }, "gateway-drain-$name").apply { isDaemon = true }
        thread.start()
        return Drain(stream, thread, output)
    }

    private fun cleanup(process: Process?, drains: List<Drain>): Int? {
        // Cancellation interrupted the worker; clear it for bounded cleanup waits.
        val interrupted = Thread.interrupted()
        try {
            if (process != null) {
                // destroy may close stdout immediately; natural exits must drain through EOF.
                if (try { process.isAlive } catch (_: Exception) { true }) destroy(process)
                if (!waitFor(process, 500)) {
                    try { process.destroyForcibly() } catch (_: Exception) { }
                    waitFor(process, 500)
                }
            }
            // First allow EOF to drain all buffered output, then close inherited/open pipes.
            drains.forEach { join(it.thread, 1000) }
            // Reader -> tail is the only reader lock order; never hold the controller here.
            // Seal before another attempt can start, even if an inherited pipe cannot close.
            drains.forEach { it.output.seal() }
            drains.forEach {
                try { it.stream.close() } catch (_: Exception) { }
                if (it.thread.isAlive) {
                    it.thread.interrupt()
                    join(it.thread, 100)
                }
            }
            try { process?.outputStream?.close() } catch (_: Exception) { }
            return try { process?.exitValue() } catch (_: Exception) { null }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun waitFor(process: Process, timeoutMs: Long): Boolean = try {
        process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: Exception) { false }

    private fun join(thread: Thread, timeoutMs: Long) {
        try { thread.join(timeoutMs) } catch (_: InterruptedException) { }
    }

    private fun destroy(process: Process?) {
        try { process?.destroy() } catch (_: Exception) { }
    }

    private fun permanentFailure(run: Run, message: String) = synchronized(lock) {
        if (activeLocked(run)) {
            supervisor.onPermanentFailure()
            publishLocked(run, Phase.FAILED, message, run.lastExitCode)
        }
    }

    private fun active(run: Run): Boolean = synchronized(lock) { activeLocked(run) }
    private fun activeLocked(run: Run): Boolean = current === run && !run.cancelled

    private fun publishLocked(run: Run, phase: Phase, message: String, exitCode: Int? = null) {
        if (activeLocked(run)) {
            val tail = run.tail.snapshot()
            run.outputThrottle.published(tail.revision, nanoTime())
            emitLocked(Status(phase, message, supervisor.snapshot().restartCount, tail.text, exitCode))
        }
    }

    private fun publishOutputIfChanged(run: Run) = synchronized(lock) {
        if (!activeLocked(run) || (lastStatus.phase != Phase.STARTING && lastStatus.phase != Phase.RUNNING)) return@synchronized
        val tail = run.tail.snapshot()
        if (tail.text != lastStatus.outputTail && run.outputThrottle.changed(tail.revision, nanoTime())) {
            emitLocked(lastStatus.copy(outputTail = tail.text))
        }
    }

    private fun emitLocked(status: Status) {
        lastStatus = status
        // A bad observer must not strand a Process or break lifecycle cleanup.
        try { report(status) } catch (_: Exception) { }
    }

    private fun elapsedMs(began: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
    private fun describe(e: Exception): String = "${e.javaClass.simpleName}: ${e.message.orEmpty().take(1024)}"

    private class Run(outputIntervalNanos: Long) {
        var cancelled = false // All accesses are under the controller lock.
        var process: Process? = null
        var lastExitCode: Int? = null // Only the worker writes/reads this field.
        val tail = GatewayDiagnosticTail()
        val outputThrottle = GatewayOutputThrottle(outputIntervalNanos)
        lateinit var worker: Thread
    }

    private data class Failure(val message: String, val permanent: Boolean, val exitCode: Int?)
    private data class Drain(val stream: InputStream, val thread: Thread, val output: GatewayDiagnosticTail.Stream)
}

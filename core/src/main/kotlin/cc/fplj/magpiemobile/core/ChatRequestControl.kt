package cc.fplj.magpiemobile.core

import java.io.Closeable
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One chat turn's cancellation signal, shared by streaming and any permitted fallback.
 * The signal is set before dispatching disconnects to workers, so checks and cancellation callers
 * never need to wait for a platform connection's active read lock.
 * Never reuse a stopped control for another turn.
 */
class ChatRequestControl {
    private enum class State { ACTIVE, CANCELLED, DEADLINE_EXCEEDED }
    private val state = AtomicReference(State.ACTIVE)
    private val cancelActions = ConcurrentHashMap.newKeySet<() -> Unit>()
    private class Deadline(val started: Long, val nanos: Long)
    private val deadlines = ConcurrentHashMap.newKeySet<Deadline>()

    fun cancel() = stop(State.CANCELLED)

    fun ensureActive() {
        if (deadlines.any { System.nanoTime() - it.started >= it.nanos }) {
            deadlineExceeded()
        }
        when (state.get()) {
            State.ACTIVE -> Unit
            State.CANCELLED -> throw CancellationException("Chat request cancelled")
            State.DEADLINE_EXCEEDED -> throw StreamingChatException(StreamingChatFailure.DEADLINE_EXCEEDED, "")
        }
    }

    internal fun deadlineExceeded() = stop(State.DEADLINE_EXCEEDED)

    /** A scoped monotonic deadline, including a permitted streaming-to-ordinary fallback. */
    internal fun startDeadline(duration: Duration): Closeable {
        require(!duration.isNegative && !duration.isZero && duration <= Duration.ofDays(1))
        ensureActive()
        val deadline = Deadline(System.nanoTime(), duration.toNanos())
        deadlines.add(deadline)
        val watchdog = deadlineWorkers.schedule({
            if (deadlines.contains(deadline)) deadlineExceeded()
        }, deadline.nanos, TimeUnit.NANOSECONDS)
        try {
            ensureActive()
        } catch (stopped: Exception) {
            deadlines.remove(deadline)
            watchdog.cancel(false)
            throw stopped
        }
        return Closeable {
            deadlines.remove(deadline)
            watchdog.cancel(false)
        }
    }

    internal fun register(cancelAction: () -> Unit): Closeable {
        ensureActive()
        val invoked = AtomicBoolean()
        val registeredAction = { if (invoked.compareAndSet(false, true)) cancelAction() }
        cancelActions.add(registeredAction)
        if (state.get() != State.ACTIVE) {
            cancelActions.remove(registeredAction)
            disconnectWorkers.execute { disconnect(registeredAction) }
            ensureActive()
        }
        return Closeable { cancelActions.remove(registeredAction) }
    }

    private fun stop(reason: State) {
        if (!state.compareAndSet(State.ACTIVE, reason)) return
        cancelActions.forEach { action -> disconnectWorkers.execute { disconnect(action) } }
    }

    private fun disconnect(cancelAction: () -> Unit) {
        try { cancelAction() } catch (_: Exception) { /* Fixed stop reason is authoritative. */ }
    }

    private companion object {
        val disconnectWorkers = Executors.newCachedThreadPool { task ->
            Thread(task, "magpie-chat-disconnect").apply { isDaemon = true }
        }
        val deadlineWorkers = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "magpie-chat-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}

package cc.fplj.magpiemobile

import java.util.concurrent.CancellationException
import java.util.concurrent.Executor

/**
 * Serializes authentication across process restarts without losing a newer RUNNING request.
 * State callbacks use the session owner's lock; authentication always runs outside that lock.
 * An epoch is invalidated by every suspend, even when the Service owner has not changed.
 */
internal class SessionConnectionCoordinator<B : Any, I, R>(
    private val lock: Any,
    private val executor: Executor,
    private val isRunning: (B) -> Boolean,
    private val authenticate: (binding: B, input: I, ensureCurrent: () -> Unit) -> R,
    private val onConnecting: (B) -> Unit,
    private val onReady: (B, R) -> Unit,
    private val onError: (B, Exception) -> Unit,
    private val onCancelled: (B) -> Unit,
) {
    private enum class Phase { IDLE, CONNECTING, READY, ERROR }

    private inner class Session(val binding: B) {
        var epoch = Any()
        var phase = Phase.IDLE
        var attempt: Attempt? = null
    }

    private inner class Attempt(val session: Session, val epoch: Any, val input: I)

    private var current: Session? = null
    private var pending: Attempt? = null
    private var drainScheduled = false

    fun attach(binding: B) = synchronized(lock) {
        current = Session(binding)
        pending = null
    }

    fun suspend(binding: B) = synchronized(lock) {
        val session = current ?: return
        if (session.binding !== binding) return
        session.epoch = Any()
        session.phase = Phase.IDLE
        session.attempt = null
        pending = null
    }

    fun disconnect(binding: B) = synchronized(lock) {
        if (current?.binding !== binding) return
        current = null
        pending = null
    }

    /** Only explicit retries may restart an ERROR; repeated RUNNING never repeats provisioning. */
    fun connect(binding: B, input: I, retry: Boolean = false): Boolean {
        val schedule = synchronized(lock) {
            val session = current ?: return false
            if (session.binding !== binding || !isRunning(binding)) return false
            if (session.phase != Phase.IDLE && !(retry && session.phase == Phase.ERROR)) return false
            val attempt = Attempt(session, session.epoch, input)
            session.attempt = attempt
            session.phase = Phase.CONNECTING
            pending = attempt
            onConnecting(binding)
            if (drainScheduled) false else {
                drainScheduled = true
                true
            }
        }
        if (schedule) {
            try {
                executor.execute(::drain)
            } catch (error: Exception) {
                synchronized(lock) {
                    drainScheduled = false
                    val attempt = pending
                    pending = null
                    if (attempt != null && isCurrent(attempt)) {
                        attempt.session.phase = Phase.ERROR
                        onError(attempt.session.binding, error)
                    }
                }
            }
        }
        return true
    }

    private fun drain() {
        // Only this one drain may call authenticate, even if the supplied executor is parallel.
        while (true) {
            val attempt = synchronized(lock) {
                val next = pending
                pending = null
                if (next == null) {
                    drainScheduled = false
                    return
                }
                next
            }
            try {
                ensureCurrent(attempt)
                val result = authenticate(attempt.session.binding, attempt.input) { ensureCurrent(attempt) }
                synchronized(lock) {
                    if (isCurrent(attempt)) {
                        attempt.session.phase = Phase.READY
                        onReady(attempt.session.binding, result)
                    }
                }
            } catch (_: CancellationException) {
                synchronized(lock) {
                    if (ownsAttempt(attempt)) {
                        attempt.session.epoch = Any()
                        attempt.session.attempt = null
                        attempt.session.phase = Phase.IDLE
                        onCancelled(attempt.session.binding)
                    }
                }
            } catch (error: Exception) {
                synchronized(lock) {
                    if (isCurrent(attempt)) {
                        attempt.session.phase = Phase.ERROR
                        onError(attempt.session.binding, error)
                    }
                }
            }
            // No old worker's finally may clear the current epoch's attempt or pending request.
        }
    }

    private fun ensureCurrent(attempt: Attempt) = synchronized(lock) {
        if (!isCurrent(attempt)) throw CancellationException("Core connection attempt is no longer current")
    }

    private fun isCurrent(attempt: Attempt): Boolean =
        ownsAttempt(attempt) && isRunning(attempt.session.binding)

    private fun ownsAttempt(attempt: Attempt): Boolean =
        current === attempt.session && attempt.session.epoch === attempt.epoch &&
            attempt.session.attempt === attempt && attempt.session.phase == Phase.CONNECTING
}

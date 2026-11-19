package cc.fplj.magpiemobile

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/** All operations use the session binding lock. Only the non-secret key enters observable state. */
internal class ProviderSessionEpochs<B : Any, C : Any> {
    internal class Captured<B : Any, C : Any>(
        val key: ProviderSessionKey,
        val binding: B,
        val config: C,
    )

    private class Connecting<B : Any>(val key: ProviderSessionKey, val binding: B)
    private var connecting: Connecting<B>? = null
    private var ready: Captured<B, C>? = null

    /** Advancing even while disconnected prevents a later binding from reusing an old epoch. */
    fun invalidate() {
        nextEpoch()
        connecting = null
        ready = null
    }

    /** Called only after the connection coordinator accepts a new attempt. */
    fun begin(owner: Long, binding: B) {
        ready = null
        connecting = Connecting(ProviderSessionKey(owner, nextEpoch()), binding)
    }

    fun publish(binding: B, config: C): ProviderSessionKey {
        val attempt = connecting
        check(attempt != null && attempt.binding === binding) { "Provider connection is no longer current" }
        val current = Captured(attempt.key, binding, config)
        ready = current
        connecting = null
        return current.key
    }

    fun current(binding: B?, config: C?, running: Boolean): ProviderSessionKey? =
        ready?.takeIf { running && it.binding === binding && it.config === config }?.key

    /** Reject a stale key before the caller can obtain the current binding's credentials. */
    fun capture(key: ProviderSessionKey, binding: B?, config: C?, running: Boolean): Captured<B, C> {
        val current = ready
        if (current == null || current.key != key || !running ||
            current.binding !== binding || current.config !== config) {
            throw CancellationException("Provider session is no longer current")
        }
        return current
    }

    fun isCurrent(captured: Captured<B, C>, binding: B?, config: C?, running: Boolean): Boolean =
        ready === captured && running && captured.binding === binding && captured.config === config

    private fun nextEpoch(): Long = epochs.updateAndGet { Math.addExact(it, 1L) }

    companion object {
        // Shared across owners and helper instances; never reset by attach, retry or Service stop.
        private val epochs = AtomicLong()
    }
}

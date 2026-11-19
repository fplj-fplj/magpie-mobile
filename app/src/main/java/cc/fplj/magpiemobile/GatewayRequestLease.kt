package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ChatRequestControl
import cc.fplj.magpiemobile.core.GatewayConfig
import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** A configuration and cancellation signal belonging to one authenticated core session. */
internal interface GatewayRequestLease : Closeable {
    val config: GatewayConfig
    val control: ChatRequestControl
    fun ensureCurrent()
}

/** Session owners serialize acquisition and invalidation with their own binding lock. */
internal class GatewayRequestLeases {
    private val lock = Any()
    private val controls = mutableSetOf<ChatRequestControl>()

    fun acquire(config: GatewayConfig, isCurrent: () -> Boolean): GatewayRequestLease {
        val control = ChatRequestControl()
        val closed = AtomicBoolean()
        synchronized(lock) { controls.add(control) }
        return object : GatewayRequestLease {
            override val config = config
            override val control = control

            override fun ensureCurrent() {
                control.ensureActive()
                if (closed.get() || !isCurrent()) {
                    control.cancel()
                    throw CancellationException("Gateway session is no longer current")
                }
                control.ensureActive()
            }

            // Normal completion unregisters without turning a successful run into cancellation.
            override fun close() {
                if (closed.compareAndSet(false, true)) synchronized(lock) { controls.remove(control) }
            }
        }
    }

    fun invalidate() = synchronized(lock) {
        // cancel() sets the signal immediately and dispatches socket teardown without blocking.
        controls.forEach { it.cancel() }
        controls.clear()
    }
}

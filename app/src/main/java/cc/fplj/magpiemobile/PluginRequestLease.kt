package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ChatRequestControl
import cc.fplj.magpiemobile.core.ManagerCredential
import cc.fplj.magpiemobile.core.PluginManagementClient
import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Captures management credentials before dispatch; never resolves a replacement session later. */
internal interface PluginRequestLease : Closeable {
    val client: PluginManagementClient
    val control: ChatRequestControl
    fun ensureCurrent()
}

/** The session owner serializes acquisition/invalidation with its binding lock. */
internal class PluginRequestLeases {
    private val lock = Any()
    private val controls = mutableSetOf<ChatRequestControl>()

    fun acquire(credential: ManagerCredential, isCurrent: () -> Boolean): PluginRequestLease {
        val control = ChatRequestControl()
        val closed = AtomicBoolean()
        val ensureCurrent = {
            control.ensureActive()
            if (closed.get() || !isCurrent()) {
                control.cancel()
                throw CancellationException("Plugin session is no longer current")
            }
            control.ensureActive()
        }
        val client = PluginManagementClient(
            credential,
            baseUrl = "http://127.0.0.1:${credential.port}",
            requestControl = control,
            ensureActive = ensureCurrent,
        )
        synchronized(lock) { controls.add(control) }
        return object : PluginRequestLease {
            override val client = client
            override val control = control
            override fun ensureCurrent() = ensureCurrent.invoke()

            // Completion unregisters without cancelling a successful result.
            override fun close() {
                if (closed.compareAndSet(false, true)) synchronized(lock) { controls.remove(control) }
            }
        }
    }

    fun invalidate() = synchronized(lock) {
        controls.forEach { it.cancel() }
        controls.clear()
    }
}

package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ChatRequestControl
import cc.fplj.magpiemobile.core.ManagerCredential
import cc.fplj.magpiemobile.core.ProviderManagementClient
import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Captures management credentials before dispatch; never resolves a replacement session later. */
internal interface ProviderRequestLease : Closeable {
    val client: ProviderManagementClient
    val control: ChatRequestControl
    fun ensureCurrent()
}

/** The session owner serializes acquisition/invalidation with its binding lock. */
internal class ProviderRequestLeases {
    private val lock = Any()
    private val controls = mutableSetOf<ChatRequestControl>()

    fun acquire(credential: ManagerCredential, isCurrent: () -> Boolean): ProviderRequestLease {
        val control = ChatRequestControl()
        val closed = AtomicBoolean()
        val ensureCurrent = {
            control.ensureActive()
            if (closed.get() || !isCurrent()) {
                control.cancel()
                throw CancellationException("Provider session is no longer current")
            }
            control.ensureActive()
        }
        val client = ProviderManagementClient(
            credential,
            baseUrl = "http://127.0.0.1:${credential.port}",
            requestControl = control,
            ensureActive = ensureCurrent,
        )
        synchronized(lock) { controls.add(control) }
        return object : ProviderRequestLease {
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

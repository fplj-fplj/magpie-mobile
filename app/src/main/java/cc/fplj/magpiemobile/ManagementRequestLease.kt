package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ChatRequestControl
import cc.fplj.magpiemobile.core.CoreManagementClient
import cc.fplj.magpiemobile.core.ManagerCredential
import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Private management credentials and cancellation bound to one verified connection epoch. */
internal interface ManagementRequestLease : Closeable {
    val client: CoreManagementClient
    val control: ChatRequestControl
    fun ensureCurrent()
}

internal class ManagementRequestLeases {
    private val lock = Any()
    private val controls = mutableSetOf<ChatRequestControl>()

    // The owner holds its binding lock while acquiring or invalidating leases.
    fun acquire(credential: ManagerCredential, isCurrent: () -> Boolean): ManagementRequestLease {
        val control = ChatRequestControl()
        val closed = AtomicBoolean()
        val ensureCurrent = {
            control.ensureActive()
            if (closed.get() || !isCurrent()) {
                control.cancel()
                throw CancellationException("Management session is no longer current")
            }
            control.ensureActive()
        }
        val client = CoreManagementClient(
            credential,
            baseUrl = "http://127.0.0.1:${credential.port}",
            requestControl = control,
            ensureActive = ensureCurrent,
        )
        synchronized(lock) { controls.add(control) }
        return object : ManagementRequestLease {
            override val client = client
            override val control = control
            override fun ensureCurrent() = ensureCurrent.invoke()
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

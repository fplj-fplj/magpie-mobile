package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.CallerKeyAction
import cc.fplj.magpiemobile.core.CallerKeyChange
import cc.fplj.magpiemobile.core.CoreManagementClient
import cc.fplj.magpiemobile.core.RegisteredGatewayKey
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface CallerKeySessionOperation : Closeable {
    fun ensureCurrent()
    fun cancel()
    suspend fun keys(): List<RegisteredGatewayKey>
    suspend fun create(name: String): RegisteredGatewayKey
    suspend fun copy(id: String): RegisteredGatewayKey
    suspend fun update(action: CallerKeyAction, change: CallerKeyChange): List<RegisteredGatewayKey>
}

/** Capture on the UI caller before any coroutine is allowed to queue work on IO. */
internal class CallerKeyOperationController(
    private val acquire: (ProviderSessionKey) -> ManagementRequestLease = NativeCoreSession::managementRequest,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun capture(session: ProviderSessionKey): CallerKeySessionOperation =
        CapturedCallerKeyOperation(acquire(session), dispatcher)
}

private class CapturedCallerKeyOperation(
    private val lease: ManagementRequestLease,
    private val dispatcher: CoroutineDispatcher,
) : CallerKeySessionOperation {
    private val closed = AtomicBoolean()
    override fun ensureCurrent() = lease.ensureCurrent()
    override fun cancel() = lease.control.cancel()
    override fun close() { if (closed.compareAndSet(false, true)) lease.close() }
    override suspend fun keys() = request { it.listCallerKeys() }
    override suspend fun create(name: String) = request { it.createCallerKey(name) }
    override suspend fun copy(id: String) = request { it.copyCallerKey(id) }
    override suspend fun update(action: CallerKeyAction, change: CallerKeyChange) =
        request { it.updateCallerKey(action, change) }

    private suspend fun <T> request(action: (CoreManagementClient) -> T): T = try {
        withContext(dispatcher) {
            withChatRequestCancellation(lease.control) {
                lease.ensureCurrent()
                action(lease.client).also { lease.ensureCurrent() }
            }
        }
    } catch (cancelled: CancellationException) {
        // A queued withContext may be cancelled before it installs its socket watcher.
        cancel()
        throw cancelled
    }
}

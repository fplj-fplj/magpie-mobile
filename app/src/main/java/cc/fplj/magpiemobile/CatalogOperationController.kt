package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ModelEntry
import cc.fplj.magpiemobile.core.ModelsClient
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One private catalog request, bound before dispatch to a verified connection epoch. */
internal interface CatalogConnection : Closeable {
    val key: ProviderSessionKey
    fun ensureCurrent()
    fun cancel()
    suspend fun read(): List<ModelEntry>
}

internal class CatalogOperationController(
    private val acquire: (ProviderSessionKey) -> GatewayRequestLease = NativeCoreSession::gatewayRequest,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun capture(key: ProviderSessionKey): CatalogConnection = CapturedCatalogConnection(key, acquire(key), dispatcher)
}

private class CapturedCatalogConnection(
    override val key: ProviderSessionKey,
    private val lease: GatewayRequestLease,
    private val dispatcher: CoroutineDispatcher,
) : CatalogConnection {
    private val closed = AtomicBoolean()
    override fun ensureCurrent() = lease.ensureCurrent()
    override fun cancel() = lease.control.cancel()
    override fun close() { if (closed.compareAndSet(false, true)) lease.close() }

    override suspend fun read(): List<ModelEntry> = try {
        withContext(dispatcher) {
            withChatRequestCancellation(lease.control) {
                lease.ensureCurrent()
                ModelsClient(lease.config, requestControl = lease.control).list().also { lease.ensureCurrent() }
            }
        }
    } catch (cancelled: CancellationException) {
        // IO may never start, so its cancellation watcher may not have been installed.
        cancel()
        throw cancelled
    }
}

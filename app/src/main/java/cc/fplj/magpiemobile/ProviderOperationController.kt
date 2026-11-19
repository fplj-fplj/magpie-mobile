package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ProviderManagementClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Captures the expected session before a UI operation can be queued on another dispatcher. */
internal class ProviderOperationController(
    private val scope: CoroutineScope,
    private val acquireLease: (ProviderSessionKey) -> ProviderRequestLease = NativeCoreSession::providerRequest,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun launch(
        session: ProviderSessionKey,
        onFinished: () -> Unit = {},
        block: suspend ProviderOperation.() -> Unit,
    ): Job {
        val operation = ProviderOperation(acquireLease(session), dispatcher)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                operation.ensureCurrent()
                operation.block()
            } finally {
                try { operation.release() } finally { onFinished() }
            }
        }
        // A disposed parent can complete a lazy launch without executing its body/finally.
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) operation.cancel()
            operation.release()
        }
        job.start()
        return job
    }
}

internal class ProviderOperation(
    private val lease: ProviderRequestLease,
    private val dispatcher: CoroutineDispatcher,
) {
    private val released = AtomicBoolean()

    fun ensureCurrent() = lease.ensureCurrent()

    suspend fun <T> request(action: (ProviderManagementClient) -> T): T = withContext(dispatcher) {
        withChatRequestCancellation(lease.control) {
            lease.ensureCurrent()
            action(lease.client).also { lease.ensureCurrent() }
        }
    }

    /** Recovery reads must wait for this operation's HTTP call, even if its UI is disposed. */
    suspend fun <T> write(
        cache: ProviderListCacheViewModel,
        session: ProviderSessionKey,
        onPending: () -> Unit,
        action: (ProviderManagementClient) -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        lease.ensureCurrent()
        var barrier: ProviderListCacheViewModel.WriteBarrier? = null
        try {
            // No cancellation gap may leave a registered write without its saveable marker.
            withContext(NonCancellable) {
                barrier = cache.beginWrite(session)
                onPending()
            }
            currentCoroutineContext().ensureActive()
            lease.ensureCurrent()
            return request(action)
        } finally {
            // withContext/request completes only after its synchronous HTTP worker has exited.
            barrier?.finish()
        }
    }

    fun cancel() = lease.control.cancel()

    fun release() {
        if (released.compareAndSet(false, true)) lease.close()
    }
}

/** A VM cache read owns its lease inside the fetch task, independently of any UI awaiter. */
internal suspend fun <T> withProviderRequest(
    session: ProviderSessionKey,
    acquireLease: (ProviderSessionKey) -> ProviderRequestLease = NativeCoreSession::providerRequest,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    action: (ProviderManagementClient) -> T,
): T {
    val operation = ProviderOperation(acquireLease(session), dispatcher)
    return try {
        operation.request(action)
    } catch (cancelled: CancellationException) {
        operation.cancel()
        throw cancelled
    } finally {
        operation.release()
    }
}

package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ProviderCapabilityReport
import cc.fplj.magpiemobile.core.ProviderCapabilityResult
import cc.fplj.magpiemobile.core.ProviderModelCapabilityClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns one dialog run, including a lease captured before any work is dispatched. */
internal class ProviderConnectivityTestRunController(
    private val scope: CoroutineScope,
    private val acquireLease: (Long) -> GatewayRequestLease = NativeCoreSession::gatewayRequest,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private var current: Run? = null

    fun start(
        owner: Long,
        providerId: String,
        modelId: String,
        onProgress: (ProviderCapabilityResult) -> Unit = {},
        onSuccess: (ProviderCapabilityReport) -> Unit = {},
        onFailure: (Exception) -> Unit = {},
        onFinished: () -> Unit = {},
    ): Job {
        cancel()
        // Capturing configuration and registering invalidation must be one synchronous operation.
        val run = Run(acquireLease(owner))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val activeJob = coroutineContext.job
                val report = withContext(dispatcher) {
                    withChatRequestCancellation(run.lease.control) {
                        run.lease.ensureCurrent()
                        ProviderModelCapabilityClient(
                            run.lease.config,
                            requestControl = run.lease.control,
                        ).run(
                            providerId = providerId,
                            savedModelId = modelId,
                            onProgress = { next ->
                                scope.launch {
                                    if (isCurrent(run) && activeJob.isActive) onProgress(next)
                                }
                            },
                            ensureActive = {
                                activeJob.ensureActive()
                                run.lease.ensureCurrent()
                            },
                        )
                    }
                }
                activeJob.ensureActive()
                run.lease.ensureCurrent()
                if (isCurrent(run)) onSuccess(report)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrent(run)) onFailure(error)
            } finally {
                run.release()
                if (isCurrent(run)) onFinished()
            }
        }
        run.job = job
        synchronized(lock) { current = run }
        // A canceled parent can prevent the launch body/finally from ever executing.
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) run.lease.control.cancel()
            run.release()
            synchronized(lock) {
                if (current === run) current = null
            }
        }
        job.start()
        return job
    }

    /** Called by close, owner/enabled changes, and the dialog's DisposableEffect. */
    fun cancel() {
        val run = synchronized(lock) { current.also { current = null } } ?: return
        run.lease.control.cancel()
        run.job.cancel()
        run.release()
    }

    private fun isCurrent(run: Run): Boolean = synchronized(lock) { current === run }

    private class Run(val lease: GatewayRequestLease) {
        lateinit var job: Job
        private val released = AtomicBoolean()

        fun release() {
            if (released.compareAndSet(false, true)) lease.close()
        }
    }
}

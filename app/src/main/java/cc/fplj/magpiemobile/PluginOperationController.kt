package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.PluginManagementClient
import cc.fplj.magpiemobile.core.PluginMarketSnapshot
import cc.fplj.magpiemobile.core.PluginProvider
import cc.fplj.magpiemobile.core.PluginSnapshot
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One captured management session, reusable only while its owner and epoch remain current. */
internal interface PluginSessionOperation : Closeable {
    fun ensureCurrent()
    fun cancel()
    suspend fun plugins(): PluginSnapshot
    suspend fun providers(): List<PluginProvider>
    suspend fun market(query: String = ""): PluginMarketSnapshot
    suspend fun edit(edit: PluginEdit): PluginSnapshot
    suspend fun auth(step: PluginAuthStep): PluginAuthReply
}

/** Captures credentials on the caller before any coroutine can queue work on another dispatcher. */
internal class PluginOperationController(
    private val acquire: (ProviderSessionKey) -> PluginRequestLease = NativeCoreSession::pluginRequest,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun capture(session: ProviderSessionKey): PluginSessionOperation =
        CapturedPluginOperation(acquire(session), dispatcher)
}

private class CapturedPluginOperation(
    private val lease: PluginRequestLease,
    private val dispatcher: CoroutineDispatcher,
) : PluginSessionOperation {
    private val closed = AtomicBoolean()

    override fun ensureCurrent() = lease.ensureCurrent()

    override fun cancel() = lease.control.cancel()

    override fun close() {
        if (closed.compareAndSet(false, true)) lease.close()
    }

    override suspend fun plugins(): PluginSnapshot = request { it.plugins() }

    override suspend fun providers(): List<PluginProvider> = request { it.providers() }

    override suspend fun market(query: String): PluginMarketSnapshot = request { it.market(query) }

    override suspend fun edit(edit: PluginEdit): PluginSnapshot = request { client ->
        when (edit) {
            is PluginEdit.Add -> client.add(edit.spec)
            is PluginEdit.Remove -> client.remove(edit.spec)
            is PluginEdit.Upgrade -> client.upgrade(edit.spec)
            is PluginEdit.Off -> client.setOff(edit.spec, edit.off)
            is PluginEdit.Options -> client.saveOptions(edit.spec, edit.value)
            is PluginEdit.Prefer -> client.prefer(edit.spec, edit.provider)
            PluginEdit.UpdateAll -> client.updateAll()
            PluginEdit.RestoreGatewayAccess -> client.restoreGatewayAccess()
        }
    }

    override suspend fun auth(step: PluginAuthStep): PluginAuthReply = request { client ->
        when (step) {
            is PluginAuthStep.Prompt -> PluginAuthReply(
                prompt = client.nextPrompt(step.pid, step.method, step.inputs, step.key, step.value),
            )
            is PluginAuthStep.Start -> PluginAuthReply(
                state = client.signIn(step.pid, step.method, step.inputs, step.key),
            )
            is PluginAuthStep.Read -> PluginAuthReply(state = client.signInState(step.id))
            is PluginAuthStep.Callback -> {
                client.submitCallback(step.id, step.value)
                PluginAuthReply()
            }
            is PluginAuthStep.Cancel -> {
                client.cancelSignIn(step.id)
                PluginAuthReply()
            }
        }
    }

    private suspend fun <T> request(action: (PluginManagementClient) -> T): T = try {
        withContext(dispatcher) {
            withChatRequestCancellation(lease.control) {
                lease.ensureCurrent()
                action(lease.client).also { lease.ensureCurrent() }
            }
        }
    } catch (cancelled: CancellationException) {
        // withContext can be cancelled before its body installs the HTTP cancellation watcher.
        cancel()
        throw cancelled
    }
}

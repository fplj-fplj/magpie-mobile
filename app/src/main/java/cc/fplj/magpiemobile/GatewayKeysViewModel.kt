package cc.fplj.magpiemobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.fplj.magpiemobile.core.CallerKeyAction
import cc.fplj.magpiemobile.core.CallerKeyChange
import cc.fplj.magpiemobile.core.ManagementExecutionUnknown
import cc.fplj.magpiemobile.core.ManagementHttpException
import cc.fplj.magpiemobile.core.RegisteredGatewayKey
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GatewayKeysUiState(
    val session: ProviderSessionKey? = null,
    val loading: Boolean = false,
    val keys: List<RegisteredGatewayKey> = emptyList(),
    val notice: String = "",
    val error: String = "",
    val newlyCreatedKey: RegisteredGatewayKey? = null,
    val needsRefresh: Boolean = false,
)

/**
 * Manages client Caller Keys for external API access (OpenAI-compatible /v1 endpoints).
 * Safe credential handling: only explicitly created or copied keys obtain a plaintext secret.
 */
class GatewayKeysViewModel internal constructor(
    private val sessionReader: () -> ProviderSessionKey? = NativeCoreSession::providerSessionKey,
    private val capture: (ProviderSessionKey) -> CallerKeySessionOperation = CallerKeyOperationController()::capture,
) : ViewModel() {

    private val mutable = MutableStateFlow(GatewayKeysUiState())
    val state = mutable.asStateFlow()
    private var owner: ProviderSessionKey? = null
    private var generation = 0L
    private var activeOperation: CallerKeySessionOperation? = null
    private var activeJob: Job? = null

    /** Clear plaintext, drafts and callbacks even when the same service owner reauthenticates. */
    fun attach(session: ProviderSessionKey?) {
        if (owner == session) return
        ++generation
        activeOperation?.cancel()
        activeJob?.cancel()
        activeOperation = null
        activeJob = null
        owner = session
        mutable.value = GatewayKeysUiState(session = session)
        if (session != null) refresh()
    }

    private fun current(session: ProviderSessionKey, revision: Long, operation: CallerKeySessionOperation): Boolean {
        operation.ensureCurrent()
        return owner == session && generation == revision && sessionReader() == session
    }

    /** A single captured operation owns the entire read/create/copy or multi-field write. */
    private fun request(
        write: Boolean = false,
        clearNotice: Boolean = true,
        expectedSession: ProviderSessionKey? = owner,
        block: suspend (CallerKeySessionOperation, ProviderSessionKey, Long) -> Unit,
    ) {
        val session = expectedSession ?: return
        if (owner != session || mutable.value.loading || (write && mutable.value.needsRefresh) || sessionReader() != session) return
        var captured: CallerKeySessionOperation? = null
        val operation = try {
            capture(session).also { captured = it; it.ensureCurrent() }
        } catch (_: CancellationException) {
            captured?.close()
            return
        } catch (failure: Exception) {
            captured?.close()
            mutable.update { it.copy(error = errorText(failure)) }
            return
        }
        val revision = ++generation
        mutable.update { it.copy(loading = true, error = "", notice = if (clearNotice) "" else it.notice) }
        val released = AtomicBoolean()
        fun release() {
            if (!released.compareAndSet(false, true)) return
            operation.close()
            if (activeOperation === operation) {
                activeOperation = null
                activeJob = null
                if (owner == session && generation == revision) mutable.update { it.copy(loading = false) }
            }
        }
        activeOperation = operation
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (current(session, revision, operation)) block(operation, session, revision)
            } catch (cancelled: CancellationException) {
                operation.cancel()
                throw cancelled
            } catch (failure: Exception) {
                if (current(session, revision, operation)) mutable.update {
                    it.copy(
                        error = errorText(failure) + if (write) "；请刷新列表核对已执行的操作，再继续编辑。" else "",
                        needsRefresh = it.needsRefresh || write,
                    )
                }
            } finally { release() }
        }
        activeJob = job
        job.invokeOnCompletion { failure ->
            if (failure is CancellationException) operation.cancel()
            release()
        }
        job.start()
    }

    fun refresh(clearNotice: Boolean = true) {
        request(clearNotice = clearNotice) { operation, session, revision ->
            val list = operation.keys()
            if (current(session, revision, operation)) mutable.update {
                it.copy(keys = list.map { key -> key.copy(secret = "") }, needsRefresh = false)
            }
        }
    }

    fun createKey(name: String, expectedSession: ProviderSessionKey? = owner, onDone: (RegisteredGatewayKey) -> Unit = {}) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            mutable.update { it.copy(error = "密钥名称不能为空") }
            return
        }
        request(write = true, expectedSession = expectedSession) { operation, session, revision ->
            val created = operation.create(trimmed)
            if (!current(session, revision, operation)) return@request
            mutable.update { it.copy(newlyCreatedKey = created, notice = "已成功创建客户端密钥：${created.name}") }
            onDone(created)
            // A failed follow-up read never causes the successful add-key POST to be replayed.
            val list = operation.keys()
            if (current(session, revision, operation)) mutable.update {
                it.copy(keys = list.map { key -> key.copy(secret = "") })
            }
        }
    }

    fun copyKey(id: String, expectedSession: ProviderSessionKey? = owner, onDone: (String) -> Unit = {}) {
        if (id.isBlank()) return
        request(expectedSession = expectedSession) { operation, session, revision ->
            val copied = operation.copy(id)
            if (!current(session, revision, operation)) return@request
            mutable.update { it.copy(notice = "已成功获取密钥：${copied.name}") }
            if (copied.secret.isNotBlank()) onDone(copied.secret)
        }
    }

    fun dismissNewKeyDialog() {
        mutable.update { it.copy(newlyCreatedKey = null) }
    }

    /**
     * A1：按白名单动作逐项更新密钥限制（models-key / aliases-key / tier-key）。
     * 每项独立 POST、失败即停，绝不误清其他限制；成功后整表刷新。
     */
    fun updateKeyRestrictions(
        id: String,
        models: List<String>,
        aliases: Map<String, String>,
        tier: String,
        expectedSession: ProviderSessionKey? = owner,
        onDone: () -> Unit = {},
    ) {
        if (id.isBlank()) return
        request(write = true, expectedSession = expectedSession) { operation, session, revision ->
            if (models.isNotEmpty()) {
                operation.update(CallerKeyAction.MODELS, CallerKeyChange(key = id, models = models))
                if (!current(session, revision, operation)) return@request
            }
            if (aliases.isNotEmpty()) {
                operation.update(CallerKeyAction.ALIASES, CallerKeyChange(key = id, aliases = aliases))
                if (!current(session, revision, operation)) return@request
            }
            val list = operation.update(CallerKeyAction.TIER, CallerKeyChange(key = id, serviceTier = tier))
            if (!current(session, revision, operation)) return@request
            mutable.update { it.copy(keys = list.map { key -> key.copy(secret = "") }, notice = "密钥限制已更新") }
            onDone()
        }
    }

    private fun errorText(failure: Exception): String = when (failure) {
        is ManagementHttpException -> "管理请求失败：HTTP ${failure.httpCode}"
        is ManagementExecutionUnknown -> "密钥操作结果待核对，不会自动重复提交"
        else -> "密钥请求失败，请检查核心连接与输入"
    }
}

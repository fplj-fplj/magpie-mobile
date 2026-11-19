package cc.fplj.magpiemobile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.fplj.magpiemobile.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface PluginEdit {
    val spec: String
    data class Add(override val spec: String) : PluginEdit
    data class Remove(override val spec: String) : PluginEdit
    data class Upgrade(override val spec: String) : PluginEdit
    data class Off(override val spec: String, val off: Boolean) : PluginEdit
    data class Options(override val spec: String, val value: JsonObject) : PluginEdit
    data class Prefer(override val spec: String, val provider: String) : PluginEdit
    data object UpdateAll : PluginEdit { override val spec = "" }
    data object RestoreGatewayAccess : PluginEdit { override val spec = "" }
}

internal sealed interface PluginAuthStep {
    data class Prompt(val pid: String, val method: Int, val inputs: Map<String, String>, val key: String = "", val value: String = "") : PluginAuthStep
    data class Start(val pid: String, val method: Int, val inputs: Map<String, String>, val key: String) : PluginAuthStep
    data class Read(val id: String) : PluginAuthStep
    data class Callback(val id: String, val value: String) : PluginAuthStep
    data class Cancel(val id: String) : PluginAuthStep
}

internal data class PluginAuthReply(val prompt: PluginPromptResult? = null, val state: PluginSignInState? = null)

internal data class OfficialPluginsState(
    val snapshot: PluginSnapshot = PluginSnapshot(),
    val providers: List<PluginProvider> = emptyList(),
    val market: PluginMarketSnapshot = PluginMarketSnapshot(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val pending: Boolean = false,
    val pendingSpec: String = "",
    val notice: String = "",
    val error: String = "",
    val authProvider: PluginProvider? = null,
    val authMethod: Int = 0,
    val prompt: PluginPrompt? = null,
    val signIn: PluginSignInState? = null,
    val authBusy: Boolean = false,
    val loginRecovery: PluginLoginRecovery? = null,
    val authWorkers: Int = 0,
) {
    override fun toString() = "OfficialPluginsState(entries=${snapshot.entries.size}, busy=$busy, pending=$pending, login=${signIn?.state})"
}

/** Only nonsecret operation markers are saved. OAuth links and answers stay in memory. */
internal class PluginManagementViewModel(
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val capture: (ProviderSessionKey) -> PluginSessionOperation = PluginOperationController()::capture,
    private val clock: () -> Long = System::currentTimeMillis,
    private val reconcileImports: suspend (PluginSnapshot) -> Unit = {},
    private val removeImport: suspend (String, PluginSnapshot) -> Unit = { _, _ -> },
    private val rejectImport: suspend (String) -> Unit = {},
) : ViewModel() {
    private val mutable = MutableStateFlow(OfficialPluginsState(
        pending = savedState["plugin.pending"] ?: false,
        pendingSpec = savedState["plugin.spec"] ?: "",
        loginRecovery = PluginLoginRecovery.restore(savedState),
        authWorkers = PluginAuthWorkTracker.active.value,
    ))
    val state = mutable.asStateFlow()
    private var owner: ProviderSessionKey? = null
    private var generation = 0L
    private var marketGeneration = 0L
    private var refreshQueued = false
    private var pendingEdit: PluginEdit? = when (savedState.get<String>("plugin.operation")) {
        "add" -> PluginEdit.Add(savedState["plugin.spec"] ?: "")
        "remove" -> PluginEdit.Remove(savedState["plugin.spec"] ?: "")
        else -> null
    }
    private var prior: PluginSnapshot? = null
    private var authGeneration = 0L
    private var authRequest = 0L
    private var poll: Job? = null
    private var pollingStarted = false
    private var answers = emptyMap<String, String>()
    private var signInUrl = ""
    private var signInId = ""
    private var authOrigin: ProviderSessionKey? = null
    private val operationLock = Any()
    private val operations = mutableSetOf<PluginSessionOperation>()
    var onChanged: () -> Unit = {}

    private fun captureOperation(session: ProviderSessionKey): PluginSessionOperation? {
        var operation: PluginSessionOperation? = null
        return try {
            capture(session).also { operation = it; it.ensureCurrent() }
        } catch (_: CancellationException) {
            operation?.close()
            null
        } catch (error: Exception) {
            operation?.close()
            if (owner == session) mutable.update { it.copy(error = safeError(error)) }
            null
        }
    }

    /** Owns a synchronously captured lease even when the scope dies before a lazy body starts. */
    private fun launchOperation(
        operation: PluginSessionOperation,
        onFinished: () -> Unit = {},
        onReleased: () -> Unit = {},
        block: suspend () -> Unit,
    ): Job {
        val released = AtomicBoolean()
        fun release() {
            if (released.compareAndSet(false, true)) {
                synchronized(operationLock) { operations.remove(operation) }
                try { operation.close() } finally { onFinished() }
            }
        }
        synchronized(operationLock) { operations.add(operation) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                operation.ensureCurrent()
                block()
            } catch (cancelled: CancellationException) {
                operation.cancel()
                throw cancelled
            } finally {
                release()
            }
        }
        job.invokeOnCompletion { cause ->
            try {
                if (cause is CancellationException) operation.cancel()
                release()
            } finally { onReleased() }
        }
        job.start()
        return job
    }

    init {
        viewModelScope.launch {
            PluginAuthWorkTracker.active.collect { count -> mutable.update { it.copy(authWorkers = count) } }
        }
        // Older versions could retain a spec before the HTTP client rejected URL credentials.
        // Discard that recovery marker instead of restoring it into the public operation state.
        savedState.get<String>("plugin.spec")?.takeIf { it.isNotEmpty() }?.let { spec ->
            if (runCatching { PluginManagementClient.validateSpec(spec) }.isFailure) clearPending()
        }
    }

    fun attach(session: ProviderSessionKey?) {
        if (owner == session) return
        synchronized(operationLock) { operations.toList() }.forEach { it.cancel() }
        owner = session
        generation++
        marketGeneration++
        refreshQueued = false
        mutable.value.loginRecovery?.let { setLoginRecovery(it.copy(stage = PluginLoginRecovery.Stage.DETACHED)) }
        clearLogin(keepRecovery = true)
        mutable.update { it.copy(snapshot = PluginSnapshot(), providers = emptyList(), market = PluginMarketSnapshot(), loading = false, busy = false, error = "",
            pending = savedState["plugin.pending"] ?: false, pendingSpec = savedState["plugin.spec"] ?: "") }
        if (session != null) refresh()
    }

    fun refresh() {
        val session = owner ?: return
        if (mutable.value.busy) { refreshQueued = true; return }
        val operation = captureOperation(session) ?: return
        val revision = ++generation
        marketGeneration++
        mutable.update { it.copy(loading = true, error = "") }
        launchOperation(operation, onFinished = {
            if (owner == session && revision == generation) mutable.update { it.copy(loading = false) }
        }) {
            try {
                val plugins = operation.plugins()
                operation.ensureCurrent()
                if (owner != session || revision != generation) return@launchOperation
                mutable.update { it.copy(snapshot = plugins, providers = emptyList(), market = it.market.withInstalled(plugins)) }
                val providers = operation.providers()
                operation.ensureCurrent()
                if (owner != session || revision != generation) return@launchOperation
                val pending = mutable.value.pending
                val proved = pending && confirmed(pendingEdit, plugins, providers)
                val elapsed = pending && clock() - (savedState.get<Long>("plugin.started") ?: clock()) >= UNKNOWN_WAIT
                if (proved && pendingEdit is PluginEdit.Remove) removeImport(pendingEdit!!.spec, plugins)
                operation.ensureCurrent()
                if (owner != session || revision != generation) return@launchOperation
                if (proved || elapsed) clearPending()
                if (!mutable.value.pending) reconcileImports(plugins)
                operation.ensureCurrent()
                if (owner != session || revision != generation) return@launchOperation
                mutable.update { it.copy(snapshot = plugins, providers = providers,
                    notice = if (proved) "操作结果已核对" else if (elapsed) "安装任务时限已结束，已重新读取核心配置" else it.notice) }
                if (pending && (proved || elapsed)) onChanged()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (owner == session && revision == generation) mutable.update { it.copy(error = safeError(e)) } }
        }
    }

    fun market(query: String) {
        if (mutable.value.busy || mutable.value.loading) return
        val session = owner ?: return
        val operation = captureOperation(session) ?: return
        val revision = ++marketGeneration
        mutable.update { it.copy(loading = true, error = "") }
        launchOperation(operation, onFinished = {
            if (owner == session && revision == marketGeneration) mutable.update { it.copy(loading = false) }
        }) {
            try {
                val result = operation.market(query)
                operation.ensureCurrent()
                if (owner == session && revision == marketGeneration) mutable.update { it.copy(market = result) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (owner == session && revision == marketGeneration) mutable.update { it.copy(error = safeError(e)) } }
        }
    }

    fun currentSession(): ProviderSessionKey? = owner

    fun edit(edit: PluginEdit, expectedSession: ProviderSessionKey? = owner): Boolean {
        val session = owner ?: return false
        if (session != expectedSession) return false
        if (mutable.value.busy || mutable.value.pending || mutable.value.authBusy || mutable.value.loginRecovery != null) return false
        val savedSpec = if (edit != PluginEdit.UpdateAll && edit != PluginEdit.RestoreGatewayAccess) {
            try {
                PluginManagementClient.validateSpec(edit.spec)
            } catch (_: IllegalArgumentException) {
                mutable.update { it.copy(error = "请检查插件地址或参数格式；插件地址不能包含 URL 凭据") }
                return false
            }
        } else ""
        val operation = captureOperation(session) ?: return false
        val revision = ++generation
        marketGeneration++
        pendingEdit = edit
        prior = mutable.value.snapshot
        savedState["plugin.pending"] = true
        savedState["plugin.spec"] = savedSpec
        savedState["plugin.started"] = clock()
        when (edit) {
            is PluginEdit.Add -> savedState["plugin.operation"] = "add"
            is PluginEdit.Remove -> savedState["plugin.operation"] = "remove"
            else -> savedState.remove<String>("plugin.operation")
        }
        mutable.update { it.copy(busy = true, loading = false, pendingSpec = savedSpec, error = "", notice = "") }
        var known = false
        launchOperation(operation, onFinished = {
            if (owner == session && revision == generation) mutable.update { it.copy(busy = false,
                pending = !known && savedState.get<Boolean>("plugin.pending") == true) }
            if (owner == session && revision == generation && refreshQueued) { refreshQueued = false; refresh() }
        }) {
            try {
                val result = operation.edit(edit)
                operation.ensureCurrent()
                known = true
                if (owner != session || revision != generation) return@launchOperation
                if (edit is PluginEdit.Remove && result.entries.none { sameSpec(it.spec, edit.spec) }) removeImport(edit.spec, result)
                operation.ensureCurrent()
                if (owner != session || revision != generation) return@launchOperation
                reconcileImports(result)
                operation.ensureCurrent()
                if (owner != session || revision != generation) return@launchOperation
                clearPending()
                mutable.update { it.copy(snapshot = result, market = it.market.withInstalled(result), notice = "核心配置已更新") }
                onChanged()
                val providers = operation.providers()
                operation.ensureCurrent()
                if (owner == session && revision == generation) mutable.update { it.copy(providers = providers) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (owner == session && revision == generation) {
                    val unknown = !known && (e is ManagementExecutionUnknown || e is PluginWriteUnknownException)
                    if (!known && !unknown && edit is PluginEdit.Add) {
                        try { rejectImport(edit.spec) } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { /* Preserve the submitted journal if disk cleanup fails. */ }
                        // A definite HTTP rejection still needs a successful read before deleting files.
                        val actual = try { operation.plugins() } catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { null }
                        if (owner == session && revision == generation && actual != null &&
                            actual.entries.none { sameSpec(it.spec, edit.spec) }) {
                            try { removeImport(edit.spec, actual) } catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { /* Retry via a later successful reconciliation. */ }
                        }
                    }
                    operation.ensureCurrent()
                    if (owner != session || revision != generation) return@launchOperation
                    if (!unknown) clearPending()
                    mutable.update { it.copy(pending = unknown, error = if (known) "配置已保存，刷新失败；请重新读取" else safeError(e)) }
                }
            }
        }
        return true
    }

    fun saveOptions(spec: String, text: String) {
        val options = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (options == null) { mutable.update { it.copy(error = "插件参数必须是有效的 JSON 对象") }; return }
        edit(PluginEdit.Options(spec, options))
    }

    private fun confirmed(edit: PluginEdit?, snapshot: PluginSnapshot, providers: List<PluginProvider>): Boolean {
        if (edit == null) return false
        val entry = snapshot.entries.firstOrNull { sameSpec(it.spec, edit.spec) || sameSpec(it.packageId, edit.spec) }
        return when (edit) {
            is PluginEdit.Add -> entry != null
            is PluginEdit.Remove -> entry == null
            is PluginEdit.Off -> entry?.off == edit.off
            is PluginEdit.Options -> entry?.options == edit.value
            is PluginEdit.Prefer -> providers.any { it.pid == edit.provider && sameSpec(it.spec, edit.spec) }
            is PluginEdit.Upgrade -> entry != null && entry.version.isNotBlank() && prior?.entries?.firstOrNull { sameSpec(it.spec, edit.spec) }?.version != entry.version
            // One updated row does not prove that a batch update has finished.
            PluginEdit.UpdateAll -> false
            PluginEdit.RestoreGatewayAccess -> false
        }
    }

    private fun clearPending() {
        savedState["plugin.pending"] = false
        savedState["plugin.spec"] = ""
        savedState.remove<Long>("plugin.started")
        savedState.remove<String>("plugin.operation")
        pendingEdit = null
        prior = null
        mutable.update { it.copy(pending = false, pendingSpec = "") }
    }

    private fun setLoginRecovery(recovery: PluginLoginRecovery?) {
        if (recovery == null) PluginLoginRecovery.clear(savedState) else recovery.save(savedState)
        mutable.update { it.copy(loginRecovery = recovery) }
    }

    fun selectLogin(provider: PluginProvider, method: Int) {
        if (mutable.value.busy || mutable.value.pending || mutable.value.authBusy ||
            mutable.value.loginRecovery != null || method !in provider.methods.indices) return
        clearLogin()
        mutable.update { it.copy(authProvider = provider, authMethod = method, error = "") }
        prompt()
    }

    fun prompt(value: String? = null) {
        val current = mutable.value
        val provider = current.authProvider ?: return
        if (current.loginRecovery != null) return
        val question = current.prompt
        auth(PluginAuthStep.Prompt(provider.pid, current.authMethod, answers,
            if (value != null) question?.key.orEmpty() else "", value.orEmpty())) { _, reply ->
            val result = reply.prompt ?: return@auth
            if (result.error.isNotBlank()) { mutable.update { it.copy(error = "此项输入未通过插件校验，请检查后重试") }; return@auth }
            answers = result.inputs
            mutable.update { it.copy(prompt = result.prompt) }
        }
    }

    fun startLogin(key: String = "") {
        val current = mutable.value
        val provider = current.authProvider ?: return
        if (current.loginRecovery != null) return
        auth(PluginAuthStep.Start(provider.pid, current.authMethod, answers, key), beforeLaunch = { session ->
            authOrigin = session
            setLoginRecovery(PluginLoginRecovery(provider.pid, PluginLoginRecovery.Stage.STARTING))
        }) { operation, reply ->
            val result = reply.state ?: throw PluginWriteUnknownException()
            signInId = result.id
            answers = emptyMap()
            if (mutable.value.loginRecovery?.cancelRequested == true && !terminal(result)) {
                if (result.id.isEmpty()) throw PluginWriteUnknownException()
                cancelAndRead(operation, result.id)
            } else acceptSignIn(result)
        }
    }

    private fun terminal(result: PluginSignInState): Boolean = result.state in setOf("done", "failed", "cancelled", "canceled")

    private fun acceptSignIn(result: PluginSignInState) {
        signInId = result.id
        val pending = mutable.value.loginRecovery
        signInUrl = if (pending?.cancelRequested == true) "" else result.url.takeIf(::validPluginBrowserUrl).orEmpty()
        mutable.update { it.copy(signIn = result.copy(url = "", keysUrl = "", error = "", proxy = "", openError = ""),
            error = if (result.error.isBlank()) "" else "插件登录未完成，请只读核对当前状态") }
        if (terminal(result)) {
            poll?.cancel()
            poll = null
            setLoginRecovery(null)
            mutable.update { it.copy(notice = when (result.state) {
                "done" -> if (pending?.cancelRequested == true) "登录已经完成；没有将其误报为取消。" else "插件登录已完成"
                "cancelled", "canceled" -> "核心已确认登录任务取消"
                else -> "核心已返回登录失败状态"
            }) }
            if (result.state == "done") { onChanged(); refresh() }
        } else {
            pending?.let { setLoginRecovery(it.copy(stage = if (it.cancelRequested)
                PluginLoginRecovery.Stage.CANCEL_UNKNOWN else PluginLoginRecovery.Stage.WAITING)) }
            if (result.id.isNotEmpty() && result.error.isEmpty()) startPolling()
        }
    }

    /** Polling schedules read operations; each request captures the current key before launch. */
    private fun startPolling() {
        // A waiting reply must not restart the ten-minute observation budget.
        if (pollingStarted) return
        pollingStarted = true
        val session = owner
        val revision = authGeneration
        poll = viewModelScope.launch {
            repeat(300) {
                delay(2000)
                if (owner != session || revision != authGeneration || mutable.value.loginRecovery == null) return@launch
                if (!mutable.value.authBusy) refreshLogin()
            }
        }
    }

    fun refreshLogin() {
        if (mutable.value.authBusy) return
        if (signInId.isEmpty() || authOrigin == null || authOrigin != owner) {
            // There is no official lookup by provider ID. A provider list cannot prove this Start.
            refresh()
            return
        }
        auth(PluginAuthStep.Read(signInId)) { _, reply ->
            reply.state?.let(::acceptSignIn) ?: throw PluginReadException()
        }
    }

    fun submitCallback(value: String) {
        val recovery = mutable.value.loginRecovery ?: return
        if (signInId.isEmpty() || value.isBlank() || authOrigin != owner ||
            recovery.cancelRequested || recovery.stage != PluginLoginRecovery.Stage.WAITING) return
        auth(PluginAuthStep.Callback(signInId, value)) { operation, _ ->
            val result = operation.auth(PluginAuthStep.Read(signInId)).state ?: throw PluginReadException()
            operation.ensureCurrent()
            acceptSignIn(result)
        }
    }

    fun cancelLogin() {
        val current = mutable.value
        if (current.signIn?.let(::terminal) == true) { clearLogin(); return }
        val recovery = current.loginRecovery
        if (recovery == null) { clearLogin(); return }
        setLoginRecovery(recovery.copy(cancelRequested = true))
        // Do not cancel Start's socket or lose a late task ID. It has a bounded response timeout.
        if (current.authBusy || recovery.cancelAttempted || authOrigin != owner || signInId.isEmpty()) return
        auth(PluginAuthStep.Cancel(signInId)) { _, _ -> }
    }

    private suspend fun cancelAndRead(operation: PluginSessionOperation, id: String) {
        val recovery = mutable.value.loginRecovery ?: return
        if (!recovery.cancelAttempted) {
            setLoginRecovery(recovery.copy(cancelRequested = true, cancelAttempted = true,
                stage = PluginLoginRecovery.Stage.CANCEL_UNKNOWN))
            try {
                operation.auth(PluginAuthStep.Cancel(id))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* POST may have run. Only its following GET may confirm state. */ }
        }
        operation.ensureCurrent()
        val result = operation.auth(PluginAuthStep.Read(id)).state ?: throw PluginReadException()
        operation.ensureCurrent()
        acceptSignIn(result)
    }

    /** Explicitly abandon local observation, never a remote cancellation or a repeated Start. */
    fun endLoginTracking() {
        if (PluginAuthWorkTracker.active.value != 0 || mutable.value.authBusy || mutable.value.loginRecovery == null) return
        clearLogin()
        mutable.update { it.copy(notice = "已结束本地跟踪；这不撤销原核心登录，旧任务仍可能继续。", error = "") }
    }

    fun hideLogin() {
        if (mutable.value.loginRecovery != null) mutable.update { it.copy(authProvider = null, prompt = null) }
    }

    fun openLoginBrowser(open: (String) -> Unit) {
        if (mutable.value.loginRecovery?.cancelRequested == true || authOrigin != owner) return
        if (!validPluginBrowserUrl(signInUrl)) { mutable.update { it.copy(error = "插件没有提供可打开的登录地址") }; return }
        try { open(signInUrl) } catch (_: Exception) { mutable.update { it.copy(error = "未能打开浏览器，请确认浏览器可用后重试") } }
    }

    private fun auth(
        step: PluginAuthStep,
        beforeLaunch: (ProviderSessionKey) -> Unit = {},
        receive: suspend (PluginSessionOperation, PluginAuthReply) -> Unit,
    ) {
        val session = owner ?: return
        if (mutable.value.authBusy || mutable.value.busy || mutable.value.pending) return
        val operation = captureOperation(session) ?: return
        val revision = authGeneration
        val request = ++authRequest
        beforeLaunch(session)
        mutable.update { it.copy(authBusy = true, error = "") }
        val finished = PluginAuthWorkTracker.register()
        launchOperation(operation, onReleased = finished, onFinished = {
            if (owner == session && revision == authGeneration && request == authRequest) {
                mutable.update { it.copy(authBusy = false) }
                mutable.value.loginRecovery?.takeIf { it.stage == PluginLoginRecovery.Stage.STARTING }?.let {
                    setLoginRecovery(it.copy(stage = PluginLoginRecovery.Stage.START_UNKNOWN))
                }
            }
        }) {
            try {
                if (step is PluginAuthStep.Cancel) {
                    cancelAndRead(operation, step.id)
                } else {
                    val result = operation.auth(step)
                    operation.ensureCurrent()
                    if (owner != session || revision != authGeneration || request != authRequest) return@launchOperation
                    receive(operation, result)
                    operation.ensureCurrent()
                    val recovery = mutable.value.loginRecovery
                    if (recovery?.cancelRequested == true && !recovery.cancelAttempted && signInId.isNotEmpty()) {
                        cancelAndRead(operation, signInId)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (owner == session && revision == authGeneration && request == authRequest) {
                    mutable.value.loginRecovery?.let { recovery ->
                        val stage = when (step) {
                            is PluginAuthStep.Start -> if (recovery.cancelAttempted) PluginLoginRecovery.Stage.CANCEL_UNKNOWN
                                else PluginLoginRecovery.Stage.START_UNKNOWN
                            is PluginAuthStep.Cancel -> PluginLoginRecovery.Stage.CANCEL_UNKNOWN
                            is PluginAuthStep.Callback -> PluginLoginRecovery.Stage.CALLBACK_UNKNOWN
                            else -> recovery.stage
                        }
                        setLoginRecovery(recovery.copy(stage = stage))
                    }
                    mutable.update { it.copy(error = if (it.loginRecovery != null) "登录结果待核对；不会自动重复提交。" else safeError(e)) }
                }
            }
        }
    }

    private fun clearLogin(keepRecovery: Boolean = false) {
        poll?.cancel()
        poll = null
        pollingStarted = false
        authGeneration++
        answers = emptyMap()
        signInUrl = ""
        signInId = ""
        authOrigin = null
        if (!keepRecovery) setLoginRecovery(null)
        mutable.update { it.copy(authProvider = null, prompt = null, signIn = null, authBusy = false) }
    }

    fun notice(value: String) { mutable.update { it.copy(notice = value, error = "") } }
    fun importError() { mutable.update { it.copy(error = "导入失败，请选择可读取的 JS / TS 文件或包含 package.json 的插件目录") } }

    private fun safeError(error: Exception): String = when (error) {
        is PluginWriteUnknownException, is ManagementExecutionUnknown -> "操作结果待核对，核心任务可能仍在执行。请重新读取，不要重复安装。"
        is ManagementHttpException -> "核心请求失败：HTTP ${error.httpCode}，请检查插件地址与配置后重新读取"
        is PluginReadException -> "插件状态读取失败，请确认核心运行后重试"
        is IllegalArgumentException -> "请检查插件地址或参数格式"
        else -> "插件操作失败，请重新读取核心状态"
    }

    private fun PluginMarketSnapshot.withInstalled(snapshot: PluginSnapshot): PluginMarketSnapshot = copy(
        installed = snapshot.entries,
        matchedSpecs = snapshot.entries.filter { entry -> listings.any { entry.matchesListing(it) } }.map { it.spec },
    )

    companion object {
        private const val UNKNOWN_WAIT = 6L * 60 * 1000
        private fun sameSpec(first: String, second: String): Boolean {
            fun normalized(value: String): String {
                var result = value.trim().removePrefix("npm:").removePrefix("github:").removeSuffix(".git")
                if (result.startsWith("file:")) result = runCatching { URI(result).path }.getOrNull() ?: result
                val at = result.lastIndexOf('@')
                if (!result.contains("://") && at > result.lastIndexOf('/') && at > 0) result = result.take(at)
                return result
            }
            return first.isNotBlank() && normalized(first) == normalized(second)
        }
    }
}

internal fun validPluginBrowserUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
}.getOrDefault(false)

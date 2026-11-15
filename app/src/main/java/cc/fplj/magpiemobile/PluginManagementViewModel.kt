package cc.fplj.magpiemobile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.fplj.magpiemobile.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.URI

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
) {
    override fun toString() = "OfficialPluginsState(entries=${snapshot.entries.size}, busy=$busy, pending=$pending, login=${signIn?.state})"
}

private suspend fun liveEdit(edit: PluginEdit): PluginSnapshot = withContext(Dispatchers.IO) {
    val client = NativeCoreSession.pluginClient()
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

private suspend fun liveAuth(step: PluginAuthStep): PluginAuthReply = withContext(Dispatchers.IO) {
    val client = NativeCoreSession.pluginClient()
    when (step) {
        is PluginAuthStep.Prompt -> PluginAuthReply(prompt = client.nextPrompt(step.pid, step.method, step.inputs, step.key, step.value))
        is PluginAuthStep.Start -> PluginAuthReply(state = client.signIn(step.pid, step.method, step.inputs, step.key))
        is PluginAuthStep.Read -> PluginAuthReply(state = client.signInState(step.id))
        is PluginAuthStep.Callback -> { client.submitCallback(step.id, step.value); PluginAuthReply() }
        is PluginAuthStep.Cancel -> { client.cancelSignIn(step.id); PluginAuthReply() }
    }
}

/** Only nonsecret operation markers are saved. OAuth links and answers stay in memory. */
internal class PluginManagementViewModel(
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val readPlugins: suspend () -> PluginSnapshot = { withContext(Dispatchers.IO) { NativeCoreSession.pluginClient().plugins() } },
    private val readProviders: suspend () -> List<PluginProvider> = { withContext(Dispatchers.IO) { NativeCoreSession.pluginClient().providers() } },
    private val readMarket: suspend (String) -> PluginMarketSnapshot = { query -> withContext(Dispatchers.IO) { NativeCoreSession.pluginClient().market(query) } },
    private val performEdit: suspend (PluginEdit) -> PluginSnapshot = ::liveEdit,
    private val performAuth: suspend (PluginAuthStep) -> PluginAuthReply = ::liveAuth,
    private val clock: () -> Long = System::currentTimeMillis,
    private val reconcileImports: suspend (PluginSnapshot) -> Unit = {},
    private val removeImport: suspend (String, PluginSnapshot) -> Unit = { _, _ -> },
    private val rejectImport: suspend (String) -> Unit = {},
) : ViewModel() {
    private val mutable = MutableStateFlow(OfficialPluginsState(
        pending = savedState["plugin.pending"] ?: false,
        pendingSpec = savedState["plugin.spec"] ?: "",
    ))
    val state = mutable.asStateFlow()
    private var owner: Long? = null
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
    private var answers = emptyMap<String, String>()
    private var signInUrl = ""
    private var signInId = ""
    var onChanged: () -> Unit = {}

    fun attach(session: Long?) {
        if (owner == session) return
        owner = session
        generation++
        marketGeneration++
        clearLogin()
        mutable.update { it.copy(snapshot = PluginSnapshot(), providers = emptyList(), loading = false, busy = false, error = "",
            pending = savedState["plugin.pending"] ?: false, pendingSpec = savedState["plugin.spec"] ?: "") }
        if (session != null) refresh()
    }

    fun refresh() {
        val session = owner ?: return
        if (mutable.value.busy) { refreshQueued = true; return }
        val revision = ++generation
        mutable.update { it.copy(loading = true, error = "") }
        viewModelScope.launch {
            try {
                val plugins = readPlugins()
                if (owner != session || revision != generation) return@launch
                mutable.update { it.copy(snapshot = plugins, providers = emptyList()) }
                val providers = readProviders()
                if (owner != session || revision != generation) return@launch
                val pending = mutable.value.pending
                val proved = pending && confirmed(pendingEdit, plugins, providers)
                val elapsed = pending && clock() - (savedState.get<Long>("plugin.started") ?: clock()) >= UNKNOWN_WAIT
                if (proved && pendingEdit is PluginEdit.Remove) removeImport(pendingEdit!!.spec, plugins)
                if (proved || elapsed) clearPending()
                if (!mutable.value.pending) reconcileImports(plugins)
                if (owner != session || revision != generation) return@launch
                mutable.update { it.copy(snapshot = plugins, providers = providers,
                    notice = if (proved) "操作结果已核对" else if (elapsed) "安装任务时限已结束，已重新读取核心配置" else it.notice) }
                if (pending && (proved || elapsed)) onChanged()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (revision == generation) mutable.update { it.copy(error = safeError(e)) } }
            finally { if (revision == generation) mutable.update { it.copy(loading = false) } }
        }
    }

    fun market(query: String) {
        if (owner == null || mutable.value.busy || mutable.value.loading) return
        val session = owner
        val revision = ++marketGeneration
        mutable.update { it.copy(loading = true, error = "") }
        viewModelScope.launch {
            try {
                val result = readMarket(query)
                if (owner == session && revision == marketGeneration) mutable.update { it.copy(market = result) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (owner == session && revision == marketGeneration) mutable.update { it.copy(error = safeError(e)) } }
            finally { if (owner == session && revision == marketGeneration) mutable.update { it.copy(loading = false) } }
        }
    }

    fun edit(edit: PluginEdit): Boolean {
        val session = owner ?: return false
        if (mutable.value.busy || mutable.value.pending || mutable.value.authBusy) return false
        val revision = ++generation
        pendingEdit = edit
        prior = mutable.value.snapshot
        savedState["plugin.pending"] = true
        savedState["plugin.spec"] = edit.spec
        savedState["plugin.started"] = clock()
        when (edit) {
            is PluginEdit.Add -> savedState["plugin.operation"] = "add"
            is PluginEdit.Remove -> savedState["plugin.operation"] = "remove"
            else -> savedState.remove<String>("plugin.operation")
        }
        mutable.update { it.copy(busy = true, pendingSpec = edit.spec, error = "", notice = "") }
        viewModelScope.launch {
            var known = false
            try {
                val result = performEdit(edit)
                known = true
                if (owner != session || revision != generation) return@launch
                if (edit is PluginEdit.Remove && result.entries.none { sameSpec(it.spec, edit.spec) }) removeImport(edit.spec, result)
                reconcileImports(result)
                if (owner != session || revision != generation) return@launch
                clearPending()
                mutable.update { it.copy(snapshot = result, notice = "核心配置已更新") }
                onChanged()
                val providers = readProviders()
                if (revision == generation) mutable.update { it.copy(providers = providers) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (revision == generation) {
                    val unknown = !known && (e is ManagementExecutionUnknown || e is PluginWriteUnknownException)
                    if (!known && !unknown && edit is PluginEdit.Add) {
                        try { rejectImport(edit.spec) } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { /* Preserve the submitted journal if disk cleanup fails. */ }
                        // A definite HTTP rejection still needs a successful read before deleting files.
                        val actual = try { readPlugins() } catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { null }
                        if (owner == session && revision == generation && actual != null &&
                            actual.entries.none { sameSpec(it.spec, edit.spec) }) {
                            try { removeImport(edit.spec, actual) } catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { /* Retry via a later successful reconciliation. */ }
                        }
                    }
                    if (owner != session || revision != generation) return@launch
                    if (!unknown) clearPending()
                    mutable.update { it.copy(pending = unknown, error = if (known) "配置已保存，刷新失败；请重新读取" else safeError(e)) }
                }
            } finally {
                if (revision == generation) mutable.update { it.copy(busy = false,
                    pending = !known && savedState.get<Boolean>("plugin.pending") == true) }
                if (owner == session && revision == generation && refreshQueued) { refreshQueued = false; refresh() }
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

    fun selectLogin(provider: PluginProvider, method: Int) {
        if (mutable.value.busy || mutable.value.pending || mutable.value.authBusy || method !in provider.methods.indices) return
        clearLogin()
        mutable.update { it.copy(authProvider = provider, authMethod = method, error = "") }
        prompt()
    }

    fun prompt(value: String? = null) {
        val current = mutable.value
        val provider = current.authProvider ?: return
        val question = current.prompt
        auth(PluginAuthStep.Prompt(provider.pid, current.authMethod, answers,
            if (value != null) question?.key.orEmpty() else "", value.orEmpty())) { reply ->
            val result = reply.prompt ?: return@auth
            if (result.error.isNotBlank()) { mutable.update { it.copy(error = "此项输入未通过插件校验，请检查后重试") }; return@auth }
            answers = result.inputs
            mutable.update { it.copy(prompt = result.prompt) }
        }
    }

    fun startLogin(key: String = "") {
        val current = mutable.value
        val provider = current.authProvider ?: return
        auth(PluginAuthStep.Start(provider.pid, current.authMethod, answers, key)) { reply ->
            reply.state?.let { acceptSignIn(it) }
            answers = emptyMap()
        }
    }

    private fun acceptSignIn(result: PluginSignInState) {
        signInId = result.id
        signInUrl = result.url.takeIf(::validPluginBrowserUrl).orEmpty()
        mutable.update { it.copy(signIn = result.copy(url = "", keysUrl = "", error = "", proxy = "", openError = ""),
            error = if (result.error.isBlank()) "" else "插件登录失败，请重新选择登录方式") }
        if (result.state == "done") { onChanged(); refresh() }
        else if (result.id.isNotEmpty() && result.error.isEmpty() && result.state !in setOf("failed", "cancelled", "canceled")) startPolling()
    }

    private fun startPolling() {
        poll?.cancel()
        val session = owner
        val revision = authGeneration
        val id = signInId
        poll = viewModelScope.launch {
            repeat(300) {
                delay(2000)
                if (owner != session || revision != authGeneration) return@launch
                if (mutable.value.authBusy) return@repeat
                try {
                    val result = performAuth(PluginAuthStep.Read(id)).state ?: return@repeat
                    if (revision != authGeneration) return@launch
                    signInUrl = result.url.takeIf(::validPluginBrowserUrl).orEmpty()
                    mutable.update { it.copy(signIn = result.copy(url = "", keysUrl = "", error = "", proxy = "", openError = ""),
                        error = if (result.error.isBlank()) "" else "插件登录未完成，请重新发起") }
                    if (result.state == "done") { onChanged(); refresh(); return@launch }
                    if (result.error.isNotBlank() || result.state in setOf("failed", "cancelled", "canceled")) return@launch
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (revision == authGeneration) mutable.update { it.copy(error = "登录状态读取失败，可以手动重新读取") }; return@launch }
            }
        }
    }

    fun refreshLogin() {
        if (signInId.isEmpty()) return
        auth(PluginAuthStep.Read(signInId)) { reply -> reply.state?.let(::acceptSignIn) }
    }

    fun submitCallback(value: String) {
        if (signInId.isEmpty() || value.isBlank()) return
        auth(PluginAuthStep.Callback(signInId, value)) { refreshLogin() }
    }

    fun cancelLogin() {
        if (signInId.isEmpty()) { clearLogin(); return }
        auth(PluginAuthStep.Cancel(signInId)) { clearLogin() }
    }

    fun openLoginBrowser(open: (String) -> Unit) {
        if (!validPluginBrowserUrl(signInUrl)) { mutable.update { it.copy(error = "插件没有提供可打开的登录地址") }; return }
        try { open(signInUrl) } catch (_: Exception) { mutable.update { it.copy(error = "未能打开浏览器，请确认浏览器可用后重试") } }
    }

    private fun auth(step: PluginAuthStep, receive: (PluginAuthReply) -> Unit) {
        val session = owner ?: return
        if (mutable.value.authBusy || mutable.value.busy || mutable.value.pending) return
        val revision = authGeneration
        val request = ++authRequest
        mutable.update { it.copy(authBusy = true, error = "") }
        viewModelScope.launch {
            try {
                val result = performAuth(step)
                if (owner == session && revision == authGeneration) {
                    mutable.update { it.copy(authBusy = false) }
                    receive(result)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (revision == authGeneration) mutable.update { it.copy(error = safeError(e)) } }
            finally { if (revision == authGeneration && request == authRequest) mutable.update { it.copy(authBusy = false) } }
        }
    }

    private fun clearLogin() {
        poll?.cancel()
        poll = null
        authGeneration++
        answers = emptyMap()
        signInUrl = ""
        signInId = ""
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

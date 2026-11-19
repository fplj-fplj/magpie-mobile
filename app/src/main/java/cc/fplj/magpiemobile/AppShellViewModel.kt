package cc.fplj.magpiemobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.fplj.magpiemobile.core.ModelEntry
import cc.fplj.magpiemobile.preferences.AppPage
import cc.fplj.magpiemobile.preferences.AppUiPreferences
import cc.fplj.magpiemobile.preferences.ThemeMode
import cc.fplj.magpiemobile.preferences.UiPreferencesState
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppShellState(
    val page: AppPage = AppPage.GATEWAY,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val preferences: UiPreferencesState = UiPreferencesState(),
    val saveNotice: String = "",
    val models: List<ModelEntry> = emptyList(),
    val selectedModel: String = "",
    val catalogValid: Boolean = false,
    /** Non-secret identity of the confirmed catalog; unconfirmed caches have no usable key. */
    val catalogSession: ProviderSessionKey? = null,
    val loadingModels: Boolean = false,
    val modelsNotice: String = "请先启动网关并完成安全连接。",
)

/** UI state and catalog reads survive tab switches/rotation. No method starts a Service or sends Chat. */
class AppShellViewModel internal constructor(
    application: Application,
    private val preferences: AppUiPreferences,
    private val currentCatalogSession: () -> ProviderSessionKey?,
    private val captureCatalog: (ProviderSessionKey) -> CatalogConnection,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, AppUiPreferences.open(application),
        NativeCoreSession::providerSessionKey, CatalogOperationController()::capture)

    private val mutable = MutableStateFlow(AppShellState())
    val state = mutable.asStateFlow()
    private var hydrated = false
    private var pageTouched = false
    private var themeTouched = false
    private var modelTouched = false
    private var restoredModel: String? = null
    private var modelRestorePending = true
    private var initialCatalog = true
    private var session: ProviderSessionKey? = null
    private var request = 0L
    private var catalogJob: Job? = null
    private var catalogOperation: CatalogConnection? = null

    init {
        viewModelScope.launch {
            preferences.state.collect { snapshot ->
                mutable.update { it.copy(preferences = snapshot) }
                if (snapshot.ready && !hydrated) {
                    hydrated = true
                    restoredModel = snapshot.value.modelId
                    mutable.update { it.copy(
                        page = if (pageTouched) it.page else snapshot.value.page,
                        theme = if (themeTouched) it.theme else snapshot.value.theme,
                    ) }
                    if (mutable.value.catalogValid) reconcileSelection()
                }
            }
        }
    }

    fun selectPage(page: AppPage) {
        pageTouched = true
        mutable.update { it.copy(page = page) }
        save { preferences.setPage(page) }
    }

    fun selectTheme(theme: ThemeMode) {
        themeTouched = true
        mutable.update { it.copy(theme = theme) }
        save { preferences.setTheme(theme) }
    }

    fun selectModel(id: String) {
        val snapshot = mutable.value
        if (!hasCurrentCatalog() || snapshot.models.none { it.id == id }) return
        modelTouched = true; modelRestorePending = false; initialCatalog = false
        mutable.update { it.copy(selectedModel = id) }
        save { preferences.setModelId(id) }
    }

    private fun save(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                mutable.update { it.copy(saveNotice = "") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(saveNotice = "本机偏好未能保存，当前临时选择仍可使用；原文件保留，未自动重写或重试。") } }
        }
    }

    fun retryPreferences() { preferences.retryRead() }

    /** Called for a live connection change only, never because a page became visible. */
    fun connectionChanged() {
        val key = currentCatalogSession()
        if (bindCatalog(key) && key != null) readModels(key)
    }

    fun refreshModels() {
        val key = currentCatalogSession()
        bindCatalog(key)
        if (key != null) readModels(key)
    }

    private fun bindCatalog(key: ProviderSessionKey?): Boolean {
        if (session == key) return false
        session = key
        ++request
        cancelCatalog()
        mutable.update { it.copy(catalogValid = false, catalogSession = null, loadingModels = false, modelsNotice =
            if (key == null) "请先启动网关并完成安全连接。" else "正在核对当前连接的模型目录，缓存尚未确认。") }
        return true
    }

    private fun cancelCatalog() {
        val operation = catalogOperation
        val job = catalogJob
        catalogOperation = null
        catalogJob = null
        try { operation?.cancel() } finally { job?.cancel() }
    }

    private fun isCurrentRequest(key: ProviderSessionKey, sequence: Long): Boolean =
        sequence == request && session == key && currentCatalogSession() == key

    private fun finishCatalog(key: ProviderSessionKey, sequence: Long) {
        if (sequence != request || session != key) return
        mutable.update { if (currentCatalogSession() == key) it.copy(loadingModels = false)
            else it.copy(catalogValid = false, catalogSession = null, loadingModels = false, modelsNotice = "核心连接已变化，模型目录尚未确认。") }
    }

    private fun readModels(key: ProviderSessionKey) {
        val sequence = ++request
        cancelCatalog()
        mutable.update { it.copy(catalogValid = false, catalogSession = null, loadingModels = true, modelsNotice = "正在读取模型目录，缓存尚未确认。") }
        var captured: CatalogConnection? = null
        val operation = try {
            captureCatalog(key).also {
                captured = it
                check(it.key == key) { "Catalog session differs from captured connection" }
                it.ensureCurrent()
            }
        } catch (cancelled: CancellationException) {
            try { captured?.cancel() } finally { captured?.close() }
            if (isCurrentRequest(key, sequence)) mutable.update { it.copy(modelsNotice = "模型目录读取已停止，缓存尚未确认；可手动刷新。") }
            finishCatalog(key, sequence)
            return
        } catch (_: Exception) {
            captured?.close()
            if (isCurrentRequest(key, sequence)) mutable.update { it.copy(modelsNotice = "模型目录读取失败，缓存未确认；请手动刷新。") }
            finishCatalog(key, sequence)
            return
        }
        val released = AtomicBoolean()
        fun release() {
            if (released.compareAndSet(false, true)) {
                try { operation.close() } finally {
                    if (catalogOperation === operation) { catalogOperation = null; catalogJob = null }
                    finishCatalog(key, sequence)
                }
            }
        }
        catalogOperation = operation
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                operation.ensureCurrent()
                val models = operation.read()
                operation.ensureCurrent()
                if (isCurrentRequest(key, sequence)) {
                    mutable.update { it.copy(models = models, catalogValid = true, catalogSession = key, modelsNotice =
                        if (models.isEmpty()) "没有已配置模型，请添加提供商。" else "可选模型 " + models.size) }
                    reconcileSelection()
                }
            } catch (e: CancellationException) {
                operation.cancel()
                if (isCurrentRequest(key, sequence)) mutable.update { it.copy(modelsNotice = "模型目录读取已停止，缓存尚未确认；可手动刷新。") }
                throw e
            }
            catch (_: Exception) {
                if (isCurrentRequest(key, sequence)) mutable.update { it.copy(modelsNotice = "模型目录读取失败，缓存未确认；请手动刷新。") }
            } finally { release() }
        }
        catalogJob = job
        job.invokeOnCompletion { cause ->
            try { if (cause is CancellationException) operation.cancel() } finally { release() }
        }
        job.start()
    }

    private fun hasCurrentCatalog(): Boolean {
        val snapshot = mutable.value
        val current = snapshot.catalogValid && snapshot.catalogSession != null &&
            snapshot.catalogSession == session && currentCatalogSession() == session
        if (snapshot.catalogValid && !current) mutable.update { it.copy(catalogValid = false, catalogSession = null,
            modelsNotice = "核心连接已变化，模型目录尚未确认。") }
        return current
    }

    private fun reconcileSelection() {
        if (!hasCurrentCatalog()) return
        val snapshot = mutable.value
        val ids = snapshot.models.map { it.id }.toSet()
        if (snapshot.selectedModel.isNotBlank()) {
            if (snapshot.selectedModel !in ids) mutable.update { it.copy(selectedModel = "", modelsNotice = "之前选择的模型已不可用，请重新选择；没有自动换模型。") }
            // User actions win over a late DataStore first read.
            if (modelTouched) { modelRestorePending = false; initialCatalog = false }
            return
        }
        if (!modelTouched && modelRestorePending && restoredModel != null) {
            val saved = restoredModel.orEmpty()
            modelRestorePending = false
            initialCatalog = false
            val chosen = if (saved.isBlank()) snapshot.models.firstOrNull()?.id.orEmpty() else saved.takeIf { it in ids }.orEmpty()
            mutable.update { it.copy(selectedModel = chosen, modelsNotice = if (saved.isNotBlank() && chosen.isBlank())
                "上次保存的模型已不可用，请重新选择；没有自动换模型。" else it.modelsNotice) }
        } else if (!modelTouched && initialCatalog && hydrated) {
            initialCatalog = false
            mutable.update { it.copy(selectedModel = snapshot.models.firstOrNull()?.id.orEmpty()) }
        }
    }

    override fun onCleared() {
        cancelCatalog()
        super.onCleared()
    }
}

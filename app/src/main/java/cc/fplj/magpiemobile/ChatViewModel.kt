package cc.fplj.magpiemobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.fplj.magpiemobile.core.ChatClient
import cc.fplj.magpiemobile.core.ChatTurnStore
import cc.fplj.magpiemobile.core.ChatException
import cc.fplj.magpiemobile.core.StreamingChatClient
import cc.fplj.magpiemobile.core.StreamingChatException
import cc.fplj.magpiemobile.core.StreamingChatFailure
import cc.fplj.magpiemobile.core.StreamingChatSession
import cc.fplj.magpiemobile.data.ChatRepository
import cc.fplj.magpiemobile.data.ContextLimitException
import cc.fplj.magpiemobile.data.ConversationSummary
import cc.fplj.magpiemobile.data.StoredChatMessage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val ready: Boolean = false,
    val busy: Boolean = true,
    val sending: Boolean = false,
    val conversations: List<ConversationSummary> = emptyList(),
    val moreConversations: Boolean = false,
    val selected: ConversationSummary? = null,
    val messages: List<StoredChatMessage> = emptyList(),
    val olderMessages: Boolean = false,
    val viewingOlderMessages: Boolean = false,
    val draft: String = "",
    val partial: String = "",
    val partialReasoning: String = "",
    val notice: String = "",
    val importNotice: String = "",
    val contextBlocked: Boolean = false,
)

/** Once begin commits, cancellation must close that identity even before core receives it. */
internal fun cancellableTurnStore(
    base: ChatTurnStore,
    context: kotlin.coroutines.CoroutineContext,
    ensureCurrent: () -> Unit = {},
    onCommitted: () -> Unit = {},
): ChatTurnStore = object : ChatTurnStore by base {
    private fun guard() {
        context.ensureActive()
        ensureCurrent()
        context.ensureActive()
    }

    override fun begin(user: cc.fplj.magpiemobile.core.SessionRecord): String {
        guard()
        val id = base.begin(user)
        try {
            guard()
        } catch (cancelled: CancellationException) {
            try {
                base.fail(id, cc.fplj.magpiemobile.core.SessionRecord(System.currentTimeMillis(),
                    "error", user.engine, "Chat failed; partial characters=0"))
            } catch (_: Exception) {
                // Keep UNKNOWN/RUNNING for later process recovery if storage is unavailable.
            }
            throw cancelled
        }
        return id
    }

    override fun complete(turnId: String, assistant: cc.fplj.magpiemobile.core.SessionRecord) {
        // Before entry cancellation prevents the transaction; once committed Room is authoritative.
        guard()
        base.complete(turnId, assistant)
        onCommitted()
    }
}

/** Activity ViewModel owns requests: rotation never launches another billable send. */
class ChatViewModel internal constructor(
    application: Application,
    private val openRepository: (Application) -> ChatRepository,
    private val acquire: (ProviderSessionKey) -> GatewayRequestLease,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val turnStore: (ChatRepository, String) -> ChatTurnStore = { repo, id -> repo.turnStore(id) },
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, { ChatRepository.open(it) }, NativeCoreSession::gatewayRequest)
    private val mutable = MutableStateFlow(ChatUiState())
    val state = mutable.asStateFlow()
    private var repository: ChatRepository? = null
    private var readRunning = false // Main-thread guard, including before openRepository returns.
    private val drafts = mutableMapOf<String, String>() // No Bundle, Intent, log or disk draft copies.

    init { refresh() }

    fun editDraft(text: String) {
        if (!mutable.value.sending && !mutable.value.busy) mutable.update { it.copy(draft = text.take(65536)) }
    }

    fun refresh() {
        if (readRunning || mutable.value.sending || (repository != null && mutable.value.busy)) return
        readRunning = true
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val report = withContext(dispatcher) {
                    val repo = repository ?: openRepository(getApplication()).also { repository = it }
                    repo.initialize(File(getApplication<Application>().filesDir, "chat.jsonl"))
                }
                val importText = report.warning ?: if (report.imported > 0)
                    "旧记录已归档：" + report.imported + " 条。源文件保留；旧记录不作为推理上下文。" else ""
                mutable.update { it.copy(importNotice = importText) }
                loadLatest(mutable.value.selected?.id)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.update { it.copy(ready = false, notice = "会话数据库读取失败；已保留原始数据，未重建或覆盖数据库。请重试读取，当前禁止发送。") }
            } finally { readRunning = false; mutable.update { it.copy(busy = false) } }
        }
    }

    fun newConversation() = changeSelection(null, create = true)
    fun selectConversation(id: String) = changeSelection(id)

    fun renameConversation(id: String, title: String) {
        val snapshot = mutable.value
        if (!snapshot.ready || snapshot.busy || snapshot.sending) return
        if (title.isBlank()) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(dispatcher) { checkNotNull(repository).renameConversation(id, title) }
                if (snapshot.selected?.id == id) loadLatest(id) else reloadConversationList()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(notice = "会话重命名失败；原标题保留。") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun deleteConversation(id: String) {
        val snapshot = mutable.value
        if (!snapshot.ready || snapshot.busy || snapshot.sending) return
        if (snapshot.selected?.legacy == true && snapshot.selected?.id == id) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(dispatcher) { checkNotNull(repository).deleteConversation(id) }
                drafts.remove(id)
                // Deleted selection falls back to the newest live conversation automatically.
                loadLatest(if (snapshot.selected?.id == id) null else snapshot.selected?.id)
                if (snapshot.selected?.id == id) {
                    mutable.update {
                        it.copy(draft = drafts[it.selected?.id].orEmpty(), partial = "",
                            partialReasoning = "", notice = "")
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(notice = "会话删除失败；若该会话正在发送，请稍后重试。") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    private suspend fun reloadConversationList() {
        val repo = checkNotNull(repository)
        val list = withContext(dispatcher) { repo.conversations(limit = 31) }
        mutable.update { it.copy(conversations = list.take(30), moreConversations = list.size > 30) }
    }

    private fun changeSelection(id: String?, create: Boolean = false) {
        val snapshot = mutable.value
        if (!snapshot.ready || snapshot.busy || snapshot.sending) return
        snapshot.selected?.id?.let { drafts[it] = snapshot.draft }
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val target = if (create) withContext(dispatcher) { checkNotNull(repository).createConversation() } else id
                loadLatest(target)
                mutable.update {
                    it.copy(draft = drafts[it.selected?.id].orEmpty(), partial = "",
                        partialReasoning = "", notice = "")
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(notice = "会话读取失败；未切换上下文，请重试。") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    private suspend fun loadLatest(selectedId: String?) {
        val loaded = withContext(dispatcher) {
            val repo = checkNotNull(repository)
            val list = repo.conversations(limit = 31)
            val selected = selectedId?.let(repo::conversation)
                ?: list.firstOrNull { !it.legacy }
                ?: repo.conversation(repo.createConversation())!!
            val messages = repo.messages(selected.id, limit = 51)
            val blocked = if (selected.legacy) false else try { repo.context(selected.id).size >= 80 }
                catch (_: ContextLimitException) { true }
            Triple(selected, messages, Pair(repo.conversations(limit = 31), blocked))
        }
        mutable.update { it.copy(
            ready = true, selected = loaded.first,
            messages = loaded.second.takeLast(50), olderMessages = loaded.second.size > 50, viewingOlderMessages = false,
            conversations = loaded.third.first.take(30), moreConversations = loaded.third.first.size > 30,
            contextBlocked = loaded.third.second,
        ) }
    }

    fun olderMessages() {
        val snapshot = mutable.value
        val id = snapshot.selected?.id ?: return
        val before = snapshot.messages.firstOrNull()?.id ?: return
        if (!snapshot.ready || snapshot.busy || snapshot.sending || !snapshot.olderMessages) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val page = withContext(dispatcher) { checkNotNull(repository).messages(id, before, 51) }
                mutable.update { it.copy(messages = page.takeLast(50), olderMessages = page.size > 50, viewingOlderMessages = true) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(notice = "更早消息读取失败；现有消息保留。") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun moreConversations() {
        val snapshot = mutable.value
        if (!snapshot.ready || snapshot.busy || snapshot.sending || !snapshot.moreConversations) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val page = withContext(dispatcher) { checkNotNull(repository).conversations(snapshot.conversations.size, 31) }
                mutable.update { it.copy(conversations = it.conversations + page.take(30), moreConversations = page.size > 30) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(notice = "会话列表读取失败；现有列表保留。") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun send(model: String, expectedSession: ProviderSessionKey?) {
        val snapshot = mutable.value
        val selected = snapshot.selected ?: return
        if (!snapshot.ready || snapshot.busy || snapshot.sending || selected.legacy || snapshot.contextBlocked || snapshot.draft.isBlank() || model.isBlank()) return
        if (expectedSession == null) return
        // Capture on the caller: an old catalog click must never acquire replacement credentials.
        val lease = try { acquire(expectedSession) } catch (_: Exception) {
            mutable.update { it.copy(notice = "连接已变化；草稿已保留，未自动重发。请等待模型目录刷新后手动发送。") }
            return
        }
        val committed = AtomicBoolean()
        val released = AtomicBoolean()
        mutable.update { it.copy(sending = true, partial = "", partialReasoning = "", notice = "") }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                withContext(dispatcher) {
                    val sendContext = coroutineContext
                    fun ensureCurrent() {
                        sendContext.ensureActive()
                        lease.ensureCurrent()
                        sendContext.ensureActive()
                    }
                    ensureCurrent()
                    val repo = checkNotNull(repository)
                    val context = repo.context(selected.id)
                    ensureCurrent()
                    if (context.size >= 80 || context.sumOf { it.content.length.toLong() } + snapshot.draft.length > 65536) throw ContextLimitException()
                    val guardedTurns = cancellableTurnStore(turnStore(repo, selected.id), sendContext,
                        ensureCurrent = lease::ensureCurrent, onCommitted = { committed.set(true) })
                    val requestControl = lease.control
                    val session = StreamingChatSession(
                        StreamingChatClient(lease.config, requestControl = requestControl),
                        ChatClient(lease.config, requestControl = requestControl), store = null, defaultModel = model,
                        turnStore = guardedTurns, initialMessages = context, requestControl = requestControl,
                    )
                    withChatRequestCancellation(requestControl) { session.sendWithReasoning(
                        text = snapshot.draft,
                        model = model,
                        onReasoning = { delta ->
                            ensureCurrent()
                            mutable.update {
                                it.copy(partialReasoning = (it.partialReasoning + delta).take(65536))
                            }
                        },
                        onDelta = { delta ->
                            ensureCurrent()
                            // Direct atomic update; no unlimited channel or delayed Activity callback.
                            mutable.update { it.copy(partial = (it.partial + delta).take(65536)) }
                        },
                    ) }
                    // No post-lease guard: a successful Room commit is the result of this turn.
                }
            } catch (e: CancellationException) {
                if (!committed.get()) mutable.update {
                    it.copy(notice = "连接已变化；草稿已保留，未自动重发。上游可能已处理，手动发送可能再次消耗额度。")
                }
                if (!coroutineContext.isActive) throw e
            } catch (e: Exception) {
                val status = when (e) {
                    is ContextLimitException -> "上下文达到本机安全上限，请新建会话"
                    is ChatException -> "HTTP " + e.httpCode
                    is StreamingChatException -> when (e.failure) {
                        StreamingChatFailure.RESPONSE_LIMIT -> "响应超过本机接收上限"
                        StreamingChatFailure.DEADLINE_EXCEEDED -> "请求超过本轮等待时限"
                        else -> e.httpCode?.takeIf { it !in 200..299 }?.let { "HTTP " + it } ?: "网络或流式中断"
                    }
                    else -> "请求或本地保存未能完成"
                }
                mutable.update { it.copy(notice = "发送未完成（" + status + "）；草稿已保留，未自动重发。上游可能已处理，手动发送可能再次消耗额度。") }
            } finally {
                // withContext may throw on its return dispatch even after the transaction committed.
                if (committed.get()) {
                    drafts.remove(selected.id)
                    mutable.update { it.copy(draft = "", partial = "", partialReasoning = "", notice = "本轮已保存。") }
                }
                // A disposed ViewModel needs no extra read; the blocking worker has already ended.
                if (coroutineContext.isActive) {
                    try { loadLatest(selected.id) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { mutable.update { it.copy(ready = false, notice = it.notice + " 会话读取失败；保留原始数据，当前禁止发送。") } }
                }
            }
        }
        // Also runs if the captured request never reaches its Main or IO body.
        job.invokeOnCompletion {
            if (released.compareAndSet(false, true)) {
                if (!committed.get()) lease.control.cancel()
                lease.close()
            }
            // Completion waits for the actual worker and Room cleanup, not just a new READY event.
            mutable.update { it.copy(sending = false) }
        }
        job.start()
    }
}

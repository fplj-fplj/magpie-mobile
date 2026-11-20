package cc.fplj.magpiemobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.fplj.magpiemobile.core.ChatClient
import cc.fplj.magpiemobile.core.ChatTurnStore
import cc.fplj.magpiemobile.core.ChatException
import cc.fplj.magpiemobile.core.ChatRequestOptions
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
import java.util.concurrent.atomic.AtomicReference
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
    private val pendingCreatedConversationId = AtomicReference<String?>(null)
    private var failedReadNotice: String? = null

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
                loadLatest(pendingCreatedConversationId.get() ?: mutable.value.selected?.id)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                readFailed("会话数据库读取失败；已保留原始数据，未重建或覆盖数据库。请重试读取，当前禁止发送。")
            } finally { readRunning = false; mutable.update { it.copy(busy = false) } }
        }
    }

    fun newConversation() = changeSelection(null, create = true)
    fun selectConversation(id: String) = changeSelection(id)

    fun renameConversation(id: String, title: String) {
        val snapshot = mutable.value
        if (!snapshot.ready || snapshot.busy || snapshot.sending) return
        if (title.isBlank()) return
        val committed = AtomicBoolean()
        val savedTitle = title.trim()
        val committedReadFailure = "会话重命名已保存，但列表读取失败；消息和草稿保留，请重读记录核对。"
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(dispatcher) {
                    checkNotNull(repository).renameConversation(id, title)
                    committed.set(true)
                    mutable.update { current -> current.copy(
                        selected = current.selected?.let { if (it.id == id) it.copy(title = savedTitle) else it },
                        conversations = current.conversations.map { if (it.id == id) it.copy(title = savedTitle) else it },
                    ) }
                }
                reloadConversationList(
                    snapshot.conversations.size.coerceAtLeast(30),
                    renamedSelectionId = id.takeIf { snapshot.selected?.id == it },
                )
            } catch (e: CancellationException) {
                if (committed.get()) readFailed(committedReadFailure)
                throw e
            }
            catch (_: Exception) {
                if (committed.get()) readFailed(committedReadFailure)
                else mutable.update { it.copy(notice = "会话重命名失败；原标题保留。") }
            }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun deleteConversation(id: String) {
        val snapshot = mutable.value
        if (!snapshot.ready || snapshot.busy || snapshot.sending) return
        if (snapshot.selected?.legacy == true && snapshot.selected?.id == id) return
        val committed = AtomicBoolean()
        val committedReadFailure = "会话已删除，但列表读取失败；请重读记录，未恢复已删除的会话。"
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(dispatcher) {
                    checkNotNull(repository).deleteConversation(id)
                    committed.set(true)
                    // A committed deletion must never remain a sendable UI identity, even
                    // when the return dispatch is cancelled or the fallback read fails.
                    mutable.update { current ->
                        val remaining = current.conversations.filterNot { it.id == id }
                        if (current.selected?.id == id) current.copy(
                            ready = false, selected = null, messages = emptyList(),
                            conversations = remaining, olderMessages = false,
                            viewingOlderMessages = false, draft = "", partial = "",
                            partialReasoning = "", notice = "", contextBlocked = false,
                        ) else current.copy(conversations = remaining)
                    }
                }
                if (snapshot.selected?.id == id) {
                    // Deleted selection falls back to the newest live conversation automatically.
                    loadLatest(null)
                } else {
                    // Unrelated deletion changes history only; preserve the current message page.
                    reloadConversationList(snapshot.conversations.size.coerceAtLeast(30))
                }
            } catch (e: CancellationException) {
                if (committed.get()) readFailed(committedReadFailure)
                throw e
            }
            catch (_: Exception) {
                if (committed.get()) readFailed(committedReadFailure)
                else mutable.update { it.copy(notice = "会话删除失败；若该会话正在发送，请稍后重试。") }
            }
            finally {
                if (committed.get()) drafts.remove(id)
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    private fun readFailed(notice: String) {
        failedReadNotice = notice
        mutable.update { it.copy(ready = false, notice = notice) }
    }

    private suspend fun reloadConversationList(visibleLimit: Int, renamedSelectionId: String? = null) {
        val (list, selected) = withContext(dispatcher) {
            val repo = checkNotNull(repository)
            val rows = repo.conversations(limit = visibleLimit + 1)
            rows to renamedSelectionId?.let { id -> rows.firstOrNull { it.id == id } ?: repo.conversation(id) }
        }
        mutable.update {
            it.copy(
                conversations = list.take(visibleLimit),
                moreConversations = list.size > visibleLimit,
                selected = selected ?: it.selected,
            )
        }
    }

    private fun changeSelection(id: String?, create: Boolean = false) {
        val snapshot = mutable.value
        if (!snapshot.ready || snapshot.busy || snapshot.sending) return
        if (!create) pendingCreatedConversationId.set(null)
        snapshot.selected?.id?.let { drafts[it] = snapshot.draft }
        val committedReadFailure = "新会话已创建，但读取失败；原草稿保留，请重读记录，未重复创建。"
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val target = if (create) withContext(dispatcher) {
                    checkNotNull(repository).createConversation().also { created ->
                        pendingCreatedConversationId.set(created)
                        // Retrying a failed read must select this committed row, not create another.
                        mutable.update { it.copy(ready = false) }
                    }
                } else id
                loadLatest(target)
                mutable.update {
                    it.copy(draft = drafts[it.selected?.id].orEmpty(), partial = "",
                        partialReasoning = "", notice = "")
                }
            } catch (e: CancellationException) {
                if (create && pendingCreatedConversationId.get() != null) readFailed(committedReadFailure)
                throw e
            }
            catch (_: Exception) {
                if (create && pendingCreatedConversationId.get() != null) {
                    readFailed(committedReadFailure)
                } else mutable.update { it.copy(notice = "会话读取失败；未切换上下文，请重试。") }
            }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    private suspend fun loadLatest(selectedId: String?) {
        val loaded = withContext(dispatcher) {
            val repo = checkNotNull(repository)
            val list = repo.conversations(limit = 31)
            val selected = selectedId?.let(repo::conversation)
                ?: list.firstOrNull { !it.legacy }
                ?: repo.createConversation().let { created ->
                    pendingCreatedConversationId.set(created)
                    mutable.update { it.copy(ready = false) }
                    checkNotNull(repo.conversation(created))
                }
            val messages = repo.messages(selected.id, limit = 51)
            val blocked = if (selected.legacy) false else try { repo.context(selected.id).size >= 80 }
                catch (_: ContextLimitException) { true }
            Triple(selected, messages, Pair(repo.conversations(limit = 31), blocked))
        }
        val previous = mutable.value
        val selectionChanged = previous.selected?.id != loaded.first.id
        if (selectionChanged) previous.selected?.id?.let { drafts[it] = previous.draft }
        val nextDraft = if (selectionChanged) drafts[loaded.first.id].orEmpty() else previous.draft
        val recoveredReadNotice = failedReadNotice
        mutable.update { it.copy(
            ready = true, selected = loaded.first,
            messages = loaded.second.takeLast(50), olderMessages = loaded.second.size > 50, viewingOlderMessages = false,
            conversations = loaded.third.first.take(30), moreConversations = loaded.third.first.size > 30,
            contextBlocked = loaded.third.second,
            draft = nextDraft,
            partial = if (selectionChanged) "" else it.partial,
            partialReasoning = if (selectionChanged) "" else it.partialReasoning,
            notice = if (selectionChanged || it.notice == recoveredReadNotice) "" else it.notice,
        ) }
        pendingCreatedConversationId.set(null)
        failedReadNotice = null
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

    fun profileUnavailable() {
        if (!mutable.value.sending && !mutable.value.busy) {
            mutable.update { it.copy(notice = "预设暂不可读取；草稿已保留。请在“模型与对话配置”中重试读取后再发送。") }
        }
    }

    fun send(
        model: String,
        expectedSession: ProviderSessionKey?,
        requestOptions: ChatRequestOptions = ChatRequestOptions(),
    ) {
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
                    val systemChars = requestOptions.systemPrompt.takeIf { it.isNotBlank() }?.length ?: 0
                    if (context.size >= 80 || context.sumOf { it.content.length.toLong() } + snapshot.draft.length + systemChars > 65536) throw ContextLimitException()
                    val guardedTurns = cancellableTurnStore(turnStore(repo, selected.id), sendContext,
                        ensureCurrent = lease::ensureCurrent, onCommitted = { committed.set(true) })
                    val requestControl = lease.control
                    val session = StreamingChatSession(
                        StreamingChatClient(lease.config, requestControl = requestControl),
                        ChatClient(lease.config, requestControl = requestControl), store = null, defaultModel = model,
                        turnStore = guardedTurns, initialMessages = context, requestControl = requestControl,
                        requestOptions = requestOptions,
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
                    catch (_: Exception) { readFailed(mutable.value.notice + " 会话读取失败；保留原始数据，当前禁止发送。") }
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

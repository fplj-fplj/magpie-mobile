package cc.fplj.magpiemobile.core

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * 流式优先的聊天会话；仅真正 HTTP 405/501（收到任何成功响应增量之前）可降级一次。
 * 其他 HTTP/SSE/IO/断流失败不重发；失败只回滚本次待定 user，不把 partial 当成功 assistant。
 * fallback 的 engine 带 :fallback 标记；reset 仅清上下文，发送期间拒绝 reset/send。
 * turnStore 存在时独占持久化（Room 调用方传 store=null），否则保留旧 JSONL store 行为。
 * 恢复只接受完整 user/assistant 对；事务 complete 返回之后才提交内存 assistant。
 * requestControl 与两个客户端共用；取消在 complete 开始前生效，已提交事务不再改记失败。
 */
class StreamingChatSession(
    private val streaming: StreamingChatClient,
    private val fallback: ChatClient,
    private val store: SessionStore?,
    private val defaultModel: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val turnStore: ChatTurnStore? = null,
    initialMessages: List<ChatClient.Message> = emptyList(),
    private val requestControl: ChatRequestControl? = null,
) {
    private val lock = Any()
    private val history = initialMessages.map { it.copy() }.toMutableList().also { messages ->
        require(messages.size % 2 == 0 && messages.withIndex().all { (index, message) ->
            message.role == if (index % 2 == 0) "user" else "assistant"
        }) { "Initial messages must be complete user/assistant pairs" }
    }
    private var nextSendSequence = 0L
    private var activeSendSequence: Long? = null

    private data class PendingUser(val sequence: Long, val index: Int, val message: ChatClient.Message)

    fun messages(): List<ChatClient.Message> = synchronized(lock) { history.toList() }

    fun reset() = synchronized(lock) {
        check(activeSendSequence == null) { "Cannot reset while a send is in progress" }
        history.clear()
    }

    fun send(
        text: String,
        model: String = defaultModel,
        onDelta: (String) -> Unit = {},
    ): String = sendWithReasoning(text, model, onDelta = onDelta)

    /**
     * 与 send 相同，同时把 reasoning_content 增量交给独立回调。
     * 思考内容只随成功 assistant 记录持久化，不加入 history，也不会进入后续模型上下文。
     */
    fun sendWithReasoning(
        text: String,
        model: String = defaultModel,
        onReasoning: (String) -> Unit = {},
        onDelta: (String) -> Unit = {},
    ): String {
        val control = requestControl ?: ChatRequestControl()
        control.ensureActive()
        val sequence = synchronized(lock) {
            check(activeSendSequence == null) { "A send is already in progress" }
            activeSendSequence = ++nextSendSequence
            nextSendSequence
        }
        var pendingUser: PendingUser? = null
        var turnId: String? = null
        var engine = engineId(model, true)
        val reasoning = StringBuilder()
        var deadline: Closeable? = null
        try {
            deadline = control.startDeadline(streaming.maxDuration)
            val requestMessages = synchronized(lock) {
                // begin 必须成功返回，才能把本轮 user 交给网络；失败不调用 fail。
                turnId = persist {
                    val user = SessionRecord(now(), "user", engineId(model, false), text)
                    control.ensureActive()
                    if (turnStore != null) turnStore.begin(user) else {
                        store?.append(user)
                        null
                    }
                }
                // A cancelled begin may already have committed: retain its identity for fail.
                control.ensureActive()
                val message = ChatClient.Message("user", text)
                pendingUser = PendingUser(sequence, history.size, message)
                history.add(message)
                history.toList()
            }
            val reply = try {
                // 返回值已经是纯文本，不能再过非流式 extractReply。
                streaming.withRequestControl(control).streamWithReasoning(
                    model = model,
                    messages = requestMessages,
                    onReasoning = { delta ->
                        control.ensureActive()
                        reasoning.append(delta)
                        onReasoning(delta)
                        control.ensureActive()
                    },
                    onDelta = { delta ->
                        control.ensureActive()
                        onDelta(delta)
                        control.ensureActive()
                    },
                )
            } catch (e: ChatException) {
                // Client 只在真正非 2xx HTTP 响应上抛此异常；SSE/回调无法进入降级分支。
                if (e.httpCode != 405 && e.httpCode != 501) throw e
                control.ensureActive()
                engine = engineId(model, false) + ":fallback"
                val raw = fallback.withRequestControl(control).complete(model, requestMessages)
                control.ensureActive()
                val completion = ChatSession.extractCompletion(raw)
                validateFallbackSize(completion.content, completion.reasoning)
                completion.reasoning.takeIf { it.isNotEmpty() }?.let { delta ->
                    control.ensureActive()
                    reasoning.append(delta)
                    onReasoning(delta)
                    control.ensureActive()
                }
                completion.content
            }
            synchronized(lock) {
                check(pendingMatches(checkNotNull(pendingUser))) { "Pending user changed during send" }
                // 存储异常不在流式 HTTP fallback 的 catch 中，也不携带原始异常文本。
                persist {
                    val assistant = SessionRecord(now(), "assistant", engine, reply,
                        reasoning = reasoning.toString())
                    // This is the commit boundary. Cancellation after complete starts cannot
                    // turn a successfully committed transaction into a failed in-memory turn.
                    control.ensureActive()
                    if (turnStore != null) turnStore.complete(checkNotNull(turnId), assistant)
                    else store?.append(assistant)
                }
                history.add(ChatClient.Message("assistant", reply))
                pendingUser = null
            }
            return reply
        } catch (e: Exception) {
            synchronized(lock) {
                // 序号、末尾索引和对象身份均匹配才回滚，不能按文本删除旧成功的同名 user。
                pendingUser?.let { if (pendingMatches(it)) history.removeAt(it.index) }
            }
            val failure = safeFailure(e, control)
            try {
                // 只记固定分类和部分长度，不写原始异常 cause/stack、JSON 或 partial。
                if (turnStore != null) {
                    turnId?.let {
                        turnStore.fail(it, SessionRecord(now(), "error", engine, errorSummary(failure)))
                    }
                } else {
                    store?.append(SessionRecord(now(), "error", engine, errorSummary(failure)))
                }
            } catch (_: Exception) {
                // 日志写入失败也不得替换成含路径/自由文本的原始 IO 异常。
            }
            throw failure
        } finally {
            deadline?.close()
            synchronized(lock) {
                if (activeSendSequence == sequence) activeSendSequence = null
            }
        }
    }

    /** 仅在持有 lock 时调用；成功上下文和其他发送的消息永不参与回滚。 */
    private fun pendingMatches(pending: PendingUser): Boolean =
        activeSendSequence == pending.sequence && history.size == pending.index + 1 &&
            history.getOrNull(pending.index) === pending.message && pending.message.role == "user"

    /** 存储不能冒充网络 HTTP/SSE 异常；不保留 partial、cause 或自由文本。 */
    private fun <T> persist(block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        throw StreamingChatException(StreamingChatFailure.IO_FAILURE, "")
    } catch (_: Exception) {
        throw StreamingChatException(StreamingChatFailure.CHAT_FAILURE, "")
    }

    private fun safeFailure(e: Exception, control: ChatRequestControl): Exception {
        if (e is CancellationException) return e
        // A socket closed by cancel may surface as I/O first. Preserve cancellation semantics
        // while still running the same pending-turn cleanup and fixed-summary persistence.
        try { control.ensureActive() }
        catch (cancelled: CancellationException) { return cancelled }
        catch (stopped: StreamingChatException) {
            return if (e is StreamingChatException && e.failure == stopped.failure) e else stopped
        }
        return when (e) {
            is StreamingChatException -> e
            is ChatException -> ChatException("chat -> HTTP " + e.httpCode, e.httpCode)
            is IOException -> StreamingChatException(StreamingChatFailure.IO_FAILURE, "")
            else -> StreamingChatException(StreamingChatFailure.CHAT_FAILURE, "")
        }
    }

    private fun errorSummary(e: Exception): String = when (e) {
        is CancellationException -> "Chat failed; partial characters=0"
        is StreamingChatException -> e.failure.safeMessage + "; partial characters=" + e.partialText.length
        is ChatException -> "HTTP " + e.httpCode
        else -> StreamingChatFailure.CHAT_FAILURE.safeMessage
    }

    private fun validateFallbackSize(content: String, reasoning: String) {
        val limits = streaming.limits
        if (content.length > limits.maxContentChars || reasoning.length > limits.maxReasoningChars ||
            content.length.toLong() + reasoning.length > limits.maxCombinedChars) {
            throw StreamingChatException(StreamingChatFailure.RESPONSE_LIMIT, "")
        }
    }

    private fun engineId(model: String, streamed: Boolean): String =
        "chat:" + model + if (streamed) ":stream" else ""
}

package cc.fplj.magpiemobile

import androidx.lifecycle.SavedStateHandle
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** No session ID, key, URL, prompt answer or numeric connection identity is persisted. */
internal data class PluginLoginRecovery(
    val providerId: String,
    val stage: Stage,
    val cancelRequested: Boolean = false,
    val cancelAttempted: Boolean = false,
) {
    enum class Stage { STARTING, WAITING, START_UNKNOWN, CANCEL_UNKNOWN, CALLBACK_UNKNOWN, DETACHED }

    val notice: String get() = when {
        stage == Stage.DETACHED -> "原核心登录结果待核对；当前连接不能接管旧登录任务。"
        cancelRequested && stage == Stage.STARTING -> "已记录取消意图，正在等待核心返回登录任务标识；尚未证明服务端已取消。"
        stage == Stage.START_UNKNOWN -> "登录启动结果未知，未取得任务标识；不会自动重新发起登录。"
        stage == Stage.CANCEL_UNKNOWN -> "取消结果待核对；关闭连接不代表服务端任务已取消。"
        stage == Stage.CALLBACK_UNKNOWN -> "回执提交结果待核对；不会自动重复提交。"
        else -> "登录任务尚未结束，可只读核对状态。"
    }

    fun save(handle: SavedStateHandle) {
        handle[PID] = providerId
        handle[STAGE] = stage.name
        handle[CANCEL] = cancelRequested
    }

    companion object {
        private const val PID = "plugin.auth.provider"
        private const val STAGE = "plugin.auth.stage"
        private const val CANCEL = "plugin.auth.cancel"

        fun restore(handle: SavedStateHandle): PluginLoginRecovery? = handle.get<String>(PID)
            ?.takeIf { it.isNotEmpty() }
            ?.let { PluginLoginRecovery(it, Stage.DETACHED, handle[CANCEL] ?: false) }

        fun clear(handle: SavedStateHandle) {
            handle.remove<String>(PID)
            handle.remove<String>(STAGE)
            handle.remove<Boolean>(CANCEL)
        }
    }
}

/** A replacement VM must also wait for an old VM's real HTTP worker to leave its finally. */
internal object PluginAuthWorkTracker {
    private val lock = Any()
    private val mutable = MutableStateFlow(0)
    val active = mutable.asStateFlow()

    fun register(): () -> Unit {
        val finished = AtomicBoolean()
        synchronized(lock) { mutable.value += 1 }
        return {
            if (finished.compareAndSet(false, true)) synchronized(lock) { mutable.value -= 1 }
        }
    }
}

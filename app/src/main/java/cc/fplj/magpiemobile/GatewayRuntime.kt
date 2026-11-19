package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.GatewayEngineController
import cc.fplj.magpiemobile.core.SecretRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Service owns the engine. Activity and healthz observe the same immutable status. */
object GatewayRuntime {
    private val mutableStatus = MutableStateFlow(
        GatewayEngineController.Status(GatewayEngineController.Phase.STOPPED, "网关未运行"),
    )
    val status = mutableStatus.asStateFlow()
    private var generation = 0L
    private val mutableStopping = MutableStateFlow(false)
    val stopping = mutableStopping.asStateFlow()
    private var stopRequested = false

    @Synchronized
    fun attach(): Long {
        generation += 1
        mutableStatus.value = GatewayEngineController.Status(
            GatewayEngineController.Phase.STARTING, "前台服务已创建，正在检查载荷…",
        )
        return generation
    }

    @Synchronized
    fun isCurrent(owner: Long): Boolean = owner == generation

    /** Returns a safe accepted lifecycle snapshot; rejected events can only merge final diagnostics. */
    @Synchronized
    fun publish(
        owner: Long,
        status: GatewayEngineController.Status,
        allowLifecycle: Boolean = true,
    ): GatewayEngineController.Status? {
        if (owner != generation) return null
        val safe = status.copy(
            message = SecretRedactor.redactDiagnostic(status.message),
            outputTail = SecretRedactor.redactDiagnostic(status.outputTail).takeLast(MAX_OUTPUT_TAIL),
        )
        val current = mutableStatus.value
        if (!allowLifecycle || mutableStopping.value || (current.phase == GatewayEngineController.Phase.FAILED &&
                safe.phase == GatewayEngineController.Phase.STOPPED)) {
            // STOPPED is not cleanup proof. Keep phase/reason/barrier while retaining final evidence.
            mutableStatus.value = current.copy(
                outputTail = safe.outputTail.ifEmpty { current.outputTail },
                exitCode = safe.exitCode ?: current.exitCode,
            )
            return null
        }
        mutableStatus.value = safe
        return safe
    }

    @Synchronized
    fun requestStart(): Boolean {
        if (mutableStopping.value) return false
        val phase = mutableStatus.value.phase
        if (phase == GatewayEngineController.Phase.RUNNING ||
            phase == GatewayEngineController.Phase.STARTING ||
            phase == GatewayEngineController.Phase.BACKOFF) return false
        stopRequested = false
        mutableStatus.value = GatewayEngineController.Status(
            GatewayEngineController.Phase.STARTING, "已收到启动请求，正在创建前台服务…",
        )
        return true
    }

    @Synchronized
    fun requestStop(): Long {
        stopRequested = true
        beginCleanup(generation)
        return generation
    }

    /** Service retirement must not turn an abnormal exit into an explicit user stop. */
    @Synchronized
    fun beginCleanup(owner: Long): Boolean {
        if (owner != generation) return false
        mutableStopping.value = true
        if (mutableStatus.value.phase != GatewayEngineController.Phase.FAILED) {
            mutableStatus.value = mutableStatus.value.copy(message = "正在停止并回收进程…")
        }
        return true
    }

    @Synchronized
    fun mustStop(): Boolean = stopRequested

    @Synchronized
    fun cleanupCompleted(owner: Long) {
        if (owner != generation) return
        val failed = mutableStatus.value.phase == GatewayEngineController.Phase.FAILED
        mutableStopping.value = false
        if (!failed) mutableStatus.value = mutableStatus.value.copy(
            phase = GatewayEngineController.Phase.STOPPED, message = "网关已停止（进程清理完成）",
        )
    }

    @Synchronized
    fun requestFailed(error: Exception) {
        mutableStatus.value = mutableStatus.value.copy(
            phase = GatewayEngineController.Phase.FAILED,
            message = SecretRedactor.redactDiagnostic("启动请求失败：" + (error.message ?: error.javaClass.simpleName)),
        )
    }

    @Synchronized
    fun stoppedWithoutService() {
        mutableStopping.value = false; stopRequested = true
        generation += 1
        mutableStatus.value = mutableStatus.value.copy(
            phase = GatewayEngineController.Phase.STOPPED, message = "网关已停止",
        )
    }

    private const val MAX_OUTPUT_TAIL = 8192
}

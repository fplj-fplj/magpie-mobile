package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.GatewayEngineController

/** Effects run on the reporting caller and must never await Main or the engine controller. */
internal interface GatewayServiceEffects {
    fun ready()
    fun connect()
    fun suspend()
    fun audit(status: GatewayEngineController.Status)
    fun log(status: GatewayEngineController.Status)
    fun notify(status: GatewayEngineController.Status)
    fun stopSelf()
}

/**
 * The Service's production report path. Tail refreshes only update the safe Runtime snapshot.
 * Stop/retire share this lock, but controller.stop()/awaitStopped() must be called after it returns:
 * controller reports already hold the controller lock, so waiting for it here would invert locks.
 */
internal class GatewayServiceReporter(
    private val owner: Long,
    private val effects: GatewayServiceEffects,
) {
    private val lock = Any()
    private var lifecycle: Pair<GatewayEngineController.Phase, Int>? = null
    private var retired = false

    fun report(status: GatewayEngineController.Status): GatewayEngineController.Status? = synchronized(lock) {
        val safe = GatewayRuntime.publish(owner, status, allowLifecycle = !retired)
            ?: return@synchronized null
        val next = safe.phase to safe.restartCount
        if (next == lifecycle) return@synchronized safe
        lifecycle = next
        if (safe.phase == GatewayEngineController.Phase.RUNNING) {
            effects.ready()
            effects.connect()
        } else {
            effects.suspend()
            if (safe.phase == GatewayEngineController.Phase.FAILED ||
                safe.phase == GatewayEngineController.Phase.BACKOFF) effects.audit(safe)
        }
        effects.log(safe)
        effects.notify(safe)
        if (safe.phase == GatewayEngineController.Phase.FAILED) effects.stopSelf()
        safe
    }

    /** Explicit stop invalidates callbacks before the caller touches the controller. */
    fun requestStop(): Boolean = synchronized(lock) {
        if (!GatewayRuntime.isCurrent(owner)) return@synchronized false
        GatewayRuntime.requestStop()
        if (!retired) effects.suspend()
        retired = true
        true
    }

    /** Destruction preserves FAILED while allowing final tail/code through the cleanup barrier. */
    fun retire(disconnect: () -> Unit): Boolean = synchronized(lock) {
        if (!GatewayRuntime.beginCleanup(owner)) return@synchronized false
        retired = true
        disconnect()
        true
    }
}

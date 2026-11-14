package cc.fplj.magpiemobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

data class BatteryOptimizationStatus(
    val isIgnoringOptimizations: Boolean = false,
    val canRequest: Boolean = false,
    val description: String = "未开启电池优化白名单",
)

data class BatteryOptimizationRoute(val intent: Intent?, val label: String, val note: String)

/**
 * Checks and guides Battery Optimization / Doze exemptions for background stability (P4-1).
 * Complies with Android foreground service requirements to avoid aggressive LMK/Doze termination.
 */
object BatteryOptimizationHelper {

    fun checkStatus(context: Context): BatteryOptimizationStatus {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm == null) {
            return BatteryOptimizationStatus(
                isIgnoringOptimizations = false,
                canRequest = false,
                description = "系统电源管理器不可用"
            )
        }

        val packageName = context.packageName
        val isIgnoring = try { pm.isIgnoringBatteryOptimizations(packageName) } catch (_: Exception) {
            return BatteryOptimizationStatus(canRequest = true, description = "暂时无法读取电源策略，请重新检测")
        }

        val desc = if (isIgnoring) {
            "已加入电池优化白名单；后台运行仍受系统策略影响"
        } else {
            "未加入白名单（长耗时会话或熄屏可能被系统省电策略终止）"
        }

        return BatteryOptimizationStatus(
            isIgnoringOptimizations = isIgnoring,
            canRequest = !isIgnoring,
            description = desc
        )
    }

    /**
     * Builds safe system settings intent to guide user to battery optimization exemptions.
     * Uses explicit package URI where supported.
     */
    internal fun settingsRoutes(context: Context): List<BatteryOptimizationRoute> = listOf(
        BatteryOptimizationRoute(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:${context.packageName}")),
            "电池优化授权", "请在系统提示中允许忽略电池优化。",
        ),
        BatteryOptimizationRoute(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            "电池优化列表", "请找到 Magpie Mobile，选择不优化。"),
        BatteryOptimizationRoute(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${context.packageName}")),
            "应用详情", "请进入电池或省电设置，允许后台运行。",
        ),
        BatteryOptimizationRoute(Intent(Settings.ACTION_SETTINGS), "系统设置",
            "请进入应用 → Magpie Mobile → 电池或省电策略。"),
    ).map { it.copy(intent = it.intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    private fun manualRoute() = BatteryOptimizationRoute(null, "手动设置",
        "未能打开系统页面，请手动前往 设置 → 应用 → Magpie Mobile → 电池或省电策略。")

    fun createOptimizationSettingsRoute(context: Context): BatteryOptimizationRoute =
        settingsRoutes(context).firstOrNull { it.intent?.resolveActivity(context.packageManager) != null } ?: manualRoute()

    fun createOptimizationSettingsIntent(context: Context): Intent? = createOptimizationSettingsRoute(context).intent

    /** 即使解析成功但启动被 ROM 拒绝，也继续尝试后续系统页面并给出反馈。 */
    fun openOptimizationSettings(context: Context): BatteryOptimizationRoute {
        for (route in settingsRoutes(context)) {
            val intent = route.intent ?: continue
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return route
                }
            } catch (_: Exception) {
                // Continue to the next route; final fallback always has a user-facing note.
            }
        }
        return manualRoute()
    }
}

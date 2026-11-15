package cc.fplj.magpiemobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import cc.fplj.magpiemobile.core.GatewayEngineController
import cc.fplj.magpiemobile.theme.UiSpacing

/** 二级目的地只保存名称，凭据和表单仍留在既有组件的内存状态中。 */
internal enum class GatewaySection(val title: String, val detail: String, val symbol: AppSymbol) {
    CONNECTION("接入应用", "端点、密钥与连接示例", AppSymbol.GATEWAY),
    KEYS("客户端密钥", "签发密钥，管理模型与额度限制", AppSymbol.KEY),
    USAGE("用量与速度", "近期请求、额度与模型速度", AppSymbol.USAGE),
    ROUTING("路由与号池", "模型别名、降级与密钥轮询", AppSymbol.ROUTE),
    MODELS("模型目录", "当前可用模型与完整目录", AppSymbol.MODELS),
    PLUGINS("插件与工具", "官方插件、工具、中间件与 Hooks", AppSymbol.PLUGIN),
    SYSTEM("系统与诊断", "网络、电池、运行输出与版本", AppSymbol.DEVICE),
}

@Composable
internal fun DestinationRow(
    title: String,
    detail: String,
    symbol: AppSymbol,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(UiSpacing.medium).heightIn(min = UiSpacing.touch),
            horizontalArrangement = Arrangement.spacedBy(UiSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(symbol)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiSpacing.tiny)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppIcon(AppSymbol.NEXT)
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun GatewayOverview(
    gateway: GatewayEngineController.Status,
    stopping: Boolean,
    active: Boolean,
    clientReady: Boolean,
    modelCount: Int?,
    stability: SystemStabilityUiState,
    notificationNotice: String,
    onAllowNotifications: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: (GatewaySection) -> Unit,
    onProviders: () -> Unit,
    onChat: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    PageHeading("把模型连在一起。", "一个本机入口，连接你的模型与应用。", "YOUR LOCAL GATEWAY")
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.inverseSurface, contentColor = colors.inverseOnSurface),
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(UiSpacing.card), verticalArrangement = Arrangement.spacedBy(UiSpacing.medium)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
                    Text("本机网关", style = MaterialTheme.typography.labelLarge)
                    Text(if (stopping) "正在停止…" else gatewayLabel(gateway.phase), style = MaterialTheme.typography.headlineMedium)
                }
                Switch(
                    checked = active,
                    onCheckedChange = { checked -> if (checked) onStart() else onStop() },
                    enabled = !stopping,
                    modifier = Modifier.testTag("gateway-power-switch").semantics {
                        testTagsAsResourceId = true
                        contentDescription = "网关开关"
                        stateDescription = if (stopping) "正在停止" else gatewayLabel(gateway.phase)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.onPrimary,
                        checkedTrackColor = colors.primary,
                        checkedBorderColor = colors.primary,
                        uncheckedThumbColor = colors.inverseOnSurface,
                        uncheckedTrackColor = colors.inverseSurface,
                        uncheckedBorderColor = colors.inverseOnSurface.copy(alpha = 0.65f),
                        disabledCheckedThumbColor = colors.inverseOnSurface.copy(alpha = 0.7f),
                        disabledCheckedTrackColor = colors.inverseOnSurface.copy(alpha = 0.2f),
                        disabledCheckedBorderColor = colors.inverseOnSurface.copy(alpha = 0.4f),
                    ),
                )
            }
            Text(
                when {
                    stopping -> "正在回收进程，请稍候。"
                    clientReady -> "安全连接已就绪，可以接入应用或开始聊天。"
                    gateway.phase == GatewayEngineController.Phase.RUNNING -> "本机端口已就绪，正在等待核心安全连接。"
                    gateway.phase == GatewayEngineController.Phase.STOPPED -> "准备好时，启动你的本机模型网关。"
                    else -> gateway.message
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider(color = colors.inverseOnSurface.copy(alpha = 0.2f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiSpacing.medium)) {
                Column(Modifier.weight(1f)) {
                    Text("本机端点", style = MaterialTheme.typography.labelSmall)
                    Text("127.0.0.1:3425", style = MaterialTheme.typography.bodyMedium)
                }
                Column {
                    Text("可用模型", style = MaterialTheme.typography.labelSmall)
                    Text(modelCount?.toString() ?: "待连接", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
    if (gateway.phase == GatewayEngineController.Phase.FAILED || gateway.phase == GatewayEngineController.Phase.BACKOFF) {
        Surface(color = colors.errorContainer, shape = MaterialTheme.shapes.medium) {
            Text(gateway.message, Modifier.fillMaxWidth().padding(UiSpacing.medium), color = colors.onErrorContainer)
        }
    }
    if (notificationNotice.isNotEmpty()) {
        SectionCard("开启通知，随时掌握状态") {
            Text(notificationNotice, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAllowNotifications) { Text("允许通知") }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
        Text("常用管理", style = MaterialTheme.typography.titleLarge)
        listOf(GatewaySection.CONNECTION, GatewaySection.KEYS, GatewaySection.USAGE, GatewaySection.ROUTING).forEach { section ->
            DestinationRow(section.title, section.detail, section.symbol, onClick = { onOpen(section) })
        }
    }
    Surface(
        onClick = { onOpen(GatewaySection.SYSTEM) },
        color = colors.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(UiSpacing.medium), verticalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
            Text(stability.networkState.description, style = MaterialTheme.typography.bodySmall)
            Text(
                if (stability.batteryStatus.isIgnoringOptimizations) "电池优化白名单已开启" else "建议设置电池优化白名单 · 查看",
                style = MaterialTheme.typography.bodySmall,
            )
            if (stability.memoryTrimNotice.isNotEmpty()) Text(stability.memoryTrimNotice, color = colors.error)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
        TextButton(onClick = onProviders, modifier = Modifier.weight(1f)) { Text("管理提供商") }
        TextButton(onClick = onChat, modifier = Modifier.weight(1f)) { Text("开始聊天") }
    }
}

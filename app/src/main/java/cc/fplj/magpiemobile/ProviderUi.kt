package cc.fplj.magpiemobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.fplj.magpiemobile.core.ProviderSummary
import cc.fplj.magpiemobile.theme.UiSpacing

/** RikkaHub 的列表结构参考：品牌标识、两行摘要、状态标签、整行进入详情。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderRow(provider: ProviderSummary, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(UiSpacing.medium),
            horizontalArrangement = Arrangement.spacedBy(UiSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { AppIcon(AppSymbol.PROVIDERS) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiSpacing.tiny)) {
                Text(provider.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (provider.editable) "OpenAI 兼容 · 原生编辑" else "官方扩展配置 · 网页编辑",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(UiSpacing.small), verticalArrangement = Arrangement.spacedBy(UiSpacing.tiny)) {
                    ProviderTag(if (provider.off) "已停用" else if (provider.noModels) "模型已禁用" else "已配置")
                    ProviderTag("${provider.chosen.size} / ${provider.models.size} 模型")
                }
            }
            AppIcon(AppSymbol.NEXT)
        }
    }
}

@Composable
private fun ProviderTag(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(text, Modifier.padding(horizontal = UiSpacing.small, vertical = UiSpacing.tiny),
            style = MaterialTheme.typography.labelSmall)
    }
}

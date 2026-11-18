package cc.fplj.magpiemobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.fplj.magpiemobile.core.ProviderSummary
import cc.fplj.magpiemobile.theme.UiSpacing

/**
 * 提供商管理沿用 RikkaHub 的信息层级，但色值、字体和图标来自 Magpie 现有主题：
 * 概览卡片 -> 简短摘要 -> 状态与模型标签 -> 整行进入详情。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderRow(
    provider: ProviderSummary,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("provider-row:${provider.id}"),
    ) {
        Column {
            Row(
                Modifier.padding(horizontal = UiSpacing.medium, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(UiSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderAvatar(provider.name, 48.dp)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        provider.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (provider.editable) {
                            provider.chat.ifBlank { "OpenAI 兼容连接" }
                        } else {
                            "官方扩展配置"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(UiSpacing.small),
                        verticalArrangement = Arrangement.spacedBy(UiSpacing.tiny),
                    ) {
                        ProviderTag(
                            when {
                                provider.off -> "已停用"
                                provider.noModels -> "模型已禁用"
                                else -> "已启用"
                            },
                            tone = if (provider.off || provider.noModels) ProviderTagTone.Warning
                            else ProviderTagTone.Success,
                        )
                        ProviderTag(
                            "${provider.chosen.size} / ${provider.models.size} 模型",
                            tone = ProviderTagTone.Info,
                        )
                    }
                }
                AppIcon(AppSymbol.NEXT, description = null)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = UiSpacing.medium, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(UiSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(AppSymbol.MODELS, description = null)
                Text(
                    if (provider.editable) "配置连接与模型" else "转到浏览器配置",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    provider.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun ProviderAvatar(name: String, size: Dp = 40.dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString()
            if (initial.isNullOrBlank()) {
                AppIcon(AppSymbol.PROVIDERS, description = null)
            } else {
                Text(initial, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

internal enum class ProviderTagTone { Success, Info, Warning, Neutral }

@Composable
internal fun ProviderTag(text: String, tone: ProviderTagTone = ProviderTagTone.Neutral) {
    val color = when (tone) {
        ProviderTagTone.Success -> MaterialTheme.colorScheme.secondaryContainer
        ProviderTagTone.Info -> MaterialTheme.colorScheme.primaryContainer
        ProviderTagTone.Warning -> MaterialTheme.colorScheme.errorContainer
        ProviderTagTone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when (tone) {
        ProviderTagTone.Warning -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(shape = MaterialTheme.shapes.small, color = color, contentColor = content) {
        Text(
            text,
            Modifier.padding(horizontal = UiSpacing.small, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

internal data class ProviderAddTemplate(
    val id: String,
    val name: String,
    val baseUrl: String,
    val description: String,
)

internal val providerAddTemplates = listOf(
    ProviderAddTemplate(
        id = "openai",
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        description = "OpenAI 官方或完全兼容的 API",
    ),
    ProviderAddTemplate(
        id = "deepseek",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        description = "DeepSeek 官方 API，支持推理模型",
    ),
    ProviderAddTemplate(
        id = "openrouter",
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        description = "通过 OpenRouter 访问多家模型",
    ),
    ProviderAddTemplate(
        id = "custom",
        name = "自定义 OpenAI 兼容",
        baseUrl = "",
        description = "填写自己的 API 地址与密钥",
    ),
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ProviderAddSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSelect: (ProviderAddTemplate) -> Unit,
) {
    if (!visible) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = UiSpacing.medium, end = UiSpacing.medium, bottom = UiSpacing.large),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.small),
        ) {
            Text("添加提供商", style = MaterialTheme.typography.headlineSmall)
            Text(
                "先选择连接类型，下一步再填写凭据并选择模型。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = UiSpacing.small),
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column {
                    providerAddTemplates.forEachIndexed { index, template ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(template) }
                                .padding(horizontal = UiSpacing.medium, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(UiSpacing.medium),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ProviderAvatar(template.name)
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(template.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    template.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            AppIcon(AppSymbol.NEXT, description = null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProviderEditorTabs(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = listOf("配置", "模型", "高级")
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = CircleShape,
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { index, label ->
                val active = index == selected
                Surface(
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                    shape = CircleShape,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else Color.Transparent,
                    contentColor = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProviderFormGroup(
    title: String? = null,
    supporting: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier.padding(UiSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.medium),
        ) {
            title?.let {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                    supporting?.let { hint ->
                        Text(
                            hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
internal fun ProviderSwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    description: String,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(UiSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ProviderModelChoiceRow(
    modelId: String,
    displayName: String,
    available: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .toggleable(
                    value = selected,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onToggle,
                )
                .padding(horizontal = UiSpacing.medium, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(UiSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AppIcon(AppSymbol.MODELS, description = null)
                }
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (displayName != modelId) {
                    Text(
                        modelId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    displayName.ifBlank { modelId },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.tiny),
                ) {
                    ProviderTag("聊天", ProviderTagTone.Info)
                    if (!selected) ProviderTag("未启用")
                    if (!available) ProviderTag("目录未返回", ProviderTagTone.Warning)
                }
            }
            Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
        }
    }
}

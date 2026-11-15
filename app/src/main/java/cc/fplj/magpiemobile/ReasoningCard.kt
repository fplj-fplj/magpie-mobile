package cc.fplj.magpiemobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cc.fplj.magpiemobile.theme.UiSpacing

/**
 * 模型思考内容卡片。生成中显示滚动预览，完成后自动折叠；点击标题行可展开或收起。
 * 内容始终与正式回答分离，并支持长按选择复制。
 */
@Composable
internal fun ReasoningCard(
    reasoning: String,
    streaming: Boolean,
    stateKey: String,
    modifier: Modifier = Modifier,
) {
    if (reasoning.isBlank()) return

    var expanded by rememberSaveable(stateKey) { mutableStateOf(streaming) }
    var wasStreaming by rememberSaveable("$stateKey:streaming") { mutableStateOf(streaming) }
    val scroll = rememberScrollState()

    LaunchedEffect(streaming) {
        if (streaming != wasStreaming) {
            expanded = streaming
            wasStreaming = streaming
        }
    }
    LaunchedEffect(reasoning, streaming, expanded) {
        if (streaming && expanded) scroll.scrollTo(scroll.maxValue)
    }

    val title = if (streaming) "正在思考…" else "思考过程"
    val hint = when {
        streaming && expanded -> "实时预览 · 点击收起"
        streaming -> "模型正在组织思路 · 点击展开"
        expanded -> "点击收起"
        else -> "点击查看模型返回的思考内容"
    }

    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .semantics {
                        role = Role.Button
                        stateDescription = if (expanded) "已展开" else "已收起"
                    }
                    .padding(horizontal = UiSpacing.medium, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("思", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppIcon(
                    if (expanded) AppSymbol.UP else AppSymbol.DOWN,
                    description = if (expanded) "收起思考过程" else "展开思考过程",
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SelectionContainer {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = if (streaming) 128.dp else 280.dp)
                                .verticalScroll(scroll)
                                .padding(horizontal = UiSpacing.medium, vertical = 12.dp),
                        ) {
                            Text(
                                reasoning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics {
                                    if (streaming) liveRegion = LiveRegionMode.Polite
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

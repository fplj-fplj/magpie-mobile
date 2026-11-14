package cc.fplj.magpiemobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.fplj.magpiemobile.theme.UiSpacing
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatPanel(
    viewModel: ChatViewModel,
    modelIds: List<String>,
    modelLabels: Map<String, String>,
    selectedModel: String,
    onSelectModel: (String) -> Unit,
    canConnect: Boolean,
    providerBusy: Boolean,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var historyOpen by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    val idle = state.ready && !state.busy && !state.sending
    val messagesScroll = rememberLazyListState()
    var previousCount by rememberSaveable(state.selected?.id) { mutableIntStateOf(0) }
    val viewportHeight = (LocalConfiguration.current.screenHeightDp * 0.42f).coerceIn(240f, 480f).dp
    LaunchedEffect(state.messages.size, state.selected?.id, state.viewingOlderMessages) {
        val nearEnd = (messagesScroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= previousCount - 2
        if (!state.viewingOlderMessages && state.messages.isNotEmpty() &&
            (previousCount == 0 || state.messages.size > previousCount && nearEnd)) {
            messagesScroll.scrollToItem(state.messages.lastIndex)
        }
        previousCount = state.messages.size
    }
    SectionCard(state.selected?.title ?: "新的对话") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
                TextButton(enabled = idle && !providerBusy, onClick = viewModel::newConversation) { Text("新会话") }
                TextButton(enabled = idle, onClick = { historyOpen = true }) { Text("历史会话") }
                TextButton(enabled = !state.busy && !state.sending, onClick = viewModel::refresh) { Text("重读记录") }
            }
            state.selected?.let { selected ->
                Text("更新于 " + chatDate(selected.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (selected.legacy) Text("旧版只读存档：无法可靠区分原会话或失败轮次，不会用作推理上下文；请新建会话。")
            }
            if (state.importNotice.isNotBlank()) Text(state.importNotice, style = MaterialTheme.typography.bodySmall)
            if (state.busy) Text("读取本机记录中…")
            if (state.contextBlocked) Text("上下文达到 40 轮或超过 65,536 字符，记录仍保留。请新建会话；不会静默截断旧上下文。")
            if (state.viewingOlderMessages) TextButton(enabled = idle, onClick = viewModel::refresh) { Text("回最新消息") }
            if (state.olderMessages) TextButton(enabled = idle, onClick = viewModel::olderMessages) { Text("读取更早 50 条消息（换页）") }
            if (state.messages.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().height(viewportHeight), state = messagesScroll, verticalArrangement = Arrangement.spacedBy(UiSpacing.medium)) {
                    items(state.messages, key = { it.id }) { message ->
                        val failed = message.state in setOf("FAILED", "INTERRUPTED") || message.role == "error"
                        val user = message.role == "user"
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = when {
                                failed -> MaterialTheme.colorScheme.errorContainer
                                user -> MaterialTheme.colorScheme.surfaceContainerHigh
                                else -> MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                        ) {
                            Column(Modifier.padding(UiSpacing.medium), verticalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
                                val role = when (message.role) { "user" -> "用户"; "assistant" -> "助手"; "error" -> "状态"; else -> "旧记录" }
                                val status = when (message.state) {
                                    "RUNNING" -> " · 进行中（不重复发送）"
                                    "FAILED" -> " · 失败（不进入成功上下文）"
                                    "INTERRUPTED" -> " · 中断 / 结果未知（未自动重发）"
                                    "LEGACY" -> " · 旧版存档"
                                    else -> ""
                                }
                                Text(role + status, style = MaterialTheme.typography.labelSmall)
                                SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
                                Text(chatDate(message.ts) + if (message.engine.isNotBlank()) " · " + message.engine else "", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            } else if (state.ready) EmptyState("想聊些什么？", "写下第一个问题，或从历史会话继续。")
            if (state.partial.isNotEmpty()) {
                Text("本轮输出预览（失败时仅在内存保留，不作为成功回答）", style = MaterialTheme.typography.labelSmall)
                SelectionContainer { Text(state.partial, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            }
            Box {
                OutlinedButton(enabled = modelIds.isNotEmpty() && idle && state.selected?.legacy != true, onClick = { modelMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selectedModel.isBlank()) "选择模型" else modelLabels[selectedModel] ?: selectedModel, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                    modelIds.forEach { id ->
                        DropdownMenuItem(text = { Column { Text(modelLabels[id] ?: id); if (modelLabels[id] != id) Text(id, style = MaterialTheme.typography.bodySmall) } }, onClick = { onSelectModel(id); modelMenu = false })
                    }
                }
            }
            OutlinedTextField(value = state.draft, onValueChange = viewModel::editDraft,
                enabled = idle && state.selected?.legacy != true, modifier = Modifier.fillMaxWidth(),
                minLines = 3, maxLines = 8,
                placeholder = { Text("写下你的问题…") })
            Button(enabled = idle && canConnect && !providerBusy && state.selected?.legacy == false && !state.contextBlocked && state.draft.isNotBlank() && selectedModel in modelIds,
                modifier = Modifier.fillMaxWidth(), onClick = { viewModel.send(selectedModel) }) { Text(if (state.sending) "正在回复…" else "发送消息") }
            Text("记录保存在本机私有数据库，系统备份关闭。发送会把成功上下文交给所选模型，可能消耗额度。草稿仅保留在内存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!canConnect) Text("连接网关并选择可用模型后即可发送。", style = MaterialTheme.typography.bodySmall)
            if (state.notice.isNotBlank()) Text(state.notice, style = MaterialTheme.typography.bodySmall)
    }
    if (historyOpen) SessionHistoryDialog(state = state, idle = idle, onDismiss = { historyOpen = false }, viewModel = viewModel)
}

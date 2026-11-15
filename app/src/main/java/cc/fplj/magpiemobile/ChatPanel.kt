package cc.fplj.magpiemobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.fplj.magpiemobile.data.StoredChatMessage
import cc.fplj.magpiemobile.theme.UiSpacing

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
    var previousMessageCount by rememberSaveable(state.selected?.id) { mutableIntStateOf(0) }
    val viewportHeight = (LocalConfiguration.current.screenHeightDp * 0.52f)
        .coerceIn(320f, 560f).dp
    val liveVisible = state.sending || state.partial.isNotEmpty() ||
        state.partialReasoning.isNotEmpty()
    val listItems = state.messages.size + if (liveVisible) 1 else 0

    LaunchedEffect(
        listItems,
        state.partial.length,
        state.partialReasoning.length,
        state.selected?.id,
        state.viewingOlderMessages,
    ) {
        if (listItems == 0) {
            previousMessageCount = state.messages.size
            return@LaunchedEffect
        }
        val lastVisible = messagesScroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearEnd = lastVisible >= listItems - 2
        val messageAdded = state.messages.size > previousMessageCount
        if (!state.viewingOlderMessages && (previousMessageCount == 0 || nearEnd || messageAdded)) {
            messagesScroll.scrollToItem(listItems - 1)
        }
        previousMessageCount = state.messages.size
    }

    SectionCard(state.selected?.title ?: "新的对话") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(UiSpacing.small),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.tiny),
        ) {
            TextButton(
                enabled = idle && !providerBusy,
                onClick = viewModel::newConversation,
            ) { Text("新会话") }
            TextButton(enabled = idle, onClick = { historyOpen = true }) { Text("历史会话") }
            TextButton(
                enabled = !state.busy && !state.sending,
                onClick = viewModel::refresh,
            ) { Text("重读记录") }
        }
        state.selected?.let { selected ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "更新于 " + chatDate(selected.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (state.sending) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        when {
                            state.sending -> "生成中"
                            selected.legacy -> "只读存档"
                            else -> "本机记录"
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (selected.legacy) {
                Text(
                    "旧版只读存档：无法可靠区分原会话或失败轮次，不会用作推理上下文；请新建会话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.importNotice.isNotBlank()) {
            Text(state.importNotice, style = MaterialTheme.typography.bodySmall)
        }
        if (state.busy) Text("读取本机记录中…")
        if (state.contextBlocked) {
            Text(
                "上下文达到 40 轮或超过 65,536 字符，记录仍保留。请新建会话；不会静默截断旧上下文。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state.viewingOlderMessages) {
            TextButton(enabled = idle, onClick = viewModel::refresh) { Text("回最新消息") }
        }
        if (state.olderMessages) {
            TextButton(enabled = idle, onClick = viewModel::olderMessages) {
                Text("读取更早 50 条消息")
            }
        }

        if (state.messages.isNotEmpty() || liveVisible) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(viewportHeight),
                state = messagesScroll,
                verticalArrangement = Arrangement.spacedBy(UiSpacing.medium),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    ChatMessageBubble(message)
                }
                if (liveVisible) {
                    item(key = "live-current-turn") {
                        LiveAssistantTurn(
                            userText = state.draft.takeIf { state.sending },
                            reasoning = state.partialReasoning,
                            answer = state.partial,
                            sending = state.sending,
                        )
                    }
                }
            }
        } else if (state.ready) {
            EmptyState("想聊些什么？", "写下第一个问题，或从历史会话继续。")
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(UiSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(UiSpacing.small),
            ) {
                Text(
                    "MODEL / COMPOSER",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    OutlinedButton(
                        enabled = modelIds.isNotEmpty() && idle && state.selected?.legacy != true,
                        onClick = { modelMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (selectedModel.isBlank()) "选择模型"
                            else modelLabels[selectedModel] ?: selectedModel,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = modelMenu,
                        onDismissRequest = { modelMenu = false },
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        modelIds.forEach { id ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(modelLabels[id] ?: id)
                                        if (modelLabels[id] != id) {
                                            Text(id, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                },
                                onClick = {
                                    onSelectModel(id)
                                    modelMenu = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = viewModel::editDraft,
                    enabled = idle && state.selected?.legacy != true,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                    placeholder = { Text("写下你的问题…") },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.contextBlocked) "上下文已达上限"
                        else "草稿仅保留在内存",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        enabled = idle && canConnect && !providerBusy &&
                            state.selected?.legacy == false && !state.contextBlocked &&
                            state.draft.isNotBlank() && selectedModel in modelIds,
                        onClick = { viewModel.send(selectedModel) },
                    ) {
                        AppIcon(AppSymbol.SEND, description = null)
                        Spacer(Modifier.width(UiSpacing.small))
                        Text(if (state.sending) "正在回复…" else "发送")
                    }
                }
            }
        }

        Text(
            "思考内容与正式回答分开保存。发送只把成功对话正文交给所选模型，思考内容不会进入下一轮上下文。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!canConnect) {
            Text(
                "连接网关并选择可用模型后即可发送。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.notice.isNotBlank()) {
            Text(state.notice, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (historyOpen) {
        SessionHistoryDialog(
            state = state,
            idle = idle,
            onDismiss = { historyOpen = false },
            viewModel = viewModel,
        )
    }
}

@Composable
private fun ChatMessageBubble(message: StoredChatMessage) {
    when (message.role) {
        "user" -> UserBubble(text = message.text, footer = messageFooter(message))
        "assistant" -> AssistantBubble(
            text = message.text,
            reasoning = message.reasoning,
            stateKey = "message-${message.id}",
            footer = messageFooter(message),
        )
        else -> StatusBubble(message)
    }
}

@Composable
private fun UserBubble(text: String, footer: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = UiSpacing.medium, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(UiSpacing.small),
            ) {
                Text("你", style = MaterialTheme.typography.labelSmall)
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (footer.isNotBlank()) {
                    Text(
                        footer,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    text: String,
    reasoning: String,
    stateKey: String,
    footer: String,
    streaming: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            modifier = Modifier.fillMaxWidth(0.94f),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.small),
        ) {
            ReasoningCard(
                reasoning = reasoning,
                streaming = streaming && text.isBlank(),
                stateKey = stateKey,
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = UiSpacing.medium, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(UiSpacing.small),
                ) {
                    Text(
                        if (streaming) "Magpie · 正在回答" else "Magpie",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    SelectionContainer {
                        Text(
                            text.ifBlank { "（空回复）" },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.semantics {
                                if (streaming) liveRegion = LiveRegionMode.Polite
                            },
                        )
                    }
                    if (footer.isNotBlank()) {
                        Text(
                            footer,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBubble(message: StoredChatMessage) {
    val failed = message.state in setOf("FAILED", "INTERRUPTED") ||
        message.role == "error"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = MaterialTheme.shapes.medium,
            color = if (failed) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier.padding(UiSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(UiSpacing.small),
            ) {
                Text(
                    if (message.role == "error") "本轮状态"
                    else "旧记录 · ${message.state}",
                    style = MaterialTheme.typography.labelSmall,
                )
                SelectionContainer {
                    Text(message.text, style = MaterialTheme.typography.bodyMedium)
                }
                Text(messageFooter(message), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun LiveAssistantTurn(
    userText: String?,
    reasoning: String,
    answer: String,
    sending: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.medium)) {
        if (!userText.isNullOrBlank()) {
            UserBubble(text = userText, footer = "刚刚发送")
        }
        if (reasoning.isNotBlank()) {
            ReasoningCard(
                reasoning = reasoning,
                streaming = sending && answer.isBlank(),
                stateKey = "live-reasoning",
            )
        }
        if (answer.isNotBlank()) {
            AssistantBubble(
                text = answer,
                reasoning = "",
                stateKey = "live-answer",
                footer = "",
                streaming = sending,
            )
        } else if (sending && reasoning.isBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.9f),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(UiSpacing.medium),
                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(8.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primary,
                    ) {}
                    Text(
                        "等待首个响应…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun messageFooter(message: StoredChatMessage): String {
    val state = when (message.state) {
        "RUNNING" -> "进行中（不重复发送）"
        "FAILED" -> "失败（不进入成功上下文）"
        "INTERRUPTED" -> "中断 / 结果未知"
        "LEGACY" -> "旧版存档"
        else -> ""
    }
    return listOf(chatDate(message.ts), message.engine, state)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

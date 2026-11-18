package cc.fplj.magpiemobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.fplj.magpiemobile.data.StoredChatMessage
import cc.fplj.magpiemobile.theme.UiSpacing

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ChatPanel(
    viewModel: ChatViewModel,
    modelGroups: List<ModelPickerGroup>,
    selectedModel: String,
    onSelectModel: (String) -> Unit,
    canConnect: Boolean,
    providerBusy: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var historyOpen by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    val idle = state.ready && !state.busy && !state.sending
    val messagesScroll = rememberLazyListState()
    val configuration = LocalConfiguration.current
    val compactChrome = configuration.screenHeightDp < 520 || WindowInsets.isImeVisible
    val modelEntries = remember(modelGroups) { modelGroups.flatMap { it.entries } }
    val modelIds = remember(modelEntries) { modelEntries.map { it.id } }
    val selectedEntry = remember(modelEntries, selectedModel) {
        modelEntries.firstOrNull { it.id == selectedModel }
    }
    var previousMessageCount by rememberSaveable(state.selected?.id) { mutableIntStateOf(0) }
    var lastScrolledConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    var wasViewingOlderMessages by rememberSaveable(state.selected?.id) {
        mutableStateOf(state.viewingOlderMessages)
    }
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
        val conversationChanged = lastScrolledConversationId != state.selected?.id
        if (conversationChanged) lastScrolledConversationId = state.selected?.id
        val returnedToLatest = wasViewingOlderMessages && !state.viewingOlderMessages
        wasViewingOlderMessages = state.viewingOlderMessages
        if (listItems == 0) {
            previousMessageCount = state.messages.size
            return@LaunchedEffect
        }
        val lastVisible = messagesScroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearEnd = lastVisible >= listItems - 2
        val messageAdded = state.messages.size > previousMessageCount
        if (!state.viewingOlderMessages &&
            (conversationChanged || returnedToLatest || previousMessageCount == 0 ||
                nearEnd || messageAdded)
        ) {
            messagesScroll.scrollToItem(listItems - 1)
        }
        previousMessageCount = state.messages.size
    }

    Card(
        modifier = modifier
            .fillMaxSize()
            .testTag("chat-panel"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(if (compactChrome) 8.dp else UiSpacing.card),
            verticalArrangement = Arrangement.spacedBy(
                if (compactChrome) 6.dp else UiSpacing.small,
            ),
        ) {
            if (!compactChrome) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.selected?.title ?: "新的对话",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (state.sending) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            when {
                                state.sending -> "生成中"
                                state.selected?.legacy == true -> "只读"
                                else -> "本机"
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            ModelSelectionField(
                displayName = if (selectedModel.isBlank()) "选择模型"
                else selectedEntry?.displayName ?: selectedModel,
                enabled = modelIds.isNotEmpty() && idle && state.selected?.legacy != true,
                modifier = Modifier.testTag("chat-model-selector").semantics {
                    testTagsAsResourceId = true
                },
                compact = true,
                onClick = { modelSheet = true },
            )

            if (!compactChrome) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.tiny),
                ) {
                    TextButton(
                        enabled = idle && !providerBusy,
                        onClick = viewModel::newConversation,
                    ) { Text("新会话", maxLines = 1) }
                    TextButton(enabled = idle, onClick = { historyOpen = true }) {
                        Text("历史会话", maxLines = 1)
                    }
                    TextButton(
                        enabled = !state.busy && !state.sending,
                        onClick = viewModel::refresh,
                    ) { Text("重读记录", maxLines = 1) }
                    if (state.viewingOlderMessages) {
                        TextButton(enabled = idle, onClick = viewModel::refresh) {
                            Text("回最新消息", maxLines = 1)
                        }
                    }
                    if (state.olderMessages) {
                        TextButton(enabled = idle, onClick = viewModel::olderMessages) {
                            Text("更早 50 条", maxLines = 1)
                        }
                    }
                }
            }

            if (state.selected?.legacy == true) {
                Text(
                    "旧版只读存档；新建会话后可继续发送。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.messages.isNotEmpty() || liveVisible) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("chat-message-list"),
                    state = messagesScroll,
                    verticalArrangement = Arrangement.spacedBy(UiSpacing.medium),
                    contentPadding = PaddingValues(
                        vertical = if (compactChrome) 0.dp else UiSpacing.small,
                    ),
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
            } else if (state.busy) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("读取本机记录中…", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (state.ready) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState("想聊些什么？", "写下第一个问题，或从历史会话继续。")
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            if (state.importNotice.isNotBlank()) {
                Text(state.importNotice, style = MaterialTheme.typography.bodySmall)
            }
            if (state.busy && state.messages.isNotEmpty()) {
                Text("正在刷新本机记录…", style = MaterialTheme.typography.bodySmall)
            }
            if (state.contextBlocked) {
                Text(
                    "上下文已达到安全上限；记录仍保留，请新建会话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!canConnect && !compactChrome) {
                Text(
                    "连接网关并选择可用模型后即可发送。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.notice.isNotBlank()) {
                Text(state.notice, style = MaterialTheme.typography.bodySmall)
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(if (compactChrome) 5.dp else 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = state.draft,
                        onValueChange = viewModel::editDraft,
                        enabled = idle && state.selected?.legacy != true,
                        modifier = Modifier.weight(1f),
                        minLines = 1,
                        maxLines = 5,
                        placeholder = { Text("写下你的问题…") },
                    )
                    Button(
                        enabled = idle && canConnect && !providerBusy &&
                            state.selected?.legacy == false && !state.contextBlocked &&
                            state.draft.isNotBlank() && selectedModel in modelIds,
                        modifier = Modifier
                            .size(if (compactChrome) 48.dp else 52.dp)
                            .testTag("chat-send")
                            .semantics { testTagsAsResourceId = true },
                        contentPadding = PaddingValues(0.dp),
                        shape = MaterialTheme.shapes.medium,
                        onClick = { viewModel.send(selectedModel) },
                    ) {
                        AppIcon(
                            AppSymbol.SEND,
                            description = if (state.sending) "正在等待模型回复" else "发送消息",
                        )
                    }
                }
            }
        }
    }
    ModelSelectionSheet(
        visible = modelSheet,
        groups = modelGroups,
        selectedIds = setOfNotNull(selectedModel.takeIf { it.isNotBlank() }),
        onDismissRequest = { modelSheet = false },
        onSelectionChange = { selection ->
            selection.singleOrNull()?.let(onSelectModel)
        },
        title = "选择模型",
        singleSelect = true,
    )
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

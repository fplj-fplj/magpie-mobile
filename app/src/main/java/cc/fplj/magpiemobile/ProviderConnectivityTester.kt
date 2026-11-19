package cc.fplj.magpiemobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cc.fplj.magpiemobile.core.ProviderCapabilityCheck
import cc.fplj.magpiemobile.core.ProviderCapabilityResult
import cc.fplj.magpiemobile.core.ProviderCapabilityStatus
import cc.fplj.magpiemobile.theme.UiSpacing

/**
 * RikkaHub-style model testing entry and result dialog.
 *
 * The three checks use a live, provider-scoped gateway model route. Only saved configuration is
 * used and provider credentials remain inside the Android host.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ProviderConnectivityTestButton(
    enabled: Boolean,
    owner: Long?,
    providerId: String?,
    providerName: String,
    testModels: List<String>,
    modifier: Modifier = Modifier,
) {
    var visible by remember(enabled, owner, providerId, providerName, testModels) {
        mutableStateOf(false)
    }
    OutlinedButton(
        enabled = enabled && owner != null,
        onClick = { visible = true },
        modifier = modifier
            .testTag("provider-connectivity-entry")
            .semantics { testTagsAsResourceId = true },
    ) {
        Text("连通测试")
    }
    if (visible && enabled && owner != null) {
        key(enabled, owner) {
            ProviderConnectivityDialog(
                owner = owner,
                providerId = providerId,
                providerName = providerName,
                models = testModels.distinct(),
                onDismiss = { visible = false },
            )
        }
    }
}

@Composable
private fun ProviderConnectivityDialog(
    owner: Long,
    providerId: String?,
    providerName: String,
    models: List<String>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val runController = remember(scope) { ProviderConnectivityTestRunController(scope) }
    var selectedModel by remember(providerId, models) {
        mutableStateOf(models.firstOrNull().orEmpty())
    }
    var modelPickerOpen by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var results by remember(providerId, models) {
        mutableStateOf(pendingCapabilityResults())
    }
    var message by remember(providerId, models) { mutableStateOf("") }
    var messageIsError by remember(providerId, models) { mutableStateOf(false) }
    var expandedChecks by remember(providerId, models) {
        mutableStateOf(emptySet<ProviderCapabilityCheck>())
    }
    var requestGeneration by remember { mutableIntStateOf(0) }

    DisposableEffect(runController, owner) {
        onDispose {
            ++requestGeneration
            runController.cancel()
        }
    }

    val canTest = !running && !providerId.isNullOrBlank() &&
        selectedModel.isNotBlank() && selectedModel in models
    val hasTerminalFailure = results.values.any {
        it.status == ProviderCapabilityStatus.FAILED ||
            it.status == ProviderCapabilityStatus.UNAVAILABLE
    }
    val allCapabilityChecksPassed =
        results.values.size == ProviderCapabilityCheck.entries.size &&
            results.values.all { it.status == ProviderCapabilityStatus.PASSED }

    fun resetResults() {
        results = pendingCapabilityResults()
        message = ""
        messageIsError = false
        expandedChecks = emptySet()
    }

    fun finishTest() {
        val finished = finishProviderCapabilityRun(results, message, messageIsError)
        results = finished.results
        message = finished.message
        messageIsError = finished.messageIsError
        running = false
    }

    fun close() {
        ++requestGeneration
        runController.cancel()
        running = false
        onDismiss()
    }

    fun startTest() {
        if (running) return
        val id = providerId ?: return
        val model = selectedModel
        if (!canTest || model !in models) return
        if (owner != NativeCoreSession.providerSessionId()) {
            message = "网关尚未就绪，请回到网关页完成安全连接。"
            messageIsError = true
            return
        }
        val generation = ++requestGeneration
        running = true
        resetResults()
        try {
            runController.start(
                owner = owner,
                providerId = id,
                modelId = model,
                onProgress = { next ->
                    if (running && providerTestUpdateIsCurrent(
                            generation = generation,
                            currentGeneration = requestGeneration,
                            owner = owner,
                            currentOwner = NativeCoreSession.providerSessionId(),
                        )
                    ) {
                        results = results + (next.check to next)
                    }
                },
                onSuccess = { capability ->
                    if (providerTestUpdateIsCurrent(
                            generation = generation,
                            currentGeneration = requestGeneration,
                            owner = owner,
                            currentOwner = NativeCoreSession.providerSessionId(),
                        )
                    ) {
                        results = capability.results.associateBy { it.check }
                        val passed = capability.results.all {
                            it.status == ProviderCapabilityStatus.PASSED
                        }
                        val routeFailed = capability.route == null
                        message = when {
                            routeFailed -> "未能建立可验证的提供商模型路由，三项检查均未发送。"
                            passed -> "三项模型能力检查已通过。"
                            else -> "能力检查已完成，请查看未通过或不可用的项目。"
                        }
                        messageIsError = !passed
                    } else if (generation == requestGeneration) {
                        message = "网关连接已变化，本次结果未采用；请重连后手动重试。"
                        messageIsError = true
                    }
                },
                onFailure = {
                    if (providerTestUpdateIsCurrent(
                            generation = generation,
                            currentGeneration = requestGeneration,
                            owner = owner,
                            currentOwner = NativeCoreSession.providerSessionId(),
                        )
                    ) {
                        message = "测试未能安全完成；请检查网关状态后手动重试。"
                        messageIsError = true
                    }
                },
                onFinished = {
                    if (generation == requestGeneration) {
                        finishTest()
                    }
                },
            )
        } catch (_: Exception) {
            message = "网关连接已变化，请确认连接后手动重试。"
            messageIsError = true
            finishTest()
        }
    }

    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().padding(UiSpacing.medium),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .heightIn(max = 760.dp)
                    .testTag("provider-connectivity-dialog"),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    Modifier.padding(UiSpacing.card),
                    verticalArrangement = Arrangement.spacedBy(UiSpacing.medium),
                ) {
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(UiSpacing.medium),
                    ) {
                        DialogHeader(
                            providerName = providerName,
                            running = running,
                            allPassed = allCapabilityChecksPassed,
                            hasFailure = hasTerminalFailure,
                        )

                        when {
                            providerId.isNullOrBlank() -> SavedConfigurationNotice(
                                "请先保存提供商，再运行连通测试。",
                            )
                            models.isEmpty() -> SavedConfigurationNotice(
                                "当前提供商没有已保存且启用的模型；保存模型选择后再测试。",
                            )
                            else -> {
                                ModelSelectionField(
                                    displayName = selectedModel,
                                    enabled = !running,
                                    onClick = { modelPickerOpen = true },
                                    supportingText = providerName,
                                )
                            }
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Column(
                                Modifier.padding(UiSpacing.medium),
                                verticalArrangement = Arrangement.spacedBy(UiSpacing.small),
                            ) {
                                Text("模型能力", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "依次检查普通响应、流式响应与工具调用。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                ProviderCapabilityCheck.entries.forEach { check ->
                                    val result = results[check] ?: pendingCapabilityResult(check)
                                    CapabilityResultRow(
                                        result = result,
                                        expanded = check in expandedChecks,
                                        stateKey = "provider-capability:${providerId.orEmpty()}:$selectedModel:$check",
                                        onToggleDetails = {
                                            expandedChecks = if (check in expandedChecks) {
                                                expandedChecks - check
                                            } else {
                                                expandedChecks + check
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        if (running) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.large,
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(UiSpacing.medium),
                                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.medium),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text("正在检查模型能力", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "三项请求依次进行；取消后不会继续下一项。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        if (message.isNotBlank()) {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (messageIsError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Text(
                            "测试使用已保存配置，当前未保存修改不会参与。请求会发往真实上游，并可能产生少量费用；每个工具调用仅用于检查，绝不会执行。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(UiSpacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = ::close,
                            modifier = Modifier.weight(1f),
                        ) { Text("关闭") }
                        Button(
                            enabled = running || canTest,
                            onClick = if (running) ::close else ::startTest,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (running) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(UiSpacing.small))
                            }
                            Text(
                                when {
                                    running -> "取消"
                                    allCapabilityChecksPassed -> "重新测试"
                                    else -> "开始测试"
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    ModelSelectionSheet(
        visible = modelPickerOpen,
        groups = if (models.isEmpty()) emptyList() else listOf(
            ModelPickerGroup(
                id = "provider-test:${providerId.orEmpty()}",
                title = providerName.ifBlank { "提供商模型" },
                entries = models.map { model ->
                    ModelPickerEntry(
                        id = model,
                        displayName = model,
                        providerName = providerName,
                    )
                },
            ),
        ),
        selectedIds = setOfNotNull(selectedModel.takeIf { it.isNotBlank() }),
        onDismissRequest = { modelPickerOpen = false },
        onSelectionChange = { selected ->
            selected.singleOrNull()?.let {
                selectedModel = it
                resetResults()
            }
        },
        title = "选择测试模型",
        singleSelect = true,
    )
}

@Composable
private fun DialogHeader(
    providerName: String,
    running: Boolean,
    allPassed: Boolean,
    hasFailure: Boolean,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text("测试连接", style = MaterialTheme.typography.headlineSmall)
            Text(
                providerName.ifBlank { "提供商" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Surface(
            shape = CircleShape,
            color = when {
                running || allPassed -> MaterialTheme.colorScheme.primaryContainer
                hasFailure -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ) {
            Text(
                when {
                    running -> "测试中"
                    allPassed -> "已通过"
                    hasFailure -> "需处理"
                    else -> "未测试"
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun CapabilityResultRow(
    result: ProviderCapabilityResult,
    expanded: Boolean,
    stateKey: String,
    onToggleDetails: () -> Unit,
) {
    val terminal = result.status in setOf(
        ProviderCapabilityStatus.PASSED,
        ProviderCapabilityStatus.FAILED,
        ProviderCapabilityStatus.UNAVAILABLE,
    )
    val hasDetails = result.preview.isNotBlank() || result.reasoning.isNotBlank()
    val container = when (result.status) {
        ProviderCapabilityStatus.PASSED -> MaterialTheme.colorScheme.secondaryContainer
        ProviderCapabilityStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        ProviderCapabilityStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer
        ProviderCapabilityStatus.PENDING,
        ProviderCapabilityStatus.UNAVAILABLE -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Surface(
        color = container,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("provider-test-result-${result.check.name}"),
    ) {
        Column(
            Modifier.padding(horizontal = UiSpacing.medium, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.small),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(UiSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(30.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        when (result.status) {
                            ProviderCapabilityStatus.RUNNING -> CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            else -> Text(
                                when (result.status) {
                                    ProviderCapabilityStatus.PASSED -> "✓"
                                    ProviderCapabilityStatus.FAILED -> "×"
                                    ProviderCapabilityStatus.UNAVAILABLE -> "!"
                                    else -> "·"
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(result.check.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        when (result.status) {
                            ProviderCapabilityStatus.PENDING -> "等待开始"
                            ProviderCapabilityStatus.RUNNING -> "正在请求上游"
                            ProviderCapabilityStatus.PASSED -> result.safeMessage.ifBlank { "已通过" }
                            ProviderCapabilityStatus.FAILED -> result.safeMessage.ifBlank { "未通过" }
                            ProviderCapabilityStatus.UNAVAILABLE -> result.safeMessage.ifBlank { "不可用" }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                result.latencyMs?.let {
                    Text("$it ms", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (terminal && hasDetails) {
                TextButton(onClick = onToggleDetails) {
                    Text(if (expanded) "收起详情" else "查看详情")
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
                        if (result.preview.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Column(
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = UiSpacing.medium, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text("响应预览", style = MaterialTheme.typography.labelMedium)
                                    SelectionContainer {
                                        Text(
                                            result.preview,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        ReasoningCard(
                            reasoning = result.reasoning,
                            streaming = false,
                            stateKey = "$stateKey:reasoning",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedConfigurationNotice(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth().padding(UiSpacing.medium),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun pendingCapabilityResults(): Map<ProviderCapabilityCheck, ProviderCapabilityResult> =
    ProviderCapabilityCheck.entries.associateWith(::pendingCapabilityResult)

private fun pendingCapabilityResult(check: ProviderCapabilityCheck) =
    ProviderCapabilityResult(
        check = check,
        status = ProviderCapabilityStatus.PENDING,
    )

internal data class FinishedProviderCapabilityRun(
    val results: Map<ProviderCapabilityCheck, ProviderCapabilityResult>,
    val message: String,
    val messageIsError: Boolean,
)

/** A canceled or failed run must leave every visible row in a terminal state. */
internal fun finishProviderCapabilityRun(
    results: Map<ProviderCapabilityCheck, ProviderCapabilityResult>,
    message: String,
    messageIsError: Boolean,
): FinishedProviderCapabilityRun {
    var interrupted = false
    val finished = ProviderCapabilityCheck.entries.associateWith { check ->
        val result = results[check] ?: pendingCapabilityResult(check)
        when (result.status) {
            ProviderCapabilityStatus.RUNNING -> {
                interrupted = true
                result.copy(status = ProviderCapabilityStatus.FAILED, safeMessage = "测试已终止")
            }
            ProviderCapabilityStatus.PENDING -> {
                interrupted = true
                result.copy(status = ProviderCapabilityStatus.UNAVAILABLE, safeMessage = "本项检查未发送")
            }
            else -> result
        }
    }
    val explainInterruption = interrupted && message.isBlank()
    return FinishedProviderCapabilityRun(
        results = finished,
        message = if (explainInterruption) "测试已终止，未完成的检查不会继续发送；请手动重试。" else message,
        messageIsError = messageIsError || explainInterruption,
    )
}

internal fun providerTestUpdateIsCurrent(
    generation: Int,
    currentGeneration: Int,
    owner: Long?,
    currentOwner: Long?,
): Boolean = generation == currentGeneration && owner != null && owner == currentOwner


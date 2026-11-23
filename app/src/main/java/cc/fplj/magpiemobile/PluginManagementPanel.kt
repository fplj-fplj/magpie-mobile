package cc.fplj.magpiemobile

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import cc.fplj.magpiemobile.core.*
import cc.fplj.magpiemobile.theme.UiSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal fun officialPluginRowTag(spec: String) = "official-plugin-row-${spec.hashCode().toUInt().toString(16)}"

/** A submitted draft becomes the editor baseline only after its own authoritative result. */
internal suspend fun confirmedPluginOptionsSave(
    states: StateFlow<OfficialPluginsState>,
    session: ProviderSessionKey,
    currentSession: () -> ProviderSessionKey?,
    spec: String,
    submitted: JsonObject,
): JsonObject? {
    // Observe the VM directly: Compose may coalesce the intermediate busy frame of a fast save.
    // An unknown write continues waiting for readonly reconciliation, never another POST.
    val settled = states.first { currentSession() != session || (!it.busy && !it.pending) }
    if (currentSession() != session) return null
    return settled.snapshot.entries.firstOrNull { it.spec == spec }?.options?.takeIf { it == submitted }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun PluginManagementPanel(
    viewModel: PluginManagementViewModel,
    state: OfficialPluginsState,
    enabled: Boolean,
    onBrowserEditor: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = remember(context) { PluginImporter(context) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var filter by rememberSaveable { mutableStateOf("") }
    var marketQuery by rememberSaveable { mutableStateOf("") }
    var installOpen by remember { mutableStateOf(false) }
    var installSpec by remember { mutableStateOf("") }
    var selectedSpec by remember { mutableStateOf<String?>(null) }
    var removingSpec by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val canWrite = enabled && !state.loading && !state.busy && !state.pending && !state.authBusy && state.loginRecovery == null && !importing
    fun importSelection(uri: Uri?, tree: Boolean) {
        if (uri == null || !canWrite) return
        val session = viewModel.currentSession() ?: return
        importing = true
        scope.launch {
            var staged: String? = null
            var submitted = false
            try {
                val spec = withContext(Dispatchers.IO) { importer.importPlugin(uri, tree).also { staged = it } }
                withContext(Dispatchers.IO) { importer.markSubmitted(spec) }
                installOpen = false
                submitted = viewModel.edit(PluginEdit.Add(spec), expectedSession = session)
                if (!submitted) {
                    viewModel.notice("核心连接已变化，请重新连接后导入")
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { viewModel.importError() }
            finally {
                if (!submitted) staged?.let { spec -> withContext(NonCancellable + Dispatchers.IO) { importer.removeImported(spec) } }
                importing = false
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importSelection(it, false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { importSelection(it, true) }

    Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.medium)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(UiSpacing.tiny / 4, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(UiSpacing.medium), verticalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(AppSymbol.PLUGIN)
                    Spacer(Modifier.width(UiSpacing.small))
                    Text("官方外部插件", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(enabled = enabled, onClick = onBrowserEditor) { Text("浏览器编辑") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
                    PluginTag(if (state.snapshot.bun) "Bun ${state.snapshot.bunVersion.ifBlank { "已安装" }}" else "Bun 状态待确认")
                    PluginTag("${state.snapshot.entries.size} 个已安装")
                }
                Text("提供商与工具 · 中间件 · Hooks，与官方网页共用配置。", style = MaterialTheme.typography.bodySmall)
                if (!enabled) Text("连接网关后可管理插件。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.loading || state.busy || importing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.busy) Text("核心正在处理插件操作，离开页面后任务仍会继续。", style = MaterialTheme.typography.bodySmall)
        if (state.pending) ContentCard {
            Text("等待核对操作结果", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text("连接已中断，核心任务可能仍在执行。重新读取可核对结果；不会重复提交安装。", style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = enabled && !state.busy, onClick = viewModel::refresh) { Text("核对结果") }
        }
        if (state.loginRecovery != null && state.authProvider == null) PluginLoginRecoveryCard(viewModel, state, enabled)
        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (state.notice.isNotBlank()) Text(state.notice, style = MaterialTheme.typography.bodySmall)
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf("已安装", "发现插件").forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index; if (index == 1) viewModel.market(marketQuery) }, text = { Text(title) })
            }
        }
        if (tab == 0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = enabled && !state.busy && !state.loading, onClick = viewModel::refresh) { Text("重新读取") }
                Button(enabled = canWrite, onClick = { installSpec = ""; installOpen = true }) { AppIcon(AppSymbol.ADD); Spacer(Modifier.width(UiSpacing.small)); Text("安装插件") }
            }
            OutlinedTextField(filter, { filter = it }, label = { Text("搜索已安装插件") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("plugin-installed-filter").semantics { testTagsAsResourceId = true })
            val entries = state.snapshot.entries.filter { it.displayName.contains(filter, true) || it.spec.contains(filter, true) }
            if (state.snapshot.entries.isEmpty() && !state.loading) EmptyState("连接你的工具与服务", "从官方目录安装插件，或导入已有的 JS / TS 插件。")
            else if (entries.isEmpty()) Text("没有匹配的插件", style = MaterialTheme.typography.bodySmall)
            entries.forEach { plugin ->
                Surface(onClick = { selectedSpec = plugin.spec }, enabled = enabled, shape = MaterialTheme.shapes.large,
                    modifier = Modifier.testTag(officialPluginRowTag(plugin.spec)).semantics { testTagsAsResourceId = true },
                    color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(UiSpacing.tiny / 4, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(UiSpacing.medium), verticalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(AppSymbol.PLUGIN); Spacer(Modifier.width(UiSpacing.medium))
                            Column(Modifier.weight(1f)) {
                                Text(plugin.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (plugin.isLocal) "本地 · ${plugin.version.ifBlank { "导入副本" }}" else SecretRedactor.redact(plugin.spec),
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            AppIcon(AppSymbol.NEXT)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(UiSpacing.small), verticalArrangement = Arrangement.spacedBy(UiSpacing.tiny)) {
                            PluginTag(if (plugin.off) "已停用" else if (plugin.error.isNotBlank() || plugin.middleware?.error?.isNotBlank() == true) "加载失败" else "已启用")
                            if (plugin.providerNames.isNotEmpty()) PluginTag("提供商")
                            if (plugin.toolNames.isNotEmpty()) PluginTag("工具 ${plugin.toolNames.size}")
                            if (plugin.isMiddleware) PluginTag("同步中间件")
                            if (plugin.isGateway) PluginTag("异步 Hooks")
                        }
                        if (plugin.providerNames.size > 1) Text(plugin.providerNames.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.snapshot.entries.isNotEmpty()) TextButton(enabled = canWrite, onClick = { viewModel.edit(PluginEdit.UpdateAll) }) { Text("更新全部插件") }
        } else {
            OutlinedTextField(marketQuery, { marketQuery = it }, label = { Text("搜索官方目录与 npm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
                Button(enabled = enabled && !state.loading && !state.busy, onClick = { viewModel.market(marketQuery) }) { Text("搜索") }
                TextButton(enabled = enabled && !state.loading, onClick = { marketQuery = ""; viewModel.market("") }) { Text("官方目录") }
                TextButton(enabled = enabled, onClick = onBrowserEditor) { Text("完整市场") }
            }
            state.market.listings.forEach { listing ->
                ContentCard {
                    Text(listing.name.ifBlank { listing.packageId }, style = MaterialTheme.typography.titleMedium)
                    Text(listing.packageId, style = MaterialTheme.typography.bodySmall)
                    val description = listing.description.ifBlank { listing.summary }
                    if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (listing.providers.isNotEmpty()) Text(listing.providers.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    val installed = state.market.isInstalled(listing)
                    Button(enabled = canWrite && !installed && listing.packageId.isNotBlank(), onClick = {
                        installSpec = listing.packageId; installOpen = true
                    }) { Text(if (installed) "已安装" else "安装") }
                }
            }
            if (state.market.listings.isEmpty() && !state.loading) EmptyState("查找需要的插件", "输入名称搜索，或打开官方网页查看完整目录。")
        }
    }

    if (installOpen) AlertDialog(onDismissRequest = { if (!importing) installOpen = false },
        title = { Text("安装官方外部插件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.medium)) {
                Text("输入 npm 包名、固定版本或 GitHub 仓库地址。")
                OutlinedTextField(installSpec, { installSpec = it }, label = { Text("插件地址 / spec") },
                    placeholder = { Text("package@version 或 github:owner/repo") }, modifier = Modifier.fillMaxWidth())
                Text("也可以导入 JS / TS 文件，或包含 package.json 的插件目录。", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = canWrite, onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("选择插件文件") }
                TextButton(enabled = canWrite, onClick = { folderPicker.launch(null) }) { Text("选择插件目录") }
            }
        },
        confirmButton = { Button(enabled = canWrite && installSpec.isNotBlank(), onClick = {
            viewModel.edit(PluginEdit.Add(installSpec.trim())); installSpec = ""; installOpen = false
        }) { Text("安装") } },
        dismissButton = { TextButton(enabled = !importing, onClick = { installOpen = false }) { Text("取消") } })

    val selected = state.snapshot.entries.firstOrNull { it.spec == selectedSpec }
    if (selected != null) PluginDetailDialog(selected, state, canWrite, { selectedSpec = null },
        { viewModel.edit(PluginEdit.Off(selected.spec, !selected.off)) },
        { viewModel.edit(PluginEdit.Upgrade(selected.spec)) },
        { removingSpec = selected.spec },
        { value, onSaved ->
            val session = viewModel.currentSession()
            if (session != null && viewModel.edit(PluginEdit.Options(selected.spec, value), expectedSession = session)) {
                scope.launch {
                    confirmedPluginOptionsSave(viewModel.state, session, viewModel::currentSession, selected.spec, value)
                        ?.let(onSaved)
                }
            }
        },
        { provider, method -> selectedSpec = null; viewModel.selectLogin(provider, method) },
        { provider -> viewModel.edit(PluginEdit.Prefer(selected.spec, provider)) }, onBrowserEditor,
        { viewModel.edit(PluginEdit.RestoreGatewayAccess) })

    removingSpec?.let { spec -> AlertDialog(onDismissRequest = { removingSpec = null }, title = { Text("移除此插件？") },
        text = { Text("它提供的模型、工具和网关 hooks 将停止使用。") },
        confirmButton = { TextButton(enabled = canWrite, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = {
            viewModel.edit(PluginEdit.Remove(spec))
            removingSpec = null; selectedSpec = null
        }) { Text("移除插件") } }, dismissButton = { TextButton(onClick = { removingSpec = null }) { Text("保留") } }) }

    if (state.authProvider != null) PluginLoginDialog(viewModel, state) { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun PluginTag(label: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
        Text(label, Modifier.padding(horizontal = UiSpacing.small, vertical = UiSpacing.tiny), style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PluginLoginRecoveryCard(viewModel: PluginManagementViewModel, state: OfficialPluginsState, enabled: Boolean) {
    val recovery = state.loginRecovery ?: return
    var confirmingEnd by remember { mutableStateOf(false) }
    val canEnd = state.authWorkers == 0 && !state.authBusy
    ContentCard {
        Text("登录结果待核对", style = MaterialTheme.typography.titleMedium)
        Text(recovery.notice, style = MaterialTheme.typography.bodySmall)
        if (!canEnd) Text("正在等待本地请求退出，暂时不能结束跟踪。", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
            TextButton(enabled = enabled && !state.authBusy && !state.loading, onClick = viewModel::refreshLogin) { Text("只读核对") }
            TextButton(enabled = canEnd, onClick = { confirmingEnd = true },
                modifier = Modifier.testTag("plugin-login-end-tracking").semantics { testTagsAsResourceId = true }) { Text("结束本地跟踪") }
        }
    }
    if (confirmingEnd) AlertDialog(onDismissRequest = { confirmingEnd = false },
        title = { Text("结束本地跟踪？") },
        text = { Text("这只会清除本机的跟踪记录，不撤销原核心的登录任务，也不能证明任务已经取消。旧任务仍可能继续；之后不会自动重新发起登录。") },
        confirmButton = { TextButton(enabled = canEnd, onClick = { viewModel.endLoginTracking(); confirmingEnd = false },
            modifier = Modifier.testTag("plugin-login-confirm-end-tracking").semantics { testTagsAsResourceId = true }) { Text("结束跟踪") } },
        dismissButton = { TextButton(onClick = { confirmingEnd = false }) { Text("继续核对") } })
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PluginDetailDialog(
    plugin: ManagedPlugin, state: OfficialPluginsState, canWrite: Boolean,
    close: () -> Unit, toggle: () -> Unit, upgrade: () -> Unit, remove: () -> Unit,
    saveOptions: (JsonObject, (JsonObject) -> Unit) -> Unit,
    login: (PluginProvider, Int) -> Unit, prefer: (String) -> Unit, browser: () -> Unit,
    restoreGateway: () -> Unit,
) {
    var tab by remember(plugin.spec) { mutableStateOf(0) }
    var baseOptions by remember(plugin.spec) { mutableStateOf(plugin.options) }
    var options by remember(plugin.spec) { mutableStateOf(Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),
        plugin.options.takeIf { it.isNotEmpty() } ?: plugin.optionsExample)) }
    var optionsError by remember(plugin.spec) { mutableStateOf("") }
    FullScreenDialog(onDismissRequest = close) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = UiSpacing.medium)) {
                Row(Modifier.fillMaxWidth().padding(vertical = UiSpacing.small), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { AppIcon(AppSymbol.BACK, description = "返回插件列表") }
                    Column(Modifier.weight(1f)) {
                        Text(plugin.displayName, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                        Text(plugin.version.ifBlank { "本地或未标注版本" }, style = MaterialTheme.typography.bodySmall)
                    }
                }
                TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                    listOf("连接与工具", "参数", "诊断").forEachIndexed { index, title -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) }) }
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在保存，核心完成后会刷新此页。", style = MaterialTheme.typography.bodySmall)
                }
                if (state.pending) Text("操作结果待核对，请返回列表重新读取。", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (state.notice.isNotBlank()) Text(state.notice, style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = UiSpacing.medium), verticalArrangement = Arrangement.spacedBy(UiSpacing.medium)) {
                    when (tab) {
                        0 -> {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
                                FilterChip(selected = !plugin.off, enabled = canWrite, onClick = toggle, label = { Text(if (plugin.off) "启用插件" else "已启用 · 点击停用") })
                                if (!plugin.isLocal) OutlinedButton(enabled = canWrite, onClick = upgrade) { Text("更新插件") }
                            }
                            if (plugin.isLocal) Text("本地插件保留导入时的副本。需要替换文件时，请移除后重新导入。", style = MaterialTheme.typography.bodySmall)
                            state.providers.filter { it.spec == plugin.spec }.forEach { provider ->
                                ContentCard {
                                    Text(provider.name, style = MaterialTheme.typography.titleMedium)
                                    Text(if (provider.signedIn) "已连接 · ${provider.models} 个模型" else "尚未登录", style = MaterialTheme.typography.bodySmall)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
                                        provider.methods.forEachIndexed { index, method -> OutlinedButton(enabled = canWrite && !plugin.off, onClick = { login(provider, index) }) {
                                            Text(method.labels["zh"] ?: method.labels["zh-CN"] ?: method.label.ifBlank { "登录方式 ${index + 1}" })
                                        } }
                                    }
                                    TextButton(enabled = canWrite, onClick = { prefer(provider.pid) }) { Text("使用此插件提供 ${provider.name}") }
                                }
                            }
                            if (plugin.tools.isNotEmpty()) ContentCard {
                                Text("可用工具", style = MaterialTheme.typography.titleMedium)
                                plugin.tools.forEach { tool ->
                                    Text(tool.name, style = MaterialTheme.typography.bodyMedium)
                                    if (tool.description.isNotBlank()) Text(tool.description, style = MaterialTheme.typography.bodySmall)
                                }
                                Text("MCP 地址", style = MaterialTheme.typography.labelLarge)
                                SelectionContainer { Text("http://127.0.0.1:3425/mcp/magpie/tools",
                                    style = MaterialTheme.typography.bodySmall) }
                                Text("在客户端添加此地址，并填写网关客户端密钥。", style = MaterialTheme.typography.bodySmall)
                            }
                            if (plugin.isMiddleware) Text("同步中间件在网关请求中执行。", style = MaterialTheme.typography.bodySmall)
                            if (plugin.isGateway) Text("异步 Hooks 在插件宿主中执行。", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = browser) { Text("在官方网页查看完整配置") }
                        }
                        1 -> {
                            Text("插件参数", style = MaterialTheme.typography.titleMedium)
                            Text("使用插件声明的 options。修改后由官方核心保存。", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(options, { options = it; optionsError = "" }, label = { Text("JSON 参数") }, minLines = 8,
                                enabled = canWrite, textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().testTag("plugin-options-json").semantics { testTagsAsResourceId = true })
                            if (optionsError.isNotBlank()) Text(optionsError, color = MaterialTheme.colorScheme.error)
                            Button(enabled = canWrite, onClick = {
                                if (plugin.options != baseOptions) optionsError = "参数已被其他入口修改，请关闭详情后重新打开"
                                else {
                                    val submitted = runCatching { Json.parseToJsonElement(options) as? JsonObject }.getOrNull()
                                    if (submitted == null) optionsError = "插件参数必须是有效的 JSON 对象"
                                    else {
                                        optionsError = ""
                                        saveOptions(submitted) { saved -> baseOptions = saved }
                                    }
                                }
                            }) { Text("保存参数") }
                        }
                        2 -> {
                            ContentCard {
                                Text("加载状态", style = MaterialTheme.typography.titleMedium)
                                Text(if (plugin.off) "已停用" else if (plugin.error.isEmpty()) "核心已接入" else "插件加载失败")
                                if (plugin.error.isNotBlank()) Text(SecretRedactor.redact(plugin.error).take(1000), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                Text(SecretRedactor.redact(plugin.spec), style = MaterialTheme.typography.bodySmall)
                                Text("Bun ${state.snapshot.bunVersion.ifBlank { "版本待确认" }}", style = MaterialTheme.typography.bodySmall)
                            }
                            plugin.middleware?.let { diagnostic -> ContentCard {
                                Text("同步中间件", style = MaterialTheme.typography.titleMedium)
                                Text("${diagnostic.calls} 次调用 · ${diagnostic.failures} 次失败")
                                Text("平均 ${diagnostic.avgMicros} μs", style = MaterialTheme.typography.bodySmall)
                                if (diagnostic.hooks.isNotEmpty()) Text(diagnostic.hooks.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                if (diagnostic.error.isNotBlank()) Text(SecretRedactor.redact(diagnostic.error).take(1000), color = MaterialTheme.colorScheme.error)
                            } }
                            plugin.gateway?.let { diagnostic -> ContentCard {
                                Text("异步 Hooks", style = MaterialTheme.typography.titleMedium)
                                Text("${diagnostic.calls} 次调用 · ${diagnostic.failures} 次失败")
                                Text("平均 ${diagnostic.avgMillis} ms · ${diagnostic.lastOutcome}", style = MaterialTheme.typography.bodySmall)
                            } }
                            OutlinedButton(enabled = canWrite, onClick = restoreGateway) {
                                Text("恢复插件网关连接")
                            }
                            Text("重新创建插件宿主的网关调用密钥，适用于该密钥被删除或停用之后。", style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = canWrite, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = remove) { Text("移除插件") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PluginLoginDialog(viewModel: PluginManagementViewModel, state: OfficialPluginsState, browser: (String) -> Unit) {
    val provider = state.authProvider ?: return
    val method = provider.methods.getOrNull(state.authMethod) ?: return
    var answer by remember(provider.pid, state.authMethod, state.prompt?.key) { mutableStateOf("") }
    var apiKey by remember(provider.pid, state.authMethod) { mutableStateOf("") }
    var callback by remember(state.signIn?.id) { mutableStateOf("") }
    val cancelRequested = state.loginRecovery?.cancelRequested == true
    val canSubmit = !state.authBusy && state.loginRecovery == null
    val canCallback = !state.authBusy && !cancelRequested && state.loginRecovery?.stage == PluginLoginRecovery.Stage.WAITING
    val sensitiveClipboard = rememberSensitiveClipboardManager()
    FullScreenDialog(onDismissRequest = { viewModel.cancelLogin(); viewModel.hideLogin() }) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(UiSpacing.card), verticalArrangement = Arrangement.spacedBy(UiSpacing.medium)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("连接 ${provider.name}", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = { if (cancelRequested) viewModel.hideLogin() else viewModel.cancelLogin() }) {
                        Text(when {
                            state.signIn?.state in setOf("done", "failed", "canceled", "cancelled") -> "返回插件"
                            cancelRequested -> "后台等待"
                            else -> "取消登录"
                        })
                    }
                }
                Text(method.labels["zh"] ?: method.labels["zh-CN"] ?: method.label, style = MaterialTheme.typography.bodySmall)
                if (state.authBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(UiSpacing.medium)) {
                        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
                        if (state.notice.isNotBlank()) Text(state.notice, style = MaterialTheme.typography.bodySmall)
                        if (state.loginRecovery != null) PluginLoginRecoveryCard(viewModel, state, true)
                        val prompt = state.prompt
                        val signIn = state.signIn
                        if (prompt != null) {
                            Text(prompt.messages["zh"] ?: prompt.messages["zh-CN"] ?: prompt.message)
                            if (prompt.type == "select") prompt.options.forEach { option ->
                                OutlinedButton(enabled = canSubmit, onClick = { viewModel.prompt(option.value) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(option.labels["zh"] ?: option.labels["zh-CN"] ?: option.label.ifBlank { option.value })
                                }
                            } else {
                                val sensitive = Regex("(?i)key|token|password|secret|credential").containsMatchIn(prompt.key)
                                val field: @Composable () -> Unit = {
                                    OutlinedTextField(answer, { answer = it }, label = { Text(prompt.placeholder.ifBlank { "填写此项" }) }, enabled = canSubmit,
                                        visualTransformation = if (sensitive) PasswordVisualTransformation() else VisualTransformation.None,
                                        modifier = Modifier.fillMaxWidth())
                                }
                                if (sensitive) CompositionLocalProvider(LocalClipboardManager provides sensitiveClipboard, content = field)
                                else field()
                                Button(enabled = canSubmit, onClick = { viewModel.prompt(answer); answer = "" }) { Text("下一步") }
                            }
                        } else if (signIn == null) {
                            if (method.type == "api" && !method.keyless) CompositionLocalProvider(LocalClipboardManager provides sensitiveClipboard) {
                                OutlinedTextField(apiKey, { apiKey = it }, label = { Text(method.placeholder.ifBlank { "API 密钥" }) },
                                    singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    enabled = canSubmit, modifier = Modifier.fillMaxWidth().testTag("plugin-login-key").semantics { testTagsAsResourceId = true })
                            }
                            Button(enabled = canSubmit && (method.type != "api" || method.keyless || apiKey.isNotBlank()), onClick = { viewModel.startLogin(apiKey); apiKey = "" }) {
                                Text(if (method.type == "api") "保存并连接" else "开始登录")
                            }
                        } else {
                            val finished = signIn.state in setOf("done", "failed", "canceled", "cancelled")
                            Text(when (signIn.state) {
                                "done" -> "连接成功"
                                "failed" -> "登录未完成，请重新发起"
                                "canceled", "cancelled" -> "登录已取消"
                                "installing" -> "正在准备登录"
                                else -> "等待完成登录"
                            }, style = MaterialTheme.typography.titleMedium)
                            if (signIn.instructions.isNotBlank()) Text(SecretRedactor.redact(signIn.instructions), style = MaterialTheme.typography.bodyMedium)
                            if (signIn.code.isNotBlank()) Text(signIn.code, style = MaterialTheme.typography.headlineMedium)
                            if (!finished) {
                                Button(enabled = !state.authBusy && !cancelRequested, onClick = { viewModel.openLoginBrowser(browser) }) { Text("打开登录浏览器") }
                                if (signIn.pasteCallback || signIn.pasteCode || signIn.pasteKey) {
                                    CompositionLocalProvider(LocalClipboardManager provides sensitiveClipboard) {
                                        OutlinedTextField(callback, { callback = it }, label = { Text(if (signIn.pasteCode) "授权码" else "完整回调地址 / 回执") },
                                            enabled = canCallback,
                                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                                    }
                                    Button(enabled = canCallback && callback.isNotBlank(), onClick = { viewModel.submitCallback(callback); callback = "" }) { Text("提交回执") }
                                }
                                TextButton(enabled = !state.authBusy, onClick = viewModel::refreshLogin) { Text("重新读取登录状态") }
                            }
                        }
                }
            }
        }
    }
}

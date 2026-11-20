package cc.fplj.magpiemobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.fplj.magpiemobile.core.GatewayEngineController
import cc.fplj.magpiemobile.core.SecretRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SubscriptionPluginCard(
    viewModel: SubscriptionPluginViewModel,
    pluginState: PluginUiState,
) {
    var pluginId by remember { mutableStateOf("") }
    var pluginName by remember { mutableStateOf("") }
    val pluginVersion = "1.0.0"
    val pluginEntry = "index.js"
    val pluginScript = "console.log('Plugin initialized');"

    ContentCard {
            Text("插件文件与运行时", style = MaterialTheme.typography.titleLarge)
            Text("本页管理本机示例文件。完整插件请在官方网页中管理；原生插件执行对接仍待完善。插件与应用同 UID 运行。", style = MaterialTheme.typography.bodySmall)
            val bunText = if (pluginState.bunStatus.installed) {
                "Bun 载荷：预置就绪 (" + pluginState.bunStatus.architecture + ")"
            } else {
                "Bun 载荷：未检测到预置 ELF"
            }
            Text(bunText, style = MaterialTheme.typography.bodySmall, color = if (pluginState.bunStatus.installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)

            if (pluginState.plugins.isEmpty()) {
                Text("暂无插件文件。下方可创建本地示例记录。", style = MaterialTheme.typography.bodySmall)
            } else {
                pluginState.plugins.forEach { plugin ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    plugin.name + " (" + plugin.id + " v" + plugin.version + ")",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (plugin.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                )
                                Text(
                                    "入口: " + plugin.entrypoint + " · SHA: " + plugin.checksum.take(12) + "…",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { viewModel.togglePlugin(plugin.id) }) {
                                    Text(if (plugin.enabled) "已启用" else "已停用")
                                }
                                TextButton(onClick = { viewModel.rollbackPlugin(plugin.id) }) {
                                    Text("回滚")
                                }
                                TextButton(onClick = { viewModel.removePlugin(plugin.id) }) {
                                    Text("移除", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = pluginId,
                    onValueChange = { pluginId = it },
                    label = { Text("插件标识 (例如: custom-filter)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pluginName,
                    onValueChange = { pluginName = it },
                    label = { Text("插件名称 (例如: Custom Filter)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = pluginId.isNotBlank() && pluginName.isNotBlank(),
                    onClick = {
                        viewModel.installPlugin(pluginId, pluginName, pluginVersion, pluginEntry, pluginScript)
                        pluginId = ""
                        pluginName = ""
                    }
                ) {
                    Text("保存示例插件")
                }
            }

            if (pluginState.error.isNotEmpty()) {
                Text(pluginState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (pluginState.notice.isNotEmpty()) {
                Text(pluginState.notice, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
            }
    }
}


@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ChatProfileCard(
    viewModel: ChatProfileViewModel,
    profileState: ChatProfileUiState,
) {
    var expanded by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(ChatProfileFormState()) }

    ContentCard(Modifier.testTag("chat-profile-card").semantics { testTagsAsResourceId = true }) {
            Text("智能体与配置预设 (Agent Profiles)", style = MaterialTheme.typography.titleMedium)
            val active = profileState.activeProfile
            if (profileState.profiles.isNotEmpty()) {
                Text(
                    "当前预设: " + active.name + " (" + active.id + ") · " + chatProfileParameterSummary(active),
                    style = MaterialTheme.typography.bodyMedium
                )
                chatProfilePromptPreview(active)?.let { prompt ->
                    Text(
                        "系统提示词: " + prompt,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                Text("预设暂不可读取", style = MaterialTheme.typography.bodyMedium)
            }

            if (profileState.storageError.isNotEmpty()) {
                Text(profileState.storageError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { viewModel.retryReload() }) { Text("重试读取") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = profileState.profiles.isNotEmpty(), onClick = { expanded = true }) {
                    Text("切换预设 (" + profileState.profiles.size + ")")
                }
                TextButton(onClick = { form = form.copy(editing = !form.editing) }) {
                    Text(if (form.editing) "收起新建" else "新建预设")
                }
            }

            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                profileState.profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(profile.name + if (profile.id == active.id) " (当前)" else "")
                                Text(chatProfileParameterSummary(profile) + " · " + profile.id, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = {
                            viewModel.selectProfile(profile.id)
                            expanded = false
                        }
                    )
                }
            }

            if (form.editing) {
                Column(
                    modifier = Modifier.testTag("chat-profile-form").semantics { testTagsAsResourceId = true },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = form.id,
                        onValueChange = { form = form.copy(id = it) },
                        label = { Text("Profile 标识 (例如: code-helper)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("chat-profile-id").semantics { testTagsAsResourceId = true }
                    )
                    OutlinedTextField(
                        value = form.name,
                        onValueChange = { form = form.copy(name = it) },
                        label = { Text("预设名称 (例如: 编程助手)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("chat-profile-name").semantics { testTagsAsResourceId = true }
                    )
                    OutlinedTextField(
                        value = form.prompt,
                        onValueChange = { form = form.copy(prompt = it) },
                        label = { Text("系统提示词 (System Prompt)") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth().testTag("chat-profile-prompt").semantics { testTagsAsResourceId = true }
                    )
                    OutlinedTextField(
                        value = form.temperature,
                        onValueChange = { form = form.copy(temperature = it, error = "") },
                        label = { Text("采样温度 (0.0 .. 2.0)") },
                        isError = form.error.isNotEmpty(),
                        supportingText = if (form.error.isNotEmpty()) ({ Text(form.error) }) else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("chat-profile-temperature").semantics { testTagsAsResourceId = true }
                    )
                    Button(
                        enabled = form.canSubmit && profileState.canWriteProfiles,
                        modifier = Modifier.testTag("chat-profile-save").semantics { testTagsAsResourceId = true },
                        onClick = {
                            form = form.submit { id, name, prompt, temperature ->
                                viewModel.saveProfile(id, name, prompt, temperature = temperature)
                            }
                        }
                    ) {
                        Text("保存预设")
                    }
                }
            }

            if (profileState.error.isNotEmpty()) {
                Text(profileState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (profileState.notice.isNotEmpty()) {
                Text(profileState.notice, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
            }
    }
}


@Composable
internal fun ModelCatalogCard(shell: AppShellState, ready: Boolean, idle: Boolean, refresh: () -> Unit) {
    ContentCard {
            Text("模型目录与速览", style = MaterialTheme.typography.titleMedium)
            if (ready && shell.catalogValid) {
                val modelCountText = "可用模型：${shell.models.size} 个"
                val selectedText = if (shell.selectedModel.isNotBlank()) "当前默认：${shell.selectedModel}" else "尚未选择默认模型"
                Text("$modelCountText · $selectedText", style = MaterialTheme.typography.bodyMedium)
                Text(shell.modelsNotice, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(shell.modelsNotice, style = MaterialTheme.typography.bodySmall)
            }
            Button(enabled = ready && idle && !shell.loadingModels, onClick = refresh) { Text(if (shell.loadingModels) "读取中…" else "刷新模型列表") }
    }
}

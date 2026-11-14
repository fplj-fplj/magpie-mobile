package cc.fplj.magpiemobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cc.fplj.magpiemobile.core.GatewayEngineController
import cc.fplj.magpiemobile.core.SecretRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SystemStabilityCard(
    viewModel: StabilityViewModel,
    stabilityState: SystemStabilityUiState,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var settingsNotice by remember { mutableStateOf("") }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    SectionCard("设备与后台运行") {
            Text("网络状态: " + stabilityState.networkState.description, style = MaterialTheme.typography.bodyMedium)
            Text("电源策略: " + stabilityState.batteryStatus.description, style = MaterialTheme.typography.bodySmall)

            if (stabilityState.batteryStatus.canRequest) {
                Button(onClick = {
                    val route = BatteryOptimizationHelper.openOptimizationSettings(context)
                    settingsNotice = (if (route.intent != null) "已打开${route.label}。" else "") + route.note
                }) {
                    Text("设置电池优化白名单")
                }
            }
            if (settingsNotice.isNotEmpty()) Text(settingsNotice, style = MaterialTheme.typography.bodyMedium)

            if (stabilityState.memoryTrimNotice.isNotEmpty()) {
                Text(
                    stabilityState.memoryTrimNotice,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { viewModel.clearMemoryNotice() }) {
                    Text("清除内存告警")
                }
            }

            TextButton(onClick = { viewModel.refresh() }) {
                Text("重新检测网络与电源策略")
            }
    }
}


@Composable
internal fun PluginMarketCard(
    viewModel: PluginMarketViewModel,
    marketState: PluginMarketUiState,
) {
    ContentCard {
            Text("插件候选目录", style = MaterialTheme.typography.titleLarge)
            Text(
                "收录 " + marketState.entries.size + " 个本机候选示例。完整插件市场请使用上方的官方网页编辑器。",
                style = MaterialTheme.typography.bodyMedium
            )

            marketState.entries.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.name + " v" + entry.version, style = MaterialTheme.typography.bodyMedium)
                        Text(entry.description, style = MaterialTheme.typography.bodySmall)
                        Text("作者: " + entry.author + " · " + entry.capabilities.joinToString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val isInstalled = marketState.installedIds.contains(entry.id)
                    Button(
                        onClick = { viewModel.installFromMarket(entry) },
                        enabled = !isInstalled
                    ) {
                        Text(if (isInstalled) "已保存" else "保存示例")
                    }
                }
            }

            if (marketState.error.isNotEmpty()) {
                Text(marketState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (marketState.notice.isNotEmpty()) {
                Text(marketState.notice, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
            }
    }
}


@Composable
internal fun McpServerCard(
    viewModel: McpViewModel,
    mcpState: McpUiState,
) {
    var editMode by remember { mutableStateOf(false) }
    var newId by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newEndpoint by remember { mutableStateOf("http://127.0.0.1:8080/mcp") }
    var newDesc by remember { mutableStateOf("") }

    ContentCard {
            Text("本机 MCP 配置记录", style = MaterialTheme.typography.titleLarge)
            Text("这些记录尚未同步到核心。要使用核心 MCP 服务，请在官方网页的资源库中配置。", style = MaterialTheme.typography.bodySmall)
            Text(
                "受控端点: " + mcpState.servers.size + " 个 (已启用: " + mcpState.activeCount + ") · 支持 HTTP/SSE/STDIO 契约",
                style = MaterialTheme.typography.bodyMedium
            )

            if (mcpState.servers.isNotEmpty()) {
                mcpState.servers.forEach { server ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(server.name + " (" + server.transport.name + ")", style = MaterialTheme.typography.bodyMedium)
                            Text(server.endpoint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (server.description.isNotBlank()) {
                                Text(server.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row {
                            TextButton(onClick = { viewModel.toggleServer(server.id) }) {
                                Text(if (server.enabled) "已启用" else "已停用")
                            }
                            TextButton(onClick = { viewModel.deleteServer(server.id) }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            } else {
                Text("暂无注册的 MCP 服务。仅允许本地环回 (127.0.0.1) 或安全 HTTPS 端点。", style = MaterialTheme.typography.bodySmall)
            }

            Button(onClick = { editMode = !editMode }) {
                Text(if (editMode) "收起注册面板" else "注册新 MCP 服务")
            }

            if (editMode) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = newId,
                        onValueChange = { newId = it },
                        label = { Text("服务标识 (例如: local-math)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("服务名称 (例如: 本地数学计算器)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newEndpoint,
                        onValueChange = { newEndpoint = it },
                        label = { Text("端点 URL (环回 HTTP 或远程 HTTPS)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newDesc,
                        onValueChange = { newDesc = it },
                        label = { Text("描述说明 (可选)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        enabled = newId.isNotBlank() && newName.isNotBlank() && newEndpoint.isNotBlank(),
                        onClick = {
                            viewModel.addServer(
                                id = newId,
                                name = newName,
                                transport = cc.fplj.magpiemobile.core.McpTransport.HTTP,
                                endpoint = newEndpoint,
                                description = newDesc
                            )
                            newId = ""
                            newName = ""
                            newEndpoint = "http://127.0.0.1:8080/mcp"
                            newDesc = ""
                            editMode = false
                        }
                    ) {
                        Text("保存并注册")
                    }
                }
            }

            if (mcpState.error.isNotEmpty()) {
                Text(mcpState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (mcpState.notice.isNotEmpty()) {
                Text(mcpState.notice, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
            }
    }
}


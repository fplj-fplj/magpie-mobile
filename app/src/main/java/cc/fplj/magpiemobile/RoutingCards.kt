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
import cc.fplj.magpiemobile.core.GatewayEngineController
import cc.fplj.magpiemobile.core.SecretRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ModelRoutingCard(
    clientReady: Boolean,
    viewModel: RoutingViewModel,
    routingState: RoutingUiState,
) {
    var newAlias by remember { mutableStateOf("") }
    var newTarget by remember { mutableStateOf("") }
    var newFallback by remember { mutableStateOf("") }

    ContentCard {
            Text("本机路由示例", style = MaterialTheme.typography.titleMedium)
            Text(
                "本页规则只保存在内存，尚未写入核心。实际网关路由请使用上方的官方网页编辑器配置。",
                style = MaterialTheme.typography.bodySmall
            )

            if (routingState.rules.isEmpty()) {
                Text("尚无本机路由示例；实际网关规则请在官方网页中查看。", style = MaterialTheme.typography.bodySmall)
            } else {
                routingState.rules.forEach { rule ->
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
                                    rule.alias + " -> " + rule.targetModel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (rule.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                )
                                if (rule.fallbackModels.isNotEmpty()) {
                                    Text(
                                        "回退链: " + rule.fallbackModels.joinToString(", "),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { viewModel.toggleRule(rule.alias) }) {
                                    Text(if (rule.enabled) "已启用" else "已停用")
                                }
                                TextButton(onClick = { viewModel.removeRule(rule.alias) }) {
                                    Text("删除", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = newAlias,
                    onValueChange = { newAlias = it },
                    label = { Text("请求别名 (例如: fast-chat)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newTarget,
                    onValueChange = { newTarget = it },
                    label = { Text("目标模型 (例如: openai/gpt-4o-mini)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newFallback,
                    onValueChange = { newFallback = it },
                    label = { Text("备选回退模型 (可选，英文逗号分隔)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = newAlias.isNotBlank() && newTarget.isNotBlank(),
                    onClick = {
                        val fallbacks = newFallback.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        viewModel.addRule(newAlias, newTarget, fallbacks)
                        newAlias = ""
                        newTarget = ""
                        newFallback = ""
                    }
                ) {
                    Text("添加路由规则")
                }
            }

            if (routingState.error.isNotEmpty()) {
                Text(routingState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (routingState.notice.isNotEmpty()) {
                Text(routingState.notice, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
            }
    }
}


@Composable
internal fun KeyPoolingCard(
    clientReady: Boolean,
    viewModel: KeyPoolViewModel,
    poolState: KeyPoolUiState,
) {
    var newKeyId by remember { mutableStateOf("") }
    var newSecret by remember { mutableStateOf("") }
    var providerInput by remember { mutableStateOf("openai") }

    ContentCard {
            Text("本机号池示例", style = MaterialTheme.typography.titleMedium)
            Text(
                "本页仅记录内存示例，尚未写入核心。实际提供商多 Key 与账号池请在官方网页中配置。",
                style = MaterialTheme.typography.bodySmall
            )

            if (poolState.keys.isEmpty()) {
                Text("尚无本机号池示例。", style = MaterialTheme.typography.bodySmall)
            } else {
                poolState.keys.forEach { keyEntry ->
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
                                    keyEntry.id + " (" + keyEntry.maskedSecret + ")",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (keyEntry.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { viewModel.toggleKey(keyEntry.id) }) {
                                    Text(if (keyEntry.enabled) "已启用" else "已停用")
                                }
                                TextButton(onClick = { viewModel.removeKey(keyEntry.id) }) {
                                    Text("移除")
                                }
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = providerInput,
                    onValueChange = { providerInput = it },
                    label = { Text("提供商标识 (例如: openai)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newKeyId,
                    onValueChange = { newKeyId = it },
                    label = { Text("密钥标识 (例如: account-2)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newSecret,
                    onValueChange = { newSecret = it },
                    label = { Text("API Key 内容 (sk-...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = providerInput.isNotBlank() && newKeyId.isNotBlank() && newSecret.isNotBlank(),
                    onClick = {
                        viewModel.addKey(providerInput, newKeyId, newSecret)
                        newKeyId = ""
                        newSecret = ""
                    }
                ) {
                    Text("添加至密钥池")
                }
            }

            if (poolState.error.isNotEmpty()) {
                Text(poolState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (poolState.notice.isNotEmpty()) {
                Text(poolState.notice, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
            }
    }
}


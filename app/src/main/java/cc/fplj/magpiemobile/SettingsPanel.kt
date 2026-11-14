package cc.fplj.magpiemobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.fplj.magpiemobile.core.EnginePaths
import cc.fplj.magpiemobile.core.GatewayEngineController
import cc.fplj.magpiemobile.core.SecretRedactor
import cc.fplj.magpiemobile.preferences.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PreferencesCard(shell: AppShellState, appShell: AppShellViewModel) {
    SectionCard(title = "本机界面偏好") {
            Text("主题、最近页面和模型标识保存于 App 私有 DataStore；不另存提供商配置、密钥或聊天正文。", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { theme ->
                    FilterChip(selected = shell.theme == theme, onClick = { appShell.selectTheme(theme) }, label = {
                        Text(when (theme) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" })
                    })
                }
            }
            if (!shell.preferences.ready) Text("偏好尚未确认；当前临时选择可使用，原文件不会被重建。", style = MaterialTheme.typography.bodySmall)
            shell.preferences.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (shell.saveNotice.isNotEmpty()) Text(shell.saveNotice, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = appShell::retryPreferences) { Text("重新读取本机偏好") }
    }

}

@Composable
internal fun DiagnosticsPanel(layout: EnginePaths.Layout, gateway: GatewayEngineController.Status, clientReady: Boolean) {
    val scope = rememberCoroutineScope()
    var diagnosing by remember { mutableStateOf(false) }
    var diagnostic by remember { mutableStateOf("") } // Never DataStore/Bundle.
    val noticesViewModel: NoticesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val noticesState by noticesViewModel.state.collectAsStateWithLifecycle()
    SectionCard(title = "版本与诊断") {
            Text("版本 " + BuildConfig.VERSION_NAME + " · " + BuildConfig.GIT_SHA, style = MaterialTheme.typography.bodySmall)
            val startupMetrics = cc.fplj.magpiemobile.core.StartupBenchmark.getMetrics()
            if (startupMetrics.totalToReadyMs >= 0) {
                Text(
                    "冷启动就绪耗时: " + startupMetrics.totalToReadyMs + "ms (" + (if (startupMetrics.isReadyWithinBudget) "达标 <800ms" else "超出预算") + ")",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (startupMetrics.isReadyWithinBudget) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.error
                )
            }
            val auditor = remember(layout) { cc.fplj.magpiemobile.core.PostMortemAuditor(layout.configDir) }
            var postMortem by remember(layout) { mutableStateOf(auditor.readReport()) }
            if (postMortem != null) {
                Text(
                    "异常退出审计记录: [" + postMortem!!.lastKnownPhase + "] 退出码=" + postMortem!!.exitCode,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                if (postMortem!!.sanitizedDetail.isNotEmpty()) {
                    Text(postMortem!!.sanitizedDetail.take(300), style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { auditor.clear(); postMortem = null }) {
                    Text("清除审计记录")
                }
            }
            Button(enabled = !diagnosing, onClick = {
                diagnosing = true
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            try {
                                val runner = cc.fplj.magpiemobile.core.EngineProcessRunner()
                                val magpie = runner.run(EnginePaths.magpieExecutable(layout).absolutePath,
                                    listOf("--version"), layout.homeDir, layout.cacheDir, layout.configDir)
                                val bun = runner.run(EnginePaths.bunExecutable(layout).absolutePath,
                                    listOf("--version"), layout.homeDir, layout.cacheDir, layout.configDir)
                                "magpie exit=" + magpie.exitCode + ": " + magpie.stdout.trim() + "\n" + magpie.stderr.take(1500) +
                                    "\nbun exit=" + bun.exitCode + ": " + bun.stdout.trim() + "\n" + bun.stderr.take(1500)
                            } catch (_: Exception) { "诊断未能完成，请检查载荷与运行状态。" }
                        }
                        diagnostic = SecretRedactor.redact(result).takeLast(8192)
                    } catch (e: CancellationException) { throw e }
                    finally { diagnosing = false }
                }
            }) { Text(if (diagnosing) "诊断中…" else "一键诊断（两引擎版本）") }
            if (diagnostic.isNotEmpty()) Text(diagnostic, style = MaterialTheme.typography.bodySmall)
    }
    SectionCard(title = "引擎状态与脱敏输出") {
            Text(gateway.phase.name + "\n" + gateway.message, style = MaterialTheme.typography.bodySmall)
            if (gateway.outputTail.isNotEmpty()) Text(gateway.outputTail, style = MaterialTheme.typography.bodySmall)
    }
    SectionCard(title = "配置安全备份与恢复（SAF · M4）") {
            Text("配置备份格式已实现；此页的文件导入导出入口仍待接入。请保留现有配置，暂勿依赖此页迁移数据。", style = MaterialTheme.typography.bodySmall)
            Text("格式版本：schemaVersion 1 · 安全上限：5MB", style = MaterialTheme.typography.labelSmall)
    }
    LicenseNoticesCard(
        clientReady = clientReady,
        viewModel = noticesViewModel,
        noticesState = noticesState,
    )
}

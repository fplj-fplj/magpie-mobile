package cc.fplj.magpiemobile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import cc.fplj.magpiemobile.core.GatewayEngineController
import cc.fplj.magpiemobile.theme.UiSpacing
import java.util.Locale

/** Compact update desk within More; the native core and Bun always travel inside the signed APK. */
@Composable
internal fun AppUpdateCard(
    viewModel: AppUpdateViewModel,
    appIdleSnapshot: Boolean,
    appIdle: () -> Boolean,
    onGateway: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gatewayStatus by GatewayRuntime.status.collectAsStateWithLifecycle()
    val gatewayStopping by GatewayRuntime.stopping.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val gatewaySettled = gatewayStatus.phase in setOf(
        GatewayEngineController.Phase.STOPPED,
        GatewayEngineController.Phase.FAILED,
    ) && !gatewayStopping
    val installAllowed = appIdleSnapshot && gatewaySettled && GatewayRuntime.canInstallUpdate() &&
        try { appIdle() } catch (_: Exception) { false }

    AppUpdateContent(
        state = state,
        installAllowed = installAllowed,
        needsInstallPermission = AppUpdateInstaller(context).needsInstallPermission(),
        onCheck = viewModel::check,
        onCancel = viewModel::cancel,
        onDownload = viewModel::download,
        onInstall = { viewModel.install(appIdle) },
        onGateway = onGateway,
        onOpenRelease = { viewModel.openLegacyRelease(context) },
    )
}

/** Activity-result registration stays in Dashboard when its More card leaves composition. */
@Composable
internal fun AppUpdateInstallHandler(
    viewModel: AppUpdateViewModel,
    enabled: Boolean,
    appIdle: () -> Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentAppIdle by rememberUpdatedState(appIdle)
    val currentEnabled by rememberUpdatedState(enabled)
    val installerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.installHandoffResult(true)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.installPermissionResult(!AppUpdateInstaller(context).needsInstallPermission())
    }
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.installRequests.collect { update ->
                try {
                    if (!currentEnabled || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                        !GatewayRuntime.canInstallUpdate() || !currentAppIdle()) {
                        throw AppUpdateRejected("请先停止网关并等待当前任务结束")
                    }
                    val installer = AppUpdateInstaller(context)
                    if (installer.needsInstallPermission()) {
                        permissionLauncher.launch(installer.unknownSourcesSettingsIntent())
                    } else {
                        installerLauncher.launch(installer.installIntent(update, currentAppIdle))
                    }
                } catch (_: Exception) {
                    viewModel.installHandoffResult(false)
                }
            }
        }
    }
}

/** Stateless update UI so phase, gate and navigation behavior can be exercised without network. */
@Composable
internal fun AppUpdateContent(
    state: AppUpdateUiState,
    installAllowed: Boolean,
    needsInstallPermission: Boolean,
    onCheck: () -> Unit,
    onCancel: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onGateway: () -> Unit,
    onOpenRelease: () -> Unit,
) {
    var notesExpanded by remember(state.release?.tag, state.legacyTag) { mutableStateOf(false) }
    SectionCard("应用更新", modifier = Modifier.testTag("app-update-card")) {
        Text("当前 ${state.currentVersionName} · ${state.currentVersionCode}", style = MaterialTheme.typography.bodyMedium)
        Text("内核 ${state.currentCoreVersion} · ${state.currentCommit}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        when (state.phase) {
            AppUpdatePhase.CHECKING -> {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("app-update-progress"))
                Text(state.message, style = MaterialTheme.typography.bodySmall)
                TextButtonRow("取消检查", "app-update-cancel", onCancel)
            }
            AppUpdatePhase.DOWNLOADING -> {
                val fraction = if (state.totalBytes > 0) (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().testTag("app-update-progress"),
                )
                Text("${state.downloadedBytes.humanBytes()} / ${state.totalBytes.humanBytes()}", style = MaterialTheme.typography.labelMedium)
                Text(state.message, style = MaterialTheme.typography.bodySmall)
                TextButtonRow("取消下载", "app-update-cancel", onCancel)
            }
            AppUpdatePhase.AVAILABLE, AppUpdatePhase.VERIFIED, AppUpdatePhase.INSTALLING -> {
                val release = state.release
                if (release != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "${release.versionName} · ${release.versionCode}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("${release.apkSize.humanBytes()} APK", style = MaterialTheme.typography.labelMedium)
                    }
                    Text("核心 ${release.coreVersion} · 上游 ${release.upstreamTag} · ${release.sourceCommit.take(8)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButtonRow(
                        action = if (notesExpanded) "收起发行说明" else "查看发行说明",
                        testTag = "app-update-notes",
                        onClick = { notesExpanded = !notesExpanded },
                    )
                    if (notesExpanded && release.notes.isNotBlank()) Text(
                        release.notes.take(4000), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(start = UiSpacing.small),
                    )
                    if (state.phase in setOf(AppUpdatePhase.VERIFIED, AppUpdatePhase.INSTALLING)) {
                        Text(state.message, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                        if (state.installBusy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("app-update-progress"))
                        Button(
                            enabled = installAllowed && !state.installBusy,
                            onClick = onInstall,
                            modifier = Modifier.fillMaxWidth().testTag("app-update-install"),
                        ) {
                            Text(if (state.installBusy) "等待 Android 安装确认…"
                                else if (needsInstallPermission) "允许安装并继续" else "交给系统安装器")
                        }
                        if (!installAllowed && !state.installBusy) {
                            Text("安装前需停止网关并等待进程清理，同时结束聊天、提供商和插件操作。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            OutlinedButton(
                                onClick = onGateway,
                                modifier = Modifier.testTag("app-update-gateway"),
                            ) { Text("前往网关状态") }
                        }
                        Text("Android 会显示最终确认；更新后请重新打开应用。", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Button(
                            enabled = !state.installBusy,
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth().testTag("app-update-download"),
                        ) { Text("下载并验证完整 APK") }
                    }
                }
            }
            AppUpdatePhase.CURRENT -> Text(state.message, style = MaterialTheme.typography.bodyMedium)
            AppUpdatePhase.LEGACY_RELEASE -> {
                Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("${state.legacyReleaseName} · ${state.legacyTag}", style = MaterialTheme.typography.labelMedium)
                if (state.legacyNotes.isNotBlank()) Text(state.legacyNotes.take(2000), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = onOpenRelease,
                    modifier = Modifier.testTag("app-update-open-release"),
                ) { Text("查看 GitHub Release") }
            }
            AppUpdatePhase.ERROR -> Unit
            AppUpdatePhase.IDLE -> Text("从官方 GitHub 稳定版检查更新；核心与运行时随签名 APK 一起升级。", style = MaterialTheme.typography.bodySmall)
        }

        if (state.error.isNotBlank()) Text(
            state.error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("app-update-error"),
        )
        if (state.message.isNotBlank() && state.phase !in setOf(AppUpdatePhase.CHECKING, AppUpdatePhase.DOWNLOADING,
                AppUpdatePhase.AVAILABLE, AppUpdatePhase.VERIFIED, AppUpdatePhase.INSTALLING, AppUpdatePhase.CURRENT, AppUpdatePhase.LEGACY_RELEASE)) {
            Text(state.message, style = MaterialTheme.typography.bodySmall)
        }
        if (state.phase !in setOf(AppUpdatePhase.CHECKING, AppUpdatePhase.DOWNLOADING)) {
            OutlinedButton(
                enabled = !state.installBusy,
                onClick = onCheck,
                modifier = Modifier.fillMaxWidth().testTag("app-update-check"),
            ) {
                Text(if (state.phase == AppUpdatePhase.IDLE || state.phase == AppUpdatePhase.ERROR) "检查 GitHub Release" else "重新检查")
            }
        }
    }
}

@Composable
private fun TextButtonRow(action: String, testTag: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
    ) { Text(action) }
}

private fun Long.humanBytes(): String {
    if (this < 1024) return "$this B"
    val unit = if (this < 1024L * 1024L) 1024f else 1024f * 1024f
    val suffix = if (unit == 1024f) "KiB" else "MiB"
    return String.format(Locale.ROOT, "%.1f %s", this / unit, suffix)
}

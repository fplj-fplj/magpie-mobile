package cc.fplj.magpiemobile

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import cc.fplj.magpiemobile.core.EnginePaths
import cc.fplj.magpiemobile.core.GatewayEngineController
import cc.fplj.magpiemobile.preferences.AppPage
import cc.fplj.magpiemobile.theme.UiSpacing

@Composable
fun Dashboard(
    layout: EnginePaths.Layout,
    appShell: AppShellViewModel,
    notificationNotice: String,
    onAllowNotifications: () -> Unit,
    onRetryCoreConnection: () -> Unit,
    onStartGateway: () -> Unit,
    onStopGateway: () -> Unit,
) {
    val context = LocalContext.current
    val compactHeight = LocalConfiguration.current.screenHeightDp < 480
    val scope = rememberCoroutineScope()
    val shell by appShell.state.collectAsStateWithLifecycle()
    val gateway by GatewayRuntime.status.collectAsStateWithLifecycle()
    val connection by NativeCoreSession.status.collectAsStateWithLifecycle()
    val stopping by GatewayRuntime.stopping.collectAsStateWithLifecycle()
    val chatViewModel: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val chat by chatViewModel.state.collectAsStateWithLifecycle()
    val keysViewModel: GatewayKeysViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val keysState by keysViewModel.state.collectAsStateWithLifecycle()
    val usageViewModel: UsageViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val usageState by usageViewModel.state.collectAsStateWithLifecycle()
    val routingViewModel: RoutingViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val routingState by routingViewModel.state.collectAsStateWithLifecycle()
    val keyPoolViewModel: KeyPoolViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val keyPoolState by keyPoolViewModel.state.collectAsStateWithLifecycle()
    val profileViewModel: ChatProfileViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val profileState by profileViewModel.state.collectAsStateWithLifecycle()
    val pluginImporter = remember(context) { PluginImporter(context.applicationContext) }
    val officialPlugins: PluginManagementViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = viewModelFactory {
        initializer { PluginManagementViewModel(savedState = createSavedStateHandle(),
            reconcileImports = { snapshot -> withContext(Dispatchers.IO) { pluginImporter.reconcile(snapshot) } },
            removeImport = { spec, snapshot -> withContext(Dispatchers.IO) { pluginImporter.removeIfUnreferenced(spec, snapshot) } },
            rejectImport = { spec -> withContext(Dispatchers.IO) { pluginImporter.markRejected(spec) } }) }
    })
    val pluginsState by officialPlugins.state.collectAsStateWithLifecycle()
    val stabilityViewModel: StabilityViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val stabilityState by stabilityViewModel.state.collectAsStateWithLifecycle()
    val externalEditor: ExternalEditorViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val externalState by externalEditor.state.collectAsStateWithLifecycle()
    val providerCache: ProviderListCacheViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, externalEditor, providerCache, appShell, officialPlugins) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && externalEditor.awaitingReturn) scope.launch {
                providerCache.invalidate()
                if (externalEditor.returned()) { officialPlugins.refresh(); appShell.refreshModels() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun openBrowserEditor() = externalEditor.open { link ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
    }

    SideEffect {
        officialPlugins.onChanged = {
            scope.launch { providerCache.invalidate(); appShell.refreshModels() }
        }
    }
    var providerBusy by remember { mutableStateOf(false) }
    var gatewaySectionName by rememberSaveable { mutableStateOf<String?>(null) }
    var moreSectionName by rememberSaveable { mutableStateOf<String?>(null) }
    var chatSettings by rememberSaveable { mutableStateOf(false) }
    val sectionName = when (shell.page) {
        AppPage.GATEWAY -> gatewaySectionName
        AppPage.MORE -> moreSectionName
        else -> null
    }
    val section = GatewaySection.entries.firstOrNull { it.name == sectionName }
    val pageStates = rememberSaveableStateHolder()
    val running = gateway.phase == GatewayEngineController.Phase.RUNNING
    val active = stopping || gateway.phase in setOf(GatewayEngineController.Phase.STARTING,
        GatewayEngineController.Phase.RUNNING, GatewayEngineController.Phase.BACKOFF)
    val clientReady = running && !stopping && connection.ready
    val owner = if (clientReady) NativeCoreSession.providerSessionId() else null
    LaunchedEffect(owner) { officialPlugins.attach(owner) }
    LaunchedEffect(section, owner) {
        if (section == GatewaySection.PLUGINS && owner != null && !officialPlugins.state.value.loading && !officialPlugins.state.value.busy) {
            officialPlugins.refresh()
        }
    }

    fun openSection(destination: GatewaySection) {
        if (shell.page == AppPage.MORE) moreSectionName = destination.name
        else gatewaySectionName = destination.name
    }
    fun back() {
        if (providerBusy) return
        when {
            shell.page == AppPage.GATEWAY && section != null -> gatewaySectionName = null
            shell.page == AppPage.MORE && section != null -> moreSectionName = null
            else -> {
                gatewaySectionName = null
                appShell.selectPage(AppPage.GATEWAY)
            }
        }
    }
    LaunchedEffect(clientReady, owner) {
        appShell.connectionChanged()
        if (clientReady) {
            keysViewModel.refresh()
            usageViewModel.refresh(5)
        } else usageViewModel.clear()
    }
    BackHandler(enabled = section != null || shell.page != AppPage.GATEWAY) { back() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = UiSpacing.medium, vertical = UiSpacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(UiSpacing.small),
                    ) {
                        if (section != null) IconButton(enabled = !providerBusy, onClick = { back() }) {
                            AppIcon(AppSymbol.BACK, description = "返回${pageTitle(shell.page)}")
                        } else AppIcon(AppSymbol.GATEWAY)
                        Column(Modifier.weight(1f)) {
                            Text(
                                section?.title ?: "Magpie Mobile",
                                style = if (section == null) MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = cc.fplj.magpiemobile.theme.BrandFont,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                ) else MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() },
                            )
                            if (section != null) Text(pageTitle(shell.page), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(
                            if (stopping) "停止中" else if (clientReady) "已连接" else gatewayLabel(gateway.phase),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (gateway.phase == GatewayEngineController.Phase.FAILED) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (chat.sending) Text(
                        "正在回复 · 可切换页面",
                        Modifier.padding(horizontal = UiSpacing.medium, vertical = UiSpacing.tiny),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                AppPage.entries.forEach { page ->
                    NavigationBarItem(
                        selected = shell.page == page,
                        enabled = !providerBusy,
                        onClick = {
                            if (!providerBusy) {
                                if (shell.page == page) {
                                    if (page == AppPage.GATEWAY) gatewaySectionName = null
                                    if (page == AppPage.MORE) moreSectionName = null
                                }
                                appShell.selectPage(page)
                            }
                        },
                        icon = { AppIcon(when (page) {
                            AppPage.GATEWAY -> AppSymbol.GATEWAY
                            AppPage.PROVIDERS -> AppSymbol.PROVIDERS
                            AppPage.CHAT -> AppSymbol.CHAT
                            AppPage.MORE -> AppSymbol.MORE
                        }) },
                        label = { Text(pageTitle(page)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            pageStates.SaveableStateProvider(shell.page.name + "/" + (section?.name ?: "HOME")) {
                Column(
                    Modifier.widthIn(max = UiSpacing.contentWidth).fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = UiSpacing.medium, vertical = if (compactHeight) UiSpacing.small else UiSpacing.card),
                    verticalArrangement = Arrangement.spacedBy(if (compactHeight) UiSpacing.small else UiSpacing.card),
                ) {
                    if (section != null) {
                        if (section != GatewaySection.PLUGINS) PageHeading(section.title, section.detail)
                        if (section == GatewaySection.ROUTING || section == GatewaySection.SYSTEM) {
                            EditorChoiceCard(clientReady && connection.webEditor && !providerBusy, externalState,
                                { appShell.selectPage(AppPage.PROVIDERS) }, ::openBrowserEditor, nativeSelected = false)
                        }
                        when (section) {
                            GatewaySection.CONNECTION -> {
                                SectionCard("核心连接") {
                                    Text(connection.message, style = MaterialTheme.typography.bodyMedium)
                                    if (running && !connection.ready && connection.phase != NativeCoreSession.Phase.CONNECTING)
                                        Button(onClick = onRetryCoreConnection) { Text("重新连接") }
                                }
                                GatewayEndpointCard(running && !stopping, clientReady,
                                    if (clientReady) NativeCoreSession.currentGatewayKey() else null, shell.selectedModel)
                            }
                            GatewaySection.KEYS -> GatewayKeysCard(clientReady, keysViewModel, keysState)
                            GatewaySection.USAGE -> {
                                RecentRequestsCard(clientReady, usageViewModel, usageState)
                                UsageInsightsCard(clientReady, usageViewModel, usageState)
                                ModelSpeedsCard(usageState)
                            }
                            GatewaySection.ROUTING -> {
                                ModelRoutingCard(clientReady, routingViewModel, routingState)
                                KeyPoolingCard(clientReady, keyPoolViewModel, keyPoolState)
                            }
                            GatewaySection.MODELS -> {
                                ModelCatalogCard(shell, clientReady, !chat.sending, appShell::refreshModels)
                                CatalogCard(usageState)
                            }
                            GatewaySection.PLUGINS -> {
                                PluginManagementPanel(officialPlugins, pluginsState, clientReady,
                                    ::openBrowserEditor)
                            }
                            GatewaySection.SYSTEM -> {
                                SystemStabilityCard(stabilityViewModel, stabilityState)
                                DiagnosticsPanel(layout, gateway, clientReady)
                            }
                        }
                    } else when (shell.page) {
                        AppPage.GATEWAY -> GatewayOverview(
                            gateway, stopping, active, clientReady,
                            if (clientReady && shell.catalogValid) shell.models.size else null,
                            stabilityState, notificationNotice, onAllowNotifications,
                            onStartGateway, onStopGateway, ::openSection,
                            { appShell.selectPage(AppPage.PROVIDERS) }, { appShell.selectPage(AppPage.CHAT) },
                        )
                        AppPage.PROVIDERS -> {
                            PageHeading("模型提供商", "管理连接与可用模型。")
                            EditorChoiceCard(clientReady && connection.webEditor && !providerBusy, externalState,
                                { appShell.selectPage(AppPage.PROVIDERS) }, ::openBrowserEditor)
                            if (!clientReady) {
                                SectionCard("先连接本机网关") {
                                    Text("启动网关并完成核心连接后，即可添加与编辑提供商。", style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = { appShell.selectPage(AppPage.GATEWAY) }) { Text("前往网关") }
                                }
                            }
                            ProvidersPanel(enabled = clientReady, operationBusy = chat.sending,
                                onBusyChanged = { providerBusy = it }, onProvidersChanged = appShell::refreshModels,
                                externalRevision = externalState.returnRevision,
                                onExternalEditor = if (clientReady && connection.webEditor) ::openBrowserEditor else null)
                        }
                        AppPage.CHAT -> {
                            PageHeading("从一个问题开始。", "选择模型，继续你的对话。", "CONVERSATIONS / 03")
                            TextButton(onClick = { chatSettings = !chatSettings }) {
                                AppIcon(AppSymbol.MORE)
                                Spacer(Modifier.width(UiSpacing.small))
                                Text(if (chatSettings) "收起模型与 Profile 配置" else "模型与 Profile 配置")
                            }
                            if (chatSettings) {
                                ModelCatalogCard(shell, clientReady, !chat.sending, appShell::refreshModels)
                                ChatProfileCard(profileViewModel, profileState)
                            }
                            ChatPanel(chatViewModel, shell.models.map { it.id },
                                shell.models.associate { it.id to (it.displayName?.takeIf(String::isNotBlank) ?: it.id) },
                                shell.selectedModel, appShell::selectModel, clientReady && shell.catalogValid, providerBusy)
                        }
                        AppPage.MORE -> {
                            PageHeading("按你的方式使用。", "模型、工具与本机设置，各有其位。", "WORKSPACE / 04")
                            EditorChoiceCard(clientReady && connection.webEditor && !providerBusy, externalState,
                                { appShell.selectPage(AppPage.PROVIDERS) }, ::openBrowserEditor, nativeSelected = false)
                            Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
                                listOf(GatewaySection.MODELS, GatewaySection.PLUGINS, GatewaySection.SYSTEM).forEach { destination ->
                                    DestinationRow(destination.title, destination.detail, destination.symbol, { openSection(destination) })
                                }
                            }
                            PreferencesCard(shell, appShell)
                        }
                    }
                }
            }
        }
    }
}

private fun pageTitle(page: AppPage): String = when (page) {
    AppPage.GATEWAY -> "网关"; AppPage.PROVIDERS -> "提供商"; AppPage.CHAT -> "聊天"; AppPage.MORE -> "更多"
}

internal fun gatewayLabel(phase: GatewayEngineController.Phase): String = when (phase) {
    GatewayEngineController.Phase.STOPPED -> "网关未运行"
    GatewayEngineController.Phase.STARTING -> "正在启动…"
    GatewayEngineController.Phase.RUNNING -> "运行中"
    GatewayEngineController.Phase.BACKOFF -> "正在恢复…"
    GatewayEngineController.Phase.FAILED -> "运行失败"
}

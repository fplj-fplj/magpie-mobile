package cc.fplj.magpiemobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.fplj.magpiemobile.core.*
import cc.fplj.magpiemobile.theme.UiSpacing
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun providerKeyFingerprint(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

/** Only nonsecret write-recovery flags are saveable. Password fields/drafts stay ephemeral. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ProvidersPanel(
    enabled: Boolean,
    operationBusy: Boolean = false,
    onBusyChanged: (Boolean) -> Unit = {},
    onProvidersChanged: () -> Unit,
    externalRevision: Int = 0,
    onExternalEditor: (() -> Unit)? = null,
    cacheViewModel: ProviderListCacheViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val scope = rememberCoroutineScope()
    val connection = LocalProviderPanelConnection.current
    val providerOperations = remember(scope, connection) {
        ProviderOperationController(scope, acquireLease = connection::acquireLease)
    }
    val sensitiveClipboard = rememberSensitiveClipboardManager()
    val focus = LocalFocusManager.current
    val latestEnabled by rememberUpdatedState(enabled)
    val latestExternalRevision by rememberUpdatedState(externalRevision)
    var providers by remember { mutableStateOf<ProviderListSnapshot?>(null) }
    var listHealthy by remember { mutableStateOf(false) }
    var externalRefresh by remember { mutableStateOf(ProviderExternalRefreshState()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var addSheet by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var modelPickerOpen by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<ProviderEditSnapshot?>(null) }
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var nameTouched by remember { mutableStateOf(false) }
    var idTouched by remember { mutableStateOf(false) }
    var localHttp by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptyList<String>()) }
    var modelOptions by remember { mutableStateOf(emptyList<ProviderModelSummary>()) }
    var policy by remember { mutableStateOf(ProviderEditorPolicy(true)) }
    var selectionMutation by remember { mutableStateOf(0) }
    val selectedModelEdit = remember(policy, selectionMutation, selected) { policy.editedModels() }
    var discoveryLoading by remember { mutableStateOf(false) }
    var discoveryDone by remember { mutableStateOf(false) }
    var discoveryError by remember { mutableStateOf("") }
    var catalogBaseAdopted by remember { mutableStateOf(false) }
    var retryCatalog by remember { mutableStateOf(0) }
    var operationSequence by remember { mutableStateOf(0L) }
    var catalogSequence by remember { mutableStateOf(0L) }
    var recovery by rememberSaveable(stateSaver = Saver<ProviderWriteRecoveryState, String>(
        save = { it.savedMarker() }, restore = { ProviderWriteRecoveryState.restore(it) },
    )) { mutableStateOf(ProviderWriteRecoveryState()) }
    val recoveryRequired = recovery.required
    val pendingProviderId = recovery.pendingProviderId.orEmpty()
    var confirmDisable by remember { mutableStateOf(false) }
    var serverPin by remember { mutableStateOf("") }
    var editorTab by rememberSaveable { mutableStateOf(0) }
    val editorScrolls = listOf(rememberScrollState(), rememberScrollState(), rememberScrollState())
    val providerStatus by connection.status.collectAsStateWithLifecycle()
    val sessionKey = if (enabled) providerStatus.providerSession else null
    val listReady = listHealthy && externalRefresh.isConfirmed(sessionKey, externalRevision)
    val canAddProvider = enabled && listReady && !busy && !operationBusy && !recoveryRequired

    // 导航守卫：busy、open (编辑Dialog打开) 或 recoveryRequired 时均锁住外层 Tab 切换
    val isLocked = busy || open || recoveryRequired
    LaunchedEffect(isLocked) {
        onBusyChanged(isLocked)
    }
    fun setBusy(value: Boolean) { busy = value }

    fun currentSession(): ProviderSessionKey? = if (latestEnabled) connection.currentSession() else null
    fun current(session: ProviderSessionKey?, sequence: Long): Boolean = session != null &&
        session == currentSession() && sequence == operationSequence
    fun listIsReady(): Boolean = listHealthy && externalRefresh.isConfirmed(currentSession(), latestExternalRevision)
    fun beginListRead(session: ProviderSessionKey, sequence: Long): ProviderExternalRefreshTicket? {
        if (!current(session, sequence)) return null
        val ticket = ProviderExternalRefreshTicket(session, latestExternalRevision)
        val started = externalRefresh.readStarted(ticket, currentSession(), latestExternalRevision)
        if (started.attempted != ticket) return null
        externalRefresh = started
        return ticket
    }
    fun listReadCurrent(ticket: ProviderExternalRefreshTicket, sequence: Long): Boolean =
        current(ticket.session, sequence) && ticket.isCurrent(currentSession(), latestExternalRevision)
    fun errorText(e: Exception): String = when (e) {
        is ProviderAlreadyExistsException -> "标识已被占用，请刷新列表并编辑已有提供商，或修改高级标识。"
        is ProviderRevisionConflictException -> "配置已变化，请重新打开编辑；没有覆盖新配置。"
        is ProviderConfigurationUnreadableException -> "核心配置文件异常，写入暂停；请保留文件并排查。"
        is ProviderUnsupportedException -> "此类型暂不支持基础编辑，原配置保持。"
        is ProviderEditPreconditionException -> "尚未修改字段，或编辑版本已过期。"
        is ManagementExecutionUnknown -> "保存结果待核对，请点击“核对保存结果”；不会自动再次提交。"
        is ManagementHttpException -> "请求失败：HTTP " + e.httpCode
        is IllegalArgumentException -> e.message ?: "请检查表单字段"
        else -> "操作失败，请重试读取；不会自动重复保存。"
    }
    fun close() {
        key = ""
        showKey = false
        modelPickerOpen = false
        catalogBaseAdopted = false
        open = false
        confirmDisable = false
    }
    fun retireRecoveredEditor() {
        // A list has no editable revision. Reopen from it instead of submitting the old draft.
        ++catalogSequence
        close()
        addSheet = false
        snapshot = null; id = ""; name = ""; baseUrl = ""; serverPin = ""
        nameTouched = false; idTouched = false; localHttp = false; manual = false
        selected = emptyList(); modelOptions = emptyList(); manualText = ""
        policy = ProviderEditorPolicy(true); selectionMutation++
        discoveryLoading = false; discoveryDone = false; discoveryError = ""; editorTab = 0
    }
    fun refresh(force: Boolean = false) {
        if (!latestEnabled || busy || sessionKey == null || sessionKey != connection.currentSession()) return
        val session = sessionKey; val sequence = ++operationSequence; val pendingAtStart = recovery
        listHealthy = false; setBusy(true)
        scope.launch {
            var readTicket: ProviderExternalRefreshTicket? = null
            try {
                val ticket = beginListRead(session, sequence) ?: return@launch
                readTicket = ticket
                val externalRead = externalRefresh.needsConfirmation(session, ticket.revision)
                // force=true alone may join an older in-flight read. Start a new generation.
                if (externalRead) cacheViewModel.invalidate()
                val fetch: suspend () -> ProviderListSnapshot = {
                    withProviderRequest(session, acquireLease = connection::acquireLease) { it.getProviders() }
                }
                val result = pendingAtStart.read(cacheViewModel, session, sequence, force || externalRead, fetch)
                if (!listReadCurrent(ticket, sequence)) return@launch
                val resolved = result.resolve(
                    currentSession = currentSession(),
                    currentSequence = operationSequence,
                    currentRecovery = recovery,
                    editorOpen = open,
                ) ?: return@launch
                providers = result.snapshot; listHealthy = !result.snapshot.hasFileError
                if (listHealthy) {
                    externalRefresh = externalRefresh.readConfirmed(ticket, currentSession(), latestExternalRevision)
                }
                recovery = resolved.recovery
                if (resolved.recoveryChecked) {
                    if (resolved.retireEditor) retireRecoveredEditor()
                    message = "已核对保存结果，请从当前列表重新打开提供商。"
                    onProvidersChanged()
                } else {
                    message = if (listHealthy) "" else "提供商文件异常，写入暂停"
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (readTicket?.let { listReadCurrent(it, sequence) } == true) {
                    listHealthy = false; message = "列表读取失败：" + errorText(e)
                }
            }
            finally { if (sequence == operationSequence) setBusy(false) }
        }
    }
    fun newProvider(presetId: String = "", presetName: String = "", url: String = "") {
        if (!listIsReady() || recoveryRequired || busy || operationBusy) return
        snapshot = null; id = presetId; name = presetName; baseUrl = url; key = ""; serverPin = ""
        idTouched = false; nameTouched = false; localHttp = false; manual = false; showKey = false
        selected = emptyList(); modelOptions = emptyList(); policy = ProviderEditorPolicy(true)
        discoveryDone = false; discoveryError = ""; message = ""; manualText = ""; editorTab = 0
        modelPickerOpen = false; catalogBaseAdopted = false
        open = true
    }
    LaunchedEffect(enabled, sessionKey) {
        // A new session must be allowed to load even while an invalidated old worker unwinds.
        ++operationSequence
        listHealthy = false
        setBusy(false)
        if (!enabled || sessionKey == null) { key = ""; discoveryLoading = false; message = "核心已断开，草稿名称、地址与选择已保留；重连后请补充密钥" }
        else refresh(force = false)
    }
    LaunchedEffect(enabled, sessionKey, externalRevision, open, busy) {
        if (enabled && sessionKey != null && externalRefresh.needsConfirmation(sessionKey, externalRevision)) {
            listHealthy = false
            if (open && !busy && !recoveryRequired && externalRefresh.needsAttempt(sessionKey, externalRevision)) {
                message = "外部配置已变化；当前草稿已保留，关闭编辑后将刷新提供商列表。"
            }
        }
        if (externalRefresh.automaticTicket(enabled, sessionKey, externalRevision, open, busy) != null) {
            refresh(force = true)
        }
    }
    val normalized = try { ProviderBaseUrl.normalize(baseUrl) } catch (_: Exception) { "" }
    val newOriginNeedsKey = snapshot != null && key.isBlank() && normalized.isNotEmpty() && run {
        val old = java.net.URI(snapshot!!.chatUrl); val next = java.net.URI(normalized)
        fun port(uri: java.net.URI): Int = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
        old.scheme != next.scheme || old.host != next.host || port(old) != port(next)
    }
    val keyFingerprint = remember(key) { providerKeyFingerprint(key) }
    val normalizedServerPin = ProviderCatalogSyncPolicy.normalizedServerPin(serverPin)
    val serverPinError = ProviderCatalogSyncPolicy.serverPinError(baseUrl, serverPin)
    val modelPickerEntries = remember(modelOptions, selected, busy, name) {
        (modelOptions.map { it.id } + selected)
            .distinct()
            .map { modelId ->
                val summary = modelOptions.firstOrNull { it.id == modelId }
                ModelPickerEntry(
                    id = modelId,
                    displayName = summary?.name?.ifBlank { modelId } ?: modelId,
                    providerName = name,
                    detail = if (summary == null) "当前目录未返回，仍保留已保存选择" else modelId,
                    enabled = !busy,
                )
            }
    }
    fun applyModelSelection(nextIds: Set<String>) {
        val current = selected.toSet()
        (current - nextIds).forEach { policy.toggle(it, false) }
        (nextIds - current).forEach { policy.toggle(it, true) }
        selected = modelPickerEntries.map { it.id }.filter { it in nextIds } +
            nextIds.filter { requested -> modelPickerEntries.none { it.id == requested } }
        selectionMutation++
        manualText = selected.joinToString("\n")
    }
    LaunchedEffect(open, normalized, keyFingerprint, localHttp, normalizedServerPin, sessionKey, snapshot?.revision, retryCatalog) {
        val sequence = ++catalogSequence
        discoveryLoading = false; discoveryDone = false; discoveryError = serverPinError.orEmpty()
        if (!open || !enabled || sessionKey == null || normalized.isEmpty() || newOriginNeedsKey ||
            normalizedServerPin == null || serverPinError != null
        ) return@LaunchedEffect
        if (snapshot == null) {
            if (!nameTouched) name = ProviderBaseUrl.suggestName(baseUrl)
            if (!idTouched) {
                val base = ProviderBaseUrl.suggestId(baseUrl)
                val used = providers?.providers.orEmpty().map { it.id }.toSet()
                var suggestion = base; var number = 2
                while (suggestion in used) { suggestion = base.take(57) + "-" + number++ }
                id = suggestion
            }
        }
        val requestedCanonicalBaseUrl = normalized
        val requestedKeyFingerprint = keyFingerprint
        val requestedLocalHttp = localHttp
        val requestedServerPin = normalizedServerPin
        val requestedProviderId = snapshot?.id
        val requestedRevision = snapshot?.revision
        val requestedBaseForm = baseUrl
        val requestedKey = key
        fun requestIsCurrent(): Boolean = ProviderCatalogSyncPolicy.requestIsCurrent(
            requestedSequence = sequence,
            currentSequence = catalogSequence,
            requestedCanonicalBaseUrl = requestedCanonicalBaseUrl,
            currentBaseUrl = baseUrl,
            requestedKeyFingerprint = requestedKeyFingerprint,
            currentKeyFingerprint = providerKeyFingerprint(key),
            requestedLocalHttp = requestedLocalHttp,
            currentLocalHttp = localHttp,
            requestedServerPin = requestedServerPin,
            currentServerPin = serverPin,
            requestedProviderId = requestedProviderId,
            currentProviderId = snapshot?.id,
            requestedRevision = requestedRevision,
            currentRevision = snapshot?.revision,
            editorOpen = open,
            sessionCurrent = latestEnabled && sessionKey == connection.currentSession(),
        )
        discoveryLoading = true
        try {
            delay(1000) // settle URL/credential/pin changes before checking the current draft
            if (!requestIsCurrent()) return@LaunchedEffect
            val catalog = withProviderRequest(sessionKey, acquireLease = connection::acquireLease) { client ->
                // The IO worker may have waited while an input callback fenced this draft.
                if (!requestIsCurrent()) throw CancellationException("Model directory draft changed")
                val activeRequest = ProviderModelDiscoveryRequest(
                    requestedBaseForm, requestedKey, requestedProviderId, requestedRevision, requestedLocalHttp,
                    serverPin = requestedServerPin,
                )
                try { client.discoverModels(activeRequest) } finally { activeRequest.clearSecret() }
            }
            val requestStillCurrent = requestIsCurrent()
            if (requestStillCurrent) {
                ProviderCatalogSyncPolicy.adoptedBaseUrl(
                    requestedCanonicalBaseUrl = requestedCanonicalBaseUrl,
                    currentBaseUrl = baseUrl,
                    returnedBaseUrl = catalog.baseUrl,
                    requestStillCurrent = true,
                    alreadyAdopted = catalogBaseAdopted,
                )?.let { adopted ->
                    catalogBaseAdopted = true
                    baseUrl = adopted
                }
                modelOptions = catalog.models; selected = policy.applyCatalog(catalog.models.map { it.id })
                manualText = selected.joinToString("\n"); discoveryDone = true
                if (catalog.models.isEmpty()) discoveryError = "目录为空；可检查凭据权限，或打开高级设置补充模型ID。"
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (requestIsCurrent()) discoveryError = if (e is ProviderModelDiscoveryException) e.message ?: "无法读取模型目录" else "目录读取失败，请重试读取；没有修改配置。"
        } finally { if (sequence == catalogSequence) discoveryLoading = false }
    }
    fun submit() {
        if (busy || operationBusy || recoveryRequired || !listIsReady() || !latestEnabled || sessionKey == null) return
        val session = sessionKey; val sequence = ++operationSequence
        val initial = snapshot; val inputKey = key; val inputName = name; val inputId = id
        val inputUrl = baseUrl; val inputModels = selected; val editedModels = policy.editedModels(); val allow = localHttp; val inputPin = serverPin
        setBusy(true)
        try {
            providerOperations.launch(session, onFinished = {
                if (sequence == operationSequence) setBusy(false)
            }) {
                var confirmed = false
                try {
                    // Validate locally before any network call. A typo does not erase the password field.
                    // Mutable one-shot drafts only exist in a worker block and are always cleared.
                    request {
                        if (initial == null) {
                            val draft = ProviderCreateDraft(inputId, inputName, inputUrl, inputKey, inputModels, allow, inputPin)
                            try { draft.validate() } finally { draft.clearSecret() }
                        } else {
                            val draft = ProviderEditDraft(initial, inputName, inputUrl, inputKey, editedModels, allow, inputPin)
                            try { draft.validate() } finally { draft.clearSecret() }
                        }
                    }
                    if (!current(session, sequence)) return@launch
                    write(
                        cache = cacheViewModel,
                        session = session,
                        onPending = {
                            if (!current(session, sequence)) throw CancellationException("Provider session changed")
                            recovery = ProviderWriteRecoveryState(inputId); key = ""
                        },
                    ) { client ->
                        if (initial == null) {
                            val draft = ProviderCreateDraft(inputId, inputName, inputUrl, inputKey, inputModels, allow, inputPin)
                            try { client.create(draft) } finally { draft.clearSecret() }
                        } else {
                            val draft = ProviderEditDraft(initial, inputName, inputUrl, inputKey, editedModels, allow, inputPin)
                            try { client.edit(draft) } finally { draft.clearSecret() }
                        }
                    }
                    confirmed = true
                    if (current(session, sequence)) {
                        recovery = ProviderWriteRecoveryState(); close(); onProvidersChanged()
                        message = "已保存；正在自动刷新目录，尚未验证真实推理"
                        val ticket = beginListRead(session, sequence) ?: return@launch
                        listHealthy = false
                        val latest = cacheViewModel.readForRecovery(session) {
                            withProviderRequest(session, acquireLease = connection::acquireLease) { it.getProviders() }
                        }
                        if (listReadCurrent(ticket, sequence)) {
                            providers = latest; listHealthy = !latest.hasFileError; message = "配置已保存"
                            if (listHealthy) {
                                externalRefresh = externalRefresh.readConfirmed(ticket, currentSession(), latestExternalRevision)
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e } // pending nonsecret marker survives cancellation/recreation
                catch (e: Exception) {
                    if (current(session, sequence)) {
                        if (confirmed) { recovery = ProviderWriteRecoveryState(); listHealthy = false; message = "保存已确认，但刷新失败；请重新读取，勿再保存。" }
                        else { if (e !is ManagementExecutionUnknown) recovery = ProviderWriteRecoveryState(); message = errorText(e) }
                    }
                }
            }
        } catch (_: CancellationException) {
            setBusy(false)
            message = "核心连接已变化，请重新读取提供商后再保存。"
        } catch (e: Exception) {
            setBusy(false)
            message = errorText(e)
        }
    }
    fun openProvider(provider: ProviderSummary) {
        if (!listIsReady() || busy || operationBusy || recoveryRequired) return
        if (!provider.editable) { onExternalEditor?.invoke(); return }
        val session = sessionKey ?: return; val sequence = ++operationSequence; setBusy(true)
        try {
            providerOperations.launch(session, onFinished = {
                if (sequence == operationSequence) setBusy(false)
            }) {
                try {
                    val edit = request { it.editable(provider.id) }
                    if (current(session, sequence)) {
                        val shownBase = runCatching { ProviderBaseUrl.storedBaseFormValue(edit.chatUrl) }.getOrDefault(edit.chatUrl)
                        snapshot = edit; id = edit.id; name = edit.name; baseUrl = shownBase; key = ""; serverPin = edit.serverPin
                        nameTouched = true; idTouched = true; localHttp = edit.chatUrl.startsWith("http://")
                        manual = false; editorTab = 0; showKey = false
                        policy = ProviderEditorPolicy(false, edit.modelIds, edit.noModels); selected = edit.modelIds
                        modelOptions = provider.models; manualText = selected.joinToString("\n"); message = ""
                        modelPickerOpen = false
                        catalogBaseAdopted = false
                        open = true
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (current(session, sequence)) message = errorText(e) }
            }
        } catch (_: CancellationException) {
            setBusy(false)
            message = "核心连接已变化，请重新读取提供商。"
        } catch (e: Exception) {
            setBusy(false)
            message = errorText(e)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.medium)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("你的提供商", style = MaterialTheme.typography.titleLarge)
                Text(
                    "管理连接、凭据与可用模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.small)) {
                TextButton(
                    enabled = enabled && !busy,
                    onClick = { refresh(force = true) },
                ) {
                    Text(if (recoveryRequired) "核对结果" else "刷新")
                }
                Button(
                    enabled = canAddProvider,
                    onClick = { addSheet = true },
                ) {
                    AppIcon(AppSymbol.ADD)
                    Spacer(Modifier.width(UiSpacing.small))
                    Text("添加")
                }
            }
        }
        if (message.isNotEmpty()) {
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
        if (recoveryRequired) {
            Text(
                "有待核对的保存结果（$pendingProviderId），核对后可继续编辑。",
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            singleLine = true,
            shape = CircleShape,
            placeholder = { Text("搜索提供商") },
            leadingIcon = { AppIcon(AppSymbol.SEARCH, description = null) },
            trailingIcon = {
                if (search.isNotEmpty()) {
                    TextButton(onClick = { search = "" }) { Text("清除") }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        val all = providers?.providers.orEmpty()
        val matches = all.filter { it.name.contains(search, true) || it.id.contains(search, true) }
        if (listReady && all.isEmpty()) {
            ProviderFormGroup(
                title = "还没有提供商",
                supporting = "选择 OpenAI、DeepSeek、OpenRouter，或添加自定义兼容连接。",
            ) {
                Button(
                    enabled = canAddProvider,
                    onClick = { addSheet = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AppIcon(AppSymbol.ADD)
                    Spacer(Modifier.width(UiSpacing.small))
                    Text("添加第一个提供商")
                }
            }
        } else if (matches.isEmpty() && search.isNotEmpty()) {
            ProviderFormGroup(title = "没有匹配项") {
                Text(
                    "换一个名称或标识试试。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { search = "" }) { Text("清除搜索") }
            }
        }
        matches.take(50).forEach { provider ->
            ProviderRow(
                provider = provider,
                enabled = enabled && !busy && !operationBusy && listReady &&
                    !recoveryRequired && (provider.editable || onExternalEditor != null),
                onClick = { openProvider(provider) },
            )
        }
        if (matches.size > 50) {
            Text(
                "显示前 50 个连接，请搜索定位其他提供商。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    ProviderAddSheet(
        visible = addSheet,
        onDismiss = { addSheet = false },
        onSelect = { template ->
            addSheet = false
            newProvider(template.id, template.name, template.baseUrl)
        },
    )
    if (open) FullScreenDialog(onDismissRequest = { if (!busy) close() }) {
        Surface(
            Modifier
                .fillMaxSize()
                .testTag("provider-editor-surface")
                .semantics { testTagsAsResourceId = true },
        ) {
            Column(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                        ),
                    )
                    .padding(horizontal = UiSpacing.medium),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(enabled = !busy, onClick = ::close) { AppIcon(AppSymbol.BACK, description = "返回提供商列表") }
                    ProviderAvatar(
                        name = name.ifBlank {
                            providerAddTemplates.firstOrNull { it.id == id }?.name ?: "新提供商"
                        },
                        size = 40.dp,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            name.ifBlank { "添加提供商" },
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (snapshot == null) "新建 OpenAI 兼容连接" else "编辑提供商配置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ProviderEditorTabs(selected = editorTab, onSelect = { editorTab = it })
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(editorScrolls[editorTab])
                        .padding(top = UiSpacing.medium, bottom = UiSpacing.small),
                    verticalArrangement = Arrangement.spacedBy(UiSpacing.medium),
                ) {
                    when (editorTab) {
                        0 -> {
                            ProviderFormGroup(
                                title = "连接信息",
                                supporting = "平台会在此地址后追加 OpenAI Chat Completions 路径。",
                            ) {
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it; nameTouched = true },
                                    enabled = !busy,
                                    singleLine = true,
                                    label = { Text("提供商名称") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("provider-name")
                                        .semantics { testTagsAsResourceId = true },
                                )
                                OutlinedTextField(
                                    value = baseUrl,
                                    onValueChange = {
                                        baseUrl = it
                                        catalogBaseAdopted = false
                                    },
                                    enabled = !busy,
                                    singleLine = true,
                                    label = { Text("API 地址") },
                                    placeholder = { Text("https://api.example.com/v1") },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Next,
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                when {
                                    normalized.isNotEmpty() -> Text(
                                        "实际地址：$normalized",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    baseUrl.isNotBlank() -> Text(
                                        "请检查 HTTPS、端口和路径；地址不能包含密钥。",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            ProviderFormGroup(
                                title = "访问凭据",
                                supporting = "密钥只保存在本机核心私有目录，不进入列表、日志或导出备份。",
                            ) {
                                CompositionLocalProvider(LocalClipboardManager provides sensitiveClipboard) {
                                    OutlinedTextField(
                                        value = key,
                                        onValueChange = { key = it },
                                        enabled = !busy,
                                        singleLine = true,
                                        label = { Text(if (snapshot == null) "API 密钥" else "新密钥（留空保留现有密钥）") },
                                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                                        trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") } },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                                        modifier = Modifier.fillMaxWidth().testTag("provider-api-key"),
                                    )
                                }
                                if (newOriginNeedsKey) {
                                    Text(
                                        "服务来源已改变，请填写新密钥。",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            ProviderFormGroup(
                                title = "模型目录",
                                supporting = if (discoveryLoading) "正在读取提供商返回的模型目录…"
                                else if (discoveryError.isNotEmpty()) discoveryError
                                else "当前草稿选择 ${selected.size} 个模型，保存后生效。",
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = MaterialTheme.shapes.large,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(UiSpacing.medium),
                                        horizontalArrangement = Arrangement.spacedBy(UiSpacing.medium),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        AppIcon(AppSymbol.MODELS)
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "将启用 ${selected.size} 个模型",
                                                style = MaterialTheme.typography.titleSmall,
                                            )
                                            Text(
                                                if (modelOptions.isEmpty()) "等待目录或手动添加"
                                                else "${modelOptions.size} 个目录模型可用",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        TextButton(onClick = { editorTab = 1 }) { Text("选择模型") }
                                    }
                                }
                            }
                        }
                        1 -> {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("选择模型", style = MaterialTheme.typography.headlineSmall)
                                    Text(
                                        "将启用 ${selected.size} · ${modelOptions.size} 可发现",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    enabled = !busy && !discoveryLoading,
                                    onClick = { focus.clearFocus(); retryCatalog++ },
                                ) { Text("刷新目录") }
                            }
                            ModelSelectionField(
                                displayName = when {
                                    selected.isEmpty() -> "选择要启用的模型"
                                    selected.size == 1 -> selected.single()
                                    else -> "当前选择 ${selected.size} 个模型"
                                },
                                enabled = !busy,
                                onClick = { modelPickerOpen = true },
                                supportingText = if (discoveryLoading) "正在刷新目录"
                                else "打开模型目录，搜索并多选",
                                modifier = Modifier
                                    .testTag("provider-model-selector")
                                    .semantics { testTagsAsResourceId = true },
                            )
                            ProviderFormGroup(
                                title = "当前选择的模型",
                                supporting = when {
                                    discoveryLoading -> "正在读取提供商返回的模型目录…"
                                    discoveryError.isNotEmpty() -> discoveryError
                                    selected.isEmpty() -> "当前草稿未选择模型。"
                                    else -> "点选卡片调整模型；保存后生效。"
                                },
                            ) {
                                if (selected.isEmpty()) {
                                    Button(
                                        onClick = { modelPickerOpen = true },
                                        enabled = !busy && modelPickerEntries.isNotEmpty(),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        AppIcon(AppSymbol.ADD)
                                        Spacer(Modifier.width(UiSpacing.small))
                                        Text("打开模型目录")
                                    }
                                } else {
                                    selected.sorted().forEach { model ->
                                        val summary = modelOptions.firstOrNull { it.id == model }
                                        ProviderModelChoiceRow(
                                            modelId = model,
                                            displayName = summary?.name?.ifBlank { model } ?: model,
                                            available = summary != null,
                                            selected = true,
                                            enabled = !busy,
                                            onToggle = { checked ->
                                                applyModelSelection(
                                                    if (checked) selected.toSet() + model
                                                    else selected.toSet() - model,
                                                )
                                            },
                                        )
                                    }
                                }
                                if (modelPickerEntries.isEmpty() && !discoveryLoading) {
                                    Text(
                                        "检查连接信息并刷新目录，或在“高级”页手动补充模型 ID。",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        2 -> {
                            ProviderFormGroup(
                                title = "连接行为",
                                supporting = "这些设置仅用于当前提供商，保存后会参与真实推理。",
                            ) {
                                OutlinedTextField(
                                    value = id,
                                    onValueChange = { id = it; idTouched = true },
                                    enabled = snapshot == null && !busy,
                                    singleLine = true,
                                    label = { Text("内部标识") },
                                    supportingText = { Text("创建后不可修改，可与其他提供商区分。") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                ProviderSwitchRow(
                                    checked = localHttp,
                                    onCheckedChange = { localHttp = it },
                                    enabled = !busy,
                                    title = "允许本机 HTTP",
                                    description = "仅用于 127.0.0.1 或 [::1] 的本地服务。",
                                )
                                ProviderSwitchRow(
                                    checked = manual,
                                    onCheckedChange = { manual = it },
                                    enabled = !busy,
                                    title = "手动补充模型 ID",
                                    description = "当提供商没有标准模型目录时使用。",
                                )
                                if (manual) {
                                    OutlinedTextField(
                                        value = manualText,
                                        onValueChange = {
                                            if (!busy) {
                                                manualText = it
                                                selected = policy.manual(
                                                    it.split(',', '\n')
                                                        .map(String::trim)
                                                        .filter(String::isNotEmpty),
                                                )
                                                selectionMutation++
                                            }
                                        },
                                        enabled = !busy,
                                        minLines = 3,
                                        label = { Text("模型 ID，每行一个") },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("provider-manual-models")
                                            .semantics { testTagsAsResourceId = true },
                                    )
                                }
                            }
                            ProviderFormGroup(
                                title = "传输安全",
                                supporting = "证书指纹用于固定自定义服务的 TLS 证书。",
                            ) {
                                OutlinedTextField(
                                    value = serverPin,
                                    onValueChange = { nextPin ->
                                        if (!busy && serverPin != nextPin) {
                                            // Fence callbacks now, before LaunchedEffect can recompose/cancel.
                                            ++catalogSequence
                                            discoveryLoading = false; discoveryDone = false; discoveryError = ""
                                            serverPin = nextPin
                                            // Also restart after equivalent or changed-then-restored pin edits.
                                            retryCatalog++
                                        }
                                    },
                                    enabled = !busy,
                                    singleLine = true,
                                    label = { Text("服务器证书 SHA-256 指纹") },
                                    isError = serverPinError != null,
                                    supportingText = { Text(serverPinError ?: "可选 · 64 位小写十六进制；留空使用系统 TLS 证书校验。") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("provider-server-pin")
                                        .semantics { testTagsAsResourceId = true },
                                )
                            }
                        }
                    }
                    if (message.isNotEmpty()) {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .testTag("provider-editor-footer")
                        .semantics { testTagsAsResourceId = true }
                        .windowInsetsPadding(
                            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                                .union(WindowInsets.ime.only(WindowInsetsSides.Bottom)),
                        )
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(UiSpacing.small),
                ) {
                    Text(
                        when {
                            busy -> "正在处理…"
                            recoveryRequired -> "等待核对保存结果"
                            snapshot == null -> "保存后可测试；未保存内容不会发送到上游"
                            else -> "连通测试只使用已保存的配置和模型"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(UiSpacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProviderConnectivityTestButton(
                            enabled = enabled && !operationBusy && !busy && listReady && !recoveryRequired,
                            owner = sessionKey?.owner,
                            providerId = snapshot?.id,
                            providerName = name.ifBlank { "提供商" },
                            testModels = savedEnabledProviderModelIds(
                                providers?.providers?.firstOrNull { it.id == snapshot?.id },
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            enabled = enabled && !operationBusy && !busy && listReady &&
                                !recoveryRequired && normalized.isNotEmpty() &&
                                !newOriginNeedsKey && !discoveryLoading &&
                                (snapshot != null || key.isNotBlank()) &&
                                (selected.isNotEmpty() || selectedModelEdit?.isEmpty() == true ||
                                    (snapshot != null && selectedModelEdit == null)) &&
                                (snapshot != null || discoveryDone || manual),
                            onClick = {
                                focus.clearFocus()
                                if (selected.isEmpty() && selectedModelEdit?.isEmpty() == true) {
                                    confirmDisable = true
                                } else {
                                    submit()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("provider-save")
                                .semantics { testTagsAsResourceId = true },
                        ) {
                            Text(if (busy) "保存中…" else "保存连接")
                        }
                    }
                }
            }
        }
    }
    ModelSelectionSheet(
        visible = modelPickerOpen,
        groups = listOf(
            ModelPickerGroup(
                id = "provider-${id.ifBlank { "new" }}",
                title = name.ifBlank { "当前提供商" },
                entries = modelPickerEntries,
            ),
        ),
        selectedIds = selected.toSet(),
        onDismissRequest = { modelPickerOpen = false },
        onSelectionChange = ::applyModelSelection,
        title = "选择提供商模型",
        confirmLabel = "应用",
    )
    if (confirmDisable) AlertDialog(onDismissRequest = { confirmDisable = false }, title = { Text("禁用全部模型？") }, text = { Text("此操作会明确禁用该提供商的全部模型，不是获取目录失败。") },
        confirmButton = { TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = { confirmDisable = false; submit() }) { Text("确认禁用并保存") } }, dismissButton = { TextButton(onClick = { confirmDisable = false }) { Text("返回选择") } })
}

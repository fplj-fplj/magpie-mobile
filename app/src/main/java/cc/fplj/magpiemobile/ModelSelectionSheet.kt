package cc.fplj.magpiemobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.fplj.magpiemobile.core.ModelEntry
import cc.fplj.magpiemobile.theme.UiSpacing
import kotlinx.coroutines.launch

/** One selectable model in the RikkaHub-style shared selection sheet. */
data class ModelPickerEntry(
    val id: String,
    val displayName: String,
    val providerName: String = "",
    val detail: String = "",
    val enabled: Boolean = true,
)

/** Provider or catalog section shown as a heading in the model sheet. */
data class ModelPickerGroup(
    val id: String,
    val title: String,
    val entries: List<ModelPickerEntry>,
)

/**
 * Builds provider headings only from catalog-owned metadata.
 *
 * `owned_by` may be blank or use Magpie's generic "magpie" owner. Those
 * entries share the gateway heading; names are never inferred from model IDs.
 */
internal fun modelPickerGroupsFromCatalog(models: List<ModelEntry>): List<ModelPickerGroup> {
    val grouped = linkedMapOf<String, Pair<String, MutableList<ModelPickerEntry>>>()
    models.forEach { model ->
        val owner = model.ownedBy?.trim().orEmpty()
        val hasNamedOwner = owner.isNotBlank() && !owner.equals("magpie", ignoreCase = true)
        val key = if (hasNamedOwner) owner.lowercase() else "magpie"
        val title = if (hasNamedOwner) owner else "Magpie 网关"
        val entry = ModelPickerEntry(
            id = model.id,
            displayName = model.displayName?.trim()?.takeIf { it.isNotBlank() } ?: model.id,
            providerName = title,
            detail = model.contextWindow?.let { "${formatContextWindow(it)} 上下文" }.orEmpty(),
        )
        grouped.getOrPut(key) { title to mutableListOf() }.second += entry
    }
    return grouped.map { (key, value) ->
        ModelPickerGroup(
            id = "provider-" + key.replace(Regex("[^a-z0-9._-]"), "-"),
            title = value.first,
            entries = value.second,
        )
    }
}

private fun formatContextWindow(tokens: Long): String = when {
    tokens >= 1_000_000 && tokens % 1_000_000 == 0L -> "${tokens / 1_000_000}M"
    tokens >= 1_000 && tokens % 1_000 == 0L -> "${tokens / 1_000}K"
    else -> tokens.toString()
}

private data class ModelSheetRow(
    val group: ModelPickerGroup,
    val entry: ModelPickerEntry? = null,
)

/**
 * Shared model selection surface used by chat and provider editing.
 *
 * Single-select closes after a tap. Multi-select keeps the sheet open and
 * applies changes immediately; the fixed footer confirms the result.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalComposeUiApi::class,
)
@Composable
internal fun ModelSelectionSheet(
    visible: Boolean,
    groups: List<ModelPickerGroup>,
    selectedIds: Set<String>,
    onDismissRequest: () -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    title: String = "选择模型",
    singleSelect: Boolean = false,
    confirmLabel: String = "完成",
) {
    if (!visible) return

    var query by remember { mutableStateOf("") }
    var collapsedGroupIds by remember { mutableStateOf(emptySet<String>()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val normalizedQuery = query.trim()
    val filteredGroups = remember(groups, normalizedQuery) {
        groups.mapNotNull { group ->
            val matches = if (normalizedQuery.isBlank()) {
                group.entries
            } else {
                group.entries.filter { entry ->
                    entry.displayName.contains(normalizedQuery, ignoreCase = true) ||
                        entry.id.contains(normalizedQuery, ignoreCase = true) ||
                        entry.providerName.contains(normalizedQuery, ignoreCase = true) ||
                        group.title.contains(normalizedQuery, ignoreCase = true)
                }
            }
            group.copy(entries = matches).takeIf { it.entries.isNotEmpty() }
        }
    }

    val rows = remember(filteredGroups, collapsedGroupIds, normalizedQuery) {
        buildList {
            filteredGroups.forEach { group ->
                add(ModelSheetRow(group))
                if (normalizedQuery.isNotBlank() || group.id !in collapsedGroupIds) {
                    group.entries.forEach { add(ModelSheetRow(group, it)) }
                }
            }
        }
    }
    val groupPositions = remember(rows) {
        buildMap {
            rows.forEachIndexed { index, row ->
                if (row.entry == null) put(row.group.id, index)
            }
        }
    }

    LaunchedEffect(visible, normalizedQuery, rows.size) {
        if (normalizedQuery.isNotBlank() || selectedIds.isEmpty()) return@LaunchedEffect
        val selectedIndex = rows.indexOfFirst { it.entry?.id in selectedIds }
        if (selectedIndex >= 0) listState.scrollToItem(selectedIndex)
    }

    fun choose(entry: ModelPickerEntry) {
        if (!entry.enabled) return
        if (singleSelect) {
            onSelectionChange(setOf(entry.id))
            onDismissRequest()
        } else {
            onSelectionChange(
                if (entry.id in selectedIds) selectedIds - entry.id else selectedIds + entry.id,
            )
        }
    }

    val visibleModelCount = filteredGroups.sumOf { it.entries.size }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .imePadding()
                .padding(horizontal = UiSpacing.medium)
                .testTag("model-selection-sheet")
                .semantics { testTagsAsResourceId = true },
            verticalArrangement = Arrangement.spacedBy(UiSpacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (normalizedQuery.isBlank()) "从已启用的提供商中选择"
                        else "找到 $visibleModelCount 个匹配模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selectedIds.isNotEmpty()) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            if (singleSelect) "已选择" else "已选 ${selectedIds.size}",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索模型或提供商") },
                    singleLine = true,
                    shape = CircleShape,
                    leadingIcon = { SearchGlyph() },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                )
            }

            if (filteredGroups.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.small),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                ) {
                    items(filteredGroups, key = { it.id }) { group ->
                        AssistChip(
                            onClick = {
                                focusManager.clearFocus()
                                groupPositions[group.id]?.let { position ->
                                    scope.launch { listState.animateScrollToItem(position) }
                                }
                            },
                            label = { Text(group.title, maxLines = 1) },
                        )
                    }
                }
            }

            if (rows.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(UiSpacing.small),
                    ) {
                        Text("没有匹配的模型", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (groups.isEmpty()) "请先在模型提供商中添加并启用模型。"
                            else "尝试模型名称、完整 ID 或提供商名称。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(UiSpacing.small),
                    contentPadding = PaddingValues(bottom = UiSpacing.small),
                ) {
                    rows.forEachIndexed { index, row ->
                        if (row.entry == null) {
                            val group = row.group
                            val collapsed = normalizedQuery.isBlank() &&
                                group.id in collapsedGroupIds
                            stickyHeader(key = "group:${group.id}:$index") {
                                GroupHeading(
                                    title = group.title,
                                    count = group.entries.size,
                                    collapsed = collapsed,
                                    onClick = {
                                        if (normalizedQuery.isBlank()) {
                                            collapsedGroupIds = if (collapsed) {
                                                collapsedGroupIds - group.id
                                            } else {
                                                collapsedGroupIds + group.id
                                            }
                                        }
                                    },
                                )
                            }
                        } else {
                            val entry = row.entry
                            item(key = "model:${row.group.id}:${entry.id}") {
                                ModelEntryCard(
                                    entry = entry,
                                    provider = row.group.title,
                                    selected = entry.id in selectedIds,
                                    singleSelect = singleSelect,
                                modifier = Modifier.testTag("model-option:${entry.id}").semantics {
                                    testTagsAsResourceId = true
                                },
                                    onClick = { choose(entry) },
                                )
                            }
                        }
                    }
                }
            }

            if (!singleSelect) {
                Button(
                    onClick = onDismissRequest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("$confirmLabel · ${selectedIds.size} 个模型")
                }
            }
            Spacer(Modifier.size(2.dp))
        }
    }
}

/** Compact model trigger matching RikkaHub's "model field" capsule. */
@Composable
internal fun ModelSelectionField(
    displayName: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String = "点击切换模型",
    compact: Boolean = false,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 12.dp else 16.dp,
                vertical = if (compact) 6.dp else 10.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(if (compact) 28.dp else 36.dp),
                shape = RoundedCornerShape(if (compact) 9.dp else 11.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "模",
                        style = if (compact) MaterialTheme.typography.labelLarge
                        else MaterialTheme.typography.titleSmall,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact) {
                    Text(
                        supportingText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AppIcon(AppSymbol.DOWN, description = "展开模型选择")
        }
    }
}

@Composable
private fun GroupHeading(
    title: String,
    count: Int,
    collapsed: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(UiSpacing.small),
        ) {
            AppIcon(if (collapsed) AppSymbol.NEXT else AppSymbol.DOWN, description = null)
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$count 个",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelEntryCard(
    entry: ModelPickerEntry,
    provider: String,
    selected: Boolean,
    singleSelect: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = entry.enabled,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                role = if (singleSelect) Role.RadioButton else Role.Checkbox
                this.selected = selected
            },
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        (entry.providerName.ifBlank { provider }).take(1).uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    entry.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(entry.providerName.ifBlank { provider }, entry.detail)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.displayName != entry.id) {
                    Text(
                        entry.id,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SelectionMark(selected = selected)
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean) {
    Surface(
        modifier = Modifier.size(24.dp),
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary
        else Color.Transparent,
        border = BorderStroke(
            1.5.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
        ),
    ) {
        if (selected) {
            val markColor = MaterialTheme.colorScheme.onPrimary
            Canvas(Modifier.padding(6.dp)) {
                drawLine(
                    markColor,
                    start = Offset(size.width * 0.08f, size.height * 0.55f),
                    end = Offset(size.width * 0.42f, size.height * 0.83f),
                    strokeWidth = 2.2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    markColor,
                    start = Offset(size.width * 0.42f, size.height * 0.83f),
                    end = Offset(size.width * 0.94f, size.height * 0.18f),
                    strokeWidth = 2.2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun SearchGlyph() {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp)) {
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.28f,
            center = Offset(size.width * 0.43f, size.height * 0.43f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8.dp.toPx()),
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.64f, size.height * 0.64f),
            end = Offset(size.width * 0.87f, size.height * 0.87f),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

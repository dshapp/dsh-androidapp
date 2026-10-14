package ai.deepseek.dshphone.ui.screen

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.net.WorkspaceId
import ai.deepseek.dshphone.net.dbl
import ai.deepseek.dshphone.net.get
import ai.deepseek.dshphone.net.obj
import ai.deepseek.dshphone.state.*
import ai.deepseek.dshphone.ui.component.CardDetailDialog
import ai.deepseek.dshphone.ui.component.MarkdownText
import ai.deepseek.dshphone.ui.component.MonospaceBlock
import ai.deepseek.dshphone.ui.theme.Dimens
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// —— 模型选择 ————————————————————————————————————————————————

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(model: PhoneModel, sessionId: SessionId, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val store = model.sessions[sessionId]
    val directory = store?.modelDirectory ?: ModelDirectoryState()
    var tab by remember { mutableStateOf(0) }
    val currentReasoning = directory.current?.let { current ->
        directory.groups.firstOrNull { it.id == current.provider }
            ?.models?.firstOrNull { it.id == current.model }?.reasoning
    }
    val hasEfforts = !currentReasoning?.efforts.isNullOrEmpty()
    // 模型搜索（web `ModelSelect`）：目录超过 4 个模型才出搜索框。
    var query by remember { mutableStateOf("") }
    val showSearch = directory.groups.sumOf { it.models.size } > MODEL_SEARCH_THRESHOLD
    val visibleGroups = remember(directory.groups, query, showSearch) {
        visibleModelGroups(directory.groups, if (showSearch) query else "")
    }

    LaunchedEffect(sessionId) { model.loadModels(sessionId) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            if (hasEfforts) {
                TabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text(Strings.model) })
                    Tab(tab == 1, { tab = 1 }, text = { Text(Strings.thinkingEffort) })
                }
            } else {
                Text(
                    Strings.model,
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (directory.status == ModelDirectoryStatus.LOADING && directory.current == null) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            val selecting = directory.status == ModelDirectoryStatus.SELECTING
            if (showSearch && (tab == 0 || !hasEfforts)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text(Strings.modelSearchPlaceholder) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, Strings.modelSearchClear)
                            }
                        }
                    },
                    singleLine = true,
                )
            }
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (selecting) 0.5f else 1f),
                userScrollEnabled = !selecting,
            ) {
                if (tab == 0 || !hasEfforts) {
                    if (visibleGroups.isEmpty() && query.isNotBlank()) {
                        item(key = "search-empty") {
                            Text(
                                Strings.modelSearchEmpty,
                                Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    visibleGroups.forEach { group ->
                        item(key = "g-${group.id}") {
                            Text(
                                group.name,
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(group.models, key = { "${group.id}/${it.id}" }) { entry ->
                            val selected = directory.current?.provider == group.id &&
                                directory.current?.model == entry.id
                            ListItem(
                                modifier = Modifier.clickable(enabled = !selecting) {
                                    if (selected) onDismiss()
                                    else {
                                        model.selectModel(
                                            ModelSelection(group.id, entry.id, null), sessionId,
                                        )
                                        onDismiss()
                                    }
                                },
                                headlineContent = { Text(entry.name) },
                                trailingContent = {
                                    if (selected) Icon(Icons.Default.Check, null,
                                        tint = MaterialTheme.colorScheme.primary)
                                },
                            )
                        }
                    }
                } else {
                    items(currentReasoning?.efforts ?: emptyList(), key = { it.id }) { effort ->
                        val selected = directory.current?.reasoningEffort == effort.id
                        ListItem(
                            modifier = Modifier.clickable(enabled = !selecting) {
                                val current = directory.current ?: return@clickable
                                if (!selected) {
                                    model.selectModel(
                                        ModelSelection(current.provider, current.model, effort.id), sessionId,
                                    )
                                }
                                onDismiss()
                            },
                            headlineContent = { Text(effort.name) },
                            trailingContent = {
                                if (selected) Icon(Icons.Default.Check, null,
                                    tint = MaterialTheme.colorScheme.primary)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Provider catalog configuration is separate from the session llm/select picker above. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderCatalogSettingsSheet(store: HostSettingsStore, onSave: () -> Unit) {
    val draft = store.draft ?: return
    val writable = store.catalogWritable
    ModalBottomSheet(onDismissRequest = { store.cancelCatalog() }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(draft.entry.displayName.ifBlank { draft.entry.provider }, style = MaterialTheme.typography.titleMedium)
            Text(settingsLabel("Models · expected revision ", "模型目录 · 原始版本 ")+draft.expectedRevision, style = MaterialTheme.typography.bodySmall)
            Text(if (draft.owned) settingsLabel("User override", "用户覆盖") else settingsLabel("Inherited models · reset only unsets the user override", "继承模型 · 恢复仅移除用户覆盖"), style = MaterialTheme.typography.bodySmall)
            if (!writable) Text(settingsLabel("Read-only / saving / provider unavailable", "只读、保存中或 provider 已不可用"), style = MaterialTheme.typography.bodySmall)
            draft.rows.forEachIndexed { index, row ->
                key(index) {
                    OutlinedTextField(row.id, { text -> store.editCatalog(index) { it.copy(id = text) } }, label = { Text(settingsLabel("Model id", "模型 ID")) }, enabled = writable, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val fields = settingsSchemaNodeAt(draft.openedNamespace.schema, draft.path + "0")?.get("dict").obj.orEmpty()
                    if ("name" in fields) OutlinedTextField(row.name, { text -> store.editCatalog(index) { it.copy(name = text) } }, label = { Text(settingsLabel("Name", "名称")) }, enabled = writable, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if ("contextWindow" in fields) OutlinedTextField(row.contextWindow, { text -> store.editCatalog(index) { it.copy(contextWindow = text) } }, label = { Text(settingsLabel("Context window (tokens)", "上下文容量（tokens）")) }, enabled = writable, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if ("maxTokens" in fields) OutlinedTextField(row.maxTokens, { text -> store.editCatalog(index) { it.copy(maxTokens = text) } }, label = { Text(settingsLabel("Max tokens", "最大输出 tokens")) }, enabled = writable, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { store.removeCatalogModel(index) }, enabled = writable) { Text(settingsLabel("Remove model", "移除模型")) }
                    HorizontalDivider()
                }
            }
            TextButton(onClick = { store.addCatalogModel() }, enabled = writable) { Text(settingsLabel("Add model", "添加模型")) }
            TextButton(onClick = { store.resetCatalogModels() }, enabled = writable && draft.owned) { Text(settingsLabel("Reset to inherited models", "恢复继承模型")) }
            (store.saveError ?: draft.validationError())?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { store.cancelCatalog() }, enabled = !store.saving) { Text(Strings.cancel) }
                Button(onClick = onSave, enabled = writable && draft.touched && draft.validationError() == null) { Text(if (store.saving) settingsLabel("Saving…", "保存中…") else settingsLabel("Save", "保存")) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// —— 上下文明细 ————————————————————————————————————————————

/** contextBreakdown 投影的三段启发式读数（与 Mac ContextBreakdownReading 同构）。 */
private data class ContextBreakdown(val system: Double, val tools: Double, val messages: Double) {
    val total: Double get() = system + tools + messages
}

private fun contextBreakdown(store: SessionStore): ContextBreakdown? {
    val obj = store.projectionValue("contextBreakdown").obj ?: return null
    return ContextBreakdown(
        obj["systemTokens"].dbl ?: 0.0,
        obj["toolsTokens"].dbl ?: 0.0,
        obj["messageTokens"].dbl ?: 0.0,
    )
}

private data class ContextSegment(val color: Color, val fraction: Double)

private val contextSystemTint = Color(0xFF8E8E93)
private val contextToolsTint = Color(0xFFAF52DE)
private val contextMessagesTint = Color(0xFF0A84FF)

/**
 * 条形分段：三段合起来只占主机给出的 percent，比例由启发式明细决定；
 * 没有明细就画一整段无色的（与 Mac ContextBreakdownPanel.segments 同一条规则）。
 */
private fun contextSegments(percent: Int, breakdown: ContextBreakdown?, plain: Color): List<ContextSegment> {
    val fraction = (percent / 100.0).coerceIn(0.0, 1.0)
    if (breakdown == null || breakdown.total <= 0) {
        return if (fraction > 0) listOf(ContextSegment(plain, fraction)) else emptyList()
    }
    return listOf(
        ContextSegment(contextSystemTint, fraction * breakdown.system / breakdown.total),
        ContextSegment(contextToolsTint, fraction * breakdown.tools / breakdown.total),
        ContextSegment(contextMessagesTint, fraction * breakdown.messages / breakdown.total),
    ).filter { it.fraction > 0 }
}

/** 会话统计读数：有 sessionStats 投影就用投影，否则按已加载窗口折算（同 Mac resolve）。 */
private data class SessionStatsReading(
    val turns: Int = 0,
    val steps: Int = 0,
    val llmMs: Double = 0.0,
    val toolMs: Double = 0.0,
    val ttftMs: Double = 0.0,
    val ttftSteps: Int = 0,
    val decodeMs: Double = 0.0,
    val decodeTokens: Int = 0,
) {
    val ttftAverageMs: Double? get() = if (ttftSteps > 0) ttftMs / ttftSteps else null
    val tokensPerSecond: Double? get() = if (decodeMs > 0) decodeTokens / (decodeMs / 1000) else null
}

private fun resolveSessionStats(store: SessionStore): SessionStatsReading {
    store.sessionStats?.let {
        return SessionStatsReading(
            it.turns, it.steps, it.llmMs, it.toolMs, it.ttftMs, it.ttftSteps, it.decodeMs, it.decodeTokens,
        )
    }
    val turns = mutableSetOf<Int>()
    var steps = 0
    var llmMs = 0.0
    var toolMs = 0.0
    var ttftMs = 0.0
    var ttftSteps = 0
    var decodeMs = 0.0
    var decodeTokens = 0
    store.nodes.forEach { node ->
        when (node) {
            is ChatNode.Assistant -> {
                val step = node.step
                steps += 1
                turns.add(step.turn)
                step.stepStartTime?.let { start ->
                    llmMs += (step.time - start).coerceAtLeast(0.0)
                    step.firstTokenTime?.let { first ->
                        ttftMs += (first - start).coerceAtLeast(0.0)
                        ttftSteps += 1
                    }
                }
                val output = step.usage?.outputTokens
                val first = step.firstTokenTime
                if (first != null && output != null) {
                    decodeMs += (step.time - first).coerceAtLeast(0.0)
                    decodeTokens += output
                }
            }
            is ChatNode.Tool -> node.data.result?.let {
                toolMs += (it.time - node.data.time).coerceAtLeast(0.0)
            }
            else -> Unit
        }
    }
    return SessionStatsReading(turns.size, steps, llmMs, toolMs, ttftMs, ttftSteps, decodeMs, decodeTokens)
}

/** 明细行；section 行是分组标题，自带一个总值。 */
private data class ContextDetailRow(val label: String, val value: String, val section: Boolean = false)

/**
 * 预设 + 两组统计（会话统计、Token 用量），逐字段对齐 Mac 的 detailRows：
 * 会话从未产出的读数不占行，而不是显示一个 0。
 */
private fun contextDetailRows(model: PhoneModel, store: SessionStore): List<ContextDetailRow> {
    val rows = mutableListOf<ContextDetailRow>()
    store.agentPreset?.let { id ->
        val name = model.agentPresets.firstOrNull { it.id == id }
            ?.let { agentPresetDisplayText(it).name } ?: id
        rows.add(ContextDetailRow(Strings.preset, name))
    }
    val stats = resolveSessionStats(store)
    if (stats.steps > 0) {
        rows.add(ContextDetailRow(Strings.statsDialogTitle, "", section = true))
        rows.add(ContextDetailRow(Strings.statsProgress, fmt(Strings.statsCounts, stats.turns, stats.steps)))
        if (stats.llmMs > 0) rows.add(ContextDetailRow(Strings.statsLlm, formatStatsDuration(stats.llmMs)))
        if (stats.toolMs > 0) rows.add(ContextDetailRow(Strings.statsTools, formatStatsDuration(stats.toolMs)))
        stats.ttftAverageMs?.let { rows.add(ContextDetailRow(Strings.statsTtft, formatStatsDuration(it))) }
        stats.tokensPerSecond?.let {
            rows.add(ContextDetailRow(
                Strings.statsDecode,
                fmt(Strings.messageTokensPerSecond, formatTokensPerSecond(it)),
            ))
        }
    }
    SessionUsageReading(store.tokenUsage)?.takeIf { it.hasTokens }?.let { usage ->
        rows.add(ContextDetailRow(Strings.statsUsageTitle, tokenCount(usage.total), section = true))
        // 会话级一手读数（Mac sessionCacheHitPercent），不是单轮那个固定一位小数的口径：
        // 99.96% 在这里是 "99.96"，用单轮那个会显示成 "99.9"。
        sessionCacheHitPercent(usage.cacheRead, usage.billed)?.let {
            rows.add(ContextDetailRow(Strings.turnUsageCacheHit, "$it%"))
        }
        rows.add(ContextDetailRow(Strings.turnUsageInput, tokenCount(usage.uncached)))
        rows.add(ContextDetailRow(Strings.turnUsageCacheRead, tokenCount(usage.cacheRead)))
        if (usage.cacheWrite != 0) {
            rows.add(ContextDetailRow(Strings.turnUsageCacheWrite, tokenCount(usage.cacheWrite)))
        }
        rows.add(ContextDetailRow(Strings.turnUsageOutput, tokenCount(usage.output)))
    }
    return rows
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextSheet(model: PhoneModel, sessionId: SessionId, onDismiss: () -> Unit) {
    val store = model.sessions[sessionId]
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val occupancy = store?.contextOccupancy
            if (occupancy != null) {
                // 百分比夹在 contextAria 的两半之间，让每种语言保留自己的语序。
                val head = Strings.contextAria.split("%d%%")
                val before = head.firstOrNull().orEmpty().trim()
                val after = head.getOrNull(1).orEmpty().trim()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (before.isNotEmpty()) {
                        Text(before, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${occupancy.percent}%", style = MaterialTheme.typography.titleSmall)
                    if (after.isNotEmpty()) {
                        Text(after, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "~${formatTokens(occupancy.usedTokens.toInt())} / " +
                            formatTokens(occupancy.contextWindow.toInt()),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                val breakdown = contextBreakdown(store)
                val segments = contextSegments(
                    occupancy.percent, breakdown, MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (segments.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        segments.forEach { segment ->
                            Box(
                                Modifier
                                    .weight(segment.fraction.toFloat())
                                    .fillMaxHeight()
                                    .background(segment.color),
                            )
                        }
                        // 余量必须有正权重，Row 的 weight 不接受 0。
                        val rest = (1.0 - segments.sumOf { it.fraction }).coerceAtLeast(0.0001)
                        Spacer(Modifier.weight(rest.toFloat()))
                    }
                }
                if (breakdown != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        LegendRow(Strings.contextSystem, breakdown.system, contextSystemTint)
                        LegendRow(Strings.contextTools, breakdown.tools, contextToolsTint)
                        LegendRow(Strings.contextMessages, breakdown.messages, contextMessagesTint)
                    }
                }
            }
            val details = store?.let { contextDetailRows(model, it) }.orEmpty()
            if (details.isNotEmpty()) {
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    details.forEach { DetailRow(it) }
                }
            }
        }
    }
}

@Composable
private fun LegendRow(label: String, tokens: Double, color: Color) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text("~${formatTokens(tokens.toInt())}", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DetailRow(row: ContextDetailRow) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = if (row.section) 6.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            row.label,
            style = if (row.section) MaterialTheme.typography.titleSmall
            else MaterialTheme.typography.bodyMedium,
            color = if (row.section) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text(row.value, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun LabeledRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// —— 会话面板 ————————————————————————————————————————————————

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionPanelSheet(
    model: PhoneModel,
    sessionId: SessionId,
    onDismiss: () -> Unit,
    onOpenSession: (SessionId) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val store = model.sessions[sessionId]
    var subagents by remember { mutableStateOf<List<SubagentCatalogEntry>>(emptyList()) }

    LaunchedEffect(sessionId) { subagents = model.loadSubagents(sessionId) }

    val deliverables = remember(store?.deliverablesRevision, store?.sessionStats?.turns) {
        val turn = store?.sessionStats?.turns ?: return@remember emptyList<PresentedFile>()
        var found = emptyList<PresentedFile>()
        for (candidate in turn downTo maxOf(turn - 5, 0)) {
            val files = store.presentedFiles(candidate)
            if (files.isNotEmpty()) { found = files; break }
        }
        found
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            store?.todos?.takeIf { it.isNotEmpty() }?.let { todos ->
                SectionTitle(Strings.todos)
                todos.forEach { todo ->
                    ListItem(
                        headlineContent = { Text(todo.content, style = MaterialTheme.typography.bodyMedium) },
                        leadingContent = { Icon(todoIcon(todo.status), null, tint = todoColor(todo.status)) },
                    )
                }
            }

            store?.jobs?.takeIf { it.isNotEmpty() }?.let { jobs -> JobsSection(model, sessionId, jobs) }

            if (subagents.isNotEmpty()) {
                SectionTitle(Strings.subagents)
                subagents.forEach { entry ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = entry.canOpenLocally) {
                            scope.launch {
                                model.openSubagent(sessionId, entry)?.let {
                                    onDismiss()
                                    onOpenSession(it)
                                }
                            }
                        },
                        headlineContent = {
                            Text(entry.label ?: entry.id, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = {
                            if (model.subagentRunning(entry.id)) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            else if (entry.canOpenLocally) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            else Text("external", style = MaterialTheme.typography.labelSmall)
                        },
                    )
                }
            }

            if (deliverables.isNotEmpty()) {
                SectionTitle(Strings.deliverables)
                deliverables.forEach { file ->
                    ListItem(
                        modifier = Modifier.clickable { model.openOnHost(file.path) },
                        headlineContent = { Text(pathBasename(file.path), maxLines = 1) },
                        supportingContent = file.description?.let { { Text(it, maxLines = 1) } },
                    )
                }
            }

            store?.sessionStats?.let { stats ->
                SectionTitle(Strings.stats)
                LabeledRow(Strings.turns, stats.turns.toString())
                LabeledRow(Strings.steps, stats.steps.toString())
                store.contextOccupancy?.let { LabeledRow(Strings.context, "${it.percent}%") }
            }
        }
    }
}

// —— 审批 ——————————————————————————————————————————————————

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(model: PhoneModel, approval: ApprovalRequested, onDismiss: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val store = model.sessions[approval.sessionId]
    // 与 iOS ApprovalPresentation.resolve 同条件：只有 bash 且该调用还没出结果时
    // 才展示完整命令，避免把已经跑完的调用再当成待审内容。
    val command = remember(approval.callId, store) {
        val callId = approval.callId ?: return@remember null
        if (approval.toolName != "bash") return@remember null
        if (store?.sessionId != approval.sessionId) return@remember null
        val call = store.toolCall(callId) ?: return@remember null
        if (call.result != null) return@remember null
        parseArgsMap(call.argsRaw)?.get("command") as? String
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(Strings.approvalTitle, style = MaterialTheme.typography.titleMedium)
            Text(
                // 主机没给理由时套统一的越权文案（与 iOS ApprovalPresentation 一致）。
                approval.reason?.takeIf { it.isNotBlank() }
                    ?: fmt(Strings.approvalEscalation, approval.toolName),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                approval.toolName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            command?.let { MonospaceBlock(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        model.respondApproval(approval, ApprovalOutcome.ALLOWED_ONCE)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(Strings.approve) }
                OutlinedButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        model.respondApproval(approval, ApprovalOutcome.REJECTED)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    // 与 iOS 的 destructive role 同色。
                    Text(Strings.deny, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// —— 提问 / 计划评审 ————————————————————————————————————————

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionSheet(model: PhoneModel, question: QuestionRequested, onDismiss: () -> Unit) {
    val review = remember(question) { PlanReviewFlow.route(question.questions) }
    if (review != null) {
        PlanReviewSheet(model, question, review, onDismiss)
        return
    }
    // 逐题作答：一次只呈现一道题，翻页条显示进度，最后一题才提交整组答案
    // （与 Mac 的 QuestionFlow 卡片同一套流程状态）。
    // 按问题身份记草稿：倒计时开始、按住等待都会换一份 question 值，但不能把草稿重置掉。
    var flow by remember(question.identity) { mutableStateOf(QuestionFlowState(question.questions)) }
    var busy by remember(question.identity) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val item = flow.current
    val draft = flow.currentDraft

    // 换题就把正文滚回顶部，免得下一题开局停在上一题的滚动位置。
    LaunchedEffect(flow.index) { scroll.scrollTo(0) }

    fun submit(state: QuestionFlowState) {
        if (busy) return
        busy = true
        model.respondQuestion(question, state.answers())
        onDismiss()
    }

    // 任何改动都让限时等待一直开着，直到这张卡结掉（web `engage`）。
    fun edit(state: QuestionFlowState) {
        flow = state
        model.holdQuestion(question)
    }

    fun advance(step: QuestionFlowStep) {
        edit(step.state)
        if (step.submit) submit(step.state)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (item == null || draft == null) return@ModalBottomSheet
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
            // 不再重复「提问」标题：问题正文本身就是标题，弹层已有拖拽把手标识层级。
            // 右上角的关闭键放弃整组问题（= 拒答），与底部只跳过当前这题的「跳过」分工不同。
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    item.header?.takeIf { it.isNotEmpty() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(item.question, style = MaterialTheme.typography.titleMedium)
                }
                IconButton(
                    onClick = {
                        if (busy) return@IconButton
                        busy = true
                        model.cancelQuestion(question)
                        onDismiss()
                    },
                    enabled = !busy,
                ) {
                    // continued 问题没有请求可拒，关掉只是收起。
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = if (question.continued) Strings.questionHide else Strings.questionCancel,
                    )
                }
            }

            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(scroll)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.detail?.takeIf { it.isNotEmpty() }?.let { MarkdownText(it) }
                item.options?.forEach { option ->
                    val display = splitRecommended(option.label)
                    val picked = draft.selected.contains(option.label)
                    ListItem(
                        modifier = Modifier.clickable(enabled = !busy) {
                            advance(flow.chooseSettlingBatch(option.label))
                        },
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(display.label)
                                if (display.recommended) {
                                    Text(Strings.questionRecommended,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        supportingContent = option.description?.takeIf { it.isNotEmpty() }?.let {
                            { Text(it, style = MaterialTheme.typography.bodySmall) }
                        },
                        leadingContent = {
                            // 多选给复选框、单选给单选钮，选择语义一眼可辨。
                            if (item.multiSelect == true) {
                                Checkbox(checked = picked, onCheckedChange = null)
                            } else {
                                RadioButton(selected = picked, onClick = null)
                            }
                        },
                    )
                }
                OutlinedTextField(
                    value = draft.custom,
                    onValueChange = { edit(flow.setCustom(it)) },
                    label = {
                        // 无选项的题只能自填，这时输入框就是答案本身。
                        Text(
                            if (item.options.isNullOrEmpty()) Strings.questionCustomPlaceholder
                            else Strings.answerCustom
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
            }

            Column(
                Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val feedback = when (flow.validation) {
                    QuestionFlowValidation.UNANSWERED -> Strings.questionErrorUnanswered
                    QuestionFlowValidation.INCOMPLETE -> Strings.questionErrorIncomplete
                    null -> null
                }
                if (feedback != null) {
                    Text(
                        feedback,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    QuestionWaitStatus(question) { model.holdQuestion(question) }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (flow.questions.size > 1) {
                        IconButton(
                            onClick = { flow = flow.previous() },
                            enabled = !flow.isFirst && !busy,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = Strings.questionPrev,
                            )
                        }
                        Text(
                            fmt(Strings.questionProgress, flow.index + 1, flow.questions.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(
                            onClick = { flow = flow.next() },
                            enabled = !flow.isLast && !busy,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = Strings.questionNext,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // 跳过 = 这道题拒答，交给下一题；整组跳完才上报。
                    TextButton(
                        onClick = { advance(flow.skip()) },
                        enabled = !busy,
                    ) { Text(Strings.questionSkip) }
                    Button(
                        onClick = { advance(flow.continueFlow()) },
                        enabled = !busy && draft.answered,
                    ) { Text(if (flow.isLast) Strings.submit else Strings.questionActionNext) }
                }
            }
        }
    }
}

/** 限时等待的状态行（web `wait.*`）：倒计时 + 慢慢回答、已按住、已继续。 */
@Composable
private fun QuestionWaitStatus(question: QuestionRequested, onTakeTime: () -> Unit) {
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val deadline = question.deadline
    when {
        question.continued -> Text(Strings.questionWaitContinued, style = style, color = color)
        deadline != null -> {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(deadline) {
                while (now < deadline) {
                    kotlinx.coroutines.delay(1000 - (now % 1000))
                    now = System.currentTimeMillis()
                }
            }
            val seconds = ((deadline - now + 999) / 1000).coerceAtLeast(0).toInt()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(Strings.questionWaitCountdown, seconds), style = style, color = color)
                TextButton(onClick = onTakeTime) { Text(Strings.questionWaitTakeTime) }
            }
        }
        question.held -> Text(Strings.questionWaitHeld, style = style, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanReviewSheet(
    model: PhoneModel,
    question: QuestionRequested,
    review: PlanReviewFlow,
    onDismiss: () -> Unit,
) {
    var notes by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            Text(
                Strings.questionPlanHeader,
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.columnInset),
            ) { MarkdownText(review.plan) }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    notes, { notes = it },
                    label = { Text(Strings.questionPlanFeedbackPlaceholder) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { busy = true; model.cancelQuestion(question); onDismiss() },
                        enabled = !busy,
                    ) { Text(Strings.questionPlanDiscuss) }
                    OutlinedButton(
                        onClick = {
                            busy = true
                            model.respondQuestion(question, listOf(review.reviseAnswer(notes)))
                            onDismiss()
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(Strings.questionPlanDecline) }
                    Button(
                        onClick = {
                            busy = true
                            model.respondQuestion(question, listOf(review.submitAnswer(notes)))
                            onDismiss()
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (notes.isBlank()) Strings.questionPlanApprove
                        else Strings.questionPlanApproveWithNotes)
                    }
                }
            }
        }
    }
}

// —— 文件预览 ————————————————————————————————————————————————

/**
 * 转写里点文件链接后的唯一落地：应用内的只读纯文本预览。
 *
 * 弹层用与系统提示卡片等详情卡同一种壳（[ai.deepseek.dshphone.ui.component.CardDetailDialog]）：
 * 标题是文件名，正文是等宽纯文本，右上角复制、左上角关闭。点击后弹层立刻出现，
 * 内容按[workspaceFiles/read]分页读，读的过程中在弹层内部转圈——不先弹"正在打开"
 * 之类的提示，也不把文件交给系统查看器或别的 App。
 */
@Composable
fun FilePreviewSheet(model: PhoneModel, sessionId: SessionId, path: String, onDismiss: () -> Unit) {
    var text by remember(path) { mutableStateOf("") }
    var nextOffset by remember(path) { mutableStateOf(1) }
    var version by remember(path) { mutableStateOf<String?>(null) }
    var eof by remember(path) { mutableStateOf(false) }
    var loading by remember(path) { mutableStateOf(false) }
    var failure by remember(path) { mutableStateOf<String?>(null) }
    // 首屏这一趟有没有回来过（不论成功还是失败）。打开弹层要立刻转圈，而
    // LaunchedEffect 起跑前 loading 还是 false：只认 loading 会在弹层弹出后
    // 先闪一下"这个文件是空的"，所以用这个标记把"还没回来"和"回来发现是空文件"分开。
    var settled by remember(path) { mutableStateOf(false) }

    suspend fun loadNextPage() {
        if (loading || eof) return
        loading = true
        failure = null
        try {
            var requested = nextOffset
            var page = model.workspaceFilePage(sessionId, path, requested)
            if (requested > 1 && version != null && version != page.version) {
                text = ""
                nextOffset = 1
                version = null
                requested = 1
                page = model.workspaceFilePage(sessionId, path, requested)
            }
            version = page.version
            if (page.lines > 0) {
                text = if (text.isEmpty()) page.text else text + "\n" + page.text
                nextOffset = page.offset + page.lines
            }
            eof = page.eof || page.lines == 0
        } catch (e: Exception) {
            failure = PhoneModel.describe(e)
        } finally {
            loading = false
            settled = true
        }
    }

    LaunchedEffect(path) { loadNextPage() }

    val scope = rememberCoroutineScope()
    CardDetailDialog(Icons.Default.Description, pathBasename(path).ifEmpty { path }, text, onDismiss) {
        when {
            // 首屏就失败：整页换成不可用态 + 重试。
            text.isEmpty() && failure != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Strings.fileUnavailable, style = MaterialTheme.typography.titleMedium)
                Text(failure!!, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { scope.launch { loadNextPage() } }) { Text(Strings.retry) }
            }
            // 首屏还没回来（含失败后的重试）：弹层已经在了，就在弹层里转圈
            // （不是弹层外先提示、再打开）。
            text.isEmpty() && (loading || !settled) -> Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(Strings.fileLoading)
            }
            text.isEmpty() -> Text(Strings.fileEmpty)
            else -> MonospaceBlock(text)
        }
        if (text.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            when {
                loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                // 翻到一半失败：正文留着，只在末尾给出原因与重试。
                failure != null -> Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(failure!!, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { scope.launch { loadNextPage() } }) { Text(Strings.retry) }
                }
                // 底部哨兵：滚到这里就取下一页。
                !eof -> LaunchedEffect(nextOffset) { loadNextPage() }
            }
        }
    }
}

// —— 后台任务 ————————————————————————————————————————————————

/**
 * 会话面板里的后台任务：分「进行中 / 已结束」两段（web 任务弹层）。
 *
 * 进行中按开始时间排，已结束按结束时间倒序；已结束一段可以折叠（默认只在没有进行中的
 * 任务时展开），也可以「清空」——只是本机隐藏，主机的记录不动。点一行打开详情。
 */
@Composable
private fun JobsSection(model: PhoneModel, sessionId: SessionId, jobs: List<JobView>) {
    var settledOpen by remember(sessionId) { mutableStateOf<Boolean?>(null) }
    var detailJobId by remember(sessionId) { mutableStateOf<String?>(null) }
    val cleared = model.clearedJobs(sessionId)
    val live = jobs.filter { it.isLive }.sortedBy { it.startedAt }
    val settled = jobs.filter { !it.isLive && !cleared.contains(it.id) }
        .sortedByDescending { it.finishedAt ?: it.startedAt }
    if (live.isEmpty() && settled.isEmpty()) return
    val settledExpanded = settledOpen ?: live.isEmpty()

    SectionTitle(Strings.jobs)
    if (live.isNotEmpty()) {
        JobGroupHeader(Strings.jobsSectionLive)
        live.forEach { JobRow(model, sessionId, it) { detailJobId = it.id } }
    }
    if (settled.isNotEmpty()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { settledOpen = !settledExpanded }
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (settledExpanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                fmt(Strings.jobsSectionSettled, settled.size),
                Modifier.weight(1f).padding(start = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { model.clearSettledJobs(sessionId, settled.map { it.id }) }) {
                Text(Strings.jobsSectionClear)
            }
        }
        if (settledExpanded) settled.forEach { JobRow(model, sessionId, it) { detailJobId = it.id } }
    }

    detailJobId?.let { id ->
        val job = jobs.firstOrNull { it.id == id }
        if (job == null) {
            detailJobId = null
        } else {
            JobDetailDialog(model, sessionId, job) { detailJobId = null }
        }
    }
}

@Composable
private fun JobGroupHeader(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun JobRow(model: PhoneModel, sessionId: SessionId, job: JobView, onOpen: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = { Text(job.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = job.qualifier?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (job.isLive) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    jobStatusLabel(job.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = jobStatusColor(job.status),
                )
                if (job.status == JobStatus.RUNNING) JobStopButton(model, sessionId, job, compact = true)
            }
        },
    )
}

/** 停止按钮：先弹原生确认框，确认后 `job/kill`；受理后一直转圈，直到 job/list 帧把它移出 running。 */
@Composable
private fun JobStopButton(model: PhoneModel, sessionId: SessionId, job: JobView, compact: Boolean) {
    val scope = rememberCoroutineScope()
    var confirming by remember(job.id) { mutableStateOf(false) }
    var pending by remember(job.id) { mutableStateOf(false) }
    var failed by remember(job.id) { mutableStateOf(false) }
    if (pending) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    } else if (compact) {
        IconButton(onClick = { confirming = true }) {
            Icon(
                Icons.Default.Stop,
                fmt(Strings.jobsKillStop, job.label),
                tint = if (failed) MaterialTheme.colorScheme.error else LocalContentColor.current,
            )
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { confirming = true }) {
                Icon(Icons.Default.Stop, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(Strings.jobsStopAction)
            }
            if (failed) {
                Text(
                    Strings.jobsKillFailed,
                    Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(fmt(Strings.jobsStopDialogTitle, job.label)) },
            text = { Text(Strings.jobsStopDialogMessage) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    pending = true
                    failed = false
                    scope.launch {
                        if (!model.killJob(sessionId, job.id)) {
                            pending = false
                            failed = true
                        }
                    }
                }) { Text(Strings.jobsStopAction, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(Strings.cancel) } },
        )
    }
}

/** 任务详情：字段（状态、开始/结束时刻、耗时、退出码、信号、详情），再加实时输出面板。 */
@Composable
private fun JobDetailDialog(model: PhoneModel, sessionId: SessionId, job: JobView, onDismiss: () -> Unit) {
    CardDetailDialog(Icons.Default.Schedule, job.label, job.label, onDismiss) {
        // 运行中的耗时需要一个时钟；已结束的冻结在 finishedAt，不必再走。
        var now by remember { mutableStateOf(System.currentTimeMillis().toDouble()) }
        if (job.isLive) {
            LaunchedEffect(job.id) {
                while (true) {
                    now = System.currentTimeMillis().toDouble()
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
        val end = if (job.isLive) now else job.finishedAt ?: job.startedAt
        val termination = JobTermination.parse(if (job.progress == null) job.detail else null)
        val remainder = job.progress ?: termination.remainder

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (job.isLive) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(jobStatusLabel(job.status), color = jobStatusColor(job.status),
                style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            if (job.status == JobStatus.RUNNING) JobStopButton(model, sessionId, job, compact = false)
        }
        Spacer(Modifier.height(8.dp))
        JobField(Strings.jobsDetailId, job.id, mono = true)
        if (job.kind.isNotEmpty()) JobField(Strings.jobsDetailKind, job.kind)
        JobField(Strings.jobsDetailStarted, clockTime(job.startedAt))
        job.finishedAt?.let { JobField(Strings.jobsDetailFinished, clockTime(it)) }
        JobField(Strings.jobsDetailDuration, formatDuration(maxOf(0.0, end - job.startedAt)))
        termination.exitCode?.let { code ->
            JobField(
                Strings.jobsDetailExitCode, code.toString(), mono = true,
                color = if (code == 0) null else MaterialTheme.colorScheme.error,
            )
        }
        termination.signal?.let { JobField(Strings.jobsDetailSignal, it, mono = true) }
        JobField(Strings.jobsDetailDetail, remainder ?: Strings.jobsDetailUnavailable, muted = remainder == null)

        // web `isObservable`：进行中的任务，以及结束后还留着输出的任务。
        if (job.isObservable) {
            Spacer(Modifier.height(12.dp))
            JobOutputPanel(model, sessionId, job)
        }
    }
}

@Composable
private fun JobField(label: String, value: String, mono: Boolean = false, muted: Boolean = false, color: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f)) {
            Text(
                value,
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                color = color ?: if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

/**
 * 任务的实时输出（`job/follow`），对应 web 内嵌的终端面板：等宽、可选中、跟着尾部走，
 * 带「较早输出已丢弃」和「实时流中断」两条提示。面板在屏时打开观察，离屏时关掉。
 */
@Composable
private fun JobOutputPanel(model: PhoneModel, sessionId: SessionId, job: JobView) {
    DisposableEffect(sessionId, job.id) {
        model.observeJob(sessionId, job.id)
        onDispose { model.stopObservingJob(job.id) }
    }
    val observed = model.observedJob?.takeIf { it.sessionId == sessionId && it.jobId == job.id }
    val streaming = observed?.streaming ?: true
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Strings.jobsOutputTitle, style = MaterialTheme.typography.titleSmall)
        if (streaming) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
    }
    if (observed?.gapBefore == true) {
        Text(Strings.jobsOutputGap, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    observed?.error?.let {
        Text(fmt(Strings.jobsOutputError, it), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error)
    }
    val text = when {
        observed == null -> ""
        observed.text.isEmpty() && !observed.streaming -> Strings.jobsOutputNone
        else -> observed.text
    }
    val scroll = rememberScrollState()
    // 跟着尾部：有新输出时滚到底。
    LaunchedEffect(text.length) { scroll.scrollTo(scroll.maxValue) }
    Box(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 360.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .verticalScroll(scroll)
            .padding(8.dp),
    ) {
        MonospaceBlock(
            text,
            tint = if (observed?.text.isNullOrEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun jobStatusLabel(status: JobStatus): String = when (status) {
    JobStatus.RUNNING -> Strings.jobStatusRunning
    JobStatus.STOPPING -> Strings.jobStatusStopping
    JobStatus.COMPLETED -> Strings.jobStatusCompleted
    JobStatus.KILLED -> Strings.jobStatusKilled
    JobStatus.FAILED -> Strings.jobStatusFailed
}

@Composable
private fun jobStatusColor(status: JobStatus): Color = when (status) {
    JobStatus.RUNNING -> MaterialTheme.colorScheme.primary
    JobStatus.FAILED -> MaterialTheme.colorScheme.error
    JobStatus.STOPPING, JobStatus.KILLED -> MaterialTheme.colorScheme.tertiary
    JobStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
}

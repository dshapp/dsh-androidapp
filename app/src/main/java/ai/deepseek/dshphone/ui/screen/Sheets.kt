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
import ai.deepseek.dshphone.ui.component.EmptyStateColumn
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
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (selecting) 0.5f else 1f),
                userScrollEnabled = !selecting,
            ) {
                if (tab == 0 || !hasEfforts) {
                    directory.groups.forEach { group ->
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
    store.tokenUsage?.let { usage ->
        val uncached = usage.uncachedInputTokens ?: usage.inputTokens ?: 0
        val cacheRead = usage.cacheReadTokens ?: 0
        val cacheWrite = usage.cacheWriteTokens ?: 0
        val output = usage.outputTokens ?: 0
        val billed = uncached + cacheRead + cacheWrite
        if (billed > 0 || output > 0) {
            rows.add(ContextDetailRow(Strings.statsUsageTitle, tokenCount(billed + output), section = true))
            cacheHitPercent(usage)?.let { rows.add(ContextDetailRow(Strings.turnUsageCacheHit, "$it%")) }
            rows.add(ContextDetailRow(Strings.turnUsageInput, tokenCount(uncached)))
            rows.add(ContextDetailRow(Strings.turnUsageCacheRead, tokenCount(cacheRead)))
            if (cacheWrite != 0) rows.add(ContextDetailRow(Strings.turnUsageCacheWrite, tokenCount(cacheWrite)))
            rows.add(ContextDetailRow(Strings.turnUsageOutput, tokenCount(output)))
        }
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

// —— 已归档 ————————————————————————————————————————————————

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedSheet(model: PhoneModel, onDismiss: () -> Unit, onOpen: (SessionId) -> Unit) {
    val ids = remember(model.workspaceStore.archivedSessionIds, model.listStore.archivedSessionIds) {
        (model.workspaceStore.archivedSessionIds + model.listStore.archivedSessionIds).distinct().sorted()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 560.dp)) {
            if (ids.isEmpty()) {
                EmptyStateColumn(Icons.Default.Inventory2, Strings.noArchived, null)
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(ids, key = { it }) { id ->
                        ListItem(
                            modifier = Modifier.clickable { onDismiss(); onOpen(id) },
                            headlineContent = {
                                Text(
                                    model.listStore.sessionsById[id]?.let { sessionDisplayTitle(it, null) } ?: id,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            },
                            // 恢复入口（0.1.6 的 workspace/unarchiveSession）：归档时本地把它从列表里
                            // 摘掉了，只靠点击是打不开的，得先让主机把它放回来。
                            trailingContent = {
                                TextButton(onClick = { model.unarchive(id) }) { Text(Strings.restore) }
                            },
                        )
                    }
                }
            }
        }
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
    var subagents by remember { mutableStateOf<List<SubagentListEntry>>(emptyList()) }

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

            store?.jobs?.takeIf { it.isNotEmpty() }?.let { jobs ->
                SectionTitle(Strings.jobs)
                jobs.forEach { job ->
                    ListItem(
                        headlineContent = { Text(job.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = job.detail?.let { { Text(it, maxLines = 1) } },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (job.isLive) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text(job.status.wire, style = MaterialTheme.typography.labelSmall)
                            }
                        },
                    )
                }
            }

            if (subagents.isNotEmpty()) {
                SectionTitle(Strings.subagents)
                subagents.forEach { entry ->
                    ListItem(
                        modifier = Modifier.clickable {
                            scope.launch {
                                model.openSubagent(sessionId, entry, true)?.let {
                                    onDismiss()
                                    onOpenSession(it)
                                }
                            }
                        },
                        headlineContent = {
                            Text(entry.label ?: entry.sessionId, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = {
                            if (entry.running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
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
    var flow by remember(question) { mutableStateOf(QuestionFlowState(question.questions)) }
    var busy by remember(question) { mutableStateOf(false) }
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

    fun advance(step: QuestionFlowStep) {
        flow = step.state
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
                ) { Icon(Icons.Filled.Close, contentDescription = Strings.questionCancel) }
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
                    onValueChange = { flow = flow.setCustom(it) },
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
                when (flow.validation) {
                    QuestionFlowValidation.UNANSWERED -> Strings.questionErrorUnanswered
                    QuestionFlowValidation.INCOMPLETE -> Strings.questionErrorIncomplete
                    null -> null
                }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
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

@Composable
fun FilePreviewSheet(model: PhoneModel, sessionId: SessionId, path: String, onDismiss: () -> Unit) {
    var text by remember(path) { mutableStateOf("") }
    var nextOffset by remember(path) { mutableStateOf(1) }
    var version by remember(path) { mutableStateOf<String?>(null) }
    var eof by remember(path) { mutableStateOf(false) }
    var loading by remember(path) { mutableStateOf(false) }
    var failure by remember(path) { mutableStateOf<String?>(null) }

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
            text.isEmpty() && eof -> Text(Strings.fileEmpty)
            text.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(Strings.fileLoading)
            }
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

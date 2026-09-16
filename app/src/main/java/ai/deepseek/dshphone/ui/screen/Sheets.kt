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
                .padding(bottom = 24.dp),
        ) {
            store?.contextOccupancy?.let { occupancy ->
                SectionTitle(Strings.context)
                LabeledRow(Strings.contextUsed, tokenCount(occupancy.usedTokens.toInt()))
                LabeledRow(Strings.contextWindow, tokenCount(occupancy.contextWindow.toInt()))
                LabeledRow(Strings.contextOccupancy, "${occupancy.percent}%")
                store.projectionValue("contextBreakdown").obj?.let { breakdown ->
                    LabeledRow(Strings.contextSystem, tokenCount((breakdown["systemTokens"].dbl ?: 0.0).toInt()))
                    LabeledRow(Strings.contextTools, tokenCount((breakdown["toolsTokens"].dbl ?: 0.0).toInt()))
                    LabeledRow(Strings.contextMessages, tokenCount((breakdown["messageTokens"].dbl ?: 0.0).toInt()))
                }
            }
            store?.sessionStats?.let { stats ->
                SectionTitle(Strings.stats)
                LabeledRow(Strings.turns, stats.turns.toString())
                LabeledRow(Strings.steps, stats.steps.toString())
                LabeledRow(Strings.statsLlm, formatDuration(stats.llmMs))
                LabeledRow(Strings.statsTools, formatDuration(stats.toolMs))
                if (stats.ttftSteps > 0) {
                    LabeledRow(Strings.statsTtft, formatDuration(stats.ttftMs / stats.ttftSteps))
                }
                if (stats.decodeMs > 0) {
                    LabeledRow(
                        Strings.statsDecode,
                        formatTokensPerSecond(stats.decodeTokens / (stats.decodeMs / 1000)) + " tok/s",
                    )
                }
            }
            store?.modelDirectory?.current?.let { current ->
                SectionTitle(Strings.model)
                LabeledRow(current.provider, current.model)
                current.reasoningEffort?.let { LabeledRow(Strings.thinkingEffort, it) }
            }
            ListItem(
                modifier = Modifier.clickable {
                    model.compactContext(sessionId)
                    onDismiss()
                },
                headlineContent = { Text(Strings.compact) },
                leadingContent = { Icon(Icons.Default.UnfoldLess, null) },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LabeledRow(label: String, value: String) {
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

// —— 设置 ——————————————————————————————————————————————————

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(model: PhoneModel, onDismiss: () -> Unit, onScan: () -> Unit) {
    var renaming by remember { mutableStateOf<ServerProfile?>(null) }
    var removing by remember { mutableStateOf<ServerProfile?>(null) }
    var nameDraft by remember { mutableStateOf("") }
    val statusText = when (val connection = model.connection) {
        is Connection.Connected ->
            if (model.socketState == ai.deepseek.dshphone.net.WsConnectionState.RECONNECTING) Strings.reconnecting
            else Strings.statusConnected
        is Connection.Connecting -> Strings.statusConnecting
        is Connection.Idle -> Strings.statusIdle
        is Connection.Failed -> connection.message
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SectionTitle(Strings.servers)
            model.profiles.profiles.forEach { profile ->
                ListItem(
                    modifier = Modifier.clickable { model.connect(profile) },
                    headlineContent = { Text(profile.name) },
                    supportingContent = { Text(profile.relayHost) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (profile.id == model.profiles.currentId) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (model.connection is Connection.Connected) Color(0xFF2E7D32)
                                            else Color(0xFFE08600)
                                        )
                                )
                                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { nameDraft = profile.name; renaming = profile }) {
                                Icon(Icons.Default.Edit, Strings.serverRename, Modifier.size(18.dp))
                            }
                            IconButton(onClick = { removing = profile }) {
                                Icon(Icons.Default.Delete, Strings.serverRemove, Modifier.size(18.dp))
                            }
                        }
                    },
                )
            }
            Text(
                Strings.serversFooter,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            model.profiles.current?.let { profile ->
                SectionTitle(Strings.currentServer)
                LabeledRow(Strings.relay, profile.relayHost)
                LabeledRow(Strings.deviceId, profile.deviceId)
                LabeledRow(Strings.status, statusText)
                profile.lastConnectedAt?.let {
                    LabeledRow(
                        Strings.lastConnected,
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(it)),
                    )
                }
                LabeledRow(Strings.sessionsCount, model.listStore.sessions.size.toString())
            }
            ListItem(
                modifier = Modifier.clickable { onDismiss(); onScan() },
                headlineContent = { Text(Strings.rescan) },
                leadingContent = { Icon(Icons.Default.QrCodeScanner, null) },
            )
        }
    }
    removing?.let { profile ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(Strings.serverRemove) },
            text = { Text("${profile.name} · ${Strings.serverRemoveMessage}") },
            confirmButton = {
                TextButton(onClick = {
                    model.profiles.remove(profile.id)
                    removing = null
                }) { Text(Strings.remove) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(Strings.cancel) } },
        )
    }
    renaming?.let { profile ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(Strings.serverRename) },
            text = { OutlinedTextField(nameDraft, { nameDraft = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    model.profiles.rename(profile.id, nameDraft)
                    renaming = null
                }) { Text(Strings.submit) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(Strings.cancel) } },
        )
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
                        )
                    }
                }
            }
        }
    }
}

// —— 新建会话 ————————————————————————————————————————————————

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionSheet(model: PhoneModel, onDismiss: () -> Unit, onCreated: (SessionId) -> Unit) {
    val scope = rememberCoroutineScope()
    var workspaceId by remember { mutableStateOf<WorkspaceId?>(null) }
    var sessionId by remember { mutableStateOf<SessionId?>(null) }
    var preparing by remember { mutableStateOf(true) }
    var picking by remember { mutableStateOf(false) }
    var pickingPreset by remember { mutableStateOf(false) }

    suspend fun prepare(id: WorkspaceId?) {
        preparing = true
        workspaceId = id
        sessionId = model.createSession(id)
        preparing = false
    }

    LaunchedEffect(Unit) {
        model.loadAgentPresets()
        prepare(model.workspaceStore.workspaces.firstOrNull()?.workspaceId)
    }

    val workspacePath = model.workspaceStore.workspaces.firstOrNull { it.workspaceId == workspaceId }?.path
    val workspaceTitle = model.workspaceStore.workspaces.firstOrNull { it.workspaceId == workspaceId }?.title
        ?: Strings.noWorkspace
    val presetTitle = sessionId?.let { id -> model.sessions[id]?.agentPreset }?.let { presetId ->
        model.agentPresets.firstOrNull { it.id == presetId }?.let { agentPresetDisplayText(it).name } ?: presetId
    } ?: Strings.preset

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(Strings.newSession, Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
            if (preparing) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.size(36.dp),
                        MaterialTheme.colorScheme.outline)
                    Text(
                        workspacePath ?: model.lastErrorMessage ?: Strings.noWorkspace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { picking = true },
                            label = { Text(workspaceTitle, maxLines = 1) },
                            leadingIcon = { Icon(Icons.Default.Folder, null, Modifier.size(16.dp)) },
                        )
                        AssistChip(
                            onClick = { pickingPreset = true },
                            label = { Text(presetTitle, maxLines = 1) },
                            leadingIcon = { Icon(Icons.Default.Layers, null, Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            sessionId?.let { id ->
                ComposerBar(model, id) { created -> onCreated(created) }
            }
        }
    }

    if (picking) {
        ModalBottomSheet(onDismissRequest = { picking = false }) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                item {
                    ListItem(
                        modifier = Modifier.clickable {
                            picking = false
                            if (workspaceId != null) scope.launch { prepare(null) }
                        },
                        headlineContent = { Text(Strings.noWorkspace) },
                        trailingContent = {
                            if (workspaceId == null) Icon(Icons.Default.Check, null,
                                tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
                items(model.workspaceStore.workspaces, key = { it.workspaceId }) { workspace ->
                    ListItem(
                        modifier = Modifier.clickable {
                            picking = false
                            if (workspace.workspaceId != workspaceId) {
                                scope.launch { prepare(workspace.workspaceId) }
                            }
                        },
                        headlineContent = { Text(workspace.title) },
                        supportingContent = { Text(workspace.path, maxLines = 1,
                            overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            if (workspace.workspaceId == workspaceId) {
                                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                    )
                }
            }
        }
    }

    if (pickingPreset && sessionId != null) {
        val id = sessionId!!
        ModalBottomSheet(onDismissRequest = { pickingPreset = false }) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(model.agentPresets, key = { it.id }) { preset ->
                    val text = agentPresetDisplayText(preset)
                    val selected = model.sessions[id]?.agentPreset == preset.id
                    ListItem(
                        modifier = Modifier.clickable {
                            pickingPreset = false
                            if (!selected) {
                                scope.launch {
                                    if (model.selectAgentPreset(preset.id, id)) {
                                        model.sessions[id]?.agentPreset = preset.id
                                    }
                                }
                            }
                        },
                        headlineContent = { Text(agentPresetMenuLabel(preset)) },
                        supportingContent = text.description?.takeIf { it.isNotEmpty() }?.let {
                            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        },
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
    var goalDraft by remember { mutableStateOf("") }
    var editingGoal by remember { mutableStateOf(false) }

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
            SectionTitle(Strings.goal)
            val goal = store?.goal
            if (goal != null) {
                Text(goal.objective, Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium)
                LabeledRow(Strings.status, goal.phase)
                goal.blockedMessage?.let {
                    Text(it, Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFE08600))
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { model.toggleGoalPause(sessionId) }) {
                        Text(if (goal.phase == "paused") Strings.goalResume else Strings.goalPause)
                    }
                    TextButton(onClick = { model.clearGoal(sessionId) }) { Text(Strings.goalClear) }
                }
            } else if (editingGoal) {
                OutlinedTextField(
                    goalDraft, { goalDraft = it },
                    label = { Text(Strings.goalObjective) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                TextButton(
                    onClick = {
                        if (goalDraft.isNotBlank()) {
                            model.createGoal(goalDraft.trim(), null, sessionId)
                            goalDraft = ""
                            editingGoal = false
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text(Strings.submit) }
            } else {
                ListItem(
                    modifier = Modifier.clickable { editingGoal = true },
                    headlineContent = { Text(Strings.goalNew) },
                    leadingContent = { Icon(Icons.Default.Flag, null) },
                )
            }

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
    val selected = remember { mutableStateMapOf<String, MutableSet<String>>() }
    val custom = remember { mutableStateMapOf<String, String>() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 不再重复「提问」标题与 host 的「选择」小标题：问题正文本身就是标题，
            // 弹层已有拖拽把手标识层级。
            question.questions.forEach { item ->
                Text(item.question, style = MaterialTheme.typography.bodyLarge)
                item.detail?.takeIf { it.isNotEmpty() }?.let { MarkdownText(it) }
                item.options?.forEach { option ->
                    val display = splitRecommended(option.label)
                    val picked = selected[item.id]?.contains(option.label) == true
                    ListItem(
                        modifier = Modifier.clickable {
                            val current = selected.getOrPut(item.id) { mutableSetOf() }
                            if (item.multiSelect == true) {
                                if (!current.add(option.label)) current.remove(option.label)
                            } else {
                                val had = current.contains(option.label)
                                current.clear()
                                if (!had) current.add(option.label)
                            }
                            selected[item.id] = current.toMutableSet()
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
                    value = custom[item.id] ?: "",
                    onValueChange = { custom[item.id] = it },
                    label = { Text(Strings.answerCustom) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { model.cancelQuestion(question); onDismiss() },
                    modifier = Modifier.weight(1f),
                ) { Text(Strings.questionSkip) }
                Button(
                    onClick = {
                        val answers = question.questions.map { item ->
                            QuestionAnswer(
                                id = item.id,
                                selected = selected[item.id]?.toList() ?: emptyList(),
                                custom = custom[item.id]?.trim()?.takeIf { it.isNotEmpty() },
                            )
                        }
                        model.respondQuestion(question, answers)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(Strings.submit) }
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

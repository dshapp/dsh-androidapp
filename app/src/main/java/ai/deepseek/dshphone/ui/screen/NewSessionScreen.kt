package ai.deepseek.dshphone.ui.screen

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.net.WorkspaceId
import ai.deepseek.dshphone.state.PhoneModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 新建会话页。
 *
 * 做成整页而不是底部弹层：这页唯一的内容就是输入框，键盘一定会顶起来，而弹层自带一个
 * 独立窗口，`imePadding` 拿不到活动窗口的输入法 inset，输入框会被键盘压在下面；整页跟
 * 会话页走同一套 `adjustResize` + `imePadding`，键盘弹起时输入框自然抬到键盘之上。
 *
 * 页面不再放标题、大图标和工作区路径：新建会话页的任务就是让人马上开口，工作区与预设
 * 各留一颗胶囊贴在输入框上方就够了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen(model: PhoneModel, onBack: () -> Unit, onCreated: (SessionId) -> Unit) {
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
        // 默认工作区跟 Mac 端一个口径：上一次真正开出新会话的那个工作区，
        // 取不到才退回"最近更新过"的那个（见 PhoneModel.defaultNewSessionWorkspaceId）。
        prepare(model.defaultNewSessionWorkspaceId())
    }

    val workspaceTitle = model.workspaceStore.workspaces.firstOrNull { it.workspaceId == workspaceId }?.title
        ?: Strings.noWorkspace
    val presetTitle = sessionId?.let { id -> model.sessions[id]?.agentPreset }?.let { presetId ->
        model.agentPresets.firstOrNull { it.id == presetId }?.let { agentPresetDisplayText(it).name } ?: presetId
    } ?: Strings.preset

    Scaffold(
        // 标题位留空：这页不需要一个"新建会话"的大标题，只需要一条回得去的路。
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, Strings.close) }
                },
            )
        },
        bottomBar = {
            val id = sessionId
            if (id != null) {
                // 工作区/预设两颗胶囊跟输入框同属一块：一起被 imePadding 抬到键盘之上。
                ComposerBar(
                    model = model,
                    sessionId = id,
                    autoFocus = true,
                    header = {
                        // 工作区名字可能很长，横向可滚，别把预设那颗胶囊挤出屏幕。
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AssistChip(
                                onClick = { picking = true },
                                label = { Text(workspaceTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Default.Folder, null, Modifier.size(16.dp)) },
                            )
                            // alpha.2 removes the old mode flag; only usable preset options matter.
                            if (model.agentPresetPickerEnabled) {
                                AssistChip(
                                    onClick = { pickingPreset = true },
                                    label = { Text(presetTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingIcon = { Icon(Icons.Default.Layers, null, Modifier.size(16.dp)) },
                                )
                            }
                        }
                    },
                    onSubmitted = { created ->
                        // 只有真的发出了第一条消息才算"在这个工作区新建过会话"，
                        // 光是翻一下工作区不该改掉下次的默认值。
                        model.rememberNewSessionWorkspace(workspaceId)
                        onCreated(created)
                    },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                preparing -> CircularProgressIndicator()
                sessionId == null -> Column(
                    Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        model.lastErrorMessage ?: Strings.noWorkspace,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { picking = true }) { Text(Strings.workspace) }
                        TextButton(onClick = { scope.launch { prepare(workspaceId) } }) { Text(Strings.retry) }
                    }
                }
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
                            if (workspaceId == null) {
                                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
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
                        supportingContent = {
                            Text(workspace.path, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
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

    val presetSessionId = sessionId
    if (pickingPreset && presetSessionId != null) {
        ModalBottomSheet(onDismissRequest = { pickingPreset = false }) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(model.agentPresets, key = { it.id }) { preset ->
                    val text = agentPresetDisplayText(preset)
                    val selected = model.sessions[presetSessionId]?.agentPreset == preset.id
                    ListItem(
                        modifier = Modifier.clickable {
                            pickingPreset = false
                            if (!selected) {
                                scope.launch {
                                    if (model.selectAgentPreset(preset.id, presetSessionId)) {
                                        model.sessions[presetSessionId]?.agentPreset = preset.id
                                    }
                                }
                            }
                        },
                        headlineContent = { Text(agentPresetMenuLabel(preset)) },
                        supportingContent = text.description?.takeIf { it.isNotEmpty() }?.let {
                            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        },
                        trailingContent = {
                            if (selected) {
                                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                    )
                }
            }
        }
    }
}

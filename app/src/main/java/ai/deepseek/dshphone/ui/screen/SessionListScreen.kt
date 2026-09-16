package ai.deepseek.dshphone.ui.screen

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.state.Connection
import ai.deepseek.dshphone.state.PhoneModel
import ai.deepseek.dshphone.ui.theme.Dimens
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 60

private data class SessionGroup(
    val id: String,
    val title: String,
    val sessions: List<SessionSummary>,
)

/** 会话列表：搜索 + 可折叠分组 + 视图菜单（分组/排序）。 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(
    model: PhoneModel,
    onOpenSession: (SessionId) -> Unit,
    onOpenServers: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("dsh_view", android.content.Context.MODE_PRIVATE) }
    var groupBy by remember { mutableStateOf(prefs.getString("groupBy", "workspace")!!) }
    var orderBy by remember { mutableStateOf(prefs.getString("orderBy", "updated")!!) }
    var query by remember { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var pages by remember { mutableStateOf(mapOf<String, Int>()) }
    var showArchived by remember { mutableStateOf(false) }
    var showNewSession by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    var viewMenu by remember { mutableStateOf(false) }
    // 搜索默认只在顶栏留一个图标，点开才把标题位换成输入框（对齐会话详情页的「会话内查找」）。
    var finding by remember { mutableStateOf(false) }
    val findFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    fun persistView() {
        prefs.edit().putString("groupBy", groupBy).putString("orderBy", orderBy).apply()
    }

    // 退出搜索统一走这里：先收键盘再拆输入框，顺手把查询和命中清干净。
    val exitFind = {
        focusManager.clearFocus()
        finding = false
        query = ""
        model.search("")
    }

    // 系统返回键：搜索态先退出搜索，别直接退出列表页。
    BackHandler(enabled = finding) { exitFind() }

    LaunchedEffect(finding) { if (finding) findFocus.requestFocus() }

    val searching = finding && query.isNotBlank()
    val listed = model.listStore.sessions.filter { !it.blank }
    val erroredIds = model.sessions.filterValues { it.unreadError }.keys

    fun ordered(items: List<SessionSummary>): List<SessionSummary> = when (orderBy) {
        "manual" -> model.listStore.manualOrder(items)
        "status" -> model.listStore.statusOrder(items, erroredIds)
        else -> items.sortedByDescending { it.updatedAt }
    }

    val groups: List<SessionGroup> = remember(listed, groupBy, orderBy, model.workspaceStore.workspaces,
        model.listStore.pinnedSessionOrder, erroredIds) {
        val result = mutableListOf<SessionGroup>()
        val pinnedIds = model.listStore.pinnedSessionIds
        val pinned = model.listStore.pinnedOrder(listed)
        if (pinned.isNotEmpty()) {
            result.add(SessionGroup("pinned", Strings.pinned, pinned))
        }
        val rest = listed.filterNot { pinnedIds.contains(it.sessionId) }
        if (groupBy == "flat") {
            result.add(SessionGroup("all", Strings.allSessions, ordered(rest)))
        } else {
            val owner = mutableMapOf<SessionId, String>()
            for (workspace in model.workspaceStore.workspaces) {
                for (id in workspace.sessionIds) owner[id] = workspace.workspaceId
            }
            val grouped = mutableMapOf<String, MutableList<SessionSummary>>()
            val loose = mutableListOf<SessionSummary>()
            for (summary in rest) {
                val id = owner[summary.sessionId]
                if (id != null) grouped.getOrPut(id) { mutableListOf() }.add(summary) else loose.add(summary)
            }
            for (workspace in model.workspaceStore.workspaces) {
                val items = grouped[workspace.workspaceId] ?: continue
                if (items.isEmpty()) continue
                result.add(SessionGroup(workspace.workspaceId, workspace.title, ordered(items)))
            }
            if (loose.isNotEmpty()) result.add(SessionGroup("loose", Strings.ungrouped, ordered(loose)))
        }
        result
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (finding) {
                        // 搜索态按原生「标题栏变搜索栏」的做法：标题位只放一行无边框输入，
                        // 清除挪到 actions；M3 的 TextField 最小 56dp，塞进标题栏会被挤扁。
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    Strings.searchSessions,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it; model.search(it) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                // 结果是边打边搜的，回车只用来收键盘，让出屏幕看命中行。
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(findFocus),
                            )
                        }
                    } else {
                        Text(
                            model.profiles.current?.name ?: Strings.appName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    if (finding) {
                        IconButton(onClick = { exitFind() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        }
                    } else {
                        // 状态点直接进服务页：机器切换、设置、添加新服务都在那一页里。
                        IconButton(onClick = onOpenServers) {
                            ConnectionDot(model)
                        }
                    }
                },
                actions = {
                    if (finding) {
                        // 清除只清文字并把光标留在框里，退出搜索交给返回键，二者不混用。
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; model.search(""); findFocus.requestFocus() }) {
                                Icon(Icons.Default.Close, Strings.close)
                            }
                        }
                        return@TopAppBar
                    }
                    IconButton(onClick = { finding = true }) {
                        Icon(Icons.Default.Search, Strings.searchSessions)
                    }
                    Box {
                        IconButton(onClick = { viewMenu = true }) {
                            Icon(Icons.Default.FilterList, Strings.viewOptions)
                        }
                        DropdownMenu(viewMenu, { viewMenu = false }) {
                            MenuSectionLabel(Strings.groupBy)
                            ChoiceItem(Strings.groupByWorkspace, Icons.Default.Folder, groupBy == "workspace") {
                                groupBy = "workspace"; persistView(); viewMenu = false
                            }
                            ChoiceItem(Strings.groupByFlat, Icons.AutoMirrored.Filled.List, groupBy == "flat") {
                                groupBy = "flat"; persistView(); viewMenu = false
                            }
                            HorizontalDivider()
                            MenuSectionLabel(Strings.orderBy)
                            ChoiceItem(Strings.orderUpdated, Icons.Default.Schedule, orderBy == "updated") {
                                orderBy = "updated"; persistView(); viewMenu = false
                            }
                            ChoiceItem(Strings.orderStatus, Icons.Default.Bolt, orderBy == "status") {
                                orderBy = "status"; persistView(); viewMenu = false
                            }
                            ChoiceItem(Strings.orderManual, Icons.Default.DragHandle, orderBy == "manual") {
                                orderBy = "manual"; persistView(); viewMenu = false
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(Strings.archived) },
                                leadingIcon = { Icon(Icons.Default.Inventory2, null) },
                                onClick = { viewMenu = false; showArchived = true },
                            )
                        }
                    }
                    // 加号比「编辑便签」更直白地表示新建。
                    IconButton(onClick = { showNewSession = true }) {
                        Icon(Icons.Default.Add, Strings.newSession)
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = model.refreshing,
                onRefresh = { model.pullToRefresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (searching) {
                        val byId = model.listStore.sessionsById
                        val hits = (model.searchHits ?: emptyList()).mapNotNull { byId[it.sessionId] }
                        item { SectionHeader("${Strings.searchResults} (${hits.size})", null, false) {} }
                        items(hits, key = { it.sessionId }) { summary ->
                            SessionRow(
                                model, summary, onOpenSession,
                                onRename = { renameText = titleOf(model, summary); renaming = summary },
                            )
                        }
                    } else {
                        groups.forEach { group ->
                            item(key = "h-${group.id}") {
                                SectionHeader(
                                    group.title, group.sessions.size,
                                    collapsed.contains(group.id),
                                ) {
                                    collapsed = if (collapsed.contains(group.id)) collapsed - group.id
                                    else collapsed + group.id
                                }
                            }
                            if (!collapsed.contains(group.id)) {
                                val limit = PAGE_SIZE * (pages[group.id] ?: 1)
                                val shown = group.sessions.take(limit)
                                items(shown, key = { it.sessionId }) { summary ->
                                    SessionRow(
                                        model, summary, onOpenSession,
                                        onRename = { renameText = titleOf(model, summary); renaming = summary },
                                    )
                                }
                                if (shown.size < group.sessions.size) {
                                    item(key = "m-${group.id}") {
                                        TextButton(onClick = {
                                            pages = pages + (group.id to (pages[group.id] ?: 1) + 1)
                                        }, modifier = Modifier.padding(start = 12.dp)) {
                                            Text(fmt(Strings.showMore, group.sessions.size - shown.size))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxSize()) { StatusOverlay(model, searching, query) }
            }
        }
    }

    if (showArchived) ArchivedSheet(model, { showArchived = false }, onOpenSession)
    if (showNewSession) {
        NewSessionSheet(model, onDismiss = { showNewSession = false }, onCreated = {
            showNewSession = false
            onOpenSession(it)
        })
    }
    renaming?.let { target ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(Strings.rename) },
            text = { OutlinedTextField(renameText, { renameText = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    model.rename(target.sessionId, renameText)
                    renaming = null
                }) { Text(Strings.submit) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(Strings.cancel) } },
        )
    }
}

private fun titleOf(model: PhoneModel, summary: SessionSummary): String =
    sessionDisplayTitle(summary, model.sessions[summary.sessionId]?.title)

/** 连接状态的颜色与说明：绿色已连接、黄色连接中/重连中、红色断开。 */
@Composable
internal fun connectionTone(model: PhoneModel): Pair<Color, String> {
    val reconnecting = model.socketState == ai.deepseek.dshphone.net.WsConnectionState.RECONNECTING
    return when (val connection = model.connection) {
        is Connection.Connected ->
            if (reconnecting) Color(0xFFE08600) to Strings.reconnecting
            else Color(0xFF2E9E4F) to Strings.statusConnected
        is Connection.Connecting -> Color(0xFFE08600) to Strings.statusConnecting
        is Connection.Failed -> MaterialTheme.colorScheme.error to connection.message
        is Connection.Idle -> MaterialTheme.colorScheme.error to Strings.statusIdle
    }
}

/** 连接状态点，颜色口径与 [connectionTone] 共用一份。 */
@Composable
internal fun ConnectionDot(model: PhoneModel) {
    val (color, label) = connectionTone(model)
    val description = "${Strings.status}: $label"
    Box(
        Modifier
            .size(12.dp)
            .background(color, CircleShape)
            .semantics { contentDescription = description },
    )
}

@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ChoiceItem(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(title) },
        leadingIcon = { Icon(if (selected) Icons.Default.Check else icon, null) },
        onClick = onClick,
    )
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int?,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 标题与计数是一簇，由它吃掉中间全部宽度，箭头才会稳定贴在右边缘。
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        Text(
            title,
            // 分组标题用更淡的中性灰，和会话行标题（onSurface）拉开层级。
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        count?.let {
            Text(it.toString(), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline)
        }
        }
        val chevronTurn by animateFloatAsState(
            targetValue = if (collapsed) -90f else 0f,
            label = "sectionChevron",
        )
        Icon(
            Icons.Default.ExpandMore,
            null,
            Modifier.rotate(chevronTurn).size(20.dp),
            MaterialTheme.colorScheme.outline,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    model: PhoneModel,
    summary: SessionSummary,
    onOpen: (SessionId) -> Unit,
    onRename: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val pinned = model.listStore.pinnedSessionIds.contains(summary.sessionId)
    val haptics = LocalHapticFeedback.current

    // 行上只有两种手势：点击进入会话、长按弹出操作菜单。左滑不再承载任何动作，避免误触。
    Box(Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.combinedClickable(
                onClick = { onOpen(summary.sessionId) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menu = true
                },
            ),
            headlineContent = {
                Text(titleOf(model, summary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingContent = { StatusGlyph(model, summary) },
            trailingContent = { TrailingBadge(model, summary) },
        )
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                text = { Text(Strings.fork) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.CallSplit, null) },
                onClick = {
                    menu = false
                    scope.launch { model.fork(summary.sessionId)?.let(onOpen) }
                },
            )
            DropdownMenuItem(
                text = { Text(if (pinned) Strings.unpin else Strings.pin) },
                leadingIcon = { Icon(Icons.Default.PushPin, null) },
                onClick = { menu = false; model.listStore.setPinned(summary.sessionId, !pinned) },
            )
            DropdownMenuItem(
                text = { Text(Strings.rename) },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { menu = false; onRename() },
            )
            // 归档原先只挂在左滑动作区；左滑撤掉后补进菜单，否则这个动作会彻底消失。
            DropdownMenuItem(
                text = { Text(Strings.archive) },
                leadingIcon = { Icon(Icons.Default.Inventory2, null) },
                onClick = { menu = false; model.archive(summary.sessionId) },
            )
        }
    }
}

@Composable
private fun StatusGlyph(model: PhoneModel, summary: SessionSummary) {
    val store = model.sessions[summary.sessionId]
    when {
        summary.running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        store?.pendingApproval != null || store?.pendingQuestion != null ->
            Icon(Icons.Default.PanTool, null, Modifier.size(16.dp), Color(0xFFE08600))
        store?.unreadError == true ->
            Icon(Icons.Default.Error, null, Modifier.size(16.dp), MaterialTheme.colorScheme.error)
        model.listStore.pinnedSessionIds.contains(summary.sessionId) ->
            Icon(Icons.Default.PushPin, null, Modifier.size(16.dp), MaterialTheme.colorScheme.outline)
        else -> Icon(
            Icons.AutoMirrored.Filled.Chat, null, Modifier.size(16.dp), MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun TrailingBadge(model: PhoneModel, summary: SessionSummary) {
    val awaiting = if (model.listStore.awaitingUserSessionIds.contains(summary.sessionId)) {
        model.listStore.awaitingUserKinds[summary.sessionId] ?: SessionAwaitingKind.APPROVAL
    } else null
    when (
        val trailing = SessionRowTrailing.resolve(
            awaitingUser = awaiting,
            isSelected = false,
            isArchived = false,
            isBlank = summary.blank,
            hasDraft = model.listStore.draftSessionIds.contains(summary.sessionId),
            updatedAt = summary.updatedAt,
        )
    ) {
        is SessionRowTrailing.AwaitingUser -> Badge(trailing.kind.badge)
        SessionRowTrailing.Draft -> Badge(Strings.sessionHasDraft)
        is SessionRowTrailing.Time -> Text(
            relativeTime(trailing.updatedAt),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        SessionRowTrailing.None -> Unit
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        Modifier
            .background(Color(0x22E08600), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFFE08600),
        maxLines = 1,
    )
}

@Composable
private fun BoxScope.StatusOverlay(model: PhoneModel, searching: Boolean, query: String) {
    val connection = model.connection
    when {
        connection is Connection.Failed -> ToastNotice(
            Icons.Default.WarningAmber, Strings.disconnected, connection.message,
        )
        connection is Connection.Connecting && model.listStore.sessions.isEmpty() ->
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        searching && model.searchHits?.isEmpty() == true ->
            EmptyState(Icons.Default.SearchOff, Strings.searchNoResults, query)
        !searching && model.listStore.sessions.isEmpty() && connection is Connection.Connected ->
            EmptyState(Icons.AutoMirrored.Filled.Chat, Strings.noSessions, null)
    }
}

/**
 * 浮在列表之上的提示（未连接等）：用 Toast 的样式——不透明的深色圆角胶囊 + 投影，
 * 避免透明背景跟下方的会话行糊在一起。
 */
@Composable
private fun BoxScope.ToastNotice(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String?,
) {
    Surface(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(32.dp)
            .widthIn(max = 360.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

@Composable
fun BoxScope.EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String?,
) {
    Column(
        Modifier
            .align(Alignment.Center)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(40.dp), MaterialTheme.colorScheme.outline)
        Text(title, style = MaterialTheme.typography.titleMedium)
        description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

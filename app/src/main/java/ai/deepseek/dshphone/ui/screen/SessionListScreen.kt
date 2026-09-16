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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 60

private data class SessionGroup(
    val id: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector?,
    val sessions: List<SessionSummary>,
)

/** 会话列表：搜索 + 可折叠分组 + 视图菜单（分组/排序）。 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(
    model: PhoneModel,
    onOpenSession: (SessionId) -> Unit,
    onScan: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("dsh_view", android.content.Context.MODE_PRIVATE) }
    var groupBy by remember { mutableStateOf(prefs.getString("groupBy", "workspace")!!) }
    var orderBy by remember { mutableStateOf(prefs.getString("orderBy", "updated")!!) }
    var query by remember { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var pages by remember { mutableStateOf(mapOf<String, Int>()) }
    var showSettings by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var showNewSession by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    var serverMenu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }

    fun persistView() {
        prefs.edit().putString("groupBy", groupBy).putString("orderBy", orderBy).apply()
    }

    val searching = query.isNotBlank()
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
            result.add(SessionGroup("pinned", Strings.pinned, Icons.Default.PushPin, pinned))
        }
        val rest = listed.filterNot { pinnedIds.contains(it.sessionId) }
        if (groupBy == "flat") {
            result.add(SessionGroup("all", Strings.allSessions, null, ordered(rest)))
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
                result.add(SessionGroup(workspace.workspaceId, workspace.title, Icons.Default.Folder, ordered(items)))
            }
            if (loose.isNotEmpty()) result.add(SessionGroup("loose", Strings.ungrouped, null, ordered(loose)))
        }
        result
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        model.profiles.current?.name ?: Strings.appName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    Box {
                        IconButton(onClick = { serverMenu = true }) {
                            Icon(Icons.Default.Computer, Strings.servers)
                        }
                        DropdownMenu(serverMenu, { serverMenu = false }) {
                            model.profiles.profiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = { Text(profile.name) },
                                    leadingIcon = {
                                        Icon(
                                            if (profile.id == model.profiles.currentId) Icons.Default.Check
                                            else Icons.Default.Computer,
                                            null,
                                        )
                                    },
                                    onClick = { serverMenu = false; model.connect(profile) },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(Strings.scanQR) },
                                leadingIcon = { Icon(Icons.Default.QrCodeScanner, null) },
                                onClick = { serverMenu = false; onScan() },
                            )
                            DropdownMenuItem(
                                text = { Text(Strings.settings) },
                                leadingIcon = { Icon(Icons.Default.Settings, null) },
                                onClick = { serverMenu = false; showSettings = true },
                            )
                        }
                    }
                },
                actions = {
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
            TextField(
                value = query,
                onValueChange = { query = it; model.search(it) },
                placeholder = { Text(Strings.searchSessions) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; model.search("") }) { Icon(Icons.Default.Close, null) }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            PullToRefreshBox(
                isRefreshing = model.refreshing,
                onRefresh = { model.pullToRefresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (searching) {
                        val byId = model.listStore.sessionsById
                        val hits = (model.searchHits ?: emptyList()).mapNotNull { byId[it.sessionId] }
                        item { SectionHeader("${Strings.searchResults} (${hits.size})", null, null, false) {} }
                        items(hits, key = { it.sessionId }) { summary ->
                            SessionRow(model, summary, onOpenSession, onRename = {
                                renameText = titleOf(model, summary); renaming = summary
                            })
                        }
                    } else {
                        groups.forEach { group ->
                            item(key = "h-${group.id}") {
                                SectionHeader(
                                    group.title, group.icon, group.sessions.size,
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
                                    SessionRow(model, summary, onOpenSession, onRename = {
                                        renameText = titleOf(model, summary); renaming = summary
                                    })
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

    if (showSettings) SettingsSheet(model, { showSettings = false }, onScan)
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
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
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
        icon?.let { Icon(it, null, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant) }
        // 标题与计数是一簇，由它吃掉中间全部宽度，箭头才会稳定贴在右边缘。
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            MaterialTheme.colorScheme.onSurfaceVariant,
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val pinned = model.listStore.pinnedSessionIds.contains(summary.sessionId)
    val haptics = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    model.listStore.setPinned(summary.sessionId, !pinned)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    model.archive(summary.sessionId)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    true
                }
                else -> false
            }
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val start = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (start) Color(0xFFE08600) else MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(if (start) Icons.Default.PushPin else Icons.Default.Inventory2, null)
            }
        },
    ) {
        Box {
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
                summary.cwd?.let { cwd ->
                    DropdownMenuItem(
                        text = { Text(Strings.openOnMac) },
                        leadingIcon = { Icon(Icons.Default.DesktopWindows, null) },
                        onClick = { menu = false; model.openOnHost(cwd) },
                    )
                }
                DropdownMenuItem(
                    text = { Text(Strings.export) },
                    leadingIcon = { Icon(Icons.Default.Share, null) },
                    onClick = {
                        menu = false
                        model.exportSessionLog(summary.sessionId)?.let { file ->
                            runCatching {
                                val uri = FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", file,
                                )
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, Strings.export))
                            }
                        }
                    },
                )
            }
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
        connection is Connection.Failed -> EmptyState(
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

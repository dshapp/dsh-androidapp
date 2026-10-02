package ai.deepseek.dshphone.ui.screen

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.net.WorkspaceId
import ai.deepseek.dshphone.state.Connection
import ai.deepseek.dshphone.state.PhoneModel
import ai.deepseek.dshphone.ui.theme.Dimens
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 60

private data class SessionGroup(
    val id: String,
    val title: String,
    val sessions: List<SessionSummary>,
    /**
     * 只有真实工作区分组才带 id，也只有它们能拖动换位——与 Mac 边栏一致：
     * 置顶条是本地的一撮会话、「其他」桶根本不是工作区，两者都不参与注册表排序。
     */
    val workspaceId: WorkspaceId? = null,
)

/** 会话列表：搜索 + 可折叠分组 + 视图菜单（分组/排序）。 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(
    model: PhoneModel,
    onOpenSession: (SessionId) -> Unit,
    onOpenSettings: () -> Unit,
    onNewSession: () -> Unit,
    onOpenArchived: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("dsh_view", android.content.Context.MODE_PRIVATE) }
    var groupBy by remember { mutableStateOf(prefs.getString("groupBy", "workspace")!!) }
    var orderBy by remember { mutableStateOf(prefs.getString("orderBy", "updated")!!) }
    var query by remember { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var pages by remember { mutableStateOf(mapOf<String, Int>()) }
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
    }

    // 系统返回键：搜索态先退出搜索，别直接退出列表页。
    BackHandler(enabled = finding) { exitFind() }

    LaunchedEffect(finding) { if (finding) findFocus.requestFocus() }

    val searching = finding && query.isNotBlank()
    // 与 Mac 边栏的 sessionVisible 同一口径：
    // - 空白会话是"新建会话"的占位，手机上没有这个页面，不该占一行；
    // - origin == "subagent" 的会话是某个会话派生出来的子智能体，它只在父会话的
    //   子智能体面板里露面，列成顶层会话会把一次委派炸成一堆看不懂的条目。
    //   注意只挡 subagent 这一种来源：普通 fork 出来的会话仍是独立会话，照常列出。
    // 名单只在会话清单变化时重建。原来每次重组都新建一个 List，下游以它为 key 的 remember
    // 全部失效——输入一个字就要把 1500 多条会话重新筛一遍、再分组排序一遍。
    // 归档这一道闸必须加在渲染口径里，而不是靠归档时把行从名册摘掉：主机 \`session/list\`
    // 会把已归档的会话带回来，只摘行的话每次刷新都会重现一次（Mac 边栏同样按
    // \`archivedSessionIds\` 在渲染时过滤）。两个 store 的归档集合取并集，与归档页同源。
    val listed = remember(
        model.listStore.sessions,
        model.workspaceStore.archivedSessionIds,
        model.listStore.archivedSessionIds,
    ) {
        listSessionsVisible(
            model.listStore.sessions,
            (model.workspaceStore.archivedSessionIds + model.listStore.archivedSessionIds).toSet(),
        )
    }
    val erroredIds = model.sessions.filterValues { it.unreadError }.keys

    // 搜索命中在进入列表前先算好：列表主体与浮层空状态读同一份结果，两处各算一次迟早会漂移。
    //
    // 主机的内容搜索是可选能力（`openAt: never` 的部署直接拒绝 `session/search`），
    // 所以命中有两个来源、合并后才完整：已经在列表里的标题/工作区名本地匹配，加上主机
    // 返回的内容命中。与 web `deriveSearchResults`、Mac 边栏同一口径。
    //
    // 标题只匹配会话自己的 [sessionStoredTitle]（实时标题 → 投影标题；未命名为空，0.2.0 起
    // 不再回落到 cwd 目录名或 sessionId），工作区名与行的灰色前缀同源，本地臂按 updatedAt 新到旧、同刻按 id 排。
    // 主机命中只认「属于当前查询」的那一页；换查询到答复落地之前都是 pending。
    //
    // 只在查询或名单变化时重建，查找表提到循环外各取一次：`workspaceTitleBySession` 是每次
    // 访问都重建整张表的计算属性，放进逐会话循环会退化成 O(n²)。实时标题包在 derivedStateOf
    // 里读：打开过的会话改了名，命中随之更新，而不必让整屏跟着重组。
    val normalizedSearchQuery = query.trim()
    val searchPending = searching && model.searchQuery != normalizedSearchQuery
    val remoteForQuery =
        if (searchPending) SessionSearchValue() else model.searchResult ?: SessionSearchValue()
    val searchRows: SessionSearchRows = remember(
        searching, normalizedSearchQuery, listed, remoteForQuery,
        model.workspaceStore.workspaces,
    ) {
        derivedStateOf {
            if (!searching) return@derivedStateOf SessionSearchRows()
            val workspaceTitles = model.workspaceStore.workspaceTitleBySession
            val localMatches = listed
                .filter { summary ->
                    sessionMatchesSearch(
                        sessionStoredTitle(summary, model.sessions[summary.sessionId]?.title),
                        sessionWorkspaceName(workspaceTitles[summary.sessionId], summary.cwd),
                        normalizedSearchQuery,
                    )
                }
                .sortedWith(compareByDescending<SessionSummary> { it.updatedAt }.thenBy { it.sessionId })
            val visibleById =
                if (remoteForQuery.items.isEmpty()) emptyMap()
                else listed.associateBy { it.sessionId }
            mergeSessionSearchResults(localMatches, remoteForQuery, visibleById)
        }
    }.value

    // 本地命中随输入即时更新；主机那一臂由 [PhoneModel.search] 防抖 250ms 再发。
    LaunchedEffect(normalizedSearchQuery) { model.search(normalizedSearchQuery) }

    fun ordered(items: List<SessionSummary>): List<SessionSummary> = when (orderBy) {
        "manual" -> model.listStore.manualOrder(items)
        "status" -> model.listStore.statusOrder(items, erroredIds)
        else -> items.sortedByDescending { it.updatedAt }
    }

    // 搜索态渲染的是命中列表，这份分组一行都用不上；不跳过的话，每个字都要把整份名单
    // 重新分组、排序一遍，白烧一帧。
    val groups: List<SessionGroup> = if (searching) emptyList() else remember(
        listed, groupBy, orderBy, model.workspaceStore.workspaces,
        model.workspaceStore.pinnedSessionIds, erroredIds,
    ) {
        val result = mutableListOf<SessionGroup>()
        val pinnedIds = model.workspaceStore.pinnedSessionSet
        val pinned = model.workspaceStore.pinnedOrder(listed)
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
                result.add(
                    SessionGroup(
                        workspace.workspaceId, workspace.title, ordered(items),
                        workspaceId = workspace.workspaceId,
                    )
                )
            }
            if (loose.isNotEmpty()) result.add(SessionGroup("loose", Strings.ungrouped, ordered(loose)))
        }
        result
    }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val groupDrag = remember(model, listState) { GroupDragState(model, listState) }
    // 拖动过程里回调要按「当前渲染出来的分组顺序」找落点，所以每次组装完分组都同步一次。
    SideEffect { groupDrag.movableIds = groups.mapNotNull { it.workspaceId } }
    // 手指贴到上下边缘时自动滚动：拖的是分组表头，越过一屏之外的分组全靠它。
    LaunchedEffect(groupDrag.dragging) {
        if (!groupDrag.dragging) return@LaunchedEffect
        // 等收起会话行的那次重排落地（一帧组合、一帧布局），再把表头对回手指底下。
        repeat(2) { withFrameNanos { } }
        groupDrag.anchorAfterCollapse()
        val edge = with(density) { 72.dp.toPx() }
        val step = with(density) { 6.dp.toPx() }
        while (groupDrag.dragging) {
            withFrameNanos { }
            val direction = groupDrag.autoScrollDirection(edge)
            if (direction != 0 && groupDrag.autoScroll(direction * step)) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }
    // 搜索态、扁平视图下没有工作区分组可排，拖到一半切过去就把这次拖动收掉。
    LaunchedEffect(searching, groupBy) {
        if (searching || groupBy != "workspace") groupDrag.cancel()
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
                                onValueChange = { query = sanitizeSessionSearchQuery(it) },
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
                        // 状态点直接进设置页：机器切换、语言与主题、添加新服务都在那一页里。
                        IconButton(onClick = onOpenSettings) {
                            ConnectionDot(model)
                        }
                    }
                },
                actions = {
                    if (finding) {
                        // 清除只清文字并把光标留在框里，退出搜索交给返回键，二者不混用。
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; findFocus.requestFocus() }) {
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
                        }
                    }
                    // 加号比「编辑便签」更直白地表示新建。
                    IconButton(onClick = onNewSession) {
                        Icon(Icons.Default.Add, Strings.newSession)
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // weight 而不是 fillMaxSize：底下那一行归档入口要常驻在可视底部，
            // 不能被一个吃满高度的兄弟把它挤出屏幕（对齐 Mac 侧栏注释「固定在这一列底部」）。
            PullToRefreshBox(
                isRefreshing = model.refreshing,
                onRefresh = { model.pullToRefresh() },
                modifier = Modifier.weight(1f),
            ) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    if (searching) {
                        val hits = searchRows.sessions
                        item { SectionHeader("${Strings.searchResults} (${hits.size})", null, false) {} }
                        items(hits, key = { it.sessionId }) { summary ->
                            SessionRow(
                                model, summary, onOpenSession,
                                onRename = { renameText = sessionStoredTitle(summary, model.sessions[summary.sessionId]?.title); renaming = summary },
                            )
                        }
                        // 主机内容命中还没回来：本地命中照常先出，末尾留一个进度行（web 的骨架行）。
                        if (searchPending) {
                            item(key = "search-pending") {
                                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                        if (searchRows.hasMore) {
                            item(key = "search-has-more") {
                                SearchNotice(fmt(Strings.searchHasMore, SESSION_SEARCH_RESULT_LIMIT))
                            }
                        }
                    } else {
                        groups.forEach { group ->
                            item(key = "h-${group.id}") {
                                GroupHeader(group, collapsed.contains(group.id), groupDrag) {
                                    collapsed = if (collapsed.contains(group.id)) collapsed - group.id
                                    else collapsed + group.id
                                }
                            }
                            // 拖动分组时整张列表先收成一排分组行：手指只在几行之间移动就能换位，
                            // 不必跨过一整组会话；松手复原。
                            // 折叠的分组仍然列出正在运行的会话（对齐 web/Mac 侧栏）：
                            // 折起来是为了收起闲置的会话，不能把正在干活的也藏掉。
                            if (!groupDrag.dragging && collapsed.contains(group.id)) {
                                items(group.sessions.filter { it.running }, key = { it.sessionId }) { summary ->
                                    SessionRow(
                                        model, summary, onOpenSession,
                                        onRename = { renameText = sessionStoredTitle(summary, model.sessions[summary.sessionId]?.title); renaming = summary },
                                    )
                                }
                            }
                            if (!groupDrag.dragging && !collapsed.contains(group.id)) {
                                val limit = PAGE_SIZE * (pages[group.id] ?: 1)
                                val shown = group.sessions.take(limit)
                                items(shown, key = { it.sessionId }) { summary ->
                                    SessionRow(
                                        model, summary, onOpenSession,
                                        onRename = { renameText = sessionStoredTitle(summary, model.sessions[summary.sessionId]?.title); renaming = summary },
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
                Box(Modifier.fillMaxSize()) {
                    StatusOverlay(
                        model, searching, query,
                        searchEmpty = searchRows.sessions.isEmpty() && !searchPending,
                        listEmpty = listed.isEmpty(),
                    )
                }
            }
            // 会话列表最下面：归档会话已经在 `listed` 里被滤掉了，这里是它们唯一的入口，
            // 所以固定在这一列底部（不随列表滚动），而不是折进视图选项菜单——对齐 Mac 侧栏。
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable { onOpenArchived() },
                leadingContent = { Icon(Icons.Default.Inventory2, null) },
                headlineContent = { Text(Strings.archivedSessions) },
            )
        }
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

private const val GROUP_HEADER_KEY_PREFIX = "h-"

private fun LazyListLayoutInfo.headerItem(workspaceId: WorkspaceId): LazyListItemInfo? =
    visibleItemsInfo.firstOrNull { it.key == GROUP_HEADER_KEY_PREFIX + workspaceId }

/**
 * 分组表头长按拖动换位的过程状态。
 *
 * 落点的表达跟 Mac 边栏一模一样：不是「第几位」，而是「插到哪个工作区之前」，空锚点表示
 * 挪到末尾（Mac 的 `moveWorkspace(_:before:)` / 主机的 `workspace/insertBefore`）。列表
 * 里只渲染有会话的工作区，下标是渲染序、跟注册表序对不上，锚点用 id 才不会挪错位置。
 *
 * Mac 靠鼠标悬停在表头上下边缘画插入线来提示落点；手机没有悬停，所以这里改成边拖边预览：
 * 拖过半格就先把本地注册表的顺序改掉，用户直接看到结果。预览纯本地，松手时先把顺序退回按下
 * 那一刻，再走 [PhoneModel.moveWorkspace] 的「乐观移动 + 主机落定 / 失败回滚」——真正的状态
 * 迁移只保留一份实现，请求也只发一次。
 */
@Stable
private class GroupDragState(
    private val model: PhoneModel,
    private val listState: LazyListState,
) {
    /** 当前渲染出来、可以换位的工作区 id，渲染序即注册表序。 */
    var movableIds: List<WorkspaceId> = emptyList()

    var draggingId by mutableStateOf<WorkspaceId?>(null)
        private set

    /** 被拖表头按下时在视口里的位置，与此后手指累计的位移；两者之和是它此刻该待的地方。 */
    private var anchorOffset by mutableFloatStateOf(0f)
    private var dragged by mutableFloatStateOf(0f)
    private var ghostHeight = 0
    private val ghostTop: Float get() = anchorOffset + dragged
    /** 按下那一刻的工作区顺序，用来在松手/取消时把预览退回去。 */
    private var baseline: List<WorkspaceId> = emptyList()

    val dragging: Boolean get() = draggingId != null

    /**
     * 被拖表头要画在手指底下需要的位移：期望位置减去布局实际给它的位置。
     * 预览换位会让布局位置跳到新槽位，这个差值随之缩小，表头因此稳稳跟着手指。
     */
    fun translationFor(workspaceId: WorkspaceId): Float {
        if (workspaceId != draggingId) return 0f
        val current = listState.layoutInfo.headerItem(workspaceId) ?: return 0f
        return ghostTop - current.offset
    }

    /** 开始拖动；只有一个分组时没有换位可言，直接不接这一次长按。 */
    fun start(workspaceId: WorkspaceId): Boolean {
        if (movableIds.size < 2) return false
        val item = listState.layoutInfo.headerItem(workspaceId) ?: return false
        anchorOffset = item.offset.toFloat()
        dragged = 0f
        ghostHeight = item.size
        baseline = model.workspaceStore.workspaces.map { it.workspaceId }
        draggingId = workspaceId
        return true
    }

    /** 跟进一次手指位移，返回预览顺序是否真的变了（变了才给一次轻震）。 */
    fun drag(deltaY: Float): Boolean {
        if (!dragging) return false
        dragged += deltaY
        return settlePreview()
    }

    /**
     * 列表收成分组行之后，把被拖的表头重新对回手指底下。
     *
     * 收起会话行会让内容整体变矮：原先滚到列表深处时，收完可能已经滚不到那么远，列表被迫
     * 上滚，被拖的表头就跑到手指上方去了——不校正的话，下一次落点计算会立刻把这一组甩到末尾。
     * 先尽量把列表滚回去，滚不动的部分（已经到顶/内容不足一屏）就认下来，改从表头当前的位置
     * 继续累计位移。
     */
    suspend fun anchorAfterCollapse() {
        val workspaceId = draggingId ?: return
        val item = listState.layoutInfo.headerItem(workspaceId) ?: return
        val drift = item.offset - ghostTop
        if (drift != 0f) listState.scrollBy(drift)
        val settled = listState.layoutInfo.headerItem(workspaceId) ?: return
        anchorOffset = settled.offset.toFloat() - dragged
    }

    /** 手指贴边时该往哪滚：-1 向上、1 向下、0 不滚。 */
    fun autoScrollDirection(edge: Float): Int {
        if (!dragging) return 0
        val info = listState.layoutInfo
        return when {
            ghostTop < info.viewportStartOffset + edge -> -1
            ghostTop + ghostHeight > info.viewportEndOffset - edge -> 1
            else -> 0
        }
    }

    /** 自动滚一步。期望位置留在视口坐标里不动，于是内容从表头底下滚过去。 */
    suspend fun autoScroll(step: Float): Boolean {
        if (!dragging) return false
        if (listState.scrollBy(step) == 0f) return false
        return settlePreview()
    }

    /** 松手：把预览出来的位置换算成锚点，交给主机落定。 */
    fun commit() {
        val workspaceId = draggingId ?: return
        draggingId = null
        val previewed = model.workspaceStore.workspaces.map { it.workspaceId }
        val index = previewed.indexOf(workspaceId)
        val before = if (index < 0) null else previewed.getOrNull(index + 1)
        val restore = baseline
        baseline = emptyList()
        model.workspaceStore.applyOrder(restore)
        model.moveWorkspace(workspaceId, before)
    }

    /** 拖动被打断（手势取消、切到搜索/扁平视图）：预览退回按下那一刻。 */
    fun cancel() {
        if (!dragging) return
        val restore = baseline
        draggingId = null
        baseline = emptyList()
        model.workspaceStore.applyOrder(restore)
    }

    /**
     * 按当前位置算落点并落成预览。
     *
     * 规则与 Mac 的插入线一致：表头中线在期望位置下面的第一个分组，就是「插到它之前」；
     * 都在上面则是末尾。只有可见表头能参与比较，所以「都在上面」还要再确认一次——最后一个
     * 可见表头之后如果还有没滚进来的分组，那才是真正的锚点，不是末尾。
     */
    private fun settlePreview(): Boolean {
        val workspaceId = draggingId ?: return false
        val info = listState.layoutInfo
        val center = ghostTop + ghostHeight / 2f
        val visible = movableIds.mapNotNull { other ->
            if (other == workspaceId) null else info.headerItem(other)?.let { other to it }
        }
        if (visible.isEmpty()) return false
        val before = visible.firstOrNull { center < it.second.offset + it.second.size / 2f }?.first
            ?: movableIds.drop(movableIds.indexOf(visible.last().first) + 1).firstOrNull { it != workspaceId }
        return model.workspaceStore.moveWorkspace(workspaceId, before)
    }
}

/**
 * 分组表头。真实工作区的表头可以长按拖动上下换位（与 Mac 边栏同口径：只有工作区参与排序），
 * 置顶条、「其他」桶和搜索结果的表头照旧只能折叠。
 */
@Composable
private fun GroupHeader(
    group: SessionGroup,
    collapsed: Boolean,
    drag: GroupDragState,
    onToggle: () -> Unit,
) {
    val workspaceId = group.workspaceId
    if (workspaceId == null) {
        SectionHeader(group.title, group.sessions.size, collapsed, onToggle = onToggle)
        return
    }
    val haptics = LocalHapticFeedback.current
    val dragging = drag.draggingId == workspaceId
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            // 拖起来的那一行要压在别的分组上面，否则滑过去会被后一行盖住。
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = drag.translationFor(workspaceId) }
            .shadow(if (dragging) 6.dp else 0.dp, shape)
            .background(if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, shape)
            .pointerInput(workspaceId) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        if (drag.start(workspaceId)) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        if (drag.drag(amount.y)) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                    onDragEnd = { drag.commit() },
                    onDragCancel = { drag.cancel() },
                )
            },
    ) {
        SectionHeader(group.title, group.sessions.size, collapsed, dragging = dragging, onToggle = onToggle)
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int?,
    collapsed: Boolean,
    dragging: Boolean = false,
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
        // 拖起来之后才亮出抓手：静态时每个分组都挂一个会把表头压得很碎。
        if (dragging) {
            Icon(
                Icons.Default.DragIndicator, Strings.reorderGroups,
                Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            title,
            // 分组标题用更淡的中性灰，和会话行标题（onSurface）拉开层级；
            // 拖动中的这一行要跳出来，改用正文色。
            style = MaterialTheme.typography.titleSmall,
            color = if (dragging) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
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
    val pinned = model.workspaceStore.pinnedSessionIds.contains(summary.sessionId)
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
                onClick = { menu = false; model.setPinned(summary.sessionId, !pinned) },
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
        model.workspaceStore.pinnedSessionIds.contains(summary.sessionId) ->
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

/**
 * 搜索列表底部的一条说明（结果被截断）。
 *
 * 与结果行同为 `LazyColumn` 的一项：它随主机答复在「有/无」之间切换，放进列表才会
 * 跟着一起增删，不会把上面的结果行顶得跳一下。
 */
@Composable
private fun SearchNotice(text: String) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun BoxScope.StatusOverlay(
    model: PhoneModel,
    searching: Boolean,
    query: String,
    searchEmpty: Boolean,
    listEmpty: Boolean,
) {
    val connection = model.connection
    when {
        // 连不上：顶部全局提示条已经把状态与重试说清楚了，这里不再弹一层居中提示；
        // 只有当列表确实空着时才补一句占位，避免用户对着一片空白猜。
        connection is Connection.Failed && listEmpty ->
            EmptyState(Icons.Default.CloudOff, Strings.offlineBannerTitle, Strings.offlineBannerBody)
        connection is Connection.Connecting && listEmpty ->
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        // 空状态认合并后的命中：本地名称匹配也算结果，只有两边都没有才算「没搜到」。
        searching && searchEmpty ->
            EmptyState(Icons.Default.SearchOff, Strings.searchNoResults, query)
        // 认渲染口径（已滤掉归档行）而不是名册本身：名册里还留着已归档会话的摘要，
        // 只看名册的话「只有已归档会话」的账号会得到一片没有任何说明的空白。
        !searching && listEmpty && connection is Connection.Connected ->
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

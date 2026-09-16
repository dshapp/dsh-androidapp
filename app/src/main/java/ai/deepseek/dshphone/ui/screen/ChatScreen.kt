package ai.deepseek.dshphone.ui.screen

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.state.PhoneModel
import ai.deepseek.dshphone.ui.component.EmptyStateColumn
import ai.deepseek.dshphone.ui.component.MessageRow
import ai.deepseek.dshphone.ui.theme.Dimens
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.*
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 会话页：转写 + 底部输入条 + 审批/提问表单。 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    model: PhoneModel,
    sessionId: SessionId,
    onBack: () -> Unit,
    onOpenSession: (SessionId) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val store = model.sessions[sessionId]
    var finding by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var showPanel by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var turnMenu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    /// 正在为跳转分页的轮次；分页期间整份轮次菜单不可点（对齐 iOS 的 TurnRailState.busy）。
    var railBusyTurn by remember(sessionId) { mutableStateOf<Int?>(null) }
    /// 阅读位置所在轮次，供轮次菜单打勾。
    var railActiveTurn by remember(sessionId) { mutableStateOf<Int?>(null) }
    val snackbarHost = remember { SnackbarHostState() }

    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val findFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // 退出查找统一走这里：先收键盘再拆输入框，避免软键盘留在屏幕上空占半屏。
    val exitFind = {
        focusManager.clearFocus()
        finding = false
        findQuery = ""
    }

    LaunchedEffect(sessionId) { model.open(sessionId) }

    // 系统返回键：查找态先退出查找，再退出会话（与标题栏返回同一口径）。
    BackHandler(enabled = finding) { exitFind() }

    LaunchedEffect(finding) { if (finding) findFocus.requestFocus() }

    val nodes = store?.nodes ?: emptyList()
    val visibleNodes = remember(nodes, finding, findQuery) {
        // 画不出内容的行不占列表项，否则 spacedBy 会在原地留一道空隙，
        // 贴底滚动也会停在一行看不见的东西上。
        val rows = nodes.filter { it.rendersRow }
        val needle = findQuery.trim()
        if (!finding || needle.isEmpty()) rows
        else rows.filter { chatNodeSearchText(it).contains(needle, ignoreCase = true) }
    }

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }

    // 首次加载直接落到最新一行；之后只在读者本来就贴着底时才跟随。
    var settledInitialScroll by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(visibleNodes.size) {
        if (visibleNodes.isEmpty()) return@LaunchedEffect
        if (!settledInitialScroll) {
            listState.scrollToItem(visibleNodes.size - 1)
            settledInitialScroll = true
            return@LaunchedEffect
        }
        if (atBottom) listState.animateScrollToItem(visibleNodes.size - 1)
    }

    // 贴底就是最新一轮，否则取阅读线之上最近的带轮次行（与 iOS 的几何判定同义）。
    LaunchedEffect(atBottom, listState.firstVisibleItemIndex, visibleNodes.size) {
        railActiveTurn = if (atBottom) {
            store?.turnRail?.lastOrNull()?.turn
        } else {
            visibleNodes.take(listState.firstVisibleItemIndex + 1)
                .lastOrNull { it.turnNumber != null }?.turnNumber
                ?: visibleNodes.getOrNull(listState.firstVisibleItemIndex)?.turnNumber
        }
    }

    // 模型层最近一次失败原因（对齐 iOS 的 phoneToasts）。
    LaunchedEffect(model.lastErrorMessage) {
        val message = model.lastErrorMessage ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message)
        model.lastErrorMessage = null
    }

    val summary = model.listStore.sessionsById[sessionId]
    val title = summary?.let { sessionDisplayTitle(it, store?.title) } ?: Strings.untitledSession
    val cwd = summary?.cwd

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (finding) {
                        // 查找态按原生「标题栏变查找栏」的做法：标题位只放一行无边框输入，
                        // 计数与清除挪到 actions。M3 的 OutlinedTextField 最小高度 56dp，
                        // 塞进 64dp 的标题栏会被挤扁，描边框也不是系统查找栏的样子。
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (findQuery.isEmpty()) {
                                Text(
                                    Strings.findInChat,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            BasicTextField(
                                value = findQuery,
                                onValueChange = { findQuery = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                // 结果是边打边筛的，回车只用来收键盘，让出屏幕看命中行。
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(findFocus),
                            )
                        }
                    } else {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (finding) exitFind() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    if (finding) {
                        // 有查询就报命中行数，免得读者对着空列表猜是不是没搜到。
                        if (findQuery.isNotBlank()) {
                            Text(
                                visibleNodes.size.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 清除只清文字并把光标留在框里，退出查找交给返回键，二者不混用。
                        if (findQuery.isNotEmpty()) {
                            IconButton(onClick = { findQuery = ""; findFocus.requestFocus() }) {
                                Icon(Icons.Default.Close, Strings.close)
                            }
                        }
                        return@TopAppBar
                    }
                    Box {
                        IconButton(
                            onClick = { turnMenu = true },
                            enabled = !(store?.turnRail.isNullOrEmpty()),
                        ) { Icon(Icons.Default.FormatListNumbered, Strings.turns) }
                        DropdownMenu(turnMenu, { turnMenu = false }) {
                            store?.turnRail?.forEach { item ->
                                DropdownMenuItem(
                                    text = {
                                        val preview = item.prompt.ifEmpty { item.response }
                                        Text(
                                            if (preview.isEmpty()) "${Strings.turns} ${item.turn}"
                                            else "${item.turn} · $preview",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            when {
                                                item.turn == railBusyTurn -> Icons.Default.Schedule
                                                item.turn == railActiveTurn -> Icons.Default.Check
                                                else -> Icons.Default.RadioButtonUnchecked
                                            },
                                            null,
                                            Modifier.size(18.dp),
                                        )
                                    },
                                    // 一次只允许一个跳转在分页（对齐 iOS：busy 期间整列禁用）。
                                    enabled = railBusyTurn == null,
                                    onClick = {
                                        turnMenu = false
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        scope.launch {
                                            railBusyTurn = item.turn
                                            val target = jumpTarget(model, store, item)
                                            railBusyTurn = null
                                            val rows = store?.nodes ?: emptyList()
                                            val index = rows.indexOfFirst { it.nodeId == target }
                                            if (index >= 0) {
                                                listState.animateScrollToItem(index)
                                                railActiveTurn = item.turn
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                        DropdownMenu(moreMenu, { moreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(Strings.findInChat) },
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                onClick = { moreMenu = false; finding = true },
                            )
                            DropdownMenuItem(
                                text = { Text(Strings.panel) },
                                leadingIcon = { Icon(Icons.Default.Info, null) },
                                onClick = { moreMenu = false; showPanel = true },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            ComposerBar(
                model = model,
                sessionId = sessionId,
                onSubmitted = {},
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
                // 宽屏（平板、折叠屏展开）限宽居中，一行文字不至于长到读不下去。
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = Dimens.columnMaxWidth)
                    .align(Alignment.TopCenter),
                contentPadding = PaddingValues(horizontal = Dimens.columnInset, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(Dimens.rowSpacing),
            ) {
                // 基线没到时别把"加载更早"顶在空列表上：那会和加载态叠成两个状态。
                if (store?.hasMore == true && store.historyLoaded && visibleNodes.isNotEmpty()) {
                    item(key = "earlier") {
                        // 顶部哨兵：滚到最上面就自动把更早的历史拉进窗口（对齐 iOS 的 onAppear）。
                        LaunchedEffect(store.firstSeq, store.loadingHistory) {
                            if (settledInitialScroll) model.loadEarlier(store)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            if (store.loadingHistory) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                TextButton(onClick = { model.loadEarlier(store) }) { Text(Strings.loadEarlier) }
                            }
                        }
                    }
                }
                items(visibleNodes.size, key = { visibleNodes[it].nodeId }) { index ->
                    val node = visibleNodes[index]
                    var rowMenu by remember(node.nodeId) { mutableStateOf(false) }
                    Box(
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { rowMenu = true },
                        )
                    ) {
                        MessageRow(
                            node = node,
                            cwd = cwd,
                            sessionId = sessionId,
                            model = model,
                            store = store,
                            onOpenFile = { preview = it },
                            onFork = { seq ->
                                scope.launch { model.fork(sessionId, seq)?.let(onOpenSession) }
                            },
                        )
                        DropdownMenu(rowMenu, { rowMenu = false }) {
                            val copyText = chatNodeSearchText(node)
                            if (copyText.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text(Strings.copy) },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                    onClick = {
                                        rowMenu = false
                                        clipboard.setText(AnnotatedString(copyText))
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(Strings.forkHere) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.CallSplit, null) },
                                onClick = {
                                    rowMenu = false
                                    scope.launch {
                                        model.fork(sessionId, node.anchorSeq.toInt())?.let(onOpenSession)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // 转写的三种"没内容"形态只认基线：基线还在路上一律转圈，
            // 只有真的拿到了基线还是零节点才算空会话，取不回来则报错并给重试。
            val historyError = store?.historyError
            when {
                nodes.isNotEmpty() -> Unit
                historyError != null -> Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.CloudOff, null, Modifier.size(40.dp), MaterialTheme.colorScheme.outline)
                    Text(Strings.historyUnavailable, style = MaterialTheme.typography.titleMedium)
                    Text(
                        historyError.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = { model.retryHistory(sessionId) }) { Text(Strings.retry) }
                }
                store?.historyLoaded == true && !store.loadingHistory ->
                    EmptyStateColumn(Icons.AutoMirrored.Filled.Chat, Strings.emptyChat, Strings.emptyChatHint)
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            AnimatedVisibility(
                visible = !atBottom && visibleNodes.isNotEmpty(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                SmallFloatingActionButton(onClick = {
                    scope.launch { listState.animateScrollToItem(visibleNodes.size - 1) }
                }) { Icon(Icons.Default.ArrowDownward, Strings.scrollToBottom) }
            }
        }
    }

    if (showPanel) {
        SessionPanelSheet(model, sessionId, { showPanel = false }, onOpenSession)
    }
    preview?.let { path ->
        FilePreviewSheet(model, sessionId, path) { preview = null }
    }
    store?.pendingApproval?.let { approval ->
        ApprovalSheet(model, approval) { store.setApproval(null) }
    }
    store?.pendingQuestion?.let { question ->
        QuestionSheet(model, question) { store.setQuestion(null) }
    }
}

/** 跳到所选轮次；较早轮次尚未载入时先分页。 */
private suspend fun jumpTarget(
    model: PhoneModel,
    store: ai.deepseek.dshphone.state.SessionStore?,
    item: TurnRailItem,
): String? {
    if (store == null) return null
    if (item.promptRow != null || item.seq == null || !store.hasMore) {
        return item.promptRow ?: item.anchorRow
    }
    model.loadHistoryThrough(store, item.seq)
    model.loadHistoryForPrompt(store, item.turn)
    val loaded = store.turnRail.firstOrNull { it.turn == item.turn } ?: return null
    return loaded.promptRow ?: loaded.anchorRow
}

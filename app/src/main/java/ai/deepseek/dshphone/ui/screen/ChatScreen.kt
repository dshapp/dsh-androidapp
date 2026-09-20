package ai.deepseek.dshphone.ui.screen

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.state.PhoneModel
import ai.deepseek.dshphone.ui.component.DshFind
import ai.deepseek.dshphone.ui.component.EmptyStateColumn
import ai.deepseek.dshphone.ui.component.MessageRow
import ai.deepseek.dshphone.ui.theme.Dimens
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.interaction.DragInteraction
import kotlinx.coroutines.flow.first
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
    // 查找栏停靠的命中行下标。查询一变回到第 1 条（Safari 边打边找的口径），
    // 上一条 / 下一条在这里循环。
    var activeHit by remember { mutableStateOf(0) }
    var showPanel by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var turnMenu by remember { mutableStateOf(false) }
    // 文件链接点开的应用内预览弹层：点下去弹层立刻出现，正文在弹层里按页读，
    // 加载中就在弹层内部转圈。不交给系统查看器，也不离开会话。
    var preview by remember { mutableStateOf<FileLinkTarget?>(null) }
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

    // 文件链接（行内产物提及与 Markdown 链接）都走这一条：直接用应用内的只读预览
    // 弹层（与系统提示卡片等详情弹层同一种壳），不在别处打开、也不先弹一次提示。
    fun openFileLink(target: FileLinkTarget) {
        preview = target
    }

    // 退出查找统一走这里：先收键盘再拆输入框，避免软键盘留在屏幕上空占半屏。
    val exitFind = {
        focusManager.clearFocus()
        finding = false
        findQuery = ""
        activeHit = 0
    }

    LaunchedEffect(sessionId) { model.open(sessionId) }

    // 屏幕上此刻显示的是哪条会话，交给通知判定用（对齐 Mac 的 visibleSessionId）：
    // 会话页在场就是它，退回列表就没有了。收尾前先比一次——跳去另一条会话时，
    // 新页进场会早于本页退场，不比就会把新页刚写下的那条抹掉。
    DisposableEffect(sessionId) {
        model.visibleSessionId = sessionId
        // 会话页进场就是"用户已经看过了"：把这条会话的未读失败红点收掉
        //（对齐 Mac 选中会话时那句 markErrorSeen）。
        model.markErrorSeen(sessionId)
        onDispose { if (model.visibleSessionId == sessionId) model.visibleSessionId = null }
    }

    // 系统返回键：查找态先退出查找，再退出会话（与标题栏返回同一口径）。
    BackHandler(enabled = finding) { exitFind() }

    LaunchedEffect(finding) { if (finding) findFocus.requestFocus() }

    val nodes = store?.nodes ?: emptyList()
    // 已插队、主机还没在步骤边界取走的消息：画在转写尾部，别让它在队列条上消失后无处可寻
    // （对齐 web 的 PendingSteeringBubble / Mac 的 ConversationRoot.pendingSteering）。
    val pendingSteering = store?.steering ?: emptyList()
    val visibleNodes = remember(nodes, finding, findQuery) {
        // 画不出内容的行不占列表项，否则 spacedBy 会在原地留一道空隙，
        // 贴底滚动也会停在一行看不见的东西上。
        val rows = nodes.filter { it.rendersRow }
        val needle = findQuery.trim()
        if (!finding || needle.isEmpty()) rows
        else rows.filter { chatNodeSearchText(it).contains(needle, ignoreCase = true) }
    }

    // LazyColumn 顶部的「加载更早」哨兵占一格；命中行在列表里的真实位置要把它算进去，
    // 否则步进会整体少一行，停在命中行的上一行上。
    val earlierRow = if (store?.hasMore == true && store.historyLoaded && visibleNodes.isNotEmpty()) 1 else 0

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }

    // 是否跟随最新内容：打开会话、发出消息、点「回到底部」时打开；用户自己往上滚就关掉。
    var followTail by remember(sessionId) { mutableStateOf(true) }

    // 首次加载直接落到最新一行。
    var settledInitialScroll by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(visibleNodes.size, pendingSteering.size) {
        if ((visibleNodes.isEmpty() && pendingSteering.isEmpty()) || settledInitialScroll) return@LaunchedEffect
        // 末行本身可能比屏幕还高，offset 给到上限会把请求钳在最大滚动位置，
        // 也就是真正的内容底部，而不是把它顶到视口上沿（只露出这段回复的开头）。
        listState.scrollToItem(visibleNodes.size + pendingSteering.size - 1, scrollOffset = Int.MAX_VALUE)
        settledInitialScroll = true
    }

    // 跟随尾部。
    //
    // 不能拿节点数量当信号：流式回复期间行数一直不变，变的是最后一行的高度，
    // size 没变就不会再触发滚动——表现就是回复写着写着就不跟了。这里改看
    // canScrollForward：只要底下还有没露出来的内容就立刻贴回底部，
    // 行数增加与单行变高都能覆盖。
    LaunchedEffect(listState, followTail, settledInitialScroll) {
        if (!followTail || !settledInitialScroll) return@LaunchedEffect
        snapshotFlow { listState.canScrollForward }.collect { canForward ->
            // 手正在拖/惯性还在滑的时候不抢：让手势跑完，落点交给上面的收集器判断。
            if (!canForward || !followTail || listState.isScrollInProgress) return@collect
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.scrollToItem(last, scrollOffset = Int.MAX_VALUE)
        }
    }

    // 用户手一碰列表就停止跟随；手势（含松手后的惯性）把列表带回底部后再恢复跟随。
    LaunchedEffect(listState, settledInitialScroll) {
        if (!settledInitialScroll) return@LaunchedEffect
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> followTail = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    // 松手那一下可能还在惯性里滑，等真的停下来再按落点判断。
                    snapshotFlow { listState.isScrollInProgress }.first { !it }
                    if (!listState.canScrollForward) followTail = true
                }
                else -> Unit
            }
        }
    }

    // 上一条 / 下一条命中：在筛过的命中行之间循环（两端环绕，与桌面端页内查找一致），
    // 并把停靠行滚进视野。命中为空时什么也不做。
    fun stepFind(delta: Int) {
        val count = visibleNodes.size
        if (!finding || findQuery.isBlank() || count == 0) return
        val next = ((activeHit + delta) % count + count) % count
        activeHit = next
        followTail = false
        val row = next + earlierRow
        scope.launch { listState.animateScrollToItem(row) }
    }

    // 查询词一变就是一组新结果，光标回到第 1 条并从顶部落位。
    LaunchedEffect(findQuery, finding) {
        activeHit = 0
        if (finding && findQuery.isNotBlank() && visibleNodes.isNotEmpty()) {
            listState.scrollToItem(earlierRow)
        }
    }

    // 结果集缩小（继续打字、历史重载）时把光标夹回范围内。
    LaunchedEffect(visibleNodes.size) {
        if (activeHit >= visibleNodes.size) activeHit = 0
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
                                // 边打边筛；查询变了光标回到第 1 条。
                                onValueChange = { findQuery = it; activeHit = 0 },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                // 结果是边打边筛的，回车只用来收键盘，让出屏幕看命中行；
                                // 上一条 / 下一条走标题栏的两个按钮。
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
                        // 有查询就报「第几条 / 共几条」，免得读者对着空列表猜是不是没搜到。
                        if (findQuery.isNotBlank()) {
                            Text(
                                if (visibleNodes.isEmpty()) "0/0"
                                else fmt(Strings.findCount, activeHit + 1, visibleNodes.size),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 上一条 / 下一条：没命中时置灰但保留槽位，数字宽度变化时按钮不左右挪。
                        val canStep = findQuery.isNotBlank() && visibleNodes.isNotEmpty()
                        IconButton(onClick = { stepFind(-1) }, enabled = canStep) {
                            Icon(Icons.Default.KeyboardArrowUp, Strings.findPrevious)
                        }
                        IconButton(onClick = { stepFind(1) }, enabled = canStep) {
                            Icon(Icons.Default.KeyboardArrowDown, Strings.findNext)
                        }
                        // 清除只清文字并把光标留在框里，退出查找交给返回键，二者不混用。
                        if (findQuery.isNotEmpty()) {
                            IconButton(onClick = { findQuery = ""; activeHit = 0; findFocus.requestFocus() }) {
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
                                        // 跳去历史轮次是明确的"往上读"，别再自动跟回底部。
                                        followTail = false
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
                                onClick = {
                                    moreMenu = false
                                    finding = true
                                    // 查找期间不再跟随流式输出的尾部：读者在逐条看命中，
                                    // 被拽回底部会直接丢掉刚跳到的位置。
                                    followTail = false
                                },
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
                // 发出消息就重新打开跟随并立刻贴底：用户刚才停在哪儿都不重要，
                // 新消息要出现在眼前，之后一路跟到底。
                onSubmitted = {
                    followTail = true
                    scope.launch {
                        val last = listState.layoutInfo.totalItemsCount - 1
                        if (last >= 0) listState.scrollToItem(last, scrollOffset = Int.MAX_VALUE)
                    }
                },
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
                    val isActiveHit = finding && findQuery.isNotBlank() && index == activeHit
                    Box(
                        Modifier
                            // 当前停靠的命中行铺一层整行底色，撑出与左右内容同宽的一栏。
                            .then(
                                if (isActiveHit) Modifier
                                    .background(DshFind.activeRow, RoundedCornerShape(8.dp))
                                else Modifier
                            )
                            .combinedClickable(
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
                            onOpenFile = { path -> openFileLink(FileLinkTarget(path)) },
                            onOpenLink = { target -> openFileLink(target) },
                            onFork = { seq ->
                                scope.launch { model.fork(sessionId, seq)?.let(onOpenSession) }
                            },
                            // 命中行才把查询词交给行内渲染，未命中的行不因查询变化重组。
                            findQuery = if (finding && findQuery.isNotBlank()) findQuery else "",
                            findActive = isActiveHit,
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
                // 已插队的消息：排在全部转写节点之后，直到主机在下一个步骤边界把它取走；
                // 一取走它就变成正式的用户/steering 节点，这里同步消失（队列帧与事件一起到）。
                items(pendingSteering.size, key = { "pending-steer:" + pendingSteering[it].id }) { index ->
                    val item = pendingSteering[index]
                    MessageRow(
                        node = ChatNode.Steering(
                            seq = store?.lastSeq ?: 0,
                            time = 0.0,
                            messageId = item.id,
                            content = item.content.map { decodeContentBlock(it) },
                        ),
                        cwd = cwd,
                        sessionId = sessionId,
                        model = model,
                        store = store,
                        onOpenFile = { path -> openFileLink(FileLinkTarget(path)) },
                        onOpenLink = { target -> openFileLink(target) },
                        onFork = {},
                    )
                }
            }

            // 转写的三种"没内容"形态只认基线：基线还在路上一律转圈，
            // 只有真的拿到了基线还是零节点才算空会话，取不回来则报错并给重试。
            val historyError = store?.historyError
            when {
                // 查找一条没命中：给一句「未找到」，别让读者对着一片空白猜。
                finding && findQuery.isNotBlank() && visibleNodes.isEmpty() && pendingSteering.isEmpty() ->
                    EmptyStateColumn(Icons.Default.SearchOff, Strings.findNoResults, findQuery)
                nodes.isNotEmpty() || pendingSteering.isNotEmpty() -> Unit
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
                    followTail = true
                    scope.launch {
                        val last = listState.layoutInfo.totalItemsCount - 1
                        if (last >= 0) listState.scrollToItem(last, scrollOffset = Int.MAX_VALUE)
                    }
                }) { Icon(Icons.Default.ArrowDownward, Strings.scrollToBottom) }
            }
        }
    }

    if (showPanel) {
        SessionPanelSheet(model, sessionId, { showPanel = false }, onOpenSession)
    }
    preview?.let { target ->
        FilePreviewSheet(model, target.sessionId ?: sessionId, target.path) { preview = null }
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

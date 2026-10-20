package ai.deepseek.dshphone.ui.screen

import ai.deepseek.dshphone.i18n.ManagementStrings as M
import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.*
import ai.deepseek.dshphone.state.AutomationManagementState
import ai.deepseek.dshphone.ui.component.EmptyStateColumn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 自动化任务页：与归档页、设置页同一套原生写法。
 *
 * - 顶栏左侧是返回箭头；右侧只放图标动作（搜索、新建），搜索把标题位换成输入框。
 * - 刷新改为下拉刷新，不在顶栏放「刷新」按钮。
 * - 每行点开看投递记录，行尾「更多」菜单里放编辑 / 投递记录 / 删除。
 * - 编辑是全屏编辑态：左上角关闭（有草稿时先确认放弃），右上角保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationsScreen(
    api: ApiClient,
    onBack: () -> Unit,
    /** 「新建」：任务由会话里的模型创建，这里只开一个预填好开头的新会话。 */
    onNewTask: () -> Unit = {},
    visible: Boolean = true,
    state: AutomationManagementState = remember(api) { AutomationManagementState() },
    events: Flow<MuxEvent>? = null,
) {
    val rpc = remember(api) { api.managementRpc() }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var finding by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ManagedAutomation?>(null) }
    var discard by remember { mutableStateOf(false) }
    val findFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val snackbarHost = remember { SnackbarHostState() }
    ManagementRefresh(visible, api, events, "schedule/changed") { state.refresh(rpc) }
    LaunchedEffect(finding) { if (finding) findFocus.requestFocus() }
    // 保存 / 删除的结果用 snackbar 提示一次；在页面级 scope 里弹，清掉 notice 不会把它连带取消。
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        state.consumeNotice()
        scope.launch { snackbarHost.showSnackbar(M.outcome(notice)) }
    }
    if (!visible) return

    val draft = state.draft
    val historyEntry = state.historyEntry
    fun exitFind() { focusManager.clearFocus(); finding = false; query = "" }
    fun back() {
        when {
            draft != null -> discard = true
            historyEntry != null -> state.closeHistory()
            finding -> exitFind()
            else -> onBack()
        }
    }
    fun pullRefresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            try { if (historyEntry != null) state.loadHistory(rpc, historyEntry) else state.refresh(rpc) } finally { refreshing = false }
        }
    }
    BackHandler { back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when {
                        draft != null -> Text(M.edit, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        historyEntry != null -> Column {
                            Text(M.history, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                historyEntry.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        finding -> TitleSearchField(query, { query = it }, M.search, findFocus) { focusManager.clearFocus() }
                        else -> Text(M.automations, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { back() }) {
                        if (draft != null) Icon(Icons.Default.Close, M.cancel)
                        else Icon(Icons.AutoMirrored.Filled.ArrowBack, M.back)
                    }
                },
                actions = {
                    when {
                        draft != null -> {
                            val current = state.entries.firstOrNull { it.key == draft.entry.key }
                            TextButton(
                                enabled = !state.busy && state.factsReady && draft.validate() == null && current?.active == true,
                                onClick = { scope.launch { state.save(rpc) } },
                            ) { Text(M.save) }
                        }
                        historyEntry != null -> Unit
                        finding -> {
                            // 清除只清文字并把光标留在框里，退出搜索交给返回键，与会话列表一致。
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = ""; findFocus.requestFocus() }) {
                                    Icon(Icons.Default.Close, Strings.close)
                                }
                            }
                        }
                        else -> {
                            IconButton(onClick = { finding = true }) { Icon(Icons.Default.Search, M.search) }
                            IconButton(onClick = onNewTask) { Icon(Icons.Default.Add, M.newTask) }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.busy && !refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (draft != null) {
                DraftEditor(state, draft, Modifier.weight(1f))
            } else {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { pullRefresh() },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    if (historyEntry != null) {
                        HistoryList(state, onMore = { scope.launch { state.loadHistory(rpc, historyEntry, more = true) } })
                    } else {
                        val entries = state.entries
                            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) || it.sessionId.contains(query, ignoreCase = true) || it.raw["prompt"].str.orEmpty().contains(query, ignoreCase = true) }
                            .sortedWith(compareByDescending<ManagedAutomation> { it.active }.thenBy { it.raw["scheduledAt"].str })
                        LazyColumn(Modifier.fillMaxSize()) {
                            if (!finding) item { Hint(M.scopeHint) }
                            if (!state.factsReady) item { Hint(M.text("Current catalog unavailable; edits and deletes are disabled. Pull to refresh.", "当前目录未加载或已失效，暂不能编辑或删除。下拉刷新重试。"), error = true) }
                            state.error?.let { error -> item { Hint(error, error = true) } }
                            items(entries, key = { it.key }) { entry ->
                                AutomationRow(
                                    entry = entry,
                                    canWrite = entry.active && !state.busy && state.factsReady,
                                    onOpen = { scope.launch { state.loadHistory(rpc, entry) } },
                                    onEdit = { state.edit(entry) },
                                    onDelete = { deleting = entry },
                                )
                            }
                        }
                        if (entries.isEmpty() && state.factsReady) {
                            if (query.isBlank()) EmptyStateColumn(Icons.Default.Schedule, M.empty, null)
                            else EmptyStateColumn(Icons.Default.SearchOff, M.noMatches, query)
                        }
                    }
                }
            }
        }
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(M.deleteTitle) },
            text = { Text(M.deleteMessage(entry.title)) },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = { deleting = null; scope.launch { state.delete(rpc, entry) } }) {
                    Text(M.delete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(M.cancel) } },
        )
    }
    if (discard) {
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text(M.discardTitle) },
            confirmButton = { TextButton(enabled = !state.busy, onClick = { state.draft = null; discard = false }) { Text(M.discard) } },
            dismissButton = { TextButton(onClick = { discard = false }) { Text(M.keepEditing) } },
        )
    }
}

/** 一行自动化任务：标题，加「规则 · 下次时间」与提醒内容摘要；行尾「更多」菜单。 */
@Composable
private fun AutomationRow(
    entry: ManagedAutomation,
    canWrite: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val meta = listOfNotNull(
        M.kindLabel(entry.raw["kind"].str),
        if (entry.active) null else M.statusLabel(entry.raw["status"].str),
        entry.raw["scheduledAt"].str?.let { M.next(localTime(it)) },
    ).filter { it.isNotBlank() }.joinToString(" · ")
    ListItem(
        modifier = Modifier.clickable { onOpen() },
        headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                entry.raw["prompt"].str?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, M.moreActions) }
                DropdownMenu(menu, { menu = false }) {
                    if (entry.active) DropdownMenuItem(text = { Text(M.edit) }, enabled = canWrite, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(M.history) }, onClick = { menu = false; onOpen() })
                    if (entry.active) DropdownMenuItem(
                        text = { Text(M.delete, color = if (canWrite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) },
                        enabled = canWrite,
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        },
    )
}

/** 投递记录：每条一行，末尾按需加载更早记录；刷新走外层下拉刷新。 */
@Composable
private fun BoxScope.HistoryList(state: AutomationManagementState, onMore: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { Hint(M.deliveryHint) }
        state.error?.let { error -> item { Hint(error, error = true) } }
        items(state.history, key = { it["messageId"].str ?: it.toString() }) { delivery ->
            ListItem(
                headlineContent = { Text(M.delivered(delivery["deliveredAt"].str?.let(::localTime).orEmpty())) },
                overlineContent = { delivery["scheduledAt"].str?.let { Text(M.scheduled(localTime(it))) } },
                supportingContent = {
                    SelectionContainer {
                        Text(
                            delivery["prompt"].str ?: M.text("Legacy receipt: prompt unavailable", "旧投递记录：内容不可用"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
        if (state.nextBefore != null) item {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                TextButton(enabled = !state.busy, onClick = onMore) { Text(M.more) }
            }
        }
        state.historyMeta?.let { metadata ->
            val notes = listOfNotNull(
                if (metadata["earlierRecordsUnavailable"].bool == true) M.text("Earlier records may be unavailable", "更早记录可能不可用") else null,
                if (metadata["earlierRecordsPruned"].bool == true) M.text("Older records were pruned by the host", "宿主已裁剪较旧记录") else null,
            )
            if (notes.isNotEmpty()) item { Hint(notes.joinToString("\n")) }
        }
    }
    if (state.history.isEmpty() && !state.busy) EmptyStateColumn(Icons.Default.History, M.empty, null)
}

/** 全屏编辑态：表单字段；保存在顶栏右侧，关闭在左上角。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DraftEditor(state: AutomationManagementState, draft: AutomationDraft, modifier: Modifier) {
    val enabled = !state.busy
    LazyColumn(
        modifier.fillMaxWidth().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.factsReady) item { Text(M.text("Current catalog unavailable; saving is disabled. Draft retained.", "当前目录未加载或已失效，暂不能保存，草稿保留。"), color = MaterialTheme.colorScheme.error) }
        state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        item { OutlinedTextField(value = draft.title, onValueChange = { state.draft = draft.copy(title = it) }, label = { Text(M.title) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = draft.prompt, onValueChange = { state.draft = draft.copy(prompt = it) }, label = { Text(M.prompt) }, enabled = enabled, minLines = 3, modifier = Modifier.fillMaxWidth()) }
        item {
            ListItem(
                modifier = Modifier.clickable(enabled = enabled) { state.draft = draft.copy(timingChanged = !draft.timingChanged) },
                headlineContent = { Text(M.changeTiming) },
                supportingContent = { Text(M.changeTimingHint) },
                trailingContent = { Switch(checked = draft.timingChanged, enabled = enabled, onCheckedChange = { state.draft = draft.copy(timingChanged = it) }) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
        if (draft.timingChanged) {
            item {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
                    OutlinedTextField(
                        value = M.kindLabel(draft.kind),
                        onValueChange = {},
                        readOnly = true,
                        enabled = enabled,
                        label = { Text(M.rule) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("at", "every", "daily", "weekly", "cron").forEach { kind ->
                            DropdownMenuItem(text = { Text(M.kindLabel(kind)) }, onClick = { state.draft = draft.copy(kind = kind); expanded = false })
                        }
                    }
                }
            }
            when (draft.kind) {
                "at" -> item { ManagementField(M.text("Absolute UTC timestamp (YYYY-MM-DDTHH:mm:ssZ)", "绝对 UTC 时间（YYYY-MM-DDTHH:mm:ssZ）"), draft.at, enabled) { state.draft = draft.copy(at = it) } }
                "every" -> item { ManagementField(M.text("Interval seconds (minimum 60)", "间隔秒数（至少 60）"), draft.seconds, enabled) { state.draft = draft.copy(seconds = it) } }
                else -> {
                    item { ManagementField(M.text("Explicit IANA time zone", "显式 IANA 时区"), draft.zone, enabled) { state.draft = draft.copy(zone = it) } }
                    if (draft.kind == "cron") item { ManagementField(M.text("Cron: minute hour day month weekday (numeric only)", "Cron：分 时 日 月 星期（仅数字）"), draft.expression, enabled) { state.draft = draft.copy(expression = it) } }
                    else item { ManagementField(M.text("Local time HH:mm:ss[.SSS]", "本地时间 HH:mm:ss[.SSS]"), draft.time, enabled) { state.draft = draft.copy(time = it) } }
                    if (draft.kind == "weekly") item { ManagementField(M.text("ISO weekdays 1–7, comma separated", "ISO 星期 1–7，逗号分隔"), draft.weekdays, enabled) { state.draft = draft.copy(weekdays = it) } }
                }
            }
        }
        draft.validate()?.let { field -> item { Text(M.text("Invalid field: ", "字段无效：") + field, color = MaterialTheme.colorScheme.error) } }
        val current = state.entries.firstOrNull { it.key == draft.entry.key }
        if (current?.active != true) item { Text(M.text("Rule is no longer active. Draft retained; saving is disabled.", "规则已不再活跃。保留草稿，禁止保存。"), color = MaterialTheme.colorScheme.error) }
    }
}

/** 标题栏里的搜索框：与会话列表、归档页同一套写法（M3 TextField 最小 56dp，放不进标题栏）。 */
@Composable
private fun TitleSearchField(query: String, onChange: (String) -> Unit, placeholder: String, focus: FocusRequester, onSearch: () -> Unit) {
    Box(contentAlignment = Alignment.CenterStart) {
        if (query.isEmpty()) {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 主机给的是 UTC 时间戳，列表里按手机本地时区显示；解析不了就原样给出。 */
private fun localTime(raw: String): String =
    runCatching { Instant.parse(raw).atZone(ZoneId.systemDefault()).format(timeFormat) }.getOrDefault(raw)

@Composable
private fun ManagementField(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
}

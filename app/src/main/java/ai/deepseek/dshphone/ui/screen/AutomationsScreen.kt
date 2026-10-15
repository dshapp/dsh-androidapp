package ai.deepseek.dshphone.ui.screen

import ai.deepseek.dshphone.i18n.ManagementStrings as M
import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.*
import ai.deepseek.dshphone.state.AutomationManagementState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

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
    var deleting by remember { mutableStateOf<ManagedAutomation?>(null) }
    var discard by remember { mutableStateOf(false) }
    ManagementRefresh(visible, api, events, "schedule/changed") { state.refresh(rpc) }
    if (!visible) return
    fun back() { if (state.draft != null) discard = true else if (state.historyEntry != null) state.closeHistory() else onBack() }
    BackHandler { back() }
    Scaffold(topBar = { TopAppBar(title = { Text(if (state.historyEntry != null) M.history else M.automations) }, navigationIcon = { TextButton(onClick = { back() }) { Text(M.back) } }, actions = { TextButton(onClick = onNewTask) { Text(M.newTask) }; TextButton(onClick = { scope.launch { state.refresh(rpc) } }) { Text(M.refresh) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(M.scopeHint, style = MaterialTheme.typography.bodySmall) }
            if (!state.factsReady) item { Text(M.text("Current catalog unavailable; edits/deletes disabled. Draft retained.", "当前目录未加载或已失效；禁止修改/删除，草稿保留。")) }
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            state.notice?.let { notice -> item { Text(M.outcome(notice)) } }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            val draft = state.draft
            val historyEntry = state.historyEntry
            if (draft != null) {
                item { Text(draft.entry.sessionId, style = MaterialTheme.typography.bodySmall) }
                item { OutlinedTextField(value = draft.title, onValueChange = { state.draft = draft.copy(title = it) }, label = { Text(M.title) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(value = draft.prompt, onValueChange = { state.draft = draft.copy(prompt = it) }, label = { Text(M.prompt) }, enabled = !state.busy, minLines = 3, modifier = Modifier.fillMaxWidth()) }
                item { Row { Checkbox(checked = draft.timingChanged, enabled = !state.busy, onCheckedChange = { state.draft = draft.copy(timingChanged = it) }); Text(M.text("Change timing (otherwise retain exact target)", "修改时间规则（否则保留原精确目标时间）")) } }
                if (draft.timingChanged) {
                    item { var expanded by remember { mutableStateOf(false) }
                        Box { OutlinedButton(enabled = !state.busy, onClick = { expanded = true }) { Text(M.text("Rule: ", "规则：") + draft.kind) }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                listOf("at", "every", "daily", "weekly", "cron").forEach { kind -> DropdownMenuItem(text = { Text(kind) }, onClick = { state.draft = draft.copy(kind = kind); expanded = false }) }
                            }
                        }
                    }
                    when (draft.kind) {
                        "at" -> item { ManagementField(M.text("Absolute UTC timestamp (YYYY-MM-DDTHH:mm:ssZ)", "绝对 UTC 时间（YYYY-MM-DDTHH:mm:ssZ）"), draft.at, !state.busy) { state.draft = draft.copy(at = it) } }
                        "every" -> item { ManagementField(M.text("Interval seconds (minimum 60)", "间隔秒数（至少 60）"), draft.seconds, !state.busy) { state.draft = draft.copy(seconds = it) } }
                        else -> {
                            item { ManagementField(M.text("Explicit IANA time zone", "显式 IANA 时区"), draft.zone, !state.busy) { state.draft = draft.copy(zone = it) } }
                            if (draft.kind == "cron") item { ManagementField(M.text("Cron: minute hour day month weekday (numeric only)", "Cron：分 时 日 月 星期（仅数字）"), draft.expression, !state.busy) { state.draft = draft.copy(expression = it) } }
                            else item { ManagementField(M.text("Local time HH:mm:ss[.SSS]", "本地时间 HH:mm:ss[.SSS]"), draft.time, !state.busy) { state.draft = draft.copy(time = it) } }
                            if (draft.kind == "weekly") item { ManagementField(M.text("ISO weekdays 1–7, comma separated", "ISO 星期 1–7，逗号分隔"), draft.weekdays, !state.busy) { state.draft = draft.copy(weekdays = it) } }
                        }
                    }
                }
                draft.validate()?.let { field -> item { Text(M.text("Invalid field: ", "字段无效：") + field, color = MaterialTheme.colorScheme.error) } }
                val current = state.entries.firstOrNull { it.key == draft.entry.key }
                if (current?.active != true) item { Text(M.text("Rule is no longer active. Draft retained; saving is disabled.", "规则已不再活跃。保留草稿，禁止保存。")) }
                item { Row { Button(enabled = !state.busy && state.factsReady && draft.validate() == null && current?.active == true, onClick = { scope.launch { state.save(rpc) } }) { Text(M.save) }; TextButton(enabled = !state.busy, onClick = { discard = true }) { Text(M.cancel) } } }
            } else if (historyEntry != null) {
                item { Text(historyEntry.title, style = MaterialTheme.typography.titleLarge); Text(M.deliveryHint) }
                items(state.history) { delivery ->
                    Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SelectionContainer { Text("messageId: " + delivery["messageId"].str.orEmpty()) }
                        Text(M.text("Scheduled UTC: ", "计划 UTC：") + delivery["scheduledAt"].str.orEmpty())
                        Text(M.text("Delivered UTC: ", "投递 UTC：") + delivery["deliveredAt"].str.orEmpty())
                        SelectionContainer { Text(delivery["prompt"].str ?: M.text("Legacy receipt: prompt unavailable", "旧投递记录：内容不可用")) }
                    } }
                }
                if (state.history.isEmpty() && !state.busy) item { Text(M.empty) }
                state.historyMeta?.let { metadata -> item {
                    Text(M.text("Retention: ", "保留策略：") + metadata["retention"].toString())
                    if (metadata["earlierRecordsUnavailable"].bool == true) Text(M.text("Earlier records may be unavailable", "更早记录可能不可用"))
                    if (metadata["earlierRecordsPruned"].bool == true) Text(M.text("Older records were pruned by the host", "宿主已裁剪较旧记录"))
                } }
                item { Row { if (state.nextBefore != null) Button(enabled = !state.busy, onClick = { scope.launch { state.loadHistory(rpc, historyEntry, more = true) } }) { Text(M.more) }
                    TextButton(enabled = !state.busy, onClick = { scope.launch { state.loadHistory(rpc, historyEntry) } }) { Text(M.refresh) } } }
            } else {
                item { ManagementField(M.text("Search title or session", "搜索名称或会话"), query, true) { query = it } }
                val entries = state.entries.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) || it.sessionId.contains(query, ignoreCase = true) || it.raw["prompt"].str.orEmpty().contains(query, ignoreCase = true) }.sortedWith(compareByDescending<ManagedAutomation> { it.active }.thenBy { it.raw["scheduledAt"].str })
                if (entries.isEmpty()) item { Text(M.empty) }
                items(entries, key = { it.key }) { entry -> Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium)
                    Text(entry.sessionId, style = MaterialTheme.typography.bodySmall)
                    Text(M.text("Status: ", "状态：") + entry.raw["status"].str + " · " + entry.raw["kind"].str)
                    Text(M.text("Next/final target UTC: ", "下次/最终目标 UTC：") + entry.raw["scheduledAt"].str)
                    entry.raw["prompt"].str?.let { Text(it) }
                    entry.raw["lastDelivery"]["deliveredAt"].str?.let { Text(M.text("Last inbox delivery UTC: ", "最近收件箱投递 UTC：") + it) }
                    Row { if (entry.active) { TextButton(enabled = !state.busy && state.factsReady, onClick = { state.edit(entry) }) { Text(M.edit) }; TextButton(enabled = !state.busy && state.factsReady, onClick = { deleting = entry }) { Text(M.delete) } }
                        TextButton(enabled = !state.busy, onClick = { scope.launch { state.loadHistory(rpc, entry) } }) { Text(M.history) } }
                } } }
            }
        }
    }
    deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(M.delete) }, text = { Text(entry.title + "\n" + entry.sessionId + "\n" + M.text("Delete this active rule? Existing inbox messages are not recalled.", "删除此活跃规则？已投递的收件箱消息不会撤回。")) }, confirmButton = { TextButton(enabled = !state.busy, onClick = { deleting = null; scope.launch { state.delete(rpc, entry) } }) { Text(M.confirm) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(M.cancel) } }) }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(M.text("Discard draft?", "放弃草稿？")) }, confirmButton = { TextButton(enabled = !state.busy, onClick = { state.draft = null; discard = false }) { Text(M.confirm) } }, dismissButton = { TextButton(onClick = { discard = false }) { Text(M.cancel) } })
}

@Composable
private fun ManagementField(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
}

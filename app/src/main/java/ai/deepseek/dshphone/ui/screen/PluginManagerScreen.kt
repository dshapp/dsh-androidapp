package ai.deepseek.dshphone.ui.screen

import ai.deepseek.dshphone.i18n.ManagementStrings as M
import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.*
import ai.deepseek.dshphone.state.PluginManagementState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagerScreen(
    api: ApiClient,
    onBack: () -> Unit,
    visible: Boolean = true,
    state: PluginManagementState = remember(api) { PluginManagementState() },
    events: Flow<MuxEvent>? = null,
) {
    val rpc = remember(api) { api.managementRpc() }
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<String?>(null) }
    var install by remember { mutableStateOf<ManagedBundle?>(null) }
    var remove by remember { mutableStateOf<ManagedBundle?>(null) }
    var risk by remember { mutableStateOf<JsonElement?>(null) }
    var accepted by remember { mutableStateOf(false) }
    val locked = state.busy || state.recovering || state.requestId != null || !state.factsReady
    ManagementRefresh(visible, api, events, "plugin-manager/changed", onActive = { if (state.requestId != null) state.recover(rpc) }) { state.refresh(rpc) }
    if (!visible) return
    BackHandler { if (detail != null) detail = null else onBack() }
    Scaffold(topBar = { TopAppBar(title = { Text(M.plugins) }, navigationIcon = { TextButton(onClick = { if (detail != null) detail = null else onBack() }) { Text(M.back) } }, actions = { TextButton(onClick = { scope.launch { state.refresh(rpc) } }) { Text(M.refresh) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(M.pluginBoundary, style = MaterialTheme.typography.bodySmall) }
            if (!state.factsReady) item { Text(M.text("Current host facts unavailable; write controls disabled. Refresh to retry.", "当前宿主事实未加载或已失效；禁止写入。请刷新重试。")) }
            state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            state.result?.takeUnless { it is JsonNull }?.let { outcome -> item {
                Text(M.outcome(outcome["application"].str ?: outcome["status"].str))
                SelectionContainer { Text(prettyJson(outcome.toString()), style = MaterialTheme.typography.bodySmall) }
            } }
            state.requestId?.let { id -> item {
                Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectionContainer { Text("requestId: " + id + "\n" + state.pendingSpec.orEmpty()) }
                    Text(M.text("Leaving this page does not cancel installation. Recovery only waits for this same id; it never reinstalls.", "离开页面不会取消安装。恢复仅等待此原始 id；不会重复安装。"))
                    if (state.result is JsonNull) Text(M.unknownInstall)
                    Row { TextButton(enabled = !state.busy && !state.recovering, onClick = { scope.launch { state.recover(rpc) } }) { Text(M.text("Recover result", "恢复结果")) }
                        TextButton(enabled = !state.recovering, onClick = { scope.launch { state.cancel(rpc) } }) { Text(M.text("Cancel installation", "取消安装")) } }
                    if (state.result is JsonNull) TextButton(onClick = { state.dismissUnknown() }) { Text(M.text("Inventory checked; dismiss unknown result", "已核对库存；关闭未知结果")) }
                } }
            } }
            val selected = state.bundles.firstOrNull { it.name == detail }
            if (selected != null) {
                item { Text(selected.name, style = MaterialTheme.typography.titleLarge) }
                item { SelectionContainer { Text(prettyJson(selected.raw.toString()), style = MaterialTheme.typography.bodySmall) } }
                items(selected.rows) { row ->
                    val entry = state.plugins.singleOrNull { it["entryId"].str == row["entryId"].str && row["entryId"].str != null }
                    Card { Column(Modifier.padding(12.dp)) {
                        Text(row["moduleName"].str ?: row["rowId"].str.orEmpty())
                        entry?.let { plugin ->
                            Text(plugin["fiberPhase"].str.orEmpty())
                            plugin["readOnlyReason"].str?.let { Text(it) }
                            Row { Text(M.enabled); Switch(checked = plugin["enabled"].bool == true, enabled = !locked && plugin["readOnlyReason"].str == null && plugin["patchId"].str != null, onCheckedChange = { enabled -> scope.launch { state.change(rpc) { rpc.pluginEnabled(plugin["entryId"].str!!, enabled) } } }) }
                        }
                    } }
                }
            } else {
                for ((label, rows) in listOf(M.official to state.bundles.filter { it.official }, M.installed to state.bundles.filter { !it.official && it.installed })) {
                    item { Text(label, style = MaterialTheme.typography.titleMedium) }
                    if (rows.isEmpty()) item { Text(M.empty) }
                    items(rows, key = { it.name }) { bundle ->
                        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(bundle.name, style = MaterialTheme.typography.titleMedium)
                            Text(bundle.version ?: bundle.installVersion.orEmpty())
                            bundle.raw["description"].str?.let { Text(it) }
                            bundle.readOnly?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            bundle.raw["error"]?.let { Text(prettyJson(it.toString())) }
                            Row { Text(M.enabled); Switch(checked = bundle.enabled, enabled = !locked && bundle.readOnly == null && !bundle.missing, onCheckedChange = { enabled -> scope.launch { state.change(rpc) { rpc.bundleEnabled(bundle.name, enabled) } } }) }
                            Row { TextButton(onClick = { detail = bundle.name }) { Text(M.details) }
                                if (bundle.installSpec != null && (bundle.missing || bundle.updateAvailable)) TextButton(enabled = !locked && bundle.readOnly == null, onClick = { install = bundle }) { Text(M.install) }
                                if (bundle.removable) TextButton(enabled = !locked, onClick = { remove = bundle }) { Text(M.remove) } }
                        } }
                    }
                }
            }
            val incompatible = ((state.result["error"]["incompatible"].arr ?: state.result["packageResult"]["incompatible"].arr.orEmpty()) + state.bundles.flatMap { it.raw["error"]["incompatible"].arr.orEmpty() }).distinctBy { it["name"].str + "@" + it["version"].str + "/" + it["runtimeVersion"].str }
            items(incompatible) { pair ->
                TextButton(enabled = !locked, onClick = { risk = pair; accepted = false }) { Text(M.text("Review exact version exemption: ", "审阅精确版本豁免：") + pair["name"].str + "@" + pair["version"].str + " / " + pair["runtimeVersion"].str) }
            }
            item { Text(M.text("Saved exact version exemptions", "已保存的精确版本豁免"), style = MaterialTheme.typography.titleMedium) }
            state.exemptions["exemptions"].obj.orEmpty().forEach { (packageVersion, runtimes) ->
                items(runtimes.arr.orEmpty()) { runtime ->
                    Row { Text(packageVersion + " / " + runtime.str, Modifier.weight(1f)); TextButton(enabled = !locked, onClick = { scope.launch { state.change(rpc) { rpc.versionExemption(packageVersion, runtime.str!!, false) } } }) { Text(M.text("Revoke", "撤销")) } }
                }
            }
            state.exemptions["warnings"].arr.orEmpty().forEach { warning -> item { Text(warning.str.orEmpty()) } }
        }
    }
    install?.let { bundle -> AlertDialog(onDismissRequest = { install = null }, title = { Text(M.install) }, text = { SelectionContainer { Text(bundle.name + "\n" + bundle.installSpec + "\n" + bundle.installVersion + "\n" + M.text("Exact host-provided target. No build-script approvals or automatic restart.", "采用宿主精确 installTarget。不授权构建脚本，不自动重启。")) } }, confirmButton = { TextButton(onClick = { install = null; scope.launch { state.install(rpc, bundle.installSpec!!, if (bundle.missing) true else bundle.enabled) } }) { Text(M.confirm) } }, dismissButton = { TextButton(onClick = { install = null }) { Text(M.cancel) } }) }
    remove?.let { bundle -> AlertDialog(onDismissRequest = { remove = null }, title = { Text(M.remove) }, text = { Text(bundle.name) }, confirmButton = { TextButton(onClick = { remove = null; scope.launch { state.change(rpc) { rpc.removeBundle(bundle.name) } } }) { Text(M.confirm) } }, dismissButton = { TextButton(onClick = { remove = null }) { Text(M.cancel) } }) }
    risk?.let { pair -> AlertDialog(onDismissRequest = { risk = null }, title = { Text(M.text("Exact version exemption", "精确版本豁免")) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(prettyJson(pair.toString())); Text(M.risk); Checkbox(checked = accepted, onCheckedChange = { accepted = it }) } }, confirmButton = { TextButton(enabled = accepted && !locked, onClick = {
        risk = null; scope.launch { state.change(rpc) { rpc.versionExemption(pair["name"].str!! + "@" + pair["version"].str!!, pair["runtimeVersion"].str!!, true, true) } }
    }) { Text(M.confirm) } }, dismissButton = { TextButton(onClick = { risk = null }) { Text(M.cancel) } }) }
}

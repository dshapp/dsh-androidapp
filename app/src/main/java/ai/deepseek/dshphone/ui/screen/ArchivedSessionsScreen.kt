package ai.deepseek.dshphone.ui.screen

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import ai.deepseek.dshphone.model.archivedSessionRows
import ai.deepseek.dshphone.model.filterArchivedSessions
import ai.deepseek.dshphone.model.SessionSummary
import ai.deepseek.dshphone.model.relativeTime
import ai.deepseek.dshphone.model.sessionDisplayTitle
import ai.deepseek.dshphone.model.sessionWorkspaceName
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.state.PhoneModel
import ai.deepseek.dshphone.ui.component.EmptyStateColumn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 已归档会话页：会话列表底部「已归档会话」入口的落点。
 *
 * 开成独立页面而不是底部弹层（原来那个 `ArchivedSheet`）：取消归档会让会话回到列表，
 * 盖住列表的弹层看不到这个结果；独立页返回一步就能看见它回来了。口径照抄 Mac 桌面端
 * `ArchivedSessionsView`——权威归档集合在 Workspace 注册表里，与已加载的会话摘要求交后
 * 成行，搜索按标题或工作区名本地过滤，每行一个取消归档动作，三种空态各自区分。
 *
 * 不额外拉取：归档集合与会话摘要分别由常驻的 `workspace/follow` 与会话流维持最新。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedSessionsScreen(
    model: PhoneModel,
    onBack: () -> Unit,
    onOpenSession: (SessionId) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // 搜索默认只在顶栏留一个图标，点开才把标题位换成输入框（与会话列表同一套写法）。
    var finding by remember { mutableStateOf(false) }
    val findFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val snackbarHost = remember { SnackbarHostState() }

    fun exitFind() {
        focusManager.clearFocus()
        finding = false
        query = ""
    }

    // 系统返回键：搜索态先退出搜索，别直接退出这一页。
    BackHandler(enabled = finding) { exitFind() }
    LaunchedEffect(finding) { if (finding) findFocus.requestFocus() }

    // 模型层最近一次失败原因：取消归档在老主机上不支持（0.1.6 才有这条 RPC），
    // 失败必须说出来，不能看起来像成功了却没动静。与 ChatScreen 同一条 snackbar 路。
    LaunchedEffect(model.lastErrorMessage) {
        val message = model.lastErrorMessage ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message)
        model.lastErrorMessage = null
    }

    // 两个 store 的归档集合取并集：`workspace/follow` 写入 WorkspaceStore，恢复成功后
    // `PhoneModel.unarchive` 也会同步应用到两边，这里合起来读不会漏掉任何一侧。
    val ids = (model.workspaceStore.archivedSessionIds + model.listStore.archivedSessionIds).distinct()
    val rows = archivedSessionRows(ids, model.listStore.sessionsById)
    val visibleRows = filterArchivedSessions(rows, model.workspaceStore.workspaceTitleBySession, query)

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
                                    Strings.searchArchivedSessions,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
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
                        Text(Strings.archivedSessions, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (finding) exitFind() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
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
                    } else {
                        IconButton(onClick = { finding = true }) {
                            Icon(Icons.Default.Search, Strings.searchArchivedSessions)
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                // 归档集合非空、却没有一行能解析出摘要：成员在 Workspace 注册表之外被删掉了，
                // 这里没有可恢复的东西。与「归档为空」区分开，别让人以为归档丢了。
                rows.isEmpty() && ids.isNotEmpty() ->
                    EmptyStateColumn(Icons.Default.Inventory2, Strings.unavailableArchivedSessions, null)
                rows.isEmpty() ->
                    EmptyStateColumn(Icons.Default.Inventory2, Strings.noArchived, null)
                // 有行但被查询滤空：这是搜索无匹配，不是归档为空。
                visibleRows.isEmpty() ->
                    EmptyStateColumn(Icons.Default.SearchOff, Strings.searchNoResults, query)
                else ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(visibleRows, key = { it.sessionId }) { summary ->
                            ArchivedSessionRow(
                                model = model,
                                summary = summary,
                                workspaceName = sessionWorkspaceName(
                                    model.workspaceStore.workspaceTitleBySession[summary.sessionId],
                                    summary.cwd,
                                ) ?: Strings.ungrouped,
                                onOpen = { onOpenSession(summary.sessionId) },
                            )
                        }
                    }
            }
        }
    }
}

/** 一行已归档会话：标题，加一行「工作区 · 最近活动」，右侧是取消归档按钮。 */
@Composable
private fun ArchivedSessionRow(
    model: PhoneModel,
    summary: SessionSummary,
    workspaceName: String,
    onOpen: () -> Unit,
) {
    val title = sessionDisplayTitle(summary, null)
    val restoring = model.unarchivingIds.contains(summary.sessionId)
    ListItem(
        modifier = Modifier.clickable { onOpen() },
        headlineContent = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                "${workspaceName} · ${relativeTime(summary.updatedAt)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            if (restoring) {
                // 进行中就不给按钮了：进度指示本身也是防重复点击的那道闸。
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = { model.unarchive(summary.sessionId) }) {
                    Text(
                        Strings.unarchiveSession,
                        modifier = Modifier.semantics {
                            contentDescription = fmt(Strings.unarchiveSessionNamed, title)
                        },
                    )
                }
            }
        },
    )
}

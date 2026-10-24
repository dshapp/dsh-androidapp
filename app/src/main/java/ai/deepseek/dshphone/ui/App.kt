package ai.deepseek.dshphone.ui

import ai.deepseek.dshphone.AppUpdater
import ai.deepseek.dshphone.LaunchRequest
import ai.deepseek.dshphone.state.Connection
import ai.deepseek.dshphone.state.PhoneModel
import ai.deepseek.dshphone.state.PairingPayload
import ai.deepseek.dshphone.ui.component.ConnectionBanner
import ai.deepseek.dshphone.ui.screen.ArchivedSessionsScreen
import ai.deepseek.dshphone.ui.screen.ChatScreen
import ai.deepseek.dshphone.ui.screen.NewSessionScreen
import ai.deepseek.dshphone.ui.screen.OnboardingScreen
import ai.deepseek.dshphone.ui.screen.PairingScreen
import ai.deepseek.dshphone.ui.screen.SettingsScreen
import ai.deepseek.dshphone.ui.screen.AutomationsScreen
import ai.deepseek.dshphone.state.AutomationManagementState
import ai.deepseek.dshphone.ui.screen.SessionListScreen
import ai.deepseek.dshphone.i18n.Strings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun DshApp(model: PhoneModel, launch: LaunchRequest?, onLaunchHandled: () -> Unit = {}) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val navController = rememberNavController()
    val api = model.managementApi
    val automationState = remember(api) { AutomationManagementState() }
    var scanning by remember { mutableStateOf(false) }

    // 宽屏（平板、折叠屏展开、手机横屏）：会话列表与会话详情并排放在同一页（对齐 Mac 的
    // 边栏 + 详情），点列表只换右侧内容，不再整页跳转。窄屏仍是列表 → 会话两页导航。
    val windowWidth = maxWidth
    val wide = windowWidth >= WIDE_LAYOUT_MIN_WIDTH
    // 宽屏右侧此刻显示什么：DetailPane 编码的字符串（可存进 rememberSaveable，转屏重建不丢）。
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    // 新建会话页关掉时退回的那条会话：从会话 A 点「新建」又放弃，右侧应回到 A 而不是空白。
    var detailBeforeNew by rememberSaveable { mutableStateOf<String?>(null) }

    fun openChat(sessionId: String) {
        if (wide) {
            detail = DetailPane.chat(sessionId)
            // 从归档页、自动化页等整页里打开会话：回到并排页，让它出现在右侧。
            navController.popBackStack("root", inclusive = false)
        } else {
            navController.navigate("chat/$sessionId")
        }
    }

    fun openNew(draftRoute: String) {
        if (wide) {
            if (DetailPane.chatId(detail) != null) detailBeforeNew = detail
            detail = draftRoute
            navController.popBackStack("root", inclusive = false)
        } else {
            navController.navigate(draftRoute)
        }
    }

    // 窄 ↔ 宽切换（转屏、折叠屏开合、分屏拖动）时把"正在看的那条会话"带过去：
    //  · 窄 → 宽：栈顶是会话页就收回并排页并在右侧选中它；栈顶是别的页（列表、设置…）
    //    说明窄屏下没有在看会话，右侧清空，免得冒出一条很早以前选过的会话。
    //  · 宽 → 窄：并排页右侧有会话就推一页会话页，用户眼前的内容不丢；返回键回到列表。
    // 上一次的宽窄要存进 rememberSaveable：转屏会重建 Activity，只用 remember 的话重建后
    // 永远是"第一次"，切换逻辑一次也跑不到。
    var lastWide by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(wide) {
        val previous = lastWide
        lastWide = wide
        if (previous == null || previous == wide) return@LaunchedEffect
        val entry = navController.currentBackStackEntry
        val route = entry?.destination?.route
        if (wide) {
            if (route == "chat/{sessionId}") {
                val sessionId = entry.arguments?.getString("sessionId")
                detail = sessionId?.let { DetailPane.chat(it) }
                navController.popBackStack("root", inclusive = false)
            } else {
                detail = null
            }
        } else if (route == "root") {
            val current = detail
            val sessionId = DetailPane.chatId(current)
            when {
                sessionId != null -> navController.navigate("chat/$sessionId")
                current == DetailPane.NEW || current == DetailPane.NEW_AUTOMATION -> navController.navigate(current)
            }
        }
    }

    // 宽屏右侧的会话没了（归档、主机删除）：别留着一页"会话已消失"的空壳。
    val detailSessionId = DetailPane.chatId(detail)
    val detailGone = detailSessionId != null && model.listStore.sessionsById.isNotEmpty() &&
        model.listStore.sessionsById[detailSessionId] == null && model.sessions[detailSessionId] == null
    val detailArchived = detailSessionId != null &&
        (model.workspaceStore.archivedSessionIds.contains(detailSessionId) ||
            model.listStore.archivedSessionIds.contains(detailSessionId))
    LaunchedEffect(detailGone, detailArchived) {
        if (wide && (detailGone || detailArchived)) detail = null
    }

    LaunchedEffect(Unit) { model.connectCurrent() }

    LaunchedEffect(launch) {
        val payload = launch?.deepLink ?: return@LaunchedEffect
        val profile = model.profiles.add(PairingPayload.suggestedName(payload), payload)
        if (profile != null) {
            scanning = false
            model.connect(profile)
        }
        // 消费放在最后：清掉待办会让这个 effect 带着新 key 重启，提前清就是在给自己
        // 的后半截安排一次取消。链接解析不出来也照样消费，否则它会一直挂着。
        onLaunchHandled()
    }

    // 通知卡片点进来的那条会话：等这次连接有了结论再落地。
    //
    // 不能一收到就导航：冷启动时 connectCurrent 还在飞，它的第一步 teardown 会清空
    // sessions 与 selectedSessionId，抢在它前面开出来的那份 store 会被一起清掉；而会话
    // 页只在进场时 open 一次，于是转写就永远停在空白。等一等，对齐 Mac 把点击先缓存在
    // router 里、boot 跑完才 drainPendingOpen 的次序。
    //
    // 连不上（Failed）也照样落地：卡片说的是"去看这条会话"，会话页自己带着报错与重试，
    // 比停在列表上什么都不说要诚实；重连后 follow 会把基线补上。
    val settled = model.connection is Connection.Connected || model.connection is Connection.Failed
    LaunchedEffect(launch, settled) {
        val sessionId = launch?.openSessionId ?: return@LaunchedEffect
        if (!settled) return@LaunchedEffect
        if (wide) {
            // 并排页：卡片要看的会话直接放到右侧，盖在上面的整页（设置、归档…）一并收起。
            detail = DetailPane.chat(sessionId)
            navController.popBackStack("root", inclusive = false)
        } else {
            navController.navigate("chat/$sessionId") {
                // 回退键回到会话列表：卡片是"跳到这条会话"，不是在当前这条上再叠一层。
                popUpTo("root")
                launchSingleTop = true
            }
        }
        onLaunchHandled()
    }

    // 根容器只做一件事：把全局连接提示条盖在导航内容之上，任何页面都看得到。
    // 提示条自己吃掉状态栏内边距，所以不会被系统栏压住；其余页面照旧从顶栏开始画。
    Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = "root") {
            composable("root") {
                if (model.profiles.profiles.isEmpty()) {
                    OnboardingScreen(onScan = { scanning = true })
                } else if (wide) {
                    WideSessionLayout(
                        model = model,
                        width = windowWidth,
                        detail = detail,
                        onOpenSession = { openChat(it) },
                        onOpenSettings = { navController.navigate("settings") },
                        onNewSession = { openNew(DetailPane.NEW) },
                        onOpenArchived = { navController.navigate("archived") },
                        onOpenAutomations = { navController.navigate("automations") },
                        onCloseNew = { detail = detailBeforeNew; detailBeforeNew = null },
                        onCreated = { detailBeforeNew = null; detail = DetailPane.chat(it) },
                    )
                } else {
                    SessionListScreen(
                        model = model,
                        onOpenSession = { openChat(it) },
                        onOpenSettings = { navController.navigate("settings") },
                        onNewSession = { openNew(DetailPane.NEW) },
                        onOpenArchived = { navController.navigate("archived") },
                        onOpenAutomations = { navController.navigate("automations") },
                    )
                }
            }
            composable("automations") {
                if (api != null) AutomationsScreen(
                    api = api,
                    onBack = { navController.popBackStack() },
                    onNewTask = { openNew(DetailPane.NEW_AUTOMATION) },
                    state = automationState,
                )
                else Text(Strings.connect)
            }
            composable("archived") {
                ArchivedSessionsScreen(
                    model = model,
                    onBack = { navController.popBackStack() },
                    // 打开归档里的会话走同一个会话页；返回键依次回到归档页、会话列表。
                    onOpenSession = { openChat(it) },
                )
            }
            composable("settings") {
                SettingsScreen(
                    model = model,
                    onBack = { navController.popBackStack() },
                    onScan = { scanning = true },
                )
            }
            composable("new") {
                NewSessionScreen(
                    model = model,
                    onBack = { navController.popBackStack() },
                    // 发出第一条消息后这页就没用了：换成会话页，返回键直接回到列表。
                    onCreated = {
                        if (wide) {
                            detail = DetailPane.chat(it)
                            navController.popBackStack("root", inclusive = false)
                        } else navController.navigate("chat/$it") {
                            popUpTo("new") { inclusive = true }
                        }
                    },
                )
            }
            // 自动化页的「新建」：同一个新建会话页，输入框预填创建定时任务的开头。
            composable("new-automation") {
                NewSessionScreen(
                    model = model,
                    onBack = { navController.popBackStack() },
                    onCreated = {
                        if (wide) {
                            detail = DetailPane.chat(it)
                            navController.popBackStack("root", inclusive = false)
                        } else navController.navigate("chat/$it") {
                            popUpTo("new-automation") { inclusive = true }
                        }
                    },
                    draft = ai.deepseek.dshphone.i18n.ManagementStrings.newTaskDraft,
                )
            }
            composable("chat/{sessionId}") { entry ->
                val sessionId = entry.arguments?.getString("sessionId") ?: return@composable
                ChatScreen(
                    model = model,
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                    onOpenSession = { openChat(it) },
                )
            }
        }

        if (scanning) {
            PairingScreen(
                onDismiss = { scanning = false },
                onPaired = { name, payload ->
                    val profile = model.profiles.add(name, payload)
                    scanning = false
                    if (profile != null) model.connect(profile)
                },
            )
        }

        model.archiveNeedsStop?.let { sessionId ->
            AlertDialog(
                onDismissRequest = { model.archiveNeedsStop = null },
                title = { Text(Strings.archiveActiveTitle) },
                text = { Text(Strings.archiveActiveMessage) },
                confirmButton = {
                    TextButton(onClick = {
                        model.archiveNeedsStop = null
                        model.archive(sessionId, stopActivity = true)
                    }) { Text(Strings.stopAndArchive) }
                },
                dismissButton = {
                    TextButton(onClick = { model.archiveNeedsStop = null }) { Text(Strings.cancel) }
                },
            )
        }

        AppUpdater.ready?.let { update ->
            val context = LocalContext.current
            AlertDialog(
                onDismissRequest = { AppUpdater.later(context) },
                title = { Text(Strings.updateReadyTitle(update.version)) },
                text = {
                    Text(
                        listOf(update.notes, Strings.updateLaterHint).filter { it.isNotEmpty() }.joinToString("\n\n"),
                        Modifier.verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { AppUpdater.installNow(context) }) { Text(Strings.updateInstallNow) }
                },
                dismissButton = {
                    TextButton(onClick = { AppUpdater.later(context) }) { Text(Strings.updateLater) }
                },
            )
        }

        ConnectionBanner(
            model = model,
            modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
        )
    }
}

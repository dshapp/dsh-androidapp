package ai.deepseek.dshphone.ui

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
import ai.deepseek.dshphone.ui.screen.PluginManagerScreen
import ai.deepseek.dshphone.ui.screen.AutomationsScreen
import ai.deepseek.dshphone.state.PluginManagementState
import ai.deepseek.dshphone.state.AutomationManagementState
import ai.deepseek.dshphone.ui.screen.SessionListScreen
import ai.deepseek.dshphone.i18n.Strings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
fun DshApp(model: PhoneModel, launch: LaunchRequest?, onLaunchHandled: () -> Unit = {}) {
    val navController = rememberNavController()
    val api = model.managementApi
    val pluginState = remember(api) { PluginManagementState() }
    val automationState = remember(api) { AutomationManagementState() }
    var scanning by remember { mutableStateOf(false) }

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
        navController.navigate("chat/$sessionId") {
            // 回退键回到会话列表：卡片是"跳到这条会话"，不是在当前这条上再叠一层。
            popUpTo("root")
            launchSingleTop = true
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
                } else {
                    SessionListScreen(
                        model = model,
                        onOpenSession = { navController.navigate("chat/$it") },
                        onOpenSettings = { navController.navigate("settings") },
                        onNewSession = { navController.navigate("new") },
                        onOpenArchived = { navController.navigate("archived") },
                        onOpenPlugins = { navController.navigate("plugins") },
                        onOpenAutomations = { navController.navigate("automations") },
                    )
                }
            }
            composable("plugins") {
                if (api != null) PluginManagerScreen(api = api, onBack = { navController.popBackStack() }, state = pluginState)
                else Text(Strings.connect)
            }
            composable("automations") {
                if (api != null) AutomationsScreen(
                    api = api,
                    onBack = { navController.popBackStack() },
                    onNewTask = { navController.navigate("new-automation") },
                    state = automationState,
                )
                else Text(Strings.connect)
            }
            composable("archived") {
                ArchivedSessionsScreen(
                    model = model,
                    onBack = { navController.popBackStack() },
                    // 打开归档里的会话走同一个会话页；返回键依次回到归档页、会话列表。
                    onOpenSession = { navController.navigate("chat/$it") },
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
                        navController.navigate("chat/$it") {
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
                        navController.navigate("chat/$it") {
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
                    onOpenSession = { navController.navigate("chat/$it") },
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

        ConnectionBanner(
            model = model,
            modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
        )
    }
}

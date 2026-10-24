package ai.deepseek.dshphone

import ai.deepseek.dshphone.state.PhoneModel
import ai.deepseek.dshphone.ui.DshApp
import ai.deepseek.dshphone.ui.theme.DshTheme
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {

    private lateinit var model: PhoneModel
    private lateinit var notifications: AgentNotifications

    /**
     * 还没落地的一次外部唤起：扫码配对链接，或通知卡片要打开的会话。
     *
     * 存在 Activity 上而不是只在 setContent 里读一次 `intent`：卡片是在 App 已经
     * 起着（前台或后台）的时候点的，那一下走 [onNewIntent]，composition 早就建好了，
     * 只在 onCreate 取一次的话换进来的 Intent 谁也不会再看一眼——点了没反应就是这么来的。
     */
    private val launch = mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 连接与通知都挂在 Application 上：退到后台 Activity 被销毁时，它们不跟着走，
        // 后台的完成/失败帧才收得到。这里只是把同一份进程级状态拿回来。
        model = (application as DshApplication).model
        notifications = (application as DshApplication).notifications
        // 跟随系统时，系统语言可能在两次组合之间变过（切系统语言会重建 Activity）；
        // 显式选过语言的不动，那条偏好比系统语言优先。
        model.settings.refreshSystemLocale()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                // 之前这里是个空回调：用户点了"不允许"也看不出任何痕迹，
                // 之后所有通知就这样静默消失。至少记一条，便于下次排查。
                if (!granted) Log.w(TAG, "POST_NOTIFICATIONS denied; agent notifications will not appear")
            }.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 重建（旋屏、切深色）会拿着同一个粘着的 Intent 再跑一遍 onCreate：落地过的
        // 唤起不能再算一次，否则用户从卡片进来、退回了列表，一转屏又被拽回会话里。
        if (savedInstanceState?.getBoolean(LAUNCH_HANDLED) != true) {
            launch.value = LaunchRequest.from(intent)
        }
        // 不是重建而是重新打开：上次「下次再说」的新版本此时安装。
        if (savedInstanceState == null) AppUpdater.onAppOpened(this)
        setContent {
            DshTheme(theme = model.settings.themeMode) {
                DshApp(model, launch.value, onLaunchHandled = { launch.value = null })
            }
        }
    }

    /** App 已经起着的时候点卡片 / 扫码链接：Intent 从这里进来。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        LaunchRequest.from(intent)?.let { launch.value = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 这次唤起落地了没有。存着的是"已经落地"而不是唤起本身：还没落地的（连接刚起来
        // 之前点的那一下）重建后由 Intent 原样再给一次，不会丢。
        outState.putBoolean(LAUNCH_HANDLED, launch.value == null)
    }

    override fun onResume() {
        super.onResume()
        if (!::model.isInitialized) return
        model.appIsActive = true
        // 回到前台，屏幕上停着的那条会话就是用户正在读的，它的卡片已经完成使命
        //（对齐 Mac `UserNotificationRouter.onAppActivated`）。
        model.visibleSessionId?.let { notifications.sessionOpened(it) }
        model.onForeground()
        AppUpdater.onForeground(this)
    }

    override fun onPause() {
        if (::model.isInitialized) model.appIsActive = false
        super.onPause()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val LAUNCH_HANDLED = "launch.handled"
    }
}

/**
 * 一次外部唤起要做的事。
 *
 * [token] 让"同一张卡片再点一次"也算一次新的唤起：Compose 的 LaunchedEffect 按值
 * 比较 key，只带着会话 id 的话第二次点击和第一次长得一模一样，effect 不会重跑，
 * 于是用户退回列表后再点同一张卡片就没了反应。
 */
data class LaunchRequest(
    val token: Long,
    /** 扫码配对链接（dshm://…）。 */
    val deepLink: String? = null,
    /** 通知卡片要落地的会话。 */
    val openSessionId: String? = null,
) {
    companion object {
        private val tokens = AtomicLong()

        /** 桌面图标那种不带任何去向的启动返回 null。 */
        fun from(intent: Intent?): LaunchRequest? {
            if (intent == null) return null
            val sessionId = AgentNotifications.sessionId(intent)
            val deepLink = intent.data?.toString()
            if (sessionId == null && deepLink == null) return null
            return LaunchRequest(tokens.incrementAndGet(), deepLink, sessionId)
        }
    }
}

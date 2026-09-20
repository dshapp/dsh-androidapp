package ai.deepseek.dshphone

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.model.isSubagentOrigin
import ai.deepseek.dshphone.model.sessionDisplayTitle
import ai.deepseek.dshphone.model.turnFailureNotificationBody
import ai.deepseek.dshphone.state.AttentionSignal
import ai.deepseek.dshphone.state.PhoneModel
import ai.deepseek.dshphone.state.TurnCardState
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 完成 / 失败 / 待回答的系统通知，与 iOS 的 AgentNotificationCenter 同一套判定：
 * 只为"不在屏幕上"的会话发通知，撤销由主机的 cancel 帧驱动。
 */
class AgentNotifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)
    /** 完成/失败卡片的去重与抑制规则（含"本轮失败过就不补完成卡"）；判定逻辑见 [TurnCardState]。 */
    private val cards = TurnCardState()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL,
                Strings.appName,
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun handle(signal: AttentionSignal, model: PhoneModel) {
        when (signal) {
            is AttentionSignal.Running -> {
                val id = signal.sessionId
                if (signal.running) {
                    cards.start(id)
                } else if (cards.stop(id)) {
                    deliverDone(model, id)
                }
            }
            // 基线里补出来的完成边沿（断线重连、甚至进程被回收后重开）。走同一道
            // 判定，但不去看本地 running 集合——那个集合可能随进程重启没了。
            is AttentionSignal.SessionCompleted -> {
                cards.completeFromBaseline(signal.sessionId)
                deliverDone(model, signal.sessionId)
            }
            is AttentionSignal.Failed -> {
                // 失败卡才是这一轮的真实结局：记下来，让紧随其后的 turn/end 不再补一张
                // "这一轮已完成"把失败顶掉（提交前用同一个通知 id，用户只看得到好消息）。
                cards.fail(signal.sessionId)
                if (!model.isOnScreen(signal.sessionId)) {
                    // 卡片的正文是主机给出的失败原因（对齐 Mac failureBody）；
                    // 固定写死一句"这一轮失败"会把"为什么挂"整条丢掉。
                    post(model, signal.sessionId, turnFailureNotificationBody(signal.message))
                }
            }
            is AttentionSignal.ApprovalRequested -> {
                val id = signal.approval.sessionId
                if (!model.isOnScreen(id)) post(model, id, Strings.notifyApproval)
            }
            is AttentionSignal.QuestionAsked -> {
                val id = signal.question.sessionId
                if (!model.isOnScreen(id)) post(model, id, Strings.notifyQuestion)
            }
            is AttentionSignal.WaterfallRetired -> withdraw(signal.sessionId)
        }
    }

    /**
     * 用户已经在看这条会话了：它的卡片已经完成使命（对齐 Mac / iOS 的
     * `AgentNotificationCenter.sessionOpened`）。点开卡片本身由 [setAutoCancel]
     * 收走，这条路管的是"用户从别处进了这条会话"——例如自己点开列表行，或者
     * 从后台切回来时会话页正停在这条会话上。
     */
    fun sessionOpened(sessionId: String) {
        withdraw(sessionId)
    }

    private fun title(model: PhoneModel, sessionId: String): String =
        model.listStore.sessionsById[sessionId]
            ?.let { sessionDisplayTitle(it, model.sessions[sessionId]?.title) }
            ?: Strings.untitledSession

    /**
     * 子智能体不发通知（与 Mac 的 AgentNotificationCenter.post 同一道闸）。
     *
     * 一次委派会派生出一串子会话，它们的开始/结束/失败都会走同一条注意力信号；
     * 放行的话，用户为一次委派收到的是一把互相看不懂的卡片，而且点开也没有对应的
     * 列表行可落地——子智能体只在父会话的子智能体面板里存在。父会话自己的完成/
     * 失败通知照常发，该提醒的那一条不会丢。
     */
    private fun post(model: PhoneModel, sessionId: String, body: String) {
        if (model.listStore.sessionsById[sessionId]?.isSubagentOrigin == true) return
        post(sessionId, title(model, sessionId), body)
    }

    /** 完成卡片只发一次，且本轮失败过就不发；"用户正在看这条会话"时不发，但同样标记为已处理。 */
    private fun deliverDone(model: PhoneModel, sessionId: String) {
        if (!cards.shouldDeliverDone(sessionId)) return
        if (!model.isOnScreen(sessionId)) post(model, sessionId, Strings.notifyDone)
    }

    private fun post(sessionId: String, title: String, body: String) {
        // 静默失败是这套通知最难查的地方：用户关了通知、或系统拒绝时，
        // notify 会抛 SecurityException，吞掉就只剩"什么都没发生"。
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "drop notification for $sessionId: notifications are disabled")
            return
        }
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(openSessionIntent(sessionId))
            .build()
        try {
            manager.notify(sessionId.hashCode(), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notify rejected for $sessionId: ${e.message}")
        }
    }

    /**
     * 卡片的点击去向：把会话 id 交回 [MainActivity]，由它导航到这条会话
     * （对齐 Mac 的 `userInfo[sessionId]` + `UserNotificationRouter.openSession`）。
     *
     * 没有 contentIntent 的通知在 Android 上是"只能看不能点"的：点一下什么也不会
     * 发生，卡片连自己都不会消失——这就是后台收到通知、点了却回不到 App 的原因。
     *
     * requestCode 取会话 id 的哈希（与通知 id 同一个值）：PendingIntent 的去重只看
     * requestCode 和 Intent 的"过滤部分"（component / action / data / category），
     * extras 不参与比较。所有卡片共用一个 requestCode 的话，[PendingIntent.FLAG_UPDATE_CURRENT]
     * 会把此前那些卡片的 extras 一起改写成最后一次的会话——托盘里躺着三张卡片，
     * 点哪一张都落到最新那条会话上。
     */
    private fun openSessionIntent(sessionId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_SESSION)
            .putExtra(EXTRA_SESSION_ID, sessionId)
            // 已经在栈上的实例接着用（走 onNewIntent），不要另起一个 Activity：
            // 新实例会重新建一份 PhoneModel，正在跑的连接、已经拉到的转写都得从头来。
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun withdraw(sessionId: String) {
        runCatching { manager.cancel(sessionId.hashCode()) }
    }

    companion object {
        private const val TAG = "AgentNotifications"
        private const val CHANNEL = "dsh.agent"

        /** 通知卡片唤起 [MainActivity] 用的 action，只是个显式 Intent 上的标记。 */
        const val ACTION_OPEN_SESSION = "ai.deepseek.dshphone.action.OPEN_SESSION"

        /** 卡片带回来的会话 id（对齐 Mac 的 `AgentNotificationUserInfoKey.sessionId`）。 */
        const val EXTRA_SESSION_ID = "sessionId"

        /**
         * 从唤起 Intent 里读出要落地的会话；不是通知卡片带来的就是 null。
         *
         * 会话 id 从 extras 里取而不是从通知 id 反推：通知 id 是哈希，反推不回来。
         */
        fun sessionId(intent: Intent): String? =
            intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotEmpty() }
    }
}

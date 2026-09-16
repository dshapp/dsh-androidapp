package ai.deepseek.dshphone

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.model.sessionDisplayTitle
import ai.deepseek.dshphone.state.AttentionSignal
import ai.deepseek.dshphone.state.PhoneModel
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 完成 / 失败 / 待回答的系统通知，与 iOS 的 AgentNotificationCenter 同一套判定：
 * 只为"不在屏幕上"的会话发通知，撤销由主机的 cancel 帧驱动。
 */
class AgentNotifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)
    private val running = mutableSetOf<String>()

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
                    running.add(id)
                } else if (running.remove(id) && model.selectedSessionId != id) {
                    post(id, title(model, id), Strings.notifyDone)
                }
            }
            is AttentionSignal.Failed -> {
                if (model.selectedSessionId != signal.sessionId) {
                    post(signal.sessionId, title(model, signal.sessionId), Strings.notifyFailed)
                }
            }
            is AttentionSignal.ApprovalRequested -> {
                val id = signal.approval.sessionId
                if (model.selectedSessionId != id) post(id, title(model, id), Strings.notifyApproval)
            }
            is AttentionSignal.QuestionAsked -> {
                val id = signal.question.sessionId
                if (model.selectedSessionId != id) post(id, title(model, id), Strings.notifyQuestion)
            }
            is AttentionSignal.WaterfallRetired -> withdraw(signal.sessionId)
        }
    }

    private fun title(model: PhoneModel, sessionId: String): String =
        model.listStore.sessionsById[sessionId]
            ?.let { sessionDisplayTitle(it, model.sessions[sessionId]?.title) }
            ?: Strings.untitledSession

    private fun post(sessionId: String, title: String, body: String) {
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(sessionId.hashCode(), notification) }
    }

    private fun withdraw(sessionId: String) {
        runCatching { manager.cancel(sessionId.hashCode()) }
    }

    companion object {
        private const val CHANNEL = "dsh.agent"
    }
}

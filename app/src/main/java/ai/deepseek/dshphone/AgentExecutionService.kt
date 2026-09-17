package ai.deepseek.dshphone

import ai.deepseek.dshphone.i18n.Strings
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 任务运行期间的常驻前台服务。
 *
 * Android 不会让一个普通后台进程继续持有实时连接：退到后台几秒内进程就被冻结
 * （本机实测 HyperOS 会直接进入 `idle`），WebSocket 的收发与重连随之停摆——
 * 而完成/失败/待回答的通知全部来自这条流的实时帧，于是通知永远不来。
 *
 * 常驻服务把进程钉在"前台服务"这个最高的后台优先级上，[ai.deepseek.dshphone.state.PhoneModel]
 * 的连接就能一直活着。它只在真的有任务在跑（[PhoneModel.onRunActivity] 报 true）时起，
 * 最后一个任务结束时自己停掉，不会长期占着一条通知。
 *
 * 服务本身不持有连接：连接挂在 [DshApplication] 的 PhoneModel 上，服务只负责
 * "别让系统把进程收走"这一件事。
 */
class AgentExecutionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notification = buildNotification()
        // Android 12+ 在"从后台拉起前台服务"这一下会抛 ForegroundServiceStartNotAllowedException
        // （APP 当时没在前台、也没豁免）。抓下来自己收摊：否则 `START_STICKY` 被系统重启后
        // 每次都在 onCreate 崩一遍，变成一个看不见的崩溃循环。
        val started = runCatching {
            // Android 14 起必须声明服务类型；DATA_SYNC 对应"维持一条网络连接"。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            Log.w(TAG, "startForeground rejected: ${it.javaClass.simpleName}: ${it.message}")
        }
        if (started.isFailure) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL,
            Strings.appName,
            NotificationManager.IMPORTANCE_MIN,
        ).apply { description = Strings.notifyServiceDescription }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(Strings.appName)
            .setContentText(Strings.notifyServiceRunning)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        private const val TAG = "AgentExecutionService"
        private const val CHANNEL = "dsh.keepalive"

        /** 常驻通知的固定 id：与按会话 id 哈希的完成卡片互不冲突。 */
        const val NOTIFICATION_ID = 0x44534801

        fun start(context: Context) {
            val intent = Intent(context, AgentExecutionService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "start failed: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentExecutionService::class.java)) }
        }
    }
}

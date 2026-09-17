package ai.deepseek.dshphone

import ai.deepseek.dshphone.state.AttentionSignal
import ai.deepseek.dshphone.state.PhoneModel
import android.app.Application

/**
 * 进程的组装点。
 *
 * 连接、通知与"任务运行期间的常驻服务"都挂着进程走，而不是挂着 Activity 走：
 * 用户把 App 退到后台后 Activity 会被销毁，挂在它身上的东西会一起消失，后台也就
 * 再也收不到 `\$events` 的完成/失败帧。把它们提前到 [onCreate] 建好并接上线，
 * Activity 重建（旋屏、切深色）只是重新 attach 同一份状态。
 */
class DshApplication : Application() {

    lateinit var model: PhoneModel
        private set

    lateinit var notifications: AgentNotifications
        private set

    override fun onCreate() {
        super.onCreate()
        val phoneModel = PhoneModel.get(this)
        val center = AgentNotifications(this)
        phoneModel.notifier = { signal -> center.handle(signal, phoneModel) }
        phoneModel.onSessionOpened = { sessionId -> center.sessionOpened(sessionId) }

        // 列表层从基线里算出的"运行 -> 停止"边沿也接到通知上：断线重连（甚至进程
        // 被系统回收后重开）时，$events 里错过的完成帧由它补发。
        phoneModel.listStore.onCompleted = { sessionId ->
            center.handle(AttentionSignal.SessionCompleted(sessionId), phoneModel)
        }

        // 有任务在跑就把进程钉在前台服务上，别让系统把后台连接冻死。
        // 没有任务时不起服务，保持"只在真需要时占一条通知"。
        phoneModel.onRunActivity = { active ->
            if (active) AgentExecutionService.start(this) else AgentExecutionService.stop(this)
        }

        model = phoneModel
        notifications = center
    }
}

package ai.deepseek.dshphone.state

/**
 * 完成/失败卡片的去重与抑制规则，纯逻辑（对齐 Mac `AgentNotificationCenter` 的
 * running / failedDuringRun / deliveredDone 三个集合及它们的守卫顺序）。
 *
 * 抽出来是因为"该不该发这张卡"是通知里唯一能出错又最难在设备上复现的部分：
 * 卡片投递本身交给系统，而这里决定的是语义——同一轮只发一张完成卡、本轮失败过就
 * 不能再补一张"已完成"，否则"这一轮失败"会被随后的"这一轮已完成"顶掉，通知栏里
 * 只剩好消息。
 */
internal class TurnCardState {

    /** 正在跑的会话；完成卡只在"亲眼看它跑过"之后才发。 */
    private val running = mutableSetOf<String>()

    /** 本轮已经失败过：失败卡讲完了这一轮的故事，完成卡必须让位。 */
    private val failedDuringRun = mutableSetOf<String>()

    /** 已经发过完成卡的会话，实时帧与基线补发同一天到达时不会互相盖一遍。 */
    private val deliveredDone = mutableSetOf<String>()

    /** 开始跑：清掉上一轮的失败标记，并让本轮还能再发一张完成卡。 */
    fun start(sessionId: String) {
        running.add(sessionId)
        failedDuringRun.remove(sessionId)
        deliveredDone.remove(sessionId)
    }

    /** 从运行落到停止；返回这次是否为真边沿（没有前一次运行就不算）。 */
    fun stop(sessionId: String): Boolean = running.remove(sessionId)

    /** 基线里补出来的完成边沿（断线重连、进程被回收后重开）。 */
    fun completeFromBaseline(sessionId: String) {
        running.remove(sessionId)
    }

    /** 收到失败。 */
    fun fail(sessionId: String) {
        failedDuringRun.add(sessionId)
    }

    /** 会话归档或移除：整套状态忘掉，避免 id 复用后串味。 */
    fun forget(sessionId: String) {
        running.remove(sessionId)
        failedDuringRun.remove(sessionId)
        deliveredDone.remove(sessionId)
    }

    /**
     * 该不该投递这张完成卡。
     *
     * 两道闸：本轮已经发过失败卡（失败才是这一轮的真实结局），或者同一轮已经发过一张
     * 完成卡。锁记在 `deliveredDone` 里，"用户正在看这条会话"由调用方另行判定，
     * 但仍要标记为已处理。
     */
    fun shouldDeliverDone(sessionId: String): Boolean {
        if (failedDuringRun.contains(sessionId)) return false
        return deliveredDone.add(sessionId)
    }
}

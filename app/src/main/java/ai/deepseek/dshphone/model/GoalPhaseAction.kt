package ai.deepseek.dshphone.model

/** 目标条上暂停/恢复那一项的落点。 */
enum class GoalPhaseAction { PAUSE, RESUME }

/**
 * 当前 phase 该给哪个动作；null = 条上不给这一项。
 *
 * 只映射主机真的接受的转移（dsh-goal `pause`/`resume`）：`pause` 仅 active，
 * `resume` 仅 paused/active/blocked。web 的 GoalBar 只有
 * `showResume = paused || (active && disarmed)`、pause 仅 `active && armed`；Mac 的
 * GoalBarContent 同样只画 active→pause、paused→resume。所以 blocked 两个都不给——
 * 对它发 pause 必被主机回 GOAL_INVALID_TRANSITION，那个按钮点了只会报错，永远不成。
 */
fun goalPhaseAction(phase: String): GoalPhaseAction? = when (phase) {
    "active" -> GoalPhaseAction.PAUSE
    "paused" -> GoalPhaseAction.RESUME
    else -> null
}

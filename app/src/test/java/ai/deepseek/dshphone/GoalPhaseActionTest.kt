package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.GoalPhaseAction
import ai.deepseek.dshphone.model.goalPhaseAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 目标条那颗暂停/恢复动作，只能落在主机真的允许的转移上（dsh-goal `pause`/`resume`）：
 * pause 只接受 active，resume 接受 paused/blocked。web 与 Mac 的 GoalBar 对 blocked
 * 两个动作都不给（`showResume = paused || (active && disarmed)`；Mac 只画 active→pause、
 * paused→resume），因为对 blocked 发 pause 必然被主机回 GOAL_INVALID_TRANSITION。
 */
class GoalPhaseActionTest {

    @Test
    fun activeOffersPause() {
        assertEquals(GoalPhaseAction.PAUSE, goalPhaseAction("active"))
    }

    @Test
    fun pausedOffersResume() {
        assertEquals(GoalPhaseAction.RESUME, goalPhaseAction("paused"))
    }

    /** 对 blocked 发 pause 是主机明确拒绝的转移，条上不能给出这个动作。 */
    @Test
    fun blockedOffersNoPause() {
        assertNull("pause from blocked is rejected by the host", goalPhaseAction("blocked"))
    }

    @Test
    fun completeOffersNothing() {
        assertNull(goalPhaseAction("complete"))
    }

    @Test
    fun unknownPhaseOffersNothing() {
        assertNull(goalPhaseAction("whatever"))
    }
}

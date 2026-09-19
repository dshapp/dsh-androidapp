package ai.deepseek.dshphone

import ai.deepseek.dshphone.state.TurnCardState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本轮失败过，就不能再补一张"已完成"。
 *
 * Mac 的 AgentNotificationCenter 用一个 failedDuringRun 集合做这道闸，注释写得很直白：
 * 「The failure card already tells this run's story, and it is the true one.」
 * 手机端原来没有这个集合：一次失败先发"这一轮失败"卡片，紧接着 turn/end 把会话从运行
 * 落到停止，完成卡又用同一个通知 id（sessionId.hashCode()）盖上去——用户最后看到的
 * 是"这一轮已完成"，一次失败被说成了成功。
 *
 * 同一轮的第二张完成卡、以及没有前一次运行的"完成"，同样不该发。
 */
class TurnCardStateTest {

    private val session = "session-a"

    private fun beganRun(): TurnCardState = TurnCardState().apply { start(session) }

    @Test
    fun failureSuppressesTheDoneCardForTheSameRun() {
        val state = beganRun()
        state.fail(session)
        state.stop(session)
        assertFalse("本轮已经发过失败卡，完成卡不能再盖上去", state.shouldDeliverDone(session))
    }

    @Test
    fun doneCardIsNotSwallowedWithoutAFailure() {
        val state = beganRun()
        assertTrue(state.stop(session))
        assertTrue("没失败过的正常完成必须照发", state.shouldDeliverDone(session))
    }

    @Test
    fun doneCardIsDeliveredOnlyOncePerRun() {
        val state = beganRun()
        state.stop(session)
        assertTrue(state.shouldDeliverDone(session))
        // 实时帧与基线补发同一天到达时，第二张不发。
        assertFalse("同一轮只发一张完成卡", state.shouldDeliverDone(session))
    }

    @Test
    fun aFreshRunClearsThePreviousFailure() {
        val state = beganRun()
        state.fail(session)
        state.stop(session)
        assertFalse(state.shouldDeliverDone(session))
        // 用户又让它跑了一轮：上一轮的失败不再代表这一轮。
        state.start(session)
        state.stop(session)
        assertTrue("新一轮的完成卡不该被上一轮的失败压住", state.shouldDeliverDone(session))
    }

    @Test
    fun stopWithoutAPreviousRunIsNotAnEdge() {
        val state = TurnCardState()
        state.fail(session)
        assertFalse("没有跑过就谈不上'落回停止'", state.stop(session))
    }

    @Test
    fun forgetDropsEverythingForThatSession() {
        val state = beganRun()
        state.fail(session)
        state.forget(session)
        // 会话没了，状态不该留到 id 复用之后。
        state.start(session)
        state.stop(session)
        assertTrue("归档/移除后状态应当清干净", state.shouldDeliverDone(session))
    }
}

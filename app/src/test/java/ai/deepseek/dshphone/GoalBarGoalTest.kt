package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.SessionProjectionFrame
import ai.deepseek.dshphone.state.SessionStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 目标条画哪个目标，必须和条上动作认哪个目标用同一条折叠。
 *
 * 条上的暂停/编辑/清除都通过 [SessionStore.goalRef] 解析，而 goalRef 把完成态折成空
 * （对齐 Mac `activeGoal`）。如果条自己仍画 `store.goal` 的原文，一条 phase == "complete"
 * 的目标就会画出一条边框、三个按钮，点下去全部静默返回——关不掉的死条。
 */
class GoalBarGoalTest {

    private fun projectionJson(id: String, phase: String) = buildJsonObject {
        put("goal", buildJsonObject {
            put("id", id)
            put("revision", 3)
            put("objective", "ship it")
            put("phase", phase)
        })
    }

    private fun storeWith(phase: String): SessionStore {
        val store = SessionStore("s1")
        store.applyProjection(SessionProjectionFrame("s1", "goal", projectionJson("g1", phase), 1))
        return store
    }

    @Test
    fun completedGoalLeavesTheComposerBar() {
        assertNull("a complete goal must not draw the bar", storeWith("complete").goalBarGoal)
    }

    @Test
    fun activeGoalStaysOnTheComposerBar() {
        assertEquals("g1", storeWith("active").goalBarGoal?.id)
    }

    @Test
    fun pausedAndBlockedGoalsStayOnTheComposerBar() {
        assertEquals("g1", storeWith("paused").goalBarGoal?.id)
        assertEquals("g1", storeWith("blocked").goalBarGoal?.id)
    }

    /** 每一条会画出来的目标都必须解析得出 goalRef，否则条上的三个动作就是死键。 */
    @Test
    fun everyRenderedGoalHasAnActionableRef() {
        for (phase in listOf("active", "paused", "blocked", "complete")) {
            val store = storeWith(phase)
            if (store.goalBarGoal != null) {
                assertNotNull("phase $phase drew a bar without an actionable ref", store.goalRef)
            }
        }
    }

    /** 完成态确实会留在 store 里（主机保留完整快照），所以上面的折叠不是空谈。 */
    @Test
    fun completedProjectionIsRetainedButInactive() {
        val store = storeWith("complete")
        assertNotNull("the host keeps the completed snapshot", store.goal)
        assertNull(store.activeGoal)
    }
}

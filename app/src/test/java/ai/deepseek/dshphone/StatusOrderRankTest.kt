package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.SessionAwaitingKind
import ai.deepseek.dshphone.model.SessionSummary
import ai.deepseek.dshphone.model.isSubagentOrigin
import ai.deepseek.dshphone.state.SessionListStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `orderBy: "status"` 的档位顺序必须与 Mac `SessionStatusOrder.rank` 一致。
 *
 * 这条排序回答的是"谁最需要我"：跑着的 > 未读失败 > 未读完成 > 等我批准 > 等我回答 >
 * 我有草稿 > 其余。手机端原来把"等我批准"和"等我回答"挤在同一档（都进 3），
 * 等到草稿又占 4——于是"等人回答"和"我有草稿"也被混在一起排。
 *
 * 这里直接对着 statusOrder 的实际输出钉，而不是只钉那个纯函数：接线错了同样算错。
 */
class StatusOrderRankTest {

    private fun summary(id: String, updatedAt: Double, running: Boolean = false) =
        SessionSummary(sessionId = id, updatedAt = updatedAt, running = running)

    /** 走真实路径造出"未读完成"：先推成运行，再落回停止。 */
    private fun SessionListStore.markUnreadCompleted(id: String) {
        setRunning(id, true)
        setRunning(id, false)
    }

    @Test
    fun approvalOutranksQuestionWhenTheyCompete() {
        val s = SessionListStore(null)
        s.upsert(summary("approval", 1.0))
        s.upsert(summary("question", 99.0))
        s.markAwaitingUser("approval", SessionAwaitingKind.APPROVAL)
        s.markAwaitingUser("question", SessionAwaitingKind.QUESTION)
        assertEquals(
            "批准是点一下放行，提问要读题作答；两档不能挤在一起",
            listOf("approval", "question"),
            s.statusOrder(s.sessions, emptySet()).map { it.sessionId },
        )
    }

    @Test
    fun draftRanksBelowAParkedQuestion() {
        val s = SessionListStore(null)
        s.upsert(summary("draft", 99.0))
        s.upsert(summary("question", 1.0))
        s.markAwaitingUser("question", SessionAwaitingKind.QUESTION)
        s.setDraft("draft", true)
        assertEquals(
            "草稿什么也没挡住，必须排在等人回答之下",
            listOf("question", "draft"),
            s.statusOrder(s.sessions, emptySet()).map { it.sessionId },
        )
    }

    @Test
    fun planReviewRanksWithQuestionNotApproval() {
        val s = SessionListStore(null)
        s.upsert(summary("approval", 1.0))
        s.upsert(summary("plan-review", 99.0))
        s.markAwaitingUser("approval", SessionAwaitingKind.APPROVAL)
        s.markAwaitingUser("plan-review", SessionAwaitingKind.PLAN_REVIEW)
        assertEquals(
            "计划评审就是一次提问",
            listOf("approval", "plan-review"),
            s.statusOrder(s.sessions, emptySet()).map { it.sessionId },
        )
    }

    @Test
    fun unreadFailureOutranksUnreadCompletion() {
        val s = SessionListStore(null)
        s.upsert(summary("done", 99.0))
        s.upsert(summary("failed", 1.0))
        s.markUnreadCompleted("done")
        s.markUnreadCompleted("failed")
        assertEquals(
            listOf("failed", "done"),
            s.statusOrder(s.sessions, setOf("failed")).map { it.sessionId },
        )
    }

    @Test
    fun runningOutranksEverything() {
        val s = SessionListStore(null)
        s.upsert(summary("running", 1.0, running = true))
        s.upsert(summary("failed", 99.0))
        s.markUnreadCompleted("failed")
        assertEquals(
            listOf("running", "failed"),
            s.statusOrder(s.sessions, setOf("failed")).map { it.sessionId },
        )
    }

    @Test
    fun aRunningSubagentMarksItsParentAsRunning() {
        val s = SessionListStore(null)
        // 父会话本身早已停下；它派出去的子智能体还在跑。
        s.upsert(summary("parent", 1.0))
        s.upsert(
            SessionSummary(
                sessionId = "child",
                updatedAt = 99.0,
                running = true,
                parentSessionId = "parent",
                origin = "subagent",
            )
        )
        // 一条纯空闲会话，作为对照。
        s.upsert(summary("idle", 50.0))
        // 真实调用点传的是顶层会话（子智能体行不进列表），这里照做。
        val listed = s.sessions.filter { !it.isSubagentOrigin }
        assertEquals(
            "子智能体还在跑，父会话就该按'正在跑'排在最前（Mac 的 runningSubagentRoots）",
            listOf("parent", "idle"),
            s.statusOrder(listed, emptySet()).map { it.sessionId },
        )
    }
}


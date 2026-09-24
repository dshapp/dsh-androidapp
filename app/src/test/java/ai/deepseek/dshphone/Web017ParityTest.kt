package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.ChatNode
import ai.deepseek.dshphone.model.JobFollowFrame
import ai.deepseek.dshphone.model.JobTermination
import ai.deepseek.dshphone.model.ObservedJobOutput
import ai.deepseek.dshphone.model.SessionEvent
import ai.deepseek.dshphone.model.TurnTriggerKind
import ai.deepseek.dshphone.model.decodeJobFollowFrame
import ai.deepseek.dshphone.net.putHistoryWindow
import ai.deepseek.dshphone.state.SessionStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对齐 dsh 0.1.7 web 的聊天可见性、轮次触发通知、历史窗口与后台任务输出（Mac `267bcde`）。 */
class Web017ParityTest {

    private fun event(type: String, seq: Int, data: String): SessionEvent =
        SessionEvent(type, seq, seq.toDouble(), Json.parseToJsonElement(data))

    private fun userMessage(seq: Int, id: String, source: String) = event(
        "user/message", seq,
        """{"id":"$id","content":[{"type":"text","text":"hello $id"}],"source":$source}""",
    )

    private fun splice(seq: Int, target: String, removed: Int, inserted: String = "[]") = event(
        "agent/inbox/spliced", seq,
        """{"target":"$target","start":0,"removedCount":$removed,"inserted":$inserted}""",
    )

    @Test
    fun ordinaryContextAndSystemPromptStayOutOfTheChat() {
        val store = SessionStore("s1")
        store.apply(event("system/message", 1, """{"message":{"content":[{"type":"text","text":"sys"}]},"turn":1,"step":1}"""), null)
        store.apply(userMessage(2, "u1", """{"kind":"user"}"""), null)
        store.apply(userMessage(3, "c1", """{"kind":"agent-instructions","changes":[{"path":"AGENTS.md","action":"set"}]}"""), null)
        assertEquals(listOf("u-u1"), store.nodes.map { it.javaClass.simpleName.let { _ -> nodeKey(it) } })
    }

    @Test
    fun wakingMessageInTheNextTurnClaimBecomesATurnTrigger() {
        val store = SessionStore("s1")
        store.apply(splice(1, "next-turn", 0, """[{"id":"m1","source":{"kind":"schedule"}}]"""), null)
        store.apply(splice(2, "next-turn", 1), null)
        store.apply(event("turn/start", 3, """{"turn":1}"""), null)
        store.apply(userMessage(4, "m1", """{"kind":"schedule"}"""), null)
        val trigger = store.nodes.filterIsInstance<ChatNode.TurnTrigger>().single()
        assertEquals(TurnTriggerKind.SCHEDULE, trigger.data.kind)
    }

    @Test
    fun idleSteerWithAHumanInTheBatchIsNotATrigger() {
        val store = SessionStore("s1")
        store.apply(event("turn/start", 1, """{"turn":1}"""), null)
        store.apply(event("step/start", 2, """{"step":1}"""), null)
        store.apply(
            splice(3, "next-step", 0, """[{"id":"h1","source":{"kind":"user"}},{"id":"j1","source":{"kind":"tool-jobs"}}]"""),
            null,
        )
        store.apply(splice(4, "next-step", 2), null)
        store.apply(userMessage(5, "j1", """{"kind":"tool-jobs"}"""), null)
        assertTrue(store.nodes.none { it is ChatNode.TurnTrigger })
    }

    @Test
    fun idleSteerWithoutHumanWakesStepOne() {
        val store = SessionStore("s1")
        store.apply(event("turn/start", 1, """{"turn":1}"""), null)
        store.apply(event("step/start", 2, """{"step":1}"""), null)
        store.apply(splice(3, "next-step", 0, """[{"id":"j1","source":{"kind":"tool-jobs"}}]"""), null)
        store.apply(splice(4, "next-step", 1), null)
        store.apply(userMessage(5, "j1", """{"kind":"tool-jobs"}"""), null)
        assertEquals(TurnTriggerKind.JOB, store.nodes.filterIsInstance<ChatNode.TurnTrigger>().single().data.kind)
    }

    @Test
    fun permissionCommandIsHidden() {
        val store = SessionStore("s1")
        store.apply(event("command/run", 1, """{"commandId":"c1","name":"permission","args":"danger-full-access"}"""), null)
        store.apply(event("command/run", 2, """{"commandId":"c2","name":"compact","args":""}"""), null)
        val names = store.nodes.filterIsInstance<ChatNode.Command>().map { it.data.name }
        assertEquals(listOf("compact"), names)
    }

    @Test
    fun historyWindowIsTurnAligned() {
        val json = buildJsonObject { putHistoryWindow(15) }
        assertEquals(500, json["maxMessages"]!!.jsonPrimitive.int)
        val window = json["turnWindow"]!!.jsonObject
        assertEquals(15, window["minMessages"]!!.jsonPrimitive.int)
        assertEquals(2, window["minTurns"]!!.jsonPrimitive.int)
        assertEquals(500, buildJsonObject { putHistoryWindow(9999) }["turnWindow"]!!.jsonObject["minMessages"]!!.jsonPrimitive.int)
    }

    @Test
    fun terminationSplitsExitCodeSignalAndRemainder() {
        assertEquals(JobTermination(3, null, null), JobTermination.parse("exit code: 3"))
        assertEquals(
            JobTermination(null, "SIGTERM", "cancelled by the user"),
            JobTermination.parse("signal: SIGTERM; cancelled by the user"),
        )
        assertEquals(JobTermination(0, null, null), JobTermination.parse("exit code: 0; signal: null"))
        assertEquals(JobTermination(null, null, null), JobTermination.parse(null))
    }

    @Test
    fun followFramesFoldIntoObservedOutput() {
        val job = """{"id":"j1","kind":"bash","label":"ls","status":"running","startedAt":1,"output":{"total":10,"earliest":4}}"""
        var observed = ObservedJobOutput("s1", "j1")
        observed = observed.folding(decodeJobFollowFrame(Json.parseToJsonElement("""{"type":"opened","job":$job,"from":4}""")))
        assertTrue("从非 0 偏移开始说明更早的输出已被丢弃", observed.gapBefore)
        observed = observed.folding(
            decodeJobFollowFrame(Json.parseToJsonElement("""{"type":"output","chunks":[{"at":4,"text":"ab"},{"at":6,"text":"cd"}],"next":8}"""))
        )
        assertEquals("abcd", observed.text)
        assertTrue(observed.streaming)
        val status = decodeJobFollowFrame(Json.parseToJsonElement("""{"type":"status","job":${job.replace("running", "completed")}}"""))
        assertTrue(status is JobFollowFrame.Status)
        observed = observed.folding(status)
        assertFalse(observed.streaming)
        assertNull(observed.error)
    }

    private fun nodeKey(node: ChatNode): String = when (node) {
        is ChatNode.User -> "u-${node.messageId}"
        else -> node.javaClass.simpleName
    }
}

package ai.deepseek.dshphone

import ai.deepseek.dshphone.i18n.AppLocale
import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.DshJson
import ai.deepseek.dshphone.state.SessionStore
import ai.deepseek.dshphone.ui.component.BREATHE_MIN_ALPHA
import ai.deepseek.dshphone.ui.component.BREATHE_PERIOD_MS
import ai.deepseek.dshphone.ui.component.breatheAlpha
import org.junit.Assert.*
import org.junit.Test

/**
 * 运行状态条（Mac `RunningStatusRow` + `liveActivity`）、轮次折叠头（Mac `TurnProcessSpec.label`）
 * 与执行中卡片的呼吸相位。
 */
class RunningStatusAndTurnProcessTest {

    private fun withLocale(locale: AppLocale, block: () -> Unit) {
        val previous = Strings.locale
        Strings.locale = locale
        try { block() } finally { Strings.locale = previous }
    }

    private fun apply(store: SessionStore, seq: Int, type: String, raw: String) =
        store.apply(SessionEvent(type, seq, seq * 1000.0, DshJson.parseToJsonElement(raw)), null)

    private fun call(store: SessionStore, seq: Int, id: String, name: String, turn: Int = 1) =
        apply(store, seq, "tool/call", """{"turn":$turn,"step":1,"callId":"$id","name":"$name","arguments":"{}"}""")

    private fun result(store: SessionStore, seq: Int, id: String) =
        apply(store, seq, "tool/result", """{"message":{"role":"tool","toolCallId":"$id","content":[{"type":"text","text":"ok"}],"isError":false}}""")

    @Test fun liveActivityGroupsToolsByWorkKind() {
        withLocale(AppLocale.ZH) {
            assertEquals("正在读取文件", liveActivity("read"))
            assertEquals("正在读取图片", liveActivity("read_image"))
            for (name in listOf("grep", "glob", "cordis_runtime_inspect")) assertEquals("正在搜索代码", liveActivity(name))
            assertEquals("正在写入文件", liveActivity("write"))
            for (name in listOf("edit", "apply_patch")) assertEquals("正在编辑文件", liveActivity(name))
            for (name in listOf("bash", "pwsh", "exec_command", "write_stdin", "terminal_read")) {
                assertEquals("正在运行命令", liveActivity(name))
            }
            assertEquals("正在运行代码", liveActivity("run_code"))
            assertEquals("正在搜索网页", liveActivity("web_search"))
            assertEquals("正在访问网页", liveActivity("web_fetch"))
            for (name in listOf("todo_write", "create_goal", "update_goal", "get_goal")) {
                assertEquals("正在更新计划", liveActivity(name))
            }
            for (name in listOf("ask_user_question", "request_user_input")) assertEquals("等待你的操作", liveActivity(name))
            for (name in listOf("subagent", "subagent_send")) assertEquals("正在协调子智能体", liveActivity(name))
            for (name in listOf("skill", "present", "readx", "mcp__x__y")) assertEquals("正在调用工具", liveActivity(name))
        }
        withLocale(AppLocale.EN) {
            assertEquals("Reading files", liveActivity("read"))
            assertEquals("Running commands", liveActivity("bash"))
            assertEquals("Waiting for your action", liveActivity("ask_user_question"))
            assertEquals("Calling tools", liveActivity("unknown_tool"))
        }
    }

    @Test fun statusLabelNamesTheNewestUnsettledCallOfTheNewestTurnOnly() {
        withLocale(AppLocale.ZH) {
            val store = SessionStore("s")
            assertEquals("正在分析请求", runningStatusLabel(store.nodes))
            apply(store, 0, "turn/start", """{"turn":1}""")
            assertEquals("正在分析请求", runningStatusLabel(store.nodes))
            call(store, 1, "a", "bash")
            call(store, 2, "b", "read")
            // 最新的未结算调用胜出，状态条不复述工具摘要。
            assertEquals("read", liveToolName(store.nodes))
            assertEquals("正在读取文件", runningStatusLabel(store.nodes))
            result(store, 3, "b")
            assertEquals("正在运行命令", runningStatusLabel(store.nodes))
            result(store, 4, "a")
            assertEquals("正在分析请求", runningStatusLabel(store.nodes))
            // 早先轮次遗留的未结算调用不算"正在做"。
            call(store, 5, "stale", "web_fetch")
            apply(store, 6, "turn/end", """{"turn":1,"reason":{"kind":"aborted"}}""")
            apply(store, 7, "turn/start", """{"turn":2}""")
            call(store, 8, "c", "edit", turn = 2)
            result(store, 9, "c")
            assertNull(liveToolName(store.nodes))
        }
    }

    @Test fun stepWithoutVisibleBlocksDoesNotTakeARow() {
        val store = SessionStore("s")
        apply(store, 0, "turn/start", """{"turn":1}""")
        store.applyAssistantStreamFrame(AssistantStreamFrame.Start("attempt", 1, 0, 1, 1))
        val empty = store.nodes.filterIsInstance<ChatNode.Assistant>()
        assertTrue(empty.none { it.rendersRow })
        store.applyAssistantStreamFrame(AssistantStreamFrame.Chunk("attempt", 1, 0, 1000.0,
            DshJson.parseToJsonElement("""{"type":"block-start","index":0,"blockType":"text"}""")))
        assertTrue(store.nodes.filterIsInstance<ChatNode.Assistant>().none { it.rendersRow })
        store.applyAssistantStreamFrame(AssistantStreamFrame.Chunk("attempt", 1, 1, 1000.0,
            DshJson.parseToJsonElement("""{"type":"text-delta","index":0,"text":"Hi"}""")))
        assertTrue(store.nodes.filterIsInstance<ChatNode.Assistant>().single().rendersRow)
    }

    @Test fun elapsedCountsFromTurnStartWithOneSecondFloorInUnits() {
        withLocale(AppLocale.ZH) {
            assertNull(runningStatusElapsed(null, 5_000.0))
            assertEquals("1秒", runningStatusElapsed(10_000.0, 10_200.0))
            assertEquals("1秒", runningStatusElapsed(10_000.0, 9_000.0))
            assertEquals("1分5秒", runningStatusElapsed(0.0, 65_400.0))
            assertEquals("1小时0分3秒", runningStatusElapsed(0.0, 3_603_000.0))
        }
        withLocale(AppLocale.EN) {
            assertEquals("1m 5s", runningStatusElapsed(0.0, 65_000.0))
            assertEquals("1h 2m 0s", runningStatusElapsed(0.0, 3_720_000.0))
        }
    }

    private fun spec(outcome: TurnOutcome, durationMs: Double?) =
        TurnProcessSpec(1, 0.0, null, 1, false, outcome, durationMs)

    @Test fun turnProcessLabelIsTheOutcome() {
        withLocale(AppLocale.ZH) {
            assertEquals("已完成，用时 35分53秒", spec(TurnOutcome.COMPLETED, 2_153_000.0).label)
            assertEquals("已完成，用时 1秒", spec(TurnOutcome.COMPLETED, 120.0).label)
            assertEquals("已完成", spec(TurnOutcome.COMPLETED, null).label)
            assertEquals("已停止", spec(TurnOutcome.STOPPED, null).label)
            assertEquals("处理失败", spec(TurnOutcome.FAILED, null).label)
        }
        withLocale(AppLocale.EN) {
            assertEquals("Completed in 35m 53s", spec(TurnOutcome.COMPLETED, 2_153_000.0).label)
            assertEquals("Completed", spec(TurnOutcome.COMPLETED, null).label)
            assertEquals("Stopped", spec(TurnOutcome.STOPPED, null).label)
            assertEquals("Failed", spec(TurnOutcome.FAILED, null).label)
        }
    }

    @Test fun everyClosedTurnGetsAHeaderAndOnlyNormalEndsWithSomethingToHideFold() {
        withLocale(AppLocale.ZH) {
            // 只有一条正文、没有可折内容：仍有折叠头，只是不可折。
            val plain = SessionStore("s")
            apply(plain, 0, "turn/start", """{"turn":1}""")
            assertTrue(plain.nodes.none { it is ChatNode.TurnProcess })
            apply(plain, 1, "assistant/message",
                """{"turn":1,"step":1,"message":{"id":"m","content":[{"type":"text","text":"Answer"}]}}""")
            // 进行中的轮没有折叠头。
            assertTrue(plain.nodes.none { it is ChatNode.TurnProcess })
            apply(plain, 3, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}""")
            val header = plain.nodes.filterIsInstance<ChatNode.TurnProcess>().single().spec
            assertFalse(header.foldable)
            assertEquals("已完成，用时 3秒", header.label)
            // 折叠头排在这一轮的最前面。
            assertTrue(plain.nodes.indexOfFirst { it is ChatNode.TurnProcess } <
                plain.nodes.indexOfFirst { it is ChatNode.Assistant })

            // 失败的轮：折叠头说「处理失败」，轮尾不再另起一行。
            val failed = SessionStore("s")
            apply(failed, 0, "turn/start", """{"turn":1}""")
            call(failed, 1, "c", "bash")
            apply(failed, 2, "assistant/message",
                """{"turn":1,"step":2,"message":{"id":"m","content":[{"type":"text","text":"partial"}]}}""")
            apply(failed, 3, "turn/end", """{"turn":1,"reason":{"kind":"error","error":{"message":"Oops"}}}""")
            val failure = failed.nodes.filterIsInstance<ChatNode.TurnProcess>().single().spec
            assertFalse(failure.foldable)
            assertEquals("处理失败", failure.label)
            assertTrue(failed.nodes.any { it is ChatNode.Tool })
        }
    }

    @Test fun breathePhaseFollowsTheGlobalClock() {
        assertEquals(1f, breatheAlpha(0), 1e-4f)
        assertEquals(BREATHE_MIN_ALPHA, breatheAlpha(BREATHE_PERIOD_MS / 2), 1e-4f)
        assertEquals(1f, breatheAlpha(BREATHE_PERIOD_MS), 1e-4f)
        // 同一时刻的任意两张卡片同相位；重建（任意时刻重新开始）也不回到起点。
        for (t in listOf(123L, 400L, 999L, 1_234_567L)) {
            assertEquals(breatheAlpha(t), breatheAlpha(t + BREATHE_PERIOD_MS * 7), 1e-5f)
            val a = breatheAlpha(t)
            assertTrue(a in BREATHE_MIN_ALPHA..1f)
        }
        // ease-in-out：四分之一周期正好过半程。
        assertEquals((1f + BREATHE_MIN_ALPHA) / 2, breatheAlpha(BREATHE_PERIOD_MS / 4), 1e-3f)
        assertTrue(breatheAlpha(200) > breatheAlpha(400))
    }
}

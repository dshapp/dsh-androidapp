package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.DshJson
import ai.deepseek.dshphone.net.decodeOrNull
import ai.deepseek.dshphone.state.SessionStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Projection regressions derived from official alpha.2 wire contracts. */
class Dsh021ChatParityTest {
    private fun json(raw: String): JsonElement = DshJson.parseToJsonElement(raw)
    private fun apply(store: SessionStore, seq: Int, type: String, raw: String) =
        store.apply(SessionEvent(type, seq, seq * 1000.0, json(raw)), null)
    private fun start(store: SessionStore) = apply(store, 0, "turn/start", """{"turn":1}""")
    private fun call(store: SessionStore, seq: Int, id: String = "c", name: String = "read") =
        apply(store, seq, "tool/call", """{"turn":1,"step":1,"callId":"$id","name":"$name","arguments":"{\"file_path\":\"file.txt\"}"}""")
    private fun result(store: SessionStore, seq: Int, id: String = "c", text: String = "read") =
        apply(store, seq, "tool/result", """{"message":{"role":"tool","toolCallId":"$id","content":[{"type":"text","text":"$text"}],"isError":false}}""")
    private fun end(store: SessionStore, seq: Int, kind: String = "completed") =
        apply(store, seq, "turn/end", """{"turn":1,"reason":{"kind":"$kind","error":{"message":"Oops"}}}""")
    private fun assistant(store: SessionStore) = apply(store, 1, "assistant/message",
        """{"turn":1,"step":1,"message":{"id":"answer","content":[{"type":"reasoning","text":"private"},{"type":"text","text":"Answer"}]}}""")

    @Test fun effectiveDirectoryKeepsOriginalProjectAndRejectsStaleCuts() {
        val store = SessionStore("s").apply { cwd = "/project" }
        store.applyProjection(SessionProjectionFrame("s", "workingDirectory", JsonPrimitive("/worktree"), 4))
        assertEquals("/project", store.cwd)
        assertEquals("/worktree", store.currentDirectory)
        store.applyProjection(SessionProjectionFrame("s", "workingDirectory", JsonPrimitive("/stale"), 3))
        assertEquals("/worktree", store.currentDirectory)
        apply(store, 5, "working-directory/change", """{"cwd":"/next"}""")
        assertEquals("/next", store.currentDirectory)
        assertTrue(store.nodes.isEmpty())
        store.applyProjection(SessionProjectionFrame("s", "workingDirectory", JsonNull, 6))
        assertEquals("/project", store.currentDirectory)
    }

    @Test fun replayKeepsOperationDirectoryInsteadOfLatestProjection() {
        val store = SessionStore("s").apply { cwd = "/project" }
        start(store); call(store, 1); result(store, 2)
        apply(store, 3, "working-directory/change", """{"cwd":"/next"}""")
        call(store, 4, "later")
        store.applyProjection(SessionProjectionFrame("s", "workingDirectory", JsonPrimitive("/future"), 6))
        result(store, 2, text = "updated") // Replacement forces full replay.
        assertEquals("/project", store.toolCall("c")!!.cwd)
        assertEquals("/next", store.toolCall("later")!!.cwd)
        assertEquals("/project/file.txt", toolRowModel(store.toolCall("c")!!, "/future").filePath)
        assertEquals("/future", store.currentDirectory)
    }

    @Test fun ptcMetaAndFailureReasonSurvivePairedAndStandaloneSettlement() {
        for (paired in listOf(false, true)) {
            val store = SessionStore("s")
            start(store); call(store, 1, "root", "run_code")
            val base = """"subCallId":"nested","rootCallId":"root","parentCallId":"root","name":"read","arguments":{"file_path":"file.txt"}"""
            if (paired) apply(store, 2, "tool/ptc-dispatch-start", "{$base}")
            apply(store, 3, "tool/ptc-dispatch", """{$base,"content":[],"isError":true,"meta":{"path":"/old/file.txt","lines":[1,2]},"error":{"name":"Denied","code":"DENIED","reason":"Raw reason\nmore"}}""")
            val child = store.toolCall("nested")!!
            assertEquals(json("""{"path":"/old/file.txt","lines":[1,2]}"""), child.result!!.meta)
            assertEquals("Raw reason\nmore", toolResultText(child.result!!))
            assertEquals("Raw reason", resultFirstLine(child.result!!))
            assertEquals("/old/file.txt", toolRowModel(child, "/new").filePath)
            assertEquals("file.txt", parseArgsMap(child.argsRaw)!!["file_path"])
            assertNull(toolRowModel(child.copy(result = child.result!!.copy(meta = null, isError = false)), "/new").filePath)
        }
    }

    @Test fun relativeNavigationRequiresSuccessfulRootSettlement() {
        val data = ToolChatData("c", "read", """{"file_path":"relative.txt"}""", 1, 1, 1, 0.0, cwd = "/old")
        assertNull(toolRowModel(data, "/new").filePath)
        assertNull(toolRowModel(data.copy(result = ToolResultData(2, 0.0, emptyList(), true)), "/new").filePath)
        val done = data.copy(result = ToolResultData(2, 0.0, emptyList(), false))
        assertEquals("/old/relative.txt", toolRowModel(done, "/new").filePath)
        assertEquals("/absolute.txt", toolRowModel(data.copy(argsRaw = """{"file_path":"/absolute.txt"}"""), "/new").filePath)
    }

    private fun streaming(): SessionStore {
        val store = SessionStore("s").apply { cwd = "/project" }
        start(store)
        store.applyAssistantStreamFrame(AssistantStreamFrame.Start("attempt", 1, 0, 1, 1))
        chunk(store, """{"type":"block-start","index":0,"blockType":"tool-call"}""")
        return store
    }
    private fun chunk(store: SessionStore, raw: String) =
        store.applyAssistantStreamFrame(AssistantStreamFrame.Chunk("attempt", 1, 0, 1000.0, json(raw)))

    @Test fun deltaRetainsCallIdAndDispatchReplacesPreparationExactlyOnce() {
        val store = streaming()
        chunk(store, """{"type":"tool-call-delta","index":0,"id":"c","name":"write","argumentsDelta":"{\"file_path\":\"out.txt\",\"content\":\"abc"}""")
        val preparing = store.nodes.filterIsInstance<ChatNode.Tool>().single()
        assertEquals("c", preparing.data.callId)
        assertTrue(preparing.data.preparing)
        assertEquals(ToolRowState.PREPARING, toolRowModel(preparing.data, "/project").state)
        assertNull(toolRowModel(preparing.data, "/project").filePath)
        call(store, 1, "c", "write")
        val dispatched = store.nodes.filterIsInstance<ChatNode.Tool>().single()
        assertEquals(preparing.nodeId, dispatched.nodeId)
        assertFalse(dispatched.data.preparing)
    }

    @Test fun multiplePreparationSlotsKeepStreamOrderAndDoNotDuplicateDispatchedIds() {
        val store = streaming()
        chunk(store, """{"type":"tool-call-delta","index":0,"id":"z","name":"read","argumentsDelta":"{}"}""")
        chunk(store, """{"type":"block-start","index":1,"blockType":"tool-call"}""")
        chunk(store, """{"type":"tool-call-delta","index":1,"id":"a","name":"read","argumentsDelta":"{}"}""")
        assertEquals(listOf("z", "a"), store.nodes.filterIsInstance<ChatNode.Tool>().map { it.data.callId })
        call(store, 1, "z")
        assertEquals(1, store.nodes.filterIsInstance<ChatNode.Tool>().count { it.data.callId == "z" })
    }

    @Test fun cancelledPreparationNeverInventsDispatchOrInterruptedToolResult() {
        val store = streaming()
        chunk(store, """{"type":"tool-call-delta","index":0,"id":"c","name":"write","argumentsDelta":"{}"}""")
        store.applyAssistantStreamFrame(AssistantStreamFrame.End("attempt", 1, 1, null))
        assertTrue(store.nodes.filterIsInstance<ChatNode.Tool>().isEmpty())
        apply(store, 1, "assistant/attempt", """{"turn":1,"step":1}""")
        end(store, 2, "aborted")
        assertTrue(store.nodes.filterIsInstance<ChatNode.Tool>().isEmpty())
        assertFalse(store.running)
    }

    @Test fun preparingSummaryIsBoundedEscapedAndReportsUtf16Kilobytes() {
        val preview = preparingArguments("write", """{"file_path":"a\\b.txt","content":"""" + "x".repeat(2048))
        assertEquals("a\\b.txt", preview.summary)
        assertEquals(3, preview.kilobytes)
        assertEquals("", preparingArguments("read", """{"file_path":"not closed""").summary)
        assertEquals("Checking", preparingArguments("run_code", """{"description":"Checking\nmore""").summary)
        assertEquals(977, preparingArguments("write", """{"file_path":"a","content":"""" + "x".repeat(1_000_000)).kilobytes)
        val data = ToolChatData("c", "edit", """{"old_string":"""" + "x".repeat(1024) + """","new_string":"😀"}""", 1, 1, 1, 0.0)
        assertEquals("2 KB", toolRowModel(data, null).summarySuffix)
    }

    @Test fun completedFinalReasoningFoldsInPartialHistoryAndToggleRestoresIt() {
        val store = SessionStore("s")
        start(store); assistant(store); end(store, 2)
        assertTrue(store.hasMore)
        val process = store.nodes.filterIsInstance<ChatNode.TurnProcess>().single().spec
        assertTrue(process.inlineReasoning)
        assertEquals(2000.0, process.durationMs)
        assertFalse(store.nodes.filterIsInstance<ChatNode.Assistant>().single().step.blocks.any { it is AssistantBlock.Reasoning })
        store.setTurnProcessExpanded(process, true)
        assertTrue(store.nodes.filterIsInstance<ChatNode.Assistant>().single().step.blocks.any { it is AssistantBlock.Reasoning })
    }

    @Test fun stoppedAndFailedTurnsKeepEvidenceAndNeverReportSuccessfulDuration() {
        for ((kind, expected) in listOf("aborted" to TurnOutcome.STOPPED, "error" to TurnOutcome.FAILED)) {
            val store = SessionStore("s")
            start(store); assistant(store); end(store, 2, kind)
            assertTrue(store.nodes.filterIsInstance<ChatNode.TurnProcess>().isEmpty())
            assertTrue(store.nodes.filterIsInstance<ChatNode.Assistant>().single().step.blocks.any { it is AssistantBlock.Reasoning })
            val tail = store.nodes.filterIsInstance<ChatNode.TurnTail>().single()
            assertEquals(expected, tail.data.outcome)
            assertNull(tail.data.runMs)
            assertTrue(tail.rendersRow)
        }
    }

    @Test fun turnEndingOnToolCallFoldsWholeUpToTurnEnd() {
        val store = SessionStore("s")
        start(store)
        apply(store, 1, "assistant/message",
            """{"turn":1,"step":1,"message":{"id":"m","content":[{"type":"tool-call","id":"c","name":"read","arguments":"{}"}]}}""")
        call(store, 2); result(store, 3); end(store, 4)
        val process = store.nodes.filterIsInstance<ChatNode.TurnProcess>().single().spec
        assertEquals(4.5, process.foldEndSeq, 0.0)
        assertFalse(process.inlineReasoning)
        assertTrue(store.nodes.none { it is ChatNode.Assistant || it is ChatNode.Tool })
        store.setTurnProcessExpanded(process, true)
        assertTrue(store.nodes.any { it is ChatNode.Tool })
    }

    @Test fun toolOnlySuccessHasDurationButUnknownStartDoesNotInventDuration() {
        val store = SessionStore("s")
        start(store); call(store, 1); end(store, 2)
        val tail = store.nodes.filterIsInstance<ChatNode.TurnTail>().single()
        assertEquals(TurnOutcome.COMPLETED, tail.data.outcome)
        assertEquals(2000.0, tail.data.runMs)
        assertNull(tail.data.messageId)
        assertTrue(tail.rendersRow)
        assertTrue(store.nodes.filterIsInstance<ChatNode.TurnProcess>().isEmpty())
        val partial = SessionStore("s")
        assistant(partial); end(partial, 2)
        assertNull(partial.nodes.filterIsInstance<ChatNode.TurnTail>().single().data.runMs)
    }

    @Test fun externalCatalogModeRoundTripsWithoutLocalNavigation() {
        val entry = json("""{"id":"external-id","createdAt":10,"mode":"external","label":"Remote"}""")
            .decodeOrNull<SubagentCatalogEntry>()!!
        assertEquals(SubagentMode.EXTERNAL, entry.mode)
        assertEquals("external", entry.mode.wire)
        assertFalse(entry.canOpenLocally)
        assertTrue(entry.copy(mode = SubagentMode.UNKNOWN).canOpenLocally)
    }

    private fun goal(store: SessionStore, seq: Int, id: String, revision: Int) =
        store.applyProjection(SessionProjectionFrame("s", "goal", json("""{"goal":{"id":"$id","revision":$revision,"objective":"Do work","phase":"active"}}"""), seq))

    @Test fun goalActivationMatchesIdentityAndInvalidatesGetEpoch() {
        val store = SessionStore("s")
        goal(store, 1, "g", 1)
        val before = store.goalActivationEpoch
        store.setGoalActivation("g", 1, "disarmed")
        assertTrue(store.goalActivationEpoch > before)
        assertEquals("disarmed", store.goalActivation)
        val validEpoch = store.goalActivationEpoch
        store.setGoalActivation("other", 1, "armed")
        assertEquals(validEpoch, store.goalActivationEpoch)
        goal(store, 2, "g", 1)
        assertEquals("disarmed", store.goalActivation)
        goal(store, 3, "g", 2)
        assertNull(store.goalActivation)
        assertTrue(store.goalActivationEpoch > validEpoch)
        store.setGoalActivation("g", 2, "armed")
        store.setGoalActivation(null, null, null)
        assertNull(store.goalActivation)
    }
}

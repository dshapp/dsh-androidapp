package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.ChatNode
import ai.deepseek.dshphone.model.SessionEvent
import ai.deepseek.dshphone.state.SessionStore
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具调用与结果的配对：callId 为空时的兜底两端必须一致。
 *
 * \`tool/call\` 落在上下文里的键是 \`tool-call:<callId>\`，而 callId 为空时
 * [SessionStore] 用 \`seq-<seq>\` 顶替（Mac \`applyToolCall\` 同一句
 * \`d.callId.isEmpty ? "seq-\\(event.seq)" : d.callId\`）。结果那一端必须能找到同一把键，
 * 否则一条 \`tool/result\` 接不上自己的调用：真正的调用行永远停在"没有结果"，
 * 旁边却多出一行叫 orphan-result 的重复行。
 *
 * Mac 在 \`applyToolResult\` 里对空 callId 显式兜底：挑"最近一条还没有结果的工具调用"
 * 接上去；只有在一条都挑不到时才另起一个唯一的 \`orphan-<seq>\` 行。这里钉的就是这条约定。
 */
class ToolResultLinkageTest {

    private fun event(type: String, seq: Int, data: String): SessionEvent =
        SessionEvent(type, seq, seq.toDouble(), Json.parseToJsonElement(data))

    @Test
    fun emptyCallIdResultLinksToThePendingToolCall() {
        val store = SessionStore("s1")
        store.apply(
            event("tool/call", 10, "{\"callId\":\"\",\"name\":\"read\",\"arguments\":\"{}\",\"turn\":1,\"step\":1}"),
            null,
        )
        assertTrue("空 callId 的调用应落成 seq-10", store.toolCall("seq-10") != null)

        store.apply(
            event(
                "tool/result", 11,
                """
                {"turn":1,"step":1,"message":{"role":"tool","source":{"kind":"tool","callId":""},
                 "toolCallId":"","content":[{"type":"text","text":"ok"}],"isError":false}}
                """.trimIndent(),
            ),
            null,
        )

        val tools = store.nodes.filterIsInstance<ChatNode.Tool>()
        assertEquals(
            "空 callId 的结果必须接到那条调用上，而不是另起一行 orphan-result",
            1, tools.size,
        )
        val linked = store.toolCall("seq-10")
        assertEquals("ok", (linked?.result?.content?.firstOrNull() as? ai.deepseek.dshphone.model.ContentBlock.Text)?.text)
    }

    @Test
    fun orphanResultGetsItsOwnUniqueIdWhenNothingIsPending() {
        val store = SessionStore("s1")
        store.apply(
            event(
                "tool/result", 11,
                """
                {"turn":1,"step":1,"message":{"role":"tool","source":{"kind":"tool","callId":""},
                 "toolCallId":"","content":[{"type":"text","text":"stray"}],"isError":false}}
                """.trimIndent(),
            ),
            null,
        )
        // 没有可接的调用时，Mac 给的是唯一 id（orphan-11），不是空串——空串会让
        // 每一条这样的结果都覆盖同一把键，只剩最后一条能看见。
        assertTrue("孤儿结果必须拿到唯一的 orphan-11", store.toolCall("orphan-11") != null)
    }
}

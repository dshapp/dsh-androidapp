package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.ContentBlock
import ai.deepseek.dshphone.model.decodeContentBlock
import ai.deepseek.dshphone.model.encodeContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内容块的编解码必须能来回走通。
 *
 * `encodeContentBlock(ToolResult)` 原来只写 type/toolCallId/isError，丢掉了嵌套的
 * `content`；Mac ChatNode.swift 的 .toolResult 分支会 `encode(content, forKey: .content)`。
 * 影响工具结果"复制"与错误摘要行（toolResultText / resultFirstLine 都靠这条编码把非文本
 * 块摊成文本）：嵌套内容整段消失。
 */
class ContentBlockRoundTripTest {

    @Test
    fun toolResultKeepsNestedContent() {
        val block = ContentBlock.ToolResult(
            toolCallId = "call-1",
            content = listOf(
                ContentBlock.Text("first line"),
                ContentBlock.Text("second line"),
            ),
            isError = false,
        )
        val decoded = decodeContentBlock(encodeContentBlock(block))
        assertTrue("解码回来还应当是 tool-result：" + decoded, decoded is ContentBlock.ToolResult)
        val result = decoded as ContentBlock.ToolResult
        assertEquals("call-1", result.toolCallId)
        assertEquals(false, result.isError)
        assertEquals(2, result.content.size)
        assertEquals(listOf("first line", "second line"), result.content.map { (it as ContentBlock.Text).text })
    }

    @Test
    fun deeplyNestedContentSurvives() {
        val inner = ContentBlock.ToolResult(
            toolCallId = "inner",
            content = listOf(ContentBlock.Text("deep")),
            isError = true,
        )
        val outer = ContentBlock.ToolResult("outer", listOf(inner), false)
        val decoded = decodeContentBlock(encodeContentBlock(outer)) as ContentBlock.ToolResult
        val nested = decoded.content.single() as ContentBlock.ToolResult
        assertEquals("inner", nested.toolCallId)
        assertEquals(true, nested.isError)
        assertEquals("deep", (nested.content.single() as ContentBlock.Text).text)
    }

    @Test
    fun plainBlocksStillRoundTrip() {
        val cases = listOf(
            ContentBlock.Text("t"),
            ContentBlock.Reasoning("r"),
            ContentBlock.ToolCall("id", "name", "{}"),
        )
        for (case in cases) {
            assertEquals(case, decodeContentBlock(encodeContentBlock(case)))
        }
    }
}

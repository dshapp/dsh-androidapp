package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.ContentBlock
import ai.deepseek.dshphone.model.decodeContentBlock
import ai.deepseek.dshphone.model.encodeContentBlock
import org.junit.Assert.assertEquals
import org.junit.Test

/** 内容块的编解码必须能来回走通。 */
class ContentBlockRoundTripTest {

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

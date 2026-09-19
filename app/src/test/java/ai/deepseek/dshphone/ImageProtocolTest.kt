package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.state.SessionStore
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片块协议的解析口径：每条断言都对着 Web 端（dsh-client-ui-tool 的
 * image-card-model.js 与 dsh-client-ui-chat 的 contentParts）以及主机真实日志形状。
 *
 * 只覆盖纯函数与事件投影，不碰 Compose 排版，因此能在 JVM 上直接跑。
 */
class ImageProtocolTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun block(text: String): ContentBlock = decodeContentBlock(json(text))

    private fun textBlockJson(text: String): String =
        "{\"type\":\"text\",\"text\":" + Json.encodeToString(String.serializer(), text) + "}"

    /** read_image 写给模型的那封信封（主机 formatImageReadOutput 的原样）。 */
    private val envelope =
        "<path>/tmp/emu1.png</path>\n<type>image</type>\n<content>\nimage/png image, 1080x2400 px, 56549 bytes\n</content>"

    private val imageBlock = """{"type":"image","attachment":{"attachmentId":"sha256:8a2213d9","mediaType":"image/png","bytes":56549,"width":1080,"height":2400,"name":"emu1.png","originalDimensions":{"width":2160,"height":4800}}}"""

    // —— 单个 attachment：宽松解码（用户/助手气泡） ——

    @Test
    fun attachmentDecodesWithAndWithoutOptionalFields() {
        val ref = decodeImageRef((block(imageBlock) as ContentBlock.Image).attachment)
        assertNotNull(ref)
        assertEquals("sha256:8a2213d9", ref!!.attachmentId)
        assertEquals("image/png", ref.mediaType)
        assertEquals(56549, ref.bytes)
        assertEquals(1080, ref.width)
        assertEquals(2400, ref.height)
        assertEquals("emu1.png", ref.name)
        assertEquals(2160, ref.originalDimensions?.width)
        assertEquals(4800, ref.originalDimensions?.height)

        // 只有 attachmentId 也认（web contentParts 不做字段校验）。
        val bare = decodeImageRef(json("{\"attachmentId\":\"sha256:beef\"}"))
        assertNotNull(bare)
        assertEquals("image/png", bare!!.mediaType)
        assertEquals(0, bare.bytes)

        // 没有 attachmentId / 不是对象：不给引用。
        assertNull(decodeImageRef(json("{\"mediaType\":\"image/png\"}")))
        assertNull(decodeImageRef(json("[1,2]")))
        assertNull(decodeImageRef(null))
    }

    // —— imageReferences：严格校验（read_image 卡片） ——

    @Test
    fun imageReferencesValidatesEveryField() {
        val good = imageReferences(listOf(block("{\"type\":\"text\",\"text\":\"x\"}"), block(imageBlock)))
        assertEquals(1, good?.size)
        assertEquals("sha256:8a2213d9", good!!.first().attachmentId)
        assertEquals(2160, good.first().originalDimensions?.width)

        // 不支持的媒体类型、非正数尺寸、字符串数字、非法 originalDimensions：整体回落。
        assertNull(imageReferences(listOf(block(imageBlock.replace("image/png", "image/tiff")))))
        assertNull(imageReferences(listOf(block(imageBlock.replace("\"bytes\":56549", "\"bytes\":0")))))
        assertNull(imageReferences(listOf(block(imageBlock.replace("\"width\":1080", "\"width\":\"1080\"")))))
        assertNull(imageReferences(listOf(block(imageBlock.replace("\"width\":2160", "\"width\":-3")))))
        assertNull(imageReferences(listOf(block(imageBlock.replace("\"name\":\"emu1.png\"", "\"name\":7")))))
        // 没有图片块：null（web 的 refs.length > 0 闸门）。
        assertNull(imageReferences(listOf(block("{\"type\":\"text\",\"text\":\"x\"}"))))
        // 宽松回退仍然认得出能解的那张。
        val loose = lenientImageRefs(listOf(block("{\"type\":\"image\",\"attachment\":{\"attachmentId\":\"sha256:beef\"}}")))
        assertEquals(1, loose.size)
    }

    // —— imageTexts / fullyRendered ——

    @Test
    fun imageTextsRequiresTheEnvelopeAndFullyRenderedRejectsExtras() {
        val content = listOf(block(textBlockJson(envelope)), block(imageBlock))
        assertTrue(imageTexts(content)!!.startsWith("<path>/tmp/emu1.png</path>"))
        // 没有信封形状的文本块：不是一次规范的图片读取。
        assertNull(imageTexts(listOf(block(textBlockJson("just text")), block(imageBlock))))
        assertTrue(fullyRendered(content))
        // 后置钩子追加的 reasoning 块让卡片回落到通用卡。
        assertTrue(!fullyRendered(content + block("{\"type\":\"reasoning\",\"text\":\"hmm\"}")))
    }

    // —— readImageCard：根调用与嵌套调用的路径口径 ——

    @Test
    fun readImageCardMatchesWebPathRules() {
        val result = ToolResultData(
            seq = 9, time = 0.0,
            content = listOf(block(textBlockJson(envelope)), block(imageBlock)),
            isError = false,
        )
        // 根调用：标签只能来自 presentationMeta.path。
        val root = ToolChatData("c1", "read_image", "{\"file_path\":\"/tmp/emu1.png\"}", 1, 0, 8, 0.0).copy(result = result)
        assertNull(readImageCard(root))
        val rootWithMeta = root.copy(result = result.copy(meta = json("{\"path\":\"/tmp/emu1.png\"}")))
        assertEquals("/tmp/emu1.png", readImageCard(rootWithMeta)?.path)

        // 嵌套调用（run_code 里派发的 read_image）不落 meta：退回调用自己的 file_path。
        val nested = root.copy(parentCallId = "c1-root")
        assertEquals("/tmp/emu1.png", readImageCard(nested)?.path)
        assertEquals(1, readImageCard(nested)?.images?.size)

        // 失败结果、别的工具名、缺 file_path：都没有图片卡。
        assertNull(readImageCard(nested.copy(result = result.copy(isError = true))))
        assertNull(readImageCard(nested.copy(name = "read")))
        assertNull(readImageCard(nested.copy(argsRaw = "{\"path\":\"/tmp/x\"}")))
    }

    // —— 事件投影：tool/ptc-dispatch* 必须长成嵌套工具调用 ——

    @Test
    fun ptcDispatchBecomesNestedReadImageCall() {
        val store = SessionStore("session-test")
        val rootData = "{\"turn\":1,\"step\":0,\"callId\":\"c1\",\"name\":\"run_code\",\"arguments\":\"{\\\"code\\\":\\\"read_image\\\"}\"}"
        store.apply(SessionEvent("tool/call", 1, 0.0, json(rootData)), null)
        val start = "{\"rootCallId\":\"c1\",\"parentCallId\":\"c1\",\"subCallId\":\"c1:ptc:1\",\"name\":\"read_image\",\"arguments\":{\"file_path\":\"/tmp/emu1.png\"}}"
        store.apply(SessionEvent("tool/ptc-dispatch-start", 2, 0.0, json(start)), null)
        val dispatch = "{\"rootCallId\":\"c1\",\"parentCallId\":\"c1\",\"subCallId\":\"c1:ptc:1\",\"name\":\"read_image\"," +
            "\"arguments\":{\"file_path\":\"/tmp/emu1.png\"},\"isError\":false," +
            "\"content\":[" + textBlockJson(envelope) + "," + imageBlock + "]}"
        store.apply(SessionEvent("tool/ptc-dispatch", 3, 0.0, json(dispatch)), null)

        val root = store.nodes.filterIsInstance<ChatNode.Tool>().firstOrNull()
        assertNotNull(root)
        assertEquals("run_code", root!!.data.name)
        val child = root.data.subCalls.firstOrNull()
        assertNotNull("ptc-dispatch 必须挂成子调用", child)
        assertEquals("read_image", child!!.name)
        // 子调用的结果里带着图片块，图片卡也就出来了。
        assertEquals(1, imageReferences(child.result!!.content)?.size)
        assertEquals("/tmp/emu1.png", readImageCard(child)?.path)
    }
}

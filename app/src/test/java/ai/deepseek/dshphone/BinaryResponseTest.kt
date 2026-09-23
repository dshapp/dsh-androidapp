package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.WorkspaceFileBytesValue
import ai.deepseek.dshphone.net.DshJson
import ai.deepseek.dshphone.net.ServerResponse
import ai.deepseek.dshphone.net.readBinaryResponse
import ai.deepseek.dshphone.net.withBinaryParts
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/**
 * 0.1.7 的 `workspaceFiles/readBytes` 回 multipart：字节在 `bytes-0`，信封在 `metadata`，
 * `value.data` 是 null，由 `attachments[].path` 指回去。夹具是 0.1.7-alpha.2 主机的原样响应。
 */
class BinaryResponseTest {

    @Test
    fun bytesAreSplicedBackIntoTheValue() {
        val raw = javaClass.classLoader!!.getResourceAsStream("readbytes-multipart.bin")!!.readBytes()
        val body = raw.toResponseBody(
            "multipart/form-data; boundary=----formdata-undici-057360852735".toMediaType(),
        )
        val (metadata, parts) = readBinaryResponse(body)
        val envelope = DshJson.decodeFromString(ServerResponse.serializer(), metadata).withBinaryParts(metadata, parts)
        val value = DshJson.decodeFromJsonElement<WorkspaceFileBytesValue>(envelope.result.value!!)
        val text = String(Base64.getDecoder().decode(value.data))
        assertEquals(64, text.toByteArray().size)
        assertEquals(true, text.startsWith("pluginManagement {"))
        assertEquals(false, value.eof)
    }
}

package ai.deepseek.dshphone.state

import ai.deepseek.dshphone.model.WorkspaceFileBytesValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * 工作区分片读取的搬运逻辑：图片要先落地再交给系统查看器，这一步错了会
 *  1) 把整份文件一次性攥在内存里（低端机 OOM），
 *  2) 或者丢字节/重复字节（图片打开失败或花屏），
 *  3) 或者 eof 为假却拿到空片时死循环转下去。
 */
class WorkspaceFileChunkedCopyTest {

    private fun window(text: String, offset: Int, eof: Boolean, bytes: Int) = WorkspaceFileBytesValue(
        absolutePath = "/w/a.png",
        version = "v1",
        bytes = bytes,
        offset = offset,
        data = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)),
        eof = eof,
    )

    @Test
    fun concatenatesEveryChunkInOrder() {
        val parts = listOf("aaaa", "bbbb", "cc")
        val out = ByteArrayOutputStream()
        var calls = 0
        val written = kotlinx.coroutines.runBlocking {
            copyWorkspaceFileChunked(out, Long.MAX_VALUE) { offset, _ ->
                calls++
                assertEquals(calls - 1, offset / 4)
                val i = calls - 1
                window(parts[i], offset, i == parts.lastIndex, 12)
            }
        }
        assertEquals(10L, written)
        assertEquals(3, calls)
        assertEquals("aaaabbbbcc", out.toString("UTF-8"))
    }

    @Test
    fun straddlesArbitraryByteBoundaries() {
        // 分片必须按字节拼接：多字节 UTF-8 被切开时不能损坏数据。
        val text = "中文图片.png"
        val raw = text.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        val written = kotlinx.coroutines.runBlocking {
            copyWorkspaceFileChunked(out, Long.MAX_VALUE) { offset, _ ->
                val end = minOf(offset + 3, raw.size)
                WorkspaceFileBytesValue(
                    bytes = raw.size,
                    offset = offset,
                    data = Base64.getEncoder().encodeToString(raw.copyOfRange(offset, end)),
                    eof = end >= raw.size,
                )
            }
        }
        assertEquals(raw.size.toLong(), written)
        assertArrayEquals(raw, out.toByteArray())
    }

    @Test
    fun refusesAFileThatIsAlreadyTooLarge() {
        val out = ByteArrayOutputStream()
        try {
            kotlinx.coroutines.runBlocking {
                copyWorkspaceFileChunked(out, limit = 4) { offset, _ ->
                    window("aaaa", offset, eof = false, bytes = 999)
                }
            }
            fail("expected the size cap to be enforced")
        } catch (expected: Exception) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains("too large"))
        }
        assertEquals("超限时不该先写盘", 0, out.size())
    }

    @Test
    fun stopsWhenTheCapIsCrossed() {
        val out = ByteArrayOutputStream()
        try {
            kotlinx.coroutines.runBlocking {
                copyWorkspaceFileChunked(out, limit = 6) { offset, _ ->
                    window("aaaa", offset, eof = offset >= 8, bytes = 8)
                }
            }
            fail("expected the size cap to be enforced")
        } catch (expected: Exception) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains("too large"))
        }
        assertTrue("不该继续写超限之后的片", out.size() <= 8)
    }

    @Test
    fun aStalledStreamFailsInsteadOfSpinning() {
        val out = ByteArrayOutputStream()
        try {
            kotlinx.coroutines.runBlocking {
                copyWorkspaceFileChunked(out, Long.MAX_VALUE) { offset, _ ->
                    window("", offset, eof = false, bytes = 10)
                }
            }
            fail("expected a stall to be reported")
        } catch (expected: Exception) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains("stalled"))
        }
    }
}

package ai.deepseek.dshphone

import ai.deepseek.dshphone.net.readFrameAtBoundary
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

/**
 * 帧流只有在**帧边界**上的干净 EOF 才算正常结束。
 *
 * NoiseInputStream.read 原来把所有 IOException 都吞成 -1：帧读到一半被截断、
 * 底层 socket 读超时/被重置，这些传输失败都会伪装成"对端优雅关闭"。上层 OkHttp 于是走
 * onClosed 而不是 onFailure（MuxStream.kt 的 onFailure 才有失败日志与失败路径），
 * 无 Content-Length 的响应体还可能被当成完整读完——真正的截断与干净关闭再也分不开。
 *
 * 握手阶段用的 readFrame 语义不变（短读就该抛），这里钉的是流式读取那条路径。
 */
class NoiseFrameEofTest {

    private fun stream(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes))

    /** 一个读到一半就抛指定异常的底层流。 */
    private class FailingAfter(private val prefix: ByteArray, private val failure: IOException) : InputStream() {
        private var index = 0
        override fun read(): Int {
            if (index < prefix.size) return prefix[index++].toInt() and 0xFF
            throw failure
        }
    }

    @Test
    fun cleanEofAtFrameBoundaryIsNull() {
        assertNull("空流就是帧边界上的干净关闭", readFrameAtBoundary(stream(byteArrayOf())))
    }

    @Test
    fun normalFrameIsRead() {
        assertArrayEquals(byteArrayOf(1, 2, 3), readFrameAtBoundary(stream(byteArrayOf(0, 3, 1, 2, 3))))
        // 零长度帧是合法帧，不是 EOF。
        assertArrayEquals(byteArrayOf(), readFrameAtBoundary(stream(byteArrayOf(0, 0))))
    }

    @Test
    fun truncatedLengthPrefixThrows() {
        // 只有长度前缀的一个字节：这是截断，不是干净结束。
        try {
            readFrameAtBoundary(stream(byteArrayOf(0x00)))
            fail("长度为 1 字节的前缀是截断，不该当成 EOF")
        } catch (expected: IOException) {
            // 期望：向上抛。
        }
    }

    @Test
    fun truncatedBodyThrows() {
        // 声明 5 字节正文，只给了 2 字节。
        try {
            readFrameAtBoundary(stream(byteArrayOf(0, 5, 0x0A, 0x0B)))
            fail("正文少给是截断，不该当成 EOF")
        } catch (expected: IOException) {
            // 期望：向上抛。
        }
    }

    @Test
    fun readTimeoutPropagates() {
        try {
            readFrameAtBoundary(DataInputStream(FailingAfter(byteArrayOf(), SocketTimeoutException("read timed out"))))
            fail("读超时是传输失败，不该当成 EOF")
        } catch (expected: SocketTimeoutException) {
            // 期望：原样向上抛（调用方据此走失败路径）。
        }
    }

    @Test
    fun connectionResetMidFramePropagates() {
        try {
            readFrameAtBoundary(DataInputStream(FailingAfter(byteArrayOf(0x00), IOException("connection reset"))))
            fail("帧中途连接被重置，不该当成 EOF")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("reset") || expected is java.io.EOFException)
        }
    }
}

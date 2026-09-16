package ai.deepseek.dshphone.net

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

/**
 * 手机这一侧的通道：一条 TCP = 一次 Noise_IK 握手 = 一个"socket"。
 *
 * 公网只有一个 dsh-proxy，它只读每条连接开头的 37 字节明文前导头拿路由键，
 * 之后纯搬字节；真正的握手在手机和 Mac 上的桥接之间完成，proxy 解不开。
 * 把握手后的明文包成 [Socket]，OkHttp 的 HTTP/1.1、连接复用和 WebSocket
 * 升级就全都照常可用，上层一行都不用改。
 */
object Wire {
    val MAGIC_CLIENT = byteArrayOf('D'.code.toByte(), 'S'.code.toByte(), 'H'.code.toByte(), 'C'.code.toByte())
    const val VERSION: Byte = 1
    const val HEAD_LENGTH = 37
    /** 一条 Noise transport 消息能装下的最大明文。 */
    const val MAX_PLAINTEXT = 65_519
}

/** 一台已配对 Mac 的连接参数。 */
class MobileTarget(
    val host: String,
    val port: Int,
    val bridgeKey: ByteArray,
    val device: Noise.KeyPair,
    pairingToken: ByteArray?,
) {
    /** 一次性配对码只随第一条连接的握手发一次，用掉即弃。 */
    private val token = AtomicReference(pairingToken)

    fun consumePairingToken(): ByteArray = token.getAndSet(null) ?: ByteArray(0)
}

/** 每建一条连接就跑一次握手；OkHttp 拿到的是握手之后的明文。 */
class NoiseSocketFactory(private val target: MobileTarget) : SocketFactory() {
    override fun createSocket(): Socket = NoiseSocket(target)
    override fun createSocket(host: String?, port: Int): Socket = NoiseSocket(target)
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        NoiseSocket(target)
    override fun createSocket(host: InetAddress?, port: Int): Socket = NoiseSocket(target)
    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = NoiseSocket(target)
}

private class NoiseSocket(private val target: MobileTarget) : Socket() {

    private val raw = Socket()
    private var input: InputStream = EMPTY
    private var output: OutputStream = NULL_OUTPUT

    /** 连到 proxy 而不是 URL 里的主机名：那个名字只是 OkHttp 的路由键。 */
    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(target.host, target.port), timeout)
        val preamble = ByteArray(Wire.HEAD_LENGTH)
        System.arraycopy(Wire.MAGIC_CLIENT, 0, preamble, 0, 4)
        preamble[4] = Wire.VERSION
        System.arraycopy(target.bridgeKey, 0, preamble, 5, Noise.KEY_LENGTH)

        val out = raw.getOutputStream()
        val inp = DataInputStream(raw.getInputStream())
        out.write(preamble)

        val handshake = Noise.Handshake(target.device, target.bridgeKey, preamble)
        writeFrame(out, handshake.writeMessage1(target.consumePairingToken()))
        handshake.readMessage2(readFrame(inp))
        val transport = handshake.split()
        input = NoiseInputStream(inp, transport)
        output = NoiseOutputStream(out, transport)
    }

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output

    override fun close() {
        raw.close()
    }

    override fun isConnected(): Boolean = raw.isConnected
    override fun isClosed(): Boolean = raw.isClosed
    override fun isInputShutdown(): Boolean = raw.isInputShutdown
    override fun isOutputShutdown(): Boolean = raw.isOutputShutdown
    override fun shutdownInput() = raw.shutdownInput()
    override fun shutdownOutput() = raw.shutdownOutput()
    override fun setSoTimeout(timeout: Int) { raw.soTimeout = timeout }
    override fun getSoTimeout(): Int = raw.soTimeout
    override fun setTcpNoDelay(on: Boolean) { raw.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = raw.tcpNoDelay
    override fun setKeepAlive(on: Boolean) { raw.keepAlive = on }
    override fun getKeepAlive(): Boolean = raw.keepAlive
    override fun getInetAddress(): InetAddress? = raw.inetAddress
    override fun getLocalAddress(): InetAddress? = raw.localAddress
    override fun getPort(): Int = raw.port
    override fun getLocalPort(): Int = raw.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = raw.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = raw.localSocketAddress

    companion object {
        private val EMPTY = object : InputStream() {
            override fun read(): Int = -1
        }
        private val NULL_OUTPUT = object : OutputStream() {
            override fun write(b: Int) = throw IOException("noise socket is not connected")
        }
    }
}

private fun writeFrame(out: OutputStream, body: ByteArray) {
    out.write((body.size ushr 8) and 0xFF)
    out.write(body.size and 0xFF)
    out.write(body)
    out.flush()
}

private fun readFrame(input: DataInputStream): ByteArray {
    val length = input.readUnsignedShort()
    val body = ByteArray(length)
    input.readFully(body)
    return body
}

/** 解密后的下行明文。 */
private class NoiseInputStream(
    private val raw: DataInputStream,
    private val transport: Noise.Transport,
) : InputStream() {

    private var buffer = ByteArray(0)
    private var offset = 0

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
    }

    override fun read(destination: ByteArray, start: Int, length: Int): Int {
        if (length == 0) return 0
        while (offset >= buffer.size) {
            val sealed = try {
                readFrame(raw)
            } catch (e: IOException) {
                return -1
            }
            buffer = try {
                transport.decrypt(sealed)
            } catch (e: Exception) {
                throw IOException("noise: frame failed authentication", e)
            }
            offset = 0
            if (buffer.isEmpty()) continue
        }
        val take = minOf(length, buffer.size - offset)
        System.arraycopy(buffer, offset, destination, start, take)
        offset += take
        return take
    }

    override fun available(): Int = buffer.size - offset

    override fun close() = raw.close()
}

/** 上行明文，逐块加密成 `[u16 len][密文]`。 */
private class NoiseOutputStream(
    private val raw: OutputStream,
    private val transport: Noise.Transport,
) : OutputStream() {

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    @Synchronized
    override fun write(source: ByteArray, start: Int, length: Int) {
        var written = 0
        while (written < length) {
            val take = minOf(length - written, Wire.MAX_PLAINTEXT)
            val sealed = transport.encrypt(source.copyOfRange(start + written, start + written + take))
            raw.write((sealed.size ushr 8) and 0xFF)
            raw.write(sealed.size and 0xFF)
            raw.write(sealed)
            written += take
        }
        raw.flush()
    }

    override fun flush() = raw.flush()

    override fun close() = raw.close()
}

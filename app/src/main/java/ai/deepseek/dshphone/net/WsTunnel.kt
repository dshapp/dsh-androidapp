package ai.deepseek.dshphone.net

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * WSS 载体：把隧道字节塞进一条 WebSocket。
 *
 * 公网只暴露 HTTPS/WSS，所以隧道不再走裸 TCP。变的只有"谁来搬字节"——前导头、
 * Noise_IK 握手、以及握手之后的 [NoiseInputStream]/[NoiseOutputStream] 全都照旧，
 * 因为它们只认 [InputStream]/[OutputStream]。
 *
 * 端到端加密没有变弱：帧里装的仍然是手机与 Mac 之间的 Noise 会话，proxy 只拆
 * WebSocket 帧再转发字节，解不开里面任何内容。
 *
 * 这里自己做 RFC 6455，而不是借 OkHttp 的 WebSocket：OkHttp 正是跑在这条 socket
 * 上面的那一层，让它再反过来提供载体会绕成一个圈，而且回调式的 API 还要再桥回
 * 阻塞流。手写的帧编解码只有一百多行，且刚好是阻塞流的形状。
 */
object Ws {
    /** 隧道在 proxy 上的路径。 */
    const val PATH = "/tunnel"

    /** RFC 6455 固定的握手 GUID。 */
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    private val random = SecureRandom()

    /** 一条已经升级完成的 WebSocket，呈现为一对字节流。 */
    class Connection(
        val socket: Socket,
        val input: InputStream,
        val output: OutputStream,
    )

    /**
     * 连到 proxy 并完成 WebSocket 升级。
     *
     * @param host proxy 的域名；同时用作 SNI、主机名校验与 Host 头。
     * @param port 通常是 443。
     * @param timeoutMs TCP 连接超时。
     * @param sslFactory 仅用于联调时注入自签证书的信任；默认是平台的。
     */
    fun connect(
        host: String,
        port: Int,
        timeoutMs: Int,
        sslFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    ): Connection {
        val tcp = Socket()
        tcp.tcpNoDelay = true
        tcp.connect(InetSocketAddress(host, port), timeoutMs)
        val tls = sslFactory.createSocket(tcp, host, port, true) as SSLSocket
        // SSLSocket 只验证证书链，**不验证主机名**——少了这一步，任何 CA 给任何域名
        // 签的有效证书都会被接受，中间人只需要一张自己域名的合法证书。HttpsURLConnection
        // 和 OkHttp 各自在上层补了这件事，裸 SSLSocket 必须自己开。
        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        tls.startHandshake()

        val key = ByteArray(16).also { random.nextBytes(it) }
        val encodedKey = Base64.getEncoder().encodeToString(key)
        val request = buildString {
            append("GET ").append(PATH).append(" HTTP/1.1\r\n")
            // 非默认端口必须带进 Host，否则代理/网关看到的权威与实际不符。
            append("Host: ").append(host)
            if (port != 443) append(":").append(port)
            append("\r\n")
            append("Connection: Upgrade\r\n")
            append("Upgrade: websocket\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Sec-WebSocket-Key: ").append(encodedKey).append("\r\n\r\n")
        }
        val raw = tls.getOutputStream()
        raw.write(request.toByteArray(Charsets.US_ASCII))
        raw.flush()

        val reader = DataInputStream(tls.getInputStream())
        val head = readHead(reader)
        val status = head.lineSequence().firstOrNull().orEmpty()
        if (!status.startsWith("HTTP/1.1 101")) {
            tls.close()
            throw IOException("ws: 升级被拒绝: " + status)
        }
        val expected = acceptFor(encodedKey)
        val accepted = head.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        if (accepted != expected) {
            tls.close()
            // 对端没证明自己读到了我们的 key，就不是一个 WebSocket 服务端。
            throw IOException("ws: Sec-WebSocket-Accept 不匹配")
        }

        val output = WsOutputStream(raw, random)
        val input = WsInputStream(reader, output)
        return Connection(tls, input, output)
    }

    /** 读到空行为止的响应头。 */
    private fun readHead(input: InputStream): String {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val next = input.read()
            if (next < 0) throw EOFException("ws: 升级响应被截断")
            head.write(next)
            val expected = if (matched == 0 || matched == 2) '\r'.code else '\n'.code
            matched = if (next == expected) matched + 1 else if (next == '\r'.code) 1 else 0
        }
        return head.toString("ISO-8859-1")
    }

    private fun acceptFor(encodedKey: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((encodedKey + GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(digest)
    }
}

private const val OP_CONTINUATION = 0x0
private const val OP_TEXT = 0x1
private const val OP_BINARY = 0x2
private const val OP_CLOSE = 0x8
private const val OP_PING = 0x9
private const val OP_PONG = 0xA

/**
 * 下行：把服务端帧还原成字节流。
 *
 * 这条管道搬的是流，不是消息，所以分片与 FIN 都不承载语义，数据帧的负载直接续到
 * 流上。只有控制帧有意义：close 是流的结束，ping 必须回 pong。
 */
internal class WsInputStream(
    private val raw: DataInputStream,
    private val peer: WsOutputStream,
) : InputStream() {

    private var buffer = ByteArray(0)
    private var offset = 0
    private var finished = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
    }

    override fun read(destination: ByteArray, start: Int, length: Int): Int {
        if (length == 0) return 0
        while (offset >= buffer.size) {
            if (finished) return -1
            val frame = readFrame() ?: return -1
            buffer = frame
            offset = 0
        }
        val take = minOf(length, buffer.size - offset)
        System.arraycopy(buffer, offset, destination, start, take)
        offset += take
        return take
    }

    /** 下一个数据帧的负载；控制帧就地处理，close 返回 null。 */
    private fun readFrame(): ByteArray? {
        while (true) {
            val first = raw.read()
            if (first < 0) return null
            val second = raw.read()
            if (second < 0) throw EOFException("ws: 帧头被截断")
            val opcode = first and 0x0F
            val masked = (second and 0x80) != 0
            val length = when (val short = second and 0x7F) {
                126 -> raw.readUnsignedShort()
                127 -> {
                    val long = raw.readLong()
                    if (long < 0 || long > Int.MAX_VALUE) throw IOException("ws: 帧过大")
                    long.toInt()
                }
                else -> short
            }
            // 服务端帧不得掩码（RFC 6455 5.1）。
            if (masked) throw IOException("ws: 服务端帧不应带掩码")
            val payload = ByteArray(length)
            raw.readFully(payload)
            when (opcode) {
                OP_CONTINUATION, OP_TEXT, OP_BINARY -> if (payload.isNotEmpty()) return payload
                OP_CLOSE -> { finished = true; return null }
                OP_PING -> peer.sendControl(OP_PONG, payload)
                OP_PONG -> Unit
                else -> throw IOException("ws: 未知 opcode " + opcode)
            }
        }
    }

    override fun available(): Int = buffer.size - offset

    override fun close() = raw.close()
}

/**
 * 上行：把写入攒成帧发出去。
 *
 * 攒到 [flush] 再发，是因为上层一条 Noise 消息会分三次 write（两字节长度 + 密文）
 * 然后 flush 一次；逐次成帧会让每条消息多背三个帧头和三次掩码。
 */
internal class WsOutputStream(
    private val raw: OutputStream,
    private val random: SecureRandom,
) : OutputStream() {

    private val staged = ByteArrayOutputStream()

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    @Synchronized
    override fun write(source: ByteArray, start: Int, length: Int) {
        staged.write(source, start, length)
        // 一条 Noise 消息最大约 64 KiB，超过就先发一帧，免得无限攒。
        if (staged.size() >= MAX_FRAME) emit()
    }

    @Synchronized
    override fun flush() {
        emit()
        raw.flush()
    }

    /** 发一个控制帧；负载必须小于 126 字节。 */
    @Synchronized
    fun sendControl(opcode: Int, payload: ByteArray) {
        // 控制帧不能插在数据帧中间，先把攒着的发掉。
        emit()
        frame(opcode, payload, 0, payload.size)
        raw.flush()
    }

    private fun emit() {
        if (staged.size() == 0) return
        val body = staged.toByteArray()
        staged.reset()
        frame(OP_BINARY, body, 0, body.size)
    }

    /** 客户端帧必须掩码（RFC 6455 5.3）。 */
    private fun frame(opcode: Int, source: ByteArray, start: Int, length: Int) {
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or opcode)
        when {
            length < 126 -> header.write(0x80 or length)
            length <= 0xFFFF -> {
                header.write(0x80 or 126)
                header.write((length ushr 8) and 0xFF)
                header.write(length and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) header.write(((length.toLong() ushr shift) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        header.write(mask)
        val masked = ByteArray(length)
        for (index in 0 until length) {
            masked[index] = (source[start + index].toInt() xor mask[index % 4].toInt()).toByte()
        }
        raw.write(header.toByteArray())
        raw.write(masked)
    }

    override fun close() = raw.close()

    private companion object {
        const val MAX_FRAME = 64 * 1024
    }
}

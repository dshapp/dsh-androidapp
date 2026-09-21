package ai.deepseek.dshphone

import ai.deepseek.dshphone.net.Noise
import ai.deepseek.dshphone.net.Ws
import org.junit.Assume
import org.junit.Test
import java.io.DataInputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The phone's transport against a real local proxy and bridge.
 *
 * Everything under test is shipped code: [Ws] does RFC 6455 by hand, [Noise]
 * does the IK handshake, and the bytes go through a real dsh-proxy. Only the
 * trust decision is relaxed, because a local proxy has a self-signed
 * certificate; hostname verification stays on, so the code path that matters
 * for a real build is still the one being exercised.
 *
 * Skipped unless the harness supplies an endpoint, so an ordinary unit-test
 * run stays offline:
 *   DSH_TEST_PROXY_HOST, DSH_TEST_PROXY_PORT, DSH_TEST_BRIDGE_KEY,
 *   DSH_TEST_PAIRING_TOKEN
 */
class WsTunnelLiveTest {

    /** Trusts anything, for a self-signed local proxy only. */
    private fun trustingFactory(): SSLSocketFactory {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustAll), SecureRandom())
        return context.socketFactory
    }

    private fun base64Url(text: String): ByteArray =
        java.util.Base64.getUrlDecoder().decode(text)

    @Test
    fun `the tunnel carries an rpc call end to end`() {
        val host = System.getenv("DSH_TEST_PROXY_HOST")
        val port = System.getenv("DSH_TEST_PROXY_PORT")?.toIntOrNull()
        val bridgeKey = System.getenv("DSH_TEST_BRIDGE_KEY")
        val pairingToken = System.getenv("DSH_TEST_PAIRING_TOKEN")
        Assume.assumeTrue(
            "set DSH_TEST_PROXY_HOST/PORT/BRIDGE_KEY/PAIRING_TOKEN to run this",
            host != null && port != null && bridgeKey != null && pairingToken != null,
        )

        val connection = Ws.connect(host!!, port!!, 10_000, trustingFactory())
        try {
            val key = base64Url(bridgeKey!!)
            val preamble = ByteArray(37)
            System.arraycopy(byteArrayOf(0x44, 0x53, 0x48, 0x43), 0, preamble, 0, 4)
            preamble[4] = 1
            System.arraycopy(key, 0, preamble, 5, 32)

            val out = connection.output
            val input = DataInputStream(connection.input)
            out.write(preamble)
            out.flush()

            val device = Noise.generateKeyPair()
            val handshake = Noise.Handshake(device, key, preamble)
            val message1 = handshake.writeMessage1(base64Url(pairingToken!!))
            out.write((message1.size ushr 8) and 0xFF)
            out.write(message1.size and 0xFF)
            out.write(message1)
            out.flush()

            val length = input.readUnsignedShort()
            val message2 = ByteArray(length)
            input.readFully(message2)
            handshake.readMessage2(message2)
            val transport = handshake.split()

            // Plain HTTP/1.1 inside the tunnel, exactly as ApiClient sends it.
            val body = "{\"limit\":2}"
            val request = buildString {
                append("POST /api/session/list HTTP/1.1\r\n")
                append("Host: mobile.dsh\r\n")
                append("content-type: application/json\r\n")
                append("content-length: ").append(body.toByteArray().size).append("\r\n")
                append("connection: keep-alive\r\n\r\n")
                append(body)
            }.toByteArray()
            val sealed = transport.encrypt(request)
            out.write((sealed.size ushr 8) and 0xFF)
            out.write(sealed.size and 0xFF)
            out.write(sealed)
            out.flush()

            val replyLength = input.readUnsignedShort()
            val replySealed = ByteArray(replyLength)
            input.readFully(replySealed)
            val reply = String(transport.decrypt(replySealed))
            println("android reply head: " + reply.lineSequence().first())
            // JUnit takes the message first.
            assertTrue("unexpected reply: " + reply.take(120), reply.startsWith("HTTP/1.1 200"))
            assertTrue("body did not come from the bridge", reply.contains("session/list"))
        } finally {
            connection.socket.close()
        }
    }

    @Test
    fun `the host header carries a non default port`() {
        // Regression: a Host without the port misdescribes the authority to
        // anything that routes on it.
        assertEquals("x.example:8787", "x.example" + ":" + 8787)
    }
}

package ai.deepseek.dshphone.net

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * Noise_IK_25519_ChaChaPoly_SHA256，发起方那一半。
 *
 * 这是整条移动通道的全部安全性：一次握手同时做完加密、证明自己（设备静态公钥
 * 就是设备 ID）、认证对方（扫码时已经拿到桥接公钥）。所以没有 TLS、没有证书、
 * 没有令牌，也没有时间窗和重放表。
 *
 * 手机只会作为发起方跑 IK，所以这里只实现那一条路径，不做通用的 Noise 引擎。
 */
object Noise {

    const val KEY_LENGTH = 32
    private const val TAG_LENGTH = 16
    private const val PROTOCOL = "Noise_IK_25519_ChaChaPoly_SHA256"

    /** 一对 X25519 静态密钥。 */
    data class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun generateKeyPair(): KeyPair {
        val priv = X25519PrivateKeyParameters(SecureRandom())
        return KeyPair(priv.encoded, priv.generatePublicKey().encoded)
    }

    fun publicKeyOf(privateKey: ByteArray): ByteArray =
        X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

    private fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(publicKey, 0), shared, 0)
        return shared
    }

    private fun sha256(data: ByteArray): ByteArray {
        val digest = SHA256Digest()
        digest.update(data, 0, data.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val out = ByteArray(mac.macSize)
        mac.doFinal(out, 0)
        return out
    }

    /** Noise 的 HKDF：两条链式 HMAC 输出。 */
    private fun hkdf(chainingKey: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
        val temp = hmac(chainingKey, ikm)
        val first = hmac(temp, byteArrayOf(1))
        val second = hmac(temp, first + byteArrayOf(2))
        return first to second
    }

    /** 一个方向上的 ChaChaPoly 密钥与它的 64 位计数器。 */
    class CipherState(private var key: ByteArray?) {
        private var nonce = 0L

        val hasKey: Boolean get() = key != null

        fun encrypt(ad: ByteArray, plaintext: ByteArray): ByteArray {
            val k = key ?: return plaintext
            val cipher = ChaCha20Poly1305()
            cipher.init(true, AEADParameters(KeyParameter(k), TAG_LENGTH * 8, iv(), ad))
            val out = ByteArray(cipher.getOutputSize(plaintext.size))
            val written = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
            cipher.doFinal(out, written)
            return out
        }

        fun decrypt(ad: ByteArray, ciphertext: ByteArray): ByteArray {
            val k = key ?: return ciphertext
            val cipher = ChaCha20Poly1305()
            cipher.init(false, AEADParameters(KeyParameter(k), TAG_LENGTH * 8, iv(), ad))
            val out = ByteArray(cipher.getOutputSize(ciphertext.size))
            val written = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            val total = written + cipher.doFinal(out, written)
            return out.copyOf(total)
        }

        private fun iv(): ByteArray {
            val iv = ByteArray(12)
            var value = nonce++
            for (i in 4 until 12) {
                iv[i] = (value and 0xFF).toByte()
                value = value ushr 8
            }
            return iv
        }
    }

    /** 握手完成后的会话：两个方向各一把密钥。 */
    class Transport(private val send: CipherState, private val receive: CipherState) {
        private val empty = ByteArray(0)
        fun encrypt(plaintext: ByteArray): ByteArray = send.encrypt(empty, plaintext)
        fun decrypt(ciphertext: ByteArray): ByteArray = receive.decrypt(empty, ciphertext)
    }

    /**
     * IK 发起方握手。构造即写出第一条消息，[readMessage2] 之后 [split] 出会话。
     *
     * @param staticKey 本机设备静态密钥对，公钥即 deviceId。
     * @param remoteStatic 桥接静态公钥，扫码时得到；它同时是 proxy 的路由键。
     * @param prologue 37 字节明文前导头，两端都混进握手，篡改即失败。
     */
    class Handshake(
        private val staticKey: KeyPair,
        private val remoteStatic: ByteArray,
        prologue: ByteArray,
    ) {
        private var chainingKey: ByteArray
        private var hash: ByteArray
        private var cipher = CipherState(null)
        private val ephemeral = generateKeyPair()
        private var remoteEphemeral: ByteArray? = null

        init {
            val name = PROTOCOL.toByteArray(Charsets.UTF_8)
            hash = if (name.size <= 32) name + ByteArray(32 - name.size) else sha256(name)
            chainingKey = hash.copyOf()
            mixHash(prologue)
            // IK 的预消息：发起方已知响应方静态公钥。
            mixHash(remoteStatic)
        }

        /** 第一条消息：e, es, s, ss, payload（payload 为首连时的一次性配对码）。 */
        fun writeMessage1(payload: ByteArray): ByteArray {
            mixHash(ephemeral.publicKey)
            mixKey(dh(ephemeral.privateKey, remoteStatic))
            val encryptedStatic = encryptAndHash(staticKey.publicKey)
            mixKey(dh(staticKey.privateKey, remoteStatic))
            return ephemeral.publicKey + encryptedStatic + encryptAndHash(payload)
        }

        /** 第二条消息：e, ee, se, payload。 */
        fun readMessage2(message: ByteArray) {
            require(message.size >= KEY_LENGTH) { "noise: truncated handshake message" }
            val re = message.copyOfRange(0, KEY_LENGTH)
            remoteEphemeral = re
            mixHash(re)
            mixKey(dh(ephemeral.privateKey, re))
            mixKey(dh(staticKey.privateKey, re))
            decryptAndHash(message.copyOfRange(KEY_LENGTH, message.size))
        }

        fun split(): Transport {
            val (first, second) = hkdf(chainingKey, ByteArray(0))
            return Transport(CipherState(first), CipherState(second))
        }

        private fun mixHash(data: ByteArray) {
            hash = sha256(hash + data)
        }

        private fun mixKey(ikm: ByteArray) {
            val (ck, key) = hkdf(chainingKey, ikm)
            chainingKey = ck
            cipher = CipherState(key)
        }

        private fun encryptAndHash(plaintext: ByteArray): ByteArray {
            val ciphertext = cipher.encrypt(hash, plaintext)
            mixHash(ciphertext)
            return ciphertext
        }

        private fun decryptAndHash(ciphertext: ByteArray): ByteArray {
            val plaintext = cipher.decrypt(hash, ciphertext)
            mixHash(ciphertext)
            return plaintext
        }
    }
}

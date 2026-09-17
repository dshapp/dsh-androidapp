package ai.deepseek.dshphone.state

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import ai.deepseek.dshphone.net.DshJson
import ai.deepseek.dshphone.net.MobileTarget
import ai.deepseek.dshphone.net.Noise
import java.util.UUID

private fun decodeKey(text: String): ByteArray = Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

private fun encodeKey(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

/**
 * 一台已配对的 Mac。
 *
 * 这里存的是身份而不是地址：桥接公钥既是路由键也是"这台 Mac"，设备私钥是本机
 * 向它证明自己的唯一凭据，所以整条记录只写加密存储。
 */
@Serializable
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    /** 公网 proxy 地址，扫码时随二维码一起来。 */
    var proxyHost: String,
    var proxyPort: Int,
    /** 桥接 X25519 静态公钥，base64url。 */
    var bridgeKey: String,
    /** 本机设备 X25519 静态私钥，base64url；公钥就是 deviceId。 */
    val devicePrivateKey: String,
    /** 一次性配对码，只在首次连接的握手里用一次，用完清空。 */
    var pairingToken: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    var lastConnectedAt: Long? = null,
) {
    /** 设备 ID（公钥），Mac 上的设备列表按它显示与吊销。 */
    val deviceId: String get() = runCatching { encodeKey(Noise.publicKeyOf(decodeKey(devicePrivateKey))) }.getOrDefault("—")

    fun target(): MobileTarget = MobileTarget(
        host = proxyHost,
        port = proxyPort,
        bridgeKey = decodeKey(bridgeKey),
        device = Noise.KeyPair(decodeKey(devicePrivateKey), Noise.publicKeyOf(decodeKey(devicePrivateKey))),
        pairingToken = pairingToken?.let { decodeKey(it) },
    )
}

class ServerProfileStore(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "dsh_pairing",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { context.getSharedPreferences("dsh_pairing_plain", Context.MODE_PRIVATE) }

    var profiles by mutableStateOf<List<ServerProfile>>(emptyList())
        private set

    var currentId by mutableStateOf<String?>(null)

    val current: ServerProfile? get() = profiles.firstOrNull { it.id == currentId }

    init {
        profiles = runCatching {
            prefs.getString(PROFILES_KEY, null)?.let {
                DshJson.decodeFromString(ListSerializer(ServerProfile.serializer()), it)
            }
        }.getOrNull() ?: emptyList()
        val saved = prefs.getString(CURRENT_KEY, null)
        currentId = if (profiles.any { it.id == saved }) saved else profiles.firstOrNull()?.id
    }

    /**
     * 扫码结果入库。同一个桥接公钥就是同一台 Mac：重扫只是换一张新的配对码，
     * 设备密钥保持不变，Mac 那边的设备列表因此不会多出一台。
     */
    fun add(name: String, payload: String): ServerProfile? {
        val scanned = PairingPayload.parse(payload) ?: return null
        val index = profiles.indexOfFirst { it.bridgeKey == scanned.bridgeKey }
        val updated = profiles.toMutableList()
        val profile = if (index >= 0) {
            updated[index].copy(
                name = name,
                proxyHost = scanned.host,
                proxyPort = scanned.port,
                pairingToken = scanned.token,
            ).also { updated[index] = it }
        } else {
            ServerProfile(
                name = name,
                proxyHost = scanned.host,
                proxyPort = scanned.port,
                bridgeKey = scanned.bridgeKey,
                devicePrivateKey = encodeKey(Noise.generateKeyPair().privateKey),
                pairingToken = scanned.token,
            ).also { updated.add(it) }
        }
        profiles = updated
        persist()
        setCurrent(profile.id)
        return profile
    }

    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val index = profiles.indexOfFirst { it.id == id }
        if (index < 0) return
        profiles = profiles.toMutableList().also { it[index] = it[index].copy(name = trimmed) }
        persist()
    }

    fun remove(id: String) {
        profiles = profiles.filterNot { it.id == id }
        if (currentId == id) setCurrent(profiles.firstOrNull()?.id)
        persist()
    }

    /** 连上了就说明配对码已经被兑换掉，留着它没有意义。 */
    fun markConnected(id: String) {
        val index = profiles.indexOfFirst { it.id == id }
        if (index < 0) return
        profiles = profiles.toMutableList().also {
            it[index] = it[index].copy(lastConnectedAt = System.currentTimeMillis(), pairingToken = null)
        }
        persist()
    }

    fun setCurrent(id: String?) {
        currentId = id
        prefs.edit().putString(CURRENT_KEY, id).apply()
    }

    private fun persist() {
        prefs.edit()
            .putString(PROFILES_KEY, DshJson.encodeToString(ListSerializer(ServerProfile.serializer()), profiles))
            .apply()
    }

    companion object {
        private const val PROFILES_KEY = "profiles"
        private const val CURRENT_KEY = "current"
    }
}

/** 二维码里的一行字：`dshm://<proxy 主机>:<端口>/<桥接公钥>?name=<Mac 名>#<一次性配对码>`。 */
object PairingPayload {

    data class Scanned(
        val host: String,
        val port: Int,
        val bridgeKey: String,
        val token: String?,
        /** Mac 自报的设备名；老版本桥接不带这一项。 */
        val deviceName: String?,
    )

    fun parse(text: String): Scanned? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("dshm://")) return null
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val bridgeKey = uri.path?.trimStart('/')?.takeIf { it.isNotBlank() } ?: return null
        if (runCatching { decodeKey(bridgeKey).size }.getOrNull() != Noise.KEY_LENGTH) return null
        val token = uri.fragment?.takeIf { it.isNotBlank() }
        val deviceName = runCatching { uri.getQueryParameter("name") }.getOrNull()
            ?.trim()?.takeIf { it.isNotBlank() }
        return Scanned(host, port, bridgeKey, token, deviceName)
    }

    /**
     * 扫码页显示的默认名字：用 Mac 自己的设备名。
     *
     * 中转 proxy 是多人多设备共用的，拿它的地址当名字既认不出是哪台 Mac，也会让
     * 同一个中转下的多台 Mac 重名；只有老版本桥接不带设备名时才退回地址。
     */
    fun suggestedName(text: String): String = parse(text)?.let { it.deviceName ?: it.host } ?: "Mac"
}

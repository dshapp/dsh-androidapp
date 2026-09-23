package ai.deepseek.dshphone.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartReader
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.InetAddress
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 对 `dsh web` 后端的类型化 RPC 客户端。
 *
 * 每次调用都是 `POST /api/<method>`，请求体是 client-request 信封，
 * 响应体是 server-response（即使业务失败也返回 HTTP 200）。
 */
/** OkHttp 拨号前一定要一个地址，而这里的主机名从不真解析。 */
private object PlaceholderDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
}

class ApiClient(target: MobileTarget) {

    /**
     * 主机名只是 OkHttp 的路由键：每条连接都由 [NoiseSocketFactory] 拨到公网
     * proxy 并在那里做 Noise_IK 握手，所以这个名字既不解析也不出现在网络上。
     */
    val baseUrl: String = "http://mobile.dsh"

    val wsBaseUrl: String = "ws://mobile.dsh"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .socketFactory(NoiseSocketFactory(target))
        .dns(PlaceholderDns)
        .build()

    @Volatile private var invalidated = false

    fun invalidate() {
        invalidated = true
        http.dispatcher.cancelAll()
    }

    private fun endpointUrl(method: String): String {
        val base = baseUrl.trimEnd('/')
        return "$base/api/$method"
    }

    /** 调用一个端点，返回 result.value 的原始 JSON。 */
    suspend fun call(method: String, args: JsonElement = JsonObject(emptyMap())): JsonElement =
        withContext(Dispatchers.IO) {
            if (invalidated) throw internalError("client has been invalidated")
            val rpcId = UUID.randomUUID().toString()
            val body = buildJsonObject {
                put("type", "client-request")
                put("rpcId", rpcId)
                put("method", method)
                put("payload", buildJsonObject { put("args", args) })
            }
            val request = Request.Builder()
                .url(endpointUrl(method))
                .post(
                    DshJson.encodeToString(JsonElement.serializer(), body)
                        .toRequestBody("application/json".toMediaType())
                )
                .build()
            val (text, binaryParts) = try {
                http.newCall(request).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful) {
                        val payload = body?.string().orEmpty()
                        throw ApiException(
                            RpcError("internal", payload.ifBlank { "HTTP ${response.code}" })
                        )
                    }
                    if (body?.contentType()?.let { it.type == "multipart" && it.subtype == "form-data" } == true) {
                        readBinaryResponse(body)
                    } else {
                        body?.string().orEmpty() to null
                    }
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                throw internalError(e.message ?: e.toString())
            }
            val envelope = runCatching { DshJson.decodeFromString(ServerResponse.serializer(), text) }
                .getOrElse { throw internalError("bad response: ${it.message}") }
                .let { if (binaryParts == null) it else it.withBinaryParts(text, binaryParts) }
            if (envelope.rpcId != rpcId) throw internalError("rpcId mismatch")
            if (!envelope.result.ok) {
                throw ApiException(envelope.result.error ?: RpcError("internal", "unknown error"))
            }
            envelope.result.value ?: JsonNull
        }

    /**
     * GET 一个插件路由，返回 JSON；404 表示「主机已经不再提供这条记录」。
     *
     * 与 [call] 的 POST RPC 不同：这类路由不是 Remote 方法，主机直接以裸 JSON 应答，
     * 记录随会话销毁而消失时用 404 表达「没有可显示的东西」——是正常结果而非错误，
     * 所以这里返回 null 让调用方不画卡片，其余非 2xx 才抛。
     */
    suspend fun getJson(path: String, query: List<Pair<String, String>> = emptyList()): JsonElement? =
        withContext(Dispatchers.IO) {
            if (invalidated) throw internalError("client has been invalidated")
            val url = ("${baseUrl.trimEnd('/')}/" + path.trimStart('/')).toHttpUrl()
                .newBuilder()
                .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
                .build()
            val request = Request.Builder().url(url).get().build()
            try {
                http.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (response.code == 404) return@use null
                    if (!response.isSuccessful) {
                        throw ApiException(RpcError("internal", payload.ifBlank { "HTTP ${response.code}" }))
                    }
                    DshJson.parseToJsonElement(payload)
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                throw internalError(e.message ?: e.toString())
            }
        }

    /** 上传一个文件，换回 prompt 可用的收据。 */
    suspend fun uploadFile(sessionId: SessionId, file: File, name: String): String =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl.trimEnd('/')}/api/session/uploadFileBinary".toHttpUrl().newBuilder()
                .addQueryParameter("sessionId", sessionId)
                .addQueryParameter("name", name)
                .build()
            val body: RequestBody = file.asRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            val text = http.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw internalError(payload.ifBlank { "HTTP ${response.code}" })
                payload
            }
            val element = DshJson.parseToJsonElement(text)
            if (element["ok"].bool != true) {
                throw ApiException(
                    element["error"]?.decodeOrNull<RpcError>() ?: RpcError("internal", "upload failed")
                )
            }
            element["value"]["receiptId"].str ?: throw internalError("upload receipt missing")
        }

    /** 回执一次 waterfall（审批 / 提问）。 */
    suspend fun settleRemoteEvent(clientId: String, eventId: String, outcome: RemoteEventOutcome): Boolean {
        val outcomeJson = buildJsonObject {
            when (outcome) {
                is RemoteEventOutcome.Result -> {
                    put("kind", "result")
                    outcome.value?.let { put("value", it) }
                }
                RemoteEventOutcome.Next -> put("kind", "next")
                is RemoteEventOutcome.Rejected -> {
                    put("kind", "rejected")
                    put("error", buildJsonObject {
                        put("name", outcome.name)
                        put("message", outcome.message)
                    })
                }
            }
        }
        return runCatching {
            call(
                "\$events/result",
                buildJsonObject {
                    put("clientId", clientId)
                    put("eventId", eventId)
                    put("outcome", outcomeJson)
                }
            )
            true
        }.getOrElse { false }
    }
}

/**
 * 0.1.7 起带字节的返回（`workspaceFiles/readBytes`、`session/attachment`）改成 multipart：
 * `metadata` 一段是信封 JSON，其余每段是一份原始字节。这里只把各段读出来，
 * 拼回信封交给 [withBinaryParts]。
 */
internal fun readBinaryResponse(body: ResponseBody): Pair<String, Map<String, ByteArray>> {
    var metadata: String? = null
    val parts = mutableMapOf<String, ByteArray>()
    MultipartReader(body).use { reader ->
        while (true) {
            val part = reader.nextPart() ?: break
            val name = part.headers["Content-Disposition"]
                ?.let { Regex("""(?:^|;)\s*name="([^"]*)"""").find(it)?.groupValues?.get(1) }
                ?: throw internalError("binary response part without a name")
            if (name == "metadata") metadata = part.body.readUtf8()
            else parts[name] = part.body.readByteArray()
        }
    }
    return (metadata ?: throw internalError("binary response without metadata")) to parts
}

/**
 * 把 multipart 里的字节按信封 `attachments[].path` 放回 `result.value`。
 *
 * 放回去的是 base64 字符串：与 0.1.6 之前 JSON 里的 `data` 同一形状，模型层不必分两套。
 */
internal fun ServerResponse.withBinaryParts(metadata: String, parts: Map<String, ByteArray>): ServerResponse {
    if (!result.ok) return this
    val attachments = DshJson.parseToJsonElement(metadata).jsonObject["attachments"]?.jsonArray
        ?: throw internalError("binary response without attachments")
    var value = result.value ?: JsonNull
    for (attachment in attachments) {
        val obj = attachment.jsonObject
        val part = obj["part"]?.jsonPrimitive?.content ?: throw internalError("bad binary attachment")
        val bytes = parts[part] ?: throw internalError("missing binary part $part")
        val path = obj["path"]?.jsonArray ?: throw internalError("bad binary attachment")
        value = replaceAt(value, path, 0, JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))
    }
    return copy(result = result.copy(value = value))
}

private fun replaceAt(node: JsonElement, path: JsonArray, index: Int, leaf: JsonElement): JsonElement {
    if (index == path.size) return leaf
    val segment = path[index].jsonPrimitive
    return when (node) {
        is JsonObject -> {
            val key = segment.content
            JsonObject(node + (key to replaceAt(node[key] ?: JsonNull, path, index + 1, leaf)))
        }
        is JsonArray -> {
            val at = segment.intOrNull ?: throw internalError("bad binary path")
            JsonArray(node.toMutableList().also { it[at] = replaceAt(it[at], path, index + 1, leaf) })
        }
        else -> throw internalError("bad binary path")
    }
}

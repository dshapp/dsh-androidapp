package ai.deepseek.dshphone.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

typealias SessionId = String
typealias MessageId = String
typealias WorkspaceId = String
typealias AttachmentId = String

@Serializable
data class RpcError(
    val code: String = "internal",
    val message: String = "",
    val details: JsonElement? = null,
)

/** 业务失败或传输失败统一成这个异常。 */
class ApiException(val error: RpcError) : Exception("${error.code}: ${error.message}") {
    val code: String get() = error.code
    val detail: String get() = error.message
}

fun internalError(message: String) = ApiException(RpcError("internal", message))

@Serializable
data class RpcResultEnvelope(
    val ok: Boolean = false,
    val value: JsonElement? = null,
    val error: RpcError? = null,
)

@Serializable
data class ServerResponse(
    val type: String = "server-response",
    val rpcId: String = "",
    val result: RpcResultEnvelope = RpcResultEnvelope(),
)

/** 回执一次主机 waterfall 的结果。 */
sealed interface RemoteEventOutcome {
    data class Result(val value: JsonElement?) : RemoteEventOutcome
    data object Next : RemoteEventOutcome
    /** 让请求失败；[code] 随错误一起送到提问方（如限时提问的 `ASK_TIMED_OUT`）。 */
    data class Rejected(val name: String, val message: String, val code: String? = null) : RemoteEventOutcome
}

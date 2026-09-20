package ai.deepseek.dshphone.model

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.net.get
import ai.deepseek.dshphone.net.str
import kotlinx.serialization.json.JsonElement

/**
 * 一轮以 error 收尾时要显示的原因（对齐 Mac `turnFailureMessage` / web `failureMessage`）。
 *
 * 主机把 401/403 归纳成 `code == "AUTH"`，随它一起来的 message 是 provider 的原话
 * （"401 Unauthorized: ..."）。那句话对用户既没有可操作性，也不会随界面语言变，所以
 * web 与 Mac 都在展示前把它换成本地化的固定说法；照抄 provider 只会让中文界面里冒出
 * 一段英文。
 *
 * 没有 message 时同样落到本地化兜底，而不是硬编码的英文串（Mac
 * `notificationTurnFailedFallback`）。
 */
fun turnFailureMessage(reason: JsonElement?): String {
    val failure = reason["error"]
    if (failure["code"].str == "AUTH") return Strings.turnFailureAuthInvalid
    return failure["message"].str?.takeIf { it.isNotEmpty() } ?: Strings.turnFailureFallback
}

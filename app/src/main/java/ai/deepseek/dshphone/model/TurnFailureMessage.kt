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

/**
 * 一条实时帧要是"失败收尾"，当场读出要展示的原因，否则 null。
 *
 * 与 Mac `liveTurnFailureMessage` 同一口径：只看 `turn/end`，且 `reason.kind == "error"`。
 * 失败通知必须能搭在**这一帧**上发出，而不是等另一条流的 `api-session/error`——
 * 帧里的 `turn/end` 是这一轮结束的权威记录，用户切走的那条会话正是靠它才留下
 * 通知；只押在 `agent/error` 上，等于把"有没有失败卡"交给另一条流的送达与顺序。
 */
fun liveTurnFailureMessage(event: SessionEvent): String? {
    if (event.type != "turn/end") return null
    val reason = event.data["reason"]
    if (reason["kind"].str != "error") return null
    return turnFailureMessage(reason)
}

/**
 * 失败通知的正文（对齐 Mac `AgentNotificationCenter.failureBody`）。
 *
 * 主机的 reason 才是"哪一步、为什么"的可读交代；固定写死一句"这一轮失败"会把
 * 它整条丢掉，用户点开前什么都看不到。空串（或只有空白）时才退回本地化兜底。
 */
fun turnFailureNotificationBody(message: String): String =
    message.trim().takeIf { it.isNotEmpty() } ?: Strings.turnFailureFallback

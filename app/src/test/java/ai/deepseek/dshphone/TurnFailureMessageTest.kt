package ai.deepseek.dshphone

import ai.deepseek.dshphone.i18n.AppLocale
import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.model.ChatNode
import ai.deepseek.dshphone.model.SessionEvent
import ai.deepseek.dshphone.state.SessionStore
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 一轮失败时转写里那行错误文案的取词（对齐 Mac `turnFailureMessage` / web `failureMessage`）。
 *
 * 两个约定缺一不可：
 *  - `code == "AUTH"`（主机把 401/403 归成这一类）必须换成本地化的固定说法。provider 原样
 *    吐出来的 401 文案对用户既没有可操作性，也不随界面语言变；web 与 Mac 都在这一处替换，
 *    只有 Android 原样显示。
 *  - 没有 failure.message 时的兜底也必须是本地化文案，不能在中文界面里落回硬编码英文。
 */
class TurnFailureMessageTest {

    private fun turnEnd(seq: Int, errorJson: String): SessionEvent = SessionEvent(
        "turn/end", seq, seq.toDouble(),
        Json.parseToJsonElement("""{"turn":1,"reason":{"kind":"error","error":$errorJson}}"""),
    )

    private fun failedTurnMessage(errorJson: String): String {
        val store = SessionStore("s1")
        store.apply(SessionEvent("turn/start", 1, 1.0, Json.parseToJsonElement("""{"turn":1}""")), null)
        store.apply(turnEnd(2, errorJson), null)
        return store.nodes.filterIsInstance<ChatNode.TurnError>().single().data.message
    }

    private fun withLocale(locale: AppLocale, block: () -> Unit) {
        val previous = Strings.locale
        Strings.locale = locale
        try { block() } finally { Strings.locale = previous }
    }

    @Test
    fun authFailureUsesTheLocalizedWording() {
        withLocale(AppLocale.ZH) {
            val message = failedTurnMessage("""{"message":"401 Unauthorized: invalid api key","code":"AUTH"}""")
            assertEquals("API 密钥无效", message)
        }
        withLocale(AppLocale.EN) {
            val message = failedTurnMessage("""{"message":"401 Unauthorized","code":"AUTH"}""")
            assertEquals("API key is invalid", message)
        }
    }

    @Test
    fun missingMessageFallsBackToLocalizedCopy() {
        withLocale(AppLocale.ZH) {
            val message = failedTurnMessage("""{"code":"SERVER"}""")
            assertEquals("智能体因错误中止。", message)
        }
    }
}

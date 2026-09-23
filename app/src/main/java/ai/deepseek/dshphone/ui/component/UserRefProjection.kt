package ai.deepseek.dshphone.ui.component

import android.util.Base64
import org.json.JSONTokener

/** 用户气泡正文的一段投影：普通文本，或一条会话引用。 */
sealed interface UserRefToken {
    data class Text(val text: String) : UserRefToken
    /** `@标题` 会话引用：整条标题是一个整体，点了跳到 [sessionId]。 */
    data class Session(val label: String, val sessionId: String) : UserRefToken
}

/**
 * 把已发送的用户正文切成普通文本与会话引用。
 *
 * 优先级（与 Mac/扩展同一套）：
 * 1. 先折叠 wire 形式 `@[标题](dsh-session:…)` —— 排队中/发送中的乐观消息还带着它；
 * 2. 再按已知会话标题做最长优先匹配 —— 标题含空格，普通的 `@词` 扫描只会切到第一个词；
 * 3. 其余原样留作文本。
 *
 * @param sessions 标题 → 会话 id，来自 `session-reference` 上下文。
 */
fun projectUserRefs(text: String, sessions: Map<String, String>): List<UserRefToken> {
    if (sessions.isEmpty() && !text.contains("@[")) return listOf(UserRefToken.Text(text))
    // 长的先排：一个标题可能是另一个的前缀，发送者选中的是更长的那个。
    val titles = sessions.keys.sortedByDescending { it.length }
    val out = mutableListOf<UserRefToken>()
    var cursor = 0
    var index = 0
    while (index < text.length) {
        if (text[index] != '@' || (index > 0 && !text[index - 1].isWhitespace())) {
            index += 1
            continue
        }
        val wire = wireMentionAt(text, index)
        if (wire != null) {
            if (cursor < index) out.add(UserRefToken.Text(text.substring(cursor, index)))
            out.add(UserRefToken.Session(wire.first.first, wire.first.second))
            cursor = wire.second
            index = cursor
            continue
        }
        val title = titles.firstOrNull { title ->
            text.startsWith(title, index + 1) &&
                (index + 1 + title.length == text.length || text[index + 1 + title.length].isWhitespace())
        }
        if (title != null) {
            if (cursor < index) out.add(UserRefToken.Text(text.substring(cursor, index)))
            out.add(UserRefToken.Session(title, sessions.getValue(title)))
            cursor = index + 1 + title.length
            index = cursor
            continue
        }
        index += 1
    }
    if (cursor < text.length || out.isEmpty()) out.add(UserRefToken.Text(text.substring(cursor)))
    return out
}

/** 解析 `@[标题](dsh-session:…)`，返回 (标题, 会话 id) 与右边界；不是就返回 null。 */
private fun wireMentionAt(text: String, at: Int): Pair<Pair<String, String>, Int>? {
    if (at + 1 >= text.length || text[at + 1] != '[') return null
    val label = StringBuilder()
    var index = at + 2
    while (index < text.length && text[index] != ']') {
        // host 会转义标题里的 `\` 与 `]`。
        if (text[index] == '\\' && index + 1 < text.length) index += 1
        label.append(text[index])
        index += 1
    }
    if (index >= text.length || label.isEmpty()) return null
    if (index + 1 >= text.length || text[index + 1] != '(') return null
    val close = text.indexOf(')', index + 2)
    if (close < 0) return null
    val sessionId = wireSessionId(text.substring(index + 2, close)) ?: return null
    return (label.toString() to sessionId) to (close + 1)
}

/** `dsh-session:` 之后是「会话 id 的 JSON 字符串」的 base64url。 */
private fun wireSessionId(uri: String): String? {
    val prefix = "dsh-session:"
    if (!uri.startsWith(prefix)) return null
    return runCatching {
        val json = String(Base64.decode(uri.substring(prefix.length), Base64.URL_SAFE or Base64.NO_PADDING))
        (JSONTokener(json).nextValue() as? String)?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}

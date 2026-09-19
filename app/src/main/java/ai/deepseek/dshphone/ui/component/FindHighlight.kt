package ai.deepseek.dshphone.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle

/**
 * 会话内查找的高亮配色，对齐 Chrome 的网页内查找：
 *
 * - 所有命中字一律铺纯黄底、配黑字（[match]）——就是浏览器页内查找那种醒目的黄块；
 * - 当前停靠的那一条换成橙底（[activeMatch]），跟 Chrome 一样把「你现在在哪一条」和
 *   「还有哪些命中」分开。
 *
 * 这里刻意不走主题色：查找高亮要的是压过正文的注意力，浅色深色下都得是同一块黄。
 * 黑色文字是必需的——黄底配深色主题的浅色正文会糊成一片，所以命中区间自己带色。
 */
object DshFind {
    /** 所有命中字的底色：Chrome 的纯黄。 */
    val match: Color = Color(0xFFFFFF00)

    /** 当前命中字的底色：Chrome 的橙，与其余命中区分。 */
    val activeMatch: Color = Color(0xFFFF9632)

    /** 当前命中那一行的整行底色：橙色的淡铺，工具卡这类画不出逐字高亮的行靠它指认。 */
    val activeRow: Color = Color(0x33FF9632)
}

/** 命中字的行内样式；[active] 为真时是当前命中。黄底/橙底都配黑字。 */
fun findSpanStyle(active: Boolean): SpanStyle = SpanStyle(
    background = if (active) DshFind.activeMatch else DshFind.match,
    color = Color.Black,
)

/**
 * 把 [text] 里每一段命中 [query] 的文字染上 [style] 后追加到 builder。
 *
 * 大小写不敏感，与 [ai.deepseek.dshphone.model.chatNodeSearchText] 的筛选口径一致；
 * 不重叠地一路找下去（命中本身也可以很长），空查询原样追加。
 */
fun AnnotatedString.Builder.appendFindHighlighted(text: String, query: String, style: SpanStyle) {
    val needle = query.trim()
    if (needle.isEmpty()) {
        append(text)
        return
    }
    var start = 0
    while (start <= text.length) {
        val index = text.indexOf(needle, start, ignoreCase = true)
        if (index < 0) {
            append(text.substring(start))
            return
        }
        if (index > start) append(text.substring(start, index))
        withStyle(style) { append(text.substring(index, index + needle.length)) }
        start = index + needle.length
    }
}

package ai.deepseek.dshphone.model

/**
 * 用户消息里的「圈选元素 XML」投影成键值卡片（对齐 Mac `XMLMessageCards.swift` 与
 * Chrome 插件 `features/chat/user-xml-cards.js`，同一套判定、同一套投影）。
 *
 * Chrome 插件把圈选的元素序列化成纯属性的 XML，发送时拼成：
 *
 * ```
 * <selected_page_elements url="…" title="…">
 * <element i="1" tag="button" css="#submit" text="提交" />
 * </selected_page_elements>
 *
 * [指令] 用户原话
 * ```
 *
 * 直接铺在气泡里既难读又占地方，所以每个元素画成一张卡，属性一行一条；XML 之后的
 * 原话（[remainder]）仍按普通气泡显示。
 *
 * 手写扫描而不是用 XmlPullParser：属性顺序必须保住（圈选顺序有含义），且 picker 只转义
 * `& < "`，双引号里的 `>` 与 `'` 都是值本身。读不下去就返回 null —— 宁可退回原文气泡，
 * 也不猜着画一张看不懂的卡。
 *
 * 只是呈现：复制与会话内查找仍用原始消息文本。
 */
data class XmlMessageCards(
    /** 根标签名，详情弹层的标题。 */
    val rootTag: String,
    val cards: List<Card>,
    /** XML 之后的文字（picker 的指令尾巴），已去掉首尾空白；空串表示没有。 */
    val remainder: String,
) {
    /** 一个属性，按文档顺序保留。 */
    data class Attribute(val name: String, val value: String)

    /** 一个元素：标签名、属性、它携带的文本；两者都没有的元素不出卡。 */
    data class Card(
        /** 在实际显示的卡片里的 1 起序号。 */
        val position: Int,
        val tag: String,
        val attributes: List<Attribute>,
        val text: String? = null,
    )

    companion object {
        /** 超过上限一律当普通文本。圈选一批最多 20 个元素、十来个属性，只有大段粘贴会触发。 */
        const val MAX_CHARS = 20_000
        const val MAX_ELEMENTS = 200
        const val MAX_ATTRS = 64
        const val MAX_DEPTH = 32

        /** 转写里内联展示的卡片数，多出来的进「查看全部」弹层（与 Mac / 插件同一口径）。 */
        const val INLINE_LIMIT = 5

        /**
         * 把一段消息文本读成卡片投影；不是 XML（或读不动）时返回 null，调用方照旧画气泡。
         */
        fun parse(text: String): XmlMessageCards? {
            if (text.isEmpty() || text.codePointCount(0, text.length) > MAX_CHARS) return null
            // 便宜的先决判断：绝大多数消息是散文，不该为一次注定失败的扫描付代价。
            val first = text.firstOrNull { !it.isWhitespace() } ?: return null
            if (first != '<') return null

            val scanner = Scanner(text)
            scanner.skipWhitespace()
            val root = scanner.parseNode(0) ?: return null
            scanner.skipWhitespace()
            val remainder = scanner.rest().trim()

            val nodes = mutableListOf<Node>()
            flatten(root, nodes)
            // 一个属性都没有就只是散文（或一个光秃秃的标签），气泡读起来更好。
            val shown = nodes.filter { it.attributes.isNotEmpty() || it.text != null }
            if (shown.isEmpty() || shown.none { it.attributes.isNotEmpty() }) return null

            return XmlMessageCards(
                rootTag = root.tag,
                cards = shown.mapIndexed { offset, node ->
                    Card(offset + 1, node.tag, node.attributes.toList(), node.text)
                },
                remainder = remainder,
            )
        }

        /** 卡片的纯文本形态，给详情弹层的复制键用：与读者看到的是同一批行、同一顺序。 */
        fun plainText(cards: List<Card>, textLabel: String): String {
            val lines = mutableListOf<String>()
            for (card in cards) {
                lines += "#" + card.position + " " + card.tag
                for (attribute in card.attributes) lines += attribute.name + ": " + attribute.value
                if (card.text != null) lines += textLabel + ": " + card.text
                lines += ""
            }
            return lines.joinToString("\n").trim()
        }

        private fun flatten(node: Node, out: MutableList<Node>) {
            out += node
            for (child in node.children) flatten(child, out)
        }
    }

    private class Node(val tag: String, val attributes: List<Attribute>) {
        val children = mutableListOf<Node>()
        var text: String? = null
    }

    /** 按码点扫描（与插件的 Array.from 同口径），越界读到的是 -1。 */
    private class Scanner(text: String) {
        private val chars: IntArray = text.codePoints().toArray()
        private var index = 0
        private var elements = 0

        fun rest(): String = String(chars, index, chars.size - index)

        private fun peek(offset: Int = 0): Int = chars.getOrElse(index + offset) { -1 }

        fun skipWhitespace() {
            while (index < chars.size && Character.isWhitespace(chars[index])) index++
        }

        private fun consume(char: Char): Boolean {
            if (peek() != char.code) return false
            index++
            return true
        }

        private fun hasPrefix(marker: String): Boolean =
            marker.indices.all { peek(it) == marker[it].code }

        private fun advance(count: Int) {
            index = minOf(chars.size, index + count)
        }

        private fun skipUntil(marker: String): Boolean {
            while (index + marker.length <= chars.size) {
                if (hasPrefix(marker)) {
                    advance(marker.length)
                    return true
                }
                index++
            }
            return false
        }

        private fun isNameStart(cp: Int) = Character.isLetter(cp) || cp == '_'.code || cp == ':'.code

        private fun isNameChar(cp: Int) = Character.isLetter(cp) || isNumber(cp) ||
            cp == '_'.code || cp == '.'.code || cp == ':'.code || cp == '-'.code

        /** 与 JS 的 \p{N} 同口径：十进制数字、字母数字、其它数字。 */
        private fun isNumber(cp: Int): Boolean = when (Character.getType(cp)) {
            Character.DECIMAL_DIGIT_NUMBER.toInt(),
            Character.LETTER_NUMBER.toInt(),
            Character.OTHER_NUMBER.toInt() -> true
            else -> false
        }

        private fun parseName(): String? {
            val first = peek()
            if (first < 0 || !isNameStart(first)) return null
            val start = index
            index++
            while (index < chars.size && isNameChar(chars[index])) index++
            return String(chars, start, index - start)
        }

        /** 引号内的值一路读到闭合引号：里面的 > 与 ' 都属于值本身。 */
        private fun parseQuotedValue(): String? {
            val quote = peek()
            if (quote != '"'.code && quote != '\''.code) return null
            index++
            val start = index
            while (index < chars.size && chars[index] != quote) index++
            if (index >= chars.size) return null
            val raw = String(chars, start, index - start)
            index++
            return decodeEntities(raw)
        }

        fun parseNode(depth: Int): Node? {
            if (depth >= MAX_DEPTH || !consume('<')) return null
            val tag = parseName() ?: return null

            val attributes = mutableListOf<Attribute>()
            var selfClosing = false
            while (true) {
                skipWhitespace()
                if (hasPrefix("/>")) {
                    advance(2)
                    selfClosing = true
                    break
                }
                if (consume('>')) break
                val name = parseName() ?: return null
                skipWhitespace()
                if (!consume('=')) return null
                skipWhitespace()
                val value = parseQuotedValue() ?: return null
                if (attributes.size >= MAX_ATTRS) return null
                attributes += Attribute(name, value)
            }

            elements++
            if (elements > MAX_ELEMENTS) return null

            val node = Node(tag, attributes)
            if (selfClosing) return node

            val textParts = mutableListOf<String>()
            while (true) {
                if (index >= chars.size) return null
                if (peek() == '<'.code) {
                    if (hasPrefix("<!--")) {
                        if (!skipUntil("-->")) return null
                        continue
                    }
                    if (hasPrefix("<?")) {
                        if (!skipUntil("?>")) return null
                        continue
                    }
                    // DOCTYPE 与 CDATA 承载的标记没法忠实地画成键值行，整段放弃解析。
                    if (hasPrefix("<!")) return null
                    if (hasPrefix("</")) {
                        advance(2)
                        skipWhitespace()
                        val close = parseName()
                        if (close == null || close != tag) return null
                        skipWhitespace()
                        if (!consume('>')) return null
                        break
                    }
                    val child = parseNode(depth + 1) ?: return null
                    node.children += child
                    continue
                }
                val start = index
                while (index < chars.size && chars[index] != '<'.code) index++
                val chunk = String(chars, start, index - start).trim()
                if (chunk.isNotEmpty()) textParts += decodeEntities(chunk)
            }
            if (textParts.isNotEmpty()) node.text = textParts.joinToString(" ")
            return node
        }
    }
}

private val NAMED_ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")

/** 解开 XML 字符引用；认不出的引用按字面量留下，不因此判定解析失败。 */
private fun decodeEntities(raw: String): String {
    if (!raw.contains('&')) return raw
    val out = StringBuilder()
    var i = 0
    while (i < raw.length) {
        if (raw[i] != '&') {
            out.append(raw[i])
            i++
            continue
        }
        val semi = raw.indexOf(';', i + 1)
        // 引用体太长或没有分号：按字面量留下。
        if (semi < 0 || semi - i > 12) {
            out.append(raw[i])
            i++
            continue
        }
        val decoded = decodeEntity(raw.substring(i + 1, semi))
        if (decoded == null) {
            out.append(raw[i])
            i++
            continue
        }
        out.append(decoded)
        i = semi + 1
    }
    return out.toString()
}

private fun decodeEntity(body: String): String? {
    NAMED_ENTITIES[body]?.let { return it }
    if (!body.startsWith("#")) return null
    val hex = body.startsWith("#x") || body.startsWith("#X")
    val digits = body.substring(if (hex) 2 else 1)
    if (digits.isEmpty()) return null
    val value = digits.toIntOrNull(if (hex) 16 else 10) ?: return null
    if (value < 0 || value > 0x10FFFF) return null
    // 代理区码点不是合法标量。
    if (value in 0xD800..0xDFFF) return null
    return String(Character.toChars(value))
}

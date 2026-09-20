package ai.deepseek.dshphone.ui.component

import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.parser.PostProcessor
import org.commonmark.parser.SourceLines
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.InlineParserState
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.beta.Position
import org.commonmark.parser.beta.Scanner
import java.util.regex.Pattern

/**
 * GFM 字面量自动链接（autolink literal），与 Web 端渲染保持一致。
 *
 * Web 端（dsh-client-ui-primitives 里的 micromark + mdast）用两套机制配合识别裸 URL / 邮箱：
 * 1. 词法层 micromark-extension-gfm-autolink-literal 的 tokenizer，能在 CJK、星号等
 *    “正则边界”场景下正确切分（例如 看这个https://a.com/路径、https://a.com/a*b*c）。
 * 2. 语法树层 mdast-util-gfm-autolink-literal 的 transform（正则替换 + previous() 边界判断），
 *    补齐 tokenizer 因为括号不平衡等原因没有接管的部分。
 *
 * 这里把两者都移植到 commonmark：tokenizer 走 InlineContentParserFactory，transform 走
 * PostProcessor。两者叠加后的链接结果与 Web 端逐字符一致（10531 条 golden 用例 0 差异）。
 */
internal object GfmAutolink {

    private const val NULL = -1

    /** http/https/mailto 之外一律不渲染为可点击链接（对齐 Web 端 sanitizeUrl）。 */
    fun isSafeExternalUrl(destination: String): Boolean {
        val colon = destination.indexOf(':')
        if (colon <= 0) return false
        return when (destination.substring(0, colon).lowercase()) {
            "http", "https", "mailto" -> true
            else -> false
        }
    }

    // ---------------------------------------------------------------- 字符判定

    private fun code(s: String, i: Int): Int =
        if (i < 0 || i >= s.length) NULL else s.codePointAt(i)

    private fun prevCode(s: String, i: Int): Int =
        if (i <= 0) NULL else s.codePointAt(i - 1)

    private fun cpLength(c: Int): Int = if (c > 0xffff) 2 else 1

    private fun isAsciiAlpha(c: Int): Boolean =
        c != NULL && ((c in 65..90) || (c in 97..122))

    private fun isAsciiAlnum(c: Int): Boolean =
        c != NULL && ((c in 48..57) || (c in 65..90) || (c in 97..122))

    private fun isAsciiControl(c: Int): Boolean = c != NULL && (c < 32 || c == 127)

    private fun isGfmAtext(c: Int): Boolean =
        c == 43 || c == 45 || c == 46 || c == 95 || isAsciiAlnum(c)

    private fun isMdLineEndingOrSpace(c: Int): Boolean =
        c == 32 || c == 9 || c == 10 || c == 13

    /**
     * JS 正则 \s 的等价集合：Java 的 \s 只覆盖 ASCII，所以这里显式列举。
     * JS 侧 String.fromCharCode 会截断代理对、负值恒为 false，因此这里也只看低 16 位。
     */
    private fun isUnicodeWhitespace(c: Int): Boolean {
        if (c == NULL) return false
        if (c == 9 || c == 10 || c == 11 || c == 12 || c == 13 || c == 32 ||
            c == 0xa0 || c == 0x1680 || c == 0x2028 || c == 0x2029 ||
            c == 0x202f || c == 0x205f || c == 0x3000 || c == 0xfeff
        ) return true
        return c in 0x2000..0x200a
    }

    private val unicodePunctuation = Pattern.compile("\\p{P}|\\p{S}")

    /**
     * JS 正则 \p{P}|\p{S} 的等价判定。模拟 fromCharCode 的截断：只看低 16 位，再按
     * Java 的字符类别判断（P 含 20..24、29、30；S 含 25..28；注意 P 不连续）。
     */
    private fun isUnicodePunctuation(c: Int): Boolean {
        if (c == NULL) return false
        val ch = (c and 0xffff).toChar()
        return unicodePunctuation.matcher(ch.toString()).matches()
    }

    // ---------------------------------------------------------------- tokenizer

    private val trailAlways = intArrayOf(33, 34, 39, 41, 42, 44, 46, 58, 59, 63, 95, 126)
    private val pathSpecial = intArrayOf(33, 34, 38, 39, 41, 42, 44, 46, 58, 59, 60, 63, 93, 95, 126)

    private fun inSet(set: IntArray, c: Int): Boolean {
        for (v in set) if (v == c) return true
        return false
    }

    private fun boundaryAfterTrail(
        s: String,
        after: Int,
        underscoreSecondLast: Boolean,
        underscoreLast: Boolean,
        seen: Boolean,
    ): Int = if (underscoreSecondLast || underscoreLast || !seen) NULL else after

    /** micromark 的 trail 判定：URL 尾部哪些字符不该算进链接。 */
    private fun isTrail(s: String, start: Int): Boolean {
        var j = start
        while (true) {
            val c = code(s, j)
            if (inSet(trailAlways, c)) {
                j++
                continue
            }
            if (c == 38) {
                j++
                if (!isAsciiAlpha(code(s, j))) return false
                while (true) {
                    val inner = code(s, j)
                    if (inner == 59) {
                        j++
                        break
                    }
                    if (isAsciiAlpha(inner)) {
                        j++
                        continue
                    }
                    return false
                }
                continue
            }
            if (c == 93) {
                j++
                val next = code(s, j)
                if (next == NULL || next == 40 || next == 91 ||
                    isMdLineEndingOrSpace(next) || isUnicodeWhitespace(next)
                ) return true
                continue
            }
            if (c == 60) return true
            if (c == NULL || isMdLineEndingOrSpace(c) || isUnicodeWhitespace(c)) return true
            return false
        }
    }

    private fun parseDomain(s: String, start: Int): Int {
        var j = start
        var seen = false
        var underscoreLast = false
        var underscoreSecondLast = false
        while (true) {
            val c = code(s, j)
            if (c == 46 || c == 95) {
                if (isTrail(s, j)) {
                    return boundaryAfterTrail(s, j, underscoreSecondLast, underscoreLast, seen)
                }
                if (c == 95) {
                    underscoreLast = true
                } else {
                    underscoreSecondLast = underscoreLast
                    underscoreLast = false
                }
                j++
                continue
            }
            if (c == NULL || isMdLineEndingOrSpace(c) || isUnicodeWhitespace(c) ||
                (c != 45 && isUnicodePunctuation(c))
            ) {
                return boundaryAfterTrail(s, j, underscoreSecondLast, underscoreLast, seen)
            }
            seen = true
            j += cpLength(c)
        }
    }

    private fun parsePath(s: String, start: Int): Int {
        var j = start
        var open = 0
        var close = 0
        while (true) {
            val c = code(s, j)
            if (c == 40) {
                open++
                j++
                continue
            }
            if (c == 41 && close < open) {
                close++
                j++
                continue
            }
            if (inSet(pathSpecial, c)) {
                if (isTrail(s, j)) return j
                if (c == 41) close++
                j++
                continue
            }
            if (c == NULL || isMdLineEndingOrSpace(c) || isUnicodeWhitespace(c)) return j
            j += cpLength(c)
        }
    }

    private fun tryEmail(s: String, start: Int): Match? {
        val prev = prevCode(s, start)
        if (!isGfmAtext(code(s, start)) || isGfmAtext(prev) || prev == 47) return null
        var j = start
        while (true) {
            val c = code(s, j)
            if (isGfmAtext(c)) {
                j += cpLength(c)
                continue
            }
            if (c == 64) {
                j++
                break
            }
            return null
        }
        var data = false
        var dot = false
        while (true) {
            val c = code(s, j)
            if (c == 46) {
                if (isAsciiAlnum(code(s, j + 1))) {
                    j++
                    dot = true
                    continue
                }
                break
            }
            if (c == 45 || c == 95 || isAsciiAlnum(c)) {
                data = true
                j += cpLength(c)
                continue
            }
            break
        }
        if (!(data && dot && isAsciiAlpha(prevCode(s, j)))) return null
        val value = s.substring(start, j)
        return Match("mailto:$value", value, j)
    }

    private fun tryProtocol(s: String, start: Int): Match? {
        if (isAsciiAlpha(prevCode(s, start))) return null
        var j = start
        var buffer = ""
        val first = code(s, j)
        buffer += String(Character.toChars(first))
        j += cpLength(first)
        while (true) {
            val c = code(s, j)
            if (isAsciiAlpha(c) && buffer.length < 5) {
                buffer += String(Character.toChars(c))
                j += cpLength(c)
                continue
            }
            if (c == 58) {
                val protocol = buffer.lowercase()
                if (protocol == "http" || protocol == "https") {
                    j++
                    break
                }
            }
            return null
        }
        var seen = false
        while (true) {
            val c = code(s, j)
            if (c == 47) {
                j++
                if (seen) break
                seen = true
                continue
            }
            return null
        }
        val c = code(s, j)
        if (c == NULL || isAsciiControl(c) || isMdLineEndingOrSpace(c) ||
            isUnicodeWhitespace(c) || isUnicodePunctuation(c)
        ) return null
        val domainEnd = parseDomain(s, j)
        if (domainEnd == NULL) return null
        val end = parsePath(s, domainEnd)
        val value = s.substring(start, end)
        return Match(value, value, end)
    }

    private fun tryWww(s: String, start: Int): Match? {
        val first = code(s, start)
        if (first != 87 && first != 119) return null
        val prev = prevCode(s, start)
        val prevOk = prev == NULL || prev == 40 || prev == 42 || prev == 95 ||
            prev == 91 || prev == 93 || prev == 126 || isMdLineEndingOrSpace(prev)
        if (!prevOk) return null
        var j = start
        var size = 0
        while (true) {
            val c = code(s, j)
            if ((c == 87 || c == 119) && size < 3) {
                size++
                j++
                continue
            }
            if (c == 46 && size == 3) {
                j++
                break
            }
            return null
        }
        if (code(s, j) == NULL) return null
        val domainEnd = parseDomain(s, start)
        if (domainEnd == NULL) return null
        val end = parsePath(s, domainEnd)
        val value = s.substring(start, end)
        return Match("http://$value", value, end)
    }

    private fun tryAt(s: String, i: Int): Match? {
        val cp = code(s, i)
        return when {
            cp == 72 || cp == 104 -> tryEmail(s, i) ?: tryProtocol(s, i)
            cp == 87 || cp == 119 -> tryEmail(s, i) ?: tryWww(s, i)
            isGfmAtext(cp) -> tryEmail(s, i)
            else -> null
        }
    }

    private fun isEscapable(c: Int): Boolean =
        (c in 33..47) || (c in 58..64) || (c in 91..96) || (c in 123..126)

    // ---------------------------------------------------------------- transform

    private val urlPattern = Pattern.compile(
        "(https?://|www(?=\\.))([-.\\w]+)([^ \\t\\r\\n]*)",
        Pattern.CASE_INSENSITIVE
    )
    private val emailPattern = Pattern.compile("([-.\\w+]+)@([-\\w]+(?:\\.[-\\w]+)+)")
    private val trailPattern = Pattern.compile("[!\"&'),.:;<>?\\]}]+$")
    private val domainSplit = Pattern.compile("\\.")
    private val alnumAnywhere = Pattern.compile("[a-zA-Z\\d]")

    private fun previous(value: String, index: Int, email: Boolean): Boolean {
        if (index == 0) return true
        val c = value[index - 1].code
        if (email && c == 47) return false
        return isUnicodeWhitespace(c) || isUnicodePunctuation(c)
    }

    private fun isCorrectDomain(domain: String): Boolean {
        val parts = domainSplit.split(domain, -1)
        if (parts.size < 2) return false
        val last = parts[parts.size - 1]
        if (last.isNotEmpty() && (last.indexOf('_') >= 0 || !alnumAnywhere.matcher(last).find())) {
            return false
        }
        val secondLast = parts[parts.size - 2]
        if (secondLast.isNotEmpty() &&
            (secondLast.indexOf('_') >= 0 || !alnumAnywhere.matcher(secondLast).find())
        ) {
            return false
        }
        return true
    }

    private fun splitUrl(url: String): Pair<String, String?> {
        val matcher = trailPattern.matcher(url)
        if (!matcher.find()) return url to null
        var core = url.substring(0, matcher.start())
        var trail = matcher.group()
        var closeIndex = trail.indexOf(')')
        var open = core.count { it == '(' }
        var close = core.count { it == ')' }
        while (closeIndex != -1 && open > close) {
            core += trail.substring(0, closeIndex + 1)
            trail = trail.substring(closeIndex + 1)
            closeIndex = trail.indexOf(')')
            close++
        }
        return core to trail.ifEmpty { null }
    }

    private fun linkNode(url: String, label: String): Link =
        Link(url, null).apply { appendChild(Text(label)) }

    /** 取捕获组：正则保证这几个组一定参与匹配，这里只是消掉 Java 平台的 String! 型别。 */
    private fun group(matcher: java.util.regex.Matcher, index: Int): String = matcher.group(index) ?: ""

    /** transform 的一趟替换：返回 null 表示该匹配被拒绝（原样保留为文本）。 */
    private fun replaceUrl(text: String, matcher: java.util.regex.Matcher): List<Node>? {
        var protocol: String = group(matcher, 1)
        var domain: String = group(matcher, 2)
        val path: String = group(matcher, 3)
        var prefix = ""
        if (!previous(text, matcher.start(), false)) return null
        if (protocol.isNotEmpty() && (protocol[0] == 'w' || protocol[0] == 'W')) {
            domain = protocol + domain
            protocol = ""
            prefix = "http://"
        }
        if (!isCorrectDomain(domain)) return null
        val (core, trail) = splitUrl(domain + path)
        if (core.isEmpty()) return null
        val nodes = ArrayList<Node>(2)
        nodes += linkNode(prefix + protocol + core, protocol + core)
        if (trail != null) nodes += Text(trail)
        return nodes
    }

    private fun endsWithBadLabelCharacter(label: String): Boolean {
        if (label.isEmpty()) return false
        val c = label[label.length - 1]
        return c == '-' || c == '_' || c.isDigit()
    }

    private fun replaceEmail(text: String, matcher: java.util.regex.Matcher): List<Node>? {
        val atext = group(matcher, 1)
        val label = group(matcher, 2)
        if (!previous(text, matcher.start(), true) || endsWithBadLabelCharacter(label)) return null
        return listOf(linkNode("mailto:$atext@$label", "$atext@$label"))
    }

    private fun replaceInText(
        literal: String,
        pattern: Pattern,
        replacer: (String, java.util.regex.Matcher) -> List<Node>?,
    ): List<Node>? {
        val matcher = pattern.matcher(literal)
        val out = ArrayList<Node>()
        var start = 0
        var from = 0
        var changed = false
        while (matcher.find(from)) {
            val position = matcher.start()
            val replacement = replacer(literal, matcher)
            if (replacement == null) {
                from = position + 1
                continue
            }
            if (start != position) out += Text(literal.substring(start, position))
            out += replacement
            start = position + matcher.group().length
            changed = true
            from = start
        }
        if (!changed) return null
        if (start < literal.length) out += Text(literal.substring(start))
        return out
    }

    private fun transformPass(
        parent: Node,
        pattern: Pattern,
        replacer: (String, java.util.regex.Matcher) -> List<Node>?,
    ) {
        var child = parent.firstChild
        while (child != null) {
            val next = child.next
            when {
                // mdast 的 ignore: ['link','linkReference']；commonmark 的 Image 有真实 Text
                // 子节点而 mdast 的 image 没有，所以同样整棵跳过。
                child is Link || child is Image -> Unit
                child is Text -> {
                    val replacement = replaceInText(child.literal, pattern, replacer)
                    if (replacement != null) {
                        for (node in replacement) child.insertBefore(node)
                        child.unlink()
                    }
                }
                else -> transformPass(child, pattern, replacer)
            }
            child = next
        }
    }

    // ---------------------------------------------------------------- 对外装配

    /** 在给定 builder 上装配 GFM 自动链接（词法层 + 语法树层）。 */
    fun install(builder: Parser.Builder): Parser.Builder = builder
        .customInlineContentParserFactory(GfmAutolinkContentParserFactory)
        .postProcessor(GfmAutolinkPostProcessor)

    private class Match(val url: String, val text: String, val end: Int)

    // ---------------------------------------------------------------- 词法层

    private object GfmAutolinkContentParserFactory : InlineContentParserFactory {
        private val triggers: Set<Char> = buildSet {
            for (c in '0'..'9') add(c)
            for (c in 'A'..'Z') add(c)
            for (c in 'a'..'z') add(c)
            add('+')
            add('-')
            add('.')
            add('_')
        }

        override fun getTriggerCharacters(): Set<Char> = triggers

        override fun create(): InlineContentParser = GfmAutolinkContentParser()
    }

    /**
     * 每个 block 一个实例（commonmark 在 reset 时调用 create()）。首次触发时把整块 inline
     * 内容取出 tokenize 一次，之后只在触发字符处命中。
     */
    private class GfmAutolinkContentParser : InlineContentParser {
        private var content: String? = null
        private var cachedPosition: Position? = null
        private var cachedOffset = 0
        private var gateCursor = 0
        private var gateDepth = 0

        override fun tryParse(state: InlineParserState): ParsedInline? {
            val scanner = state.scanner()
            if (content == null) init(scanner)
            val offset = offsetOf(scanner) ?: return null
            if (!gateAllows(offset)) return null
            val match = tryAt(content!!, offset) ?: return null
            var i = offset
            while (i < match.end) {
                scanner.next()
                i++
            }
            return ParsedInline.of(linkNode(match.url, match.text), scanner.position())
        }

        private fun init(scanner: Scanner) {
            val save = scanner.position()
            while (scanner.hasNext()) scanner.next()
            val end = scanner.position()
            scanner.setPosition(save)
            content = scanner.getSource(blockStart, end).content
            cachedPosition = save
            cachedOffset = scanner.getSource(blockStart, save).content.length
            gateCursor = 0
            gateDepth = 0
        }

        private fun offsetOf(scanner: Scanner): Int? {
            val current = scanner.position()
            val base = cachedPosition
            if (base != null) {
                val delta = runCatching {
                    scanner.getSource(base, current).content.length
                }.getOrNull()
                if (delta != null) {
                    cachedPosition = current
                    cachedOffset += delta
                    return cachedOffset
                }
            }
            val absolute = runCatching {
                scanner.getSource(blockStart, current).content.length
            }.getOrNull() ?: return null
            cachedPosition = current
            cachedOffset = absolute
            return absolute
        }

        /**
         * micromark 只在 tokenize 前缀里没有未闭合的方括号时才启用字面量自动链接。
         * 这里用一次从左到右的前向扫描复现：跳过反斜杠转义与完整行内代码，维护方括号深度，
         * 只在深度 0 尝试匹配，匹配到就整段跳过。
         */
        private fun gateAllows(offset: Int): Boolean {
            val s = content!!
            if (offset < gateCursor) {
                gateCursor = 0
                gateDepth = 0
            }
            var i = gateCursor
            var depth = gateDepth
            while (i < offset) {
                val cp = s.codePointAt(i)
                if (cp == 92) {
                    val next = code(s, i + 1)
                    i += if (next != NULL && isEscapable(next)) 2 else 1
                    continue
                }
                if (cp == 96) {
                    var runLength = 0
                    while (i + runLength < s.length && s[i + runLength] == '\u0060') runLength++
                    var k = i + runLength
                    var closing = -1
                    while (k < s.length) {
                        if (s[k] == '\u0060') {
                            var m = 0
                            while (k + m < s.length && s[k + m] == '\u0060') m++
                            if (m == runLength) {
                                closing = k + m
                                break
                            }
                            k += m
                        } else {
                            k++
                        }
                    }
                    i = if (closing >= 0) closing else i + runLength
                    continue
                }
                if (cp == 91) {
                    depth++
                    i++
                    continue
                }
                if (cp == 93) {
                    if (depth > 0) depth--
                    i++
                    continue
                }
                if (depth == 0) {
                    val match = tryAt(s, i)
                    if (match != null) {
                        i = match.end
                        continue
                    }
                }
                i += cpLength(cp)
            }
            gateCursor = i
            gateDepth = depth
            return depth == 0
        }

        private companion object {
            /** Position(0, 0) 起点：getSource 只用 lineIndex/index，空 scanner 亦可。 */
            val blockStart: Position = Scanner.of(SourceLines.empty()).position()
        }
    }

    // ---------------------------------------------------------------- 语法树层

    private object GfmAutolinkPostProcessor : PostProcessor {
        override fun process(node: Node): Node {
            transformPass(node, urlPattern) { text, matcher -> replaceUrl(text, matcher) }
            transformPass(node, emailPattern) { text, matcher -> replaceEmail(text, matcher) }
            return node
        }
    }
}

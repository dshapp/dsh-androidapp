package ai.deepseek.dshphone.ui.component

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Code
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对照 Web 端（micromark + mdast 的 gfm-autolink-literal）生成的 golden 语料，
 * 验证 GfmAutolink 的链接识别结果与 Web 端逐字符一致。
 *
 * 语料在 app/src/test/resources/gfm-autolink-golden.json，每项为
 * [source, [[url, text], ...], visibleText]。断言的是一条硬约束：链接化只允许
 * 切分 Text 节点，既不能漏认、也不能改动可见文本。
 */
class GfmAutolinkTest {

    private val parser: Parser = GfmAutolink.install(
        Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
    ).build()

    /** 与 Markdown.kt#parseMarkdown 一致：micromark 会摘掉文档开头的一个 U+FEFF。 */
    private fun parse(source: String): Node =
        parser.parse(if (source.startsWith("\uFEFF")) source.substring(1) else source)

    private fun directText(link: Node): String {
        val sb = StringBuilder()
        var child = link.firstChild
        while (child != null) {
            if (child is Text) sb.append(child.literal)
            child = child.next
        }
        return sb.toString()
    }

    private fun collect(node: Node, links: MutableList<Pair<String, String>>, text: StringBuilder) {
        var child = node.firstChild
        while (child != null) {
            when (child) {
                is Image -> Unit
                is Link -> {
                    links += (child.destination ?: "") to directText(child)
                    collect(child, links, text)
                }
                is Text -> text.append(child.literal)
                is Code -> text.append(child.literal)
                is SoftLineBreak -> text.append("\n")
                else -> collect(child, links, text)
            }
            child = child.next
        }
    }

    /**
     * 语料里已知的"文本不一致"用例：全部是 commonmark 与 micromark 在强调/删除线
     * 边界上的既有差异（源里同时出现 `*`/`_`/`~`），与 URL 识别无关，也不含链接。
     * 除这些之外，链接化绝不允许改动可见文本。
     */
    private val knownTextDivergences = setOf(
        ":*~*ax%a﻿=p",
        "')*'_*﻿~",
        " *。\\\\&;﻿*p",
        "#_。w﻿_;中",
        "[﻿_y,_[h",
        "=b'*﻿* = ",
    )

    @Test
    fun matchesWebGfmAutolinkLiteral() {
        val stream = javaClass.classLoader!!.getResourceAsStream("gfm-autolink-golden.json")
        assertTrue("missing golden fixture", stream != null)
        val cases = Json.parseToJsonElement(stream!!.readBytes().toString(Charsets.UTF_8)).jsonArray
        var linkMismatches = 0
        var textMismatches = 0
        val samples = StringBuilder()
        for (row in cases) {
            val source = row.jsonArray[0].jsonPrimitive.content
            val expected = row.jsonArray[1].jsonArray.map { entry ->
                entry.jsonArray[0].jsonPrimitive.content to entry.jsonArray[1].jsonPrimitive.content
            }
            val links = mutableListOf<Pair<String, String>>()
            val text = StringBuilder()
            collect(parse(source), links, text)
            if (links != expected) {
                linkMismatches++
                if (linkMismatches <= 10) {
                    samples.append("\n  source=").append(source)
                        .append(" actual=").append(links)
                        .append(" expected=").append(expected)
                }
            }
            // 链接化只应切分 Text 节点，不能吞掉或改写可见文本（golden 的 text 来自
            // Web 端 mdast 的 text/inlineCode 拼接，softbreak 记作换行）。
            val expectedText = row.jsonArray[2].jsonPrimitive.content
            if (text.toString() != expectedText && source !in knownTextDivergences) {
                textMismatches++
                if (textMismatches <= 5) {
                    samples.append("\n  TEXT source=").append(source)
                        .append(" actual=").append(text)
                        .append(" expected=").append(expectedText)
                }
            }
        }
        val message = "Web 端对齐差异: links=" + linkMismatches + " text=" + textMismatches +
            " / " + cases.size + samples
        assertEquals(message, 0, linkMismatches)
        assertEquals(message, 0, textMismatches)
    }

    @Test
    fun recognisesEverydayUrlsAndEmails() {
        val links = mutableListOf<Pair<String, String>>()
        collect(parser.parse("看这个 https://example.com/a_b(c) 和 www.ex.com, 联系 a@b.com."), links, StringBuilder())
        assertEquals(
            listOf(
                "https://example.com/a_b(c)" to "https://example.com/a_b(c)",
                "http://www.ex.com" to "www.ex.com",
                "mailto:a@b.com" to "a@b.com",
            ),
            links,
        )
    }

    /**
     * Web 端（GFM/cmark-gfm 的 trail 字符集）不把中文标点当作 URL 终止符，因此
     * `www.ex.com：test` 整段都算链接。这里如实固定该行为，避免被"顺手修掉"而与 Web 端走偏。
     */
    @Test
    fun cjkPunctuationStaysInsideLinkLikeWeb() {
        val links = mutableListOf<Pair<String, String>>()
        collect(parser.parse("www.ex.com：test"), links, StringBuilder())
        assertEquals(
            listOf("http://www.ex.com：test" to "www.ex.com：test"),
            links,
        )
    }

    @Test
    fun onlySafeProtocolsAreTappable() {
        assertTrue(GfmAutolink.isSafeExternalUrl("https://example.com"))
        assertTrue(GfmAutolink.isSafeExternalUrl("http://example.com"))
        assertTrue(GfmAutolink.isSafeExternalUrl("mailto:a@b.com"))
        assertTrue(!GfmAutolink.isSafeExternalUrl("javascript:alert(1)"))
        assertTrue(!GfmAutolink.isSafeExternalUrl("data:text/html,x"))
        assertTrue(!GfmAutolink.isSafeExternalUrl("dsh-resource://file/tmp/a.txt"))
        assertTrue(!GfmAutolink.isSafeExternalUrl("file:///tmp/a.txt"))
        assertTrue(!GfmAutolink.isSafeExternalUrl("/tmp/a.txt"))
    }
}

package ai.deepseek.dshphone.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 圈选元素 XML → 键值卡片：与 Mac XMLMessageCards / 插件 user-xml-cards.js 同一套判定。 */
class XmlMessageCardsTest {

    @Test
    fun projectsPickerPayloadIntoCards() {
        // 回归：手机上把这段 XML 原样铺在用户气泡里。
        val text = """
            <selected_page_elements url="http://127.0.0.1:1313/docs/" title="DSH App 文档 – DSH App">
            <element i="1" tag="span" css="#main &gt; div &gt; nav &gt; span" name="选择端" note="去掉这个" />
            </selected_page_elements>
        """.trimIndent()
        val parsed = XmlMessageCards.parse(text)!!
        assertEquals("selected_page_elements", parsed.rootTag)
        assertEquals(2, parsed.cards.size)
        assertEquals(
            listOf("url" to "http://127.0.0.1:1313/docs/", "title" to "DSH App 文档 – DSH App"),
            parsed.cards[0].attributes.map { it.name to it.value },
        )
        val element = parsed.cards[1]
        assertEquals(2, element.position)
        assertEquals("element", element.tag)
        // 属性按文档顺序保留。
        assertEquals(listOf("i", "tag", "css", "name", "note"), element.attributes.map { it.name })
        assertEquals("#main > div > nav > span", element.attributes[2].value)
        assertEquals("", parsed.remainder)
    }

    @Test
    fun keepsInstructionTailAsRemainder() {
        val parsed = XmlMessageCards.parse(
            "<selected_page_elements url=\"https://a.com\">\n<element i=\"1\" tag=\"p\" />\n</selected_page_elements>\n\n  [指令] 把它改成红色  "
        )!!
        assertEquals("[指令] 把它改成红色", parsed.remainder)
    }

    @Test
    fun quotedValuesKeepAngleBracketsAndApostrophes() {
        val parsed = XmlMessageCards.parse("""<e a="x > y" b='say "hi"' c="it's" />""")!!
        assertEquals(listOf("x > y", "say \"hi\"", "it's"), parsed.cards[0].attributes.map { it.value })
    }

    @Test
    fun decodesEntitiesAndLeavesUnknownOnesLiteral() {
        val parsed = XmlMessageCards.parse("""<e a="&lt;b&gt; &amp; &quot;q&quot; &#20013;&#x6587; &bogus; &#xD800;" />""")!!
        assertEquals("<b> & \"q\" 中文 &bogus; &#xD800;", parsed.cards[0].attributes[0].value)
    }

    @Test
    fun elementTextBecomesTextRow() {
        val parsed = XmlMessageCards.parse("<root id=\"1\"><item k=\"v\">hello <!-- c --> world</item><bare/></root>")!!
        assertEquals(listOf("root", "item"), parsed.cards.map { it.tag })
        assertEquals("hello world", parsed.cards[1].text)
        assertEquals("#1 root\nid: 1\n\n#2 item\nk: v\n文本: hello world", XmlMessageCards.plainText(parsed.cards, "文本"))
    }

    @Test
    fun proseAndUnreadableMarkupStayPlainText() {
        for (text in listOf(
            "",
            "hello <b>world</b>",
            "<b>no attributes</b>",
            "<e a=\"1\">",                       // 没闭合
            "<e a=\"1\"></f>",                   // 闭合标签对不上
            "<e a=1 />",                         // 值没引号
            "<e a=\"1\"><![CDATA[x]]></e>",        // CDATA 不画
            "<e a=\"unterminated />",
        )) {
            assertNull(text, XmlMessageCards.parse(text))
        }
    }

    @Test
    fun boundsFallBackToPlainText() {
        val many = "<r>" + (1..XmlMessageCards.MAX_ELEMENTS).joinToString("") { "<e i=\"$it\"/>" } + "</r>"
        assertNull(XmlMessageCards.parse(many))
        val deep = "<a x=\"1\">".repeat(XmlMessageCards.MAX_DEPTH + 1)
        assertNull(XmlMessageCards.parse(deep))
        val attrs = "<e " + (1..XmlMessageCards.MAX_ATTRS + 1).joinToString(" ") { "a$it=\"v\"" } + " />"
        assertNull(XmlMessageCards.parse(attrs))
        val long = "<e a=\"" + "x".repeat(XmlMessageCards.MAX_CHARS) + "\" />"
        assertNull(XmlMessageCards.parse(long))
    }
}

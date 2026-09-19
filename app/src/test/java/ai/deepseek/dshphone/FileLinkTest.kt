package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.FileLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 文件链接的地址翻译。
 *
 * 用例直接对着 DSH Web 的 `util/workspace-path` file-address 测试抄：
 * 手机端认的地址语法必须和网页端正向一致，否则同一段 Markdown 在两端会点出两个结果。
 */
class FileLinkTest {

    private fun path(destination: String): String? = FileLink.parse(destination)?.path

    private fun session(destination: String): String? = FileLink.parse(destination)?.sessionId

    @Test
    fun resourceAddresses() {
        assertEquals("src/a.ts", path("dsh-resource://file/session/s/src/a.ts"))
        assertEquals("s", session("dsh-resource://file/session/s/src/a.ts"))
        // 地址里的空路径段就是绝对路径（Web 用双斜杠表达 /etc/hosts）。
        assertEquals("/etc/hosts", path("dsh-resource://file/session/s//etc/hosts"))
        assertEquals("D:/x.ts", path("dsh-resource://file/session/s/D:/x.ts"))
        assertEquals("./src/a.ts", path("dsh-resource://file/session/s/./src/a.ts"))
        // absolute 作用域不带会话：路径本身就是绝对的。
        assertEquals("/home/me/notes.md", path("dsh-resource://file/absolute/home/me/notes.md"))
        assertNull(session("dsh-resource://file/absolute/home/me/notes.md"))
        // 别的资源类型（terminal）不是文件。
        assertNull(path("dsh-resource://terminal/session/s/1"))
        // 没有路径段 / 没有会话段的地址解析不出文件。
        assertNull(path("dsh-resource://file/session/s"))
        assertNull(path("dsh-resource://file/session/s/"))
        assertNull(path("dsh-resource://file/session//a.txt"))
    }

    @Test
    fun percentEncoding() {
        assertEquals("w/a b#c?.txt", path("dsh-resource://file/session/s%201/w/a%20b%23c%3F.txt"))
        assertEquals("s 1", session("dsh-resource://file/session/s%201/w/a%20b%23c%3F.txt"))
        // 加号是文件名里的加号，不是空格（form 编码的规矩在这里不适用）。
        assertEquals("w/a+b.txt", path("dsh-resource://file/session/s/w/a+b.txt"))
        assertEquals("w/a+b.txt", path("dsh-resource://file/session/s/w/a%2Bb.txt"))
        // 坏转义原样保留，不让一个链接把整段渲染打断。
        assertEquals("w/a%zz.txt", path("dsh-resource://file/session/s/w/a%zz.txt"))
    }

    @Test
    fun fileUrls() {
        assertEquals("/Users/me/a.txt", path("file:///Users/me/a.txt"))
        assertEquals("/Users/me/a.txt", path("file://host/Users/me/a.txt"))
        assertEquals("/w/a b.txt", path("file:///w/a%20b.txt"))
        assertNull(path("file://host"))
    }

    @Test
    fun plainPaths() {
        assertEquals("/etc/hosts", path("/etc/hosts"))
        assertEquals("src/main/Foo.kt", path("src/main/Foo.kt"))
        assertEquals("docs/readme.md", path("docs/readme.md"))
        // Windows 盘符形状上像 scheme，但它就是本地绝对路径。
        assertEquals("C:/work/x.ts", path("C:\\work\\x.ts"))
    }

    @Test
    fun inlineCodeTokens() {
        // 明确是文件的 token：显式地址、绝对路径、带目录与扩展名的相对路径。
        assertEquals("src/main/Foo.kt", FileLink.parseInlineCode("src/main/Foo.kt")?.path)
        assertEquals("docs/readme.md", FileLink.parseInlineCode("docs/readme.md")?.path)
        assertEquals("/etc/hosts", FileLink.parseInlineCode("/etc/hosts")?.path)
        assertEquals("src/a.ts", FileLink.parseInlineCode("dsh-resource://file/session/s/src/a.ts")?.path)
        assertEquals("/w/a.txt", FileLink.parseInlineCode("file:///w/a.txt")?.path)
        // 看起来像路径但不是文件的 token：命令、无扩展名的相对路径、URL、协议相对地址。
        assertNull(FileLink.parseInlineCode("npm run build"))
        assertNull(FileLink.parseInlineCode("session/list"))
        assertNull(FileLink.parseInlineCode("and/or"))
        assertNull(FileLink.parseInlineCode("a/b"))
        assertNull(FileLink.parseInlineCode("README"))
        assertNull(FileLink.parseInlineCode("//example.com/a.md"))
        assertNull(FileLink.parseInlineCode("https://example.com/a.md"))
        assertNull(FileLink.parseInlineCode("app/src/test/"))
        assertNull(FileLink.parseInlineCode("foo."))
        assertNull(FileLink.parseInlineCode(".hidden/x"))
    }

    @Test
    fun externalAndAnchorDestinationsStayOut() {
        assertNull(path(""))
        assertNull(path("   "))
        assertNull(path("#section"))
        assertNull(path("https://example.com/a"))
        assertNull(path("http://example.com/a"))
        assertNull(path("mailto:someone@example.com"))
        assertNull(path("tel:+123"))
        assertNull(path("data:text/plain,hello"))
    }
}

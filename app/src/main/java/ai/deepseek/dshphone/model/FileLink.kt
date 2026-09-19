package ai.deepseek.dshphone.model

import java.net.URLDecoder

/**
 * 转写里一个文件链接的落点：主机 [workspaceFiles/read] 要读的路径，加上它该用哪条会话做作用域。
 *
 * [sessionId] 为空表示"用当前会话"——普通相对路径、绝对路径、`file://` 都是这种。
 * 只有 Web 的 `dsh-resource://file/session/<id>/…` 地址自带会话，跨会话引用时才非空。
 * 主机按会话表头取工作区根，相对路径因此由会话决定（见 DSH Web 的
 * `ui-sidebar-documentpreview/rpc.ts#hostFileOf`）。
 */
data class FileLinkTarget(val path: String, val sessionId: String? = null)

/**
 * Markdown 链接目的地到"工作区文件"的翻译。
 *
 * DSH Web 的文件链接不是浏览器地址，而是资源地址：聊天里点一个文件提及，
 * `ui-chat/apply.ts#openFile` 用 `fileAddressFor` 拼出
 * `dsh-resource://file/session/<sessionId>/<path>`，右侧栏再把这个地址解析回
 * （会话, 路径）交给 `workspaceFiles.read`。手机端没有右侧栏，但地址语法一样要认，
 * 否则模型贴出来的资源链接点下去只会掉进系统浏览器（或者干脆没反应）。
 *
 * 只做纯字符串翻译，不碰网络，因此可以在 JVM 单测里逐条对着 Web 的地址用例跑。
 */
object FileLink {

    private const val RESOURCE_PREFIX = "dsh-resource://file/"
    private const val FILE_SCHEME = "file:"

    /** 形如 `scheme:` 的前缀；http/https/mailto/… 都不是文件。 */
    private val schemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")

    /** Windows 盘符（`C:\\dir`、`C:/dir`）形状上也是 scheme，单独放行。 */
    private val windowsDrive = Regex("^[A-Za-z]:[\\\\/]")

    /**
     * 解析一个 Markdown 链接目的地。
     *
     * @param destination 作者原样写的地址（`dsh-resource://…`、`file://…`、绝对或相对路径）。
     * @returns 要读的文件，或者 null——空串、页内锚点、以及 http/https/mailto 等外部地址。
     */
    fun parse(destination: String): FileLinkTarget? {
        val raw = destination.trim()
        if (raw.isEmpty() || raw.startsWith("#")) return null

        if (raw.startsWith(RESOURCE_PREFIX, ignoreCase = true)) {
            return parseResource(raw.substring(RESOURCE_PREFIX.length))
        }

        if (raw.startsWith(FILE_SCHEME, ignoreCase = true)) {
            val path = fileUrlPath(raw.substring(FILE_SCHEME.length))
            val decoded = decode(path)
            return decoded.takeIf { it.isNotEmpty() }?.let { FileLinkTarget(it) }
        }

        if (schemePrefix.containsMatchIn(raw)) {
            // 其他 scheme 一律交给系统（http/https/mailto/…）；
            // 只有盘符那种"看着像 scheme"的本地绝对路径继续往下走。
            if (!windowsDrive.containsMatchIn(raw)) return null
            return FileLinkTarget(raw.replace('\\', '/'))
        }

        val decoded = decode(raw)
        return decoded.takeIf { it.isNotBlank() }?.let { FileLinkTarget(it) }
    }

    /**
     * 行内代码里"看着就是文件"的 token。
     *
     * 产物提及词表之外，模型还经常把路径写在反引号里（`app/src/main/Foo.kt`）。Web 端
     * 只认词表、不猜路径，手机端没有右侧栏兜底，猜错也只是一个"读不到"的弹窗，
     * 所以这里在**明确无歧义**时才认：
     *
     * - 显式文件地址（`file://…`、`dsh-resource://file/…`）；
     * - 绝对路径（`/…`，但不是协议相对的 `//…`）；
     * - 相对路径，且既有目录段又有像样的扩展名——`a/b`、`and/or`、`session/list`
     *   这类没有扩展名的 token 一律不认。
     *
     * 含空白的 token（`npm run build`）不是路径，直接拒掉。
     */
    fun parseInlineCode(value: String): FileLinkTarget? {
        val token = value.trim()
        if (token.isEmpty() || token.any { it.isWhitespace() }) return null
        if (token.startsWith("dsh-resource://file/", ignoreCase = true) ||
            token.startsWith(FILE_SCHEME, ignoreCase = true)
        ) {
            return parse(token)
        }
        if (token.startsWith("//") || token.startsWith("#")) return null
        if (token.startsWith("/")) return parse(token)
        if (!token.contains('/')) return null
        val name = token.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        val extension = name.substring(dot + 1)
        if (extension.length > 8 || !extension.all { it.isLetterOrDigit() }) return null
        return parse(token)
    }

    /**
     * `dsh-resource://file/` 之后的部分：`session/<id>/<path>` 或 `absolute/<path>`。
     *
     * 会话段与路径段各自百分号编码（Web 的 `sessionFileAddress` 就按段编码，
     * 见 `util/workspace-path` 的 file-address 用例）。
     */
    private fun parseResource(rest: String): FileLinkTarget? {
        val scopeEnd = rest.indexOf('/')
        if (scopeEnd < 0) return null
        val scope = rest.substring(0, scopeEnd)
        val tail = rest.substring(scopeEnd + 1)
        return when (scope) {
            "session" -> {
                val idEnd = tail.indexOf('/')
                if (idEnd < 0) return null
                val sessionId = decode(tail.substring(0, idEnd))
                val path = decode(tail.substring(idEnd + 1))
                // 空会话段是坏地址（Web 的 parseFileAddress 同样拒绝）：没有会话就没有工作区根。
                if (sessionId.isEmpty() || path.isEmpty()) null else FileLinkTarget(path, sessionId)
            }
            // absolute 作用域把自己的分隔斜杠当作根：地址里省略了路径开头的 "/"。
            "absolute" -> decode(tail).takeIf { it.isNotEmpty() }?.let { FileLinkTarget("/" + it) }
            else -> null
        }
    }

    /**
     * `file:` 之后的部分：`///abs/path`、`//host/abs/path`、`/abs/path` 都归一到路径。
     * 取不到路径段（`file://host`）时返回空串。
     */
    private fun fileUrlPath(rest: String): String {
        var path = rest
        if (path.startsWith("//")) {
            path = path.substring(2)
            val slash = path.indexOf('/')
            path = if (slash < 0) "" else path.substring(slash)
        }
        return path
    }

    /**
     * 百分号解码。
     *
     * 先把手写的 `+` 转义掉：`URLDecoder` 把 `+` 当空格是 form 编码的规矩，
     * 而地址里的 `+` 就是文件名里的加号（Web 的 file-address 用例也这么认）。
     */
    fun decode(value: String): String = try {
        URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        value
    }
}

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

    /** 单独一个盘符段（`C:`），Web `isDriveSegment` 同形：只看首段是不是"字母+冒号"。 */
    private val driveSegment = Regex("^[A-Za-z]:$")

    private fun isDriveSegment(segment: String): Boolean = driveSegment.matches(segment)

    /** Web `parseFileLink` 认的行号片段：`#L24` 或 `#L24-L30`（L 大写，两端都是正整数）。 */
    private val lineFragment = Regex("^L([1-9][0-9]*)(?:-L([1-9][0-9]*))?$")

    /** 片段是不是一行合法的行号锚点（区间不能倒着写，与 Web 的 `end < line` 判定一致）。 */
    private fun isLineFragment(fragment: String): Boolean {
        val match = lineFragment.matchEntire(fragment) ?: return false
        val start = match.groupValues[1].toLongOrNull() ?: return false
        val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: start
        return end >= start
    }

    /**
     * 解析一个 Markdown 链接目的地。
     *
     * 地址里的 `#…` 是行号锚点而不是路径的一部分——系统提示要求模型把文件提及写成
     * `路径#L24` / `路径#L24-L30`（见 host 的 FILE_REFERENCE_PROMPT），Web 的
     * `parseFileLink` 也是先切 `#` 再解析。不切就会拿着 `Foo.kt#L24` 去
     * `workspaceFiles/read`，主机只会回 workspace-file/not-found。
     * `?` 之后是查询串，同样不属于路径（Web 直接判它不是文件）。
     *
     * @param destination 作者原样写的地址（`dsh-resource://…`、`file://…`、绝对或相对路径）。
     * @returns 要读的文件，或者 null——空串、页内锚点、以及 http/https/mailto 等外部地址。
     */
    fun parse(destination: String): FileLinkTarget? {
        val trimmed = destination.trim()
        if (trimmed.isEmpty()) return null
        val raw = fileAddress(trimmed) ?: return null
        if (raw.isEmpty()) return null

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
        // 行号锚点（`…Foo.kt#L24`）不算路径，判形状前先切掉。
        val address = fileAddress(token) ?: return null
        if (address.startsWith("dsh-resource://file/", ignoreCase = true) ||
            address.startsWith(FILE_SCHEME, ignoreCase = true)
        ) {
            return parse(token)
        }
        if (address.startsWith("//") || address.isEmpty()) return null
        if (address.startsWith("/")) return parse(token)
        if (!address.contains('/')) return null
        val name = address.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        val extension = name.substring(dot + 1)
        if (extension.length > 8 || !extension.all { it.isLetterOrDigit() }) return null
        return parse(token)
    }

    /**
     * 去掉行号锚点与查询串，留下真正要读的地址。
     *
     * `#` 之后只认行号（`L24`、`L24-L30`）：别的片段说明这条链接不是文件链接
     * （Web `parseFileLink` 同样返回 undefined），返回 null 让调用方退回普通链接；
     * 含 `?` 的地址是查询串，同样不当作路径。每段都百分号编码过，所以文件名里的
     * `#`/`?` 是 `%23`/`%3F`，不会被这里误切。
     */
    private fun fileAddress(trimmed: String): String? {
        val hash = trimmed.indexOf('#')
        val address = if (hash < 0) trimmed else trimmed.substring(0, hash)
        if (hash >= 0 && !isLineFragment(trimmed.substring(hash + 1))) return null
        if (address.contains('?')) return null
        return address
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
            // 与 Web parseFileAddress 的 absolute 分支同构：先分段再逐段解码，
            // 双斜杠开头是 UNC（拼回 "//host/share/…"），首段是 Windows 盘符时不再补
            // 前导 "/"——"/C:/work/x.ts" 交给主机只会 workspace-file/not-found。
            "absolute" -> {
                val segments = tail.split('/')
                val unc = segments.firstOrNull() == "" && segments.size > 1
                val parts = (if (unc) segments.drop(1) else segments).map { decode(it) }
                when {
                    parts.isEmpty() || parts.first().isEmpty() -> null
                    unc -> FileLinkTarget("//" + parts.joinToString("/"))
                    isDriveSegment(parts.first()) -> FileLinkTarget(parts.joinToString("/"))
                    else -> FileLinkTarget("/" + parts.joinToString("/"))
                }
            }
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

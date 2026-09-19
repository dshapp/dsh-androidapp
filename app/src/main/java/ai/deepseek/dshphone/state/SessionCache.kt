package ai.deepseek.dshphone.state

import ai.deepseek.dshphone.model.HistoryEntry
import ai.deepseek.dshphone.model.SessionSummary
import ai.deepseek.dshphone.net.DshJson
import android.content.Context
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 一份可以立刻画出来、随后交给远端覆盖的会话窗口。
 *
 * 不带 projections：窗口里真正花时间的是消息记录，标题一类的投影由
 * 常开的 session/control 基线补，缓存里再存一份只会让写盘变大。
 */
@Serializable
data class CachedWindow(
    /** 快照当时的权威尾序号；落盘时还不知道就留 -1，读回后不 subscribe 即可。 */
    val cursor: Int = -1,
    val hasMore: Boolean = true,
    val records: List<HistoryEntry> = emptyList(),
)

@Serializable
private data class CachedRoster(val items: List<SessionSummary> = emptyList())

/**
 * 会话清单与历史窗口的本地缓存，用来做 stale-while-revalidate：
 * 冷启动先把上次的样子画出来，同时照常连远端，基线回来整体替换。
 *
 * 权威性永远在远端——这里存的任何东西都只是"先看着"，不参与任何序号/去重判定。
 *
 * 落在 cacheDir 而不是 filesDir 是有意的：它不该进系统备份，系统也可以随时回收；
 * 回收的后果只是回到今天的体验（白屏等远端），不会出错。目录按 profile 分开，
 * 两台主机的会话 id 不能互相顶替。
 */
class SessionCache(context: Context, profileId: String) {

    private val root = File(File(context.cacheDir, "dsh-cache"), sanitize(profileId))
    private val rosterFile = File(root, "roster.json")
    private val windowsDir = File(root, "windows")

    /** 上次落盘的清单；没有或读坏了都返回空表。 */
    fun loadRoster(): List<SessionSummary> = runCatching {
        if (!rosterFile.isFile) return emptyList()
        DshJson.decodeFromString(CachedRoster.serializer(), rosterFile.readText()).items
    }.getOrDefault(emptyList())

    fun saveRoster(items: List<SessionSummary>) {
        if (items.isEmpty()) return
        runCatching {
            write(rosterFile, DshJson.encodeToString(CachedRoster.serializer(), CachedRoster(items)))
        }
    }

    /** 某个会话上次的窗口；没有或读坏了都返回 null。 */
    fun loadWindow(sessionId: String): CachedWindow? = runCatching {
        val file = windowFile(sessionId)
        if (!file.isFile) return null
        DshJson.decodeFromString(CachedWindow.serializer(), file.readText())
            .takeIf { it.records.isNotEmpty() }
    }.getOrNull()

    fun saveWindow(sessionId: String, window: CachedWindow) {
        if (window.records.isEmpty() || window.records.size > MAX_CACHED_RECORDS) return
        runCatching {
            write(windowFile(sessionId), DshJson.encodeToString(CachedWindow.serializer(), window))
            pruneWindows()
        }
    }

    fun removeWindow(sessionId: String) {
        runCatching { windowFile(sessionId).delete() }
    }

    fun clear() {
        runCatching { root.deleteRecursively() }
    }

    /** 同一台主机上最多留这么多条会话的窗口，按最后写入时间淘汰。 */
    private fun pruneWindows() {
        val files = windowsDir.listFiles() ?: return
        if (files.size <= MAX_CACHED_WINDOWS) return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_CACHED_WINDOWS)
            .forEach { it.delete() }
    }

    private fun windowFile(sessionId: String) = File(windowsDir, sanitize(sessionId) + ".json")

    /** 先写临时文件再改名：进程被杀不会留下半截 JSON。 */
    private fun write(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)

    companion object {
        private const val MAX_CACHED_WINDOWS = 24
        private const val MAX_CACHED_RECORDS = 4000
    }
}

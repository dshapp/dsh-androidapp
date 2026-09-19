package ai.deepseek.dshphone.model

import ai.deepseek.dshphone.net.bool
import ai.deepseek.dshphone.net.arr
import ai.deepseek.dshphone.net.get
import ai.deepseek.dshphone.net.int
import ai.deepseek.dshphone.net.str
import kotlinx.serialization.json.JsonElement

/**
 * 一轮里被改动的一个文件（主机 `WorkspaceChangedFile`）。
 *
 * [display] 是主机的排序键与展示文案——相对工作目录的路径、往上的 `../` 路径、
 * home 下的 `~` 路径或绝对路径——卡片显示它；[path] 是 diff 路由寻址用的身份。
 */
data class ChangedFileEntry(
    val path: String,
    val display: String,
    val added: Int,
    val deleted: Int,
    val binary: Boolean,
    val oversized: Boolean,
) {
    val id: String get() = path
}

/**
 * 一条已公告的改动记录的坐标：会话 + 公告它那条 `workspace/changes` 事件的 seq。
 *
 * 按 seq 而不是按轮次寻址：同一轮后来的公告会取代先前的记录，被取代的那条不再可寻址，
 * 与主机替换自己的记录一致（对齐 Mac `TurnChangesStore.Address`）。
 */
data class TurnChangesAddress(val sessionId: String, val seq: Int)

/** 一轮改动文件的汇总（主机 `WorkspaceChangesSummary`）。 */
data class TurnChangesSummary(
    val turn: Int,
    val files: List<ChangedFileEntry>,
    /** 完整的改动文件数，可能大于 [files]（主机有上限）。 */
    val total: Int,
    val added: Int,
    val deleted: Int,
) {
    companion object {
        /**
         * 解一条 `/api/changes.summary` 的响应体，缺字段的记录直接丢掉
         * （对齐 web `isChangesSummary` 与 Mac `TurnChangesSummary.decode`）。
         */
        fun decode(value: JsonElement?): TurnChangesSummary? {
            val turn = value["turn"].int ?: return null
            if (turn < 1) return null
            val total = value["total"].int ?: return null
            val added = value["added"].int ?: return null
            val deleted = value["deleted"].int ?: return null
            val entries = value["files"].arr ?: return null
            val files = entries.mapNotNull { entry ->
                val path = entry["path"].str?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val display = entry["display"].str?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val fileAdded = entry["added"].int ?: return@mapNotNull null
                val fileDeleted = entry["deleted"].int ?: return@mapNotNull null
                ChangedFileEntry(
                    path = path,
                    display = display,
                    added = fileAdded,
                    deleted = fileDeleted,
                    binary = entry["binary"].bool == true,
                    oversized = entry["oversized"].bool == true,
                )
            }
            return TurnChangesSummary(turn, files, total, added, deleted)
        }
    }
}

/** 比较结果里的一段统一 diff；每行保留 `+`、`-` 或空格前缀。 */
data class ChangedFileHunk(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val lines: List<String>,
)

/**
 * 一个列出的文件在轮首与轮末的比较（主机 `WorkspaceFileDiff`）。
 *
 * 二进制或超限的一侧没有行可给，所以 kind 是值的一部分，而不是「空 hunk 列表」。
 */
sealed interface ChangedFileDiff {
    val path: String
    val display: String

    data class Text(
        override val path: String,
        override val display: String,
        val hunks: List<ChangedFileHunk>,
        val coarse: Boolean,
    ) : ChangedFileDiff

    data class Binary(override val path: String, override val display: String) : ChangedFileDiff

    data class Oversized(override val path: String, override val display: String) : ChangedFileDiff

    companion object {
        fun decode(value: JsonElement?): ChangedFileDiff? {
            val kind = value["kind"].str ?: return null
            val path = value["path"].str ?: return null
            val display = value["display"].str ?: return null
            return when (kind) {
                "text" -> {
                    val hunks = (value["hunks"].arr ?: emptyList()).mapNotNull { hunk ->
                        val oldStart = hunk["oldStart"].int ?: return@mapNotNull null
                        val oldLines = hunk["oldLines"].int ?: return@mapNotNull null
                        val newStart = hunk["newStart"].int ?: return@mapNotNull null
                        val newLines = hunk["newLines"].int ?: return@mapNotNull null
                        val lines = hunk["lines"].arr ?: return@mapNotNull null
                        ChangedFileHunk(
                            oldStart = oldStart,
                            oldLines = oldLines,
                            newStart = newStart,
                            newLines = newLines,
                            lines = lines.mapNotNull { it.str },
                        )
                    }
                    Text(path, display, hunks, value["coarse"].bool == true)
                }
                "binary" -> Binary(path, display)
                "oversized" -> Oversized(path, display)
                else -> null
            }
        }
    }
}

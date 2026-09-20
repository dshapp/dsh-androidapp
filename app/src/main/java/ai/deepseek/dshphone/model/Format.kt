package ai.deepseek.dshphone.model

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.net.arr
import ai.deepseek.dshphone.net.get
import ai.deepseek.dshphone.net.int
import ai.deepseek.dshphone.net.str
import kotlinx.serialization.json.JsonElement
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// 轮次页脚格式化（与 iOS/web 同口径）——————————————————————————

/**
 * 数字读数的固定区域。
 *
 * `String.format` 不带 Locale 时跟设备区域走：德语设备把小数点写成 `,`，阿拉伯语设备连
 * 千分位和数字形状都换掉，同一台主机在三端显示的数字于是不再一致（web 在中/英两种界面语言
 * 下都把小数点固定为 `.`、千分位固定为 `,`，Mac 的 formatExactTokens 也把 groupingSeparator
 * 直接写成 `,`）。本端界面语言只有中/英两种，读数因此也只能跟着界面语言走，不能跟着设备区域走。
 */
private val FIXED_LOCALE: Locale = Locale.ROOT

fun formatDuration(ms: Double): String {
    val total = max(0.0, ms / 1000).toInt()
    val minutes = total / 60
    val seconds = total % 60
    return if (minutes > 0) fmt(Strings.durationMinutes, minutes, seconds) else fmt(Strings.durationSeconds, seconds)
}

/**
 * 上下文面板里的用时读数：不足一分钟保留一位小数（45.2秒），超过就按分秒拆开（2分42秒）。
 * 与 Mac formatStatsDuration 同口径。
 */
fun formatStatsDuration(ms: Double): String {
    // 非有限读数过不了下面 `< 60` 的判断，会一路落到分钟分支的 `roundToLong()` 上——
    // Kotlin 的 roundToLong 对 NaN/无穷直接抛 IllegalArgumentException（不是"显示得不对"，
    // 是把整块 UI 带崩）。时长来自主机投影（`llmMs`/`toolMs`/`ttftMs`），而 wire 上
    // `"NaN"` 或 `"1e400"` 这类字符串会原样解析成 NaN/Infinity，所以这道闸是必须的。
    // Mac 在同一位置显式挡了一道（SessionStatsReadings.formatStatsDuration）。
    if (!ms.isFinite()) return fmt(Strings.durationSecondsText, "0")
    val seconds = nonNegative(ms) / 1000
    if (seconds < 60) {
        val tenths = (seconds * 10).roundToLong()
        val text = if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
        return fmt(Strings.durationSecondsText, text)
    }
    val whole = seconds.roundToLong()
    return fmt(Strings.statsDurationMinutes, whole / 60, whole % 60)
}

fun formatLatencySeconds(ms: Double): String {
    val s = nonNegative(ms) / 1000
    return if (s < 10) String.format(FIXED_LOCALE, "%.1f", (s * 10).roundToInt() / 10.0)
    else String.format(FIXED_LOCALE, "%.0f", s)
}

fun formatTokensPerSecond(tps: Double): String {
    val clamped = nonNegative(tps)
    return if (clamped >= 10) String.format(FIXED_LOCALE, "%.0f", clamped)
    else String.format(FIXED_LOCALE, "%.1f", (clamped * 10).roundToInt() / 10.0)
}

/**
 * 非负夹取，NaN 读作 0——等价于 Mac 的 `max(0, x)`（Swift 的 `max` 用 `>=` 比较，
 * `NaN >= 0` 为假，于是返回 0），而 Kotlin 的 `max(0.0, x)` 会把 NaN 原样带下去，
 * 之后任何一次 `roundToInt/roundToLong` 都是一次崩溃。次秒级/速率读数都从 wire 来，
 * 所以这条口径必须是 NaN 安全的。
 */
private fun nonNegative(value: Double): Double = if (value.isNaN()) 0.0 else max(0.0, value)

/**
 * 一轮的计费桶加 output（web `TurnTokenUsage.totalTokens`）。
 *
 * 四个桶都直接来自 wire 上的 `tokenUsage` 投影，任一桶都可能顶着 Int.MAX_VALUE；
 * 直接相加会回绕成负数，于是调用方的 `turnTotalTokens(usage) > 0` 为假，token 药丸
 * 与明细卡双双消失——用户看到的是"这一轮没有用量"。Mac 的 `turnTotalTokens` 用
 * saturatingAdding，WireNumbers.swift 里写明理由正是这条。
 */
fun turnTotalTokens(usage: TokenUsage): Int {
    val uncached = usage.uncachedInputTokens ?: usage.inputTokens ?: 0
    return uncached
        .saturatingAdding(usage.cacheReadTokens ?: 0)
        .saturatingAdding(usage.cacheWriteTokens ?: 0)
        .saturatingAdding(usage.outputTokens ?: 0)
}

fun formatTokens(value: Int): String {
    fun scaled(candidate: Double): String {
        if (candidate >= 100) return String.format(FIXED_LOCALE, "%.0f", candidate)
        val tenths = (candidate * 10).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
    }
    if (value < 1_000) return value.toString()
    if (value < 1_000_000) return scaled(value / 1_000.0) + "K"
    return scaled(value / 1_000_000.0) + "M"
}

fun formatExactTokens(value: Int): String = String.format(FIXED_LOCALE, "%,d", value)

fun tokenCount(value: Int): String = fmt(Strings.turnUsageCount, formatExactTokens(value))

fun compactTokenCount(value: Int): String = fmt(Strings.turnUsageCount, formatTokens(value))

fun cacheHitPercent(usage: TokenUsage): String? {
    val cacheRead = usage.cacheReadTokens ?: return null
    val prompt = turnTotalTokens(usage) - (usage.outputTokens ?: 0)
    if (prompt <= 0) return null
    val tenths = min(1000L, max(0L, (cacheRead.toDouble() / prompt * 1000).roundToLong()))
    val units = if (cacheRead < prompt) min(tenths, 999L) else tenths
    val whole = units / 10
    val fraction = units % 10
    return if (fraction == 0L) whole.toString() else "$whole.$fraction"
}

// 会话标题 ————————————————————————————————————————————————————

fun pathBasename(path: String): String {
    var trimmed = path
    while (trimmed.isNotEmpty() && (trimmed.last() == '/' || trimmed.last() == '\\')) trimmed = trimmed.dropLast(1)
    return trimmed.split('/', '\\').lastOrNull().orEmpty()
}

fun sessionDisplayTitle(summary: SessionSummary): String {
    summary.projections?.values?.get("title").str?.takeIf { it.isNotEmpty() }?.let { return it }
    summary.cwd?.takeIf { it.isNotEmpty() }?.let { cwd ->
        val base = pathBasename(cwd)
        if (base.isNotEmpty()) return base
    }
    return summary.sessionId
}

fun sessionDisplayTitle(summary: SessionSummary, liveTitle: String?): String {
    if (summary.blank) return Strings.newSessionPlaceholder
    if (!liveTitle.isNullOrEmpty()) return liveTitle
    return sessionDisplayTitle(summary)
}

/**
 * 会话归属的工作区显示名：优先工作区标题，退回 cwd 的目录名；都没有就是 null。
 *
 * 与 Mac `sessionWorkspaceName` 同口径，也复用同一份路径取 basename 的规则——
 * 会话标题与工作区名必须用同一种写法，否则搜索命中与列表行的灰色前缀会对不上。
 */
fun sessionWorkspaceName(workspaceTitle: String?, cwd: String?): String? {
    workspaceTitle?.takeIf { it.isNotEmpty() }?.let { return it }
    val base = cwd?.takeIf { it.isNotEmpty() }?.let { pathBasename(it) }.orEmpty()
    return base.takeIf { it.isNotEmpty() }
}

/**
 * 本地搜索用的会话名：实时标题 / 投影标题 / cwd 目录名；三者都没有就是空串。
 *
 * 刻意不退回 sessionId：那是 [sessionDisplayTitle] 给无标题会话的兜底显示值，
 * 拿它参与子串匹配会让「session」这种常见词命中几乎每一条会话。
 */
fun sessionSearchName(summary: SessionSummary, liveTitle: String?): String {
    if (!liveTitle.isNullOrEmpty()) return liveTitle
    summary.projections?.values?.get("title").str?.takeIf { it.isNotEmpty() }?.let { return it }
    return summary.cwd?.takeIf { it.isNotEmpty() }?.let { pathBasename(it) }.orEmpty()
}

/**
 * 本地元数据是否命中查询：会话标题或工作区名包含查询串（大小写不敏感）。
 *
 * 主机的内容搜索是可选的（`openAt: never` 的部署直接拒绝 `session/search`），
 * 而三端（web `deriveSearchResults` 本地臂 / Mac `resolved` / 本端）都保留一条
 * 纯本地的兜底：拿已经在列表里的标题和工作区名做子串匹配。主机拒绝搜索时，
 * 用户至少还能按名字找到会话，而不是任何查询都空。
 */
fun sessionMatchesSearch(title: String, workspaceName: String?, query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return false
    return title.contains(needle, ignoreCase = true) ||
        (workspaceName?.contains(needle, ignoreCase = true) == true)
}

/** 协议约定的搜索结果上限（web/Mac 的 `searchResultLimit` 都是 20）。 */
const val SESSION_SEARCH_RESULT_LIMIT = 20

/** 合并后的搜索结果：截断后的行，以及是否还有被截掉的命中。 */
data class SessionSearchRows(
    val sessions: List<SessionSummary> = emptyList(),
    val hasMore: Boolean = false,
)

/**
 * 合并「本地标题命中的会话」与「主机内容命中的结果」。
 *
 * 顺序与 web `deriveSearchResults` 一致：本地命中先出（[localMatches] 已按最近
 * 活动排序），主机命中补齐其余、按 sessionId 去重。空白会话与 subagent 来源的
 * 会话一律不进结果：前者是「新建会话」的占位，后者只在父会话的子智能体面板里存在。
 * 与 web/Mac 一样截到 [limit]，多出来的用 [SessionSearchRows.hasMore] 告知调用方。
 */
fun mergeSessionSearchResults(
    localMatches: List<SessionSummary>,
    remoteHits: List<SessionSearchItem>,
    byId: Map<SessionId, SessionSummary>,
    limit: Int = SESSION_SEARCH_RESULT_LIMIT,
): SessionSearchRows {
    val ordered = mutableListOf<SessionSummary>()
    val seen = mutableSetOf<SessionId>()
    for (summary in localMatches) if (seen.add(summary.sessionId)) ordered.add(summary)
    for (hit in remoteHits) {
        val summary = byId[hit.sessionId] ?: continue
        if (summary.blank || summary.isSubagentOrigin) continue
        if (seen.add(summary.sessionId)) ordered.add(summary)
    }
    return SessionSearchRows(ordered.take(limit), ordered.size > limit)
}

// 列表行尾 ————————————————————————————————————————————————————

enum class SessionAwaitingKind {
    APPROVAL, PLAN_REVIEW, QUESTION;

    val badge: String
        get() = when (this) {
            APPROVAL -> Strings.sessionAwaitingApproval
            PLAN_REVIEW -> Strings.sessionAwaitingPlanReview
            QUESTION -> Strings.sessionAwaitingQuestion
        }
}

sealed interface SessionRowTrailing {
    data class AwaitingUser(val kind: SessionAwaitingKind) : SessionRowTrailing
    data object Draft : SessionRowTrailing
    data class Time(val updatedAt: Double) : SessionRowTrailing
    data object None : SessionRowTrailing

    companion object {
        fun resolve(
            awaitingUser: SessionAwaitingKind?,
            isSelected: Boolean,
            isArchived: Boolean,
            isBlank: Boolean,
            hasDraft: Boolean,
            updatedAt: Double,
        ): SessionRowTrailing {
            if (isArchived || isBlank) return None
            if (isSelected) return Time(updatedAt)
            if (awaitingUser != null) return AwaitingUser(awaitingUser)
            if (hasDraft) return Draft
            return Time(updatedAt)
        }
    }
}

// 轮次导航 ————————————————————————————————————————————————————

data class TurnRailItem(
    val turn: Int,
    val prompt: String,
    val response: String,
    val anchorRow: String?,
    val promptRow: String?,
    val seq: Int?,
) {
    val isLoaded: Boolean get() = anchorRow != null
}

object TurnRailPreview {
    const val PROMPT_LIMIT = 50
    const val RESPONSE_LIMIT = 120

    fun clip(parts: List<String>, limit: Int): String {
        var text = ""
        var unread = false
        for (part in parts) {
            if (text.length >= limit * 2) { unread = true; break }
            val clipped = part.length > limit * 2
            val chunk = if (clipped) part.take(limit * 2) else part
            text = if (text.isEmpty()) chunk else "$text $chunk"
            if (clipped) { unread = true; break }
        }
        val normalized = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (normalized.length > limit - 1) return normalized.take(limit - 1).trim() + "…"
        return if (unread) "$normalized…" else normalized
    }
}

fun mergeTurnRailItems(
    outline: JsonElement?,
    anchors: Map<Int, String>,
    promptAnchors: Map<Int, String>,
    loadedPreview: (Int) -> Pair<String, String>,
): List<TurnRailItem> {
    val prompts = mutableMapOf<Int, String>()
    val responses = mutableMapOf<Int, String>()
    val seqs = mutableMapOf<Int, Int>()
    for (entry in outline.arr ?: emptyList()) {
        val turn = entry["turn"].int ?: continue
        val seq = entry["seq"].int ?: continue
        if (turn < 0 || seq < 0) continue
        seqs[turn] = seq
        prompts[turn] = entry["prompt"].str ?: ""
        responses[turn] = entry["response"].str ?: ""
    }
    val turns = (seqs.keys + anchors.keys).sorted()
    return turns.map { turn ->
        val anchor = anchors[turn]
        var prompt = prompts[turn] ?: ""
        var response = responses[turn] ?: ""
        if (anchor != null && (prompt.isEmpty() || response.isEmpty())) {
            val loaded = loadedPreview(turn)
            if (prompt.isEmpty()) prompt = loaded.first
            if (response.isEmpty()) response = loaded.second
        }
        TurnRailItem(turn, prompt, response, anchor, promptAnchors[turn], seqs[turn])
    }
}

// 计划模式 ————————————————————————————————————————————————————

object PlanModeToggle {
    const val ENTER = "/plan"
    const val LEAVE = "/plan off"
    fun line(target: Boolean): String = if (target) ENTER else LEAVE
}

// 产物 ————————————————————————————————————————————————————————

data class PresentedFile(val seq: Int, val index: Int, val path: String, val description: String?)

object TurnDeliverables {
    fun presented(seq: Int, data: JsonElement?): Pair<Int, List<PresentedFile>>? {
        val turn = data["turn"].int ?: return null
        if (turn < 1) return null
        val entries = data["files"].arr ?: return null
        val files = mutableListOf<PresentedFile>()
        entries.forEachIndexed { index, entry ->
            val path = entry["path"].str ?: return@forEachIndexed
            if (path.isBlank()) return@forEachIndexed
            files.add(PresentedFile(seq, index, path, entry["description"].str))
        }
        return if (files.isEmpty()) null else turn to files
    }

    /** 从模型自己的参数解析出这次调用产出的文件。 */
    fun producedPath(name: String, argsRaw: String): String? {
        val args = parseArgsMap(argsRaw) ?: return null
        return when (name) {
            "write" -> if (args["content"] is String) pathValue(args["file_path"]) else null
            "edit" -> {
                val old = args["old_string"] as? String
                val new = args["new_string"] as? String
                // replace_all 要么缺省、要么必须是布尔：别的类型说明这次调用畸形，
                // 不算产出（Web validEditArgs / Mac producedPath 同款校验）。
                val replaceAll = args["replace_all"]
                val replaceAllValid = replaceAll == null || replaceAll is Boolean
                if (old.isNullOrEmpty() || new == null || old == new || !replaceAllValid) null
                else pathValue(args["file_path"])
            }
            "str_replace_editor" -> editorMutationPath(args)
            else -> null
        }
    }

    private fun editorMutationPath(args: Map<String, Any?>): String? {
        val path = pathValue(args["path"]) ?: return null
        return when (args["command"] as? String) {
            "create" -> if (args["file_text"] is String) path else null
            "str_replace" -> {
                val old = args["old_str"] as? String
                // new_str 缺省或字符串都行，别的类型整条不算（Web editorMutationPath）。
                val newStr = args["new_str"]
                val newStrValid = newStr == null || newStr is String
                if (old.isNullOrEmpty() || !newStrValid) null else path
            }
            "insert" -> {
                // 必须是整数行号：1.5 这种小数在 Web 走 Number.isInteger 被拒、
                // Mac 的 as? Int 也拒，toInt() 截断会把它当成合法的第 1 行。
                val line = (args["insert_line"] as? Number)?.takeIf {
                    it.toDouble() == Math.floor(it.toDouble()) && !it.toDouble().isInfinite()
                }?.toLong()
                if (line != null && line >= 0 && args["new_str"] is String) path else null
            }
            else -> null
        }
    }

    private fun pathValue(value: Any?): String? = (value as? String)?.takeIf { it.isNotBlank() }

    fun resolveMention(value: String, paths: List<String>): String? {
        if (paths.contains(value)) return value
        val matches = paths.filter { pathBasename(it) == value }
        return if (matches.size == 1) matches[0] else null
    }
}

// Agent 预设显示 ——————————————————————————————————————————————

data class AgentPresetDisplayText(val name: String, val description: String?)

fun agentPresetDisplayText(preset: AgentPresetEntry): AgentPresetDisplayText {
    if (preset.trust == "system") {
        when (preset.id) {
            "standard" -> return AgentPresetDisplayText(Strings.presetStandardName, preset.description)
            "code" -> return AgentPresetDisplayText(Strings.presetCodeName, preset.description)
            "minimal" -> return AgentPresetDisplayText(Strings.presetMinimalName, preset.description)
            "cordis" -> return AgentPresetDisplayText(Strings.presetCordisName, preset.description)
        }
    }
    return AgentPresetDisplayText(preset.name ?: preset.id, preset.description)
}

fun agentPresetMenuLabel(preset: AgentPresetEntry): String {
    val name = agentPresetDisplayText(preset).name
    return if (preset.trust == "user") "$name · ${Strings.presetUserTrust}" else name
}

// "(Recommended)" 后缀拆分 ——————————————————————————————————

data class RecommendedOptionLabel(val label: String, val recommended: Boolean)

/** 与 iOS RecommendedOptionLabel 同一条正则：全/半角括号 × 中英文，且不区分大小写。 */
private val recommendedSuffix =
    Regex("""\s*(?:\((?:recommended|推荐)\)|（(?:recommended|推荐)）)\s*$""", RegexOption.IGNORE_CASE)

fun splitRecommended(raw: String): RecommendedOptionLabel {
    val match = recommendedSuffix.find(raw) ?: return RecommendedOptionLabel(raw, false)
    val label = raw.substring(0, match.range.first).trim()
    if (label.isEmpty()) return RecommendedOptionLabel(raw, false)
    return RecommendedOptionLabel(label, true)
}

// 相对时间 ————————————————————————————————————————————————————

/**
 * 紧凑相对时间，六档桶与 Web `ui-primitives` 的 relativeTime / Mac relativeTimeLabel 同口径：
 * now（<1 分钟）、minutes、hours、days、months（30 天起）、years（365 天起）。
 *
 * 口径必须两端一致：同一屏里两端对同一条会话的说法不能一个是"刚刚"一个是"0秒"，
 * 也不能把一年前读成 "395天"。
 */
fun relativeTime(updatedAtMs: Double): String {
    // 时钟漂移会让 updatedAt 落到未来；夹到 0 后走 now 档，不出现负数。
    val diff = (System.currentTimeMillis() - updatedAtMs).coerceAtLeast(0.0)
    val en = Strings.locale.name == "EN"
    val minute = 60_000.0
    val hour = 3_600_000.0
    val day = 86_400_000.0
    val month = 30 * day
    val year = 365 * day
    return when {
        diff < minute -> if (en) "now" else "刚刚"
        diff < hour -> {
            val n = (diff / minute).toLong()
            if (en) "${n}min" else "${n}分钟"
        }
        diff < day -> {
            val n = (diff / hour).toLong()
            if (en) "${n}h" else "${n}小时"
        }
        diff < month -> {
            val n = (diff / day).toLong()
            if (en) "${n}d" else "${n}天"
        }
        diff < year -> {
            val n = (diff / month).toLong()
            if (en) "${n}mo" else "${n}个月"
        }
        else -> {
            val n = (diff / year).toLong()
            if (en) "${n}y" else "${n}年"
        }
    }
}

fun clockTime(ms: Double): String {
    val formatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(ms.toLong()))
}

fun formatBytes(count: Int): String =
    if (count >= 1 shl 20) String.format(FIXED_LOCALE, "%.1f MB", count.toDouble() / (1 shl 20))
    else String.format(FIXED_LOCALE, "%.0f KB", count.toDouble() / 1024)

// 子智能体会话 id ——————————————————————————————————————————————

fun subagentSessionIds(entries: List<SubagentListEntry>): List<SessionId> = entries.map { it.sessionId }

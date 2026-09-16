package ai.deepseek.dshphone.model

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.net.arr
import ai.deepseek.dshphone.net.get
import ai.deepseek.dshphone.net.int
import ai.deepseek.dshphone.net.str
import kotlinx.serialization.json.JsonElement
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// 轮次页脚格式化（与 iOS/web 同口径）——————————————————————————

fun formatDuration(ms: Double): String {
    val total = max(0.0, ms / 1000).toInt()
    val minutes = total / 60
    val seconds = total % 60
    return if (minutes > 0) fmt(Strings.durationMinutes, minutes, seconds) else fmt(Strings.durationSeconds, seconds)
}

fun formatLatencySeconds(ms: Double): String {
    val s = max(0.0, ms) / 1000
    return if (s < 10) String.format("%.1f", (s * 10).roundToInt() / 10.0) else String.format("%.0f", s)
}

fun formatTokensPerSecond(tps: Double): String {
    val clamped = max(0.0, tps)
    return if (clamped >= 10) String.format("%.0f", clamped) else String.format("%.1f", (clamped * 10).roundToInt() / 10.0)
}

fun turnTotalTokens(usage: TokenUsage): Int {
    val uncached = usage.uncachedInputTokens ?: usage.inputTokens ?: 0
    return uncached + (usage.cacheReadTokens ?: 0) + (usage.cacheWriteTokens ?: 0) + (usage.outputTokens ?: 0)
}

fun formatTokens(value: Int): String {
    fun scaled(candidate: Double): String {
        if (candidate >= 100) return String.format("%.0f", candidate)
        val tenths = (candidate * 10).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
    }
    if (value < 1_000) return value.toString()
    if (value < 1_000_000) return scaled(value / 1_000.0) + "K"
    return scaled(value / 1_000_000.0) + "M"
}

fun formatExactTokens(value: Int): String = String.format("%,d", value)

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
                if (old.isNullOrEmpty() || new == null || old == new) null else pathValue(args["file_path"])
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
                if (old.isNullOrEmpty()) null else path
            }
            "insert" -> {
                val line = (args["insert_line"] as? Number)?.toInt()
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

fun relativeTime(updatedAtMs: Double): String {
    val delta = (System.currentTimeMillis() - updatedAtMs).toLong().coerceAtLeast(0L)
    val seconds = delta / 1000
    return when {
        seconds < 60 -> if (Strings.locale.name == "EN") "${seconds}s" else "${seconds}秒"
        seconds < 3600 -> if (Strings.locale.name == "EN") "${seconds / 60}m" else "${seconds / 60}分钟"
        seconds < 86_400 -> if (Strings.locale.name == "EN") "${seconds / 3600}h" else "${seconds / 3600}小时"
        else -> if (Strings.locale.name == "EN") "${seconds / 86_400}d" else "${seconds / 86_400}天"
    }
}

fun clockTime(ms: Double): String {
    val formatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(ms.toLong()))
}

fun formatBytes(count: Int): String =
    if (count >= 1 shl 20) String.format("%.1f MB", count.toDouble() / (1 shl 20))
    else String.format("%.0f KB", count.toDouble() / 1024)

// 子智能体会话 id ——————————————————————————————————————————————

fun subagentSessionIds(entries: List<SubagentListEntry>): List<SessionId> = entries.map { it.sessionId }

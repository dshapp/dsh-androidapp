package ai.deepseek.dshphone.model

import ai.deepseek.dshphone.net.DshJson
import ai.deepseek.dshphone.net.get
import ai.deepseek.dshphone.net.str
import kotlinx.serialization.json.JsonPrimitive

data class PreparingArguments(val summary: String, val filePath: String?, val kilobytes: Int?)

/** Parse only the argument head, never reparse a multi-MB streaming write. */
fun preparingArguments(name: String, raw: String): PreparingArguments {
    val head = raw.take(8192)
    fun partialString(key: String): Pair<String, Boolean>? {
        val match = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"").find(head) ?: return null
        val start = match.range.last + 1
        var i = start
        while (i < head.length) {
            if (head[i] == '\\') { i += 2; continue }
            if (head[i] == '"') break
            i++
        }
        val complete = i < head.length
        var encoded = head.substring(start, minOf(i, head.length))
        if (!complete) encoded = encoded.replace(Regex("\\\\(?:u[0-9a-fA-F]{0,3})?$"), "")
        val value = runCatching { (DshJson.parseToJsonElement("\"" + encoded + "\"") as? JsonPrimitive)?.content }.getOrNull() ?: return null
        return value to complete
    }
    val variant = classifyTool(name)
    val fileVariant = variant in setOf(ToolRowVariant.READ, ToolRowVariant.WRITE, ToolRowVariant.EDIT)
    val path = if (fileVariant) partialString("file_path") ?: partialString("path") else null
    val summary = if (path?.second == true) firstLine(path.first)
        else if (fileVariant) "" else firstLine(partialString("description")?.first ?: "")
    val kb = if ((variant == ToolRowVariant.WRITE || variant == ToolRowVariant.EDIT) &&
        CONTENT_FIELDS.any { key -> Regex("\"" + key + "\"\\s*:").containsMatchIn(head) })
        (raw.length.toLong() + 1023).div(1024).toInt() else null
    return PreparingArguments(summary, path?.takeIf { it.second }?.first?.let(::firstLine), kb)
}

private val CONTENT_FIELDS = listOf("content", "old_string", "new_string")

internal fun contentKilobytes(args: Map<String, Any?>?): Int? {
    val values = CONTENT_FIELDS.mapNotNull { args?.get(it) as? String }
    return if (values.isEmpty()) null else (values.sumOf { it.length.toLong() } + 1023).div(1024).toInt()
}

/** Metadata is authoritative; nested relative paths must never be guessed. */
internal fun recordedToolPath(data: ToolChatData, path: String?): String? {
    if (path == null || data.preparing) return null
    val recorded = data.result?.meta?.get("path").str
    fun absolute(value: String) = value.startsWith("/") || Regex("^[a-zA-Z]:[/\\\\]").containsMatchIn(value)
    if (recorded != null && absolute(recorded)) return recorded
    if (absolute(path) || path.startsWith("~")) return path
    if (data.result == null || data.result?.isError == true || data.parentCallId != null) return null
    return data.cwd?.let { it.trimEnd('/') + "/" + path } ?: path
}

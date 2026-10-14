package ai.deepseek.dshphone.model

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import ai.deepseek.dshphone.net.DshJson
import ai.deepseek.dshphone.net.get
import ai.deepseek.dshphone.net.int
import ai.deepseek.dshphone.net.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

enum class ToolRowVariant { SEARCH, READ, BASH, WRITE, EDIT, CODE, OTHERS }

enum class ToolRowState { PREPARING, RUNNING, OK, ERROR, STOPPED }

data class ToolRowModel(
    val variant: ToolRowVariant,
    val summary: String,
    val filePath: String?,
    val summarySuffix: String?,
    val errorSummary: String?,
    val state: ToolRowState,
)

private val TOOL_VARIANTS = mapOf(
    "bash" to ToolRowVariant.BASH,
    "pwsh" to ToolRowVariant.BASH,
    "read" to ToolRowVariant.READ,
    "read_image" to ToolRowVariant.READ,
    "web_fetch" to ToolRowVariant.READ,
    "web_search" to ToolRowVariant.SEARCH,
    "grep" to ToolRowVariant.SEARCH,
    "glob" to ToolRowVariant.SEARCH,
    "write" to ToolRowVariant.WRITE,
    "edit" to ToolRowVariant.EDIT,
    "run_code" to ToolRowVariant.CODE,
    "cordis_package_inspect" to ToolRowVariant.READ,
    "cordis_runtime_inspect" to ToolRowVariant.READ,
    "cordis_run" to ToolRowVariant.OTHERS,
    "cordis_stop" to ToolRowVariant.OTHERS,
    "cordis_undefine" to ToolRowVariant.OTHERS,
)

private val KEYED_ROW_TOOLS = setOf(
    "cordis_run", "cordis_stop", "cordis_undefine", "skill",
    "todo_write", "ask_user_question", "exit_plan_mode",
)

private val SUMMARY_KEYS = mapOf(
    ToolRowVariant.BASH to listOf("description", "command"),
    ToolRowVariant.READ to listOf("path", "file_path", "url"),
    ToolRowVariant.SEARCH to listOf("query", "pattern", "url"),
    ToolRowVariant.WRITE to listOf("path", "file_path"),
    ToolRowVariant.EDIT to listOf("path", "file_path"),
    ToolRowVariant.CODE to listOf("description"),
    ToolRowVariant.OTHERS to emptyList(),
)

private val FILE_PATH_VARIANTS = setOf(ToolRowVariant.READ, ToolRowVariant.WRITE, ToolRowVariant.EDIT)

fun classifyTool(name: String): ToolRowVariant = TOOL_VARIANTS[name] ?: ToolRowVariant.OTHERS

/** 把 JSON 对象摊成 Kotlin Map，非 JSON 返回 null（流式截断时会遇到）。 */
fun parseArgsMap(raw: String): Map<String, Any?>? {
    if (raw.isBlank()) return null
    val element = runCatching { DshJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
    return element.mapValues { toAnyValue(it.value) }
}

private fun toAnyValue(value: JsonElement): Any? = when (value) {
    is JsonPrimitive -> when {
        value.isString -> value.content
        value.booleanOrNull != null -> value.booleanOrNull
        value.longOrNull != null -> value.longOrNull
        value.doubleOrNull != null -> value.doubleOrNull
        else -> null
    }
    is JsonArray -> value.map { toAnyValue(it) }
    is JsonObject -> value.mapValues { toAnyValue(it.value) }
}

/**
 * 读取一个"对象数组"字段，元素级校验类型。
 *
 * 不能写成 `value as? List<Map<String, Any?>>`：泛型擦除后只剩 `List`，元素是 String
 * 时转换照样"成功"，直到取键时才抛 ClassCastException（本函数在组合期执行，等于崩溃）。
 * 元素类型不对就整体返回 null，与 Mac `as? [[String: Any]]` 一致，由调用方回退到通用摘要。
 */
@Suppress("UNCHECKED_CAST")
private fun objectList(value: Any?): List<Map<String, Any?>>? {
    val list = value as? List<*> ?: return null
    val out = ArrayList<Map<String, Any?>>(list.size)
    for (item in list) {
        out.add(item as? Map<String, Any?> ?: return null)
    }
    return out
}

private fun pickString(args: Map<String, Any?>, keys: List<String>): String? {
    for (key in keys) {
        val value = args[key] as? String
        if (!value.isNullOrEmpty()) return value
    }
    return null
}

fun deriveSummary(variant: ToolRowVariant, argsRaw: String, args: Map<String, Any?>?): String {
    if (argsRaw.isEmpty()) return ""
    if (args != null) {
        if (variant == ToolRowVariant.SEARCH) {
            val queries = (args["queries"] as? List<*>)?.filterIsInstance<String>()?.filter { it.isNotEmpty() }
            if (!queries.isNullOrEmpty()) return queries.joinToString(", ") { firstLine(it) }
        }
        pickString(args, SUMMARY_KEYS[variant] ?: emptyList())?.let { return firstLine(it) }
        for (value in args.values) {
            if (value is String && value.isNotEmpty()) return firstLine(value)
        }
    }
    return firstLine(argsRaw)
}

private fun deriveFilePath(variant: ToolRowVariant, args: Map<String, Any?>?): String? {
    if (variant !in FILE_PATH_VARIANTS || args == null) return null
    return pickString(args, listOf("path", "file_path"))?.let { firstLine(it) }
}

private fun terminalFailed(resultView: JsonElement?): Boolean {
    if (resultView["card"].str != "terminal") return false
    val exit = resultView["exitCode"].int
    if (exit != null && exit != 0) return true
    return resultView["signal"].str != null
}

private fun searchResultTitle(resultView: JsonElement?): String? {
    if (resultView["card"].str != "search") return null
    return resultView["title"].str
}

private fun terminalDescription(callView: JsonElement?): String? {
    if (callView["card"].str != "terminal") return null
    return callView["description"].str
}

/** 把工作区内的绝对路径显示成相对路径。 */
private fun relativizeToCwd(summary: String, cwd: String?): String {
    if (cwd.isNullOrEmpty() || summary.isEmpty()) return summary
    val root = cwd.trimEnd('/')
    return when {
        summary == root -> "."
        summary.startsWith("$root/") -> summary.removePrefix("$root/")
        else -> summary
    }
}

fun toolRowModel(data: ToolChatData, cwd: String?): ToolRowModel {
    val variant = classifyTool(data.name)
    val done = data.result != null
    val argsRaw = data.argsRaw
    if (data.preparing) {
        val preview = preparingArguments(data.name, argsRaw)
        return ToolRowModel(variant, relativizeToCwd(preview.summary, data.cwd ?: cwd), null,
            preview.kilobytes?.let { "$it KB" }, null, ToolRowState.PREPARING)
    }
    val args = if (argsRaw.isEmpty()) null else parseArgsMap(argsRaw)
    var state = when {
        !done -> ToolRowState.RUNNING
        data.result?.error?.code == "interrupted" -> ToolRowState.STOPPED
        data.result?.isError == true -> ToolRowState.ERROR
        else -> ToolRowState.OK
    }
    val base = when (data.name) {
        "todo_write" -> todoSummary(argsRaw)?.first ?: ""
        "ask_user_question" -> askSummary(data) ?: ""
        "exit_plan_mode" -> planSummary(data) ?: ""
        else -> relativizeToCwd(deriveSummary(variant, argsRaw, args), data.cwd ?: cwd)
    }
    val replacement = terminalDescription(data.callView) ?: searchResultTitle(data.result?.resultView)
    val summary = if (variant == ToolRowVariant.OTHERS && data.name.isNotEmpty() && data.name !in KEYED_ROW_TOOLS) {
        "${data.name} · $base"
    } else {
        replacement ?: base
    }
    if (data.name == "ask_user_question" && data.result?.error?.code == "ASK_ABORTED") state = ToolRowState.STOPPED
    val resolvedState =
        if (state == ToolRowState.OK && terminalFailed(data.result?.resultView)) ToolRowState.ERROR else state
    val errorSummary = if (resolvedState == ToolRowState.ERROR) {
        data.result?.let { resultFirstLine(it) }?.takeIf { it.isNotEmpty() }
    } else null
    return ToolRowModel(
        variant = variant,
        summary = summary,
        filePath = recordedToolPath(data, deriveFilePath(variant, args)),
        summarySuffix = if (errorSummary != null) null
            else if (data.name == "todo_write") todoSummary(argsRaw)?.second
            else if (variant == ToolRowVariant.WRITE || variant == ToolRowVariant.EDIT) contentKilobytes(args)?.let { "$it KB" }
            else null,
        errorSummary = errorSummary,
        state = resolvedState,
    )
}

/** todo 行的摘要：完成数 + 当前进行项；后缀是并行数，不能被截断。 */
fun todoSummary(argsRaw: String): Pair<String, String?>? {
    val args = parseArgsMap(argsRaw) ?: return null
    val todos = objectList(args["todos"]) ?: return null
    val active = todos.filter { it["status"] == "in_progress" }
    val done = todos.count { it["status"] == "completed" }
    val head = fmt(Strings.todoCompleted, done, todos.size)
    val first = active.mapNotNull { it["content"] as? String }.firstOrNull { it.isNotBlank() }
        ?: return head to null
    val suffix = if (active.size > 1) "+${active.size - 1}" else null
    return "$head · $first" to suffix
}

fun askSummary(data: ToolChatData): String? {
    when (data.result?.error?.code) {
        "ASK_CANCELLED" -> return Strings.askCancelled
        "ASK_ABORTED" -> return Strings.askInterrupted
    }
    val result = data.result ?: return Strings.askWaiting
    if (result.isError) return null
    val text = result.content.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
    val obj = parseArgsMap(text) ?: return null
    val answers = objectList(obj["answers"]) ?: return null
    val answered = answers.count { answer ->
        ((answer["selected"] as? List<*>)?.isNotEmpty() == true) ||
            ((answer["custom"] as? String)?.isNotEmpty() == true)
    }
    return fmt(Strings.askAnswered, answered, answers.size)
}

fun planSummary(data: ToolChatData): String? {
    val result = data.result ?: return Strings.planAwaitingReview
    if (result.isError) return null
    return Strings.planApproved
}

package ai.deepseek.dshphone.model

import ai.deepseek.dshphone.i18n.Strings

// 运行状态条（Mac `RunningStatusRow`）：运行中的轮次在输入框正上方给出一行
// "正在做哪类工作 · 用时"，例如「正在运行命令 · 1分5秒」。上方运行中的卡片已经点名
// 具体调用，这里只说工作类别，不再复述工具自己的摘要。

/** 工具名 → 工作类别文案（Mac `liveActivity`，按 wire 名精确匹配）。 */
fun liveActivity(name: String): String = when (name) {
    "read" -> Strings.activityRead
    "read_image" -> Strings.activityReadImage
    "grep", "glob" -> Strings.activitySearch
    "write" -> Strings.activityWrite
    "edit", "apply_patch" -> Strings.activityEdit
    "bash", "pwsh", "exec_command", "write_stdin" -> Strings.activityCommands
    "run_code" -> Strings.activityCode
    "web_search" -> Strings.activityWebSearch
    "web_fetch" -> Strings.activityWebFetch
    "todo_write", "create_goal", "update_goal", "get_goal" -> Strings.activityPlan
    "ask_user_question", "request_user_input" -> Strings.activityQuestions
    else -> when {
        name.endsWith("_inspect") -> Strings.activitySearch
        name.startsWith("terminal_") -> Strings.activityCommands
        name == "subagent" || name.startsWith("subagent_") -> Strings.activitySubagents
        else -> Strings.activityTools
    }
}

/**
 * 最新一轮里最新的、尚无结果的工具调用名（Mac `SessionStore.liveToolName`）：
 * 只看最新一轮，早先轮次里遗留的未结算调用不算"正在做"。
 */
fun liveToolName(nodes: List<ChatNode>): String? {
    val newestTurn = nodes.lastOrNull { it.turnNumber != null }?.turnNumber
    return nodes.asReversed().asSequence()
        .takeWhile { it.turnNumber == null || it.turnNumber == newestTurn }
        .filterIsInstance<ChatNode.Tool>()
        .firstOrNull { it.data.result == null }
        ?.data?.name
}

/** 状态条文案：有工具在跑报工作类别，否则「正在分析请求」（Mac `activityThinking`）。 */
fun runningStatusLabel(nodes: List<ChatNode>): String =
    liveToolName(nodes)?.let(::liveActivity) ?: Strings.activityThinking

/**
 * 状态条的用时：从本轮 turn/start（[startTime]，毫秒）起算，下限 1 秒（web/Mac 同口径）；
 * 拿不到 turn/start 时为 null，状态条只显示文案。
 */
fun runningStatusElapsed(startTime: Double?, now: Double): String? =
    startTime?.let { formatDuration(maxOf(1000.0, now - it)) }

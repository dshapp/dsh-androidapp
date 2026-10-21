package ai.deepseek.dshphone.model

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import ai.deepseek.dshphone.net.DshJson
import ai.deepseek.dshphone.net.MessageId
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.net.arr
import ai.deepseek.dshphone.net.bool
import ai.deepseek.dshphone.net.get
import ai.deepseek.dshphone.net.int
import ai.deepseek.dshphone.net.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

typealias CallId = String

// 内容块 ——————————————————————————————————————————————————————

sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Reasoning(val text: String) : ContentBlock
    data class Image(val attachment: JsonElement?) : ContentBlock
    data class ToolCall(val id: CallId, val name: String, val arguments: String) : ContentBlock
    data class Other(val raw: JsonElement?) : ContentBlock
}

fun decodeContentBlock(value: JsonElement?): ContentBlock {
    val obj = value as? JsonObject ?: return ContentBlock.Other(value)
    return when (obj["type"].str) {
        "text" -> ContentBlock.Text(obj["text"].str ?: "")
        "reasoning" -> ContentBlock.Reasoning(obj["text"].str ?: "")
        "image" -> ContentBlock.Image(obj["attachment"])
        "tool-call" -> ContentBlock.ToolCall(
            obj["id"].str ?: "",
            obj["name"].str ?: "",
            obj["arguments"].str ?: "",
        )
        else -> ContentBlock.Other(value)
    }
}

fun encodeContentBlock(block: ContentBlock): JsonElement = when (block) {
    is ContentBlock.Text -> buildJsonObject { put("type", "text"); put("text", block.text) }
    is ContentBlock.Reasoning -> buildJsonObject { put("type", "reasoning"); put("text", block.text) }
    is ContentBlock.Image -> buildJsonObject {
        put("type", "image")
        block.attachment?.let { put("attachment", it) }
    }
    is ContentBlock.ToolCall -> buildJsonObject {
        put("type", "tool-call"); put("id", block.id); put("name", block.name); put("arguments", block.arguments)
    }
    is ContentBlock.Other -> block.raw ?: JsonObject(emptyMap())
}

fun plainText(content: List<ContentBlock>): String =
    content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

/** 用户消息 `file` 块里的上传文件引用：`{type:"file", attachment:{attachmentId,name,bytes}}`。 */
data class FileAttachmentRef(val attachmentId: String, val name: String, val bytes: Int)

/** 消息里的上传文件；本端把 file 块留作 [ContentBlock.Other]，在这里认出来。 */
fun fileAttachmentRefs(content: List<ContentBlock>): List<FileAttachmentRef> = content.mapNotNull { block ->
    val raw = (block as? ContentBlock.Other)?.raw
    if (raw["type"].str != "file") return@mapNotNull null
    val attachment = raw["attachment"]
    FileAttachmentRef(
        attachmentId = attachment["attachmentId"].str ?: return@mapNotNull null,
        name = attachment["name"].str ?: return@mapNotNull null,
        bytes = attachment["bytes"].int ?: 0,
    )
}

// 助手块 ——————————————————————————————————————————————————————

sealed interface AssistantBlock {
    data class Text(val text: String) : AssistantBlock
    data class Reasoning(val text: String) : AssistantBlock
    data class Image(val attachment: JsonElement?) : AssistantBlock
    data class ToolCall(val callId: CallId, val name: String, val argsRaw: String) : AssistantBlock
    data class Other(val raw: JsonElement?) : AssistantBlock
}

fun toAssistantBlock(block: ContentBlock): AssistantBlock = when (block) {
    is ContentBlock.Text -> AssistantBlock.Text(block.text)
    is ContentBlock.Reasoning -> AssistantBlock.Reasoning(block.text)
    is ContentBlock.Image -> AssistantBlock.Image(block.attachment)
    is ContentBlock.ToolCall -> AssistantBlock.ToolCall(block.id, block.name, block.arguments)
    is ContentBlock.Other -> AssistantBlock.Other(block.raw)
}

// 轮次过程折叠 ————————————————————————————————————————————————

/**
 * 一轮结束后的折叠头（Mac `TurnProcessSpec` / web `TurnProcessNodeView`）：每个已结束的轮次
 * 都有一个，文案说明这一轮怎么结束（「已完成，用时 1分14秒」/「已停止」/「处理失败」）。
 * 可折叠时（web `canCollapse`）：有最终回答时折起回答之前的过程；没有最终回答
 * （如以工具调用结束）时整轮都是过程，一直折到 turn/end。插话、停止、失败的轮不折，
 * 折叠头只陈述结局。
 */
data class TurnProcessSpec(
    val turn: Int,
    val processStartSeq: Double,
    /**
     * 折叠区间的开区间终点：最终回答的锚点；没有最终回答时是 turn/end 之后一点。
     * 不可折叠（停止、失败、插话、无可折内容）时为 null。
     */
    val foldEndSeq: Double?,
    val answerStep: Int,
    val inlineReasoning: Boolean,
    val outcome: TurnOutcome = TurnOutcome.COMPLETED,
    /** turn/end − turn/start；只有正常完成且载到了 turn/start 时才有。 */
    val durationMs: Double? = null,
) {
    data class Key(val turn: Int, val answerStep: Int)

    val key: Key get() = Key(turn, answerStep)
    val foldable: Boolean get() = foldEndSeq != null
    fun covers(anchor: Double): Boolean {
        val end = foldEndSeq ?: return false
        return anchor >= processStartSeq && anchor < end
    }
}

/**
 * 折叠头的文案就是这一轮的结局（Mac `TurnProcessSpec.label`）：完成且有用时 →
 * 「已完成，用时 35分53秒」（下限 1 秒）；完成但没有用时 →「已完成」；停止 →「已停止」；
 * 失败 →「处理失败」。
 */
val TurnProcessSpec.label: String
    get() = when (outcome) {
        TurnOutcome.STOPPED -> Strings.turnProcessStopped
        TurnOutcome.FAILED -> Strings.turnProcessFailed
        TurnOutcome.COMPLETED -> durationMs
            ?.let { fmt(Strings.turnProcessTook, formatDuration(maxOf(1000.0, it))) }
            ?: Strings.turnProcessCompleted
    }

/** 出厂的子智能体委派工具（send_message / list_agents 这类控制工具算普通调用）。 */
fun isSubagentDelegationTool(name: String): Boolean = name == "subagent" || name.startsWith("subagent_")

// 助手步骤 ————————————————————————————————————————————————————

enum class AssistantStatus { RUNNING, SETTLED, INTERRUPTED }

@Serializable
data class TokenUsage(
    val inputTokens: Int? = null,
    val uncachedInputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val reasoningTokens: Int? = null,
)

data class AssistantStepData(
    val turn: Int,
    val step: Int,
    var startSeq: Double,
    var time: Double,
    var firstVisibleSeq: Double? = null,
    var settledSeq: Int? = null,
    var status: AssistantStatus = AssistantStatus.RUNNING,
    var blocks: MutableList<AssistantBlock?> = mutableListOf(),
    var usage: TokenUsage? = null,
    var messageId: MessageId? = null,
    var provider: String? = null,
    var model: String? = null,
    var interrupted: Boolean = false,
    var stepStartTime: Double? = null,
    var firstTokenTime: Double? = null,
) {
    fun copyState(): AssistantStepData = copy(blocks = blocks.toMutableList())

    val hasVisibleContent: Boolean
        get() = blocks.any { block ->
            when (block) {
                is AssistantBlock.Text -> block.text.isNotEmpty()
                is AssistantBlock.Reasoning -> block.text.isNotEmpty()
                is AssistantBlock.ToolCall -> block.callId.isNotEmpty()
                is AssistantBlock.Image, is AssistantBlock.Other -> true
                null -> false
            }
        }

    /** 被打断的步骤即使没有可见内容也要出现：那一行本身就是"已停止"的证据。 */
    val isVisible: Boolean get() = hasVisibleContent || interrupted
}

// 工具调用 ————————————————————————————————————————————————————

@Serializable
data class ToolError(val name: String = "", val code: String = "", val reason: String? = null)

data class ToolResultData(
    val seq: Int,
    val time: Double,
    val content: List<ContentBlock>,
    val isError: Boolean,
    val error: ToolError? = null,
    val meta: JsonElement? = null,
    val resultView: JsonElement? = null,
)

data class ToolChatData(
    val callId: CallId,
    val name: String,
    val argsRaw: String,
    val turn: Int,
    val step: Int,
    val startSeq: Int,
    val time: Double,
    val rootCallId: CallId? = null,
    val parentCallId: CallId? = null,
    var result: ToolResultData? = null,
    var callView: JsonElement? = null,
    var subCalls: List<ToolChatData> = emptyList(),
    /** Execution directory at dispatch, not the session's later directory. */
    val cwd: String? = null,
    val preparing: Boolean = false,
    val displaySeq: Double? = null,
)

// 工作流 ——————————————————————————————————————————————————————

enum class WorkflowRunStatus { RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

data class WorkflowRunMemberData(
    val seq: Int,
    val label: String,
    val childId: SessionId,
    val status: WorkflowRunStatus,
)

data class WorkflowRunPhaseData(val key: String, val phase: String?, val members: List<WorkflowRunMemberData>)

data class WorkflowRunData(
    val runId: String,
    val name: String,
    val status: WorkflowRunStatus,
    val phases: List<WorkflowRunPhaseData>,
    val anchorSeq: Int,
)

// 其它节点 ————————————————————————————————————————————————————

/**
 * 唤醒这一轮的非人类消息（web `turn-trigger`）：定时任务、后台任务结束、插件、goal 续跑、
 * webhook……单独画成一行通知、点开看详情；能读出提醒的定时任务画成用户气泡。其余非人类上下文一律不进聊天。
 */
data class TurnTriggerNode(
    val seq: Int,
    val time: Double,
    val messageId: MessageId,
    val content: List<ContentBlock>,
    val source: JsonElement?,
) {
    /** 按 `source.kind` 分的触发类别（web `turnTriggerDetails`）；认不出的来源归 [TurnTriggerKind.REQUEST]。 */
    val kind: TurnTriggerKind
        get() = when (source["kind"].str) {
            "goal" -> TurnTriggerKind.GOAL
            "agent-message" -> TurnTriggerKind.AGENT
            "team-message" -> TurnTriggerKind.TEAM
            "subagent-settled" -> TurnTriggerKind.SUBAGENT
            "webhook" -> if (source["provider"].str == "github") TurnTriggerKind.GITHUB else TurnTriggerKind.WEBHOOK
            "schedule" -> TurnTriggerKind.SCHEDULE
            "tool-jobs" -> TurnTriggerKind.JOB
            "cordis-host-runner" -> TurnTriggerKind.PLUGIN
            "user-question-reply" -> TurnTriggerKind.QUESTION_REPLY
            else -> TurnTriggerKind.REQUEST
        }

    /**
     * 迟到的 `ask_user_question` 回答（web `question-reply` 节点）：从消息正文那份
     * `answer_to_pending_question` JSON 里读出「问题 → 答案」；别的消息是 null。
     */
    val questionReply: List<QuestionReplyPair>?
        get() {
            if (source["kind"].str != "user-question-reply") return null
            val text = content.filterIsInstance<ContentBlock.Text>().firstOrNull()?.text ?: return null
            val json = runCatching { DshJson.parseToJsonElement(text) }.getOrNull() ?: return null
            val questions = json["questions"].arr ?: return null
            val answers = json["answers"].arr ?: emptyList()
            return questions.map { question ->
                val id = question["id"].str
                val answer = answers.firstOrNull { it["id"].str == id }
                val values = (answer["selected"].arr ?: emptyList()).mapNotNull { it.str } +
                    listOfNotNull(answer["custom"].str?.takeIf { it.isNotEmpty() })
                QuestionReplyPair(question["question"].str.orEmpty(), values)
            }
        }

    /**
     * 定时任务批次送达的提醒：读正文里 `reminders_json:` 那一行的 JSON 数组；
     * 别的触发或解析不出时为空。
     */
    val scheduleReminders: List<ScheduleReminder>
        get() {
            if (source["kind"].str != "schedule") return emptyList()
            for (block in content) {
                val text = (block as? ContentBlock.Text)?.text ?: continue
                for (line in text.lineSequence()) {
                    if (!line.startsWith(REMINDERS_PREFIX)) continue
                    val list = runCatching { DshJson.parseToJsonElement(line.removePrefix(REMINDERS_PREFIX).trim()) }.getOrNull().arr
                        ?: continue
                    val reminders = list.mapNotNull { item ->
                        val prompt = item["reminder_prompt"].str ?: return@mapNotNull null
                        ScheduleReminder(item["schedule_id"].str.orEmpty(), item["occurrence_at"].str.orEmpty(), prompt)
                    }
                    if (reminders.isNotEmpty()) return reminders
                }
            }
            return emptyList()
        }
}

private const val REMINDERS_PREFIX = "reminders_json:"

/** 定时任务批次里的一条提醒：任务 ID、计划时间（UTC 时间戳）与用户写的任务内容。 */
data class ScheduleReminder(val scheduleId: String, val occurrenceAt: String, val prompt: String)

/** 一道题和它的答案：选中的选项加自填文字；跳过时为空。 */
data class QuestionReplyPair(val question: String, val answers: List<String>)

enum class TurnTriggerKind { REQUEST, GOAL, AGENT, TEAM, SUBAGENT, GITHUB, WEBHOOK, SCHEDULE, JOB, PLUGIN, QUESTION_REPLY }

data class UnknownSurfaceNode(val seq: Int, val time: Double, val type: String, val data: JsonElement?)

/**
 * 工具增减提示（web `ContextInjectionRow` 的工具形态）：下一次请求的工具表与上一次不同时，
 * agent 循环会记一条 `developer/message`，这里只留其中的 tool-addition / tool-removal。
 */
data class ToolChangeNode(val seq: Int, val time: Double, val added: List<String>, val removed: List<String>) {
    /** 只变了一个工具：这一行不需要展开名单。 */
    val single: Boolean get() = added.size + removed.size == 1
}

data class CompactionSummaryNode(
    val compactionId: String,
    val seq: Int,
    val time: Double,
    var summary: String? = null,
    var summaryEventSeq: Int? = null,
    var shadowedItemCount: Int? = null,
    var shadowedTokenCount: Int? = null,
)

enum class RetryState { SCHEDULED, STARTED, CANCELLED }

data class ModelRetryNode(
    val retryId: String,
    val seq: Int,
    val time: Double,
    var retryState: RetryState = RetryState.SCHEDULED,
    var retry: Int = 1,
    var maxRetries: Int? = null,
    var delayMs: Int? = null,
    var failureMessage: String? = null,
    var failureCode: String? = null,
    var provider: String? = null,
)

data class TurnErrorNode(
    val seq: Int,
    val time: Double,
    val turn: Int,
    val step: Int?,
    val message: String,
    val code: String?,
)

data class TurnMaxTokensNode(val seq: Int, val time: Double, val turn: Int, val step: Int?)

data class CommandOutcome(val kind: String, val text: String? = null, val sourceEventSeq: Int? = null)

data class CommandNode(
    val commandId: String,
    val seq: Int,
    val time: Double,
    var name: String? = null,
    var args: String? = null,
    var outcome: CommandOutcome? = null,
)

data class TurnTailData(
    val turn: Int,
    var seq: Int,
    val time: Double,
    var usage: TokenUsage? = null,
    var routes: List<String> = emptyList(),
    var ttftMs: Double? = null,
    var decodeTokensPerSec: Double? = null,
    var closingText: String? = null,
    var messageId: MessageId? = null,
    var runMs: Double? = null,
    var outcome: TurnOutcome? = null,
    var endedAt: Double? = null,
    /** 本轮提交的计划，按调用顺序（web ui-plan 轮尾卡片）。 */
    val plans: List<SubmittedPlan> = emptyList(),
)

enum class TurnOutcome { COMPLETED, STOPPED, FAILED }

// 转写节点 ————————————————————————————————————————————————————

sealed interface ChatNode {
    data class User(val seq: Int, val time: Double, val messageId: MessageId, val content: List<ContentBlock>) : ChatNode
    data class Steering(val seq: Int, val time: Double, val messageId: MessageId, val content: List<ContentBlock>) : ChatNode
    data class TurnTrigger(val data: TurnTriggerNode) : ChatNode
    data class Assistant(val step: AssistantStepData) : ChatNode
    data class Tool(val data: ToolChatData) : ChatNode
    data class Compaction(val data: CompactionSummaryNode) : ChatNode
    data class ModelRetry(val data: ModelRetryNode) : ChatNode
    data class TurnError(val data: TurnErrorNode) : ChatNode
    data class TurnMaxTokens(val data: TurnMaxTokensNode) : ChatNode
    data class Command(val data: CommandNode) : ChatNode
    data class TurnTail(val data: TurnTailData) : ChatNode
    data class Workflow(val data: WorkflowRunData) : ChatNode
    data class TurnProcess(val spec: TurnProcessSpec) : ChatNode
    data class UnknownSurface(val data: UnknownSurfaceNode) : ChatNode
    data class ToolChange(val data: ToolChangeNode) : ChatNode
}

val ChatNode.nodeId: String
    get() = when (this) {
        is ChatNode.User -> "u-$messageId"
        is ChatNode.Steering -> "s-$messageId"
        is ChatNode.TurnTrigger -> "trg-${data.messageId}"
        is ChatNode.Assistant -> "a-${step.turn}:${step.step}"
        is ChatNode.Tool -> "t-${data.callId}"
        is ChatNode.Compaction -> "cmp-${data.compactionId}"
        is ChatNode.ModelRetry -> "rty-${data.retryId}"
        is ChatNode.TurnError -> "err-${data.turn}"
        is ChatNode.TurnMaxTokens -> "mt-${data.turn}"
        is ChatNode.Command -> "cmd-${data.commandId}"
        is ChatNode.TurnTail -> "ttl-${data.turn}"
        is ChatNode.Workflow -> "wf-${data.runId}"
        is ChatNode.TurnProcess -> "tp-${spec.turn}"
        is ChatNode.UnknownSurface -> "unk-${data.seq}"
        is ChatNode.ToolChange -> "tc-${data.seq}"
    }

/** 排序锚点：这一行在转写里的位置。 */
val ChatNode.anchorSeq: Double
    get() = when (this) {
        is ChatNode.User -> seq.toDouble()
        is ChatNode.Steering -> seq.toDouble()
        is ChatNode.TurnTrigger -> data.seq.toDouble()
        is ChatNode.Assistant -> step.settledSeq?.toDouble() ?: step.firstVisibleSeq ?: step.startSeq
        is ChatNode.Tool -> data.displaySeq ?: data.startSeq.toDouble()
        is ChatNode.Compaction -> data.seq.toDouble()
        is ChatNode.ModelRetry -> data.seq.toDouble()
        is ChatNode.TurnError -> data.seq.toDouble()
        is ChatNode.TurnMaxTokens -> data.seq.toDouble() + 0.05
        is ChatNode.Command -> data.seq.toDouble()
        is ChatNode.TurnTail -> data.seq.toDouble() + 0.1
        is ChatNode.Workflow -> data.anchorSeq.toDouble()
        is ChatNode.TurnProcess -> spec.processStartSeq - 0.001
        is ChatNode.UnknownSurface -> data.seq.toDouble()
        is ChatNode.ToolChange -> data.seq.toDouble()
    }

/**
 * turn/start only reserves a footer context; it must not render an early branch/time row.
 * Once turn/end settles, completed turns may report duration without a final message,
 * and stopped/failed turns render their terminal status without successful statistics.
 * Retain messageId as a compatibility fallback for callers constructing legacy nodes.
 */
val ChatNode.rendersRow: Boolean
    get() = when (this) {
        is ChatNode.TurnTail -> data.outcome != null || data.messageId != null
        // 助手 step 只在有可见内容（正文、思考、图片）时成行；工具调用另有自己的行。
        // 首个 token 之前 step 还没有任何块，记录里不画东西也不留空行——此时唯一的
        // 运行提示是输入框上方的状态条（Mac `RunningStatusRow`）。
        is ChatNode.Assistant -> step.blocks.any { block ->
            (block is AssistantBlock.Text && block.text.isNotEmpty()) ||
                block is AssistantBlock.Reasoning || block is AssistantBlock.Image
        }
        else -> true
    }

val ChatNode.turnNumber: Int?
    get() = when (this) {
        is ChatNode.Assistant -> step.turn
        is ChatNode.Tool -> data.turn
        is ChatNode.TurnError -> data.turn
        is ChatNode.TurnMaxTokens -> data.turn
        is ChatNode.TurnTail -> data.turn
        is ChatNode.TurnProcess -> spec.turn
        else -> null
    }

/** 转写内查找用的可检索文本。 */
fun chatNodeSearchText(node: ChatNode): String = when (node) {
    is ChatNode.User -> plainText(node.content)
    is ChatNode.Steering -> plainText(node.content)
    // 定时任务画成用户气泡，查找也按气泡里的任务内容来。
    is ChatNode.TurnTrigger -> node.data.scheduleReminders.takeIf { it.isNotEmpty() }
        ?.joinToString("\n\n") { it.prompt } ?: plainText(node.data.content)
    is ChatNode.Assistant -> node.step.blocks.mapNotNull { block ->
        when (block) {
            is AssistantBlock.Text -> block.text
            is AssistantBlock.Reasoning -> block.text
            else -> null
        }
    }.joinToString("\n")
    is ChatNode.Tool -> node.data.name + " " + node.data.argsRaw
    else -> ""
}

/** 工具结果摊平成可复制文本。 */
fun toolResultText(result: ToolResultData): String {
    val parts = mutableListOf<String>()
    for (block in result.content) {
        if (block is ContentBlock.Text) parts.add(block.text)
        else parts.add(DshJson.encodeToString(JsonElement.serializer(), encodeContentBlock(block)))
    }
    if (parts.isEmpty()) result.error?.let { parts.add(it.reason?.takeIf { reason -> reason.isNotEmpty() } ?: "${it.name}: ${it.code}") }
    return parts.joinToString("\n")
}

fun resultFirstLine(result: ToolResultData): String {
    for (block in result.content) {
        if (block is ContentBlock.Text) {
            if (block.text.isEmpty()) continue
            return firstLine(block.text)
        }
        return firstLine(DshJson.encodeToString(JsonElement.serializer(), encodeContentBlock(block)))
    }
    result.error?.let { return firstLine(it.reason?.takeIf { reason -> reason.isNotEmpty() } ?: "${it.name}: ${it.code}") }
    return ""
}

fun firstLine(text: String): String = text.substringBefore('\n')

fun jsonPrimitiveText(value: JsonElement?): String = (value as? JsonPrimitive)?.content ?: ""

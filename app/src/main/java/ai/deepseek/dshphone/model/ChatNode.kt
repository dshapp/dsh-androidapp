package ai.deepseek.dshphone.model

import ai.deepseek.dshphone.net.DshJson
import ai.deepseek.dshphone.net.MessageId
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.net.arr
import ai.deepseek.dshphone.net.bool
import ai.deepseek.dshphone.net.get
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

data class TurnProcessSpec(
    val turn: Int,
    val processStartSeq: Double,
    val answerAnchorSeq: Double,
    val answerStep: Int,
    val messageCount: Int,
    val toolCallCount: Int,
    val subagentCount: Int,
    val inlineReasoning: Boolean,
) {
    data class Key(val turn: Int, val answerStep: Int)

    val key: Key get() = Key(turn, answerStep)
    fun covers(anchor: Double): Boolean = anchor >= processStartSeq && anchor < answerAnchorSeq
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
data class ToolError(val name: String = "", val code: String = "")

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

data class SystemPromptChatNode(
    val seq: Int,
    val time: Double,
    val anchorSeq: Int,
    val text: String,
    val update: Boolean,
)

data class UnknownSurfaceNode(val seq: Int, val time: Double, val type: String, val data: JsonElement?)

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
)

// 转写节点 ————————————————————————————————————————————————————

sealed interface ChatNode {
    data class User(val seq: Int, val time: Double, val messageId: MessageId, val content: List<ContentBlock>) : ChatNode
    data class Steering(val seq: Int, val time: Double, val messageId: MessageId, val content: List<ContentBlock>) : ChatNode
    data class Context(
        val seq: Int,
        val time: Double,
        val messageId: MessageId,
        val content: List<ContentBlock>,
        val source: JsonElement?,
        val provenanceLabel: String?,
        val provenanceRole: String,
    ) : ChatNode
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
    data class SystemPrompt(val data: SystemPromptChatNode) : ChatNode
    data class UnknownSurface(val data: UnknownSurfaceNode) : ChatNode
}

val ChatNode.nodeId: String
    get() = when (this) {
        is ChatNode.User -> "u-$messageId"
        is ChatNode.Steering -> "s-$messageId"
        is ChatNode.Context -> "c-$messageId"
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
        is ChatNode.SystemPrompt -> "sys-${data.seq}"
        is ChatNode.UnknownSurface -> "unk-${data.seq}"
    }

/** 排序锚点：这一行在转写里的位置。 */
val ChatNode.anchorSeq: Double
    get() = when (this) {
        is ChatNode.User -> seq.toDouble()
        is ChatNode.Steering -> seq.toDouble()
        is ChatNode.Context -> seq.toDouble()
        is ChatNode.Assistant -> step.settledSeq?.toDouble() ?: step.firstVisibleSeq ?: step.startSeq
        is ChatNode.Tool -> data.startSeq.toDouble()
        is ChatNode.Compaction -> data.seq.toDouble()
        is ChatNode.ModelRetry -> data.seq.toDouble()
        is ChatNode.TurnError -> data.seq.toDouble()
        is ChatNode.TurnMaxTokens -> data.seq.toDouble() + 0.05
        is ChatNode.Command -> data.seq.toDouble()
        is ChatNode.TurnTail -> data.seq.toDouble() + 0.1
        is ChatNode.Workflow -> data.anchorSeq.toDouble()
        is ChatNode.TurnProcess -> spec.processStartSeq - 0.001
        is ChatNode.SystemPrompt -> data.anchorSeq.toDouble()
        is ChatNode.UnknownSurface -> data.seq.toDouble()
    }

/**
 * 这一行是不是真的有内容可画。
 *
 * 轮次页脚（复制、分叉、用量、用时、结束时刻）在 turn/start 就建好了上下文，
 * 但它描述的是「这一轮收尾时」的事实：要等 turn/end 把收尾助手消息折进来
 * （messageId 落定）才成立。在此之前它的 seq 还是 turn/start 的 seq，排在
 * 这一轮的最前面——也就是紧贴系统提示卡片（同样锚在 turn/start）下方，于是
 * 一轮刚开始就先冒出一行分叉图标加时刻。
 *
 * Mac 的 `TurnTailRow` 用 `if data.messageId != nil` 把这一行关住，
 * Web 的 `TurnTailNodeView` 在 `closing === null` 时直接返回 null；这里是
 * 同一道关。
 */
val ChatNode.rendersRow: Boolean
    get() = when (this) {
        is ChatNode.TurnTail -> data.messageId != null
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
    is ChatNode.Context -> plainText(node.content)
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
    if (parts.isEmpty()) result.error?.let { parts.add("${it.name}: ${it.code}") }
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
    result.error?.let { return "${it.name}: ${it.code}" }
    return ""
}

fun firstLine(text: String): String = text.substringBefore('\n')

fun jsonPrimitiveText(value: JsonElement?): String = (value as? JsonPrimitive)?.content ?: ""

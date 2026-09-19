package ai.deepseek.dshphone.model

import ai.deepseek.dshphone.net.MessageId
import ai.deepseek.dshphone.net.RpcError
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.net.WorkspaceId
import ai.deepseek.dshphone.net.arr
import ai.deepseek.dshphone.net.bool
import ai.deepseek.dshphone.net.dbl
import ai.deepseek.dshphone.net.decodeOrNull
import ai.deepseek.dshphone.net.get
import ai.deepseek.dshphone.net.int
import ai.deepseek.dshphone.net.obj
import ai.deepseek.dshphone.net.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

// 可应答的审批 / 提问 ——————————————————————————————————————————

data class ApprovalRequested(
    val eventId: String?,
    val sessionId: SessionId,
    val approvalId: String,
    val toolName: String,
    val callId: String? = null,
    val reason: String? = null,
)

enum class ApprovalOutcome(val wire: String) {
    ALLOWED_ONCE("allowed-once"),
    REJECTED("rejected"),
    CANCELLED("cancelled"),
    UNAVAILABLE("unavailable"),
}

@Serializable
data class AskUserQuestionOption(val label: String, val description: String? = null)

@Serializable
data class QuestionIntent(val kind: String, val approve: String? = null)

@Serializable
data class AskUserQuestionItem(
    val id: String,
    val question: String,
    val header: String? = null,
    val detail: String? = null,
    val options: List<AskUserQuestionOption>? = null,
    val multiSelect: Boolean? = null,
    val intent: QuestionIntent? = null,
)

data class QuestionRequested(
    val eventId: String?,
    val sessionId: SessionId,
    val questions: List<AskUserQuestionItem>,
)

data class QuestionAnswer(val id: String, val selected: List<String>, val custom: String? = null)

/** 计划评审（exit_plan_mode）的路由与应答编码，与 iOS/web 完全一致。 */
data class PlanReviewFlow(
    val id: String,
    val question: String,
    val plan: String,
    val approve: AskUserQuestionOption,
    val decline: AskUserQuestionOption?,
) {
    fun approveAnswer() = QuestionAnswer(id, listOf(approve.label), null)

    fun submitAnswer(notes: String): QuestionAnswer =
        if (notes.isBlank()) approveAnswer() else reviseAnswer(notes)

    fun reviseAnswer(notes: String): QuestionAnswer {
        val trimmed = notes.trim()
        if (trimmed.isNotEmpty()) return QuestionAnswer(id, emptyList(), trimmed)
        return QuestionAnswer(id, decline?.let { listOf(it.label) } ?: emptyList(), null)
    }

    companion object {
        fun route(questions: List<AskUserQuestionItem>): PlanReviewFlow? {
            if (questions.size != 1) return null
            val question = questions[0]
            if (question.intent?.kind != "plan-review") return null
            val approveLabel = question.intent.approve ?: return null
            val plan = question.detail ?: return null
            if (question.multiSelect == true) return null
            val options = question.options ?: emptyList()
            if (options.size > 2) return null
            val approve = options.firstOrNull { it.label == approveLabel } ?: return null
            return PlanReviewFlow(
                question.id, question.question, plan, approve,
                options.firstOrNull { it.label != approveLabel },
            )
        }
    }
}

/** 会话停在哪一种请求上（与边栏徽标共用判定）。 */
fun pendingInteractionKind(
    approval: ApprovalRequested?,
    question: QuestionRequested?,
): SessionAwaitingKind? {
    if (approval != null) return SessionAwaitingKind.APPROVAL
    if (question != null) {
        return if (PlanReviewFlow.route(question.questions) != null) SessionAwaitingKind.PLAN_REVIEW
        else SessionAwaitingKind.QUESTION
    }
    return null
}

// 队列 ————————————————————————————————————————————————————————

data class QueuedInboxItem(
    val id: MessageId,
    val placement: String,
    val rpcId: String?,
    val content: List<JsonElement>,
) {
    /** 队列条目的正文（内容块是 wire JSON，取其中的 text 字段）。 */
    val text: String get() = content.mapNotNull { it["text"].str }.joinToString(" ")
}

/**
 * 0.1.5 的 control 队列条目：条目自带 placement，message 里只有 content。
 *
 * 0.1.6 删掉了这条通道（见 [decodeControlFrame]），保留它是为了让客户端仍能连 0.1.5 主机。
 */
fun decodeQueuedItem(value: JsonElement?): QueuedInboxItem? {
    val id = value["id"].str ?: return null
    return QueuedInboxItem(
        id = id,
        placement = value["placement"].str ?: "queued",
        rpcId = value["rpcId"].str,
        content = value["message"]["content"].arr ?: emptyList(),
    )
}

// —— 0.1.6 的 inbox 会话投影 ————————————————————————————————

/**
 * inbox 投影里的一条待发消息（主机侧的整条 user message）。
 *
 * 0.1.5 由 control 流发布的条目只带呈现所需字段；0.1.6 改成投影发布整条消息，
 * 因此 placement 不再由主机给，而是由客户端按「它在哪个列表里」推导（见 [rowsFromInbox]）。
 */
data class QueuedInboxMessage(
    val id: MessageId,
    val role: String?,
    val content: List<JsonElement>,
    val source: JsonElement?,
) {
    /** 浏览器提交的消息在 source 里带着 prompt-RPC 身份，用来退掉本地回声。 */
    val rpcId: String? get() = source?.takeIf { it["kind"].str == "user" }?.let { it["rpcId"].str }
}

/** `inbox` 会话投影的 wire 值：两个有序的待发列表。 */
data class SessionInboxValue(
    val nextTurn: List<QueuedInboxMessage> = emptyList(),
    val nextStep: List<QueuedInboxMessage> = emptyList(),
)

fun decodeInbox(value: JsonElement?): SessionInboxValue? {
    if (value == null || value is JsonNull) return null
    fun messages(key: String): List<QueuedInboxMessage> =
        (value[key].arr ?: emptyList()).mapNotNull { item ->
            val id = item["id"].str ?: return@mapNotNull null
            QueuedInboxMessage(
                id = id,
                role = item["role"].str,
                content = item["content"].arr ?: emptyList(),
                source = item["source"],
            )
        }
    return SessionInboxValue(nextTurn = messages("next-turn"), nextStep = messages("next-step"))
}

/**
 * 把一份 inbox 投影摊成待发队列行，主机顺序在前。
 *
 * next-turn 是「下一轮才会取走」的排队项；next-step 里由用户写的（source.kind == "user"）
 * 是已点插队、等下一个步骤边界送进当前回合的项，其余是上下文注入。与 Mac 的
 * QueuedInboxItem.rows(fromInbox:) 同一口径。
 */
fun rowsFromInbox(value: SessionInboxValue): List<QueuedInboxItem> {
    fun row(message: QueuedInboxMessage, placement: String) = QueuedInboxItem(
        id = message.id,
        placement = placement,
        rpcId = message.rpcId,
        content = message.content,
    )
    val turn = value.nextTurn.map { row(it, "queued") }
    val step = value.nextStep.map { row(it, if (it.source?.get("kind")?.str == "user") "steering" else "context") }
    return turn + step
}

data class SessionQueueFrame(val sessionId: SessionId, val items: List<QueuedInboxItem>)

data class SessionJobsFrame(val sessionId: SessionId, val jobs: List<JobView>)

data class SessionProjectionFrame(val sessionId: SessionId, val key: String, val value: JsonElement, val seq: Int)

// $events 流 ——————————————————————————————————————————————————

sealed interface RemoteEventFrame {
    data class Ready(val clientId: String, val home: String?) : RemoteEventFrame
    data class Emit(val event: String, val args: List<JsonElement>) : RemoteEventFrame
    data class Waterfall(
        val event: String,
        val eventId: String,
        val agentId: String,
        val request: JsonElement?,
    ) : RemoteEventFrame
    data class Cancel(val eventId: String) : RemoteEventFrame
    data object Unknown : RemoteEventFrame
}

fun decodeRemoteEventFrame(value: JsonElement?): RemoteEventFrame = when (value["type"].str) {
    "ready" -> RemoteEventFrame.Ready(value["clientId"].str ?: "", value["host"]["home"].str)
    "emit" -> RemoteEventFrame.Emit(value["event"].str ?: "", value["args"].arr ?: emptyList())
    "waterfall" -> RemoteEventFrame.Waterfall(
        value["event"].str ?: "",
        value["eventId"].str ?: "",
        value["agentId"].str ?: "",
        value["request"],
    )
    "cancel" -> RemoteEventFrame.Cancel(value["eventId"].str ?: "")
    else -> RemoteEventFrame.Unknown
}

// workspace/follow 流 ————————————————————————————————————————

sealed interface WorkspaceFollowFrame {
    data class Baseline(val items: List<WorkspaceView>, val archivedSessionIds: List<SessionId>) : WorkspaceFollowFrame
    data class Upsert(val workspace: WorkspaceView) : WorkspaceFollowFrame
    data class Remove(val workspaceId: WorkspaceId) : WorkspaceFollowFrame
    data class Order(val workspaceIds: List<WorkspaceId>) : WorkspaceFollowFrame
    data class Archived(val archivedSessionIds: List<SessionId>) : WorkspaceFollowFrame
    data object Unknown : WorkspaceFollowFrame
}

fun decodeWorkspaceFrame(value: JsonElement?): WorkspaceFollowFrame = when (value["type"].str) {
    "baseline" -> WorkspaceFollowFrame.Baseline(
        (value["value"]["items"].arr ?: emptyList()).mapNotNull { it.decodeOrNull<WorkspaceView>() },
        (value["value"]["archivedSessionIds"].arr ?: emptyList()).mapNotNull { it.str },
    )
    "upsert" -> value["workspace"]?.decodeOrNull<WorkspaceView>()
        ?.let { WorkspaceFollowFrame.Upsert(it) } ?: WorkspaceFollowFrame.Unknown
    "remove" -> WorkspaceFollowFrame.Remove(value["workspaceId"].str ?: "")
    "order" -> WorkspaceFollowFrame.Order((value["workspaceIds"].arr ?: emptyList()).mapNotNull { it.str })
    "archived" -> WorkspaceFollowFrame.Archived((value["archivedSessionIds"].arr ?: emptyList()).mapNotNull { it.str })
    else -> WorkspaceFollowFrame.Unknown
}

// session/control 流 ——————————————————————————————————————————

sealed interface SessionControlFrame {
    data class Baseline(
        val queues: Map<SessionId, List<QueuedInboxItem>>,
        val jobs: Map<SessionId, List<JobView>>,
        val projections: Map<SessionId, SessionProjectionsBlock>,
    ) : SessionControlFrame
    data class Queue(val frame: SessionQueueFrame) : SessionControlFrame
    data class Jobs(val frame: SessionJobsFrame) : SessionControlFrame
    data class Projection(val frame: SessionProjectionFrame) : SessionControlFrame
    data object Unknown : SessionControlFrame
}

fun decodeControlFrame(value: JsonElement?): SessionControlFrame = when (value["type"].str) {
    "baseline" -> {
        val v = value["value"]
        SessionControlFrame.Baseline(
            queues = (v["queues"].obj ?: emptyMap()).mapValues { (_, items) ->
                (items.arr ?: emptyList()).mapNotNull { decodeQueuedItem(it) }
            },
            jobs = (v["jobs"].obj ?: emptyMap()).mapValues { (_, items) ->
                (items.arr ?: emptyList()).mapNotNull { it.decodeOrNull<JobView>() }
            },
            projections = (v["projections"].obj ?: emptyMap()).mapValues { (_, block) ->
                block.decodeOrNull<SessionProjectionsBlock>() ?: SessionProjectionsBlock()
            },
        )
    }
    "queue" -> SessionControlFrame.Queue(
        SessionQueueFrame(
            value["sessionId"].str ?: "",
            (value["items"].arr ?: emptyList()).mapNotNull { decodeQueuedItem(it) },
        )
    )
    "jobs" -> SessionControlFrame.Jobs(
        SessionJobsFrame(
            value["sessionId"].str ?: "",
            (value["jobs"].arr ?: emptyList()).mapNotNull { it.decodeOrNull<JobView>() },
        )
    )
    "projection" -> SessionControlFrame.Projection(
        SessionProjectionFrame(
            value["sessionId"].str ?: "",
            value["key"].str ?: "",
            value["value"] ?: JsonObject(emptyMap()),
            value["seq"].int ?: 0,
        )
    )
    else -> SessionControlFrame.Unknown
}

// session/follow 流 ——————————————————————————————————————————

sealed interface AssistantStreamFrame {
    val attemptId: String

    data class Start(
        override val attemptId: String,
        val revision: Int,
        val startedAfterSeq: Int,
        val turn: Int,
        val step: Int,
    ) : AssistantStreamFrame

    data class Chunk(
        override val attemptId: String,
        val revision: Int,
        val index: Int,
        val time: Double,
        val chunk: JsonElement?,
    ) : AssistantStreamFrame

    data class End(
        override val attemptId: String,
        val revision: Int,
        val index: Int,
        val committedSeq: Int?,
    ) : AssistantStreamFrame
}

data class AssistantStreamAttempt(
    val attemptId: String,
    val startedAfterSeq: Int,
    val turn: Int,
    val step: Int,
    val nextIndex: Int,
    val stream: JsonElement?,
)

data class AssistantStreamBaseline(val revision: Int, val activeAttempt: AssistantStreamAttempt?)

sealed interface SessionFollowFrame {
    data class Snapshot(
        val cursor: Int,
        val records: List<HistoryEntry>,
        val hasMore: Boolean,
        val projections: SessionProjectionsBlock?,
        val assistantStream: AssistantStreamBaseline?,
    ) : SessionFollowFrame

    data class Event(val entry: HistoryEntry) : SessionFollowFrame
    data class Stream(val frame: AssistantStreamFrame) : SessionFollowFrame
    data object Unknown : SessionFollowFrame
}

fun decodeFollowFrame(value: JsonElement?): SessionFollowFrame = when (value["type"].str) {
    "snapshot" -> SessionFollowFrame.Snapshot(
        cursor = value["cursor"].int ?: 0,
        records = (value["records"].arr ?: emptyList()).mapNotNull { it.decodeOrNull<HistoryEntry>() },
        hasMore = value["hasMore"].bool ?: false,
        projections = value["projections"]?.decodeOrNull<SessionProjectionsBlock>(),
        assistantStream = value["assistantStream"]?.let { decodeAssistantBaseline(it) },
    )
    "assistant-stream" -> decodeAssistantStreamFrame(value["frame"])
        ?.let { SessionFollowFrame.Stream(it) } ?: SessionFollowFrame.Unknown
    else -> value?.decodeOrNull<HistoryEntry>()
        ?.let { SessionFollowFrame.Event(it) } ?: SessionFollowFrame.Unknown
}

private fun decodeAssistantBaseline(value: JsonElement?): AssistantStreamBaseline {
    val attempt = value["activeAttempt"]?.let { a ->
        val id = a["attemptId"].str ?: return@let null
        AssistantStreamAttempt(
            attemptId = id,
            startedAfterSeq = a["startedAfterSeq"].int ?: 0,
            turn = a["turn"].int ?: 0,
            step = a["step"].int ?: 0,
            nextIndex = a["nextIndex"].int ?: 0,
            stream = a["stream"],
        )
    }
    return AssistantStreamBaseline(value["revision"].int ?: 0, attempt)
}

fun decodeAssistantStreamFrame(value: JsonElement?): AssistantStreamFrame? {
    val attemptId = value["attemptId"].str ?: return null
    val revision = value["revision"].int ?: 0
    return when (value["type"].str) {
        "start" -> AssistantStreamFrame.Start(
            attemptId, revision,
            value["startedAfterSeq"].int ?: 0,
            value["turn"].int ?: 0,
            value["step"].int ?: 0,
        )
        "chunk" -> AssistantStreamFrame.Chunk(
            attemptId, revision,
            value["index"].int ?: 0,
            value["time"].dbl ?: 0.0,
            value["chunk"],
        )
        "end" -> {
            val outcome = value["outcome"]
            val committed = if (outcome["kind"].str == "committed") outcome["seq"].int else null
            AssistantStreamFrame.End(attemptId, revision, value["index"].int ?: 0, committed)
        }
        else -> null
    }
}

/** 主机在设置里推送的连接错误。 */
data class StreamFailure(val streamId: String, val error: RpcError)

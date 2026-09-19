package ai.deepseek.dshphone.state

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 转写投影里一个上下文槽的状态。 */
private sealed interface NodeContext {
    data class Finalized(val node: ChatNode) : NodeContext
    data class Assistant(val state: AssistantStepData) : NodeContext
    data class Tool(val data: ToolChatData) : NodeContext
    data class Compaction(val node: CompactionSummaryNode) : NodeContext
    data class Retry(val node: ModelRetryNode) : NodeContext
    data class TurnErrorPending(val pending: PendingTurnError) : NodeContext
    data class MaxTokens(val node: TurnMaxTokensNode) : NodeContext
    data class Command(val node: CommandNode) : NodeContext
    data class Tail(val data: TurnTailData) : NodeContext
    data class Workflow(val state: WorkflowState) : NodeContext
}

private data class PendingTurnError(
    val turn: Int,
    val startSeq: Int,
    val time: Double,
    var seq: Int? = null,
    var message: String? = null,
    var code: String? = null,
)

private data class WorkflowMemberState(
    val seq: Int,
    val label: String,
    val phase: String?,
    val childId: SessionId,
    var outcome: String? = null,
)

private data class WorkflowState(
    val runId: String,
    val name: String,
    val anchorSeq: Int,
    var stopReason: String? = null,
    val members: MutableList<WorkflowMemberState> = mutableListOf(),
)

data class TodoItem(val id: String, val content: String, val status: String, val priority: String? = null)

data class GoalProjection(
    val id: String,
    val revision: Int,
    val objective: String,
    val phase: String,
    val maxGoalRounds: Int? = null,
    val blockedMessage: String? = null,
)

data class SessionStatsProjection(
    val turns: Int = 0,
    val steps: Int = 0,
    val llmMs: Double = 0.0,
    val toolMs: Double = 0.0,
    val ttftMs: Double = 0.0,
    val ttftSteps: Int = 0,
    val decodeMs: Double = 0.0,
    val decodeTokens: Int = 0,
)

data class ContextOccupancy(val percent: Int, val usedTokens: Double, val contextWindow: Double)

data class HistoryError(val code: String, val message: String)

/**
 * 把 SessionEvent 重放成 ChatNode，并维护会话级投影。
 * 与 iOS 的 SessionStore 同一套分组与折叠规则。
 */
class SessionStore(val sessionId: SessionId) {

    // —— 可观察状态 ————————————————————————————————————————————

    var nodes by mutableStateOf<List<ChatNode>>(emptyList())
        private set
    var hasMore by mutableStateOf(true)
    var loadingHistory by mutableStateOf(false)
    var historyLoaded by mutableStateOf(false)
        private set
    var historyError by mutableStateOf<HistoryError?>(null)
    var lastSeq by mutableStateOf(-1)
    var running by mutableStateOf(false)
    var blank by mutableStateOf(true)
    var title by mutableStateOf("")
    var cwd by mutableStateOf<String?>(null)
    var agentPreset by mutableStateOf<String?>(null)

    var pendingApproval by mutableStateOf<ApprovalRequested?>(null)
        private set
    var pendingQuestion by mutableStateOf<QuestionRequested?>(null)
        private set
    var queue by mutableStateOf<List<QueuedInboxItem>>(emptyList())
        private set
    /**
     * 队列条只吃这一份：主机的 next-turn 项，也就是下一轮才会被取走的消息。
     *
     * `queue` 里还混着 next-step 的项（placement == "steering"）：那些是已经点了
     * "插队发送"、正等着在下一个步骤边界送进当前回合的消息。它们不属于"排队"，
     * 画在队列条里就会和排队项长得一模一样（见 ComposerBar.QueueStrip 的说明）。
     * 与 web 的 queue filter / Mac 的 QueueDock.queued / Mac 手机端 PhoneQueueStrip 同口径。
     */
    val queued: List<QueuedInboxItem> get() = queue.filter { it.placement == "queued" }

    /** 已插队、等主机在下一个步骤边界取走的消息；画在转写尾部（对齐 web 的 PendingSteeringBubble）。 */
    val steering: List<QueuedInboxItem> get() = queue.filter { it.placement == "steering" }

    var jobs by mutableStateOf<List<JobView>>(emptyList())
        private set

    var todos by mutableStateOf<List<TodoItem>>(emptyList())
        private set
    var goal by mutableStateOf<GoalProjection?>(null)
        private set
    var tokenUsage by mutableStateOf<TokenUsage?>(null)
        private set
    var sessionStats by mutableStateOf<SessionStatsProjection?>(null)
        private set
    var imageLimits by mutableStateOf<ImageAttachmentLimits?>(null)
        private set
    /** `permissions` 投影（0.1.6 只带 currentValue，0.1.5 还内联候选）。 */
    private var permissionProjection by mutableStateOf<PermissionSelectValue?>(null)
    /** `permissionPresets/catalog` 拉到之前的候选是空的，退回投影内联的那份。 */
    private var permissionCatalogOptions: List<PermissionPresetOption>? = null
    var permissions by mutableStateOf<PermissionSelection?>(null)
        private set
    var modelDirectory by mutableStateOf(ModelDirectoryState())
    var turnRail by mutableStateOf<List<TurnRailItem>>(emptyList())
        private set
    var deliverablesRevision by mutableStateOf(0)
        private set

    var subagentAddress by mutableStateOf<SubagentAddress?>(null)
        private set
    var parentAvailable by mutableStateOf(false)
        private set

    var lastTurnErrored by mutableStateOf(false)
        private set
    private var lastErroredTurn: Int? = null
    private var seenErroredTurn: Int? = null
    val unreadError: Boolean get() = lastTurnErrored && lastErroredTurn != seenErroredTurn

    var subscribedLastSeq: Int? = null
        private set

    // —— 内部状态 ——————————————————————————————————————————————

    private val projections = mutableMapOf<String, Pair<JsonElement, Int>>()
    private val projectionHistory = mutableMapOf<String, MutableMap<Int, JsonElement>>()
    private val contexts = LinkedHashMap<String, NodeContext>()
    private var eventWindow = SessionEventWindow()
    private val inboxByTarget = mutableMapOf<String, MutableList<MessageId>>()
    private val claimedSteeringIds = mutableSetOf<MessageId>()
    private var currentTurn = 0
    private val activeSteps = mutableSetOf<Int>()
    private val stepStartTimes = mutableMapOf<String, Double>()
    private val turnStartSeqs = mutableMapOf<Int, Int>()
    private val stepStartSeqs = mutableMapOf<String, Int>()
    private var sawSystemPrompt = false
    private val turnEndBoundaries = mutableListOf<Pair<Int, Double>>()
    private val presentedByTurn = mutableMapOf<Int, MutableList<PresentedFile>>()
    /** 每轮最新的 `workspace/changes` 公告 seq（见 [changesAddress]）。 */
    private val changesSeqByTurn = mutableMapOf<Int, Int>()
    private val producedPathsByTurn = mutableMapOf<Int, MutableList<String>>()
    private val closingAssistantStepByTurn = mutableMapOf<Int, Int>()
    private val turnAnchorRows = mutableMapOf<Int, String>()
    private val turnPromptAnchors = mutableMapOf<Int, String>()
    private val turnPromptRow = mutableMapOf<Int, Int>()
    private val turnResponseRow = mutableMapOf<Int, Int>()
    private val turnProcessSpecs = mutableMapOf<Int, TurnProcessSpec>()
    private val expandedProcessKeys = mutableSetOf<TurnProcessSpec.Key>()
    private val toolCallRootIndex = mutableMapOf<CallId, Int>()

    private var liveAttemptId: String? = null
    private var liveAttemptTurn = 0
    private var liveAttemptStep = 0
    private var durableCursor = -1
    private var transientInGap = 0

    private var historyCursorHint = -1
    private val liveRepairBuffer = mutableListOf<HistoryEntry>()
    private var gapRepairGeneration = 0
    private var gapRepairTargetSeq: Int? = null
    var repairingGap = false
        private set
    private var droppedTailSeq: Int? = null

    /** 本地已发出、主机尚未回声的消息。 */
    data class PendingSubmission(val requestId: String, val placement: String, val time: Double, val text: String)

    var pendingSubmissions by mutableStateOf<List<PendingSubmission>>(emptyList())
        private set

    val firstSeq: Int? get() = eventWindow.firstSeq
    val historyWindow: List<HistoryEntry> get() = eventWindow.entries

    /**
     * 分页请求的 throughSeq；没有权威游标时是 -1，调用方必须跳过这次请求。
     *
     * 不能把未知游标钳成 0：主机把 throughSeq 读作"截止到这一条事件"，0 只会回来
     * 一条 permission/preset，historyLoaded 却已置真，转写于是闪一下"还没有消息"，
     * 而且那页带回的 hasMore=false 还会顺手关掉"加载更早"。
     */
    val historyCursor: Int
        get() = subscribedLastSeq ?: max(lastSeq, historyCursorHint)

    val repairCursor: Int
        get() = maxOf(
            subscribedLastSeq ?: -1,
            lastSeq,
            liveRepairBuffer.lastOrNull()?.event?.seq ?: -1,
            droppedTailSeq ?: -1,
            0,
        )

    val followAddress: SessionAddress
        get() = subagentAddress?.let { SessionAddress.Subagent(it) } ?: SessionAddress.Session(sessionId)

    val contextOccupancy: ContextOccupancy?
        get() {
            val value = projections["contextPressure"]?.first ?: return null
            val window = value["contextWindow"].dbl ?: return null
            if (window <= 0 || !window.isFinite()) return null
            val used = value["projectedTokens"].dbl ?: value["pressureTokens"].dbl ?: return null
            if (!used.isFinite()) return null
            val percent = min(100.0, max(0.0, (used / window * 100))).roundToInt()
            return ContextOccupancy(percent, used, window)
        }

    /** 折叠后的计划模式目标状态。 */
    val planModeTarget: Boolean?
        get() {
            val value = projections["plan"]?.first ?: return null
            val active = value["active"].bool ?: return null
            val pending = value["pending"].bool ?: false
            return if (pending) !active else active
        }

    val activeGoal: GoalProjection? get() = goal?.takeIf { it.phase != "complete" }

    val goalRef: GoalRef? get() = activeGoal?.let { GoalRef(it.id, it.revision) }

    fun projectionValue(key: String): JsonElement? = projections[key]?.first

    fun producedPaths(turn: Int): List<String> = producedPathsByTurn[turn] ?: emptyList()

    fun presentedFiles(turn: Int): List<PresentedFile> = presentedByTurn[turn] ?: emptyList()

    /** 收尾步骤才带上文件提及词表。 */
    fun closingMentions(turn: Int, step: Int, isRunning: Boolean): List<String> {
        if (isRunning || closingAssistantStepByTurn[turn] != step) return emptyList()
        val produced = producedPathsByTurn[turn] ?: emptyList()
        val presented = presentedByTurn[turn] ?: return produced
        if (presented.isEmpty()) return produced
        val paths = produced.toMutableList()
        for (file in presented) if (!paths.contains(file.path)) paths.add(file.path)
        return paths
    }

    fun toolCall(id: CallId): ToolChatData? {
        val index = toolCallRootIndex[id] ?: return findNestedTool(id)
        val node = nodes.getOrNull(index) as? ChatNode.Tool ?: return findNestedTool(id)
        return node.data
    }

    private fun findNestedTool(id: CallId): ToolChatData? {
        fun search(tool: ToolChatData, depth: Int): ToolChatData? {
            if (tool.callId == id) return tool
            if (depth > 64) return null
            for (sub in tool.subCalls) search(sub, depth + 1)?.let { return it }
            return null
        }
        for (node in nodes) if (node is ChatNode.Tool) search(node.data, 0)?.let { return it }
        return null
    }

    // —— 本地投递回声 ————————————————————————————————————————

    fun beginPendingSubmission(requestId: String, placement: String, text: String) {
        pendingSubmissions = pendingSubmissions + PendingSubmission(
            requestId, placement, System.currentTimeMillis().toDouble(), text,
        )
    }

    fun retirePendingSubmission(requestId: String) {
        pendingSubmissions = pendingSubmissions.filterNot { it.requestId == requestId }
    }

    fun configureSubagent(address: SubagentAddress?, parentAvailable: Boolean) {
        subagentAddress = address
        this.parentAvailable = if (address == null) false else parentAvailable
    }

    fun seedHistoryCursor(seq: Int) {
        if (subscribedLastSeq != null) return
        historyCursorHint = max(historyCursorHint, seq)
    }

    fun markErrorSeen() {
        if (seenErroredTurn != lastErroredTurn) seenErroredTurn = lastErroredTurn
    }

    // —— 事件摄入 ——————————————————————————————————————————————

    fun apply(event: SessionEvent, view: ToolEventView?) {
        val entry = HistoryEntry(event, view)
        if (repairingGap) {
            liveRepairBuffer.add(entry)
            if (liveRepairBuffer.size > MAX_REPAIR_BUFFER) resetWindow()
            return
        }
        val extendsTail = event.seq > (eventWindow.lastSeq ?: Int.MIN_VALUE)
        if (!eventWindow.applyLive(entry)) return
        lastSeq = max(lastSeq, event.seq)
        if (!extendsTail || requiresFullReplay(event)) {
            replayHistory()
            return
        }
        noteTurnBoundary(event)
        applyEvent(event, view)
        rebuildNodes()
    }

    private fun requiresFullReplay(event: SessionEvent): Boolean {
        if (event.surfaceOp != null) return true
        return event.type in setOf(
            "agent/inbox/spliced",
            "compaction/start", "compaction/summary", "compaction/end", "compaction/prune",
        )
    }

    fun noteDroppedLiveEvent(seq: Int) {
        if (!historyLoaded) return
        droppedTailSeq = max(droppedTailSeq ?: seq, seq)
    }

    fun beginGapRepair(entry: HistoryEntry): Int? {
        liveRepairBuffer.add(entry)
        if (repairingGap) return null
        gapRepairGeneration++
        repairingGap = true
        gapRepairTargetSeq = repairCursor
        return gapRepairGeneration
    }

    fun completeGapRepair(value: SessionHistoryValue, generation: Int) {
        if (!repairingGap || generation != gapRepairGeneration) return
        val buffered = liveRepairBuffer.toList()
        val target = gapRepairTargetSeq
        gapRepairTargetSeq = null
        if ((value.events.lastOrNull()?.event?.seq ?: -1) < (target ?: -1)) {
            repairingGap = false
            droppedTailSeq = max(droppedTailSeq ?: -1, target ?: -1)
            return
        }
        liveRepairBuffer.clear()
        repairingGap = false
        droppedTailSeq = null
        eventWindow.repair(value, buffered)
        historyLoaded = true
        hasMore = eventWindow.hasMore
        value.projections?.let { block ->
            for ((key, projectionValue) in block.values) recordProjectionVersion(projectionValue, key, block.asOfSeq)
        }
        lastSeq = max(subscribedLastSeq ?: -1, eventWindow.lastSeq ?: -1)
        rebuildProjections(lastSeq)
        replayHistory()
    }

    fun failGapRepair(generation: Int) {
        if (!repairingGap || generation != gapRepairGeneration) return
        repairingGap = false
        if (liveRepairBuffer.size > MAX_REPAIR_BUFFER) resetWindow()
    }

    fun loadHistory(value: SessionHistoryValue, replacing: Boolean) {
        if (replacing && repairingGap) {
            gapRepairGeneration++
            liveRepairBuffer.clear()
            repairingGap = false
        }
        historyLoaded = true
        val pageTail = value.events.lastOrNull()?.event?.seq ?: -1
        val liveTailBeyondPage = (eventWindow.lastSeq ?: -1) > pageTail
        if (replacing && !liveTailBeyondPage) {
            droppedTailSeq = null
            eventWindow.replace(value)
        } else {
            eventWindow.prepend(value)
        }
        hasMore = eventWindow.hasMore
        value.projections?.let { block ->
            lastSeq = max(lastSeq, block.asOfSeq)
            for ((key, projectionValue) in block.values) recordProjectionVersion(projectionValue, key, block.asOfSeq)
            rebuildProjections(lastSeq)
        }
        replayHistory()
    }

    /**
     * 把本机缓存里的窗口铺出来，供点开会话时立刻看到内容（stale-while-revalidate）。
     *
     * 只是一份"先看着"的底稿：不设 historyLoaded、也不动 lastSeq/historyCursor，
     * 所以它不参与任何裁切与去重判定；follow 的 snapshot 回来照样 replacing 整体替换，
     * 已有实时内容或已加载过的窗口也不会被它盖掉。
     */
    fun seedFromCache(window: CachedWindow) {
        if (historyLoaded || eventWindow.entries.isNotEmpty() || window.records.isEmpty()) return
        eventWindow.replace(SessionHistoryValue(window.records, window.hasMore, null))
        replayHistory()
    }

    fun subscribe(authoritativeLastSeq: Int) {
        subscribedLastSeq = authoritativeLastSeq
        align(authoritativeLastSeq)
    }

    fun align(authoritativeLastSeq: Int) {
        val needsTruncate = authoritativeLastSeq < lastSeq ||
            (eventWindow.lastSeq?.let { it > authoritativeLastSeq } == true) ||
            projections.values.any { it.second > authoritativeLastSeq }
        if (!needsTruncate) {
            lastSeq = authoritativeLastSeq
            return
        }
        eventWindow.truncate(authoritativeLastSeq)
        for (key in projectionHistory.keys.toList()) {
            val versions = projectionHistory[key] ?: continue
            versions.keys.filter { it > authoritativeLastSeq }.forEach { versions.remove(it) }
            if (versions.isEmpty()) projectionHistory.remove(key)
        }
        rebuildProjections(authoritativeLastSeq)
        queue = emptyList()
        jobs = emptyList()
        pendingApproval = null
        pendingQuestion = null
        lastSeq = authoritativeLastSeq
        replayHistory()
        lastSeq = authoritativeLastSeq
    }

    fun resetWindow() {
        gapRepairGeneration++
        eventWindow = SessionEventWindow()
        lastSeq = -1
        subscribedLastSeq = null
        liveRepairBuffer.clear()
        repairingGap = false
        droppedTailSeq = null
        nodes = emptyList()
        hasMore = true
        historyLoaded = false
        replayHistory()
    }

    private fun nextLiveChunkSeq(): Double {
        transientInGap += 1
        return (durableCursor + 1).toDouble() - 1.0 / (transientInGap + 1)
    }

    private fun replayHistory() {
        contexts.clear()
        durableCursor = -1
        transientInGap = 0
        presentedByTurn.clear()
        inboxByTarget.clear()
        claimedSteeringIds.clear()
        stepStartTimes.clear()
        turnStartSeqs.clear()
        stepStartSeqs.clear()
        sawSystemPrompt = false
        turnEndBoundaries.clear()
        currentTurn = 0
        activeSteps.clear()
        var highestSeq = lastSeq
        for (entry in eventWindow.entries) {
            highestSeq = max(highestSeq, entry.event.seq)
            noteTurnBoundary(entry.event)
            applyEvent(entry.event, entry.view)
        }
        lastSeq = highestSeq
        rebuildNodes()
    }

    private fun noteTurnBoundary(event: SessionEvent) {
        if (event.type != "turn/end") return
        if (turnEndBoundaries.any { it.first == event.seq }) return
        turnEndBoundaries.add(event.seq to event.time)
        turnEndBoundaries.sortBy { it.first }
    }

    private fun applyEvent(event: SessionEvent, view: ToolEventView?) {
        if (event.seq > durableCursor) durableCursor = event.seq
        transientInGap = 0
        when (event.type) {
            "agent/inbox/spliced" -> applyInboxSplice(event)
            "user/message" -> applyUserMessage(event)
            "assistant/message" -> applyAssistantMessage(event)
            "assistant/attempt" -> applyAssistantAttempt(event)
            "tool/call" -> applyToolCall(event, view)
            "tool/result" -> applyToolResult(event, view)
            // PTC 子调用：0.1.6 起主机把 code-dispatch 改名成 ptc-dispatch，
            // 旧日志里的 code-dispatch 仍然要能解（Web 端两个都收）。
            "tool/ptc-dispatch-start", "tool/code-dispatch-start" -> applySubcallStart(event)
            "tool/ptc-dispatch", "tool/code-dispatch" -> applySubcallEnd(event)
            "turn/start" -> applyTurnStart(event)
            "turn/end" -> applyTurnEnd(event)
            "step/start" -> applyStepStart(event)
            "step/end" -> applyStepEnd(event)
            "compaction/start", "compaction/summary", "compaction/end", "compaction/prune" -> applyCompaction(event)
            "llm/retry", "llm/retry-started" -> applyRetry(event)
            "command/run", "command/done" -> applyCommand(event)
            "tool-workflow/run-start", "tool-workflow/agent-start",
            "tool-workflow/agent-end", "tool-workflow/run-end" -> applyWorkflow(event)
            "session/title" -> event.data["title"].str?.let { title = it }
            "deliverables/presented" -> applyPresented(event)
            // 只带轮次，本身不画任何东西：汇总与 diff 要按这条事件的 seq 去主机读。
            "workspace/changes" -> applyWorkspaceChanges(event)
            "system/message" -> applySystemPrompt(event)
            else -> applyUnknownSurface(event)
        }
    }

    private fun isAppendSurface(event: SessionEvent): Boolean {
        val op = event.surfaceOp ?: return false
        if (op.str == "append") return true
        return op["op"].str == "append"
    }

    private fun isReplacementSurface(event: SessionEvent): Boolean = event.surfaceOp["op"].str == "replace"

    private fun applySystemPrompt(event: SessionEvent) {
        if (!isAppendSurface(event)) return
        val content = event.data["message"]["content"].arr ?: return
        val text = content.filter { it["type"].str == "text" }.mapNotNull { it["text"].str }.joinToString("")
        if (text.isEmpty()) return
        val update = sawSystemPrompt
        sawSystemPrompt = true
        val turn = event.data["turn"].int
        val step = event.data["step"].int
        contexts["system-prompt:${event.seq}"] = NodeContext.Finalized(
            ChatNode.SystemPrompt(
                SystemPromptChatNode(
                    seq = event.seq,
                    time = event.time,
                    anchorSeq = if (update) event.seq else systemPromptAnchor(event, turn, step),
                    text = text,
                    update = update,
                )
            )
        )
    }

    private fun systemPromptAnchor(event: SessionEvent, turn: Int?, step: Int?): Int {
        if (turn == null || step == null) return event.seq
        if (step == 1) turnStartSeqs[turn]?.let { return it }
        return stepStartSeqs["$turn:$step"] ?: turnStartSeqs[turn] ?: event.seq
    }

    private fun applyUnknownSurface(event: SessionEvent) {
        if (!isAppendSurface(event)) return
        contexts["unknown-surface:${event.seq}"] = NodeContext.Finalized(
            ChatNode.UnknownSurface(UnknownSurfaceNode(event.seq, event.time, event.type, event.data))
        )
    }

    private fun applyInboxSplice(event: SessionEvent) {
        val data = event.data ?: return
        val target = data["target"].str ?: return
        val start = data["start"].int ?: return
        val inserted = data["inserted"].arr ?: return
        val pending = inboxByTarget.getOrPut(target) { mutableListOf() }
        val boundedStart = min(max(start, 0), pending.size)
        val requested = max(data["removedCount"].int ?: 0, 0)
        val removalCount = min(requested, pending.size - boundedStart)
        val removed = pending.subList(boundedStart, boundedStart + removalCount).toList()
        val insertedIds = inserted.mapNotNull { it["id"].str }
        repeat(removalCount) { pending.removeAt(boundedStart) }
        pending.addAll(boundedStart, insertedIds)
        claimedSteeringIds.removeAll(insertedIds.toSet())
        if (target == "next-step" && data["outcome"].str != "canceled") claimedSteeringIds.addAll(removed)
    }

    private fun applyUserMessage(event: SessionEvent) {
        val data = event.data ?: return
        val id = data["id"].str ?: return
        val content = (data["content"].arr ?: emptyList()).map { decodeContentBlock(it) }
        val source = data["source"]
        source["rpcId"].str?.let { retirePendingSubmission(it) }
        if (isReplacementSurface(event)) return
        if (source["plugin"].str == "compact") return
        val sourceKind = source["kind"].str ?: "user"
        val kind = when (sourceKind) {
            "user" -> if (claimedSteeringIds.contains(id)) "steering" else "user"
            else -> "context"
        }
        val node: ChatNode = when (kind) {
            "steering" -> ChatNode.Steering(event.seq, event.time, id, content)
            "context" -> {
                val provenance = contextProvenance(source)
                ChatNode.Context(event.seq, event.time, id, content, source, provenance.second, provenance.first)
            }
            else -> ChatNode.User(event.seq, event.time, id, content)
        }
        contexts["$kind:$id"] = NodeContext.Finalized(node)
    }

    // —— 助手流 ————————————————————————————————————————————————

    private fun assistantStepKey(turn: Int, step: Int) = "assistant-step:$turn:$step"

    private fun applyAssistantLiveChunk(turn: Int, step: Int, seq: Double, time: Double, rawChunk: JsonElement?) {
        val chunkObj = rawChunk as? JsonObject ?: return
        val chunkType = chunkObj["type"].str ?: return
        val key = assistantStepKey(turn, step)
        val existing = (contexts[key] as? NodeContext.Assistant)?.state
        val state = existing?.copyState() ?: AssistantStepData(turn = turn, step = step, startSeq = seq, time = time)
        state.time = time
        if (state.stepStartTime == null) state.stepStartTime = stepStartTimes["$turn:$step"]
        applyStreamChunk(chunkType, chunkObj, seq, state)
        contexts[key] = NodeContext.Assistant(state)
    }

    private fun applyStreamChunk(type: String, chunk: JsonElement, eventSeq: Double, state: AssistantStepData) {
        val index = chunk["index"].int
        when (type) {
            "block-start" -> {
                val slot = index ?: 0
                if (slot < 0 || slot > MAX_BLOCK_INDEX) return
                while (state.blocks.size <= slot) state.blocks.add(null)
                state.blocks[slot] = when (chunk["blockType"].str) {
                    "text" -> AssistantBlock.Text("")
                    "reasoning" -> AssistantBlock.Reasoning("")
                    "tool-call" -> AssistantBlock.ToolCall("", "", "")
                    else -> AssistantBlock.Other(null)
                }
            }
            "text-delta" -> {
                val text = chunk["text"].str ?: ""
                startedSlot(index, state)?.let { slot ->
                    val prev = state.blocks[slot] as? AssistantBlock.Text
                    state.blocks[slot] = AssistantBlock.Text((prev?.text ?: "") + text)
                }
                if (state.firstVisibleSeq == null && text.isNotEmpty()) state.firstVisibleSeq = eventSeq
                stampFirstToken(state, text)
            }
            "reasoning-delta" -> {
                val text = chunk["text"].str ?: ""
                startedSlot(index, state)?.let { slot ->
                    val prev = state.blocks[slot] as? AssistantBlock.Reasoning
                    state.blocks[slot] = AssistantBlock.Reasoning((prev?.text ?: "") + text)
                }
                stampFirstToken(state, text)
            }
            "tool-call-delta" -> {
                val delta = chunk["argumentsDelta"].str ?: ""
                startedSlot(index, state)?.let { slot ->
                    val prev = state.blocks[slot] as? AssistantBlock.ToolCall
                    state.blocks[slot] = if (prev != null) {
                        AssistantBlock.ToolCall(prev.callId, chunk["name"].str ?: prev.name, prev.argsRaw + delta)
                    } else {
                        AssistantBlock.ToolCall(chunk["id"].str ?: "", chunk["name"].str ?: "", delta)
                    }
                }
                stampFirstToken(state, delta)
            }
            "block-end" -> {
                val slot = startedSlot(index, state) ?: return
                val block = chunk["block"] ?: return
                state.blocks[slot] = toAssistantBlock(decodeContentBlock(block))
            }
            "usage" -> chunk["usage"]?.decodeOrNull<TokenUsage>()?.let { state.usage = it }
        }
    }

    private fun startedSlot(index: Int?, state: AssistantStepData): Int? {
        if (index == null || index < 0 || index >= state.blocks.size) return null
        return index
    }

    private fun stampFirstToken(state: AssistantStepData, text: String?) {
        if (state.firstTokenTime == null && !text.isNullOrEmpty()) state.firstTokenTime = state.time
    }

    /** 把一个 attempt 的压缩 stream 记录展开并重放。 */
    private fun applyAssistantStreamRecords(turn: Int, step: Int, seq: Double?, stream: JsonElement?) {
        val chunks = expandAssistantStream(stream)
        if (chunks.isEmpty()) return
        for (timed in chunks) {
            applyAssistantLiveChunk(turn, step, seq ?: nextLiveChunkSeq(), timed.first, timed.second)
        }
    }

    private fun applyAssistantAttempt(event: SessionEvent) {
        val data = event.data ?: return
        val turn = data["turn"].int ?: 0
        val step = data["step"].int ?: 0
        val key = assistantStepKey(turn, step)
        var state = (contexts[key] as? NodeContext.Assistant)?.state?.copyState()
        if (state == null) {
            state = AssistantStepData(turn = turn, step = step, startSeq = event.seq.toDouble(), time = event.time)
            contexts[key] = NodeContext.Assistant(state)
            applyAssistantStreamRecords(turn, step, event.seq.toDouble(), data["stream"])
            state = (contexts[key] as? NodeContext.Assistant)?.state?.copyState() ?: state
        }
        if (state.stepStartTime == null) state.stepStartTime = stepStartTimes["$turn:$step"]
        state.time = event.time
        state.status = AssistantStatus.INTERRUPTED
        state.interrupted = true
        state.settledSeq = event.seq
        contexts[key] = NodeContext.Assistant(state)
    }

    private fun applyAssistantMessage(event: SessionEvent) {
        val data = event.data ?: return
        val turn = data["turn"].int ?: 0
        val step = data["step"].int ?: 0
        val message = data["message"]
        val key = assistantStepKey(turn, step)
        val blocks = (message["content"].arr ?: emptyList()).map { toAssistantBlock(decodeContentBlock(it)) }
        val state = (contexts[key] as? NodeContext.Assistant)?.state?.copyState()
            ?: AssistantStepData(turn = turn, step = step, startSeq = event.seq.toDouble(), time = event.time)
        if (state.stepStartTime == null) state.stepStartTime = stepStartTimes["$turn:$step"]
        val interrupted = data["interrupted"].bool == true
        state.status = if (interrupted) AssistantStatus.INTERRUPTED else AssistantStatus.SETTLED
        state.interrupted = interrupted
        state.settledSeq = event.seq
        state.blocks = blocks.toMutableList<AssistantBlock?>()
        state.messageId = message["id"].str
        state.provider = message["source"]["provider"].str
        state.model = message["source"]["model"].str
        data["usage"]?.decodeOrNull<TokenUsage>()?.let { state.usage = it }
        contexts[key] = NodeContext.Assistant(state)
    }

    // —— 工具调用 ————————————————————————————————————————————

    private fun applyToolCall(event: SessionEvent, view: ToolEventView?) {
        val data = event.data ?: return
        val name = data["name"].str ?: return
        val rawId = data["callId"].str ?: ""
        val callId = rawId.ifEmpty { "seq-${event.seq}" }
        contexts["tool-call:$callId"] = NodeContext.Tool(
            ToolChatData(
                callId = callId,
                name = name,
                argsRaw = data["arguments"].str ?: "",
                turn = data["turn"].int ?: 0,
                step = data["step"].int ?: 0,
                startSeq = event.seq,
                time = event.time,
                callView = if (view?.target == "call") view.view else null,
            )
        )
    }

    private fun applyToolResult(event: SessionEvent, view: ToolEventView?) {
        val data = event.data ?: return
        val message = data["message"] ?: return
        val callIdFromSource = message["source"]["callId"].str ?: return
        val rawBlocks = message["content"].arr ?: emptyList()
        val decoded = rawBlocks.map { decodeContentBlock(it) }
        val wrappers = decoded.filterIsInstance<ContentBlock.ToolResult>()
        val content = if (wrappers.isEmpty()) decoded else wrappers.flatMap { it.content }
        val wrappedCallId = wrappers.firstOrNull()?.toolCallId ?: callIdFromSource
        val errorObj = data["error"]
        val error = errorObj?.let { ToolError(it["name"].str ?: "", it["code"].str ?: "") }
        val result = ToolResultData(
            seq = event.seq,
            time = event.time,
            content = content,
            isError = error != null || wrappers.any { it.isError },
            error = error,
            meta = data["meta"],
            resultView = if (view?.target == "result") view.view else null,
        )
        val key = "tool-call:$wrappedCallId"
        val existing = contexts[key] as? NodeContext.Tool
        if (existing != null) {
            val updated = existing.data.copy()
            updated.result = result
            if (view?.target == "call") updated.callView = view.view
            contexts[key] = NodeContext.Tool(updated)
            return
        }
        contexts[key] = NodeContext.Tool(
            ToolChatData(
                callId = wrappedCallId,
                name = "orphan-result",
                argsRaw = "",
                turn = data["turn"].int ?: 0,
                step = data["step"].int ?: 0,
                startSeq = event.seq,
                time = event.time,
                result = result,
            )
        )
    }

    private fun applySubcallStart(event: SessionEvent) {
        val data = event.data ?: return
        val rootCallId = data["rootCallId"].str ?: return
        val parentCallId = data["parentCallId"].str ?: return
        val subCallId = data["subCallId"].str ?: return
        val root = (contexts["tool-call:$rootCallId"] as? NodeContext.Tool)?.data
        contexts["tool-call:$subCallId"] = NodeContext.Tool(
            ToolChatData(
                callId = subCallId,
                name = data["name"].str ?: "",
                argsRaw = data["arguments"]?.let { DshJson.encodeToString(JsonElement.serializer(), it) } ?: "",
                turn = root?.turn ?: currentTurn,
                step = root?.step ?: (activeSteps.maxOrNull() ?: 0),
                startSeq = event.seq,
                time = event.time,
                rootCallId = rootCallId,
                parentCallId = parentCallId,
            )
        )
    }

    private fun applySubcallEnd(event: SessionEvent) {
        val data = event.data ?: return
        val subCallId = data["subCallId"].str ?: return
        val key = "tool-call:$subCallId"
        val existing = (contexts[key] as? NodeContext.Tool)?.data
        val child = existing?.copy() ?: ToolChatData(
            callId = subCallId,
            name = data["name"].str ?: "",
            argsRaw = "",
            turn = currentTurn,
            step = activeSteps.maxOrNull() ?: 0,
            startSeq = event.seq,
            time = event.time,
            rootCallId = data["rootCallId"].str,
            parentCallId = data["parentCallId"].str,
        )
        child.result = ToolResultData(
            seq = event.seq,
            time = event.time,
            content = (data["content"].arr ?: emptyList()).map { decodeContentBlock(it) },
            isError = data["isError"].bool ?: false,
            error = data["error"]?.let { ToolError(it["name"].str ?: "", it["code"].str ?: "") },
            meta = data["meta"],
        )
        contexts[key] = NodeContext.Tool(child)
    }

    // —— 轮次与步骤 ————————————————————————————————————————————

    private fun applyTurnStart(event: SessionEvent) {
        val turn = event.data["turn"].int ?: return
        currentTurn = turn
        turnStartSeqs[turn] = event.seq
        running = true
        lastTurnErrored = false
        val errKey = "turn-error:$turn"
        if (contexts[errKey] == null) {
            contexts[errKey] = NodeContext.TurnErrorPending(PendingTurnError(turn, event.seq, event.time))
        }
        val tailKey = "turn-tail:$turn"
        if (contexts[tailKey] == null) {
            contexts[tailKey] = NodeContext.Tail(TurnTailData(turn, event.seq, event.time))
        }
    }

    private fun applyStepStart(event: SessionEvent) {
        val step = event.data["step"].int ?: return
        activeSteps.add(step)
        if (currentTurn == 0) return
        stepStartTimes["$currentTurn:$step"] = event.time
        stepStartSeqs["$currentTurn:$step"] = event.seq
        val key = assistantStepKey(currentTurn, step)
        (contexts[key] as? NodeContext.Assistant)?.let { ctx ->
            val state = ctx.state.copyState()
            state.stepStartTime = event.time
            contexts[key] = NodeContext.Assistant(state)
        }
    }

    private fun applyStepEnd(event: SessionEvent) {
        event.data["step"].int?.let { activeSteps.remove(it) }
    }

    private fun sumNullable(a: Int?, b: Int?): Int? {
        if (a == null && b == null) return null
        return (a ?: 0) + (b ?: 0)
    }

    private fun applyTurnEnd(event: SessionEvent) {
        val data = event.data ?: return
        val turn = data["turn"].int ?: return
        activeSteps.clear()
        val reason = data["reason"]
        val kind = reason["kind"].str
        when (kind) {
            "error" -> {
                val failure = reason["error"]
                val message = failure["message"].str ?: reason["message"].str ?: "Turn failed"
                val errKey = "turn-error:$turn"
                (contexts[errKey] as? NodeContext.TurnErrorPending)?.let {
                    it.pending.message = message
                    it.pending.code = failure["code"].str
                    it.pending.seq = event.seq
                }
                lastTurnErrored = true
                lastErroredTurn = turn
            }
            "max-tokens" -> {
                contexts["turn-max-tokens:$turn"] =
                    NodeContext.MaxTokens(TurnMaxTokensNode(event.seq, event.time, turn, null))
            }
            else -> contexts.remove("turn-error:$turn")
        }

        val tailKey = "turn-tail:$turn"
        val hasStart = contexts[tailKey] is NodeContext.Tail
        if (!hasStart) contexts[tailKey] = NodeContext.Tail(TurnTailData(turn, event.seq, event.time))
        val tailCtx = contexts[tailKey] as NodeContext.Tail
        val tail = tailCtx.data
        if (kind == "completed") {
            tail.seq = event.seq
            var lastAssistant: AssistantStepData? = null
            var firstStep: Int? = null
            var firstStepTtftMs: Double? = null
            var decodeMs = 0.0
            var outputTokens = 0
            var sampled = false
            var turnUsage = TokenUsage()
            var billed = false
            val steps = mutableListOf<AssistantStepData>()
            for (ctx in contexts.values) {
                val s = (ctx as? NodeContext.Assistant)?.state ?: continue
                if (s.turn != turn) continue
                steps.add(s)
                if (lastAssistant == null || s.step > (lastAssistant?.step ?: -1)) lastAssistant = s
                s.usage?.let { usage ->
                    billed = true
                    turnUsage = TokenUsage(
                        uncachedInputTokens = sumNullable(
                            turnUsage.uncachedInputTokens,
                            usage.uncachedInputTokens ?: usage.inputTokens,
                        ),
                        outputTokens = sumNullable(turnUsage.outputTokens, usage.outputTokens),
                        cacheReadTokens = sumNullable(turnUsage.cacheReadTokens, usage.cacheReadTokens),
                        cacheWriteTokens = sumNullable(turnUsage.cacheWriteTokens, usage.cacheWriteTokens),
                        reasoningTokens = sumNullable(turnUsage.reasoningTokens, usage.reasoningTokens),
                    )
                }
                val ttftMs = s.stepStartTime?.let { start -> s.firstTokenTime?.let { max(0.0, it - start) } }
                val stepDecodeMs = s.firstTokenTime?.let { max(0.0, s.time - it) }
                val out = s.usage?.outputTokens ?: 0
                if (firstStep == null || s.step < firstStep!!) {
                    firstStep = s.step
                    firstStepTtftMs = ttftMs
                }
                if (stepDecodeMs != null && out > 0) {
                    decodeMs += stepDecodeMs
                    outputTokens += out
                    sampled = true
                }
            }
            if (billed && hasStart) tail.usage = turnUsage
            val routes = mutableListOf<String>()
            for (s in steps.sortedBy { it.step }) {
                val provider = s.provider ?: continue
                val model = s.model ?: continue
                if (provider.isEmpty() || model.isEmpty()) continue
                val route = "$provider/$model"
                if (!routes.contains(route)) routes.add(route)
            }
            tail.routes = routes
            tail.ttftMs = firstStepTtftMs
            tail.decodeTokensPerSec = if (sampled && decodeMs > 0) outputTokens / (decodeMs / 1000) else null
            lastAssistant?.let { last ->
                tail.messageId = last.messageId
                tail.closingText = last.blocks.filterIsInstance<AssistantBlock.Text>().joinToString("") { it.text }
                tail.runMs = max(0.0, event.time - tail.time)
            }
        } else {
            tail.seq = event.seq
        }

        running = false
        if (currentTurn == turn) currentTurn = 0
        for ((key, ctx) in contexts.entries.toList()) {
            val state = (ctx as? NodeContext.Assistant)?.state ?: continue
            if (state.turn != turn || state.status != AssistantStatus.RUNNING) continue
            val closed = state.copyState()
            closed.status = AssistantStatus.INTERRUPTED
            closed.interrupted = true
            contexts[key] = NodeContext.Assistant(closed)
        }
    }

    // —— 压缩 / 重试 / 命令 / 工作流 ————————————————————————————

    private fun applyCompaction(event: SessionEvent) {
        val data = event.data ?: return
        val compactionId = data["compactionId"].str ?: return
        val key = "compaction:$compactionId"
        val node = (contexts[key] as? NodeContext.Compaction)?.node
            ?: CompactionSummaryNode(compactionId, event.seq, event.time)
        if (event.type == "compaction/summary") {
            val first = data["summary"].arr?.firstOrNull()
            (first?.let { decodeContentBlock(it) } as? ContentBlock.Text)?.let { node.summary = it.text }
            node.summaryEventSeq = event.seq
            node.shadowedTokenCount = data["shadowedTokenCount"].int
        }
        contexts[key] = NodeContext.Compaction(node)
    }

    private fun applyRetry(event: SessionEvent) {
        val data = event.data ?: return
        val retryId = data["retryId"].str ?: return
        val key = "model-retry:$retryId"
        val node = (contexts[key] as? NodeContext.Retry)?.node
            ?: ModelRetryNode(retryId, event.seq, event.time)
        if (event.type == "llm/retry") {
            node.retry = data["retry"].int ?: node.retry
            node.maxRetries = data["maxRetries"].int
            node.delayMs = data["delayMs"].int
            node.failureMessage = data["failure"]["message"].str
            node.failureCode = data["failure"]["code"].str
            node.provider = data["provider"].str
            node.retryState = RetryState.SCHEDULED
        } else if (event.type == "llm/retry-started") {
            node.retryState = RetryState.STARTED
        }
        contexts[key] = NodeContext.Retry(node)
        data["turn"].int?.let { contexts.remove("turn-error:$it") }
        if (event.type == "llm/retry") {
            val turn = data["turn"].int
            val step = data["step"].int
            if (turn != null && step != null) {
                val stepKey = assistantStepKey(turn, step)
                (contexts[stepKey] as? NodeContext.Assistant)?.let { ctx ->
                    val state = ctx.state.copyState()
                    state.blocks = mutableListOf()
                    state.status = AssistantStatus.RUNNING
                    state.firstVisibleSeq = null
                    state.settledSeq = null
                    state.messageId = null
                    state.usage = null
                    contexts[stepKey] = NodeContext.Assistant(state)
                }
            }
        }
    }

    private fun applyCommand(event: SessionEvent) {
        val data = event.data ?: return
        val commandId = data["commandId"].str ?: return
        val key = "command:$commandId"
        val node = (contexts[key] as? NodeContext.Command)?.node ?: CommandNode(commandId, event.seq, event.time)
        if (event.type == "command/run") {
            node.name = data["name"].str
            node.args = data["args"].str
        } else if (event.type == "command/done") {
            node.outcome = CommandOutcome(
                data["kind"].str ?: "success",
                data["text"].str,
                data["sourceEventSeq"].int,
            )
        }
        contexts[key] = NodeContext.Command(node)
    }

    private fun applyWorkflow(event: SessionEvent) {
        val data = event.data ?: return
        val runId = data["runId"].str ?: return
        val key = "workflow-run:$runId"
        val state = (contexts[key] as? NodeContext.Workflow)?.state
            ?: if (event.type == "tool-workflow/run-start") {
                WorkflowState(runId, data["name"].str ?: Strings.workflowUntitled, event.seq)
            } else {
                return
            }
        when (event.type) {
            "tool-workflow/agent-start" -> {
                val seq = data["seq"].int ?: return
                val label = data["label"].str ?: return
                val childId = data["childId"].str ?: return
                state.members.add(WorkflowMemberState(seq, label, data["phase"].str, childId))
            }
            "tool-workflow/agent-end" -> {
                val seq = data["seq"].int ?: return
                val outcome = data["outcome"].str ?: return
                state.members.filter { it.seq == seq }.forEach { it.outcome = outcome }
            }
            "tool-workflow/run-end" -> state.stopReason = data["stopReason"].str
        }
        contexts[key] = NodeContext.Workflow(state)
    }

    private fun projectWorkflow(state: WorkflowState): WorkflowRunData {
        val interrupted = state.stopReason == null && turnEndBoundaryAfter(state.anchorSeq) != null
        val order = mutableListOf<String>()
        val groups = linkedMapOf<String, Pair<String?, MutableList<WorkflowRunMemberData>>>()
        for (member in state.members) {
            val key = member.phase?.let { "value:${it.length}:$it" } ?: "missing"
            if (groups[key] == null) {
                groups[key] = member.phase to mutableListOf()
                order.add(key)
            }
            groups[key]!!.second.add(
                WorkflowRunMemberData(
                    member.seq, member.label, member.childId,
                    member.outcome?.let { workflowStatusFromOutcome(it) }
                        ?: if (interrupted) WorkflowRunStatus.INTERRUPTED else WorkflowRunStatus.RUNNING,
                )
            )
        }
        val status = state.stopReason?.let { workflowStatusFromStopReason(it) }
            ?: if (interrupted) WorkflowRunStatus.INTERRUPTED else WorkflowRunStatus.RUNNING
        return WorkflowRunData(
            state.runId, state.name, status,
            order.map { WorkflowRunPhaseData(it, groups[it]!!.first, groups[it]!!.second) },
            state.anchorSeq,
        )
    }

    private fun turnEndBoundaryAfter(anchorSeq: Int): Pair<Int, Double>? =
        turnEndBoundaries.firstOrNull { it.first > anchorSeq }

    private fun applyPresented(event: SessionEvent) {
        val parsed = TurnDeliverables.presented(event.seq, event.data) ?: return
        val files = presentedByTurn.getOrPut(parsed.first) { mutableListOf() }
        for (file in parsed.second) {
            val at = files.indexOfFirst { it.path == file.path }
            if (at >= 0) files[at] = file else files.add(file)
        }
        deliverablesRevision++
    }

    // —— 投影 ——————————————————————————————————————————————————

    private fun recordProjectionVersion(value: JsonElement, key: String, seq: Int) {
        val versions = projectionHistory.getOrPut(key) { mutableMapOf() }
        versions[seq] = value
        if (versions.size > MAX_PROJECTION_VERSIONS) {
            val retained = versions.keys.sorted().takeLast(MAX_PROJECTION_VERSIONS).toSet()
            versions.keys.filterNot { it in retained }.forEach { versions.remove(it) }
        }
    }

    fun applyProjection(frame: SessionProjectionFrame) {
        recordProjectionVersion(frame.value, frame.key, frame.seq)
        val existing = projections[frame.key]
        if (existing == null || frame.seq > existing.second) {
            projections[frame.key] = frame.value to frame.seq
            decodeSingleProjection(frame.key, frame.value)
        }
    }

    private fun rebuildProjections(throughSeq: Int) {
        projections.clear()
        for ((key, versions) in projectionHistory) {
            val best = versions.filterKeys { it <= throughSeq }.maxByOrNull { it.key } ?: continue
            projections[key] = best.value to best.key
        }
        title = ""
        blank = true
        todos = emptyList()
        goal = null
        tokenUsage = null
        sessionStats = null
        imageLimits = null
        permissionProjection = null
        changesSeqByTurn.clear()
        refreshPermissions()
        for ((key, pair) in projections) decodeSingleProjection(key, pair.first)
    }

    private fun decodeSingleProjection(key: String, value: JsonElement) {
        when (key) {
            "turnOutline" -> rebuildTurnRail()
            "title" -> value.str?.takeIf { it.isNotEmpty() }?.let { title = it }
            "sessionListMetadata" -> value["blank"].bool?.let { blank = it }
            "todos" -> value.arr?.let { list ->
                todos = list.map {
                    TodoItem(
                        id = it["id"].str ?: java.util.UUID.randomUUID().toString(),
                        content = it["content"].str ?: "",
                        status = it["status"].str ?: "pending",
                        priority = it["priority"].str,
                    )
                }
            }
            "goal" -> {
                if (value is JsonNull) {
                    goal = null
                } else {
                    val snapshot = value["goal"]
                    goal = if (snapshot is JsonObject) {
                        GoalProjection(
                            id = snapshot["id"].str ?: "",
                            revision = snapshot["revision"].int ?: 0,
                            objective = snapshot["objective"].str ?: "",
                            phase = snapshot["phase"].str ?: "active",
                            maxGoalRounds = snapshot["maxGoalRounds"].int,
                            blockedMessage = snapshot["blockedReason"]["message"].str,
                        )
                    } else null
                }
            }
            "tokenUsage" -> tokenUsage = value.decodeOrNull<TokenUsage>()
            "sessionStats" -> sessionStats = value.decodeOrNull<SessionStatsProjection>()
            "imageLimits" -> imageLimits = value.decodeOrNull<ImageAttachmentLimits>()
            "permissions" -> value.decodeOrNull<PermissionSelectValue>()?.let {
                permissionProjection = it
                refreshPermissions()
            }
            "inbox" ->
                // 0.1.6 把待发队列从 session/control 挪到了这里。投影发布的是整条消息，
                // 行位置（queued / steering / context）由客户端本地推导，再按原快照语义整体应用。
                decodeInbox(value)?.let { applyQueue(rowsFromInbox(it)) }
            "agentPreset" -> value.str?.takeIf { it.isNotEmpty() }?.let { agentPreset = it }
            "modelSelection" -> value["next"]?.decodeOrNull<ModelSelection>()?.let {
                modelDirectory = modelDirectory.applyProjectedSelection(it)
            }
        }
    }

    // —— 节点物化 ——————————————————————————————————————————————

    private fun rebuildNodes() {
        val built = mutableListOf<ChatNode>()
        val tools = mutableMapOf<CallId, ToolChatData>()
        for (ctx in contexts.values) {
            when (ctx) {
                is NodeContext.Finalized -> built.add(ctx.node)
                is NodeContext.Assistant -> if (ctx.state.isVisible) built.add(ChatNode.Assistant(ctx.state))
                is NodeContext.Tool -> {
                    val tool = ctx.data.copy()
                    if (tool.result == null) {
                        turnEndBoundaryAfter(tool.startSeq)?.let { boundary ->
                            tool.result = ToolResultData(
                                seq = boundary.first, time = boundary.second, content = emptyList(),
                                isError = true,
                                error = ToolError(Strings.workflowStatusInterrupted, "interrupted"),
                            )
                        }
                    }
                    tools[tool.callId] = tool
                }
                is NodeContext.Compaction -> built.add(ChatNode.Compaction(ctx.node))
                is NodeContext.Retry -> built.add(ChatNode.ModelRetry(ctx.node))
                is NodeContext.TurnErrorPending -> ctx.pending.message?.let { message ->
                    built.add(
                        ChatNode.TurnError(
                            TurnErrorNode(
                                ctx.pending.seq ?: ctx.pending.startSeq, ctx.pending.time,
                                ctx.pending.turn, null, message, ctx.pending.code,
                            )
                        )
                    )
                }
                is NodeContext.MaxTokens -> built.add(ChatNode.TurnMaxTokens(ctx.node))
                is NodeContext.Command -> built.add(ChatNode.Command(ctx.node))
                is NodeContext.Tail -> built.add(ChatNode.TurnTail(ctx.data))
                is NodeContext.Workflow -> built.add(ChatNode.Workflow(projectWorkflow(ctx.state)))
            }
        }
        val childrenByParent = mutableMapOf<CallId, MutableList<ToolChatData>>()
        for (tool in tools.values) {
            val parent = tool.parentCallId ?: continue
            if (tools[parent] == null) continue
            childrenByParent.getOrPut(parent) { mutableListOf() }.add(tool)
        }
        childrenByParent.values.forEach { it.sortBy { tool -> tool.startSeq } }

        fun nested(tool: ToolChatData, visited: Set<CallId>, depth: Int): ToolChatData {
            val children = childrenByParent[tool.callId]
            if (children == null || tool.callId in visited || depth >= 64) return tool
            val value = tool.copy()
            val seen = visited + tool.callId
            value.subCalls = children.map { nested(it, seen, depth + 1) }
            return value
        }
        for (tool in tools.values) {
            if (tool.parentCallId == null || tools[tool.parentCallId] == null) {
                built.add(ChatNode.Tool(nested(tool, emptySet(), 0)))
            }
        }

        var keyed = built.map { Triple(it.anchorSeq, it.nodeId, it) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .toMutableList()
        keyed = applyTurnProcessFolding(keyed)
        nodes = keyed.map { it.third }
        indexRows()
    }

    private fun applyTurnProcessFolding(
        input: MutableList<Triple<Double, String, ChatNode>>,
    ): MutableList<Triple<Double, String, ChatNode>> {
        rebuildTurnProcessSpecs(input.map { it.third })
        if (turnProcessSpecs.isEmpty()) return input
        var keyed = input
        for (spec in turnProcessSpecs.values) {
            val control = ChatNode.TurnProcess(spec)
            keyed.add(Triple(control.anchorSeq, control.nodeId, control))
        }
        keyed = keyed.sortedWith(compareBy({ it.first }, { it.second })).toMutableList()
        keyed.removeAll { isFoldedProcessRow(it.third) }
        // 折叠状态下把答案步骤自己的 reasoning 隐掉。
        for (spec in turnProcessSpecs.values) {
            if (!spec.inlineReasoning || expandedProcessKeys.contains(spec.key)) continue
            val index = keyed.indexOfFirst {
                val node = it.third
                node is ChatNode.Assistant && node.step.turn == spec.turn && node.step.step == spec.answerStep
            }
            if (index < 0) continue
            val node = keyed[index].third as ChatNode.Assistant
            val step = node.step.copyState()
            step.blocks = step.blocks.map { if (it is AssistantBlock.Reasoning) null else it }.toMutableList()
            keyed[index] = keyed[index].copy(third = ChatNode.Assistant(step))
        }
        return keyed
    }

    private fun isReplyBlock(block: AssistantBlock?): Boolean = when (block) {
        is AssistantBlock.Text -> block.text.isNotBlank()
        is AssistantBlock.Image, is AssistantBlock.Other -> true
        else -> false
    }

    private fun rebuildTurnProcessSpecs(rows: List<ChatNode>) {
        turnProcessSpecs.clear()
        if (hasMore) return

        class Accumulator {
            val processAnchors = mutableListOf<Double>()
            var lastAnchor: Double? = null
            var lastStep = 0
            var lastIsAnswer = false
            var lastReasoning = false
            val messagesByStep = mutableMapOf<Int, Int>()
            var toolCalls = 0
            var subagents = 0
        }

        val byTurn = mutableMapOf<Int, Accumulator>()
        val order = mutableListOf<Int>()
        var openTurn: Int? = null
        val pendingAnchors = mutableListOf<Double>()

        for (node in rows) {
            if (node is ChatNode.User || node is ChatNode.Steering) {
                openTurn = null
                pendingAnchors.clear()
                continue
            }
            node.turnNumber?.let { openTurn = it }
            val turn = openTurn
            if (turn == null) {
                pendingAnchors.add(node.anchorSeq)
                continue
            }
            val acc = byTurn.getOrPut(turn) { order.add(turn); Accumulator() }
            if (pendingAnchors.isNotEmpty()) {
                acc.processAnchors.addAll(pendingAnchors)
                pendingAnchors.clear()
            }
            when (node) {
                is ChatNode.Assistant -> {
                    val step = node.step
                    val hasReply = step.blocks.any { isReplyBlock(it) }
                    if (hasReply) acc.messagesByStep[step.step] = (acc.messagesByStep[step.step] ?: 0) + 1
                    acc.lastAnchor?.let { acc.processAnchors.add(it) }
                    val isAnswer = step.status == AssistantStatus.SETTLED && hasReply &&
                        step.blocks.none { it is AssistantBlock.ToolCall }
                    val reasoning = step.blocks.any { it is AssistantBlock.Reasoning && it.text.isNotBlank() }
                    acc.lastAnchor = node.anchorSeq
                    acc.lastStep = step.step
                    acc.lastIsAnswer = isAnswer
                    acc.lastReasoning = reasoning
                }
                is ChatNode.Tool -> {
                    if (isSubagentDelegationTool(node.data.name)) acc.subagents++ else acc.toolCalls++
                    acc.processAnchors.add(node.anchorSeq)
                }
                is ChatNode.ModelRetry, is ChatNode.Context, is ChatNode.Compaction,
                is ChatNode.Command, is ChatNode.Workflow, is ChatNode.UnknownSurface ->
                    acc.processAnchors.add(node.anchorSeq)
                else -> continue
            }
        }

        for (turn in order) {
            val acc = byTurn[turn] ?: continue
            val lastAnchor = acc.lastAnchor ?: continue
            if (!acc.lastIsAnswer) continue
            if (turnEndBoundaryAfter(lastAnchor.toInt()) == null) continue
            val external = acc.processAnchors.filter { it < lastAnchor }.minOrNull()
            if (external == null && !acc.lastReasoning) continue
            val messages = acc.messagesByStep.filterKeys { it < acc.lastStep }.values.sum()
            turnProcessSpecs[turn] = TurnProcessSpec(
                turn = turn,
                processStartSeq = external ?: lastAnchor,
                answerAnchorSeq = lastAnchor,
                answerStep = acc.lastStep,
                messageCount = messages,
                toolCallCount = acc.toolCalls,
                subagentCount = acc.subagents,
                inlineReasoning = acc.lastReasoning,
            )
        }
    }

    private fun isFoldedProcessRow(node: ChatNode): Boolean {
        when (node) {
            is ChatNode.User, is ChatNode.Steering, is ChatNode.TurnProcess,
            is ChatNode.TurnError, is ChatNode.TurnMaxTokens, is ChatNode.TurnTail,
            is ChatNode.SystemPrompt -> return false
            else -> Unit
        }
        val spec = turnProcessSpecs.values.firstOrNull { it.covers(node.anchorSeq) } ?: return false
        return !expandedProcessKeys.contains(spec.key)
    }

    fun isTurnProcessExpanded(spec: TurnProcessSpec): Boolean = expandedProcessKeys.contains(spec.key)

    fun setTurnProcessExpanded(spec: TurnProcessSpec, expanded: Boolean) {
        val changed = if (expanded) expandedProcessKeys.add(spec.key) else expandedProcessKeys.remove(spec.key)
        if (changed) rebuildNodes()
    }

    private fun indexRows() {
        val producedBefore = producedPathsByTurn.toMap()
        producedPathsByTurn.clear()
        closingAssistantStepByTurn.clear()
        toolCallRootIndex.clear()
        turnAnchorRows.clear()
        turnPromptRow.clear()
        turnPromptAnchors.clear()
        turnResponseRow.clear()
        val seenPaths = mutableMapOf<Int, MutableSet<String>>()
        var pendingPromptRow: Int? = null
        nodes.forEachIndexed { index, node ->
            if (node is ChatNode.User || node is ChatNode.Command) pendingPromptRow = index
            val turn = node.turnNumber
            if (turn != null && turnAnchorRows[turn] == null) {
                val anchor = pendingPromptRow ?: index
                val label = nodes[anchor].nodeId
                turnAnchorRows[turn] = label
                if (pendingPromptRow != null) {
                    turnPromptRow[turn] = anchor
                    turnPromptAnchors[turn] = label
                }
                pendingPromptRow = null
            }
            when (node) {
                is ChatNode.Tool -> {
                    toolCallRootIndex[node.data.callId] = index
                    val data = node.data
                    if (data.parentCallId == null) {
                        val result = data.result
                        if (result != null && !result.isError) {
                            TurnDeliverables.producedPath(data.name, data.argsRaw)?.let { path ->
                                if (seenPaths.getOrPut(data.turn) { mutableSetOf() }.add(path)) {
                                    producedPathsByTurn.getOrPut(data.turn) { mutableListOf() }.add(path)
                                }
                            }
                        }
                    }
                }
                is ChatNode.Assistant -> {
                    val step = node.step
                    if (step.step >= (closingAssistantStepByTurn[step.turn] ?: Int.MIN_VALUE)) {
                        closingAssistantStepByTurn[step.turn] = step.step
                    }
                    if (step.blocks.any { it is AssistantBlock.Text && it.text.isNotEmpty() }) {
                        turnResponseRow[step.turn] = index
                    }
                }
                else -> Unit
            }
        }
        if (producedBefore != producedPathsByTurn) deliverablesRevision++
        rebuildTurnRail()
    }

    private fun rebuildTurnRail() {
        val merged = mergeTurnRailItems(
            outline = projections["turnOutline"]?.first,
            anchors = turnAnchorRows,
            promptAnchors = turnPromptAnchors,
        ) { turn -> loadedTurnPreview(turn) }
        if (turnRail != merged) turnRail = merged
    }

    private fun loadedTurnPreview(turn: Int): Pair<String, String> {
        var prompt = ""
        turnPromptRow[turn]?.let { index ->
            (nodes.getOrNull(index) as? ChatNode.User)?.let { node ->
                prompt = TurnRailPreview.clip(
                    node.content.filterIsInstance<ContentBlock.Text>().map { it.text },
                    TurnRailPreview.PROMPT_LIMIT,
                )
            }
        }
        var response = ""
        turnResponseRow[turn]?.let { index ->
            (nodes.getOrNull(index) as? ChatNode.Assistant)?.let { node ->
                response = TurnRailPreview.clip(
                    node.step.blocks.filterIsInstance<AssistantBlock.Text>().map { it.text },
                    TurnRailPreview.RESPONSE_LIMIT,
                )
            }
        }
        return prompt to response
    }

    // —— 实时助手侧信道 ————————————————————————————————————————

    fun applyAssistantStreamFrame(frame: AssistantStreamFrame) {
        when (frame) {
            is AssistantStreamFrame.Start -> {
                liveAttemptId = frame.attemptId
                liveAttemptTurn = frame.turn
                liveAttemptStep = frame.step
            }
            is AssistantStreamFrame.Chunk -> {
                if (liveAttemptId != frame.attemptId) return
                applyAssistantLiveChunk(
                    liveAttemptTurn, liveAttemptStep, nextLiveChunkSeq(), frame.time, frame.chunk,
                )
                rebuildNodes()
            }
            is AssistantStreamFrame.End -> {
                if (liveAttemptId == frame.attemptId) liveAttemptId = null
            }
        }
    }

    fun applyAssistantStreamBaseline(baseline: AssistantStreamBaseline?) {
        val attempt = baseline?.activeAttempt
        if (attempt == null) {
            liveAttemptId = null
            return
        }
        liveAttemptId = attempt.attemptId
        liveAttemptTurn = attempt.turn
        liveAttemptStep = attempt.step
        applyAssistantStreamRecords(attempt.turn, attempt.step, null, attempt.stream)
        rebuildNodes()
    }

    // —— 可应答帧 ————————————————————————————————————————————

    fun setApproval(approval: ApprovalRequested?) { pendingApproval = approval }

    fun setQuestion(question: QuestionRequested?) { pendingQuestion = question }

    /**
     * 记下一条 `workspace/changes` 公告（对齐 Mac `applyWorkspaceChanges`）。
     *
     * 事件本身没有 surfaceOp，不会渲染成任何节点；它只负责把「这一轮有改动记录」传进来，
     * 具体内容由 UI 按 [changesAddress] 去主机读。同一轮后来的公告取代先前的地址。
     */
    private fun applyWorkspaceChanges(event: SessionEvent) {
        val turn = event.data["turn"].int ?: return
        if (turn < 1) return
        changesSeqByTurn[turn] = event.seq
        deliverablesRevision += 1
    }

    /** 某个轮次的改动记录地址；没有公告过就是 null（不画卡片）。 */
    fun changesAddress(turn: Int): TurnChangesAddress? =
        changesSeqByTurn[turn]?.let { TurnChangesAddress(sessionId, it) }

    /**
     * 记下进程级权限目录（0.1.6 的 `permissionPresets/catalog`）。
     *
     * 目录随主机实时贡献变化，与某个会话的历史无关，所以由 PhoneModel 拉到后广播给每个 store；
     * 没拉到（0.1.5 主机没有这条 RPC）时保持 null，退回投影内联的那份。
     */
    fun setPermissionCatalog(options: List<PermissionPresetOption>?) {
        permissionCatalogOptions = options
        refreshPermissions()
    }

    /** 投影与目录合并成界面读的那一个值；两半谁后到都能收敛。 */
    private fun refreshPermissions() {
        permissions = PermissionSelection.merge(permissionProjection, permissionCatalogOptions)
    }

    /** 整体应用一份待发队列（快照语义：传进来的是这一时刻的全部）。 */
    fun applyQueue(items: List<QueuedInboxItem>) {
        val observed = items.mapNotNull { it.rpcId }.toSet()
        if (observed.isNotEmpty()) {
            pendingSubmissions = pendingSubmissions.filterNot { it.requestId in observed }
        }
        if (queue != items) queue = items
    }

    fun setJobs(frame: SessionJobsFrame) { jobs = frame.jobs }

    companion object {
        private const val MAX_REPAIR_BUFFER = 10_000
        private const val MAX_PROJECTION_VERSIONS = 32
        private const val MAX_BLOCK_INDEX = 1024
    }
}

private fun workflowStatusFromStopReason(reason: String): WorkflowRunStatus = when (reason) {
    "completed" -> WorkflowRunStatus.COMPLETED
    "cancelled" -> WorkflowRunStatus.CANCELLED
    "error" -> WorkflowRunStatus.FAILED
    else -> WorkflowRunStatus.INTERRUPTED
}

private fun workflowStatusFromOutcome(outcome: String): WorkflowRunStatus = when (outcome) {
    "completed" -> WorkflowRunStatus.COMPLETED
    "cancelled" -> WorkflowRunStatus.CANCELLED
    "failed" -> WorkflowRunStatus.FAILED
    else -> WorkflowRunStatus.INTERRUPTED
}

/** 上下文注入行的来源分类（与 web contextProvenance 同源）。 */
private fun contextProvenance(source: JsonElement?): Pair<String, String?> {
    val kind = source["kind"].str ?: return "inject" to null
    fun collect(member: String, field: String): String? {
        val list = source[member].arr ?: return null
        val names = mutableListOf<String>()
        for (entry in list) {
            val value = entry[field].str?.takeIf { it.isNotEmpty() } ?: continue
            if (!names.contains(value)) names.add(value)
        }
        return names.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
    return when (kind) {
        "session-reference" -> "recall" to (collect("references", "label") ?: kind)
        "agent-instructions" -> "inject" to (collect("changes", "path") ?: kind)
        "plugin" -> "inject" to (source["plugin"].str ?: kind)
        "skill-invocation" -> "inject" to (source["name"].str ?: kind)
        else -> "inject" to kind
    }
}

/** 展开 DSH 1.5 压缩的 assistant stream 记录。 */
private fun expandAssistantStream(stream: JsonElement?): List<Pair<Double, JsonElement>> {
    val records = stream.arr ?: return emptyList()
    val out = mutableListOf<Pair<Double, JsonElement>>()
    for (record in records) {
        val type = record["type"].str ?: continue
        if (type == "chunk") {
            val chunk = record["chunk"] ?: continue
            val time = record["time"].dbl ?: continue
            out.add(time to chunk)
            continue
        }
        val index = record["index"].int ?: continue
        val time0 = record["time0"].dbl ?: continue
        val isToolCall = type == "tool-call-chunks"
        val members = if (isToolCall) {
            record["args"].arr?.mapNotNull { it.str }
        } else {
            record["texts"].arr?.mapNotNull { it.str }
        } ?: continue
        if (members.isEmpty()) continue
        val gaps = record["dt"].arr?.mapNotNull { it.int } ?: emptyList()
        if (gaps.size != members.size - 1) continue
        val chunkType = when {
            isToolCall -> "tool-call-delta"
            type == "reasoning-chunks" -> "reasoning-delta"
            else -> "text-delta"
        }
        val callId = record["id"].str
        val callName = record["name"].str
        var time = time0
        members.forEachIndexed { k, member ->
            if (k > 0) time += gaps[k - 1]
            val chunk = buildJsonObject {
                put("type", chunkType)
                put("index", index)
                if (isToolCall) {
                    callId?.let { put("id", it) }
                    callName?.let { put("name", it) }
                    put("argumentsDelta", member)
                } else {
                    put("text", member)
                }
            }
            out.add(time to chunk)
        }
    }
    return out
}

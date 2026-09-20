package ai.deepseek.dshphone.state

import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.*
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 消费唯一的多路下行，把每条逻辑流路由到各个 Store。
 *
 * 连接期间常开四条流：$events / workspace/follow / session/control，
 * 外加每个打开会话一条 session/follow。每条流都以完整基线开场，
 * 所以"重连"等于"重新订阅"而不是"重新同步"。
 */
class StreamCoordinator(
    private val api: ApiClient,
    private val scope: CoroutineScope,
    /** 本机缓存；每次拿到完整的 follow 快照后把窗口落盘，供下次冷启动先画。 */
    private val cache: SessionCache? = null,
    private val listStore: SessionListStore,
    private val workspaceStore: WorkspaceStore,
    private val sessionLookup: (SessionId) -> SessionStore?,
    private val sessionUpsert: (SessionId) -> SessionStore,
    private val allSessions: () -> List<SessionStore>,
    private val remoteEventHandler: (String) -> Unit,
    private val sessionRemovedHandler: (SessionId) -> Unit,
    private val sessionsArchivedHandler: (List<SessionId>) -> Unit,
    private val resyncHandler: () -> Unit,
    private val gapRepairLoader: suspend (SessionAddress, Int) -> SessionHistoryValue,
    private val attentionHandler: (AttentionSignal) -> Unit,
    /** 会话运行状态变过一次：宿主据此决定要不要起常驻的前台服务。 */
    private val runActivityHandler: () -> Unit = {},
    /**
     * 这条会话此刻是否真的在用户眼前（对齐 Mac 构造 coordinator 时传入的
     * `foldsLiveEvents`/AppModel.sessionIsOnScreen）。
     *
     * Mac 与手机在这里的取舍不同：Mac 把"不在屏的会话"的整帧都丢掉，于是根本没有
     * 机会把失败标成已读；手机仍然折叠（后台会话的窗口保持最新，回到列表后不必先
     * 补一个洞），所以"帧要不要折"和"失败算不算看见"必须分开问——折叠照旧，
     * 只有正看着的那条会话才把失败标成已读。
     */
    private val sessionIsOnScreen: (SessionId) -> Boolean = { true },
) {
    companion object {
        private const val TAG = "StreamCoordinator"
        const val EVENTS = "events"
        const val WORKSPACES = "workspaces"
        const val CONTROL = "control"
        /**
         * follow 快照默认带几轮消息，与 PhoneModel 的首屏口径一致。
         *
         * 这里只兜底调用方没显式给窗口的情况（[reopenAssistantStream]、补洞重开）；
         * 首屏那份由 PhoneModel.INITIAL_HISTORY_MESSAGES 明确指定。
         */
        private const val DEFAULT_FOLLOW_MESSAGES = 15
        fun followStreamId(sessionId: SessionId) = "follow:$sessionId"
        fun sessionIdOfFollow(streamId: String): SessionId? =
            if (streamId.startsWith("follow:")) streamId.removePrefix("follow:") else null
    }

    private var mux: MuxStream? = null
    private var muxJob: Job? = null

    var remoteEventClientId: String? = null
        private set
    var hostHome: String? = null
        private set
    var connectionState: WsConnectionState? = null
        private set

    private val removedSessionIds = mutableSetOf<SessionId>()
    private val pendingEventSessions = mutableMapOf<String, SessionId>()
    /** 0.1.5 的 control 队列状态（baseline 的 queues 块与 queue 帧）；0.1.6 不再有这条通道。 */
    private val queues = mutableMapOf<SessionId, List<QueuedInboxItem>>()
    /**
     * 0.1.6 的 `inbox` 会话投影派生的待发队列。
     *
     * 0.1.6 把 queue 从 control 基线里删掉了，投影是唯一来源。这里另存一份镜像，是因为
     * 「基线之后才创建的 store」没有投影历史可回放，只能从这份最新值回灌（对齐 Mac 的 inboxQueues）。
     */
    private val inboxQueues = mutableMapOf<SessionId, List<QueuedInboxItem>>()
    private val jobs = mutableMapOf<SessionId, List<JobView>>()
    private val followAddresses = mutableMapOf<SessionId, SessionAddress>()
    private val assistantExpectations = mutableMapOf<SessionId, Pair<String, Int>>()

    var onConnectionState: ((WsConnectionState) -> Unit)? = null

    fun start() {
        if (mux != null) return
        val stream = MuxStream(api.wsBaseUrl, api.http, scope)
        mux = stream
        stream.onStateChange = { state -> foldSocketState(state) }
        stream.subscribe(EVENTS, "\$events")
        stream.subscribe(WORKSPACES, "workspace/follow")
        stream.subscribe(CONTROL, "session/control")
        muxJob = scope.launch {
            stream.stream.collect { event -> handle(event) }
        }
        stream.start()
    }

    fun stop() {
        mux?.stop()
        muxJob?.cancel()
        mux = null
        muxJob = null
    }

    fun probeNow() { mux?.probeNow() }

    /**
     * 打开一个会话的实时窗口，幂等。
     *
     * @returns 订阅是否已经登记。登记之后每一代连接都会自动重开，所以调用方可以据此
     *   判断"基线已经有人给了"，不必再自己发一次分页。
     */
    fun follow(sessionId: SessionId, address: SessionAddress? = null, maxMessages: Int = DEFAULT_FOLLOW_MESSAGES): Boolean {
        val target = address ?: SessionAddress.Session(sessionId)
        followAddresses[sessionId] = target
        val addressJson = when (target) {
            is SessionAddress.Session -> buildJsonObject {
                put("kind", "session"); put("sessionId", target.sessionId)
            }
            is SessionAddress.Subagent -> buildJsonObject {
                put("kind", "subagent")
                put("parentSessionId", target.address.parentSessionId)
                put("childSessionId", target.address.childSessionId)
                put("mode", target.address.mode.wire)
            }
        }
        mux?.subscribe(
            followStreamId(sessionId),
            "session/follow",
            buildJsonObject {
                put("request", buildJsonObject {
                    put("address", addressJson)
                    put("maxMessages", maxMessages)
                    put("assistantStream", true)
                })
            },
        )
        return true
    }

    /** 重开一条 follow：流失败后重试基线用（subscribe 幂等，失败的那条得先撤掉）。 */
    fun refollow(sessionId: SessionId): Boolean {
        val address = followAddresses[sessionId] ?: SessionAddress.Session(sessionId)
        unfollow(sessionId)
        return follow(sessionId, address)
    }

    fun unfollow(sessionId: SessionId) {
        followAddresses.remove(sessionId)
        assistantExpectations.remove(sessionId)
        mux?.unsubscribe(followStreamId(sessionId))
    }

    private fun foldSocketState(state: WsConnectionState) {
        val previous = connectionState
        connectionState = state
        onConnectionState?.invoke(state)
        if (state == previous) return
        if (state == WsConnectionState.CONNECTED) {
            resyncHandler()
        } else if (previous != null) {
            dropStaleInteractions()
        }
    }

    private fun dropStaleInteractions() {
        remoteEventClientId = null
        pendingEventSessions.clear()
        listStore.clearAllAwaitingUser()
        for (store in allSessions()) {
            if (store.pendingApproval != null) store.setApproval(null)
            if (store.pendingQuestion != null) store.setQuestion(null)
        }
    }

    private suspend fun handle(event: MuxEvent) {
        when (event) {
            MuxEvent.Opened -> dropStaleInteractions()
            is MuxEvent.Item -> handleItem(event.streamId, event.value)
            is MuxEvent.Failure -> {
                Log.w(TAG, "stream ${event.streamId} failed: ${event.error.message}")
                sessionIdOfFollow(event.streamId)?.let { failFollow(it, event.error) }
            }
            is MuxEvent.End -> Unit
        }
    }

    private fun failFollow(sessionId: SessionId, error: RpcError) {
        val store = sessionLookup(sessionId) ?: return
        store.loadingHistory = false
        store.running = false
        store.historyError = HistoryError(error.code, error.message)
    }

    private suspend fun handleItem(streamId: String, value: JsonElement) {
        when (streamId) {
            EVENTS -> handleRemoteEvent(decodeRemoteEventFrame(value))
            WORKSPACES -> handleWorkspace(decodeWorkspaceFrame(value))
            CONTROL -> handleControl(decodeControlFrame(value))
            else -> {
                val sessionId = sessionIdOfFollow(streamId) ?: return
                handleFollow(sessionId, decodeFollowFrame(value))
            }
        }
    }

    // —— $events ————————————————————————————————————————————

    private fun handleRemoteEvent(frame: RemoteEventFrame) {
        when (frame) {
            is RemoteEventFrame.Ready -> {
                remoteEventClientId = frame.clientId
                frame.home?.let { hostHome = it }
            }
            is RemoteEventFrame.Emit -> handleHostEmit(frame.event, frame.args)
            is RemoteEventFrame.Waterfall -> handleWaterfall(frame)
            is RemoteEventFrame.Cancel -> retireWaterfall(frame.eventId)
            RemoteEventFrame.Unknown -> Unit
        }
    }

    private fun handleHostEmit(event: String, args: List<JsonElement>) {
        when (event) {
            "api-session/added" -> {
                val summary = args.firstOrNull()?.decodeOrNull<SessionSummary>() ?: return
                if (removedSessionIds.contains(summary.sessionId)) return
                listStore.upsert(summary)
            }
            "api-session/removed" -> {
                val sessionId = args.firstOrNull().str ?: return
                removedSessionIds.add(sessionId)
                listStore.remove(sessionId)
                queues.remove(sessionId)
                inboxQueues.remove(sessionId)
                jobs.remove(sessionId)
                unfollow(sessionId)
                sessionRemovedHandler(sessionId)
            }
            "api-session/status" -> {
                val sessionId = args.firstOrNull().str ?: return
                val running = args.getOrNull(1).bool ?: return
                listStore.setRunning(sessionId, running)
                sessionLookup(sessionId)?.running = running
                runActivityHandler()
                attentionHandler(AttentionSignal.Running(sessionId, running))
            }
            "api-session/activity" -> {
                val sessionId = args.firstOrNull().str ?: return
                val updatedAt = args.getOrNull(1).dbl ?: return
                listStore.recordActivity(sessionId, updatedAt)
            }
            "api-session/error" -> {
                val sessionId = args.firstOrNull().str ?: return
                val message = args.getOrNull(1).str ?: return
                attentionHandler(AttentionSignal.Failed(sessionId, message))
            }
            else -> remoteEventHandler(event)
        }
    }

    private fun handleWaterfall(frame: RemoteEventFrame.Waterfall) {
        val sessionId = frame.agentId
        when (frame.event) {
            "approval/request" -> {
                pendingEventSessions[frame.eventId] = sessionId
                val approval = ApprovalRequested(
                    eventId = frame.eventId,
                    sessionId = sessionId,
                    approvalId = frame.request["approvalId"].str ?: frame.eventId,
                    toolName = frame.request["toolName"].str ?: "",
                    callId = frame.request["callId"].str,
                    reason = frame.request["reason"].str,
                )
                val store = sessionUpsert(sessionId)
                store.setApproval(approval)
                syncAwaitingUser(sessionId, store)
                attentionHandler(AttentionSignal.ApprovalRequested(approval))
            }
            "user-questions/request" -> {
                val questions = (frame.request["questions"].arr ?: return)
                    .mapNotNull { it.decodeOrNull<AskUserQuestionItem>() }
                if (questions.isEmpty()) return
                pendingEventSessions[frame.eventId] = sessionId
                val question = QuestionRequested(frame.eventId, sessionId, questions)
                val store = sessionUpsert(sessionId)
                store.setQuestion(question)
                syncAwaitingUser(sessionId, store)
                attentionHandler(AttentionSignal.QuestionAsked(question))
            }
        }
    }

    private fun retireWaterfall(eventId: String) {
        val sessionId = pendingEventSessions.remove(eventId) ?: return
        attentionHandler(AttentionSignal.WaterfallRetired(sessionId, eventId))
        val store = sessionLookup(sessionId) ?: return
        if (store.pendingApproval?.eventId == eventId) store.setApproval(null)
        if (store.pendingQuestion?.eventId == eventId) store.setQuestion(null)
        syncAwaitingUser(sessionId, store)
    }

    private fun syncAwaitingUser(sessionId: SessionId, store: SessionStore) {
        val kind = pendingInteractionKind(store.pendingApproval, store.pendingQuestion)
        if (kind == null) listStore.clearAwaitingUser(sessionId) else listStore.markAwaitingUser(sessionId, kind)
    }

    // —— workspace/follow ——————————————————————————————————

    private fun handleWorkspace(frame: WorkspaceFollowFrame) {
        when (frame) {
            is WorkspaceFollowFrame.Baseline -> {
                workspaceStore.setWorkspaces(frame.items, frame.archivedSessionIds)
                // 冷启动/重连的基线也要把名册侧的归档镜像对齐，否则上一次连接留下的
                // 归档集合会继续影响主列表的渲染过滤（见 PhoneModel.handleSessionsArchived）。
                listStore.archivedSessionIds = frame.archivedSessionIds
            }
            is WorkspaceFollowFrame.Upsert -> workspaceStore.upsert(frame.workspace)
            is WorkspaceFollowFrame.Remove -> workspaceStore.remove(frame.workspaceId)
            is WorkspaceFollowFrame.Order -> workspaceStore.applyOrder(frame.workspaceIds)
            is WorkspaceFollowFrame.Archived -> {
                workspaceStore.setArchived(frame.archivedSessionIds)
                sessionsArchivedHandler(frame.archivedSessionIds)
            }
            WorkspaceFollowFrame.Unknown -> Unit
        }
    }

    // —— session/control ——————————————————————————————————

    private fun handleControl(frame: SessionControlFrame) {
        when (frame) {
            is SessionControlFrame.Baseline -> {
                queues.clear(); queues.putAll(frame.queues)
                jobs.clear(); jobs.putAll(frame.jobs)
                for (store in allSessions()) hydrateControlState(store)
                for ((sessionId, block) in frame.projections) {
                    for ((key, value) in block.values) {
                        applyControlProjection(SessionProjectionFrame(sessionId, key, value, block.asOfSeq))
                    }
                }
            }
            is SessionControlFrame.Queue -> {
                queues[frame.frame.sessionId] = frame.frame.items
                sessionLookup(frame.frame.sessionId)?.applyQueue(frame.frame.items)
            }
            is SessionControlFrame.Jobs -> {
                jobs[frame.frame.sessionId] = frame.frame.jobs
                sessionLookup(frame.frame.sessionId)?.setJobs(frame.frame)
            }
            is SessionControlFrame.Projection -> applyControlProjection(frame.frame)
            SessionControlFrame.Unknown -> Unit
        }
    }

    /**
     * 折叠一条 control 投影：写进对应的 store，并同步到扁平列表行。
     *
     * `inbox` 额外在这里镜像一份：基线之后才创建的 store 没有投影历史，待发队列必须靠
     * 这份最新值补上，口径等同被删掉的 queue 基线。
     */
    private fun applyControlProjection(frame: SessionProjectionFrame) {
        if (frame.key == "inbox") {
            decodeInbox(frame.value)?.let { inboxQueues[frame.sessionId] = rowsFromInbox(it) }
        }
        sessionLookup(frame.sessionId)?.applyProjection(frame)
        listStore.updateProjection(frame.sessionId, frame.key, frame.value, frame.seq)
    }

    fun hydrateControlState(store: SessionStore) {
        // 0.1.5 走 control 的 queues，0.1.6 走 inbox 投影；两者只会有一个有值。
        store.applyQueue(queues[store.sessionId] ?: inboxQueues[store.sessionId] ?: emptyList())
        store.setJobs(SessionJobsFrame(store.sessionId, jobs[store.sessionId] ?: emptyList()))
    }

    // —— session/follow ——————————————————————————————————

    private suspend fun handleFollow(sessionId: SessionId, frame: SessionFollowFrame) {
        when (frame) {
            is SessionFollowFrame.Snapshot -> {
                val store = sessionUpsert(sessionId)
                store.loadHistory(
                    SessionHistoryValue(frame.records, frame.hasMore, frame.projections),
                    replacing = true,
                )
                store.subscribe(frame.cursor)
                // 快照就是这条会话最新的窗口，落盘留给下次冷启动。写盘不挂在
                // 下行收集上：大窗口落盘要几十毫秒，不该拖住后面的实时帧。
                val cache = cache
                if (cache != null) {
                    val window = CachedWindow(frame.cursor, frame.hasMore, store.historyWindow)
                    scope.launch(Dispatchers.IO) { cache.saveWindow(sessionId, window) }
                }
                store.applyAssistantStreamBaseline(frame.assistantStream)
                // 快照的重放会把失败位重新抬起来，所以已读要在窗口落定之后再标一次
                //（对齐 Mac openHistoryIfNeeded 里那句 defer）。
                if (sessionIsOnScreen(sessionId)) store.markErrorSeen()
                val attempt = frame.assistantStream?.activeAttempt
                if (attempt == null) assistantExpectations.remove(sessionId)
                else assistantExpectations[sessionId] = attempt.attemptId to attempt.nextIndex
            }
            is SessionFollowFrame.Event -> applyLiveEvent(sessionId, frame.entry)
            is SessionFollowFrame.Stream -> {
                if (!foldAssistantStreamFrame(frame.frame, sessionId)) return
                sessionLookup(sessionId)?.applyAssistantStreamFrame(frame.frame)
            }
            SessionFollowFrame.Unknown -> Unit
        }
    }

    /** 侧信道的连续性闸门：漏一帧就重开这条 follow 重新拿基线。 */
    private fun foldAssistantStreamFrame(frame: AssistantStreamFrame, sessionId: SessionId): Boolean {
        when (frame) {
            is AssistantStreamFrame.Start -> {
                if (assistantExpectations[sessionId] != null) {
                    reopenAssistantStream(sessionId)
                    return false
                }
                assistantExpectations[sessionId] = frame.attemptId to 0
                return true
            }
            is AssistantStreamFrame.Chunk -> {
                val expectation = assistantExpectations[sessionId] ?: return false
                if (expectation.first != frame.attemptId) return false
                if (frame.index != expectation.second) {
                    reopenAssistantStream(sessionId)
                    return false
                }
                assistantExpectations[sessionId] = expectation.first to (frame.index + 1)
                return true
            }
            is AssistantStreamFrame.End -> {
                val expectation = assistantExpectations[sessionId] ?: return false
                if (expectation.first != frame.attemptId) return false
                if (frame.index != expectation.second) {
                    reopenAssistantStream(sessionId)
                    return false
                }
                assistantExpectations.remove(sessionId)
                return true
            }
        }
    }

    private fun reopenAssistantStream(sessionId: SessionId) {
        assistantExpectations.remove(sessionId)
        val address = followAddresses[sessionId] ?: return
        unfollow(sessionId)
        follow(sessionId, address)
    }

    internal suspend fun applyLiveEvent(sessionId: SessionId, entry: HistoryEntry) {
        val event = entry.event
        if (event.type == "turn/start") listStore.setRunning(sessionId, true)
        else if (event.type == "turn/end") listStore.setRunning(sessionId, false)
        sessionActivityTimestamp(event)?.let { listStore.recordActivity(sessionId, it) }
        // 失败 peek 搭在这一帧上，所以要在下面折叠/丢帧之前发出：正是用户切走的
        // 那条会话，它的失败最需要一张通知卡（与 Mac applyLiveEvent 同一顺序）。
        liveTurnFailureMessage(event)?.let { message ->
            attentionHandler(AttentionSignal.Failed(sessionId, message))
        }
        val store = sessionUpsert(sessionId)
        if (store.historyLoaded && event.seq > store.lastSeq + 1) {
            val generation = store.beginGapRepair(entry)
            if (generation != null) {
                val address = store.followAddress
                scope.launch {
                    try {
                        store.completeGapRepair(gapRepairLoader(address, store.repairCursor), generation)
                    } catch (e: Exception) {
                        Log.w(TAG, "gap repair failed: ${e.message}")
                        store.failGapRepair(generation)
                    }
                }
            }
        } else {
            store.apply(event, entry.view)
            // 失败算不算"已经看见"，只取决于用户是不是正看着这条会话。
            //
            // Mac 在 fold 之前就把不在屏的整帧丢掉了（foldsLiveEvents），于是那里的
            // markErrorSeen 只可能对屏上的会话执行；手机不丢帧（窗口要靠实时帧保持最新，
            // 选择时没有补洞那条路），所以必须在这里显式问一次。少了这一问，任何一条
            // 已登记 follow 的后台会话一出错就立刻被标成已读，列表行的红点永远不会亮。
            if (sessionIsOnScreen(sessionId)) store.markErrorSeen()
        }
    }
}

/** 通知中心消费的注意力事实。 */
sealed interface AttentionSignal {
    data class Running(val sessionId: SessionId, val running: Boolean) : AttentionSignal
    data class Failed(val sessionId: SessionId, val message: String) : AttentionSignal
    data class ApprovalRequested(val approval: ai.deepseek.dshphone.model.ApprovalRequested) : AttentionSignal
    data class QuestionAsked(val question: QuestionRequested) : AttentionSignal
    data class WaterfallRetired(val sessionId: SessionId, val eventId: String) : AttentionSignal
    /**
     * 会话从"运行"落回"停止"的完成事实。
     *
     * [Running] 那张边沿只存在 [StreamCoordinator] 处理 `\$events` 实时帧的那一刻；
     * 后台/断线期间错过的帧不会有第二次机会。而列表层每次基线刷新都会把上一次记下的
     * running 与基线里的 running 比一遍，于是这条"从基线里补出来的完成边沿"由
     * [ai.deepseek.dshphone.state.SessionListStore] 产出，交给同一套通知判定。
     */
    data class SessionCompleted(val sessionId: SessionId) : AttentionSignal
}

/** 只有真正的人类消息才推进会话活跃时间。 */
fun sessionActivityTimestamp(event: SessionEvent): Double? {
    if (event.type != "user/message") return null
    if (event.data["source"]["kind"].str != "user") return null
    return event.time
}

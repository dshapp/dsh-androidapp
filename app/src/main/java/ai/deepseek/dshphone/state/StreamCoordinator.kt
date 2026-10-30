package ai.deepseek.dshphone.state

import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.*
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * 连接期间常开三条流：$events / workspace/follow / session/control，
 * 外加每个打开会话一条 session/follow 与一条 job/list（0.1.7 把后台任务从 control
 * 流拆成了按会话订阅）。每条流都以完整基线开场，
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
    private val goalStateChangedHandler: (SessionId) -> Unit = {},
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
        fun jobsStreamId(sessionId: SessionId) = "jobs:$sessionId"
        /** 唯一的一条 `job/follow` 观察流（同一时刻只看一个任务的输出，同 web/Mac）。 */
        const val JOB_OUTPUT = "job-output"
        fun sessionIdOfJobs(streamId: String): SessionId? =
            if (streamId.startsWith("jobs:")) streamId.removePrefix("jobs:") else null
        /** 一个限时提问的前台等待认领（`userQuestions/attachWait`），按 waterfall 事件分。 */
        fun questionWaitStreamId(eventId: String) = "question-wait:$eventId"
        fun eventIdOfQuestionWait(streamId: String): String? =
            if (streamId.startsWith("question-wait:")) streamId.removePrefix("question-wait:") else null
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
    /**
     * 每个已认领限时提问的倒计时，按 waterfall 事件分。计时归 coordinator 而不归弹层
     *（web `PendingQuestion`）：弹层不在屏上也得按时超时，否则认领会把 agent 卡过期限。
     */
    private val questionTimers = mutableMapOf<String, Job>()
    /** 0.1.5 的 control 队列状态（baseline 的 queues 块与 queue 帧）；0.1.6 不再有这条通道。 */
    private val queues = mutableMapOf<SessionId, List<QueuedInboxItem>>()
    /**
     * 0.1.6 的 `inbox` 会话投影派生的待发队列。
     *
     * 0.1.6 把 queue 从 control 基线里删掉了，投影是唯一来源。这里另存一份镜像，是因为
     * 「基线之后才创建的 store」没有投影历史可回放，只能从这份最新值回灌（对齐 Mac 的 inboxQueues）。
     */
    private val inboxQueues = mutableMapOf<SessionId, List<QueuedInboxItem>>()
    private val followAddresses = mutableMapOf<SessionId, SessionAddress>()
    private val assistantExpectations = mutableMapOf<SessionId, Pair<String, Int>>()

    var onConnectionState: ((WsConnectionState) -> Unit)? = null

    /** 正在观察输出的那一个后台任务（`job/follow`）；观察跟着那一个可见的输出面板走。 */
    var observedJob by mutableStateOf<ObservedJobOutput?>(null)
        private set

    /** 为一个任务打开 `job/follow`，替换掉别的观察；对已在观察的同一任务幂等。 */
    fun observeJob(sessionId: SessionId, jobId: String) {
        val current = observedJob
        if (current?.sessionId == sessionId && current.jobId == jobId) return
        stopObservingJob()
        observedJob = ObservedJobOutput(sessionId, jobId)
        mux?.subscribe(
            JOB_OUTPUT,
            "job/follow",
            buildJsonObject {
                put("request", buildJsonObject {
                    put("sessionId", sessionId)
                    put("jobId", jobId)
                })
            },
        )
    }

    /** 关掉 [observeJob] 打开的观察；给了 [jobId] 时只在它正是这一条时才关。 */
    fun stopObservingJob(jobId: String? = null) {
        val current = observedJob ?: return
        if (jobId != null && current.jobId != jobId) return
        observedJob = null
        mux?.unsubscribe(JOB_OUTPUT)
    }

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
                    putHistoryWindow(maxMessages)
                    put("assistantStream", true)
                    // 0.2.2：让主机在后台唤起 Agent（与 Web 一致）；不带时冷会话一直 agentAvailable=false。
                    put("activate", true)
                })
            },
        )
        mux?.subscribe(
            jobsStreamId(sessionId),
            "job/list",
            buildJsonObject { put("request", buildJsonObject { put("sessionId", sessionId) }) },
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
        mux?.unsubscribe(jobsStreamId(sessionId))
        if (observedJob?.sessionId == sessionId) stopObservingJob()
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
        pendingEventSessions.keys.forEach { endQuestionWait(it) }
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
                if (event.streamId == JOB_OUTPUT) {
                    observedJob = observedJob?.copy(streaming = false, error = event.error.message)
                }
                sessionIdOfFollow(event.streamId)?.let { failFollow(it, event.error) }
            }
            is MuxEvent.End -> if (event.streamId == JOB_OUTPUT) observedJob = observedJob?.copy(streaming = false)
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
            JOB_OUTPUT -> observedJob = observedJob?.folding(decodeJobFollowFrame(value))
            else -> {
                eventIdOfQuestionWait(streamId)?.let { eventId ->
                    value["remainingMs"].dbl?.let { startQuestionCountdown(eventId, it) }
                    return
                }
                sessionIdOfJobs(streamId)?.let { sessionId ->
                    if (removedSessionIds.contains(sessionId)) return
                    decodeJobListFrame(value)?.let { sessionUpsert(sessionId).replaceJobs(it) }
                    return
                }
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
            "goal/activation-changed" -> {
                val value = args.firstOrNull() ?: return
                val sessionId = value["sessionId"].str ?: return
                val goal = value["goal"]
                sessionLookup(sessionId)?.setGoalActivation(goal["id"].str, goal["revision"].int, goal["activation"].str)
                goalStateChangedHandler(sessionId)
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
                val wait = frame.request["wait"]
                val question = QuestionRequested(frame.eventId, sessionId, questions, callId = wait["callId"].str)
                // 限时等待先认领再计时（web `answerQuestion`）：认领流的第一帧是主机算好的剩余时长，
                // 握着认领才轮到本端（而不是主机的计时器）决定何时超时。
                if (wait["timed"].bool == true && question.callId != null) {
                    mux?.subscribe(
                        questionWaitStreamId(frame.eventId),
                        "userQuestions/attachWait",
                        buildJsonObject {
                            put("agentId", sessionId)
                            put("callId", question.callId)
                        },
                    )
                }
                val store = sessionUpsert(sessionId)
                store.setQuestion(question)
                syncAwaitingUser(sessionId, store)
                attentionHandler(AttentionSignal.QuestionAsked(question))
            }
        }
    }

    /**
     * 本端自己应答了一个 waterfall：应答方收不到自己那份结果的撤回帧，所以在这里就地撤掉
     *（认领、倒计时、通知、边栏徽标）。
     */
    fun retireLocally(eventId: String?) {
        if (eventId != null) retireWaterfall(eventId)
    }

    private fun retireWaterfall(eventId: String) {
        endQuestionWait(eventId)
        val sessionId = pendingEventSessions.remove(eventId) ?: return
        attentionHandler(AttentionSignal.WaterfallRetired(sessionId, eventId))
        val store = sessionLookup(sessionId) ?: return
        if (store.pendingApproval?.eventId == eventId) store.setApproval(null)
        if (store.pendingQuestion?.eventId == eventId) store.setQuestion(null)
        syncAwaitingUser(sessionId, store)
    }

    /** 按主机给的剩余时间给一个已认领的提问上倒计时。 */
    private fun startQuestionCountdown(eventId: String, remainingMs: Double) {
        val sessionId = pendingEventSessions[eventId] ?: return
        val store = sessionLookup(sessionId) ?: return
        val question = store.pendingQuestion?.takeIf { it.eventId == eventId } ?: return
        if (question.held) return
        val delayMs = remainingMs.toLong().coerceAtLeast(0)
        store.setQuestion(question.copy(deadline = System.currentTimeMillis() + delayMs))
        questionTimers.remove(eventId)?.cancel()
        questionTimers[eventId] = scope.launch {
            kotlinx.coroutines.delay(delayMs)
            expireQuestion(eventId)
        }
    }

    /**
     * 用户开始作答或点了「慢慢回答」（web `engage`/`takeTime`）：停掉倒计时，认领一直握着，
     * agent 就一直等到本端提交或取消。
     */
    fun holdQuestion(question: QuestionRequested) {
        val eventId = question.eventId ?: return
        val store = sessionLookup(question.sessionId) ?: return
        val pending = store.pendingQuestion?.takeIf { it.eventId == eventId } ?: return
        if (pending.held || pending.deadline == null) return
        questionTimers.remove(eventId)?.cancel()
        store.setQuestion(pending.copy(held = true, deadline = null))
    }

    /**
     * 倒计时走完、用户没碰过：以 `ASK_TIMED_OUT` 回执 waterfall，主机把它映射成 pending 结果，
     * agent 继续干活；这组问题随后作为 continued 问题回来。弹层被本地收起也照样超时，
     * 否则 agent 会一直被这份认领挡着。
     */
    private suspend fun expireQuestion(eventId: String) {
        val sessionId = pendingEventSessions[eventId] ?: return
        val pending = sessionLookup(sessionId)?.pendingQuestion
        if (pending?.eventId == eventId && pending.held) return
        val clientId = remoteEventClientId ?: return
        api.settleRemoteEvent(
            clientId, eventId,
            RemoteEventOutcome.Rejected(
                name = "UserQuestionError",
                message = "ask_user_question timed out before the user answered",
                code = "ASK_TIMED_OUT",
            ),
        )
        retireWaterfall(eventId)
    }

    /** 放掉一个提问的认领与倒计时。 */
    private fun endQuestionWait(eventId: String) {
        questionTimers.remove(eventId)?.cancel()
        mux?.unsubscribe(questionWaitStreamId(eventId))
    }

    private fun syncAwaitingUser(sessionId: SessionId, store: SessionStore) {
        val kind = pendingInteractionKind(store.pendingApproval, store.pendingQuestion)
        if (kind == null) listStore.clearAwaitingUser(sessionId) else listStore.markAwaitingUser(sessionId, kind)
    }

    // —— workspace/follow ——————————————————————————————————

    private fun handleWorkspace(frame: WorkspaceFollowFrame) {
        when (frame) {
            is WorkspaceFollowFrame.Baseline -> {
                workspaceStore.setWorkspaces(frame.items, frame.archivedSessionIds, frame.pinnedSessionIds)
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
            is WorkspaceFollowFrame.Pinned -> workspaceStore.setPinned(frame.pinnedSessionIds)
            WorkspaceFollowFrame.Unknown -> Unit
        }
    }

    // —— session/control ——————————————————————————————————

    private fun handleControl(frame: SessionControlFrame) {
        when (frame) {
            is SessionControlFrame.Baseline -> {
                queues.clear(); queues.putAll(frame.queues)
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
        if (frame.key == "goal") goalStateChangedHandler(frame.sessionId)
        listStore.updateProjection(frame.sessionId, frame.key, frame.value, frame.seq)
    }

    fun hydrateControlState(store: SessionStore) {
        // 0.1.5 走 control 的 queues，0.1.6 走 inbox 投影；两者只会有一个有值。
        store.applyQueue(queues[store.sessionId] ?: inboxQueues[store.sessionId] ?: emptyList())
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
                goalStateChangedHandler(sessionId)
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
                        goalStateChangedHandler(sessionId)
                    } catch (e: Exception) {
                        Log.w(TAG, "gap repair failed: ${e.message}")
                        store.failGapRepair(generation)
                    }
                }
            }
        } else {
            store.apply(event, entry.view)
            if (event.type.startsWith("goal/")) goalStateChangedHandler(sessionId)
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

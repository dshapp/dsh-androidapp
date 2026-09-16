package ai.deepseek.dshphone.state

import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.*
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
) {
    companion object {
        private const val TAG = "StreamCoordinator"
        const val EVENTS = "events"
        const val WORKSPACES = "workspaces"
        const val CONTROL = "control"
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
    private val queues = mutableMapOf<SessionId, List<QueuedInboxItem>>()
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

    /** 打开一个会话的实时窗口，幂等。 */
    fun follow(sessionId: SessionId, address: SessionAddress? = null, maxMessages: Int = 50) {
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
    }

    /** 重开一条 follow：流失败后重试基线用（subscribe 幂等，失败的那条得先撤掉）。 */
    fun refollow(sessionId: SessionId) {
        val address = followAddresses[sessionId] ?: SessionAddress.Session(sessionId)
        unfollow(sessionId)
        follow(sessionId, address)
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
                jobs.remove(sessionId)
                unfollow(sessionId)
                sessionRemovedHandler(sessionId)
            }
            "api-session/status" -> {
                val sessionId = args.firstOrNull().str ?: return
                val running = args.getOrNull(1).bool ?: return
                listStore.setRunning(sessionId, running)
                sessionLookup(sessionId)?.running = running
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
            is WorkspaceFollowFrame.Baseline -> workspaceStore.setWorkspaces(frame.items, frame.archivedSessionIds)
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
                        sessionLookup(sessionId)?.applyProjection(
                            SessionProjectionFrame(sessionId, key, value, block.asOfSeq)
                        )
                        listStore.updateProjection(sessionId, key, value, block.asOfSeq)
                    }
                }
            }
            is SessionControlFrame.Queue -> {
                queues[frame.frame.sessionId] = frame.frame.items
                sessionLookup(frame.frame.sessionId)?.setQueue(frame.frame)
            }
            is SessionControlFrame.Jobs -> {
                jobs[frame.frame.sessionId] = frame.frame.jobs
                sessionLookup(frame.frame.sessionId)?.setJobs(frame.frame)
            }
            is SessionControlFrame.Projection -> {
                sessionLookup(frame.frame.sessionId)?.applyProjection(frame.frame)
                listStore.updateProjection(
                    frame.frame.sessionId, frame.frame.key, frame.frame.value, frame.frame.seq,
                )
            }
            SessionControlFrame.Unknown -> Unit
        }
    }

    fun hydrateControlState(store: SessionStore) {
        store.setQueue(SessionQueueFrame(store.sessionId, queues[store.sessionId] ?: emptyList()))
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
                store.applyAssistantStreamBaseline(frame.assistantStream)
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

    private suspend fun applyLiveEvent(sessionId: SessionId, entry: HistoryEntry) {
        val event = entry.event
        if (event.type == "turn/start") listStore.setRunning(sessionId, true)
        else if (event.type == "turn/end") listStore.setRunning(sessionId, false)
        sessionActivityTimestamp(event)?.let { listStore.recordActivity(sessionId, it) }
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
            store.markErrorSeen()
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
}

/** 只有真正的人类消息才推进会话活跃时间。 */
fun sessionActivityTimestamp(event: SessionEvent): Double? {
    if (event.type != "user/message") return null
    if (event.data["source"]["kind"].str != "user") return null
    return event.time
}

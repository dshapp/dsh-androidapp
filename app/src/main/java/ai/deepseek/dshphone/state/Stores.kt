package ai.deepseek.dshphone.state

import ai.deepseek.dshphone.model.SessionAwaitingKind
import ai.deepseek.dshphone.model.SessionProjectionsBlock
import ai.deepseek.dshphone.model.SessionSummary
import ai.deepseek.dshphone.model.WorkspaceView
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.net.WorkspaceId
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonElement

/** 会话列表：主机推来的会话清单加上本机侧的置顶/草稿/待办标记。 */
class SessionListStore(private val prefs: SharedPreferences?) {

    private var sessionsState by mutableStateOf<List<SessionSummary>>(emptyList())

    /**
     * 会话清单。整体赋值是唯一的写入口，顺手作废派生缓存（与 Mac `sessions.didSet` 同构）——
     * 其中就包括投影版本账本：它必须跟着行里的 projections 一起重建，否则一次
     * `session/list` 刷新把行上的 title 换没了之后，旧的 seq 还会把重新推来的 title 挡在门外。
     */
    var sessions: List<SessionSummary>
        get() = sessionsState
        private set(value) {
            sessionsState = value
            projectionSeqCache = null
        }
    var archivedSessionIds by mutableStateOf<List<SessionId>>(emptyList())
    var selectedSessionId: SessionId? = null

    var completedSessionIds by mutableStateOf<Set<SessionId>>(emptySet())
        private set
    var awaitingUserSessionIds by mutableStateOf<Set<SessionId>>(emptySet())
        private set
    var awaitingUserKinds by mutableStateOf<Map<SessionId, SessionAwaitingKind>>(emptyMap())
        private set
    var draftSessionIds by mutableStateOf<Set<SessionId>>(emptySet())
        private set
    var pinnedSessionOrder by mutableStateOf<List<SessionId>>(emptyList())
        private set
    var flatManualOrder by mutableStateOf<List<SessionId>>(emptyList())
        private set

    val pinnedSessionIds: Set<SessionId> get() = pinnedSessionOrder.toSet()
    val sessionsById: Map<SessionId, SessionSummary> get() = sessions.associateBy { it.sessionId }

    private val previousRunning = mutableMapOf<SessionId, Boolean>()

    /**
     * 每个键的投影版本：`asOfSeq` 是整块快照的截断点，但活跃的键会乱序到达，
     * 一个键前进不能让另一个键的更新看起来变旧。账本从行里的 projections 派生，
     * `sessions` 一变就重建，所以它永远不会比列表本身更"新"。
     */
    private var projectionSeqCache: MutableMap<SessionId, MutableMap<String, Int>>? = null
    private val projectionSeqBySession: MutableMap<SessionId, MutableMap<String, Int>>
        get() = projectionSeqCache ?: buildProjectionSeqs().also { projectionSeqCache = it }

    private fun buildProjectionSeqs(): MutableMap<SessionId, MutableMap<String, Int>> {
        val computed = mutableMapOf<SessionId, MutableMap<String, Int>>()
        for (summary in sessions) {
            val block = summary.projections ?: continue
            computed[summary.sessionId] = block.values.keys.associateWith { block.asOfSeq }.toMutableMap()
        }
        return computed
    }

    init {
        pinnedSessionOrder = readOrder(PINNED_KEY)
        flatManualOrder = readOrder(MANUAL_KEY)
    }

    private fun readOrder(key: String): List<SessionId> =
        prefs?.getString(key, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    private fun writeOrder(key: String, value: List<SessionId>) {
        prefs?.edit()?.putString(key, value.joinToString("\n"))?.apply()
    }

    // —— 拉取基线期间的增量录制（与 Mac beginBaselinePull/endBaselinePull 同构）——————
    //
    // `session/list` 是一次 HTTP 往返，而 `session/control` 基线（会话标题就在它的
    // projections 里）常常在往返途中就到了：那一刻列表还是空的，updateProjection
    // 找不到行直接丢帧，行上就只剩 cwd basename——看起来正是"只显示 workspace 名"，
    // 要等点开会话、SessionStore 拿到实时标题才补得回来。把拉取期间的增量录下来，
    // 等基线落库后原样重放，这些帧就不会再丢。

    private sealed interface RosterMutation {
        data class Upserted(val summary: SessionSummary) : RosterMutation
        data class Removed(val sessionId: SessionId) : RosterMutation
        data class Running(val sessionId: SessionId, val running: Boolean) : RosterMutation
        data class Activity(val sessionId: SessionId, val updatedAt: Double) : RosterMutation
        data class Engaged(val sessionId: SessionId) : RosterMutation
        data class Projection(
            val sessionId: SessionId,
            val key: String,
            val value: JsonElement,
            val seq: Int,
        ) : RosterMutation
    }

    /** 正在进行的那次拉取的录制位；没有拉取在跑时为 null。 */
    private var pullRecording: MutableList<RosterMutation>? = null
    /** 标记录制位的归属，后发的拉取不会被先发的那次收尾抹掉。 */
    private var pullRecordingToken = 0

    /** 开始为即将发起的拉取录制增量，返回收尾时要回传的 token。 */
    fun beginBaselinePull(): Int {
        pullRecordingToken++
        pullRecording = mutableListOf()
        return pullRecordingToken
    }

    /** 结束一次已被后发拉取取代的录制，什么都不落库。 */
    fun discardBaselinePull(token: Int) {
        if (pullRecordingToken == token) pullRecording = null
    }

    /**
     * 落库这次拉取的基线，并重放期间录下的每一条增量。
     * 这些 mutator 重放时都是幂等的：upsert 只把 blank 往下压，recordActivity 与
     * updateProjection 只进不退，setRunning 就是一次普通赋值。
     */
    fun endBaselinePull(items: List<SessionSummary>, token: Int) {
        if (pullRecordingToken != token) return
        val mutations = pullRecording ?: return
        pullRecording = null
        replaceSessions(items)
        for (mutation in mutations) apply(mutation)
    }

    private fun record(mutation: RosterMutation) {
        pullRecording?.add(mutation)
    }

    private fun apply(mutation: RosterMutation) {
        when (mutation) {
            is RosterMutation.Upserted -> upsert(mutation.summary)
            is RosterMutation.Removed -> remove(mutation.sessionId)
            is RosterMutation.Running -> setRunning(mutation.sessionId, mutation.running)
            is RosterMutation.Activity -> recordActivity(mutation.sessionId, mutation.updatedAt)
            is RosterMutation.Engaged -> markEngaged(mutation.sessionId)
            is RosterMutation.Projection ->
                updateProjection(mutation.sessionId, mutation.key, mutation.value, mutation.seq)
        }
    }

    private fun replaceSessions(items: List<SessionSummary>) {
        sessions = items.sortedByDescending { it.updatedAt }
        syncCompletedNotifications()
    }

    fun upsert(summary: SessionSummary) {
        record(RosterMutation.Upserted(summary))
        val index = sessions.indexOfFirst { it.sessionId == summary.sessionId }
        if (index < 0) {
            sessions = (sessions + summary).sortedByDescending { it.updatedAt }
            reconcileCompleted(summary)
            return
        }
        val existing = sessions[index]
        val merged = existing.copy(
            blank = existing.blank && summary.blank,
            cwd = existing.cwd ?: summary.cwd,
            parentSessionId = existing.parentSessionId ?: summary.parentSessionId,
            origin = existing.origin ?: summary.origin,
            agentPreset = summary.agentPreset ?: existing.agentPreset,
        )
        sessions = sessions.toMutableList().also { it[index] = merged }
        reconcileCompleted(merged)
    }

    fun remove(sessionId: SessionId) {
        record(RosterMutation.Removed(sessionId))
        sessions = sessions.filterNot { it.sessionId == sessionId }
        previousRunning.remove(sessionId)
        completedSessionIds = completedSessionIds - sessionId
        awaitingUserSessionIds = awaitingUserSessionIds - sessionId
        awaitingUserKinds = awaitingUserKinds - sessionId
        draftSessionIds = draftSessionIds - sessionId
        if (pinnedSessionIds.contains(sessionId)) setPinned(sessionId, false)
    }

    fun recordActivity(sessionId: SessionId, updatedAt: Double) {
        record(RosterMutation.Activity(sessionId, updatedAt))
        val index = sessions.indexOfFirst { it.sessionId == sessionId }
        if (index < 0 || updatedAt <= sessions[index].updatedAt) return
        val updated = sessions.toMutableList()
        updated[index] = updated[index].copy(updatedAt = updatedAt)
        sessions = updated.sortedByDescending { it.updatedAt }
    }

    fun markEngaged(sessionId: SessionId) {
        record(RosterMutation.Engaged(sessionId))
        val index = sessions.indexOfFirst { it.sessionId == sessionId }
        if (index < 0 || !sessions[index].blank) return
        sessions = sessions.toMutableList().also { it[index] = it[index].copy(blank = false) }
    }

    fun setRunning(sessionId: SessionId, running: Boolean) {
        record(RosterMutation.Running(sessionId, running))
        val index = sessions.indexOfFirst { it.sessionId == sessionId }
        if (index < 0) return
        val updated = sessions.toMutableList()
        updated[index] = updated[index].copy(running = running, blank = if (running) false else updated[index].blank)
        sessions = updated
        reconcileCompleted(updated[index])
    }

    /**
     * 把一帧 `session/projection` 镜像进这条会话的列表行快照（与 Mac updateProjection 同构）：
     * 列表行的标题只认 `projections["title"]`，而 `session/list` 的投影列来自主机侧缓存、
     * `host/session-added` 更是完全不带投影，所以没打开过的会话全靠这条路拿到标题。
     * 乱序/重复帧按键比对 seq，只有更新的才覆盖；行还没进列表（帧跑到 `session-added` 前面）
     * 时录制/重放兜底，不在录制期就交给下一次完整刷新。
     */
    fun updateProjection(sessionId: SessionId, key: String, value: JsonElement, seq: Int) {
        record(RosterMutation.Projection(sessionId, key, value, seq))
        val index = sessions.indexOfFirst { it.sessionId == sessionId }
        if (index < 0) return
        val seqs = projectionSeqBySession
        if (seq <= (seqs[sessionId]?.get(key) ?: -1)) return
        val existing = sessions[index].projections
        val values = (existing?.values ?: emptyMap()).toMutableMap()
        values[key] = value
        val updated = sessions.toMutableList()
        updated[index] = updated[index].copy(
            projections = SessionProjectionsBlock(maxOf(existing?.asOfSeq ?: 0, seq), values)
        )
        sessions = updated
        // 上一行作废了账本缓存，这里把刚算好的账本连同本键的新版本重新装回去，
        // 免得高频投影帧每来一帧都重建一遍。
        seqs.getOrPut(sessionId) { mutableMapOf() }[key] = seq
        projectionSeqCache = seqs
    }

    fun setPinned(sessionId: SessionId, pinned: Boolean) {
        pinnedSessionOrder = if (pinned) {
            if (pinnedSessionOrder.contains(sessionId)) pinnedSessionOrder else pinnedSessionOrder + sessionId
        } else {
            pinnedSessionOrder.filterNot { it == sessionId }
        }
        writeOrder(PINNED_KEY, pinnedSessionOrder)
    }

    fun pinnedOrder(visible: List<SessionSummary>): List<SessionSummary> {
        if (pinnedSessionOrder.isEmpty()) return emptyList()
        val byId = visible.associateBy { it.sessionId }
        return pinnedSessionOrder.mapNotNull { byId[it] }
    }

    fun manualOrder(visible: List<SessionSummary>): List<SessionSummary> {
        if (flatManualOrder.isEmpty()) return visible.sortedByDescending { it.updatedAt }
        val position = flatManualOrder.withIndex().associate { (index, id) -> id to index }
        val known = visible.filter { position.containsKey(it.sessionId) }.sortedBy { position[it.sessionId] }
        val fresh = visible.filterNot { position.containsKey(it.sessionId) }.sortedByDescending { it.updatedAt }
        return known + fresh
    }

    fun statusOrder(visible: List<SessionSummary>, erroredIds: Set<SessionId>): List<SessionSummary> =
        visible.sortedWith(
            compareBy<SessionSummary> { summary ->
                val id = summary.sessionId
                when {
                    summary.running -> 0
                    erroredIds.contains(id) -> 1
                    completedSessionIds.contains(id) -> 2
                    awaitingUserSessionIds.contains(id) -> 3
                    draftSessionIds.contains(id) -> 4
                    else -> 5
                }
            }.thenByDescending { it.updatedAt }
        )

    fun reorderWithinSubset(moving: SessionId, before: SessionId?, within: List<SessionSummary>) {
        val subsetIds = within.map { it.sessionId }.toMutableList()
        val from = subsetIds.indexOf(moving)
        if (from < 0 || moving == before) return
        subsetIds.removeAt(from)
        val insertAt = before?.let { subsetIds.indexOf(it).takeIf { i -> i >= 0 } } ?: subsetIds.size
        subsetIds.add(insertAt, moving)
        val subsetSet = subsetIds.toSet()
        val result = flatManualOrder.toMutableList()
        val slots = result.indices.filter { result[it] in subsetSet }
        val iterator = subsetIds.iterator()
        for (slot in slots) {
            if (!iterator.hasNext()) break
            result[slot] = iterator.next()
        }
        val appended = mutableListOf<SessionId>()
        while (iterator.hasNext()) appended.add(iterator.next())
        flatManualOrder = result + appended
        writeOrder(MANUAL_KEY, flatManualOrder)
    }

    fun setDraft(sessionId: SessionId, hasDraft: Boolean) {
        draftSessionIds = if (hasDraft) draftSessionIds + sessionId else draftSessionIds - sessionId
    }

    fun markAwaitingUser(sessionId: SessionId, kind: SessionAwaitingKind) {
        awaitingUserSessionIds = awaitingUserSessionIds + sessionId
        awaitingUserKinds = awaitingUserKinds + (sessionId to kind)
    }

    fun clearAwaitingUser(sessionId: SessionId) {
        awaitingUserSessionIds = awaitingUserSessionIds - sessionId
        awaitingUserKinds = awaitingUserKinds - sessionId
    }

    fun clearAllAwaitingUser() {
        awaitingUserSessionIds = emptySet()
        awaitingUserKinds = emptyMap()
    }

    fun clearCompleted(sessionId: SessionId) {
        completedSessionIds = completedSessionIds - sessionId
    }

    private fun syncCompletedNotifications() {
        val seen = sessions.map { it.sessionId }.toSet()
        sessions.forEach { reconcileCompleted(it) }
        previousRunning.keys.filterNot { it in seen }.forEach { previousRunning.remove(it) }
        completedSessionIds = completedSessionIds.filter { it in seen }.toSet()
    }

    private fun reconcileCompleted(summary: SessionSummary) {
        val previous = previousRunning[summary.sessionId]
        if (previous == null) {
            previousRunning[summary.sessionId] = summary.running
            return
        }
        if (previous && !summary.running) {
            if (summary.sessionId != selectedSessionId) {
                completedSessionIds = completedSessionIds + summary.sessionId
            }
        } else if (summary.running) {
            completedSessionIds = completedSessionIds - summary.sessionId
        }
        previousRunning[summary.sessionId] = summary.running
    }

    companion object {
        private const val PINNED_KEY = "pinned_session_order"
        private const val MANUAL_KEY = "flat_session_order"
    }
}

/** 工作区注册表。 */
class WorkspaceStore {
    var workspaces by mutableStateOf<List<WorkspaceView>>(emptyList())
        private set
    var archivedSessionIds by mutableStateOf<List<SessionId>>(emptyList())
        private set

    val workspaceTitleBySession: Map<SessionId, String>
        get() = buildMap {
            for (workspace in workspaces) for (id in workspace.sessionIds) put(id, workspace.title)
        }

    fun setWorkspaces(items: List<WorkspaceView>, archived: List<SessionId>) {
        workspaces = items
        archivedSessionIds = archived
    }

    fun upsert(workspace: WorkspaceView) {
        val index = workspaces.indexOfFirst { it.workspaceId == workspace.workspaceId }
        workspaces = if (index < 0) workspaces + workspace
        else workspaces.toMutableList().also { it[index] = workspace }
    }

    fun remove(workspaceId: WorkspaceId) {
        workspaces = workspaces.filterNot { it.workspaceId == workspaceId }
    }

    fun applyOrder(order: List<WorkspaceId>) {
        val byId = workspaces.associateBy { it.workspaceId }
        workspaces = order.mapNotNull { byId[it] } + workspaces.filterNot { order.contains(it.workspaceId) }
    }

    fun setArchived(ids: List<SessionId>) { archivedSessionIds = ids }
}

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

    var sessions by mutableStateOf<List<SessionSummary>>(emptyList())
        private set
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
    private val projectionSeqBySession = mutableMapOf<SessionId, MutableMap<String, Int>>()

    init {
        pinnedSessionOrder = readOrder(PINNED_KEY)
        flatManualOrder = readOrder(MANUAL_KEY)
    }

    private fun readOrder(key: String): List<SessionId> =
        prefs?.getString(key, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    private fun writeOrder(key: String, value: List<SessionId>) {
        prefs?.edit()?.putString(key, value.joinToString("\n"))?.apply()
    }

    fun replaceSessions(items: List<SessionSummary>) {
        sessions = items.sortedByDescending { it.updatedAt }
        syncCompletedNotifications()
    }

    fun upsert(summary: SessionSummary) {
        val index = sessions.indexOfFirst { it.sessionId == summary.sessionId }
        if (index < 0) {
            sessions = (sessions + summary).sortedByDescending { it.updatedAt }
            summary.projections?.let { block ->
                projectionSeqBySession[summary.sessionId] =
                    block.values.keys.associateWith { block.asOfSeq }.toMutableMap()
            }
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
        sessions = sessions.filterNot { it.sessionId == sessionId }
        previousRunning.remove(sessionId)
        projectionSeqBySession.remove(sessionId)
        completedSessionIds = completedSessionIds - sessionId
        awaitingUserSessionIds = awaitingUserSessionIds - sessionId
        awaitingUserKinds = awaitingUserKinds - sessionId
        draftSessionIds = draftSessionIds - sessionId
        if (pinnedSessionIds.contains(sessionId)) setPinned(sessionId, false)
    }

    fun recordActivity(sessionId: SessionId, updatedAt: Double) {
        val index = sessions.indexOfFirst { it.sessionId == sessionId }
        if (index < 0 || updatedAt <= sessions[index].updatedAt) return
        val updated = sessions.toMutableList()
        updated[index] = updated[index].copy(updatedAt = updatedAt)
        sessions = updated.sortedByDescending { it.updatedAt }
    }

    fun markEngaged(sessionId: SessionId) {
        val index = sessions.indexOfFirst { it.sessionId == sessionId }
        if (index < 0 || !sessions[index].blank) return
        sessions = sessions.toMutableList().also { it[index] = it[index].copy(blank = false) }
    }

    fun setRunning(sessionId: SessionId, running: Boolean) {
        val index = sessions.indexOfFirst { it.sessionId == sessionId }
        if (index < 0) return
        val updated = sessions.toMutableList()
        updated[index] = updated[index].copy(running = running, blank = if (running) false else updated[index].blank)
        sessions = updated
        reconcileCompleted(updated[index])
    }

    fun updateProjection(sessionId: SessionId, key: String, value: JsonElement, seq: Int) {
        val index = sessions.indexOfFirst { it.sessionId == sessionId }
        if (index < 0) return
        val seqs = projectionSeqBySession.getOrPut(sessionId) { mutableMapOf() }
        if (seq <= (seqs[key] ?: -1)) return
        val existing = sessions[index].projections
        val values = (existing?.values ?: emptyMap()).toMutableMap()
        values[key] = value
        val updated = sessions.toMutableList()
        updated[index] = updated[index].copy(
            projections = SessionProjectionsBlock(maxOf(existing?.asOfSeq ?: 0, seq), values)
        )
        sessions = updated
        seqs[key] = seq
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

package ai.deepseek.dshphone.state

import ai.deepseek.dshphone.model.SessionAwaitingKind
import ai.deepseek.dshphone.model.SessionProjectionsBlock
import ai.deepseek.dshphone.model.isSubagentOrigin
import ai.deepseek.dshphone.model.SessionSummary
import ai.deepseek.dshphone.model.WorkspaceView
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.net.WorkspaceId
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonElement
import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.ApiClient
import ai.deepseek.dshphone.net.describeHostSettings
import ai.deepseek.dshphone.net.listConfigurableProviders
import ai.deepseek.dshphone.net.describeCredentialRefs
import ai.deepseek.dshphone.net.readAgentPresetDocument
import ai.deepseek.dshphone.net.mutateHostSettings
import kotlinx.coroutines.CancellationException

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
            runningSubagentRootsCache = null
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
    var flatManualOrder by mutableStateOf<List<SessionId>>(emptyList())
        private set

    val sessionsById: Map<SessionId, SessionSummary> get() = sessions.associateBy { it.sessionId }

    private val previousRunning = mutableMapOf<SessionId, Boolean>()

    /**
     * 从基线里补出来的"运行 -> 停止"边沿。
     *
     * 每会话只发一次：`reconcileCompleted` 在每个会话上每个 running 周期只会观察到
     * 一次 true->false，翻转后 `previousRunning` 也被写成 false，不会重复触发。
     */
    var onCompleted: ((SessionId) -> Unit)? = null

    /**
     * 每个键的投影版本：`asOfSeq` 是整块快照的截断点，但活跃的键会乱序到达，
     * 一个键前进不能让另一个键的更新看起来变旧。账本从行里的 projections 派生，
     * `sessions` 一变就重建，所以它永远不会比列表本身更"新"。
     */
    private var projectionSeqCache: MutableMap<SessionId, MutableMap<String, Int>>? = null

    /** [runningSubagentRoots] 的缓存；sessions 一变就作废（与 Mac 同一处失效点）。 */
    private var runningSubagentRootsCache: Set<SessionId>? = null

    /**
     * 正在跑的子智能体所牵连到的祖先会话。
     *
     * 子智能体只在父会话的子智能体面板里存在、也不进顶层列表，所以一条「父会话自己早已停下、
     * 派出去的子智能体还在跑」的行，光看 summary.running 会被当成空闲——状态排序于是把它排到
     * 一堆无关会话后面，而它其实正是最需要用户回来看的那条。Mac 为此维护 runningSubagentRoots
     * （Stores.swift 的 computeRunningSubagentRoots），并且只在 origin == "subagent" 这条边上
     * 往父走（与子智能体面板的 stop-at-fork 规则一致）。
     *
     * 从整份名单算，不是从传进 [statusOrder] 的可见子集算：子智能体行本身不在可见子集里。
     */
    val runningSubagentRoots: Set<SessionId>
        get() = runningSubagentRootsCache ?: computeRunningSubagentRoots().also { runningSubagentRootsCache = it }

    /** 子 → 父一次，每个在跑的子智能体都把它的祖先链标上；父环靠 seen 集合兜底。 */
    private fun computeRunningSubagentRoots(): Set<SessionId> {
        val parentOf = mutableMapOf<SessionId, SessionId>()
        for (summary in sessions) {
            val parent = if (summary.isSubagentOrigin) summary.parentSessionId else null
            if (parent != null) parentOf[summary.sessionId] = parent
        }
        val roots = mutableSetOf<SessionId>()
        for (summary in sessions) {
            if (!summary.running || !summary.isSubagentOrigin) continue
            var current = summary.sessionId
            val seen = mutableSetOf(current)
            while (true) {
                val parent = parentOf[current] ?: break
                if (!seen.add(parent)) break
                roots.add(parent)
                current = parent
            }
        }
        return roots
    }
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
        flatManualOrder = readOrder(MANUAL_KEY)
        // 上次进程结束时还在跑的那些会话。进程被系统回收后重开时，内存里的
        // `previousRunning` 已经没了，只有这份落盘的记录能让"运行 -> 停止"的边沿
        // 重新成立，完成通知才补得回来。
        readOrder(RUNNING_KEY).forEach { previousRunning[it] = true }
    }

    private fun persistRunning() {
        writeOrder(RUNNING_KEY, previousRunning.filterValues { it }.keys.toList())
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

    /**
     * 用本机缓存里的清单铺底，让冷启动立刻有列表可画。
     *
     * 只在还没有任何远端数据时铺（连接刚开始、列表是空的），所以它永远不会盖掉
     * 已经落库的基线；随后 refreshInternal 会把整份换成权威答案。title/agentPreset
     * 这些投影随行一起进来，投影账本也因此重建，和一次正常刷新没有区别。
     */
    fun seedFromCache(items: List<SessionSummary>) {
        if (items.isEmpty() || sessions.isNotEmpty()) return
        replaceSessions(items)
    }

    fun upsert(summary: SessionSummary) {
        record(RosterMutation.Upserted(summary))
        val index = sessions.indexOfFirst { it.sessionId == summary.sessionId }
        if (index < 0) {
            sessions = (sessions + summary).sortedByDescending { it.updatedAt }
            reconcileCompleted(summary)
            persistRunning()
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
        persistRunning()
    }

    fun remove(sessionId: SessionId) {
        record(RosterMutation.Removed(sessionId))
        sessions = sessions.filterNot { it.sessionId == sessionId }
        previousRunning.remove(sessionId)
        persistRunning()
        completedSessionIds = completedSessionIds - sessionId
        awaitingUserSessionIds = awaitingUserSessionIds - sessionId
        awaitingUserKinds = awaitingUserKinds - sessionId
        draftSessionIds = draftSessionIds - sessionId
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
        persistRunning()
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

    fun manualOrder(visible: List<SessionSummary>): List<SessionSummary> {
        if (flatManualOrder.isEmpty()) return visible.sortedByDescending { it.updatedAt }
        val position = flatManualOrder.withIndex().associate { (index, id) -> id to index }
        val known = visible.filter { position.containsKey(it.sessionId) }.sortedBy { position[it.sessionId] }
        val fresh = visible.filterNot { position.containsKey(it.sessionId) }.sortedByDescending { it.updatedAt }
        return known + fresh
    }

    /**
     * `orderBy: "status"`：按"最需要我"的档位分档，档内再按最近活动。
     *
     * 档位规则本身在 [sessionStatusRank]（纯函数，与 Mac `SessionStatusOrder.rank` 同序）。
     * 之前这里是内联的 when，把"等我批准"和"等我回答"挤进同一档：两者同时挂着时，
     * 等回答的会话会凭 updatedAt 排到等批准的前面，用户无从判断先看哪个。
     */
    fun statusOrder(visible: List<SessionSummary>, erroredIds: Set<SessionId>): List<SessionSummary> =
        visible
            .map { summary ->
                summary to sessionStatusRank(
                    isRunning = summary.running || runningSubagentRoots.contains(summary.sessionId),
                    isUnread = completedSessionIds.contains(summary.sessionId),
                    didFail = erroredIds.contains(summary.sessionId),
                    // 集合是"是否停在这"的权威，kind 表只负责命名：没有名字的标记
                    // 仍按批准排档，与行上徽标的兜底同一口径。
                    awaiting = if (awaitingUserSessionIds.contains(summary.sessionId)) {
                        awaitingUserKinds[summary.sessionId] ?: SessionAwaitingKind.APPROVAL
                    } else {
                        null
                    },
                    hasDraft = draftSessionIds.contains(summary.sessionId),
                )
            }
            .sortedWith(compareBy<Pair<SessionSummary, SessionStatusRank>> { it.second.key }.thenByDescending { it.first.updatedAt })
            .map { it.first }

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
        persistRunning()
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
            // 无论当前选没选中都上报：判定"要不要弹"是通知中心的事（它自己比
            // appIsActive / visibleSessionId），这里只负责把边沿交出去。
            onCompleted?.invoke(summary.sessionId)
        } else if (summary.running) {
            completedSessionIds = completedSessionIds - summary.sessionId
        }
        previousRunning[summary.sessionId] = summary.running
    }

    companion object {
        private const val MANUAL_KEY = "flat_session_order"
        /** 落盘的"上次还在跑"的会话集合，用于进程重启后的完成补发。 */
        private const val RUNNING_KEY = "running_session_ids"
    }
}

/** Host facts and editor state have separate lifetimes: broadcast refresh never rebases a draft. */
class HostSettingsStore {
    var visible by mutableStateOf(false)
    var loading by mutableStateOf(false)
        private set
    var writable by mutableStateOf(false)
        private set
    var rows by mutableStateOf<List<ProviderSettingsRow>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var credentialError by mutableStateOf<String?>(null)
        private set
    var draft by mutableStateOf<ProviderCatalogDraft?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    var presetId by mutableStateOf<String?>(null)
        private set
    var presetDocument by mutableStateOf<AgentPresetDocument?>(null)
        private set
    var presetError by mutableStateOf<String?>(null)
        private set
    private var generation = 0L
    private var loadToken = 0L
    private var presetToken = 0L

    /** Call when switching/disconnecting hosts; low revisions belong to the new generation. */
    fun reset() {
        generation++; loadToken++; presetToken++
        loading = false; writable = false; rows = emptyList(); error = null; credentialError = null
        draft = null; saving = false; saveError = null; closePreset()
    }

    /** Pause/navigation invalidates pending reads without discarding a provider draft. */
    fun suspendReads() {
        visible = false; loadToken++; loading = false; closePreset()
    }

    /** Exposed separately for deterministic offline fact-refresh regression tests. */
    fun applyFacts(settings: SettingsDescribeValue, providers: List<ConfigurableProviderView>, credentials: Map<String, CredentialInfo>) {
        writable = settings.writable
        val namespaces = settings.namespaces.associateBy { it.ns }
        rows = providers.map { entry ->
            val row = ProviderSettingsRow(entry, namespaces[entry.settingsNs])
            row.copy(credential = row.credentialRef?.let { credentials[it] })
        }
        // draft.openedNamespace and expectedRevision intentionally stay unchanged.
    }

    suspend fun refresh(api: ApiClient) {
        val host = generation
        val token = ++loadToken
        loading = true
        try {
            val settings = api.describeHostSettings()
            val providers = api.listConfigurableProviders()
            val namespaces = settings.namespaces.associateBy { it.ns }
            val refs = providers.mapNotNull { ProviderSettingsRow(it, namespaces[it.settingsNs]).credentialRef }.distinct().sorted()
            var keyError: String? = null
            val credentials = try { if (refs.isEmpty()) emptyMap() else api.describeCredentialRefs(refs) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { keyError = e.message ?: "Credential facts unavailable"; emptyMap() }
            if (host != generation || token != loadToken) return
            applyFacts(settings, providers, credentials)
            error = null; credentialError = keyError
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (host == generation && token == loadToken) {
                error = e.message ?: "Settings unavailable"
                writable = false // stale facts must never authorize writes after a failed refresh
            }
        } finally { if (host == generation && token == loadToken) loading = false }
    }

    fun beginCatalog(row: ProviderSettingsRow) {
        if (saving || !row.catalogSupported) return
        saveError = null
        try { draft = ProviderCatalogDraft.open(row) }
        catch (e: Exception) { saveError = e.message }
    }
    val catalogWritable: Boolean get() {
        val current = draft ?: return false
        return writable && rows.any { it.entry == current.entry && it.catalogSupported } && !saving
    }
    fun editCatalog(index: Int, update: (CatalogModelDraft) -> CatalogModelDraft) {
        if (!catalogWritable) return
        val current = draft ?: return
        draft = current.copy(rows = current.rows.mapIndexed { i, row -> if (i == index) update(row) else row }, owned = true, touched = true)
        saveError = null
    }
    fun addCatalogModel() {
        if (!catalogWritable) return
        draft = draft?.let { it.copy(rows = it.rows + CatalogModelDraft(kotlinx.serialization.json.JsonObject(emptyMap())), owned = true, touched = true) }
    }
    fun removeCatalogModel(index: Int) {
        if (!catalogWritable) return
        draft = draft?.let { it.copy(rows = it.rows.filterIndexed { i, _ -> i != index }, owned = true, touched = true) }
    }
    fun resetCatalogModels() { if (catalogWritable) { draft = draft?.reset(); saveError = null } }
    fun cancelCatalog() { if (!saving) { draft = null; saveError = null } }

    suspend fun saveCatalog(api: ApiClient) {
        if (!visible || !catalogWritable) return
        val opened = draft ?: return
        val host = generation
        try {
            val mutation = opened.mutation() ?: run { draft = null; return }
            saving = true; saveError = null
            api.mutateHostSettings(mutation)
            if (host != generation) return
            if (draft === opened) draft = null
            refresh(api)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (host == generation) saveError = e.message ?: "Save failed; draft retained"
            // Conflict is deliberately not retried or rebased onto fresh facts.
        } finally { if (host == generation) saving = false }
    }

    fun closePreset() { presetToken++; presetId = null; presetDocument = null; presetError = null }
    suspend fun readPreset(api: ApiClient, id: String) {
        val host = generation
        val token = ++presetToken
        presetId = id; presetDocument = null; presetError = null
        try {
            val document = api.readAgentPresetDocument(id)
            require(document.agentPreset == id) { "Preset response identity mismatch" }
            if (host == generation && token == presetToken && presetId == id) presetDocument = document
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (host == generation && token == presetToken) presetError = e.message ?: "Preset YAML unavailable" }
    }
}

/** 工作区注册表。 */
class WorkspaceStore {
    var workspaces by mutableStateOf<List<WorkspaceView>>(emptyList())
        private set
    var archivedSessionIds by mutableStateOf<List<SessionId>>(emptyList())
        private set
    /**
     * 置顶集，最近置顶的在前。0.1.7 起由主机保存（`workspace/follow` 的基线与 `pinned` 帧），
     * 多端共享同一份，本地不再落盘。
     */
    var pinnedSessionIds by mutableStateOf<List<SessionId>>(emptyList())
        private set
    val pinnedSessionSet: Set<SessionId> get() = pinnedSessionIds.toSet()

    fun setPinned(ids: List<SessionId>) { if (ids != pinnedSessionIds) pinnedSessionIds = ids }

    /** 按主机的置顶顺序挑出可见行。 */
    fun pinnedOrder(visible: List<SessionSummary>): List<SessionSummary> {
        if (pinnedSessionIds.isEmpty()) return emptyList()
        val byId = visible.associateBy { it.sessionId }
        return pinnedSessionIds.mapNotNull { byId[it] }
    }

    val workspaceTitleBySession: Map<SessionId, String>
        get() = buildMap {
            // 同一会话挂在多个工作区时取第一个（web `deriveSearchResults` 的 workspaceBySession）。
            for (workspace in workspaces) for (id in workspace.sessionIds) if (id !in this) put(id, workspace.title)
        }

    fun setWorkspaces(items: List<WorkspaceView>, archived: List<SessionId>, pinned: List<SessionId>) {
        workspaces = items
        archivedSessionIds = archived
        setPinned(pinned)
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

    /**
     * 把一个工作区挪到另一个工作区之前，[beforeWorkspaceId] 为空表示挪到末尾
     * （与 Mac `WorkspaceStore.moveWorkspace` 同构）。
     *
     * 锚点用 id 而不是下标：列表里只渲染「有会话的工作区」，下标是渲染序，跟注册表的
     * 真实顺序对不上；而且这次本地移动随后还要交给主机落定，主机认的也是 id。
     * 返回是否真的动过，没动就不必发请求。
     */
    fun moveWorkspace(workspaceId: WorkspaceId, beforeWorkspaceId: WorkspaceId?): Boolean {
        if (workspaceId == beforeWorkspaceId) return false
        val from = workspaces.indexOfFirst { it.workspaceId == workspaceId }
        if (from < 0) return false
        if (beforeWorkspaceId != null && workspaces.none { it.workspaceId == beforeWorkspaceId }) return false
        val next = workspaces.toMutableList()
        val moved = next.removeAt(from)
        val insertAt = beforeWorkspaceId
            ?.let { id -> next.indexOfFirst { it.workspaceId == id }.takeIf { it >= 0 } }
            ?: next.size
        next.add(insertAt, moved)
        if (next.map { it.workspaceId } == workspaces.map { it.workspaceId }) return false
        workspaces = next
        return true
    }

    fun setArchived(ids: List<SessionId>) { archivedSessionIds = ids }
}

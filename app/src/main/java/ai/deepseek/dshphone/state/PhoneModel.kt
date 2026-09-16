package ai.deepseek.dshphone.state

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.*
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.TimeZone
import java.util.UUID

sealed interface Connection {
    data object Idle : Connection
    data object Connecting : Connection
    data object Connected : Connection
    data class Failed(val message: String) : Connection
}

/** 输入区里等待发送的一件附件。 */
sealed interface PhoneAttachmentPayload {
    data class Image(val mediaType: String, val base64: String, val preview: ByteArray) : PhoneAttachmentPayload
    data class FilePayload(val file: File) : PhoneAttachmentPayload
}

data class PhoneAttachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val payload: PhoneAttachmentPayload,
)

/** 输入区触发建议的一条候选。 */
data class PhoneSuggestion(
    val id: String,
    val title: String,
    val subtitle: String?,
    val icon: String,
    val insert: String,
)

/** 草稿末尾的触发 token。 */
data class TriggerToken(val kind: Kind, val query: String, val start: Int, val end: Int) {
    enum class Kind { REFERENCE, COMMAND }

    companion object {
        fun parse(draft: String): TriggerToken? {
            if (draft.isEmpty() || draft.endsWith(" ") || draft.endsWith("\n")) return null
            val lastBreak = draft.indexOfLast { it == ' ' || it == '\n' }
            val start = lastBreak + 1
            val token = draft.substring(start)
            val first = token.firstOrNull() ?: return null
            return when {
                first == '@' -> TriggerToken(Kind.REFERENCE, token.drop(1), start, draft.length)
                first == '/' && start == 0 -> TriggerToken(Kind.COMMAND, token.drop(1), start, draft.length)
                else -> null
            }
        }
    }
}

/**
 * iOS PhoneModel 的对应物：连接、会话、发送，与各 Store 的编排。
 */
class PhoneModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app.applicationContext
    private val prefs = appContext.getSharedPreferences("dsh_phone", android.content.Context.MODE_PRIVATE)

    val profiles = ServerProfileStore(appContext)
    val listStore = SessionListStore(prefs)
    var workspaceStore by mutableStateOf(WorkspaceStore())
        private set

    var connection by mutableStateOf<Connection>(Connection.Idle)
        private set
    var socketState by mutableStateOf<WsConnectionState?>(null)
        private set

    val sessions = mutableStateMapOf<SessionId, SessionStore>()
    var selectedSessionId by mutableStateOf<SessionId?>(null)
    var lastErrorMessage by mutableStateOf<String?>(null)
    var agentPresets by mutableStateOf<List<AgentPresetEntry>>(emptyList())
        private set
    var searchHits by mutableStateOf<List<SessionSearchItem>?>(null)
        private set
    /// 列表下拉刷新是否在飞行中。
    var refreshing by mutableStateOf(false)
        private set

    private var api: ApiClient? = null
    private var coordinator: StreamCoordinator? = null
    /// 已经建立连接的配置；用来让重复的 connectCurrent 变成空操作。
    private var connectedProfileId: String? = null
    /** 会话清单拉取的代次；旧的一次回来晚了也不会盖掉新的一次。 */
    private var sessionListRefreshGeneration = 0
    private val imageCache = mutableMapOf<String, Bitmap>()
    private val drafts = mutableMapOf<SessionId, String>()

    var notifier: ((AttentionSignal) -> Unit)? = null

    val currentStore: SessionStore? get() = selectedSessionId?.let { sessions[it] }

    // —— 连接 ——————————————————————————————————————————————————

    fun connectCurrent() {
        val profile = profiles.current
        if (profile == null) {
            connection = Connection.Idle
            return
        }
        connect(profile)
    }

    fun connect(profile: ServerProfile) {
        // Activity 重建（旋屏、换深色）会重跑 connectCurrent；同一条配置已连上就直接返回，
        // 否则 teardown 会清掉会话窗口，正在看的转写会变成空白。
        if (profile.id == connectedProfileId && api != null && connection is Connection.Connected) return
        viewModelScope.launch {
            teardown()
            connection = Connection.Connecting
            profiles.setCurrent(profile.id)
            val client = ApiClient(profile.target())
            api = client
            val streamCoordinator = StreamCoordinator(
                api = client,
                scope = viewModelScope,
                listStore = listStore,
                workspaceStore = workspaceStore,
                sessionLookup = { sessions[it] },
                sessionUpsert = { store(it) },
                allSessions = { sessions.values.toList() },
                remoteEventHandler = { event -> handleRemoteInvalidation(event) },
                sessionRemovedHandler = { handleSessionRemoved(it) },
                sessionsArchivedHandler = { ids -> ids.forEach { handleSessionRemoved(it) } },
                resyncHandler = { viewModelScope.launch { resyncAfterReconnect() } },
                gapRepairLoader = { address, throughSeq ->
                    when (address) {
                        is SessionAddress.Session ->
                            client.sessionHistory(address.sessionId, throughSeq, null, 50)
                        is SessionAddress.Subagent ->
                            client.subagentHistory(address.address, throughSeq, null, 50)
                    }
                },
                attentionHandler = { signal -> notifier?.invoke(signal) },
            )
            streamCoordinator.onConnectionState = { socketState = it }
            coordinator = streamCoordinator
            connectedProfileId = profile.id
            streamCoordinator.start()
            refreshInternal(markConnected = profile.id)
        }
    }

    private suspend fun refreshInternal(markConnected: String? = null) {
        val client = api ?: return
        sessionListRefreshGeneration++
        val generation = sessionListRefreshGeneration
        // 拉取期间落地的流帧先录下来，等基线落库后重放（与 Mac refreshSessions 同构）：
        // `session/control` 基线带着每个会话的标题投影，往返途中到达时列表还是空的，
        // 不录就整批丢掉，列表行只好退回 cwd basename，看起来就是"只有 workspace 名"。
        val token = listStore.beginBaselinePull()
        try {
            val value = client.sessionList()
            if (generation != sessionListRefreshGeneration) return
            listStore.endBaselinePull(value.items, token)
            connection = Connection.Connected
            markConnected?.let { profiles.markConnected(it) }
        } catch (e: Exception) {
            if (generation != sessionListRefreshGeneration) return
            listStore.discardBaselinePull(token)
            connection = Connection.Failed(describe(e))
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshInternal() }
    }

    /// 下拉刷新：重拉会话列表，并把当前会话的窗口对齐到最新。
    fun pullToRefresh() {
        if (refreshing) return
        viewModelScope.launch {
            refreshing = true
            try {
                refreshInternal()
                selectedSessionId?.let { id -> sessions[id]?.let { loadHistory(it) } }
            } finally {
                refreshing = false
            }
        }
    }

    private fun handleRemoteInvalidation(event: String) {
        viewModelScope.launch {
            when (event) {
                "agent-preset/selected", "settings/document-updated" -> loadAgentPresets()
            }
            if (event == "llm/adapters-updated" || event == "settings/document-updated") {
                sessions.keys.toList().forEach { loadModels(it) }
            }
            if (event == "connection/reset") refreshInternal()
        }
    }

    private fun handleSessionRemoved(sessionId: SessionId) {
        sessions.remove(sessionId)
        listStore.remove(sessionId)
        if (selectedSessionId == sessionId) {
            selectedSessionId = null
            lastErrorMessage = Strings.sessionGone
        }
    }

    private suspend fun resyncAfterReconnect() {
        refreshInternal()
        val sessionId = selectedSessionId ?: return
        val store = sessions[sessionId] ?: return
        // 重连后每条已注册的流都会重开，follow 的 snapshot 帧会重新送来完整基线；
        // 这里清空窗口即可，不要用没有游标的分页去抢跑。
        store.resetWindow()
        loadHistory(store)
    }

    suspend fun teardown() {
        coordinator?.stop()
        coordinator = null
        api?.invalidate()
        api = null
        connectedProfileId = null
        sessions.clear()
        selectedSessionId = null
        workspaceStore = WorkspaceStore()
        connection = Connection.Idle
    }

    fun onForeground() { coordinator?.probeNow() }

    // —— 会话 ——————————————————————————————————————————————————

    private fun store(id: SessionId): SessionStore =
        sessions.getOrPut(id) { SessionStore(id).also { coordinator?.hydrateControlState(it) } }

    fun open(sessionId: SessionId) {
        viewModelScope.launch {
            selectedSessionId = sessionId
            listStore.selectedSessionId = sessionId
            listStore.clearCompleted(sessionId)
            val store = store(sessionId)
            // 基线由 session/follow 的 snapshot 帧给出：它自带 records / hasMore / projections。
            // 这里的分页只兜底"已经有实时事件、却还没有基线"的 store（通知唤起的那种）；
            // 完全空白的新 store 没有游标，loadHistory 会直接跳过，转写保持加载态等 snapshot。
            coordinator?.follow(sessionId)
            if (!store.historyLoaded) loadHistory(store)
        }
    }

    suspend fun loadHistory(store: SessionStore) {
        val client = api ?: return
        // 没有权威游标就不发 session/page：throughSeq 只能填一个假的 0，
        // 主机会当成"截止到第 0 条事件"回一页非消息事件，反而把转写打成空态。
        val throughSeq = store.historyCursor
        if (throughSeq < 0) return
        store.loadingHistory = true
        val loadingOlder = store.historyLoaded
        try {
            val value = client.sessionHistory(
                store.sessionId,
                throughSeq,
                if (loadingOlder) store.firstSeq else null,
                50,
            )
            store.loadHistory(value, replacing = !loadingOlder)
            store.historyError = null
        } catch (e: Exception) {
            store.historyError = HistoryError("internal", describe(e))
        } finally {
            store.loadingHistory = false
        }
    }

    /** 基线取不回来时的重试：清掉错误并重开这条 follow。 */
    fun retryHistory(sessionId: SessionId) {
        val store = sessions[sessionId] ?: return
        store.historyError = null
        coordinator?.refollow(sessionId)
        viewModelScope.launch { if (!store.historyLoaded) loadHistory(store) }
    }

    fun loadEarlier(store: SessionStore) {
        if (!store.hasMore || store.loadingHistory) return
        viewModelScope.launch { loadHistory(store) }
    }

    /** 为轮次跳转分页到指定序号。 */
    suspend fun loadHistoryThrough(store: SessionStore, seq: Int) {
        var pages = 0
        while (store.hasMore && (store.firstSeq ?: Int.MAX_VALUE) > seq && pages < 20) {
            val before = store.firstSeq
            loadHistory(store)
            if (store.firstSeq == before) return
            pages++
        }
    }

    suspend fun loadHistoryForPrompt(store: SessionStore, turn: Int) {
        var pages = 0
        while (store.hasMore && store.turnRail.firstOrNull { it.turn == turn }?.promptRow == null && pages < 20) {
            val before = store.firstSeq
            loadHistory(store)
            if (store.firstSeq == before) return
            pages++
        }
    }

    /** 新建会话：优先复用该工作区下已有的空白会话。 */
    suspend fun createSession(workspaceId: WorkspaceId?): SessionId? {
        val client = api ?: return null
        val workspace = workspaceId?.let { id -> workspaceStore.workspaces.firstOrNull { it.workspaceId == id } }
        if (workspace != null) {
            val archived = workspaceStore.archivedSessionIds.toSet()
            val reuse = listStore.sessions.firstOrNull {
                it.blank && it.cwd == workspace.path &&
                    workspace.sessionIds.contains(it.sessionId) && it.sessionId !in archived
            }
            if (reuse != null) {
                open(reuse.sessionId)
                return reuse.sessionId
            }
        }
        return try {
            val value = client.sessionCreate(workspaceId)
            refreshInternal()
            open(value.sessionId)
            value.sessionId
        } catch (e: Exception) {
            lastErrorMessage = describe(e)
            null
        }
    }

    // —— 发送 ——————————————————————————————————————————————————

    suspend fun send(text: String, sessionId: SessionId, attachments: List<PhoneAttachment>): Boolean {
        val client = api ?: return false
        val store = sessions[sessionId] ?: return false
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return false
        val requestId = UUID.randomUUID().toString()
        store.beginPendingSubmission(requestId, if (store.running) "queued" else "transcript", trimmed)
        val content = mutableListOf<PromptContentPart>()
        if (trimmed.isNotEmpty()) content.add(PromptContentPart.Text(trimmed))
        try {
            for (attachment in attachments) {
                when (val payload = attachment.payload) {
                    is PhoneAttachmentPayload.Image ->
                        content.add(PromptContentPart.Image(payload.mediaType, payload.base64, attachment.name))
                    is PhoneAttachmentPayload.FilePayload ->
                        content.add(PromptContentPart.File(client.uploadFile(sessionId, payload.file, attachment.name)))
                }
            }
        } catch (e: Exception) {
            store.retirePendingSubmission(requestId)
            lastErrorMessage = describe(e)
            return false
        }
        return try {
            val accepted = client.sessionPrompt(
                sessionId, "queue", content, TimeZone.getDefault().id, requestId,
            )
            if (!accepted) {
                store.retirePendingSubmission(requestId)
                return false
            }
            store.blank = false
            store.running = true
            listStore.markEngaged(sessionId)
            true
        } catch (e: Exception) {
            store.retirePendingSubmission(requestId)
            store.historyError = HistoryError("internal", describe(e))
            false
        }
    }

    suspend fun steer(text: String, sessionId: SessionId): Boolean {
        val client = api ?: return false
        val store = sessions[sessionId] ?: return false
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val requestId = UUID.randomUUID().toString()
        store.beginPendingSubmission(requestId, "steering", trimmed)
        return try {
            client.sessionPrompt(
                sessionId, "steer", listOf(PromptContentPart.Text(trimmed)),
                TimeZone.getDefault().id, requestId,
            )
            true
        } catch (e: Exception) {
            store.retirePendingSubmission(requestId)
            lastErrorMessage = describe(e)
            false
        }
    }

    fun stop(sessionId: SessionId) {
        viewModelScope.launch { runCatching { api?.sessionCancel(sessionId) } }
    }

    fun removeQueued(itemId: MessageId, sessionId: SessionId) {
        viewModelScope.launch { runCatching { api?.sessionUpdateQueue(sessionId, itemId, QueueAction.Remove) } }
    }

    fun editQueued(itemId: MessageId, text: String, sessionId: SessionId) {
        viewModelScope.launch { runCatching { api?.sessionUpdateQueue(sessionId, itemId, QueueAction.Edit(text)) } }
    }

    fun steerQueued(itemId: MessageId, sessionId: SessionId) {
        viewModelScope.launch { runCatching { api?.sessionUpdateQueue(sessionId, itemId, QueueAction.Steer) } }
    }

    // —— 草稿 ——————————————————————————————————————————————————

    fun draft(sessionId: SessionId): String =
        drafts[sessionId] ?: prefs.getString(draftKey(sessionId), "") ?: ""

    fun setDraft(text: String, sessionId: SessionId) {
        drafts[sessionId] = text
        listStore.setDraft(sessionId, text.isNotBlank())
        if (text.isEmpty()) prefs.edit().remove(draftKey(sessionId)).apply()
        else prefs.edit().putString(draftKey(sessionId), text).apply()
    }

    private fun draftKey(sessionId: SessionId) = "phone.draft.$sessionId"

    /** 附件是否越过主机公布的上限。 */
    fun attachmentRejection(attachments: List<PhoneAttachment>, sessionId: SessionId): String? {
        val limits = sessions[sessionId]?.imageLimits ?: return null
        val images = attachments.mapNotNull {
            (it.payload as? PhoneAttachmentPayload.Image)?.let { image ->
                image.mediaType to image.base64.length / 4 * 3
            }
        }
        if (images.size > limits.maxImagesPerMessage) {
            return fmt(Strings.tooManyImages, limits.maxImagesPerMessage)
        }
        images.firstOrNull { it.second > limits.maxImageBytes }?.let {
            return fmt(Strings.imageTooLarge, formatBytes(it.second))
        }
        if (images.sumOf { it.second } > limits.maxMessageImageBytes) {
            return fmt(Strings.imagesTooLarge, formatBytes(limits.maxMessageImageBytes))
        }
        images.firstOrNull { it.first !in limits.mediaTypes }?.let {
            return fmt(Strings.imageTypeUnsupported, it.first)
        }
        return null
    }

    // —— 会话管理 ——————————————————————————————————————————————

    fun search(query: String) {
        viewModelScope.launch {
            val trimmed = query.trim()
            val client = api
            if (client == null || trimmed.isEmpty()) {
                searchHits = null
                return@launch
            }
            searchHits = runCatching { client.sessionSearch(trimmed).items }.getOrNull()
        }
    }

    fun rename(sessionId: SessionId, title: String) {
        viewModelScope.launch {
            try {
                api?.sessionRename(sessionId, title)
                refreshInternal()
            } catch (e: Exception) {
                lastErrorMessage = describe(e)
            }
        }
    }

    suspend fun fork(sessionId: SessionId, atSeq: Int? = null): SessionId? {
        val client = api ?: return null
        return try {
            val value = client.sessionFork(sessionId, atSeq)
            refreshInternal()
            value.sessionId
        } catch (e: Exception) {
            lastErrorMessage = describe(e)
            null
        }
    }

    fun archive(sessionId: SessionId) {
        viewModelScope.launch {
            try {
                api?.workspaceArchiveSession(sessionId)
                sessions.remove(sessionId)
                listStore.remove(sessionId)
            } catch (e: Exception) {
                lastErrorMessage = describe(e)
            }
        }
    }

    fun openOnHost(path: String) {
        viewModelScope.launch {
            try { api?.hostOpenPath(path) } catch (e: Exception) { lastErrorMessage = describe(e) }
        }
    }

    // —— 子智能体 ——————————————————————————————————————————————

    suspend fun loadSubagents(parentSessionId: SessionId): List<SubagentListEntry> =
        runCatching { api?.subagentList(parentSessionId)?.entries }.getOrNull() ?: emptyList()

    suspend fun openSubagent(
        parentSessionId: SessionId,
        entry: SubagentListEntry,
        parentAvailable: Boolean,
    ): SessionId? {
        if (!entry.isChild) return null
        val address = SubagentAddress(parentSessionId, entry.sessionId, entry.mode)
        val store = store(entry.sessionId)
        store.configureSubagent(address, parentAvailable)
        entry.label?.let { store.title = it }
        store.running = entry.running
        selectedSessionId = entry.sessionId
        coordinator?.follow(entry.sessionId, SessionAddress.Subagent(address))
        if (!store.historyLoaded) loadHistory(store)
        return entry.sessionId
    }

    suspend fun sendToSubagent(text: String, sessionId: SessionId): Boolean {
        val client = api ?: return false
        val store = sessions[sessionId] ?: return false
        val address = store.subagentAddress ?: return false
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return try {
            client.subagentPrompt(address, trimmed, TimeZone.getDefault().id)
            store.running = true
            true
        } catch (e: Exception) {
            lastErrorMessage = describe(e)
            false
        }
    }

    fun interruptSubagent(sessionId: SessionId) {
        viewModelScope.launch {
            sessions[sessionId]?.subagentAddress?.let { runCatching { api?.subagentInterrupt(it) } }
        }
    }

    // —— 触发建议 ——————————————————————————————————————————————

    suspend fun suggestions(token: TriggerToken, sessionId: SessionId): List<PhoneSuggestion> {
        val client = api ?: return emptyList()
        return when (token.kind) {
            TriggerToken.Kind.REFERENCE -> {
                val files = runCatching { client.fileReferencesList(sessionId, token.query) }.getOrDefault(emptyList())
                val hits = runCatching { client.sessionReferenceCandidates(sessionId, token.query) }
                    .getOrDefault(emptyList())
                files.take(8).map {
                    PhoneSuggestion("file:${it.path}", pathBasename(it.path), it.path,
                        if (it.kind == "directory") "folder" else "doc", "@${it.path} ")
                } + hits.take(5).map {
                    PhoneSuggestion("session:${it.sessionId}", it.label, it.cwd, "chat", "${it.mention} ")
                }
            }
            TriggerToken.Kind.COMMAND -> {
                val needle = token.query.lowercase()
                val commands = runCatching { client.commandsList(sessionId) }.getOrDefault(emptyList())
                val skills = runCatching { client.skillList(sessionId).skills }.getOrDefault(emptyList())
                commands.filter { needle.isEmpty() || it.name.lowercase().contains(needle) }.take(8).map {
                    PhoneSuggestion("cmd:${it.name}", "/${it.name}", it.description, "command", "/${it.name} ")
                } + skills.filter { needle.isEmpty() || it.name.lowercase().contains(needle) }.take(5).map {
                    PhoneSuggestion("skill:${it.name}", "$${it.name}", it.description, "sparkle", "$${it.name} ")
                }
            }
        }
    }

    // —— 目标 ——————————————————————————————————————————————————

    fun createGoal(objective: String, maxGoalRounds: Int?, sessionId: SessionId) {
        viewModelScope.launch {
            try { api?.goalCreate(sessionId, objective, maxGoalRounds) }
            catch (e: Exception) { lastErrorMessage = describe(e) }
        }
    }

    fun editGoal(objective: String, sessionId: SessionId) {
        viewModelScope.launch {
            val ref = sessions[sessionId]?.goalRef ?: return@launch
            try { api?.goalEdit(sessionId, ref, objective) }
            catch (e: Exception) { lastErrorMessage = describe(e) }
        }
    }

    fun toggleGoalPause(sessionId: SessionId) {
        viewModelScope.launch {
            val store = sessions[sessionId] ?: return@launch
            val goal = store.goal ?: return@launch
            val ref = store.goalRef ?: return@launch
            try {
                if (goal.phase == "paused") api?.goalResume(sessionId, ref) else api?.goalPause(sessionId, ref)
            } catch (e: Exception) { lastErrorMessage = describe(e) }
        }
    }

    fun clearGoal(sessionId: SessionId) {
        viewModelScope.launch {
            val ref = sessions[sessionId]?.goalRef ?: return@launch
            try { api?.goalClear(sessionId, ref) } catch (e: Exception) { lastErrorMessage = describe(e) }
        }
    }

    // —— 模型 / 权限 / 预设 / 计划 ————————————————————————————

    suspend fun loadModels(sessionId: SessionId) {
        val client = api ?: return
        val store = sessions[sessionId] ?: return
        var directory = store.modelDirectory.beginLoading()
        val generation = directory.generation
        store.modelDirectory = directory
        try {
            val value = client.sessionModels()
            store.modelDirectory = store.modelDirectory.receive(value, generation)
        } catch (e: Exception) {
            store.modelDirectory = store.modelDirectory.fail(describe(e), generation)
        }
    }

    fun selectModel(selection: ModelSelection, sessionId: SessionId) {
        viewModelScope.launch {
            val client = api ?: return@launch
            val store = sessions[sessionId] ?: return@launch
            val directory = store.modelDirectory.beginSelecting()
            val generation = directory.generation
            store.modelDirectory = directory
            try {
                val value = client.sessionSelectModel(
                    sessionId, selection.provider, selection.model, selection.reasoningEffort,
                )
                store.modelDirectory = store.modelDirectory.receive(value, generation)
            } catch (e: Exception) {
                store.modelDirectory = store.modelDirectory.fail(describe(e), generation)
                lastErrorMessage = describe(e)
            }
        }
    }

    fun selectPermission(presetId: String, sessionId: SessionId) {
        if (presetId == "custom") return
        viewModelScope.launch {
            try { api?.sessionSelectPermission(sessionId, presetId) }
            catch (e: Exception) { lastErrorMessage = describe(e) }
        }
    }

    suspend fun loadAgentPresets() {
        runCatching { api?.agentPresetList()?.presets }.getOrNull()?.let { agentPresets = it }
    }

    suspend fun selectAgentPreset(id: String, sessionId: SessionId): Boolean = try {
        api?.agentPresetSelect(sessionId, id)
        true
    } catch (e: Exception) {
        lastErrorMessage = describe(e)
        false
    }

    fun setPlanMode(on: Boolean, sessionId: SessionId) = runCommand(PlanModeToggle.line(on), sessionId)

    fun compactContext(sessionId: SessionId) = runCommand("/compact", sessionId)

    private fun runCommand(line: String, sessionId: SessionId) {
        viewModelScope.launch {
            try { api?.commandsExecute(sessionId, line) }
            catch (e: Exception) { lastErrorMessage = describe(e) }
        }
    }

    // —— 审批与提问 ————————————————————————————————————————————

    fun respondApproval(approval: ApprovalRequested, outcome: ApprovalOutcome) {
        viewModelScope.launch {
            settle(approval.eventId, RemoteEventOutcome.Result(
                kotlinx.serialization.json.JsonPrimitive(outcome.wire)
            ))
            sessions[approval.sessionId]?.setApproval(null)
            listStore.clearAwaitingUser(approval.sessionId)
        }
    }

    fun respondQuestion(question: QuestionRequested, answers: List<QuestionAnswer>) {
        viewModelScope.launch {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("answers", kotlinx.serialization.json.buildJsonArray {
                    for (answer in answers) {
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("id", kotlinx.serialization.json.JsonPrimitive(answer.id))
                            put("selected", kotlinx.serialization.json.buildJsonArray {
                                answer.selected.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                            })
                            answer.custom?.let { put("custom", kotlinx.serialization.json.JsonPrimitive(it)) }
                        })
                    }
                })
            }
            settle(question.eventId, RemoteEventOutcome.Result(payload))
            sessions[question.sessionId]?.setQuestion(null)
            listStore.clearAwaitingUser(question.sessionId)
        }
    }

    fun cancelQuestion(question: QuestionRequested) {
        viewModelScope.launch {
            settle(question.eventId, RemoteEventOutcome.Next)
            sessions[question.sessionId]?.setQuestion(null)
            listStore.clearAwaitingUser(question.sessionId)
        }
    }

    private suspend fun settle(eventId: String?, outcome: RemoteEventOutcome) {
        val client = api ?: return
        val clientId = coordinator?.remoteEventClientId ?: return
        if (eventId == null) return
        client.settleRemoteEvent(clientId, eventId, outcome)
    }

    // —— 文件与图片 ————————————————————————————————————————————

    suspend fun workspaceFilePage(sessionId: SessionId, path: String, offset: Int): WorkspaceFileTextValue {
        val client = api ?: throw internalError(Strings.disconnected)
        return client.workspaceFileRead(sessionId, path, offset)
    }

    suspend fun attachmentImage(sessionId: SessionId, ref: ImageAttachmentRef): Bitmap? {
        imageCache[ref.attachmentId]?.let { return it }
        val client = api ?: return null
        return withContext(Dispatchers.IO) {
            val value = runCatching { client.sessionAttachment(sessionId, ref.attachmentId) }.getOrNull()
                ?: return@withContext null
            val bytes = runCatching { Base64.decode(value.data, Base64.DEFAULT) }.getOrNull()
                ?: return@withContext null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            imageCache[ref.attachmentId] = bitmap
            bitmap
        }
    }

    /** 导出会话日志到缓存文件，交给系统分享。 */
    fun exportSessionLog(sessionId: SessionId): File? {
        val store = sessions[sessionId] ?: return null
        return runCatching {
            val file = File(appContext.cacheDir, "$sessionId.log.json")
            val json = DshJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(HistoryEntry.serializer()),
                store.historyWindow,
            )
            file.writeText(json)
            file
        }.getOrNull()
    }

    companion object {
        fun describe(error: Throwable): String =
            (error as? ApiException)?.detail ?: error.message ?: error.toString()
    }
}

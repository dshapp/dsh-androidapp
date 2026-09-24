package ai.deepseek.dshphone.state

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.*
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    /** 带参数的命令的用法提示（如 `[off|message]`），只用于显示，不参与插入。 */
    val hint: String? = null,
    /** 这条命令声明了 `input`：只有行首上下文才允许召唤（Mac `commandCandidates` 的 position 规则）。 */
    val wantsArgs: Boolean = false,
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
 *
 * 进程级的会话模型：`PhoneModel` 挂在 [android.app.Application] 上，而不是 Activity 的
 * ViewModel 上。
 *
 * 退到后台时 Activity 会被销毁，挂在它身上的 ViewModel 跟着走，连接和正在跑的
 * `viewModelScope` 一起没了——后台也就再也收不到 `\$events` 的完成/失败帧，通知自然不发。
 * 放到 Application 上之后，只要进程还在（前台服务会保它不死），连接就一直在。
 *
 * `get` 只由 [DshApplication] 持有实例，是这条替换唯一的入口。
 */
/** 与主机 workspaceFiles 的 maxBytes 默认值一致；超过会被拒，不会被截短。 */
internal const val CHUNK_BYTES = 2 * 1024 * 1024

/**
 * 按 [CHUNK_BYTES] 分片把一个工作区文件写进 [out]，返回写入的字节数。
 *
 * 分片而不是一次读完，是因为主机 readAll 最多能给到 32 MiB，base64 之后更大，
 * 解成字节时两份同时在内存里，低端机容易 OOM；分片读则只有当前这一片在内存里。
 *
 * 三条退出规则，任何一条不满足就抛错，交给调用方去提示：
 *  · 第一片带回的 [WorkspaceFileBytesValue.bytes] 就超过 [limit]，直接放弃，不白下一遍；
 *  · 累计写入超过 [limit]，停在超限那一刻（调用方负责删掉半截文件）；
 *  · [WorkspaceFileBytesValue.eof] 为假却给回空片，说明主机没有推进，再循环就是死转。
 *
 * 只做字节搬运，不碰网络：[fetch] 由调用方提供，测试里可以直接喂假数据。
 */
internal suspend fun copyWorkspaceFileChunked(
    out: java.io.OutputStream,
    limit: Long,
    fetch: suspend (offset: Int, length: Int) -> WorkspaceFileBytesValue,
): Long {
    var offset = 0
    var written = 0L
    while (true) {
        val window = fetch(offset, CHUNK_BYTES)
        if (written == 0L && window.bytes.toLong() > limit) {
            throw internalError("file is too large to open")
        }
        // minSdk 26 起 java.util.Base64 可用；这里不用 android.util.Base64，
        // 一是纯 JVM 可测，二是 MIME 解码器的宽容度与 android DEFAULT 一致。
        val chunk = java.util.Base64.getMimeDecoder().decode(window.data)
        if (chunk.isNotEmpty()) {
            out.write(chunk)
            written += chunk.size
        }
        if (written > limit) throw internalError("file is too large to open")
        if (window.eof) break
        if (chunk.isEmpty()) throw internalError("file read stalled")
        offset += chunk.size
    }
    return written
}

class PhoneModel private constructor(app: Application) {

    private val appContext = app.applicationContext

    /**
     * 替代原来的 `viewModelScope`：作用域跟着进程走，不跟 Activity 走。
     * `SupervisorJob` 让一个子任务失败不会连坐掉整个连接循环。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val appScope: CoroutineScope get() = scope
    private val prefs = appContext.getSharedPreferences("dsh_phone", android.content.Context.MODE_PRIVATE)

    val profiles = ServerProfileStore(appContext)

    /** 本机外观偏好（语言、主题）；设置页读写，MainActivity 在组合前先按它定主题。 */
    val settings = SettingsStore(appContext)

    val listStore = SessionListStore(prefs)
    /** 每轮改动文件的汇总与比较（0.1.6 的 ui-deliverables 路由）。 */
    val turnChanges = TurnChangesStore(scope)

    /** 按公告地址读一次那一轮的改动汇总；UI 不必自己拿 APIClient。 */
    fun loadTurnChanges(address: TurnChangesAddress) {
        api?.let { turnChanges.load(address, it) }
    }

    /** 读一次某个列出的文件的比较。 */
    fun loadTurnChangesDiff(address: TurnChangesAddress, index: Int) {
        api?.let { turnChanges.loadDiff(address, index, it) }
    }
    /**
     * 本机缓存。用来 stale-while-revalidate：先把上次的清单/窗口画出来，
     * 远端基线回来整体替换。谁也没连接上时（profile 为空）静默不用。
     */
    private var cache: SessionCache? = null
    var workspaceStore by mutableStateOf(WorkspaceStore())
        private set

    var connection by mutableStateOf<Connection>(Connection.Idle)
        private set
    var socketState by mutableStateOf<WsConnectionState?>(null)
        private set

    val sessions = mutableStateMapOf<SessionId, SessionStore>()
    var selectedSessionId by mutableStateOf<SessionId?>(null)
    /**
     * 此刻真的显示在屏幕上的会话：会话页在场就是它，退回列表就是 null。
     *
     * 和 [selectedSessionId] 分开记：那一条是"最后打开过的会话"，会话页退场之后还
     * 留着，拿它当"用户已经看见了"的判据，会把这条会话之后所有的完成/失败通知一路
     * 吞掉。Mac 上窗口始终显示着选中的会话，两者是同一件事；手机上由导航栈决定。
     */
    var visibleSessionId by mutableStateOf<SessionId?>(null)
    /** App 是否在前台（对齐 Mac 的 `AgentNotificationConditions.appIsActive`）。 */
    var appIsActive by mutableStateOf(false)
    var lastErrorMessage by mutableStateOf<String?>(null)
    var agentPresets by mutableStateOf<List<AgentPresetEntry>>(emptyList())
        private set
    /**
     * 主机是否允许新会话选择 Agent 模式（`agentPresets/list` 的 `modeSelectionEnabled`）。
     *
     * 关掉时新会话统一走部署默认模式：Web 收起选择器并清掉暂存的选择，Mac/iOS 的
     * `agentPresetPickerEnabled` 也不给入口。主机侧 `agentPresets/select` 并不校验这个
     * 策略，所以摆出选择器不只是多一个入口，而是真的能把预设选上去、绕过部署的默认边界。
     */
    var agentPresetPickerEnabled by mutableStateOf(true)
        private set
    /** 进程级权限目录（0.1.6）；0.1.5 主机上始终是空的，各 store 退回投影内联的候选。 */
    var permissionCatalog by mutableStateOf<List<PermissionPresetOption>>(emptyList())
        private set
    var searchHits by mutableStateOf<List<SessionSearchItem>?>(null)
        private set
    /** 当前这次搜索的查询串；结果与它一起发布，旧查询的晚到结果据此丢弃。 */
    var searchQuery by mutableStateOf("")
        private set
    /**
     * 主机内容搜索是否不可用（例如 `openAt: never` 的部署直接拒绝 `session/search`）。
     *
     * 与 web `currentRemote.status === 'error'` 同一口径：不可用不是「没有命中」，
     * 结果里仍保留本地标题/工作区命中，界面另外给一条「内容搜索暂不可用」的提示。
     */
    var searchContentUnavailable by mutableStateOf(false)
        private set
    /** 搜索代次：每次输入自增，只有最新一次的结果能落库。 */
    private var searchGeneration = 0
    /// 列表下拉刷新是否在飞行中。
    var refreshing by mutableStateOf(false)
        private set
    /**
     * 正在等 `workspace/unarchiveSession` 答复的会话。
     *
     * 归档页据此把该行的按钮换成进度指示，也顺带挡住重复点击；失败时在 `finally`
     * 里收掉，行保留下来可以重试（对齐 Mac `ArchivedSessionsView` 的 `restoring`）。
     */
    var unarchivingIds by mutableStateOf<Set<SessionId>>(emptySet())
        private set
    /**
     * 主机以 `workspace/session-active` 拒绝归档的那条会话：它还有任务在跑。
     * 根视图据此弹确认框，确认后带 `stopActivity` 重试（对齐 Mac `archiveNeedsStop`）。
     */
    var archiveNeedsStop by mutableStateOf<SessionId?>(null)

    private var api: ApiClient? = null
    private var coordinator: StreamCoordinator? = null
    /// 已经建立连接的配置；用来让重复的 connectCurrent 变成空操作。
    private var connectedProfileId: String? = null
    /** 会话清单拉取的代次；旧的一次回来晚了也不会盖掉新的一次。 */
    private var sessionListRefreshGeneration = 0
    private val imageCache = mutableMapOf<String, Bitmap>()
    private val drafts = mutableMapOf<SessionId, String>()

    var notifier: ((AttentionSignal) -> Unit)? = null
    /** 会话被打开时的旁路观察者：通知中心用它撤掉这条会话的卡片。 */
    var onSessionOpened: ((SessionId) -> Unit)? = null

    /**
     * 本机是否有"正在跑的任务"进出。"进"的红沿就来自一次本地提交（后台被回收时
     * 这条红沿也会跟着丢），"出"由 [listStore.onCompleted] 收到补发的完成边沿时置回。
     * 常驻的前台服务按它起停，见 [ai.deepseek.dshphone.AgentExecutionService]。
     */
    private var activeTaskCount = 0
    var onRunActivity: ((Boolean) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(activeTaskCount > 0)
        }

    /** 重新扫一遍"现在有没有任务在跑"，并把结论交给宿主（前台服务的起停靠它）。 */
    private fun noteRunActivity() {
        val active = listStore.sessions.any { it.running } || sessions.values.any { it.running }
        activeTaskCount = if (active) 1 else 0
        onRunActivity?.invoke(active)
    }

    val currentStore: SessionStore? get() = selectedSessionId?.let { sessions[it] }

    /**
     * 用户已经看过这条会话的失败：把它的红点收掉。
     *
     * 红点（[SessionStore.unreadError]）只有在会话于**屏幕之外**出错时才该亮着，
     * 所以"看过"由两条边沿合成：会话页进场（[visibleSessionId]）和选择打开
     *（[open]）。两条都走这里，免得红点在两种进入路径上表现不一致。
     */
    fun markErrorSeen(sessionId: SessionId) {
        sessions[sessionId]?.markErrorSeen()
    }

    /**
     * 这条会话此刻是不是就在用户眼前——通知的"已经看得见，不必再喊一遍"判据
     * （对齐 Mac `decideAgentNotification` 里的 `appIsActive && visibleSessionId == id`）。
     */
    fun isOnScreen(sessionId: SessionId): Boolean = appIsActive && visibleSessionId == sessionId

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
        scope.launch {
            teardown()
            connection = Connection.Connecting
            profiles.setCurrent(profile.id)
            val client = ApiClient(profile.target())
            api = client
            // 先把这台主机上次的清单铺上：网络还没起来，列表已经有内容可画。
            // 权威答案在随后的 refreshInternal，它整体替换这份底稿。
            val sessionCache = SessionCache(appContext, profile.id)
            cache = sessionCache
            val cachedRoster = withContext(Dispatchers.IO) { sessionCache.loadRoster() }
            listStore.seedFromCache(cachedRoster)
            val streamCoordinator = StreamCoordinator(
                api = client,
                scope = scope,
                cache = sessionCache,
                listStore = listStore,
                workspaceStore = workspaceStore,
                sessionLookup = { sessions[it] },
                sessionUpsert = { store(it) },
                allSessions = { sessions.values.toList() },
                remoteEventHandler = { event -> handleRemoteInvalidation(event) },
                sessionRemovedHandler = { handleSessionRemoved(it) },
                sessionsArchivedHandler = { ids -> handleSessionsArchived(ids) },
                resyncHandler = { scope.launch { resyncAfterReconnect() } },
                gapRepairLoader = { address, throughSeq ->
                    // 补洞不是翻页：只补漏掉的那一小段，窗口大小照翻页口径给即可。
                    when (address) {
                        is SessionAddress.Session ->
                            client.sessionHistory(address.sessionId, throughSeq, null, HISTORY_MESSAGES)
                        is SessionAddress.Subagent ->
                            client.subagentHistory(address.address, throughSeq, null, HISTORY_MESSAGES)
                    }
                },
                attentionHandler = { signal -> notifier?.invoke(signal) },
                runActivityHandler = { noteRunActivity() },
                // "用户已经看见这次失败"= 会话页此刻真的停在这条会话上（与通知那条
                // appIsActive && visibleSessionId 的判据不同：这里问的是"读没读过"，
                // 后台失败仍然要留红点，所以不看 appIsActive）。
                sessionIsOnScreen = { visibleSessionId == it },
            )
            streamCoordinator.onConnectionState = { socketState = it }
            coordinator = streamCoordinator
            connectedProfileId = profile.id
            streamCoordinator.start()
            refreshInternal(markConnected = profile.id)
            // catalog 与列表并行拉：它是进程级的，拉不到（0.1.5 主机）也不影响连上。
            loadPermissionCatalog()
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
            // 落盘的是合并后的清单（基线 + 往返期间重放的增量），比裸的 value.items
            // 更接近用户此刻看到的那一份。
            val snapshot = listStore.sessions
            withContext(Dispatchers.IO) { cache?.saveRoster(snapshot) }
            connection = Connection.Connected
            markConnected?.let { profiles.markConnected(it) }
            // 基线里的 running 是权威答案：进程被回收后重开、或断线重连时，
            // `$events` 里那条 status 帧早就错过了，只有这里能重新认出"还有任务在跑"，
            // 前台服务才起得起来（否则重开后的第一次后台又会被冻死）。
            noteRunActivity()
        } catch (e: Exception) {
            if (generation != sessionListRefreshGeneration) return
            listStore.discardBaselinePull(token)
            // 底层异常只写日志排查；界面上给的是"连不上"这条人话，见 ConnectionBanner。
            Log.w(TAG, "session list refresh failed: ${rawDescription(e)}")
            connection = Connection.Failed(Strings.offlineBannerTitle)
        }
    }

    fun refresh() {
        scope.launch { refreshInternal() }
    }

    /// 下拉刷新：重拉会话列表，并把当前会话的窗口对齐到最新。
    fun pullToRefresh() {
        if (refreshing) return
        scope.launch {
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
        scope.launch {
            when (event) {
                "agent-preset/selected", "settings/document-updated" -> loadAgentPresets()
                // 主机实时贡献改变就叫一声，目录要跟着重拉。
                "permission-presets/catalog-changed" -> loadPermissionCatalog()
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
        // 改动记录是主机在会话存活期间才提供的东西，会话没了就整批丢掉。
        turnChanges.closeAll(sessionId)
        // 会话在主机侧没了，缓存里的窗口跟着删，别让下一屏画出幽灵会话。
        cache?.let { sessionCache -> scope.launch(Dispatchers.IO) { sessionCache.removeWindow(sessionId) } }
        if (selectedSessionId == sessionId) {
            selectedSessionId = null
            lastErrorMessage = Strings.sessionGone
        }
    }

    /**
     * 主机把一批会话推入归档集合（`workspace/follow` 的 `archived` 增量，帧里是完整集合）。
     *
     * 与 [handleSessionRemoved] 的区别就是「归档不是删除」：
     * - 名册行**保留**。归档集合由 Workspace 注册表持有，渲染时过滤；主机 `session/list`
     *   还会把已归档的会话带回来，删掉行只会在下一次刷新后变成幽灵重现。
     * - 运行时状态收掉。改动记录是会话存活期间才提供的东西，归档后再打开也不会回填
     *   （与 Mac 在 `handleSessionsArchived` 里 `turnChanges.closeAll` 同一处动作）；
     *   缓存里的窗口跟着删，避免归档的会话在下一屏画出过期内容。
     * - 不写 `lastErrorMessage`：会话还在主机上，说「该会话已在主机上消失」是谎报。
     */
    private fun handleSessionsArchived(ids: List<SessionId>) {
        // 帧里带的是完整集合，把名册侧的镜像也一起对齐：渲染时两个 store 取并集，
        // 镜像落后于注册表的话会把「已经不归档」的会话继续按归档过滤掉。
        listStore.archivedSessionIds = ids
        for (id in ids) {
            turnChanges.closeAll(id)
            cache?.let { sessionCache -> scope.launch(Dispatchers.IO) { sessionCache.removeWindow(id) } }
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
        cache = null
        workspaceStore = WorkspaceStore()
        connection = Connection.Idle
    }

    fun onForeground() { coordinator?.probeNow() }

    // —— 会话 ——————————————————————————————————————————————————

    private fun store(id: SessionId): SessionStore =
        sessions.getOrPut(id) {
            SessionStore(id).also { store ->
                seedFromSummary(store)
                // 目录是进程级的、不随会话走，新建的 store 也得补上那份已经在手里的。
                store.setPermissionCatalog(permissionCatalog.takeIf { it.isNotEmpty() })
                coordinator?.hydrateControlState(store)
            }
        }

    /**
     * 用会话列表摘要给刚建出来的 store 打底（对齐 Mac 的 AppModel.upsertSession）。
     *
     * 后台帧会懒建 store：重启后第一条实时事件、或一条从没打开过的会话的审批，都会走到这里。
     * 不铺这一层的话，新 store 的 `running` 默认是 false，队列条上那颗「插队发送」按钮会一直禁用，
     * 点下去毫无反应——而主机其实正在跑。转写的裁切点同理：不先喂 projections.asOfSeq，
     * loadHistory 会退化成 throughSeq 0，先闪一下空态再等 snapshot 补。
     */
    private fun seedFromSummary(store: SessionStore) {
        val summary = listStore.sessionsById[store.sessionId] ?: return
        // blank 只往下压（web handleBlank）：已经跑过的会话不因为这份可能过期的摘要变回空白。
        if (!summary.blank) store.blank = false
        store.running = summary.running
        summary.cwd?.let { store.cwd = it }
        summary.agentPreset?.let { store.agentPreset = it }
        summary.projections?.asOfSeq?.let { store.seedHistoryCursor(it) }
    }

    fun open(sessionId: SessionId) {
        scope.launch {
            selectedSessionId = sessionId
            // 用户已经落到这条会话上了，托盘里那张卡片已经完成使命
            //（对齐 iOS `open()` 里的 `notifications.sessionOpened`）。
            onSessionOpened?.invoke(sessionId)
            listStore.selectedSessionId = sessionId
            listStore.clearCompleted(sessionId)
            val store = store(sessionId)
            // 打开就是看过：清掉这条会话的未读失败红点（对齐 Mac 选中会话时那句
            // markErrorSeen）。必须在 store 建出来之后——懒建的 store 还没有失败位，
            // 在它前面标记只会是一次空操作。
            store.markErrorSeen()
            // 这条会话上次的窗口先铺上再 follow：点开就能看到内容，follow 的
            // snapshot 回来整体替换。整段不阻塞，别的会话也能立刻打开。
            launch {
                // 读盘在 IO，铺底稿回主线程（Compose 状态只在主线程写）。
                val cachedWindow = withContext(Dispatchers.IO) { cache?.loadWindow(sessionId) }
                if (cachedWindow != null) store.seedFromCache(cachedWindow)
            }
            // 先喂首页裁切点再 follow：store 是既有的那些不会走 seedFromSummary，
            // 少了这一步 historyCursor 会退化成 0（与 Mac 手机端 open 同一处动作）。
            listStore.sessionsById[sessionId]?.projections?.asOfSeq?.let { store.seedHistoryCursor(it) }
            // 首屏只拉一份：基线由 session/follow 的 snapshot 帧给出（自带 records / hasMore /
            // projections），凡是 follow 能覆盖的 store 都不要再发 session/page——过去这里两条
            // 都发，同一段历史在首屏被拉了两遍，正是点开会话要等很久的大头。
            //
            // 只有 follow 覆盖不到的才补分页：已经有实时事件、却还没有基线的 store（通知唤起
            // 的那种，historyCursor 已经有权威值）。随后的 loadHistory 见没有权威游标会直接
            // 跳过，转写保持加载态等 snapshot，多发一次也是空操作。
            val following = coordinator?.follow(sessionId, maxMessages = INITIAL_HISTORY_MESSAGES) == true
            if (following) return@launch
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
                // 首屏窄、往上翻宽：见 INITIAL_HISTORY_MESSAGES 的说明。
                if (loadingOlder) HISTORY_MESSAGES else INITIAL_HISTORY_MESSAGES,
            )
            store.loadHistory(value, replacing = !loadingOlder)
            store.historyError = null
        } catch (e: CancellationException) {
            throw e
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
        // 重开 follow 就够了：snapshot 就是基线；再叠一次分页只会把同一段历史拉两遍。
        // 只有连 follow 都登记不上（coordinator 已经拆掉）时才退回分页。
        if (coordinator?.refollow(sessionId) != true) {
            scope.launch { loadHistory(store) }
        }
    }

    fun loadEarlier(store: SessionStore) {
        if (!store.hasMore || store.loadingHistory) return
        scope.launch { loadHistory(store) }
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

    /**
     * 新建会话页默认选中的工作区。
     *
     * Mac 端并不额外存一份「工作区偏好」：它把当前会话 id 存进 UserDefaults 的
     * `dsh.sessions.current`，新建会话时先由这条会话反查出所属工作区
     * （`currentWorkspaceId`），取不到才退回 `recentWorkspaceId()`。手机端列表页没有
     * 常驻的"当前会话"，所以这里把等价的事实直接落成一条偏好：上一次真正开出新会话
     * （发出了第一条消息）时用的那个工作区。
     *
     * 不能只看 [recentWorkspaceId]：在新建会话页里切换工作区就会在那边落一个空白会话，
     * 于是"只是翻了一下工作区"也会把它顶成最近更新的那个——用户看到的默认值就变成了
     * 最后一次切换的痕迹，而不是最后一次真的在哪儿开过会话。
     *
     * 存下来的 id 主机上已经没有了（换了服务器、工作区被删）就当没存过，退回
     * [recentWorkspaceId]（对齐 Mac 对失效 `dsh.sessions.current` 的处理）。
     */
    fun defaultNewSessionWorkspaceId(): WorkspaceId? {
        val remembered = prefs.getString(LAST_WORKSPACE_KEY, null)
        if (remembered != null && workspaceStore.workspaces.any { it.workspaceId == remembered }) {
            return remembered
        }
        return recentWorkspaceId()
    }

    /** 新建会话页真的开出了一条会话：记住这次用的工作区，作为下次的默认值。 */
    fun rememberNewSessionWorkspace(workspaceId: WorkspaceId?) {
        if (workspaceId == null) prefs.edit().remove(LAST_WORKSPACE_KEY).apply()
        else prefs.edit().putString(LAST_WORKSPACE_KEY, workspaceId).apply()
    }

    /**
     * 最近更新过的工作区（Mac `recentWorkspaceId` / web `recentWorkspace` 口径）。
     *
     * 取「最近更新过的工作区」：工作区下所有会话 updatedAt 的最大值，没有任何会话时退回
     * 工作区自己的 createdAt；并列时按主机工作区顺序取靠前的那个（严格大于才换人）。
     * 不能直接用 workspaces.first()——主机的工作区顺序是「最后一次切换工作区」留下的，
     * 那是切换痕迹，不是「最后一次在哪儿新建过会话」。
     */
    fun recentWorkspaceId(): WorkspaceId? {
        val sessionsById = listStore.sessionsById
        var selected: WorkspaceView? = null
        var selectedTime = Double.NEGATIVE_INFINITY
        for (workspace in workspaceStore.workspaces) {
            var latest = Double.NEGATIVE_INFINITY
            for (sessionId in workspace.sessionIds) {
                val session = sessionsById[sessionId] ?: continue
                if (session.updatedAt > latest) latest = session.updatedAt
            }
            if (latest == Double.NEGATIVE_INFINITY) latest = parseWorkspaceCreatedAt(workspace.createdAt)
            if (selected == null || latest > selectedTime) {
                selected = workspace
                selectedTime = latest
            }
        }
        return selected?.workspaceId
    }

    /**
     * 工作区 createdAt 是带小数秒的 ISO-8601 字符串，会话 updatedAt 是毫秒时间戳，
     * 这里统一换算成毫秒好比大小；解析不出来就给 -inf，免得一个坏日期反而赢过真实会话时间
     * （对齐 web `Date.parse` → NaN 比较失败、Mac `parseWorkspaceCreatedAt`）。
     */
    private fun parseWorkspaceCreatedAt(raw: String): Double =
        try {
            java.time.Instant.parse(raw).toEpochMilli().toDouble()
        } catch (_: Exception) {
            try {
                java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli().toDouble()
            } catch (_: Exception) {
                Double.NEGATIVE_INFINITY
            }
        }

    /** 新建会话：优先复用该工作区下已有的空白会话。 */
    suspend fun createSession(workspaceId: WorkspaceId?): SessionId? {
        val client = api ?: return null
        val workspace = workspaceId?.let { id -> workspaceStore.workspaces.firstOrNull { it.workspaceId == id } }
        if (workspace != null) {
            val reuse = reusableBlankSession(
                listStore.sessions,
                workspace,
                workspaceStore.archivedSessionIds.toSet(),
                agentPresets,
            )
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastErrorMessage = describe(e)
            null
        }
    }

    // —— 发送 ——————————————————————————————————————————————————

    /**
     * 草稿行首的 `/命令`：与主机 `parseCommand` 同一口径——名字后必须是行尾或空白，
     * 所以 `/usr/bin` 这类路径不会被当成命令。
     */
    private val commandLinePattern = Regex("^/([a-z][a-z0-9_-]*)(?=$|[\\t\\n\\r ])")

    suspend fun send(text: String, sessionId: SessionId, attachments: List<PhoneAttachment>): Boolean {
        val client = api ?: return false
        val store = sessions[sessionId] ?: return false
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return false
        // 行首的斜杠命令走主机的命令通道，不能当作普通提示词交给模型：
        // 主机才会跑处理器并落 command/run、command/done，`/plan …` 也由此真正进入计划模式。
        if (attachments.isEmpty() && commandLinePattern.containsMatchIn(trimmed)) {
            val execution = try {
                client.commandsExecute(sessionId, trimmed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastErrorMessage = describe(e)
                return false
            }
            if (execution != null) {
                val failure = execution.result?.takeIf { it.kind != "success" }?.text
                if (failure != null) {
                    lastErrorMessage = failure
                    store.historyError = HistoryError("internal", failure)
                } else {
                    store.historyError = null
                    store.blank = false
                }
                listStore.markEngaged(sessionId)
                return true
            }
            // 主机没认下这个名字：按普通消息继续（与 web `matchEnter` 的落点一致）。
        }
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
        } catch (e: CancellationException) {
            store.retirePendingSubmission(requestId)
            throw e
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
            noteRunActivity()
            true
        } catch (e: CancellationException) {
            store.retirePendingSubmission(requestId)
            throw e
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
        } catch (e: CancellationException) {
            store.retirePendingSubmission(requestId)
            throw e
        } catch (e: Exception) {
            store.retirePendingSubmission(requestId)
            lastErrorMessage = describe(e)
            false
        }
    }

    // 发送一旦交出去就得跑完：挂在 scope 上，输入区跟着导航离开 composition 也不会被取消。
    fun submit(text: String, sessionId: SessionId, attachments: List<PhoneAttachment>) {
        scope.launch { send(text, sessionId, attachments) }
    }

    fun submitSteer(text: String, sessionId: SessionId) {
        scope.launch { steer(text, sessionId) }
    }

    fun submitToSubagent(text: String, sessionId: SessionId) {
        scope.launch { sendToSubagent(text, sessionId) }
    }

    fun stop(sessionId: SessionId) {
        scope.launch { runCatching { api?.sessionCancel(sessionId) } }
    }

    /**
     * 队列动作：主机一旦收下就在控制流里推新的队列帧，界面随即收敛，所以这里不做乐观更新。
     *
     * 失败只在"用户还能做点什么"时才弹提示：插队发送撞上 steer-unavailable /
     * queue-item-not-found（行已经被主机取走、或已经被自己前一次点击插过队）属于竞态收敛，
     * 静默吃掉即可——与 Mac 的 QueueInteractionState.completeMutation 逐条对齐。
     */
    private suspend fun queueMutation(
        sessionId: SessionId,
        itemId: MessageId,
        action: QueueAction,
        failure: String,
    ) {
        val client = api ?: return
        try {
            // 动作一旦发出就要跑完：点了插队发送后这一行会立刻离开队列条、
            // 承载它的 composition 也随之消失。若跟着 UI 作用域一起取消，
            // 主机可能已经把消息挪进 next-step 而我们却读不到回执。
            withContext(NonCancellable) { client.sessionUpdateQueue(sessionId, itemId, action) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 主机抛的是带命名空间的 "session/steer-unavailable"，而 Mac 端拿到的是
            // 去掉命名空间的 "steer-unavailable"；两种写法都认。
            val code = (e as? ApiException)?.code?.substringAfterLast('/') ?: ""
            val converged = action is QueueAction.Steer &&
                (code == "steer-unavailable" || code == "queue-item-not-found")
            if (!converged) lastErrorMessage = failure.ifEmpty { describe(e) }
        }
    }

    suspend fun removeQueued(itemId: MessageId, sessionId: SessionId) {
        queueMutation(sessionId, itemId, QueueAction.Remove, Strings.queueRemoveFailed)
    }

    suspend fun editQueued(itemId: MessageId, text: String, sessionId: SessionId) {
        queueMutation(sessionId, itemId, QueueAction.Edit(text), Strings.queueEditFailed)
    }

    suspend fun steerQueued(itemId: MessageId, sessionId: SessionId) {
        queueMutation(sessionId, itemId, QueueAction.Steer, Strings.queueSteerFailed)
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

    /**
     * 运行一次会话搜索。
     *
     * 主机的 `session/search` 是可选的内容检索：部署把它关掉时（`openAt: never`）
     * 每一次调用都以失败告终。失败不等于「没有命中」——调用方仍用本地标题/工作区
     * 匹配兜底，并另给一条不可用提示，所以这里把「不可用」单独发布出来，而不是把
     * 失败混进空结果里。
     *
     * 边打边搜会叠很多次请求，晚到的旧结果必须丢掉：用代次比对，只有最新一次能落库
     * （对齐 web 每次查询新建 AbortController 的做法）。
     */
    fun search(query: String) {
        val trimmed = query.trim()
        val generation = ++searchGeneration
        if (api == null || trimmed.isEmpty()) {
            searchHits = null
            searchQuery = ""
            searchContentUnavailable = false
            return
        }
        scope.launch {
            val client = api
            if (generation != searchGeneration) return@launch
            val result = runCatching { client?.sessionSearch(trimmed)?.items }
            if (generation != searchGeneration) return@launch
            searchQuery = trimmed
            searchHits = result.getOrNull()
            searchContentUnavailable = result.isFailure
        }
    }

    fun rename(sessionId: SessionId, title: String) {
        scope.launch {
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

    /**
     * 归档一条会话。
     *
     * 归档不是删除：主机上这条会话还在，只是被 Workspace 注册表记进了归档集合。所以这里
     * 只把主机落定后的归档集合写进两个 store（主列表据此在渲染时把它滤掉），**不再**
     * `listStore.remove`——摘要在名册里留着，归档页才拿得到标题与工作区名（与 Mac
     * `archiveSession` 先 `handleSessionsArchived` 再 `refreshSessions` 同一口径）。
     */
    fun archive(sessionId: SessionId, stopActivity: Boolean = false) {
        scope.launch {
            try {
                val archived = api?.workspaceArchiveSession(sessionId, stopActivity) ?: return@launch
                workspaceStore.setArchived(archived)
                // 本地这一次归档掉它的运行时 store：名册行留着给归档页，但没人在看的
                // 会话流没必要继续挂着（再打开时会重新 `open()` 建回来）。
                sessions.remove(sessionId)
                // 传主机返回的完整集合：`handleSessionsArchived` 会把它同步进名册侧镜像，
                // 只传刚归档的这一条会把别的已归档会话从镜像里挤掉。
                handleSessionsArchived(archived)
            } catch (e: ApiException) {
                if (e.code == "workspace/session-active" && !stopActivity) archiveNeedsStop = sessionId
                else lastErrorMessage = describe(e)
            } catch (e: Exception) {
                lastErrorMessage = describe(e)
            }
        }
    }

    /** 置顶/取消置顶；主机返回的完整置顶集直接落进 [WorkspaceStore]，不必等 follow 帧。 */
    fun setPinned(sessionId: SessionId, pinned: Boolean) {
        scope.launch {
            try {
                val ids = api?.workspaceSetPinned(sessionId, pinned) ?: return@launch
                workspaceStore.setPinned(ids)
            } catch (e: Exception) {
                lastErrorMessage = describe(e)
            }
        }
    }

    /**
     * 恢复一条已归档会话（0.1.6 新增的 `workspace/unarchiveSession`）。
     *
     * 归档时本地把它从列表里摘掉了，恢复成功后必须重拉列表才回得来；返回的归档集合同步应用，
     * 归档页不必等下一次 `workspace/follow` 帧。老主机上这条 RPC 不存在，失败会走 toast 文案，
     * 而不是看起来像成功了却没动静。
     */
    fun unarchive(sessionId: SessionId) {
        if (sessionId in unarchivingIds) return
        unarchivingIds = unarchivingIds + sessionId
        scope.launch {
            try {
                val archived = api?.workspaceUnarchiveSession(sessionId) ?: return@launch
                workspaceStore.setArchived(archived)
                listStore.archivedSessionIds = archived
                refreshInternal()
            } catch (e: Exception) {
                lastErrorMessage = describe(e)
            } finally {
                unarchivingIds = unarchivingIds - sessionId
            }
        }
    }

    /**
     * 分组拖动换位后的排序（与 Mac `AppModel.moveWorkspace` 同构）：先本地乐观移动，
     * 手指一松就到位；再交给主机 `workspace/insertBefore` 落定，[beforeWorkspaceId]
     * 为空表示挪到末尾。
     *
     * 落定和回滚前都先比一次「本地顺序是否还等于我们乐观改出来的那个」：请求在飞的这段
     * 时间里，工作区跟随流可能推来了新的顺序，用户也可能又拖了一次，这时候再拿旧结果去
     * 覆盖就是把新状态冲掉。比较只看这次移动前就已存在的 id，期间新建的工作区不参与。
     */
    fun moveWorkspace(workspaceId: WorkspaceId, beforeWorkspaceId: WorkspaceId?) {
        val previousOrder = workspaceStore.workspaces.map { it.workspaceId }
        if (!workspaceStore.moveWorkspace(workspaceId, beforeWorkspaceId)) return
        val optimisticOrder = workspaceStore.workspaces.map { it.workspaceId }
        val previousIds = previousOrder.toSet()
        fun untouched(): Boolean =
            workspaceStore.workspaces.map { it.workspaceId }.filter { it in previousIds } == optimisticOrder
        scope.launch {
            try {
                val settled = api?.workspaceInsertBefore(workspaceId, beforeWorkspaceId) ?: return@launch
                if (untouched()) workspaceStore.applyOrder(settled.workspaceIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (untouched()) workspaceStore.applyOrder(previousOrder)
                lastErrorMessage = describe(e)
            }
        }
    }

    fun openOnHost(path: String) {
        scope.launch {
            try { api?.hostOpenPath(path) } catch (e: Exception) { lastErrorMessage = describe(e) }
        }
    }

    /** 停止一个后台任务；结果由 `job/list` 流带回来，这里不改本地状态。 */
    /**
     * 请求停止一个后台任务（`job/kill`）。主机受理后就返回（已经结束的也算）；
     * 行从列表里消失前界面保持忙碌态，由 job/list 帧把它移出 running。
     *
     * @return 主机是否受理；失败时同时把原因放进 [lastErrorMessage]。
     */
    suspend fun killJob(sessionId: SessionId, jobId: String): Boolean {
        val client = api ?: return false
        return try {
            client.jobKill(sessionId, jobId)
            true
        } catch (e: Exception) {
            lastErrorMessage = describe(e)
            false
        }
    }

    /** 当前在观察输出的后台任务（`job/follow`），同一时刻只有一个。 */
    val observedJob: ObservedJobOutput? get() = coordinator?.observedJob

    fun observeJob(sessionId: SessionId, jobId: String) { coordinator?.observeJob(sessionId, jobId) }

    fun stopObservingJob(jobId: String) { coordinator?.stopObservingJob(jobId) }

    /**
     * 每个会话里用户「清空」掉的已结束任务 id（web `clearedKeys`）：只是本机隐藏，
     * 主机那边的记录不动。
     */
    private val clearedJobIds = mutableStateMapOf<SessionId, Set<String>>()

    fun clearedJobs(sessionId: SessionId): Set<String> = clearedJobIds[sessionId] ?: emptySet()

    fun clearSettledJobs(sessionId: SessionId, jobIds: Collection<String>) {
        clearedJobIds[sessionId] = clearedJobs(sessionId) + jobIds
    }

    // —— 子智能体 ——————————————————————————————————————————————

    suspend fun loadSubagents(parentSessionId: SessionId): List<SubagentCatalogEntry> =
        runCatching { api?.subagentCatalog(parentSessionId) }.getOrNull() ?: emptyList()

    /** 子会话是否在跑：目录不带运行状态，取子会话自己的会话摘要。 */
    fun subagentRunning(childSessionId: SessionId): Boolean =
        sessions[childSessionId]?.running ?: listStore.sessionsById[childSessionId]?.running ?: false

    suspend fun openSubagent(parentSessionId: SessionId, entry: SubagentCatalogEntry): SessionId? {
        val childSessionId = entry.id
        val address = SubagentAddress(parentSessionId, childSessionId, entry.mode)
        val store = store(childSessionId)
        // 父会话能否接消息取它自己的摘要（原 subagents/list 的 parentAvailable 提示）。
        val parentAvailable = listStore.sessionsById[parentSessionId]?.agentAvailable ?: true
        store.configureSubagent(address, parentAvailable)
        entry.label?.let { store.title = it }
        store.running = subagentRunning(childSessionId)
        selectedSessionId = childSessionId
        // 与 open() 同一条规矩：首屏只拉一份，follow 建不起来时才补分页。
        val following = coordinator?.follow(
            childSessionId,
            SessionAddress.Subagent(address),
            INITIAL_HISTORY_MESSAGES,
        ) == true
        if (!following && !store.historyLoaded) loadHistory(store)
        return childSessionId
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastErrorMessage = describe(e)
            false
        }
    }

    fun interruptSubagent(sessionId: SessionId) {
        scope.launch {
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
                    PhoneSuggestion(
                        "cmd:${it.name}", "/${it.name}", it.description, "command",
                        "/${it.name} ", it.input?.hint, wantsArgs = it.input != null,
                    )
                } + skills.filter { needle.isEmpty() || it.name.lowercase().contains(needle) }.take(5).map {
                    PhoneSuggestion("skill:${it.name}", "$${it.name}", it.description, "sparkle", "$${it.name} ")
                }
            }
        }
    }

    // —— 目标 ——————————————————————————————————————————————————

    fun createGoal(objective: String, maxGoalRounds: Int?, sessionId: SessionId) {
        scope.launch {
            try { api?.goalCreate(sessionId, objective, maxGoalRounds) }
            catch (e: Exception) { lastErrorMessage = describe(e) }
        }
    }

    fun editGoal(objective: String, sessionId: SessionId) {
        scope.launch {
            val ref = sessions[sessionId]?.goalRef ?: return@launch
            try { api?.goalEdit(sessionId, ref, objective) }
            catch (e: Exception) { lastErrorMessage = describe(e) }
        }
    }

    fun toggleGoalPause(sessionId: SessionId) {
        scope.launch {
            val store = sessions[sessionId] ?: return@launch
            // 用 activeGoal：条上画的和这里认的共用同一条折叠（blocked 也在这条上）。
            val goal = store.activeGoal ?: return@launch
            val ref = store.goalRef ?: return@launch
            try {
                // 与 GoalBar 同一张映射表：blocked/complete 上的暂停/恢复主机不接受，直接不发，
                // 免得弹一句用户没法处理的 GOAL_INVALID_TRANSITION。
                when (goalPhaseAction(goal.phase)) {
                    GoalPhaseAction.PAUSE -> api?.goalPause(sessionId, ref)
                    GoalPhaseAction.RESUME -> api?.goalResume(sessionId, ref)
                    null -> Unit
                }
            } catch (e: Exception) { lastErrorMessage = describe(e) }
        }
    }

    fun clearGoal(sessionId: SessionId) {
        scope.launch {
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
        scope.launch {
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
        scope.launch {
            try { api?.sessionSelectPermission(sessionId, presetId) }
            catch (e: Exception) { lastErrorMessage = describe(e) }
        }
    }

    suspend fun loadAgentPresets() {
        val roster = runCatching { api?.agentPresetList()?.toRoster() }.getOrNull() ?: return
        agentPresets = roster.presets
        agentPresetPickerEnabled = roster.pickerEnabled
    }

    /**
     * 拉进程级权限目录（0.1.6 的 `permissionPresets/catalog`）并广播给每个已打开的会话。
     *
     * 目录与单个会话的历史无关，拉一次即可；0.1.5 主机没有这条 RPC，失败时保持空，
     * 各 store 会退回 `permissions` 投影内联的那份，不丢功能。
     */
    suspend fun loadPermissionCatalog() {
        val catalog = runCatching { api?.permissionCatalog() }.getOrNull() ?: return
        permissionCatalog = catalog
        sessions.values.forEach { it.setPermissionCatalog(catalog) }
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
        scope.launch {
            try { api?.commandsExecute(sessionId, line) }
            catch (e: Exception) { lastErrorMessage = describe(e) }
        }
    }

    // —— 审批与提问 ————————————————————————————————————————————

    fun respondApproval(approval: ApprovalRequested, outcome: ApprovalOutcome) {
        scope.launch {
            settle(approval.eventId, RemoteEventOutcome.Result(
                kotlinx.serialization.json.JsonPrimitive(outcome.wire)
            ))
            sessions[approval.sessionId]?.setApproval(null)
            listStore.clearAwaitingUser(approval.sessionId)
        }
    }

    fun respondQuestion(question: QuestionRequested, answers: List<QuestionAnswer>) {
        scope.launch {
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
        scope.launch {
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

    /**
     * 把一个工作区文件读进 [target]（图片交给系统查看器之前先落地）。
     *
     * 按窗口分片读、拿到一片就立刻写盘，所以峰值内存只有一片而不是整份文件：
     * 主机 readBytes 单窗口默认 2 MiB，readAll 虽然能一次给到 32 MiB，但 base64
     * 之后再解成字节会同时占掉两份，低端机容易直接 OOM。返回写着文件内容的临时文件，
     * 调用方负责用完删除；任何一片失败都抛出，由调用方决定怎么提示。
     */
    suspend fun workspaceFileBytes(
        sessionId: SessionId,
        path: String,
        target: File,
        limit: Long,
    ): Long {
        val client = api ?: throw internalError(Strings.disconnected)
        return withContext(Dispatchers.IO) {
            // outputStream 会截断，所以整个循环共用一个，不能每片开一次。
            target.outputStream().buffered().use { out ->
                copyWorkspaceFileChunked(out, limit) { offset, length ->
                    client.workspaceFileReadBytes(sessionId, path, offset, length)
                }
            }
        }
    }

    /** 正文里本地路径图片的解码结果，按「会话|路径」缓存；只留最近的几张。 */
    private val workspaceImageCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > WORKSPACE_IMAGE_CACHE_ENTRIES
    }
    /** 读过但失败的「会话|路径」：同一屏重组时不必反复向主机要一张打不开的图。 */
    private val workspaceImageFailures = mutableSetOf<String>()

    /**
     * 助手正文里 `![](/abs/shot.png)` 这类本地路径图片的位图（对齐 Web `localPathMediaUrl`
     * 把本地路径改走主机取图）。
     *
     * Web/Mac 走的是主机的 `GET /api/file?path=…`；手机经隧道只放行 `/api/<ns>/<method>`
     * 的 POST，所以这里改用同一条会话作用域的 `workspaceFiles/readBytes` 分片读到缓存文件，
     * 再按显示宽度降采样解码，峰值内存只有一片加一张缩过的图。超过
     * [WORKSPACE_IMAGE_MAX_BYTES] 的不内联显示（点开仍交给系统查看器）。
     *
     * @return 解码好的位图；读不到、太大或不是能解的图片时为 null。
     */
    suspend fun workspaceImage(sessionId: SessionId, path: String): Bitmap? {
        val key = "$sessionId|$path"
        synchronized(workspaceImageCache) { workspaceImageCache[key] }?.let { return it }
        if (synchronized(workspaceImageFailures) { key in workspaceImageFailures }) return null
        if (api == null) return null
        val staged = File(appContext.cacheDir, "inline-images").apply { mkdirs() }
            .let { File.createTempFile("img", ".bin", it) }
        val bitmap = try {
            workspaceFileBytes(sessionId, path, staged, WORKSPACE_IMAGE_MAX_BYTES)
            withContext(Dispatchers.IO) { decodeSampled(staged, WORKSPACE_IMAGE_MAX_EDGE) }
        } catch (e: CancellationException) {
            staged.delete()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "inline image $path unavailable: ${e.message}")
            null
        } finally {
            staged.delete()
        }
        if (bitmap == null) {
            synchronized(workspaceImageFailures) { workspaceImageFailures.add(key) }
        } else {
            synchronized(workspaceImageCache) { workspaceImageCache[key] = bitmap }
        }
        return bitmap
    }

    /** 先只读尺寸，再按 2 的幂降采样到长边不超过 [maxEdge]。 */
    private fun decodeSampled(file: File, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
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
        private const val TAG = "PhoneModel"

        /** 正文内联图片的字节上限：再大就只给链接，点开交给系统查看器。 */
        private const val WORKSPACE_IMAGE_MAX_BYTES = 16L * 1024 * 1024
        /** 内联图片解码后的长边上限（像素），手机列宽用不着更大。 */
        private const val WORKSPACE_IMAGE_MAX_EDGE = 2048
        private const val WORKSPACE_IMAGE_CACHE_ENTRIES = 24

        /** 上一次真正开出新会话时用的工作区（新建会话页的默认选中项）。 */
        private const val LAST_WORKSPACE_KEY = "phone.workspace.lastNewSession"

        /**
         * 打开一条会话时首屏要几轮消息。
         *
         * 主机按"消息数"分页：每多一轮就多带几条事件（一轮消息 ~8 条）。50 是按
         * web 的旧默认值抄来的，对手机屏太大——首屏多出几百条事件，纯是噪音，
         * 用户往上翻才有意义。15 轮够铺满一屏并留出滚动余量，往上翻时再按
         * [HISTORY_MESSAGES] 分页补。
         */
        private const val INITIAL_HISTORY_MESSAGES = 15

        /**
         * 之后每一页要几轮消息。
         *
         * 比首屏大：用户已经明确在往上翻，一次多给一点少一次往返。
         */
        private const val HISTORY_MESSAGES = 30

        /**
         * 面向用户的失败文案：把底层异常收敛成一句人话。
         *
         * 传输/解析失败在 [ApiClient] 里被包成 code = "internal" 的 [ApiException]，
         * 它的 detail 就是 `java.io.EOFException` 这类原样异常文本——直接端给用户
         * 看不懂也没有可操作性，这里一律换成兜底文案。主机主动回的业务错误
         * （code 非 internal）本身通常是可读的，保留。
         */
        fun describe(error: Throwable): String {
            Log.w(TAG, "request failed: ${rawDescription(error)}")
            val hostMessage = (error as? ApiException)
                ?.takeIf { it.code != "internal" }
                ?.detail
                ?.takeIf { it.isNotBlank() }
            return hostMessage ?: Strings.operationFailed
        }

        /** 原始异常文本：只用于日志排查，绝不直接展示给用户。 */
        fun rawDescription(error: Throwable): String =
            (error as? ApiException)?.let { "${it.code}: ${it.detail}" }
                ?: error.message ?: error.toString()

        /**
         * 进程内唯一实例。由 [DshApplication.onCreate] 在首帧之前建好，
         * 之后 Activity 重建拿到的都是它。
         */
        @Volatile
        private var instance: PhoneModel? = null

        fun get(app: Application): PhoneModel =
            instance ?: synchronized(this) {
                instance ?: PhoneModel(app).also { instance = it }
            }
    }
}

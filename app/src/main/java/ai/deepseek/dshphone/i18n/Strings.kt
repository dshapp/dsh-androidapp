package ai.deepseek.dshphone.i18n

import androidx.compose.runtime.mutableStateOf
import java.util.Locale

/**
 * 应用实际支持的界面语言（与 iOS 的 AppLocale 同口径）。
 *
 * 偏好里"跟随系统"是缺省而不是这一种的成员：跟着系统时具体落到哪一种由
 * [Strings.resolve] 按系统语言算，设备语言不是中/英时中文优先回退。
 */
enum class AppLocale { EN, ZH }

object Strings {
    /**
     * 生效中的界面语言。写成 Compose 状态而不是普通字段：所有文案都从这个单例上读，
     * 切换语言时读过的组合会自动重组，不必靠重建整棵界面去刷新。
     */
    private val localeState = mutableStateOf(resolve())

    /** 当前语言；设置页选"跟随系统"时由系统语言解析而来。 */
    var locale: AppLocale
        get() = localeState.value
        set(value) { localeState.value = value }

    /** 系统语言决定的那一种（中文优先回退，与 iOS 的 AppLocale 同口径）。 */
    fun resolve(): AppLocale {
        val tag = Locale.getDefault().language.lowercase()
        return if (tag == "en") AppLocale.EN else AppLocale.ZH
    }

    private fun pick(en: String, zh: String): String = if (locale == AppLocale.EN) en else zh

    val appName: String get() = pick("DeepSeek Harness", "DeepSeek Harness")
    val onboardingTitle: String get() = pick("Pair with your Desktop", "与电脑配对")
    val onboardingBody: String get() = pick("On your Desktop open DeepSeek Harness → Settings → Server → Mobile Access, then scan the QR code shown there.", "在电脑上打开 DeepSeek Harness → 设置 → 服务 → 移动端访问，扫描那里显示的二维码。")
    val scanQR: String get() = pick("Scan QR Code", "扫描二维码")
    val pastePairing: String get() = pick("Paste pairing text", "手动输入配对串")
    val pairTitle: String get() = pick("Pair", "配对")
    val pairInvalid: String get() = pick("That is not a DeepSeek Harness pairing code.", "这不是 DeepSeek Harness 的配对码。")
    val scanHint: String get() = pick("Scan the DSH pairing QR code.", "请扫描DSH匹配二维码")
    val scanUnavailable: String get() = pick("This device has no usable camera, so paste the pairing text instead.", "此设备没有可用相机，请改为粘贴配对串。")
    val connect: String get() = pick("Connect", "连接")
    val settings: String get() = pick("Settings", "设置")
    val servers: String get() = pick("Servers", "服务")
    // 设置页「外观」区：语言与主题都是本机偏好，跟连接哪台 Mac 无关。
    val appearance: String get() = pick("Appearance", "外观")
    val language: String get() = pick("Language", "语言")
    val languageSystem: String get() = pick("System default", "跟随系统")
    val languageEnglish: String get() = pick("English", "英语")
    val languageChinese: String get() = pick("简体中文", "简体中文")
    val theme: String get() = pick("Theme", "主题")
    val themeSystem: String get() = pick("System default", "跟随系统")
    val themeLight: String get() = pick("Light", "浅色")
    val themeDark: String get() = pick("Dark", "深色")
    val currentServer: String get() = pick("Current Server", "当前服务")
    val relay: String get() = pick("Relay", "中转")
    val deviceId: String get() = pick("This device", "本机设备")
    val status: String get() = pick("Status", "状态")
    val statusConnected: String get() = pick("Connected", "已连接")
    val statusConnecting: String get() = pick("Connecting…", "连接中…")
    val statusIdle: String get() = pick("Not connected", "未连接")
    val lastConnected: String get() = pick("Last connected", "最近连接")
    val rescan: String get() = pick("Scan Again", "重新扫描")
    val serverName: String get() = pick("Server name", "电脑名称")
    val serverRename: String get() = pick("Rename Server", "重命名服务")
    val serverNameFooter: String get() = pick("Name this Desktop however you like; it is only shown on this device.", "给这台电脑标个名字，只在本机显示。")
    val addServer: String get() = pick("Add Server", "添加新服务")
    val noSessions: String get() = pick("No sessions yet", "还没有会话")
    /** 没有标题的会话（web `session.untitled`）。 */
    val sessionUntitled: String get() = pick("Untitled", "未命名")
    val composerPlaceholder: String get() = pick("Message…", "输入消息…")
    val approvalTitle: String get() = pick("Approval", "审批")
    val searchSessions: String get() = pick("Search sessions", "搜索会话")
    val newSession: String get() = pick("New Session", "新建会话")
    val workspace: String get() = pick("Workspace", "工作区")
    val noWorkspace: String get() = pick("No workspace", "不指定工作区")
    val toolInput: String get() = pick("Input", "输入")
    val toolOutput: String get() = pick("Output", "输出")
    /** 详情弹层里 run_code 派发的子调用列表标题（Mac `subcalls`）。 */
    fun subcalls(count: Int): String = pick("Subcalls ($count)", "子调用($count)")
    val thinking: String get() = pick("Thinking", "思考过程")
    // 运行状态条的工作类别（Mac `liveActivity` / web `message.stepProcess.*`）。
    val activityThinking: String get() = pick("Analyzing the request", "正在分析请求")
    val activityRead: String get() = pick("Reading files", "正在读取文件")
    val activityReadImage: String get() = pick("Reading images", "正在读取图片")
    val activityWrite: String get() = pick("Writing files", "正在写入文件")
    val activitySearch: String get() = pick("Searching code", "正在搜索代码")
    val activityEdit: String get() = pick("Editing files", "正在编辑文件")
    val activityCommands: String get() = pick("Running commands", "正在运行命令")
    val activityCode: String get() = pick("Running code", "正在运行代码")
    val activityWebSearch: String get() = pick("Searching the web", "正在搜索网页")
    val activityWebFetch: String get() = pick("Visiting web pages", "正在访问网页")
    val activitySubagents: String get() = pick("Coordinating subagents", "正在协调子智能体")
    val activityPlan: String get() = pick("Updating the plan", "正在更新计划")
    val activityQuestions: String get() = pick("Waiting for your action", "等待你的操作")
    val activityTools: String get() = pick("Calling tools", "正在调用工具")
    val compacted: String get() = pick("Context compacted", "上下文已压缩")
    val modelRetry: String get() = pick("Model retried", "模型重试")
    val maxTokens: String get() = pick("Response hit the token limit", "回复达到长度上限")
    val model: String get() = pick("Model", "模型")
    val modelSearchPlaceholder: String get() = pick("Search models…", "搜索模型…")
    val modelSearchClear: String get() = pick("Clear search", "清除搜索")
    val modelSearchEmpty: String get() = pick("No matching models.", "没有匹配的模型。")
    val thinkingEffort: String get() = pick("Thinking effort", "思考强度")
    val preset: String get() = pick("Preset", "预设")
    val planMode: String get() = pick("Plan", "计划")
    val compact: String get() = pick("Compact context", "压缩上下文")
    val questionTitle: String get() = pick("Question", "提问")
    val questionSkip: String get() = pick("Skip", "跳过")
    val submit: String get() = pick("Submit", "提交")
    val answerCustom: String get() = pick("Your answer", "自定义回答")
    val pickPhoto: String get() = pick("Photo", "照片")
    val pickFile: String get() = pick("File", "文件")
    val searchResults: String get() = pick("Results", "搜索结果")
    val rename: String get() = pick("Rename", "重命名")
    val archive: String get() = pick("Archive", "归档")
    val archiveActiveTitle: String get() = pick("Stop running work and archive?", "停止运行中的任务并归档？")
    val archiveActiveMessage: String get() =
        pick("This session still has running work. Archiving will stop it first.", "该会话仍有任务在运行，归档会先停止它们。")
    val stopAndArchive: String get() = pick("Stop and Archive", "停止并归档")
    val fork: String get() = pick("Fork session", "Fork 会话")
    /** 左滑动作条上的短标签，按钮只有几十 dp 宽，放不下完整措辞。 */
    val forkShort: String get() = pick("Fork", "分叉")
    val export: String get() = pick("Export log", "导出日志")
    val panel: String get() = pick("Session", "会话面板")
    val todos: String get() = pick("Todos", "待办")
    val jobs: String get() = pick("Background jobs", "后台任务")
    val jobsSectionLive: String get() = pick("Running", "进行中")
    val jobsSectionSettled: String get() = pick("Finished %d", "已结束 %d")
    val jobsSectionClear: String get() = pick("Clear", "清空")
    val jobsKillStop: String get() = pick("Stop task %s", "停止任务 %s")
    val jobsKillFailed: String get() = pick("Stop failed", "停止失败")
    val jobsStopAction: String get() = pick("Stop Task", "停止任务")
    val jobsStopDialogTitle: String get() = pick("Stop “%s”?", "停止“%s”？")
    val jobsStopDialogMessage: String get() =
        pick("The task is cancelled, and the agent is told you stopped it.", "任务会被取消，Agent 会收到你停止了它的通知。")
    val jobsDetailId: String get() = pick("ID", "ID")
    val jobsDetailKind: String get() = pick("Kind", "类型")
    val jobsDetailStarted: String get() = pick("Started", "开始时间")
    val jobsDetailFinished: String get() = pick("Finished", "结束时间")
    val jobsDetailDuration: String get() = pick("Duration", "耗时")
    val jobsDetailExitCode: String get() = pick("Exit code", "退出码")
    val jobsDetailSignal: String get() = pick("Signal", "信号")
    val jobsDetailDetail: String get() = pick("Detail", "详情")
    val jobsDetailUnavailable: String get() = pick("No further detail is available", "暂无更多详情")
    val jobsOutputTitle: String get() = pick("Output", "输出")
    val jobsOutputGap: String get() = pick("… earlier output dropped …", "……较早的输出已丢弃……")
    val jobsOutputError: String get() = pick("Live output stream interrupted: %s", "实时输出流中断：%s")
    val jobsOutputNone: String get() = pick("(no output)", "（无输出）")
    val jobStatusRunning: String get() = pick("Running", "运行中")
    val jobStatusStopping: String get() = pick("Stopping", "停止中")
    val jobStatusCompleted: String get() = pick("Completed", "已完成")
    val jobStatusKilled: String get() = pick("Stopped", "已停止")
    val jobStatusFailed: String get() = pick("Failed", "失败")
    val stats: String get() = pick("Stats", "统计")
    val turns: String get() = pick("Turns", "轮次")
    val steps: String get() = pick("Steps", "步骤")
    val context: String get() = pick("Context", "上下文")
    val goal: String get() = pick("Goal", "目标")
    val goalNew: String get() = pick("New goal", "新建目标")
    val goalObjective: String get() = pick("Objective", "目标描述")
    val goalPause: String get() = pick("Pause goal", "暂停目标")
    val goalResume: String get() = pick("Resume goal", "恢复目标")
    val goalClear: String get() = pick("Clear goal", "清除目标")
    val goalEdit: String get() = pick("Edit goal", "编辑目标")
    val findInChat: String get() = pick("Find in chat", "会话内查找")
    /** 查找命中计数，如「2/7」（按命中行算）。标题栏留给输入框的宽度有限，用紧凑写法。 */
    val findCount: String get() = pick("%d/%d", "%d/%d")
    val findPrevious: String get() = pick("Previous match", "上一个匹配")
    val findNext: String get() = pick("Next match", "下一个匹配")
    val findNoResults: String get() = pick("No matches in this chat", "会话内未找到匹配")
    val copy: String get() = pick("Copy", "复制")
    val subagents: String get() = pick("Subagents", "子智能体")
    val deliverables: String get() = pick("Deliverables", "产物")
    val openOnDesktop: String get() = pick("Open on desktop", "在电脑上打开")
    val pin: String get() = pick("Pin", "置顶")
    val unpin: String get() = pick("Unpin", "取消置顶")
    val loadEarlier: String get() = pick("Load earlier", "加载更早")
    // 轮次折叠头：文案就是这一轮的结局（Mac `TurnProcessSpec.label`）。
    val turnProcessCompleted: String get() = pick("Completed", "已完成")
    val turnProcessTook: String get() = pick("Completed in %s", "已完成，用时 %s")
    val turnProcessStopped: String get() = pick("Stopped", "已停止")
    val turnProcessFailed: String get() = pick("Failed", "处理失败")
    // 工具增减提示（web `message.tool*`，文案以 Mac L10n 为准）。
    val toolAdded: String get() = pick("Tool added: %s", "已添加工具：%s")
    val toolRemoved: String get() = pick("Tool removed: %s", "已移除工具：%s")
    val toolsUpdated: String get() = pick("Tools updated", "工具已更新")
    val toolsAddedCount: String get() = pick("%d added", "新增 %d 个")
    val toolsRemovedCount: String get() = pick("%d removed", "移除 %d 个")
    val toolsChangedCount: String get() = pick("%d added, %d removed", "新增 %d 个，移除 %d 个")
    val toolsAddedList: String get() = pick("Added: %s", "新增：%s")
    val toolsRemovedList: String get() = pick("Removed: %s", "移除：%s")
    // 轮次触发通知（web ui-chat `message.trigger.*`）。
    val turnTriggerRequest: String get() = pick("Execution requested", "收到执行请求")
    val turnTriggerGoal: String get() = pick("Continuing goal", "继续执行目标")
    val turnTriggerAgent: String get() = pick("Task message received", "收到任务消息")
    val turnTriggerTeam: String get() = pick("Team message received", "收到团队消息")
    val turnTriggerSubagent: String get() = pick("Subtask status updated", "子任务状态更新")
    val turnTriggerGitHub: String get() = pick("GitHub event received", "收到 GitHub 事件")
    val turnTriggerWebhook: String get() = pick("External event received", "收到外部事件")
    val turnTriggerSchedule: String get() = pick("Automation task", "自动化任务")
    val turnTriggerJob: String get() = pick("Background task updated", "后台任务状态更新")
    val turnTriggerPlugin: String get() = pick("Plugin status updated", "插件状态更新")
    val turnTriggerExplanation: String get() = pick("This notification triggered this response.", "这条通知触发了本轮回复。")
    val tooManyImages: String get() = pick("At most %d images per message.", "每条消息最多 %d 张图片。")
    val imageTooLarge: String get() = pick("An image exceeds the %s limit.", "有图片超过 %s 上限。")
    val imagesTooLarge: String get() = pick("Images exceed the %s total limit.", "图片总量超过 %s 上限。")
    val imageTypeUnsupported: String get() = pick("%s is not an accepted image type.", "不支持的图片类型：%s。")
    // 图片块在日志里存在、字节却读不到（未被会话引用或已被清理）：明说，别一直转圈。
    val imageUnavailable: String get() = pick("Image unavailable", "图片不可用")
    // 圈选元素 XML 的卡片（与 Mac xmlCardsViewAll / xmlCardText、插件 xmlCards.viewAll 同文案）。
    val xmlCardsViewAll: String get() = pick("View all %d elements", "查看全部 %d 个元素")
    val xmlCardText: String get() = pick("text", "文本")
    val accessRead: String get() = pick("Read", "只读")
    val accessWrite: String get() = pick("Write", "写入")
    val accessFull: String get() = pick("Full", "完全")
    val fullAccessTitle: String get() = pick("Switch to full access?", "切换到完全访问？")
    val fullAccessMessage: String get() = pick("The agent will run tools without asking, including writes outside the workspace.", "智能体将不再逐次询问即可执行工具，包括工作区之外的写入。")
    val fullAccessConfirm: String get() = pick("Switch", "切换")
    val queueEdit: String get() = pick("Edit queued message", "编辑排队消息")
    val queueSteer: String get() = pick("Send now", "插队发送")
    val queueRemove: String get() = pick("Remove", "撤回")
    // 失败提示只在"用户还能做点什么"时出现：插队发送的竞态（行已被取走）静默收敛，见 PhoneModel.queueMutation。
    val queueEditFailed: String get() = pick("Could not edit the queued message. Try again.", "编辑排队消息失败，请重试。")
    val queueRemoveFailed: String get() = pick("Could not remove the queued message. Try again.", "移除排队消息失败，请重试。")
    val queueSteerFailed: String get() = pick("Steering failed. Try again.", "插话发送失败，请重试。")
    val forkHere: String get() = pick("Fork from here", "从此处分支")
    val subagentComposer: String get() = pick("Messages go to this subagent", "消息将发给这个子智能体")
    val subagentReadOnly: String get() = pick("One-shot subagent — read only", "一次性子智能体，仅可查看")
    val pinned: String get() = pick("Pinned", "置顶")
    val groupBy: String get() = pick("Group by", "分组")
    val groupByWorkspace: String get() = pick("Workspace", "按工作区")
    val groupByFlat: String get() = pick("Flat", "不分组")
    val orderBy: String get() = pick("Order", "排序")
    val orderUpdated: String get() = pick("Recent", "按最近")
    val orderStatus: String get() = pick("Status", "按状态")
    val orderManual: String get() = pick("Manual", "手动")
    val archived: String get() = pick("Archived", "已归档")
    val noArchived: String get() = pick("No archived sessions", "没有已归档会话")
    val restore: String get() = pick("Restore", "恢复")
    /** 会话列表视图菜单入口与归档页标题（对齐 Mac 的 archivedSessions）。 */
    val archivedSessions: String get() = pick("Archived sessions", "已归档会话")
    /** 归档页搜索框的占位（对齐 Mac 的 searchArchivedSessions）。 */
    val searchArchivedSessions: String get() = pick("Search archived sessions", "搜索已归档会话")
    /** 归档集合非空、但没有任何一条能解析出会话摘要时的空态（对齐 Mac 的 unavailableArchivedSessions）。 */
    val unavailableArchivedSessions: String get() =
        pick("No archived session here can be restored.", "这里没有可恢复的已归档会话。")
    /**
     * 每行的取消归档动作。点名「归档状态」而不是通用的「恢复」，与底部入口同一个词根；
     * 对齐 Mac 把侧栏入口与行内动作统一为 unarchive 的做法。
     */
    val unarchiveSession: String get() = pick("Unarchive", "取消归档")
    /** 同上动作的无障碍标签，%s 是被操作那一行的标题（对齐 Mac 的 unarchiveSessionNamed）。 */
    val unarchiveSessionNamed: String get() = pick("Unarchive %s", "取消归档 %s")
    // 改动文件卡片（0.1.6 ui-deliverables）：内容与主机 workspace-changes 插件一一对应。
    // 卡片行用上游 `changes.*`、diff 正文用 `diff.*`，两套文案上游是分开的。
    val changedFilesTitle: String get() = pick("Edited %d files", "已编辑 %d 个文件")
    val changedFilesShowAll: String get() = pick("All %d files", "全部 %d 个文件")
    val changedFilesCollapse: String get() = pick("Collapse", "收起")
    val changedFilesBinary: String get() = pick("binary", "二进制")
    val changedFilesOversized: String get() = pick("too large", "过大")
    val changedDiffBinary: String get() = pick("Binary file; changes cannot be shown", "二进制文件，无法显示改动")
    val changedDiffOversized: String get() = pick("File too large; changes cannot be shown", "文件过大，无法显示改动")
    val changedDiffUnchanged: String get() = pick("Both sides hold the same lines", "两侧内容相同")
    val changedDiffCoarse: String get() =
        pick("Line comparison timed out; shown as a whole-file replacement", "逐行对比超时，按整个文件替换显示")
    // present 交付卡片（上游 `presented.*`）。折叠上限与改动卡片同为 4。
    val presentedAll: String get() = pick("All %d files", "全部 %d 个文件")
    val presentedCollapse: String get() = pick("Collapse", "收起")
    val presentedFileKind: String get() = pick("File", "文件")
    // 轮尾计划卡片（上游 ui-plan `preview.*`）。
    val planCardKind: String get() = pick("Plan · Markdown", "计划 · Markdown")
    val planCardAction: String get() = pick("Open", "打开")
    val planCardOpenNamed: String get() = pick("Open plan: %s", "打开计划：%s")
    val reconnecting: String get() = pick("Reconnecting…", "重连中…")
    val sessionGone: String get() = pick("This session is no longer on the host.", "该会话已在主机上消失。")
    val pairingExpired: String get() = pick("Pairing expired — scan the QR code again.", "配对已失效，请重新扫描二维码。")
    val send: String get() = pick("Send", "发送")
    val scrollToBottom: String get() = pick("Scroll to latest", "回到最新")
    val historyUnavailable: String get() = pick("Couldn’t load this session", "无法加载这个会话")
    val emptyChat: String get() = pick("No messages yet", "还没有消息")
    val emptyChatHint: String get() = pick("Describe what you want built.", "描述你想构建的内容。")
    val stop: String get() = pick("Stop", "停止")
    val contextUsed: String get() = pick("Used", "已用")
    val contextWindow: String get() = pick("Window", "窗口")
    val contextOccupancy: String get() = pick("Occupancy", "占用")
    // 上下文环弹窗的字段，与 Desktop ContextBreakdownPanel 逐条同文案。
    val contextAria: String get() = pick("%d%% of context used", "上下文已用 %d%%")
    val contextSystem: String get() = pick("System prompt", "系统提示词")
    val contextTools: String get() = pick("Tools", "工具")
    val contextMessages: String get() = pick("Messages", "对话消息")
    val statsDialogTitle: String get() = pick("Session statistics", "会话统计")
    val statsUsageTitle: String get() = pick("Token usage", "Token 用量")
    val statsProgress: String get() = pick("Turns and steps", "轮次与步数")
    val statsCounts: String get() = pick("%d turns %d steps", "%d 轮 %d 步")
    val statsLlm: String get() = pick("LLM time", "模型用时")
    val statsTools: String get() = pick("Tool time", "工具调用用时")
    val statsTtft: String get() = pick("Avg time to first token (TTFT)", "首 token 平均（TTFT）")
    val statsDecode: String get() = pick("Tokens per second (TPS)", "输出速度（TPS）")
    val statsDurationMinutes: String get() = pick("%dm%ds", "%d分%d秒")
    val sessionsCount: String get() = pick("Sessions", "会话数")
    val allSessions: String get() = pick("Sessions", "会话")
    val ungrouped: String get() = pick("Other", "其他")
    val viewOptions: String get() = pick("View options", "视图选项")
    val reorderGroups: String get() = pick("Touch and hold to reorder groups", "长按拖动调整分组顺序")
    val disconnected: String get() = pick("Not connected", "未连接")
    // 顶部全局连接提示条：连不上时只说人话，底层异常绝不端到用户面前。
    val offlineBannerTitle: String get() = pick("Can’t reach your Desktop", "无法连接到电脑")
    val offlineBannerBody: String get() =
        pick("Check that DSH is running on your Desktop and this phone is online.", "请确认电脑上的 DSH 正在运行，且手机网络正常。")
    /** 其余请求失败的兜底文案：不暴露异常类型与堆栈信息。 */
    val operationFailed: String get() = pick("Something went wrong. Please try again.", "操作失败，请重试。")
    val showMore: String get() = pick("Show %d more", "再显示 %d 个")
    val approve: String get() = pick("Approve once", "本次允许")
    val deny: String get() = pick("Deny", "拒绝")
    val fileLoading: String get() = pick("Loading file…", "正在加载文件…")
    val fileEmpty: String get() = pick("This file is empty", "这个文件是空的")
    val fileUnavailable: String get() = pick("Couldn’t open this file", "无法打开此文件")
    /** 图片链接：取回失败，或系统里没有能看图片的应用（与附件图的 imageUnavailable 不同）。 */
    val imageOpenFailed: String get() = pick("Couldn’t open this image", "无法打开这张图片")
    val cancel: String get() = pick("Cancel", "取消")
    val done: String get() = pick("Done", "完成")
    val close: String get() = pick("Close", "关闭")
    val retry: String get() = pick("Retry", "重试")
    /** Markdown 里单独成段的图片读取/解码失败（web `image.failed`），后接「 · alt」。 */
    val imagePreviewUnavailable: String get() = pick("Image preview unavailable", "图片无法预览")
    val permissions: String get() = pick("Permissions", "权限")
    val sessionHasDraft: String get() = pick("Draft", "草稿")
    val sessionAwaitingApproval: String get() = pick("Approve", "待审批")
    val sessionAwaitingPlanReview: String get() = pick("Review plan", "计划待审")
    val sessionAwaitingQuestion: String get() = pick("Answer", "待回答")
    val newSessionPlaceholder: String get() = pick("New Session", "新会话")
    val todoPanelTitle: String get() = pick("Todo", "待办")
    val todoProgressDone: String get() = pick("%d completed", "%d 已完成")
    val todoProgressActive: String get() = pick("%d in progress", "%d 进行中")
    val todoProgressPending: String get() = pick("%d pending", "%d 待处理")
    val todoCompleted: String get() = pick("%d/%d completed", "%d/%d 已完成")
    val copyAction: String get() = pick("Copy", "复制")
    val messageBranch: String get() = pick("Branch into a new conversation", "在新对话中分支")
    val turnUsageTitle: String get() = pick("Turn usage", "本轮用量")
    val turnUsageModel: String get() = pick("Provider / model", "提供方 / 模型")
    val turnUsageCacheHit: String get() = pick("Cache hit", "缓存命中")
    val turnUsageInput: String get() = pick("Uncached input", "未缓存输入")
    val turnUsageCacheRead: String get() = pick("Cached input", "缓存读取")
    val turnUsageCacheWrite: String get() = pick("Cache write", "缓存写入")
    val turnUsageOutput: String get() = pick("Output", "输出")
    val turnUsageReasoning: String get() = pick("(%s reasoning)", "（其中推理 %s）")
    val turnUsageCount: String get() = pick("%s tok", "%s tok")
    val turnTimeTitle: String get() = pick("Turn time and speed", "本轮用时和速度")
    val turnTimeDuration: String get() = pick("Total run time", "本轮总用时")
    val turnTimeSpeed: String get() = pick("Tokens per second (TPS)", "输出速度（TPS）")
    val turnTimeTtft: String get() = pick("Time to first token (TTFT)", "首 token 用时（TTFT）")
    val durationSecondsText: String get() = pick("%ss", "%s秒")
    val messageTokensPerSecond: String get() = pick("%s tok/s", "%s tok/s")
    // web `duration.*Unit`：用时读数里每个数字后面跟的单位，含尾随空格。
    val durationSecondUnit: String get() = pick("s", "秒")
    val durationMinuteUnit: String get() = pick("m ", "分")
    val durationHourUnit: String get() = pick("h ", "小时")
    val questionRecommended: String get() = pick("Recommended", "推荐")
    val questionPrev: String get() = pick("Previous question", "上一题")
    val questionNext: String get() = pick("Next question", "下一题")
    val questionActionNext: String get() = pick("Next", "下一题")
    val questionCancel: String get() = pick("Dismiss all questions", "放弃整组问题")
    val questionCustomPlaceholder: String get() = pick("Type your answer", "输入你的答案")
    val questionErrorUnanswered: String get() =
        pick("Please select an option or enter a custom answer.", "请选择一个选项或填写自定义答案。")
    val questionErrorIncomplete: String get() = pick("Please complete this question first.", "请先完成这道问题。")
    /** 逐题分页的进度，如 "2 / 3"。 */
    val questionProgress: String get() = pick("%d / %d", "%d / %d")
    // 限时提问（web `wait.*`、`nav.close`、`reply.*`）。
    val questionWaitTakeTime: String get() = pick("Take time", "慢慢回答")
    val questionWaitCountdown: String get() = pick("Continuing in %ds", "%d 秒后继续工作")
    val questionWaitHeld: String get() = pick("Waiting until you answer", "会一直等你回答")
    val questionWaitContinued: String get() = pick("Work continued — you can still answer", "已继续工作，仍可回答")
    val questionHide: String get() = pick("Close — reopen it above the composer", "收起，可在输入框上方重新打开")
    val questionReopen: String get() = pick("Answer", "回答")
    val questionReplyLabel: String get() = pick("Reply to earlier pending questions", "回答先前等待中的问题")
    val questionReplyAnswer: String get() = pick("Answer: ", "回答：")
    val questionReplySkipped: String get() = pick("Skipped", "已跳过")
    val questionPlanHeader: String get() = pick("Plan review", "计划待审")
    val questionPlanApprove: String get() = pick("Approve", "确认执行")
    val questionPlanApproveWithNotes: String get() = pick("Submit Notes", "提交修改")
    val questionPlanDecline: String get() = pick("Revise", "修改")
    val questionPlanFeedbackPlaceholder: String get() = pick("Add revision notes (optional)", "写下修改意见（可选）")
    val questionPlanDiscuss: String get() = pick("Chat about it", "去聊天里说")
    val workflowUntitled: String get() = pick("Workflow", "工作流")
    // web `workflowRun` 字典的 status.* 五个词（Mac `localizedStatusWord`）。
    val workflowStatusRunning: String get() = pick("Running", "运行中")
    val workflowStatusCompleted: String get() = pick("Completed", "已完成")
    val workflowStatusFailed: String get() = pick("Failed", "失败")
    val workflowStatusCancelled: String get() = pick("Cancelled", "已取消")
    val workflowStatusInterrupted: String get() = pick("Interrupted", "已中断")
    val askWaiting: String get() = pick("waiting", "等待回答")
    val askCancelled: String get() = pick("cancelled", "已取消")
    val askInterrupted: String get() = pick("interrupted", "已中断")
    val askAnswered: String get() = pick("%d/%d answered", "%d/%d 已回答")
    val planAwaitingReview: String get() = pick("Awaiting review", "等待审阅")
    val planApproved: String get() = pick("Plan approved", "计划已通过")
    val accessReadOnly: String get() = pick("Read Only", "只读")
    val accessWorkspaceWrite: String get() = pick("Workspace Write", "工作区写入")
    val accessFullName: String get() = pick("Full access", "完全访问")
    val accessCustom: String get() = pick("Custom", "自定义")
    val presetStandardName: String get() = pick("Standard mode", "标准模式")
    val presetPtcName: String get() = pick("PTC mode", "PTC 模式")
    val presetMinimalName: String get() = pick("Minimal mode", "极简模式")
    val presetCordisName: String get() = pick("Creator mode", "创造模式")
    val presetUserTrust: String get() = pick("Custom", "自定义")
    /** 与 iOS approvalEscalation 同口径：工具 %s 请求越权执行。 */
    val approvalEscalation: String get() = pick("Tool %s requests privileged execution", "工具 %s 请求越权执行")
    val searchNoResults: String get() = pick("No matching sessions", "没有匹配的会话")
    /** 结果被截断时的提示，%s 是结果上限。 */
    val searchHasMore: String get() =
        pick("Showing the first %s results. Narrow your search.", "仅显示前 %s 条结果，请缩小搜索范围。")
    val serverRemove: String get() = pick("Remove pairing", "移除配对")
    val serverRemoveMessage: String get() =
        pick("This only removes the pairing on this device.", "只会移除本机上的这条配对。")
    val remove: String get() = pick("Remove", "移除")
    val notifyApproval: String get() = pick("Needs approval", "需要审批")
    val notifyQuestion: String get() = pick("Needs an answer", "需要回答")
    val notifyDone: String get() = pick("Turn finished", "这一轮已完成")
    val notifyFailed: String get() = pick("Turn failed", "这一轮失败")
    /** 主机的失败码是 `AUTH`（401/403）时替换 provider 原文的说法（web `message.failure.auth`）。 */
    val turnFailureAuthInvalid: String get() = pick("API key is invalid", "API 密钥无效")
    /** `turn/end` 的 error 没带 message 时的兜底（Mac `notificationTurnFailedFallback`）。 */
    val turnFailureFallback: String get() = pick("The agent stopped with an error.", "智能体因错误中止。")
    val notifyServiceRunning: String get() =
        pick("Keeping the connection alive for running tasks", "正在为运行中的任务保持连接")
    val notifyServiceDescription: String get() =
        pick("Shown while a task is running.", "任务运行期间常驻，避免系统冻结后台连接。")
}

/**
 * t() 风格的格式化：把 %d/%s 占位符填上。
 *
 * 区域固定成 [java.util.Locale.ROOT]：本端界面语言只有中/英两种，占位符里的数字就该是
 * ASCII。跟随设备区域会让阿拉伯语设备把 `%d` 渲染成阿拉伯数字（٢٠٢٥），与 Format.kt
 * 里已固定区域的小数/千分位读数对不上，同一屏出现两种数字形状。
 */
fun fmt(template: String, vararg args: Any): String =
    runCatching { String.format(java.util.Locale.ROOT, template, *args) }.getOrDefault(template)

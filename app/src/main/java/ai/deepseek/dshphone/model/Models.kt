package ai.deepseek.dshphone.model

import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.net.WorkspaceId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// 会话 —————————————————————————————————————————————————————————

@Serializable
data class SessionProjectionsBlock(
    val asOfSeq: Int = 0,
    val values: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class SessionSummary(
    val sessionId: SessionId,
    var updatedAt: Double = 0.0,
    var running: Boolean = false,
    var blank: Boolean = false,
    var parentSessionId: SessionId? = null,
    var origin: String? = null,
    var cwd: String? = null,
    var agentPreset: String? = null,
    var projections: SessionProjectionsBlock? = null,
)

/**
 * 这条会话是不是某个会话派生出来的子智能体（主机的 origin 口径，与 Mac 端一致）。
 *
 * 子智能体归父会话的子智能体面板管，不进顶层会话列表、也不进会话搜索结果；
 * 普通 fork 出来的会话 origin 不是 "subagent"，仍然当独立会话对待。
 */
val SessionSummary.isSubagentOrigin: Boolean get() = origin == "subagent"

@Serializable
data class SessionListValue(val items: List<SessionSummary> = emptyList())

@Serializable
data class SessionSearchItem(val sessionId: SessionId, val snippet: String = "")

@Serializable
data class SessionSearchValue(val items: List<SessionSearchItem> = emptyList(), val hasMore: Boolean = false)

@Serializable
data class SessionCreateValue(val sessionId: SessionId, val agentPreset: String? = null)

@Serializable
data class SessionForkValue(val sessionId: SessionId)

@Serializable
data class FileReferenceItem(val path: String, val kind: String = "file")

@Serializable
data class SessionReferenceItem(
    val mention: String,
    val sessionId: SessionId,
    val label: String,
    val cwd: String? = null,
    val createdAt: Double = 0.0,
)

// 模型目录 ————————————————————————————————————————————————————

@Serializable
data class ModelSelection(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
)

@Serializable
data class ModelReasoningEffort(val id: String, val name: String, val description: String? = null)

@Serializable
data class ModelReasoning(val efforts: List<ModelReasoningEffort> = emptyList(), val defaultEffort: String? = null)

@Serializable
data class ModelCatalogModel(
    val id: String,
    val name: String,
    val description: String? = null,
    val reasoning: ModelReasoning? = null,
)

@Serializable
data class ModelProviderGroup(val id: String, val name: String, val models: List<ModelCatalogModel> = emptyList())

@Serializable
data class ModelCatalogFailure(val id: String, val name: String, val message: String)

@Serializable
data class ModelCatalogValue(
    @SerialName("default") val defaultSelection: ModelSelection,
    val routableProviders: List<String> = emptyList(),
    val groups: List<ModelProviderGroup> = emptyList(),
    val failures: List<ModelCatalogFailure>? = null,
)

data class SessionModelsValue(
    val current: ModelSelection,
    val routable: Boolean,
    val groups: List<ModelProviderGroup>,
    val failures: List<ModelCatalogFailure>,
)

@Serializable
data class SessionSelectModelValue(val selected: ModelSelection)

enum class ModelDirectoryStatus { IDLE, LOADING, READY, SELECTING, ERROR }

/** 一个会话的模型目录状态：最新一次操作获胜。 */
data class ModelDirectoryState(
    val current: ModelSelection? = null,
    val projected: ModelSelection? = null,
    val routable: Boolean? = null,
    val groups: List<ModelProviderGroup> = emptyList(),
    val failures: List<ModelCatalogFailure> = emptyList(),
    val status: ModelDirectoryStatus = ModelDirectoryStatus.IDLE,
    val error: String? = null,
    val generation: Int = 0,
) {
    fun beginLoading() = copy(generation = generation + 1, status = ModelDirectoryStatus.LOADING, error = null)
    fun beginSelecting() = copy(generation = generation + 1, status = ModelDirectoryStatus.SELECTING, error = null)

    fun receive(value: SessionModelsValue, expected: Int): ModelDirectoryState {
        if (generation != expected) return this
        return copy(
            current = if (projected == null) value.current else current,
            routable = value.routable,
            groups = value.groups,
            failures = value.failures,
            status = ModelDirectoryStatus.READY,
            error = null,
        )
    }

    fun receive(value: SessionSelectModelValue, expected: Int): ModelDirectoryState {
        if (generation != expected) return this
        return copy(
            projected = null,
            current = value.selected,
            routable = true,
            status = ModelDirectoryStatus.READY,
            error = null,
        )
    }

    fun applyProjectedSelection(selection: ModelSelection) = copy(
        projected = selection,
        current = selection,
        routable = if (groups.any { it.id == selection.provider }) true else routable,
    )

    fun fail(message: String, expected: Int): ModelDirectoryState =
        if (generation != expected) this else copy(status = ModelDirectoryStatus.ERROR, error = message)
}

// 权限 ————————————————————————————————————————————————————————

@Serializable
data class PermissionPresetOption(val value: String, val name: String, val description: String? = null)

/**
 * `permissions` 会话投影的 wire 值：在位的那个选择。
 *
 * 0.1.6 把这个投影收窄成只剩 currentValue，可选预设改由 [PermissionCatalogValue] 的另一条 RPC
 * 发布；`options` 仍保留且可缺省，因为 0.1.5 主机仍把列表内联在这里，客户端要能连两个版本。
 */
@Serializable
data class PermissionSelectValue(
    val options: List<PermissionPresetOption> = emptyList(),
    val currentValue: String = "",
)

/** `permissionPresets/catalog` 发布的进程级预设目录。 */
@Serializable
data class PermissionCatalogValue(val options: List<PermissionPresetOption> = emptyList())

/**
 * 一个会话能提供的权限选择：在位的当前值 + 候选预设。
 *
 * 两半来自不同来源（0.1.6 是投影 + RPC，0.1.5 只有投影），合并后各界面只读这一个值。
 * catalog 一旦拉到就优先；否则退回投影内联的那份（0.1.5 主机）。
 */
data class PermissionSelection(
    val currentValue: String,
    val options: List<PermissionPresetOption>,
) {
    val currentOption: PermissionPresetOption? get() = options.firstOrNull { it.value == currentValue }
    val selectableOptions: List<PermissionPresetOption> get() = options.filter { it.value != "custom" }

    companion object {
        fun merge(projection: PermissionSelectValue?, catalog: List<PermissionPresetOption>?): PermissionSelection? {
            if (projection == null) return null
            return PermissionSelection(projection.currentValue, catalog ?: projection.options)
        }
    }
}

// 附件 ————————————————————————————————————————————————————————

/** 归一化缩放前的输入尺寸；只有被缩放过的图片才有（web 的 originalDimensions）。 */
@Serializable
data class ImageDimensions(val width: Int = 0, val height: Int = 0)

@Serializable
data class ImageAttachmentRef(
    val attachmentId: String,
    val mediaType: String = "image/png",
    val bytes: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val name: String? = null,
    val originalDimensions: ImageDimensions? = null,
)

@Serializable
data class SessionAttachmentValue(val attachment: ImageAttachmentRef, val data: String)

@Serializable
data class ImageAttachmentLimits(
    val maxImageBytes: Int,
    val maxImagesPerMessage: Int,
    val maxMessageImageBytes: Int,
    val maxImagePixels: Int,
    val mediaTypes: List<String>,
)

// 历史 ————————————————————————————————————————————————————————

@Serializable
data class SessionEvent(
    val type: String,
    val seq: Int,
    val time: Double = 0.0,
    val data: JsonElement? = null,
    val sourceEventSeqs: List<Int>? = null,
    val surfaceOp: JsonElement? = null,
    val ignorable: Boolean? = null,
)

@Serializable
data class ToolEventView(
    @SerialName("for") val target: String = "",
    val view: JsonElement? = null,
)

@Serializable
data class HistoryEntry(val event: SessionEvent, val view: ToolEventView? = null)

data class SessionHistoryValue(
    val events: List<HistoryEntry>,
    val hasMore: Boolean,
    val projections: SessionProjectionsBlock? = null,
)

@Serializable
data class SessionPageValue(val records: List<HistoryEntry> = emptyList(), val hasMore: Boolean = false)

@Serializable
data class WorkspaceFileTextValue(
    val absolutePath: String = "",
    val version: String = "",
    val bytes: Int? = null,
    val offset: Int = 1,
    val text: String = "",
    val lines: Int = 0,
    val eof: Boolean = true,
)

// 工作区 ————————————————————————————————————————————————————————

@Serializable
data class WorkspaceView(
    val workspaceId: WorkspaceId,
    val path: String = "",
    var title: String = "",
    var sessionIds: List<SessionId> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = "",
)

/** `workspace/insertBefore` 的返回：主机落定后的完整工作区顺序。 */
@Serializable
data class WorkspaceOrderValue(val workspaceIds: List<WorkspaceId> = emptyList())

// Agent 预设 ——————————————————————————————————————————————————

@Serializable
data class AgentPresetEntry(
    val id: String,
    val trust: String = "user",
    val isDefault: Boolean = false,
    val name: String? = null,
    val description: String? = null,
    val broken: String? = null,
)

@Serializable
data class AgentPresetListValue(
    val presets: List<AgentPresetEntry> = emptyList(),
    val authorable: Boolean = false,
    val hasDocument: Boolean = false,
)

// 命令与 skills ————————————————————————————————————————————————

@Serializable
data class CommandInputDescriptor(val hint: String = "", val attachments: Boolean = false)

@Serializable
data class CommandDescriptor(
    val name: String,
    val description: String = "",
    val input: CommandInputDescriptor? = null,
)

/**
 * `commands/execute` 的结果。主机只在命令行命中某条命令时才回值（未命中回 null），
 * 所以这里同时是"这条草稿是不是命令"的判据。
 */
@Serializable
data class CommandExecutionValue(
    val commandId: String = "",
    val result: CommandResultValue? = null,
)

@Serializable
data class CommandResultValue(val kind: String = "success", val text: String? = null)

@Serializable
data class SkillEntry(val name: String, val description: String? = null, val modelInvocable: Boolean = false)

@Serializable
data class SkillListValue(val skills: List<SkillEntry> = emptyList())

// 目标 ————————————————————————————————————————————————————————

@Serializable
data class GoalRef(val id: String, val revision: Int)

@Serializable
data class GoalCreateValue(val ref: GoalRef)

@Serializable
data class GoalViewValue(val ref: GoalRef)

// 后台任务 ————————————————————————————————————————————————————

@Serializable
enum class JobStatus {
    @SerialName("running") RUNNING,
    @SerialName("stopping") STOPPING,
    @SerialName("completed") COMPLETED,
    @SerialName("killed") KILLED,
    @SerialName("failed") FAILED;

    val wire: String get() = name.lowercase()
}

@Serializable
data class JobView(
    val id: String,
    val kind: String = "",
    val label: String = "",
    val status: JobStatus = JobStatus.RUNNING,
    val detail: String? = null,
    val startedAt: Double = 0.0,
    val finishedAt: Double? = null,
) {
    val isLive: Boolean get() = status == JobStatus.RUNNING || status == JobStatus.STOPPING
}

// 子智能体 ————————————————————————————————————————————————————

@Serializable
enum class SubagentMode {
    @SerialName("one-shot") ONE_SHOT,
    @SerialName("continuable") CONTINUABLE;

    val wire: String get() = if (this == ONE_SHOT) "one-shot" else "continuable"
}

data class SubagentAddress(
    val parentSessionId: SessionId,
    val childSessionId: SessionId,
    val mode: SubagentMode,
)

/** subagents/list 的一行：健康的子会话或一条诊断。 */
data class SubagentListEntry(
    val sessionId: SessionId,
    val isChild: Boolean,
    val mode: SubagentMode = SubagentMode.ONE_SHOT,
    val running: Boolean = false,
    val hasChildren: Boolean = false,
    val label: String? = null,
    val reason: String? = null,
)

data class SubagentListValue(val entries: List<SubagentListEntry>, val parentAvailable: Boolean)

// 会话地址 ————————————————————————————————————————————————————

sealed interface SessionAddress {
    data class Session(val sessionId: SessionId) : SessionAddress
    data class Subagent(val address: SubagentAddress) : SessionAddress
}

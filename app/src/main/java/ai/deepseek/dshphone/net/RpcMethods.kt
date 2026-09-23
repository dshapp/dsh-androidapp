package ai.deepseek.dshphone.net

import ai.deepseek.dshphone.model.AgentPresetListValue
import ai.deepseek.dshphone.model.ChangedFileDiff
import ai.deepseek.dshphone.model.CommandDescriptor
import ai.deepseek.dshphone.model.CommandExecutionValue
import ai.deepseek.dshphone.model.FileReferenceItem
import ai.deepseek.dshphone.model.GoalCreateValue
import ai.deepseek.dshphone.model.GoalRef
import ai.deepseek.dshphone.model.GoalViewValue
import ai.deepseek.dshphone.model.HistoryEntry
import ai.deepseek.dshphone.model.ModelCatalogValue
import ai.deepseek.dshphone.model.PermissionCatalogValue
import ai.deepseek.dshphone.model.PermissionPresetOption
import ai.deepseek.dshphone.model.SessionAddress
import ai.deepseek.dshphone.model.TurnChangesSummary
import ai.deepseek.dshphone.model.SessionAttachmentValue
import ai.deepseek.dshphone.model.SessionCreateValue
import ai.deepseek.dshphone.model.SessionForkValue
import ai.deepseek.dshphone.model.SessionHistoryValue
import ai.deepseek.dshphone.model.SessionListValue
import ai.deepseek.dshphone.model.SessionModelsValue
import ai.deepseek.dshphone.model.SessionPageValue
import ai.deepseek.dshphone.model.SessionReferenceItem
import ai.deepseek.dshphone.model.SessionSearchValue
import ai.deepseek.dshphone.model.SessionSelectModelValue
import ai.deepseek.dshphone.model.SkillListValue
import ai.deepseek.dshphone.model.SubagentAddress
import ai.deepseek.dshphone.model.SubagentCatalogEntry
import ai.deepseek.dshphone.model.WorkspaceFileBytesValue
import ai.deepseek.dshphone.model.WorkspaceFileTextValue
import ai.deepseek.dshphone.model.WorkspaceOrderValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** prompt 的一块内容。 */
sealed interface PromptContentPart {
    data class Text(val text: String) : PromptContentPart
    data class Image(val mediaType: String, val data: String, val name: String?) : PromptContentPart
    data class File(val receiptId: String) : PromptContentPart
}

private fun PromptContentPart.toJson(): JsonElement = when (this) {
    is PromptContentPart.Text -> buildJsonObject { put("type", "text"); put("text", text) }
    is PromptContentPart.Image -> buildJsonObject {
        put("type", "image"); put("mediaType", mediaType); put("data", data)
        name?.let { put("name", it) }
    }
    is PromptContentPart.File -> buildJsonObject { put("type", "file"); put("receiptId", receiptId) }
}

private fun SessionAddress.toJson(): JsonElement = when (this) {
    is SessionAddress.Session -> buildJsonObject { put("kind", "session"); put("sessionId", sessionId) }
    is SessionAddress.Subagent -> buildJsonObject {
        put("kind", "subagent")
        put("parentSessionId", address.parentSessionId)
        put("childSessionId", address.childSessionId)
        put("mode", address.mode.wire)
    }
}

/** 队列条目的操作。 */
sealed interface QueueAction {
    data class Edit(val text: String) : QueueAction
    data object Remove : QueueAction
    data object Steer : QueueAction
}

private fun QueueAction.toJson(): JsonElement = when (this) {
    is QueueAction.Edit -> buildJsonObject {
        put("kind", "edit")
        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
    }
    QueueAction.Remove -> buildJsonObject { put("kind", "remove") }
    QueueAction.Steer -> buildJsonObject { put("kind", "steer") }
}

private fun request(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonElement =
    buildJsonObject { put("request", buildJsonObject(block)) }

// —— session ——————————————————————————————————————————————————

/**
 * 会话列表。
 *
 * `_request.projections` 不是主机的字段，是给手机链路（mobile bridge）的提示：列表行只画
 * `title` 和 `agentPreset`，其余十几份投影块是这条响应体积的大头，由 bridge 在转发时丢掉。
 * 主机不认识这个字段会直接忽略，所以直连主机时（或多客户端共享 bridge 时）行为不变。
 */
suspend fun ApiClient.sessionList(): SessionListValue =
    call(
        "session/list",
        buildJsonObject {
            put("_request", buildJsonObject {
                put("projections", buildJsonArray {
                    add(JsonPrimitive("title"))
                    add(JsonPrimitive("agentPreset"))
                })
            })
        },
    ).decodeOrNull<SessionListValue>() ?: SessionListValue()

suspend fun ApiClient.sessionSearch(query: String): SessionSearchValue =
    call("session/search", request { put("query", query) })
        .decodeOrNull<SessionSearchValue>() ?: SessionSearchValue()

suspend fun ApiClient.sessionCreate(workspaceId: WorkspaceId? = null): SessionCreateValue =
    call("session/create", request { workspaceId?.let { put("workspaceId", it) } })
        .decodeOrNull<SessionCreateValue>() ?: throw internalError("session/create returned no value")

suspend fun ApiClient.sessionPage(
    address: SessionAddress,
    throughSeq: Int,
    beforeSeq: Int? = null,
    maxMessages: Int? = null,
): SessionPageValue = call(
    "session/page",
    request {
        put("address", address.toJson())
        put("throughSeq", throughSeq)
        beforeSeq?.let { put("beforeSeq", it) }
        maxMessages?.let { put("maxMessages", it) }
    },
).decodeOrNull<SessionPageValue>() ?: SessionPageValue()

suspend fun ApiClient.sessionHistory(
    sessionId: SessionId,
    throughSeq: Int,
    beforeSeq: Int? = null,
    maxMessages: Int? = 50,
): SessionHistoryValue {
    val page = sessionPage(SessionAddress.Session(sessionId), throughSeq, beforeSeq, maxMessages)
    return SessionHistoryValue(page.records, page.hasMore, null)
}

suspend fun ApiClient.subagentHistory(
    address: SubagentAddress,
    throughSeq: Int,
    beforeSeq: Int? = null,
    maxMessages: Int? = 50,
): SessionHistoryValue {
    val page = sessionPage(SessionAddress.Subagent(address), throughSeq, beforeSeq, maxMessages)
    return SessionHistoryValue(page.records, page.hasMore, null)
}

suspend fun ApiClient.sessionModels(): SessionModelsValue {
    val catalog = call("session/modelCatalog", JsonObject(emptyMap()))
        .decodeOrNull<ModelCatalogValue>() ?: throw internalError("model catalog unavailable")
    return SessionModelsValue(
        current = catalog.defaultSelection,
        routable = catalog.routableProviders.contains(catalog.defaultSelection.provider),
        groups = catalog.groups,
        failures = catalog.failures ?: emptyList(),
    )
}

suspend fun ApiClient.sessionSelectModel(
    sessionId: SessionId,
    provider: String,
    model: String,
    reasoningEffort: String?,
): SessionSelectModelValue = call(
    "session/selectModel",
    request {
        put("sessionId", sessionId)
        put("provider", provider)
        put("model", model)
        reasoningEffort?.let { put("reasoningEffort", it) }
    },
).decodeOrNull<SessionSelectModelValue>() ?: throw internalError("selectModel returned no value")

suspend fun ApiClient.sessionRename(sessionId: SessionId, title: String) {
    call("session/rename", request { put("sessionId", sessionId); put("title", title) })
}

suspend fun ApiClient.sessionFork(sessionId: SessionId, atSeq: Int? = null): SessionForkValue =
    call("session/fork", request { put("sessionId", sessionId); atSeq?.let { put("atSeq", it) } })
        .decodeOrNull<SessionForkValue>() ?: throw internalError("fork returned no value")

suspend fun ApiClient.sessionPrompt(
    sessionId: SessionId,
    mode: String,
    content: List<PromptContentPart>,
    clientTimeZone: String?,
    requestId: String,
): Boolean {
    val value = call(
        "session/prompt",
        request {
            put("requestId", requestId)
            put("sessionId", sessionId)
            put("mode", mode)
            put("content", JsonArray(content.map { it.toJson() }))
            clientTimeZone?.let { put("clientTimeZone", it) }
        },
    )
    return value["accepted"].bool ?: true
}

suspend fun ApiClient.sessionCancel(sessionId: SessionId) {
    call("session/cancel", request { put("sessionId", sessionId) })
}

suspend fun ApiClient.sessionUpdateQueue(sessionId: SessionId, itemId: MessageId, action: QueueAction) {
    call(
        "session/updateQueue",
        request { put("sessionId", sessionId); put("itemId", itemId); put("action", action.toJson()) },
    )
}

suspend fun ApiClient.sessionAttachment(sessionId: SessionId, attachmentId: AttachmentId): SessionAttachmentValue =
    call("session/attachment", request { put("sessionId", sessionId); put("attachmentId", attachmentId) })
        .decodeOrNull<SessionAttachmentValue>() ?: throw internalError("attachment unavailable")

/**
 * 归档一条会话；返回主机落定后的完整归档集合。
 *
 * 主机一直返回 `{ archivedSessionIds }`，只是本地实现早先丢弃了它。返回值让调用方
 * 能立刻把归档集合落进 WorkspaceStore / SessionListStore，不必等下一次
 * `workspace/follow` 帧——与 [workspaceUnarchiveSession] 同一形状。
 */
suspend fun ApiClient.workspaceArchiveSession(sessionId: SessionId, stopActivity: Boolean = false): List<SessionId> =
    call("workspace/archiveSession", request { put("sessionId", sessionId); if (stopActivity) put("stopActivity", true) })
        .let { value -> (value["archivedSessionIds"].arr ?: emptyList()).mapNotNull { it.str } }

/**
 * 置顶/取消置顶一条会话（0.1.7 起置顶存在主机上），返回落定后的完整置顶集，最近置顶的在前。
 */
suspend fun ApiClient.workspaceSetPinned(sessionId: SessionId, pinned: Boolean): List<SessionId> =
    call(if (pinned) "workspace/pinSession" else "workspace/unpinSession", request { put("sessionId", sessionId) })
        .let { value -> (value["pinnedSessionIds"].arr ?: emptyList()).mapNotNull { it.str } }

// —— 每轮改动文件（0.1.6 的 ui-deliverables 路由） —————————————————

/**
 * 一轮改动文件的汇总。
 *
 * 这是插件路由而不是 Remote 方法：`workspace/changes` 事件只带轮次，汇总（文件与增删行数）
 * 要按该事件自己的 seq 来这里读。主机在会话销毁后返回 404，那是「没有可显示的东西」，
 * 因此返回 null 而不是抛错。
 */
suspend fun ApiClient.changesSummary(sessionId: SessionId, seq: Int): TurnChangesSummary? =
    getJson(
        "api/changes.summary",
        listOf("sessionId" to sessionId, "seq" to seq.toString()),
    )?.let { TurnChangesSummary.decode(it) }

/** 一个列出的文件在轮首与轮末的比较；同样以 404 表示主机不再提供。 */
suspend fun ApiClient.changesDiff(sessionId: SessionId, seq: Int, index: Int): ChangedFileDiff? =
    getJson(
        "api/changes.diff",
        listOf("sessionId" to sessionId, "seq" to seq.toString(), "index" to index.toString()),
    )?.let { ChangedFileDiff.decode(it) }

/** 恢复已归档会话（0.1.6 新增）；返回主机落定后的完整归档集合。 */
suspend fun ApiClient.workspaceUnarchiveSession(sessionId: SessionId): List<SessionId> =
    call("workspace/unarchiveSession", request { put("sessionId", sessionId) })
        .let { value -> (value["archivedSessionIds"].arr ?: emptyList()).mapNotNull { it.str } }

/**
 * 把一个工作区挪到另一个工作区之前，[beforeWorkspaceId] 为空表示挪到末尾。
 *
 * 与 Mac 的 `workspaceInsertBefore` 同一口径：末尾是「不带这个字段」，而不是把它写成
 * null——主机按字段存在与否区分「插到某个工作区之前」和「插到末尾」。返回的是主机落定
 * 后的完整顺序，本地据此对齐。
 */
suspend fun ApiClient.workspaceInsertBefore(
    workspaceId: WorkspaceId,
    beforeWorkspaceId: WorkspaceId? = null,
): WorkspaceOrderValue =
    call(
        "workspace/insertBefore",
        request {
            put("workspaceId", workspaceId)
            beforeWorkspaceId?.let { put("beforeWorkspaceId", it) }
        },
    ).decodeOrNull<WorkspaceOrderValue>() ?: throw internalError("workspace/insertBefore returned no value")

suspend fun ApiClient.hostOpenPath(path: String) {
    call("session/openWorkspacePath", request { put("path", path) })
}

suspend fun ApiClient.workspaceFileRead(sessionId: SessionId, path: String, offset: Int): WorkspaceFileTextValue =
    call(
        "workspaceFiles/read",
        buildJsonObject {
            put("workspaceFileScopeId", sessionId)
            put("path", path)
            put("range", buildJsonObject { put("offset", offset) })
        },
    ).decodeOrNull<WorkspaceFileTextValue>() ?: throw internalError("file read failed")

/**
 * 读回一个工作区文件的字节窗口（[ApiClient.call] 把 multipart 字节还原成 base64），用于把图片交给系统查看器。
 * 主机 readBytes 的单窗口上限默认 2 MiB，靠 [WorkspaceFileBytesValue.eof] 分片读完，
 * 避免为了打开一张图一次拉回整份文件（readAll 上限 32 MiB，base64 后还要再涨三分之一）。
 */
suspend fun ApiClient.workspaceFileReadBytes(
    sessionId: SessionId,
    path: String,
    offset: Int,
    length: Int,
): WorkspaceFileBytesValue =
    call(
        "workspaceFiles/readBytes",
        buildJsonObject {
            put("workspaceFileScopeId", sessionId)
            put("path", path)
            // 0.1.7 把字节窗口挪进了 options.range（同层还可带 baseFile）；字节走 multipart 回来。
            put("options", buildJsonObject {
                put("range", buildJsonObject { put("offset", offset); put("length", length) })
            })
        },
    ).decodeOrNull<WorkspaceFileBytesValue>() ?: throw internalError("file read failed")

// —— commands / skills ————————————————————————————————————————

suspend fun ApiClient.commandsList(sessionId: SessionId): List<CommandDescriptor> =
    (call("commands/list", buildJsonObject { put("agentId", sessionId) }).arr ?: emptyList())
        .mapNotNull { it.decodeOrNull<CommandDescriptor>() }

/**
 * 执行一条斜杠命令行。返回 null 表示主机没认出这条命令（语法不合法或名字不在目录里）；
 * 认出但处理器失败时仍返回非 null，错误在 [CommandExecutionValue.result] 里。
 */
suspend fun ApiClient.commandsExecute(sessionId: SessionId, line: String): CommandExecutionValue? {
    val value = call(
        "commands/execute",
        buildJsonObject {
            put("agentId", sessionId)
            put("line", line)
            put("submittedAttachments", JsonArray(emptyList()))
        },
    )
    if (value is JsonNull) return null
    return value.decodeOrNull<CommandExecutionValue>()
}

suspend fun ApiClient.sessionSelectPermission(sessionId: SessionId, presetId: String) {
    commandsExecute(sessionId, "/permission $presetId")
}

/**
 * 进程级权限预设目录（0.1.6 新增）。
 *
 * 0.1.6 把 `permissions` 会话投影收窄成只剩 currentValue 之后，候选列表只能从这里拿；
 * 0.1.5 主机没有这条 RPC，调用会以 endpoint-unknown 失败，调用方按 null 处理即可。
 */
suspend fun ApiClient.permissionCatalog(): List<PermissionPresetOption> =
    call("permissionPresets/catalog", JsonObject(emptyMap()))
        .decodeOrNull<PermissionCatalogValue>()?.options ?: emptyList()

suspend fun ApiClient.skillList(sessionId: SessionId): SkillListValue =
    call("skills/list", request { put("sessionId", sessionId) })
        .decodeOrNull<SkillListValue>() ?: SkillListValue()

// —— 引用候选 ——————————————————————————————————————————————————

suspend fun ApiClient.fileReferencesList(sessionId: SessionId, query: String): List<FileReferenceItem> =
    (call("fileReferences/list", buildJsonObject { put("agentId", sessionId); put("query", query) }).arr
        ?: emptyList()).mapNotNull { it.decodeOrNull<FileReferenceItem>() }

suspend fun ApiClient.sessionReferenceCandidates(sessionId: SessionId, query: String): List<SessionReferenceItem> =
    (call(
        "sessionReferenceResolver/candidates",
        buildJsonObject { put("agentId", sessionId); put("query", query) },
    ).arr ?: emptyList()).mapNotNull { it.decodeOrNull<SessionReferenceItem>() }

// —— agent 预设 ————————————————————————————————————————————————

suspend fun ApiClient.agentPresetList(): AgentPresetListValue =
    call("agentPresets/list", JsonObject(emptyMap()))
        .decodeOrNull<AgentPresetListValue>() ?: AgentPresetListValue()

suspend fun ApiClient.agentPresetSelect(sessionId: SessionId, agentPreset: String) {
    call("agentPresets/select", buildJsonObject { put("agentId", sessionId); put("agentPreset", agentPreset) })
}

// —— 目标 ——————————————————————————————————————————————————————

suspend fun ApiClient.goalCreate(sessionId: SessionId, objective: String, maxGoalRounds: Int?): GoalRef =
    call(
        "goals/create",
        buildJsonObject {
            put("agentId", sessionId)
            put("request", buildJsonObject {
                put("objective", objective)
                maxGoalRounds?.let { put("maxGoalRounds", it) }
            })
        },
    ).decodeOrNull<GoalCreateValue>()?.ref ?: throw internalError("goal create failed")

private fun goalRefArgs(sessionId: SessionId, ref: GoalRef) = buildJsonObject {
    put("agentId", sessionId)
    put("ref", buildJsonObject { put("id", ref.id); put("revision", ref.revision) })
}

suspend fun ApiClient.goalClear(sessionId: SessionId, ref: GoalRef) {
    call("goals/clear", goalRefArgs(sessionId, ref))
}

suspend fun ApiClient.goalPause(sessionId: SessionId, ref: GoalRef) {
    call("goals/pause", goalRefArgs(sessionId, ref))
}

suspend fun ApiClient.goalResume(sessionId: SessionId, ref: GoalRef) {
    call("goals/resume", goalRefArgs(sessionId, ref))
}

suspend fun ApiClient.goalEdit(sessionId: SessionId, ref: GoalRef, objective: String) {
    call(
        "goals/edit",
        buildJsonObject {
            put("agentId", sessionId)
            put("ref", buildJsonObject { put("id", ref.id); put("revision", ref.revision) })
            put("request", buildJsonObject { put("objective", objective) })
        },
    ).decodeOrNull<GoalViewValue>()
}

// —— 子智能体 ————————————————————————————————————————————————

/** 请求停止一个后台任务（0.1.7 `job/kill`）；已结束的任务主机答 `already-finished`，不算失败。 */
suspend fun ApiClient.jobKill(sessionId: SessionId, jobId: String) {
    call("job/kill", request { put("sessionId", sessionId); put("jobId", jobId) })
}

/**
 * 父会话的直接子会话，读自它的 `subagentCatalog` 投影（`session/projections` 不会激活父会话的
 * Agent）。0.1.7 删掉了 `subagents/list`；会话不存在时主机返回 null，这里按空表处理。
 */
suspend fun ApiClient.subagentCatalog(parentSessionId: SessionId): List<SubagentCatalogEntry> =
    (call("session/projections", request { put("sessionId", parentSessionId) })["values"]["subagentCatalog"].arr
        ?: emptyList()).mapNotNull { it.decodeOrNull<SubagentCatalogEntry>() }

/**
 * 给可继续的子会话发一条消息。`requestId` 由客户端预先生成、随消息落库；
 * `delivery` 为 `queue`（排到下一轮）或 `steer`（插进最近的步骤边界），两者主机都要求必填。
 */
suspend fun ApiClient.subagentPrompt(address: SubagentAddress, text: String, clientTimeZone: String?, steer: Boolean = false) {
    call(
        "subagents/prompt",
        request {
            put("requestId", java.util.UUID.randomUUID().toString())
            put("delivery", if (steer) "steer" else "queue")
            put("parentSessionId", address.parentSessionId)
            put("childSessionId", address.childSessionId)
            put("mode", address.mode.wire)
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
            clientTimeZone?.let { put("clientTimeZone", it) }
        },
    )
}

suspend fun ApiClient.subagentInterrupt(address: SubagentAddress) {
    call(
        "subagents/interruptByParent",
        buildJsonObject {
            put("childSessionId", address.childSessionId)
            put("parentSessionId", address.parentSessionId)
            put("mode", address.mode.wire)
        },
    )
}

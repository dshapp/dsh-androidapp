package ai.deepseek.dshphone.model

import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.net.str

/**
 * 新建会话页要复用的那条空白会话（web `connectWorkspace` 的 reuse 扫描 / Mac
 * `materializePendingConversation`）。
 *
 * 同一工作区、同一 cwd、没被归档、且主机还认得出的空白会话可以接着用：用户再次
 * 进入新建会话不该每来一次就多留一条幽灵空白。
 *
 * [agentPresets] 是主机当前认得的预设目录；一条留在已删除/已损坏预设下的空白会话
 * 永久打不开（见 [canResumeBlankSession]），复用它会把这页做成发不出消息的死胡同。
 */
fun reusableBlankSession(
    sessions: List<SessionSummary>,
    workspace: WorkspaceView?,
    archived: Set<SessionId>,
    agentPresets: List<AgentPresetEntry>,
): SessionSummary? {
    val target = workspace ?: return null
    val memberIds = target.sessionIds.toSet()
    return sessions.firstOrNull { summary ->
        summary.blank && summary.cwd == target.path &&
            memberIds.contains(summary.sessionId) && summary.sessionId !in archived &&
            canResumeBlankSession(summary, agentPresets)
    }
}

/** 会话创建时记下的 Agent 预设：平铺字段优先，其次才是投影里的那份。 */
fun recordedAgentPreset(summary: SessionSummary): String? =
    summary.agentPreset ?: summary.projections?.values?.get("agentPreset")?.str

/**
 * 这条空白会话主机还认不认（Mac `canResume`）。
 *
 * 会话把自己创建时的 Agent 预设记在头上，主机每次 resume 都按那个**确切**的 id
 * 解析，没有"退回部署默认值"这一步。所以一条留在已删除/已损坏预设下的空白会话
 * 是永久打不开的：每次 `session/follow`、`session/prompt` 都会以
 * `agent-presets: preset "…" not found` 失败。
 *
 * 目录还没回来时一律放行：错放一条只多留一条空白，错拒一条赔的是用户那条消息。
 */
fun canResumeBlankSession(summary: SessionSummary, agentPresets: List<AgentPresetEntry>): Boolean {
    val preset = recordedAgentPreset(summary)
    if (preset == null || agentPresets.isEmpty()) return true
    val entry = agentPresets.firstOrNull { it.id == preset } ?: return false
    return entry.broken == null
}

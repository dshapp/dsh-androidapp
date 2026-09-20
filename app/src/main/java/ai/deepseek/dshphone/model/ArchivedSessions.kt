package ai.deepseek.dshphone.model

import ai.deepseek.dshphone.net.SessionId

/**
 * 已归档会话在客户端呈现所用的三个纯函数。
 *
 * 口径与 Mac 桌面端 `ArchivedSessionsView` 一致：主机把归档集合放在 Workspace 注册表
 * （`workspace/follow` 帧里带的是完整的 `archivedSessionIds`），归档不是删除，已归档会话的
 * 摘要仍在会话名册里；客户端只在渲染时把它滤掉。所以这一组函数都不发请求，纯粹从本地状态算结果。
 */

/**
 * 主会话列表可见的行：非空白、非 subagent、且不在归档集合里。
 *
 * 空白行是「新建会话」的占位；subagent 行归父会话的子智能体面板管。归档这一道闸必须
 * 加在这里而不是依赖归档时把行从名册摘掉：主机 `session/list` 会把已归档的会话带回来，
 * 只靠摘行的话每次刷新都会重现一次（Mac 边栏同样按 `archivedSessionIds` 在渲染时过滤）。
 */
fun listSessionsVisible(
    sessions: List<SessionSummary>,
    archivedSessionIds: Set<SessionId>,
): List<SessionSummary> = sessions.filter {
    !it.blank && !it.isSubagentOrigin && it.sessionId !in archivedSessionIds
}

/**
 * 归档页的行：按 [archivedSessionIds] 解析会话摘要，最近归档的在前。
 *
 * 顺序照抄 Mac/Web：持久归档集合按归档先后写入，页面把它反过来渲染。摘要已经不存在的
 * 成员（会话在主机被删、名册还没同步到）直接跳过——留着只会得到一条点不动的取消归档。
 */
fun archivedSessionRows(
    archivedSessionIds: List<SessionId>,
    sessionsById: Map<SessionId, SessionSummary>,
): List<SessionSummary> = archivedSessionIds.asReversed().mapNotNull { sessionsById[it] }

/**
 * 归档页的本地搜索过滤：按标题或工作区名做大小写不敏感的子串匹配。
 *
 * 纯本地、不请求 `session/search`——主机的内容搜索是可选的，而归档页要的是「按名字找回
 * 那条会话」。标题取 [sessionSearchName]（不拿 sessionId 兜底，否则「session」这种词会命中
 * 几乎每一条）；工作区名取 [sessionWorkspaceName]，与列表行的灰色前缀同一口径。
 */
fun filterArchivedSessions(
    rows: List<SessionSummary>,
    workspaceTitleBySession: Map<SessionId, String>,
    query: String,
): List<SessionSummary> {
    val needle = query.trim()
    if (needle.isEmpty()) return rows
    return rows.filter { summary ->
        sessionMatchesSearch(
            sessionSearchName(summary, liveTitle = null),
            sessionWorkspaceName(workspaceTitleBySession[summary.sessionId], summary.cwd),
            needle,
        )
    }
}

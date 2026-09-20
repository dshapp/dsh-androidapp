package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.AgentPresetEntry
import ai.deepseek.dshphone.model.SessionProjectionsBlock
import ai.deepseek.dshphone.model.SessionSummary
import ai.deepseek.dshphone.model.WorkspaceView
import ai.deepseek.dshphone.model.reusableBlankSession
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 新建会话页复用的空白会话必须是主机还认得出的那条。
 *
 * 会话把自己创建时的 Agent 预设记在头上，主机每次 resume 都按那个确切 id 解析，
 * 没有退回部署默认值这一步。所以一条留在已删除/已损坏预设下的空白会话永久打不开：
 * `session/follow`、`session/prompt` 都会以 `agent-presets: preset "…" not found`
 * 失败。Mac 的 `canResume` 正是这道闸（AppModel.swift），安卓的复用扫描以前只看
 * blank/cwd/归档三件事，会把这条死会话接着递回新建会话页——用户在那里打的第一条
 * 消息永远发不出去。
 */
class BlankSessionReuseTest {

    private val workspace = WorkspaceView(
        workspaceId = "ws1",
        path = "/tmp/proj",
        title = "proj",
        sessionIds = listOf("s1"),
    )

    private fun blank(preset: String?): SessionSummary = SessionSummary(
        sessionId = "s1",
        blank = true,
        cwd = "/tmp/proj",
        agentPreset = preset,
    )

    private val liveCatalog = listOf(AgentPresetEntry(id = "standard", trust = "system"))

    @Test
    fun aBlankUnderAMissingPresetIsNotReused() {
        val reused = reusableBlankSession(
            listOf(blank("plugin-that-was-removed")),
            workspace,
            emptySet(),
            liveCatalog,
        )
        assertNull("预设已不在目录里的空白会话是死胡同，不能再复用", reused)
    }

    @Test
    fun aBlankUnderABrokenPresetIsNotReused() {
        val reused = reusableBlankSession(
            listOf(blank("cordis")),
            workspace,
            emptySet(),
            listOf(AgentPresetEntry(id = "cordis", trust = "system", broken = "plugin failed to load")),
        )
        assertNull("已损坏的预设同样 resume 不了", reused)
    }

    @Test
    fun aBlankUnderALivePresetIsReused() {
        val reused = reusableBlankSession(
            listOf(blank("standard")),
            workspace,
            emptySet(),
            liveCatalog,
        )
        assertEquals("s1", reused?.sessionId)
    }

    @Test
    fun anEmptyCatalogDoesNotRejectAnything() {
        val reused = reusableBlankSession(
            listOf(blank("standard")),
            workspace,
            emptySet(),
            emptyList(),
        )
        assertEquals("目录还没回来时错拒一条赔的是用户那条消息，所以要放行", "s1", reused?.sessionId)
    }

    /** 平铺字段缺席时才看投影：`session/list` 的 `_request.projections` 只留 title/agentPreset。 */
    @Test
    fun theRecordedPresetFallsBackToTheProjection() {
        val summary = SessionSummary(
            sessionId = "s1",
            blank = true,
            cwd = "/tmp/proj",
        ).also {
            it.projections = SessionProjectionsBlock(
                values = mapOf("agentPreset" to JsonPrimitive("plugin-that-was-removed")),
            )
        }
        assertNull(
            "投影里记着的那份预设同样要过闸",
            reusableBlankSession(listOf(summary), workspace, emptySet(), liveCatalog),
        )
    }

    @Test
    fun sessionsThatAreNotInTheWorkspaceAreIgnored() {
        val other = WorkspaceView(workspaceId = "ws2", path = "/tmp/other", sessionIds = listOf("s1"))
        assertNull(reusableBlankSession(listOf(blank("standard")), other, emptySet(), liveCatalog))
    }

    @Test
    fun archivedBlanksAreIgnored() {
        assertNull(reusableBlankSession(listOf(blank("standard")), workspace, setOf("s1"), liveCatalog))
    }
}
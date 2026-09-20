package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.SessionProjectionsBlock
import ai.deepseek.dshphone.model.SessionSummary
import ai.deepseek.dshphone.model.archivedSessionRows
import ai.deepseek.dshphone.model.filterArchivedSessions
import ai.deepseek.dshphone.model.listSessionsVisible
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归档页与主列表的两个口径都必须与 Mac 一致：
 *
 * - Mac 桌面端 `ArchivedSessionsView`：行由**权威归档集合**（Workspace 注册表）与已加载的
 *   会话摘要求交而来，最近归档的在前，摘要已不存在的成员不产生行；搜索按标题或工作区名。
 * - Mac 边栏 `SidebarRoot`：已归档的行在渲染时被滤掉，而不是从名册里删掉——主机
 *   `session/list` 会把已归档的会话带回来，摘行只会在刷新后重现。
 *
 * 安卓原来归档后直接 `listStore.remove`，于是归档页拿不到标题、主列表刷新后又会冒回。
 * 这一组用例把新口径钉住。
 */
class ArchivedSessionsTest {

    private fun summary(
        id: String,
        title: String? = null,
        cwd: String? = null,
        blank: Boolean = false,
        origin: String? = null,
        updatedAt: Double = 0.0,
    ) = SessionSummary(
        sessionId = id,
        updatedAt = updatedAt,
        blank = blank,
        origin = origin,
        cwd = cwd,
        projections = title?.let {
            SessionProjectionsBlock(values = mapOf("title" to JsonPrimitive(it)))
        },
    )

    private fun ids(vararg values: String) = values.toList()

    // —— 归档页的行推导 ——————————————————————————————————————————

    @Test
    fun rowsAreMostRecentlyArchivedFirst() {
        val byId = listOf("a", "b", "c").associateWith { summary(it) }
        assertEquals(
            "归档集合按归档先后写入，页面要反过来渲染",
            listOf("c", "b", "a"),
            archivedSessionRows(ids("a", "b", "c"), byId).map { it.sessionId },
        )
    }

    @Test
    fun rowsSkipMembersWithoutASummary() {
        // "gone" 在归档集合里，但名册里已经没有摘要（会话在主机被删）。
        val byId = listOf("kept").associateWith { summary(it) }
        assertEquals(
            "摘要已不存在的归档条目不产生行，免得留下点不动的取消归档",
            listOf("kept"),
            archivedSessionRows(ids("kept", "gone"), byId).map { it.sessionId },
        )
    }

    @Test
    fun rowsAreEmptyWhenArchiveIsEmpty() {
        assertTrue(archivedSessionRows(emptyList(), emptyMap()).isEmpty())
    }

    // —— 归档页搜索 ————————————————————————————————————————————

    @Test
    fun blankQueryReturnsAllRows() {
        val rows = listOf(summary("a", title = "Alpha"), summary("b", title = "Beta"))
        assertEquals(
            listOf("a", "b"),
            filterArchivedSessions(rows, emptyMap(), "   ").map { it.sessionId },
        )
    }

    @Test
    fun queryMatchesTitleCaseInsensitively() {
        val rows = listOf(summary("a", title = "Fix The Parser"), summary("b", title = "Other"))
        assertEquals(
            "标题子串匹配不区分大小写",
            listOf("a"),
            filterArchivedSessions(rows, emptyMap(), "parser").map { it.sessionId },
        )
    }

    @Test
    fun queryMatchesWorkspaceName() {
        val rows = listOf(summary("a", title = "One", cwd = "/work/alpha"), summary("b", title = "Two"))
        assertEquals(
            "工作区名也参与匹配（这里是 cwd 末段）",
            listOf("a"),
            filterArchivedSessions(rows, emptyMap(), "ALPHA").map { it.sessionId },
        )
    }

    @Test
    fun workspaceTitleOverridesCwdBasenameInSearch() {
        val rows = listOf(summary("a", title = "One", cwd = "/work/alpha"))
        assertEquals(
            "工作区标题优先于 cwd 目录名，与列表行的灰色前缀同一口径",
            listOf("a"),
            filterArchivedSessions(rows, mapOf("a" to "Renamed Workspace"), "renamed").map { it.sessionId },
        )
        assertTrue(
            "被工作区标题遮住的 cwd 名不再参与匹配",
            filterArchivedSessions(rows, mapOf("a" to "Renamed Workspace"), "alpha").isEmpty(),
        )
    }

    @Test
    fun queryWithoutMatchReturnsEmpty() {
        val rows = listOf(summary("a", title = "Alpha"))
        assertTrue(filterArchivedSessions(rows, emptyMap(), "zzz").isEmpty())
    }

    // —— 主列表口径 ——————————————————————————————————————————————

    @Test
    fun archivedRowsAreHiddenFromTheMainList() {
        val sessions = listOf(
            summary("kept", title = "Kept"),
            summary("archived", title = "Archived"),
            summary("blank", blank = true),
            summary("subagent", origin = "subagent"),
        )
        assertEquals(
            "主列表滤掉归档、空白与 subagent 三种行",
            listOf("kept"),
            listSessionsVisible(sessions, setOf("archived")).map { it.sessionId },
        )
    }

    @Test
    fun forkedSessionsStayVisible() {
        // 普通 fork 出来的会话 origin 不是 subagent，照常列出。
        val sessions = listOf(summary("forked", title = "Fork"))

        assertEquals(
            listOf("forked"),
            listSessionsVisible(sessions, emptySet()).map { it.sessionId },
        )
    }

    @Test
    fun oneArchivedIdIsHiddenFromTheListAndShownOnTheArchivePage() {
        // 同一份输入喂两个口径：主列表里消失，归档页里出现——归档不是删除。
        val sessions = listOf(
            summary("kept", title = "Kept"),
            summary("archived", title = "Archived"),
        )
        val archived = ids("archived")

        assertEquals(
            listOf("kept"),
            listSessionsVisible(sessions, archived.toSet()).map { it.sessionId },
        )
        assertEquals(
            listOf("archived"),
            archivedSessionRows(archived, sessions.associateBy { it.sessionId }).map { it.sessionId },
        )
    }
}

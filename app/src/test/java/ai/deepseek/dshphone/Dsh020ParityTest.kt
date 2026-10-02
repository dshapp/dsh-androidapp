package ai.deepseek.dshphone

import ai.deepseek.dshphone.i18n.AppLocale
import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.model.ChatNode
import ai.deepseek.dshphone.model.ModelCatalogModel
import ai.deepseek.dshphone.model.ModelProviderGroup
import ai.deepseek.dshphone.model.QuestionAnswer
import ai.deepseek.dshphone.model.QuestionReplyPair
import ai.deepseek.dshphone.model.SessionEvent
import ai.deepseek.dshphone.model.SessionProjectionFrame
import ai.deepseek.dshphone.model.SessionProjectionsBlock
import ai.deepseek.dshphone.model.SessionSummary
import ai.deepseek.dshphone.model.formatDuration
import ai.deepseek.dshphone.model.questionAnswerPayload
import ai.deepseek.dshphone.model.rankByName
import ai.deepseek.dshphone.model.sessionDisplayTitle
import ai.deepseek.dshphone.model.sessionStoredTitle
import ai.deepseek.dshphone.model.visibleModelGroups
import ai.deepseek.dshphone.state.SessionStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 对齐 dsh 0.2.0-rc.2 web 的行为（Mac `c710a29`）。 */
class Dsh020ParityTest {

    private var originalLocale = AppLocale.EN

    @Before fun pinEnglish() { originalLocale = Strings.locale; Strings.locale = AppLocale.EN }

    @After fun restoreLocale() { Strings.locale = originalLocale }

    private fun event(type: String, seq: Int, data: String): SessionEvent =
        SessionEvent(type, seq, seq.toDouble(), Json.parseToJsonElement(data))

    private fun summary(title: String? = null, cwd: String? = "/work/alpha") = SessionSummary(
        sessionId = "s1",
        updatedAt = 0.0,
        blank = false,
        cwd = cwd,
        projections = title?.let { SessionProjectionsBlock(values = mapOf("title" to JsonPrimitive(it))) },
    )

    @Test
    fun durationUsesUnitsWithoutPadding() {
        assertEquals("5s", formatDuration(5_900.0))
        assertEquals("2m 5s", formatDuration(125_000.0))
        assertEquals("1h 0m 3s", formatDuration(3_603_000.0))
        Strings.locale = AppLocale.ZH
        assertEquals("2分5秒", formatDuration(125_000.0))
        assertEquals("1小时0分3秒", formatDuration(3_603_000.0))
    }

    @Test
    fun untitledSessionNoLongerFallsBackToItsDirectory() {
        assertEquals("搜索只匹配会话自己的标题", "", sessionStoredTitle(summary(), liveTitle = null))
        assertEquals(Strings.sessionUntitled, sessionDisplayTitle(summary(), liveTitle = null))
        assertEquals("Live", sessionStoredTitle(summary("Stored"), liveTitle = "  Live "))
        assertEquals("Stored", sessionDisplayTitle(summary(" Stored "), liveTitle = ""))
    }

    @Test
    fun rankByNamePutsPrefixHitsFirstAndDropsNonSubsequences() {
        val names = listOf("gpt-flash", "flash-pro", "deepseek-v4", "falsh")
        assertEquals(listOf("flash-pro", "gpt-flash"), rankByName(names, "flash") { it })
        assertEquals(names, rankByName(names, "") { it })
        assertEquals(listOf("deepseek-v4"), rankByName(names, "dsv") { it })
    }

    @Test
    fun providersOrderDeepSeekFirstAndHideEmptyGroups() {
        fun group(id: String, vararg models: String) =
            ModelProviderGroup(id, id, models.map { ModelCatalogModel(it, it) })
        val groups = listOf(
            group("mock", "alpha", "beta"),
            group("deepseek-official", "chat"),
            group("empty"),
            group("deepseek-account", "flash"),
        )
        assertEquals(
            listOf("deepseek-account", "deepseek-official", "mock"),
            visibleModelGroups(groups, "").map { it.id },
        )
        assertEquals(listOf("mock"), visibleModelGroups(groups, "be").map { it.id })
    }

    @Test
    fun lateQuestionReplyRendersItsPairsEvenWhenNotWaking() {
        val store = SessionStore("s1")
        val reply = """{"kind":"answer_to_pending_question","tool":"ask_user_question","callId":"c1",""" +
            """"questions":[{"id":"q1","question":"Which DB?"},{"id":"q2","question":"Name?"}],""" +
            """"answers":[{"id":"q1","selected":["Postgres"],"custom":"v16"},{"id":"q2","selected":[]}]}"""
        store.apply(event("turn/start", 1, """{"turn":1}"""), null)
        store.apply(
            event(
                "user/message", 2,
                """{"id":"m1","content":[{"type":"text","text":${JsonPrimitive(reply)}}],""" +
                    """"source":{"kind":"user-question-reply","callId":"c1","outcome":"answered"}}""",
            ),
            null,
        )
        val trigger = store.nodes.filterIsInstance<ChatNode.TurnTrigger>().single()
        assertEquals(
            listOf(QuestionReplyPair("Which DB?", listOf("Postgres", "v16")), QuestionReplyPair("Name?", emptyList())),
            trigger.data.questionReply,
        )
    }

    @Test
    fun continuedQuestionsComeFromTheProjectionMinusRepliedAndHidden() {
        val store = SessionStore("s1")
        store.applyProjection(
            SessionProjectionFrame(
                "s1", "userQuestions",
                Json.parseToJsonElement(
                    """{"active":[""" +
                        """{"callId":"open","questions":[{"id":"q","question":"Live?"}],"state":"open"},""" +
                        """{"callId":"c1","questions":[{"id":"q","question":"First?"}],"state":"continued"},""" +
                        """{"callId":"c2","questions":[{"id":"q","question":"Second?"}],"state":"continued"}""" +
                        """],"settled":[]}""",
                ),
                5,
            ),
        )
        assertEquals(listOf("c1", "c2"), store.continuedQuestions.map { it.callId })
        assertEquals("c1", store.presentedQuestion?.callId)
        store.hideQuestion("c1")
        assertEquals("收起的问题让位给下一个", "c2", store.presentedQuestion?.callId)
        store.markQuestionReplied("c2")
        assertNull(store.presentedQuestion)
        assertEquals("被收起的仍可作答（输入框上方的入口）", listOf("c1"), store.continuedQuestions.map { it.callId })
    }

    @Test
    fun answerPayloadMatchesTheWireShape() {
        assertEquals(
            """{"answers":[{"id":"q1","selected":["A"]},{"id":"q2","selected":[],"custom":"x"}]}""",
            questionAnswerPayload(listOf(QuestionAnswer("q1", listOf("A")), QuestionAnswer("q2", emptyList(), "x"))).toString(),
        )
    }
}

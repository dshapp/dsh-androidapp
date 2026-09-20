package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.ContextForm
import ai.deepseek.dshphone.model.contextCollapsedSummary
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 上下文注入行的摘要与形态（对齐 Mac `ContextPresentation` / iOS `PhoneContextRow`）。
 *
 * 主机在 `source.form = "notice"` 上带一句 `summary`（子智能体结束、切换模型都会发），
 * 那正是收起状态下要读的东西。Android 以前完全不看 form/summary，于是行上只剩原始 wire
 * kind（"subagent-settled" / "model-selection"），一句人能读的话被整条丢掉。
 */
class ContextCollapsedSummaryTest {

    private fun source(vararg pairs: Pair<String, JsonElement>) =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private fun notice(summary: String) = source(
        "kind" to JsonPrimitive("subagent-settled"),
        "form" to JsonPrimitive("notice"),
        "summary" to JsonPrimitive(summary),
    )

    @Test
    fun noticeSummaryIsTheCollapsedRow() {
        assertEquals(
            "Background subagent finished.",
            contextCollapsedSummary(notice("Background subagent finished.")),
        )
    }

    @Test
    fun nonNoticeFormsHaveNoCollapsedSummary() {
        val relay = source(
            "kind" to JsonPrimitive("agent-message"),
            "form" to JsonPrimitive("relay"),
            "senderSessionId" to JsonPrimitive("abc"),
        )
        assertNull(contextCollapsedSummary(relay))
    }

    @Test
    fun aNoticeWithoutSummaryIsNotANotice() {
        val empty = notice("")
        assertNull(contextCollapsedSummary(empty))
        assertNull(ContextForm.from(empty))
    }

    @Test
    fun declaredFormsAreValidatedAgainstTheirFields() {
        assertEquals(ContextForm.NOTICE, ContextForm.from(notice("done")))
        val instructions = source(
            "form" to JsonPrimitive("instructions"),
            "changes" to buildJsonArray {
                add(buildJsonObject {
                    put("path", "AGENTS.md")
                    put("action", "set")
                })
            },
        )
        assertEquals(ContextForm.INSTRUCTIONS, ContextForm.from(instructions))
        // 声明了 form 但字段读不出来 -> opaque，不画成那个形态。
        assertNull(ContextForm.from(source("form" to JsonPrimitive("instructions"))))
        assertNull(ContextForm.from(source("form" to JsonPrimitive("catalog"))))
        assertNull(ContextForm.from(source("form" to JsonPrimitive("relay"))))
        assertNull(ContextForm.from(JsonNull))
        assertNull(ContextForm.from(null))
    }
}

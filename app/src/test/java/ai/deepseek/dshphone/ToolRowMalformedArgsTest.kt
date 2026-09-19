package ai.deepseek.dshphone

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import ai.deepseek.dshphone.model.ToolChatData
import ai.deepseek.dshphone.model.ToolResultData
import ai.deepseek.dshphone.model.askSummary
import ai.deepseek.dshphone.model.todoSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * 工具行摘要对畸形参数必须回退，而不是崩溃。
 *
 * `args["todos"] as? List<Map<String, Any?>>` 因为泛型擦除只校验外层是 List：元素是
 * String 时这条转换照样"成功"，紧接着的 `it["status"]` 才会在运行期插入
 * checkcast java/util/Map 并抛 ClassCastException。这个函数在 Compose 组合期执行
 * （Cards.kt 的 remember { toolRowModel(...) }），于是"打开含该行的会话"就是一次崩溃。
 * askSummary 的 answers 是同一种写法。
 *
 * Mac ToolRowModel.swift 用元素级的 `as? [[String: Any]]`，元素类型不对就整体返回 nil，
 * 这里跟着回退到通用摘要（null），绝不抛。
 */
class ToolRowMalformedArgsTest {

    @Test
    fun todoSummaryRejectsNonObjectElements() {
        for (raw in listOf(
            "{\"todos\":[\"a\"]}",
            "{\"todos\":[null]}",
            "{\"todos\":[1,2]}",
            "{\"todos\":[[\"nested\"]]}",
        )) {
            val result = try {
                todoSummary(raw)
            } catch (e: Throwable) {
                fail("畸形 todos 不该抛异常（raw=" + raw + "）：" + e)
                return
            }
            assertNull("元素不是对象时应当回退（raw=" + raw + "）", result)
        }
    }

    @Test
    fun todoSummaryStillReadsWellFormedArgs() {
        val result = todoSummary("{\"todos\":[{\"status\":\"completed\",\"content\":\"a\"},{\"status\":\"in_progress\",\"content\":\"b\"}]}")
        assertEquals(fmt(Strings.todoCompleted, 1, 2) + " · b", result?.first)
    }

    @Test
    fun askSummaryRejectsNonObjectElements() {
        val data = ToolChatData(
            callId = "c1",
            name = "ask_user_question",
            argsRaw = "",
            turn = 1,
            step = 1,
            startSeq = 1,
            time = 0.0,
            result = ToolResultData(
                seq = 2,
                time = 1.0,
                content = listOf(ai.deepseek.dshphone.model.ContentBlock.Text("{\"answers\":[\"a\"]}")),
                isError = false,
            ),
        )
        val result = try {
            askSummary(data)
        } catch (e: Throwable) {
            fail("畸形 answers 不该抛异常：" + e)
            return
        }
        assertNull("answers 元素不是对象时应当回退", result)
    }
}

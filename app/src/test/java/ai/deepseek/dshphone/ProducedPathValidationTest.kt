package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.TurnDeliverables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 只有当一次改动调用"完整、可执行"时才认它产出了文件。
 *
 * Web 的 validEditArgs / editorMutationPath（ui-deliverables）与 Mac 的
 * producedPath/editorMutationPath 逐字段校验参数类型；校验不过就整条不算产出。
 * 手机端原来漏了两处：edit 的 replace_all 类型、str_replace 的 new_str 类型，
 * 另外 insert 的 insert_line 用 Number.toInt() 会把 1.5 悄悄截成 1（web 要求整数，
 * Mac 的 as? Int 同样拒掉小数）。漏校验的后果是畸形调用被算进本轮产出文件，
 * 让"本轮改动"里多出一个根本没写成的路径。
 */
class ProducedPathValidationTest {

    private fun produced(name: String, args: String): String? = TurnDeliverables.producedPath(name, args)

    @Test
    fun editRejectsNonBooleanReplaceAll() {
        val base = "{\"file_path\":\"a.ts\",\"old_string\":\"x\",\"new_string\":\"y\"}"
        // 合法：缺省或布尔都行。
        assertEquals("a.ts", produced("edit", base))
        assertEquals("a.ts", produced("edit", base.dropLast(1) + ",\"replace_all\":true}"))
        assertEquals("a.ts", produced("edit", base.dropLast(1) + ",\"replace_all\":false}"))
        // 非法：replace_all 是字符串 —— web/Mac 都判整条无效。
        assertNull(produced("edit", base.dropLast(1) + ",\"replace_all\":\"true\"}"))
    }

    @Test
    fun editorStrReplaceRejectsNonStringNewStr() {
        val head = "{\"path\":\"a.ts\",\"command\":\"str_replace\",\"old_str\":\"x\""
        // 合法：缺省或字符串都行。
        assertEquals("a.ts", produced("str_replace_editor", head + "}"))
        assertEquals("a.ts", produced("str_replace_editor", head + ",\"new_str\":\"y\"}"))
        // 非法：new_str 是数字 —— web/Mac 都判整条无效。
        assertNull(produced("str_replace_editor", head + ",\"new_str\":3}"))
    }

    @Test
    fun editorInsertRejectsNonIntegerLine() {
        val head = "{\"path\":\"a.ts\",\"command\":\"insert\",\"new_str\":\"y\""
        assertEquals("a.ts", produced("str_replace_editor", head + ",\"insert_line\":0}"))
        assertEquals("a.ts", produced("str_replace_editor", head + ",\"insert_line\":7}"))
        // 非法：小数行号。web 要 Number.isInteger，Mac 的 as? Int 也拒。
        assertNull(produced("str_replace_editor", head + ",\"insert_line\":1.5}"))
        // 非法：负数。
        assertNull(produced("str_replace_editor", head + ",\"insert_line\":-1}"))
    }
}

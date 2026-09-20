package ai.deepseek.dshphone

import ai.deepseek.dshphone.i18n.AppLocale
import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.model.WorkflowRunStatus
import ai.deepseek.dshphone.model.workflowStatusLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工作流卡片状态词的取词（对齐 Mac `localizedStatusWord` / web `workflowRun` 字典）。
 *
 * 卡片以前直接把枚举名小写印上去：中文界面上那张卡片的尾标是 "running"、"completed"，
 * 既不是中文，也不是 web/Mac 里那个首字母大写的状态词。web 的
 * `workflowRun` 字典与 Mac 的 `localizedStatusWord` 都把它翻成 "运行中"/"Running"。
 */
class WorkflowStatusLabelTest {

    private fun withLocale(locale: AppLocale, block: () -> Unit) {
        val previous = Strings.locale
        Strings.locale = locale
        try { block() } finally { Strings.locale = previous }
    }

    @Test
    fun statusWordsFollowTheInterfaceLanguage() {
        withLocale(AppLocale.ZH) {
            assertEquals("运行中", workflowStatusLabel(WorkflowRunStatus.RUNNING))
            assertEquals("已完成", workflowStatusLabel(WorkflowRunStatus.COMPLETED))
            assertEquals("失败", workflowStatusLabel(WorkflowRunStatus.FAILED))
            assertEquals("已取消", workflowStatusLabel(WorkflowRunStatus.CANCELLED))
            assertEquals("已中断", workflowStatusLabel(WorkflowRunStatus.INTERRUPTED))
        }
        withLocale(AppLocale.EN) {
            assertEquals("Running", workflowStatusLabel(WorkflowRunStatus.RUNNING))
            assertEquals("Completed", workflowStatusLabel(WorkflowRunStatus.COMPLETED))
            assertEquals("Interrupted", workflowStatusLabel(WorkflowRunStatus.INTERRUPTED))
        }
    }
}

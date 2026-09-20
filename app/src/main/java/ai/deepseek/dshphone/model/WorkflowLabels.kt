package ai.deepseek.dshphone.model

import ai.deepseek.dshphone.i18n.Strings

/**
 * 工作流那张卡片上的状态词（对齐 Mac `localizedStatusWord` / web `workflowRun` 的
 * `status.*` 字典）。
 *
 * 卡片以前直接把枚举名小写印上去：中文界面上尾标是 "running"、"completed"，既不是中文，
 * 也不是 web/Mac 里那个首字大写的状态词。
 */
fun workflowStatusLabel(status: WorkflowRunStatus): String = when (status) {
    WorkflowRunStatus.RUNNING -> Strings.workflowStatusRunning
    WorkflowRunStatus.COMPLETED -> Strings.workflowStatusCompleted
    WorkflowRunStatus.FAILED -> Strings.workflowStatusFailed
    WorkflowRunStatus.CANCELLED -> Strings.workflowStatusCancelled
    WorkflowRunStatus.INTERRUPTED -> Strings.workflowStatusInterrupted
}

package ai.deepseek.dshphone.i18n

import ai.deepseek.dshphone.net.arr
import ai.deepseek.dshphone.net.get
import ai.deepseek.dshphone.net.str
import ai.deepseek.dshphone.net.text as jsonText
import kotlinx.serialization.json.JsonObject

/** Uses the existing observable locale; deliberately no edits to shared Strings. */
object ManagementStrings {
    fun text(en: String, zh: String) = if (Strings.locale == AppLocale.EN) en else zh
    val automations get() = text("Automations", "自动化任务")
    val back get() = text("Back", "返回")
    val newTask get() = text("New automation", "新建自动化任务")
    val search get() = text("Search automations", "搜索自动化任务")
    val noMatches get() = text("No matching automations", "没有匹配的任务")
    val moreActions get() = text("More actions", "更多操作")
    val rule get() = text("Schedule rule", "时间规则")
    val changeTiming get() = text("Change timing", "修改时间规则")
    val changeTimingHint get() = text("Otherwise the exact target time is retained", "关闭时保留原定的精确时间")
    val deleteTitle get() = text("Delete automation?", "删除自动化任务？")
    fun deleteMessage(title: String) = text("\"$title\" will be deleted. Messages already delivered are not recalled.", "「$title」将被删除，已投递的消息不会撤回。")
    val discardTitle get() = text("Discard changes?", "放弃修改？")
    val discard get() = text("Discard", "放弃")
    val keepEditing get() = text("Keep editing", "继续编辑")
    fun next(time: String) = text("Next $time", "下次 $time")
    fun delivered(time: String) = text("Delivered $time", "投递于 $time")
    fun scheduled(time: String) = text("Scheduled $time", "计划 $time")
    fun kindLabel(kind: String?) = when (kind) {
        "at" -> text("Once", "单次")
        "after" -> text("Delay", "延时")
        "every" -> text("Interval", "固定间隔")
        "daily" -> text("Daily", "每天")
        "weekly" -> text("Weekly", "每周")
        "cron" -> "Cron"
        else -> kind.orEmpty()
    }
    fun statusLabel(status: String?) = when (status) {
        "active" -> text("Active", "运行中")
        null -> ""
        else -> text("Ended", "已结束") + " ($status)"
    }
    // 定时任务消息的详情弹层（Mac 自动化任务「规则」页的字段）。
    val taskName get() = text("Automation task name", "自动化任务名称")
    val occurrence get() = text("Scheduled time", "计划时间")
    val runTime get() = text("Run time", "运行时间")
    val instruction get() = text("Automation task instruction", "自动化任务内容")
    val taskId get() = text("Task ID", "任务 ID")

    /** 规则的频率文本（Mac `ScheduleFormat.frequency` 的简版）：时区与手机相同时不写时区。 */
    fun frequency(record: JsonObject): String {
        val zone = record["timeZone"].str
        val suffix = if (zone == null || zone == java.time.ZoneId.systemDefault().id) "" else text(" ($zone)", "（$zone）")
        val time = record["time"].str.orEmpty().substringBefore('.').let { if (it.length == 8 && it.endsWith(":00")) it.dropLast(3) else it }
        return when (record["kind"].str) {
            "every" -> {
                val seconds = record["everySeconds"].jsonText?.toLongOrNull() ?: 0L
                val (size, en, zh) = listOf(Triple(86400L, "day", "天"), Triple(3600L, "hour", "小时"), Triple(60L, "minute", "分钟"))
                    .firstOrNull { seconds > 0 && seconds % it.first == 0L } ?: Triple(1L, "second", "秒")
                val value = seconds / size
                text("Every $value " + (if (value == 1L) en else en + "s"), "每 $value $zh")
            }
            "daily" -> text("Daily at $time", "每天 $time") + suffix
            "weekly" -> {
                val days = record["weekdays"].arr.orEmpty().mapNotNull { it.jsonText?.toIntOrNull()?.takeIf { d -> d in 1..7 } }
                val names = days.joinToString(text(", ", "、")) {
                    text(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[it - 1], listOf("一", "二", "三", "四", "五", "六", "日")[it - 1])
                }
                text("Weekly on $names at $time", "每周$names $time") + suffix
            }
            "cron" -> "Cron " + record["expression"].str.orEmpty() + suffix
            else -> text("Once", "仅一次")
        }
    }

    /** 新建自动化任务时预填进新会话输入框的开头（Mac `automationsNewDraft`）。 */
    val newTaskDraft get() = text("Help me create a scheduled task that runs every day at 9 AM:", "帮我创建一个每天早上9点执行的定时任务:")
    val cancel get() = text("Cancel", "取消")
    val confirm get() = text("Confirm", "确认")
    val delete get() = text("Delete", "删除")
    val edit get() = text("Edit rule", "编辑规则")
    val save get() = text("Save", "保存")
    val history get() = text("Delivery history", "投递记录")
    val more get() = text("Older deliveries", "更早投递")
    val empty get() = text("No entries", "暂无条目")
    val title get() = text("Title (up to 120 characters)", "名称（最多 120 字符）")
    val prompt get() = text("Reminder instruction", "提醒内容")
    val deliveryHint get() = text("These records acknowledge durable inbox delivery, not model execution or business success. Legacy records may have no saved prompt.", "记录仅确认持久化收件箱投递，不代表模型执行完成或业务成功。旧记录可能没有保存当次内容。")
    val scopeHint get() = text("Existing rules only. Create a reminder in its session with the agent; there is no schedule/create RPC.", "仅管理已有规则。请在所属会话中让智能体创建提醒；宿主没有 schedule/create RPC。")
    fun outcome(raw: String?) = when (raw) {
        "schedule_conflict" -> text("Rule changed elsewhere. Draft and original expected retained. Cancel and reopen to adopt latest; no automatic retry.", "规则已被其他端修改。保留草稿与原 expected。取消后重新打开可采用最新规则；不会自动重试。")
        "schedule_ended" -> text("Rule ended; draft retained", "规则已结束；草稿保留")
        "saved" -> text("Saved", "已保存")
        "deleted" -> text("Deleted", "已删除")
        else -> raw.orEmpty()
    }
}

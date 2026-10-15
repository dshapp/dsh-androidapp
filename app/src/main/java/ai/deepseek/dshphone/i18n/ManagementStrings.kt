package ai.deepseek.dshphone.i18n

/** Uses the existing observable locale; deliberately no edits to shared Strings. */
object ManagementStrings {
    fun text(en: String, zh: String) = if (Strings.locale == AppLocale.EN) en else zh
    val plugins get() = text("Plugins", "插件")
    val automations get() = text("Automations", "自动化")
    val back get() = text("Back", "返回")
    val refresh get() = text("Refresh", "刷新")
    val newTask get() = text("New", "新建")
    /** 新建自动化任务时预填进新会话输入框的开头（Mac `automationsNewDraft`）。 */
    val newTaskDraft get() = text("Help me create a scheduled task that runs every day at 9 AM:", "帮我创建一个每天早上9点执行的定时任务:")
    val official get() = text("Official", "官方")
    val installed get() = text("Installed", "已安装")
    val details get() = text("Details", "详情")
    val install get() = text("Install / update", "安装 / 更新")
    val cancel get() = text("Cancel", "取消")
    val confirm get() = text("Confirm", "确认")
    val remove get() = text("Uninstall", "卸载")
    val delete get() = text("Delete", "删除")
    val edit get() = text("Edit rule", "编辑规则")
    val save get() = text("Save", "保存")
    val history get() = text("Delivery history", "投递记录")
    val more get() = text("Older deliveries", "更早投递")
    val empty get() = text("No entries", "暂无条目")
    val enabled get() = text("Enabled", "启用")
    val title get() = text("Title (up to 120 characters)", "名称（最多 120 字符）")
    val prompt get() = text("Reminder instruction", "提醒内容")
    val deliveryHint get() = text("These records acknowledge durable inbox delivery, not model execution or business success. Legacy records may have no saved prompt.", "记录仅确认持久化收件箱投递，不代表模型执行完成或业务成功。旧记录可能没有保存当次内容。")
    val scopeHint get() = text("Existing rules only. Create a reminder in its session with the agent; there is no schedule/create RPC.", "仅管理已有规则。请在所属会话中让智能体创建提醒；宿主没有 schedule/create RPC。")
    val pluginBoundary get() = text("Native package/component controls only. Companion pages and arbitrary plugin settings are not executed here. Changes may require a host restart; no automatic restart.", "原生界面仅管理包和组件；不执行 companion 页面或任意插件配置。变更可能需要宿主重启，不会自动重启。")
    val unknownInstall get() = text("No active result retained. This does not prove success or cancellation. Check the refreshed inventory before dismissing; dismissal never reinstalls.", "宿主未保留活跃结果。这不证明成功或取消。请核对刷新后的库存再关闭；关闭不会重复安装。")
    val risk get() = text("I accept possible crashes and data loss for this exact package/runtime version pair.", "我接受此精确插件/运行时版本组合可能导致崩溃或数据丢失。")
    fun outcome(raw: String?) = when (raw) {
        "applied" -> text("Applied", "已应用")
        "restart-required" -> text("Saved; host restart required (not automatic)", "已保存；需要宿主重启（不会自动重启）")
        "overridden" -> text("Saved but overridden by another layer", "已保存，但被其他配置层覆盖")
        "failed" -> text("Failed; inspect diagnostics", "失败；请查看诊断")
        "cancelled" -> text("Cancelled; host restored files", "已取消；宿主已恢复文件")
        "schedule_conflict" -> text("Rule changed elsewhere. Draft and original expected retained. Cancel and reopen to adopt latest; no automatic retry.", "规则已被其他端修改。保留草稿与原 expected。取消后重新打开可采用最新规则；不会自动重试。")
        "schedule_ended" -> text("Rule ended; draft retained", "规则已结束；草稿保留")
        "saved" -> text("Saved", "已保存")
        "deleted" -> text("Deleted", "已删除")
        else -> raw.orEmpty()
    }
}

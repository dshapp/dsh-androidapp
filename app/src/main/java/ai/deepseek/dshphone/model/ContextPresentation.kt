package ai.deepseek.dshphone.model

import ai.deepseek.dshphone.net.arr
import ai.deepseek.dshphone.net.bool
import ai.deepseek.dshphone.net.get
import ai.deepseek.dshphone.net.int
import ai.deepseek.dshphone.net.str
import kotlinx.serialization.json.JsonElement

/**
 * 一条上下文注入消息按其 `source.form` 实际能渲染成的形态（对齐 Mac
 * `ContextPresentation.renderedForm` / web `contextBody` 的 `rendered`）。
 *
 * 声明的 form 只有在它需要的字段可读时才成立；否则退回 opaque（null），
 * 调用方就按「没有 form」画默认图标。
 */
enum class ContextForm(val wire: String) {
    INSTRUCTIONS("instructions"),
    CATALOG("catalog"),
    SNAPSHOT("snapshot"),
    NOTICE("notice"),
    RELAY("relay"),
    RECALL("recall");

    companion object {
        fun from(source: JsonElement?): ContextForm? {
            val declared = source["form"].str ?: return null
            val form = entries.firstOrNull { it.wire == declared } ?: return null
            return if (form.fieldsReadable(source)) form else null
        }
    }

    private fun fieldsReadable(source: JsonElement?): Boolean = when (this) {
        INSTRUCTIONS -> instructionChangesReadable(source)
        CATALOG -> catalogEntriesReadable(source)
        SNAPSHOT -> snapshotSectionsReadable(source)
        NOTICE -> source["summary"].str?.isNotEmpty() == true
        RELAY -> source["senderSessionId"].str?.isNotEmpty() == true
        RECALL -> recalledSessionsReadable(source)
    }
}

/**
 * 折叠时那一行摘要：只有 `notice` 自带一句人能读的话（对齐 Mac
 * `ContextPresentation.collapsedSummary(source:)`）。
 *
 * 其余 form 没有这一行，返回 null，调用方落回 `provenanceLabel`。
 */
fun contextCollapsedSummary(source: JsonElement?): String? {
    if (ContextForm.from(source) != ContextForm.NOTICE) return null
    return source["summary"].str?.takeIf { it.isNotEmpty() }
}

private fun instructionChangesReadable(source: JsonElement?): Boolean {
    val list = source["changes"].arr ?: return false
    val paths = mutableSetOf<String>()
    for (item in list) {
        val path = item["path"].str?.takeIf { it.isNotEmpty() } ?: return false
        if (item["action"].str !in setOf("set", "replace", "remove")) return false
        paths.add(path)
    }
    return paths.isNotEmpty()
}

private fun catalogEntriesReadable(source: JsonElement?): Boolean {
    val list = source["entries"].arr ?: return false
    for (item in list) {
        if (item["name"].str?.isNotEmpty() != true) return false
        // description 必须存在且是字符串（可以为空），与 Mac 的 stringValue 口径一致。
        if (item["description"].str == null) return false
    }
    return list.isNotEmpty()
}

private fun snapshotSectionsReadable(source: JsonElement?): Boolean {
    val list = source["sections"].arr ?: return false
    for (item in list) {
        if (item["name"].str?.isNotEmpty() != true) return false
        if (item["text"].str == null) return false
    }
    return list.isNotEmpty()
}

private fun recalledSessionsReadable(source: JsonElement?): Boolean {
    val list = source["references"].arr ?: return false
    for (item in list) {
        if (item["label"].str?.isNotEmpty() != true) return false
        if (item["retainedMessages"].int == null) return false
        if (item["omittedMessages"].int == null) return false
        if (item["truncated"].bool == null) return false
    }
    return list.isNotEmpty()
}

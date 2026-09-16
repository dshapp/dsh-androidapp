package ai.deepseek.dshphone.state

import ai.deepseek.dshphone.model.HistoryEntry
import ai.deepseek.dshphone.model.SessionHistoryValue

/**
 * 去重、按 seq 排序的原始历史窗口；分页与实时投递共用。
 */
class SessionEventWindow {
    var entries: List<HistoryEntry> = emptyList()
        private set
    var hasMore: Boolean = true
        private set
    var revision: Int = 0
        private set

    val firstSeq: Int? get() = entries.firstOrNull()?.event?.seq
    val lastSeq: Int? get() = entries.lastOrNull()?.event?.seq

    fun replace(value: SessionHistoryValue) {
        val replacement = deduplicated(value.events)
        hasMore = value.hasMore
        if (replacement == entries) return
        entries = replacement
        revision++
    }

    fun prepend(value: SessionHistoryValue) {
        val merged = mergeOrdered(deduplicated(value.events), entries)
        hasMore = value.hasMore
        if (merged == entries) return
        entries = merged
        revision++
    }

    fun repair(value: SessionHistoryValue, buffered: List<HistoryEntry>) {
        val repaired = mergeOrdered(deduplicated(value.events), deduplicated(buffered))
        hasMore = value.hasMore
        if (repaired == entries) return
        entries = repaired
        revision++
    }

    fun truncate(afterSeq: Int): Boolean {
        val kept = entries.filter { it.event.seq <= afterSeq }
        if (kept.size == entries.size) return false
        entries = kept
        revision++
        return true
    }

    /** 插入或替换一条实时事件；返回窗口是否真的变了。 */
    fun applyLive(entry: HistoryEntry): Boolean {
        val index = entries.binarySearchBy(entry.event.seq) { it.event.seq }
        if (index >= 0) {
            if (entries[index] == entry) return false
            val updated = entries.toMutableList()
            updated[index] = preferred(entries[index], entry)
            entries = updated
            revision++
            return true
        }
        val insertAt = -index - 1
        val updated = entries.toMutableList()
        updated.add(insertAt, entry)
        entries = updated
        revision++
        return true
    }

    private fun deduplicated(items: List<HistoryEntry>): List<HistoryEntry> {
        val bySequence = LinkedHashMap<Int, HistoryEntry>()
        for (entry in items) {
            val seq = entry.event.seq
            val existing = bySequence[seq]
            bySequence[seq] = if (existing == null) entry else preferred(existing, entry)
        }
        return bySequence.values.sortedBy { it.event.seq }
    }

    private fun mergeOrdered(left: List<HistoryEntry>, right: List<HistoryEntry>): List<HistoryEntry> {
        val merged = ArrayList<HistoryEntry>(left.size + right.size)
        var i = 0
        var j = 0
        while (i < left.size && j < right.size) {
            val a = left[i]
            val b = right[j]
            when {
                a.event.seq < b.event.seq -> { merged.add(a); i++ }
                b.event.seq < a.event.seq -> { merged.add(b); j++ }
                else -> { merged.add(preferred(a, b)); i++; j++ }
            }
        }
        while (i < left.size) merged.add(left[i++])
        while (j < right.size) merged.add(right[j++])
        return merged
    }

    private fun preferred(left: HistoryEntry, right: HistoryEntry): HistoryEntry {
        if (left.view == null && right.view != null) return right
        if (right.view == null && left.view != null) return left
        return right
    }
}

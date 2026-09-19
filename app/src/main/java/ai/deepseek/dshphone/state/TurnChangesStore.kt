package ai.deepseek.dshphone.state

import ai.deepseek.dshphone.model.ChangedFileDiff
import ai.deepseek.dshphone.model.TurnChangesAddress
import ai.deepseek.dshphone.model.TurnChangesSummary
import ai.deepseek.dshphone.net.ApiClient
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.net.changesDiff
import ai.deepseek.dshphone.net.changesSummary
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 主机为已公告的 `workspace/changes` 记录提供的改动汇总与逐文件比较。
 *
 * 汇总按公告事件的 seq 寻址而不是按轮次：同一轮后来的公告取代先前的记录，被取代的那条不再
 * 可寻址。这里全是「合法的空读取」——会话销毁后主机会对记录回 404——所以「没有」和「有值」
 * 一样缓存，免得每次重绘都去重试（对齐 Mac `TurnChangesStore`）。
 */
class TurnChangesStore(private val scope: CoroutineScope) {

    private data class DiffAddress(val summary: TurnChangesAddress, val index: Int)

    private val summaries = mutableStateMapOf<TurnChangesAddress, TurnChangesSummary>()
    private val diffs = mutableStateMapOf<DiffAddress, ChangedFileDiff>()
    /** 主机已明确拒绝提供的记录：与「还在读」区分开，前者不再重试，后者还要等。 */
    private val unavailable = mutableSetOf<TurnChangesAddress>()
    private val unavailableDiffs = mutableSetOf<DiffAddress>()
    private val loading = mutableSetOf<TurnChangesAddress>()
    private val loadingDiffs = mutableSetOf<DiffAddress>()

    /** 已经取到的汇总；还在读、或主机已不再提供，都是 null（两者都不画卡片）。 */
    fun summary(address: TurnChangesAddress): TurnChangesSummary? = summaries[address]

    /** 主机是否已经明确拒绝提供这条记录。 */
    fun isUnavailable(address: TurnChangesAddress): Boolean = address in unavailable

    /** 已经取到的某个文件的比较。 */
    fun diff(address: TurnChangesAddress, index: Int): ChangedFileDiff? = diffs[DiffAddress(address, index)]

    /** 会话归档或删除后丢掉它的全部记录。 */
    fun closeAll(sessionId: SessionId) {
        summaries.keys.filter { it.sessionId == sessionId }.forEach { summaries.remove(it) }
        diffs.keys.filter { it.summary.sessionId == sessionId }.forEach { diffs.remove(it) }
        unavailable.removeAll { it.sessionId == sessionId }
        unavailableDiffs.removeAll { it.summary.sessionId == sessionId }
        loading.removeAll { it.sessionId == sessionId }
        loadingDiffs.removeAll { it.summary.sessionId == sessionId }
    }

    /**
     * 读一次已公告的汇总（没读过才读）。
     *
     * 传输失败不算答案：记录保持未缓存，让之后的重绘还能重试，而不是把一个
     * 恰好重启的主机钉死成「没有」。
     */
    fun load(address: TurnChangesAddress, api: ApiClient) {
        if (summaries.containsKey(address) || address in unavailable || !loading.add(address)) return
        scope.launch {
            try {
                val fetched = runCatching { api.changesSummary(address.sessionId, address.seq) }.getOrNull()
                if (fetched != null) summaries[address] = fetched else unavailable.add(address)
            } finally {
                loading.remove(address)
            }
        }
    }

    /** 读一次某个列出的文件的比较（没读过才读）。 */
    fun loadDiff(address: TurnChangesAddress, index: Int, api: ApiClient) {
        val key = DiffAddress(address, index)
        if (diffs.containsKey(key) || key in unavailableDiffs || !loadingDiffs.add(key)) return
        scope.launch {
            try {
                val fetched = runCatching { api.changesDiff(address.sessionId, address.seq, index) }.getOrNull()
                if (fetched != null) diffs[key] = fetched else unavailableDiffs.add(key)
            } finally {
                loadingDiffs.remove(key)
            }
        }
    }
}

package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.HistoryEntry
import ai.deepseek.dshphone.model.SessionEvent
import ai.deepseek.dshphone.state.CachedWindow
import ai.deepseek.dshphone.state.SessionStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存窗口的 hasMore 必须跟着窗口一起铺到 store 上（对齐 Mac `seedFromCache`）。
 *
 * 冷启动点开一条会话时，先从本机缓存画一份底稿；那份窗口自己知道它是不是最后一页。
 * 少了这一步，一份「就是完整历史」的短会话在 follow 快照回来之前会被当成还有更早的页：
 * `rebuildTurnProcessSpecs` 因 hasMore 直接短路，过程行（思考/工具）不折叠，「加载更早」
 * 的条件也被点亮。Mac `seedFromCache` 在 replace 之后紧接着 `self.hasMore = eventWindow.hasMore`。
 */
class SeedFromCacheHasMoreTest {

    private fun entry(seq: Int) = HistoryEntry(
        SessionEvent(
            type = "user/message",
            seq = seq,
            data = buildJsonObject { put("id", "m$seq") },
        )
    )

    private fun seeded(hasMore: Boolean): SessionStore {
        val store = SessionStore("s1")
        store.seedFromCache(CachedWindow(hasMore = hasMore, records = listOf(entry(1))))
        return store
    }

    /** 缓存里就是完整历史（最后一页）时，底稿不能还亮着「还有更早」。 */
    @Test
    fun completeCachedWindowClearsHasMore() {
        assertFalse("a cached window with no older page must clear hasMore", seeded(false).hasMore)
    }

    /** 缓存只是一段尾部时，hasMore 照旧为真。 */
    @Test
    fun partialCachedWindowKeepsHasMore() {
        assertTrue("a partial cached tail is still hasMore", seeded(true).hasMore)
    }

    /** 铺底稿不等于加载过：权威 follow 快照仍然要整体替换它。 */
    @Test
    fun seedingStillDoesNotMarkHistoryLoaded() {
        assertFalse("the cache is only a preview", seeded(false).historyLoaded)
    }
}

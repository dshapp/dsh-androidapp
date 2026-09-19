package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.ChangedFileDiff
import ai.deepseek.dshphone.model.TurnChangesAddress
import ai.deepseek.dshphone.model.TurnChangesSummary
import ai.deepseek.dshphone.state.TurnChangesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传输失败不能被钉死成"主机不提供"。
 *
 * TurnChangesStore 的文档写得很清楚：「传输失败不算答案：记录保持未缓存，让之后的重绘还能
 * 重试，而不是把一个恰好重启的主机钉死成'没有'。」Mac 的 TurnChangesStore 也是这么做的
 * （throw 走 catch、不写缓存；只有 fetch 返回 nil=404 才记 unavailable）。
 *
 * 但实现把两者一起吞成了 unavailable：runCatching { ... }.getOrNull() 之后，
 * 抛出的网络异常与 404 的 null 得到同一个 null，于是主机重启一次、网络抖动一次，这一轮的
 * "改动文件"卡片在本进程内就永远不再重试，界面表现为"这轮没有任何改动"。
 */
class TurnChangesStoreRetryTest {

    private val address = TurnChangesAddress("session-a", 7)

    private fun summary(turn: Int) = TurnChangesSummary(turn = turn, files = emptyList(), total = 0, added = 0, deleted = 0)

    /** 跑掉 scope 里排队的协程。 */
    private fun drain(scope: CoroutineScope) = runBlocking {
        repeat(20) { delay(1) }
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.join() }
    }

    @Test
    fun transportFailureDoesNotLatchUnavailable() {
        val scope = CoroutineScope(Dispatchers.Default)
        val store = TurnChangesStore(scope)
        store.load(address) { throw java.io.IOException("connection reset") }
        drain(scope)
        assertNull("传输失败不该留下任何值", store.summary(address))
        assertFalse("传输失败不是'主机不提供'，不能钉死成不可用", store.isUnavailable(address))
    }

    @Test
    fun transportFailureThenRetrySucceeds() {
        val scope = CoroutineScope(Dispatchers.Default)
        val store = TurnChangesStore(scope)
        store.load(address) { throw java.io.IOException("boom") }
        drain(scope)
        // 主机回来了：重绘时还能再试，并且这次真的拿到汇总。
        store.load(address) { summary(3) }
        drain(scope)
        assertEquals(3, store.summary(address)?.turn)
    }

    @Test
    fun notFoundStillLatchesUnavailable() {
        val scope = CoroutineScope(Dispatchers.Default)
        val store = TurnChangesStore(scope)
        store.load(address) { null }
        drain(scope)
        assertTrue("404 是合法答案，应当记成不可用并不再重试", store.isUnavailable(address))
    }

    @Test
    fun diffTransportFailureDoesNotLatchEither() {
        val scope = CoroutineScope(Dispatchers.Default)
        val store = TurnChangesStore(scope)
        store.loadDiff(address, 0) { throw java.io.IOException("reset") }
        drain(scope)
        assertNull(store.diff(address, 0))
        // 重试能拿到值。
        store.loadDiff(address, 0) { ChangedFileDiff.Binary("a.ts", "a.ts") }
        drain(scope)
        assertEquals("a.ts", store.diff(address, 0)?.path)
    }
}

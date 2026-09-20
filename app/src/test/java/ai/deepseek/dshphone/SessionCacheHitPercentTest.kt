package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.SessionUsageReading
import ai.deepseek.dshphone.model.TokenUsage
import ai.deepseek.dshphone.model.sessionCacheHitPercent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 上下文面板那条「缓存命中」读数。
 *
 * Mac 的 `detailRows`（ComposerDock.swift）用的是会话级 `sessionCacheHitPercent`：
 * 先按整数百分比取，只有会读成 100 的部分命中才多要几位小数（99.9、99.99…），
 * 最多 6 位，仍不够就退到 "99.9"。安卓这边先前错用了单轮的 `cacheHitPercent`：
 * 它固定一位小数并把任何部分命中夹在 99.9，于是 99.94% 这种会话级读数会显示成
 * "99.9"，而 Mac 显示 "99.94" —— 同一个 tokenUsage 投影，两端数字不一致。
 */
class SessionCacheHitPercentTest {

    @Test
    fun aWholePercentRatioHasNoDecimals() {
        assertEquals("50", sessionCacheHitPercent(cacheRead = 50, billed = 100))
    }

    @Test
    fun aPartialHitKeepsEnoughDecimalsToStayBelowOneHundred() {
        // 9996/10000 = 99.96%：0 位和 1 位小数都取到 100，2 位才落回 99.96。
        // 单轮的 cacheHitPercent 只肯给 99.9，Mac 的会话级读数给 99.96。
        assertEquals("99.96", sessionCacheHitPercent(cacheRead = 9996, billed = 10000))
    }

    @Test
    fun anOrdinaryPartialHitStopsAtOneDecimal() {
        // 9994/10000 = 99.94%：一位小数 99.9 已经诚实低于 100，Mac 就停在那里。
        assertEquals("99.9", sessionCacheHitPercent(cacheRead = 9994, billed = 10000))
    }

    @Test
    fun aFullHitIsExactlyOneHundred() {
        assertEquals("100", sessionCacheHitPercent(cacheRead = 100, billed = 100))
        assertEquals("100", sessionCacheHitPercent(cacheRead = 120, billed = 100))
    }

    @Test
    fun nothingBilledHasNoRow() {
        assertNull(sessionCacheHitPercent(cacheRead = 0, billed = 0))
    }

    @Test
    fun aRatioThatNeverResolvesFallsBackToNinetyNinePointNine() {
        // 2/3 = 66.666…%，整数位取到 67，不会读成 100，所以停在整数位。
        assertEquals("67", sessionCacheHitPercent(cacheRead = 2, billed = 3))
    }

    /**
     * `billed` 是三个桶的和，主机给的都是 Int；三个大桶直接相加会溢出成负数，
     * 于是 `billed > 0` 为假、整组 Token 行都不画。Mac 用 saturatingAdding。
     */
    @Test
    fun billedInputSaturatesInsteadOfWrappingNegative() {
        val usage = TokenUsage(
            uncachedInputTokens = Int.MAX_VALUE,
            cacheReadTokens = Int.MAX_VALUE,
            cacheWriteTokens = Int.MAX_VALUE,
            outputTokens = 0,
        )
        val reading = SessionUsageReading(usage)!!
        assertEquals("三个大桶相加必须夹在 Int.MAX_VALUE，而不是回绕成负数", Int.MAX_VALUE, reading.billed)
        assertEquals(Int.MAX_VALUE, reading.total)
    }

    @Test
    fun aSaturatedBilledInputStillShowsRows() {
        val usage = TokenUsage(uncachedInputTokens = Int.MAX_VALUE, cacheReadTokens = Int.MAX_VALUE)
        val reading = SessionUsageReading(usage)!!
        assertEquals("溢出后 hasTokens 仍要为真，Token 行不能整组消失", true, reading.hasTokens)
    }
}
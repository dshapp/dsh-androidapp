package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.TokenUsage
import ai.deepseek.dshphone.model.cacheHitPercent
import ai.deepseek.dshphone.model.turnTotalTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 单轮 Token 总量的相加必须是饱和的（Mac `turnTotalTokens` 用 saturatingAdding）。
 *
 * 四个桶都直接来自 wire，任一桶都可能顶着 Int.MAX_VALUE；直接相加会回绕成负数，
 * 于是 `turnTotalTokens(usage) > 0` 为假，token 药丸与明细卡双双消失，用户看到的
 * 是「这一轮没有用量」。Mac 的 AdditiveArithmetic 明文写了这条理由（WireNumbers.swift）。
 */
class TurnTotalTokensTest {

    @Test
    fun aSingleBucketKeepsItsValue() {
        assertEquals(1234, turnTotalTokens(TokenUsage(uncachedInputTokens = 1234)))
    }

    @Test
    fun ordinaryBucketsAdd() {
        assertEquals(
            300,
            turnTotalTokens(
                TokenUsage(
                    uncachedInputTokens = 100,
                    cacheReadTokens = 100,
                    cacheWriteTokens = 50,
                    outputTokens = 50,
                ),
            ),
        )
    }

    @Test
    fun overflowingBucketsSaturateInsteadOfWrappingNegative() {
        val total = turnTotalTokens(
            TokenUsage(
                uncachedInputTokens = Int.MAX_VALUE,
                cacheReadTokens = Int.MAX_VALUE,
                cacheWriteTokens = Int.MAX_VALUE,
                outputTokens = Int.MAX_VALUE,
            ),
        )
        assertEquals("四个满桶相加要顶在 Int.MAX_VALUE，而不是回绕成负数", Int.MAX_VALUE, total)
    }

    @Test
    fun anOverflowingTotalStillKeepsThePillVisible() {
        val usage = TokenUsage(uncachedInputTokens = Int.MAX_VALUE, outputTokens = Int.MAX_VALUE)
        assertEquals("溢出后 > 0 仍要为真，否则药丸和明细卡都消失", true, turnTotalTokens(usage) > 0)
    }

    @Test
    fun aNegativeOverflowReadsAreNotMaskedIntoThePositiveRange() {
        // 极端的负桶（主机不可能写出，但 wire 上没上界）相加后仍要是负数，
        // 这样上层能靠 `> 0` 判断「没有可信读数」。
        val total = turnTotalTokens(
            TokenUsage(
                uncachedInputTokens = Int.MIN_VALUE,
                cacheReadTokens = Int.MIN_VALUE,
            ),
        )
        assertEquals(Int.MIN_VALUE, total)
    }

    @Test
    fun cacheHitPercentIgnoresAnOutputOnlyUsage() {
        // 没有 cacheRead 就没有命中率行；只有 output 时 prompt 为 0，也不该除零。
        assertNull(cacheHitPercent(TokenUsage(outputTokens = 100)))
    }
}
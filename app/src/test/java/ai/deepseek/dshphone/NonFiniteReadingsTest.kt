package ai.deepseek.dshphone

import ai.deepseek.dshphone.i18n.AppLocale
import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import ai.deepseek.dshphone.model.formatLatencySeconds
import ai.deepseek.dshphone.model.formatStatsDuration
import ai.deepseek.dshphone.model.formatTokensPerSecond
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 读数格式化必须扛得住非有限输入。
 *
 * 时长/速率都从主机投影与 wire 上来：`llmMs`、`toolMs`、`ttftMs` 走
 * `sessionStats` 投影，`decodeTokensPerSec` 走轮次页脚，而 wire 上 `"NaN"` 或
 * `"1e400"` 这类字符串会被 `JsonPrimitive.doubleOrNull` 原样解析成 NaN / Infinity。
 *
 * Kotlin 的 `Double.roundToLong()/roundToInt()` 对 NaN 与无穷直接抛
 * IllegalArgumentException（"Cannot round NaN value."），也就是"打开上下文面板就把 App
 * 崩掉"。Mac 的三处同口径格式化都用 `max(0, x)`（Swift 的 max 把 NaN 当 0）或显式
 * `isFinite` 闸躲开，手机端原来用的是 Kotlin 的 `max(0.0, x)`——它会把 NaN 原样留下。
 */
class NonFiniteReadingsTest {

    private var saved: AppLocale = AppLocale.EN

    @Before
    fun pinLocale() {
        saved = Strings.locale
        Strings.locale = AppLocale.ZH
    }

    @After
    fun restoreLocale() {
        Strings.locale = saved
    }

    @Test
    fun statsDurationReadsNaNAsZero() {
        assertEquals(fmt(Strings.durationSecondsText, "0"), formatStatsDuration(Double.NaN))
    }

    @Test
    fun statsDurationSurvivesInfinities() {
        assertEquals(fmt(Strings.durationSecondsText, "0"), formatStatsDuration(Double.POSITIVE_INFINITY))
        assertEquals(fmt(Strings.durationSecondsText, "0"), formatStatsDuration(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun tokensPerSecondReadsNaNAsZero() {
        assertEquals("0.0", formatTokensPerSecond(Double.NaN))
    }

    @Test
    fun latencySecondsReadsNaNAsZero() {
        assertEquals("0.0", formatLatencySeconds(Double.NaN))
    }

    @Test
    fun normalReadingsAreUnchanged() {
        assertEquals(fmt(Strings.durationSecondsText, "45.2"), formatStatsDuration(45_200.0))
        assertEquals(fmt(Strings.statsDurationMinutes, 2, 42), formatStatsDuration(162_000.0))
        assertEquals("1.5", formatTokensPerSecond(1.5))
        assertEquals("42", formatTokensPerSecond(41.6))
        assertEquals("0.6", formatLatencySeconds(640.0))
    }
}

package ai.deepseek.dshphone.model

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 会话级 Token 用量读数（Mac `SessionUsageReading`，Composer/Dock 的上下文面板用）。
 *
 * 三个互不重叠的 prompt 侧计费桶加 output，读自常驻的 `tokenUsage` 投影。
 * 端口形状与 Mac 逐字段一致，判定逻辑因此能在 JVM 单测里钉住。
 */
data class SessionUsageReading(
    val uncached: Int,
    val cacheRead: Int,
    val cacheWrite: Int,
    val output: Int,
) {
    /** 计费输入：uncached + cache read + cache write。
     *
     * 饱和相加而不是直接 `+`：三个桶各自都可能是 Int.MAX_VALUE（wire 上的整数没有
     * 上界），直接相加会回绕成负数，`billed > 0` 随即为假，整组 Token 行从面板上
     * 消失——用户看到的是「没有用量」，而真实情况是用量爆表。Mac 在同一个位置用
     * `saturatingAdding`（SessionStatsReadings.swift），并写明理由正是这条。
     */
    val billed: Int get() = uncached.saturatingAdding(cacheRead).saturatingAdding(cacheWrite)

    /** 抬头那行数字：计费输入加 output。 */
    val total: Int get() = billed.saturatingAdding(output)

    /** 闸门：所有步骤都没计费的会话不占行。 */
    val hasTokens: Boolean get() = billed > 0 || output > 0

    constructor(usage: TokenUsage?) : this(
        uncached = usage?.uncachedInputTokens ?: usage?.inputTokens ?: 0,
        cacheRead = usage?.cacheReadTokens ?: 0,
        cacheWrite = usage?.cacheWriteTokens ?: 0,
        output = usage?.outputTokens ?: 0,
    )
}

/** 与 Mac `WireNumbers.saturatingAdding` 同一口径：溢出就顶到该方向的极值。 */
internal fun Int.saturatingAdding(other: Int): Int {
    val sum = toLong() + other.toLong()
    return when {
        sum > Int.MAX_VALUE -> Int.MAX_VALUE
        sum < Int.MIN_VALUE -> Int.MIN_VALUE
        else -> sum.toInt()
    }
}

/**
 * 会话级缓存命中率（Mac `sessionCacheHitPercent`）。
 *
 * 先按整数百分比取；只有会读成 100 的部分命中才多要几位小数（99.9、99.99…），
 * 最多 6 位，仍不够就退到 "99.9"——任何部分命中都不许读成整 100%。
 *
 * 不能用单轮的 [cacheHitPercent] 代替：那个是"固定一位小数 + 夹到 99.9"的口径，
 * 99.94% 这样的会话读数会被它显示成 99.9，而 Mac 显示 99.94。
 */
fun sessionCacheHitPercent(cacheRead: Int, billed: Int): String? {
    if (billed <= 0) return null
    if (cacheRead >= billed) return "100"
    val ratio = cacheRead.toDouble() / billed * 100
    for (places in 0..6) {
        val scale = 10.0.pow(places)
        val rounded = (ratio * scale).roundToLong() / scale
        if (rounded >= 100.0) continue
        if (places == 0) return rounded.roundToLong().toString()
        val text = formatFixed(rounded, places)
        return text
    }
    return "99.9"
}

/** 定点小数；只用于非负小读数，和 Mac 的 `String(format: "%.Nf", …)` 同口径。 */
private fun formatFixed(value: Double, places: Int): String {
    val scale = 10.0.pow(places)
    val units = (value * scale).roundToLong()
    val whole = units / scale.toLong()
    val fraction = abs(units % scale.toLong())
    return if (places == 0) whole.toString()
    else "$whole." + fraction.toString().padStart(places, '0')
}

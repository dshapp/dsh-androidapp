package ai.deepseek.dshphone.ui.component

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** 执行中卡片的呼吸周期，与 Chrome 扩展 tools.css 的 `card-breathe`（1.6s）一致。 */
const val BREATHE_PERIOD_MS = 1600L

/** 呼吸的最暗一档：1 → 0.35 → 1。 */
const val BREATHE_MIN_ALPHA = 0.35f

/** CSS 的 ease-in-out，每半程（变暗、变亮）各走一遍。 */
private val BreatheEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/**
 * 全局时钟 [timeMs] 上的呼吸透明度。相位只由时钟决定（扩展用负的 animation-delay =
 * -(now % 1600ms) 做同一件事），所以同时执行中的多张卡片同起同落，
 * 卡片内容更新、行被重建时也不会跳回最亮的起点。
 */
fun breatheAlpha(timeMs: Long): Float {
    val phase = Math.floorMod(timeMs, BREATHE_PERIOD_MS).toFloat() / BREATHE_PERIOD_MS
    val dimming = phase < 0.5f
    val eased = BreatheEasing.transform(if (dimming) phase * 2f else (phase - 0.5f) * 2f)
    val depth = 1f - BREATHE_MIN_ALPHA
    return if (dimming) 1f - depth * eased else BREATHE_MIN_ALPHA + depth * eased
}

/**
 * 执行中的卡片整行呼吸：只动内容（图标 + 标题）的透明度，不动行背景。
 *
 * 透明度在 graphicsLayer 里读，逐帧只重画这一层、不触发重组；[active] 变 false 即停。
 * 系统关掉动画（开发者选项「移除动画」/ 无障碍「移除动画」，即
 * [ValueAnimator.areAnimatorsEnabled] 为 false）时不播放，保持完全不透明。
 */
@Composable
fun Modifier.breathing(active: Boolean): Modifier {
    if (!active) return this
    var alpha by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis {
                alpha = if (ValueAnimator.areAnimatorsEnabled()) breatheAlpha(System.currentTimeMillis()) else 1f
            }
        }
    }
    return this.graphicsLayer { this.alpha = alpha }
}

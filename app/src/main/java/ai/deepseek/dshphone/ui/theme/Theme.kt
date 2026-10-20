package ai.deepseek.dshphone.ui.theme

import ai.deepseek.dshphone.state.AppThemeMode
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 转写与列表共用的度量，对应 iOS 的 PhoneLayout。 */
object Dimens {
    val columnMaxWidth = 760.dp
    val columnInset = 16.dp
    val rowSpacing = 4.dp
    val messageTopInset = 8.dp
    val cardRadius = 12.dp
    val cardPadding = 12.dp
    /** 卡片行（思考、工具、提示）的最小高度，与 iOS PhoneLayout / Mac 同一紧凑节奏。 */
    val chromeRowHeight = 18.dp
    /** 卡片行左侧图标：略小于摘要文字的视觉高度，跟 12sp 摘要配套。 */
    val iconSlot = 12.dp
    val bubbleRadius = 18.dp
    val listRowPadding = 4.dp
    /**
     * 输入区上方的条（目标、收起的提问、排队消息）一行的高度。
     * Mac QueueDock.rowHeight 是 36pt；手机屏幕更紧，再收到 32dp。
     */
    val stripRowHeight = 32.dp
    val stripIconSlot = 28.dp
    val stripSpacing = 8.dp
}

/**
 * 转写里"外围"元素的色阶：思考卡、工具卡这类卡片不是正文，
 * 比 onSurfaceVariant 再弱一级（M3 的 outline 档），让它们明显退到正文后面。
 */
object DshChrome {
    /** 卡片行的图标与摘要。 */
    val label: Color @Composable get() = MaterialTheme.colorScheme.outline

    /** 卡片行尾部的附注：比摘要再淡一点。 */
    val labelSubtle: Color @Composable get() = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
}

private val LightScheme = lightColorScheme(primary = Color(0xFF1C64F2))
private val DarkScheme = darkColorScheme(primary = Color(0xFF7FA8FF))

/**
 * 主题入口。深浅由设置页的主题偏好在组合时解出（跟随系统 / 强制浅色 / 强制深色），
 * 只在 API 31+ 之上仍交给系统的动态取色决定具体色板。
 */
@Composable
fun DshTheme(theme: AppThemeMode = AppThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val darkTheme = when (theme) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

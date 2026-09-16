package ai.deepseek.dshphone.ui.theme

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
    val chromeRowHeight = 24.dp
    val iconSlot = 16.dp
    val bubbleRadius = 18.dp
    val listRowPadding = 4.dp
    val stripRowHeight = 44.dp
    val stripIconSlot = 28.dp
    val stripSpacing = 8.dp
}

private val LightScheme = lightColorScheme(primary = Color(0xFF1C64F2))
private val DarkScheme = darkColorScheme(primary = Color(0xFF7FA8FF))

@Composable
fun DshTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

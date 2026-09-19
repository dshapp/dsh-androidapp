package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.formatBytes
import ai.deepseek.dshphone.model.formatExactTokens
import ai.deepseek.dshphone.model.formatLatencySeconds
import ai.deepseek.dshphone.model.formatTokens
import ai.deepseek.dshphone.model.formatTokensPerSecond
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * 数字读数只跟界面语言走，不跟设备区域走。
 *
 * 本端界面语言只有中/英两种（[ai.deepseek.dshphone.i18n.AppLocale]），web/Mac 的同一批
 * 读数也固定用 `.` 当小数点、`,` 当千分位（web 的 number.groupSeparator 在中/英下就是
 * `,`，Mac 的 formatExactTokens 直接把 groupingSeparator 写成 `,`）。
 * 只要 String.format 用了默认 Locale，德语设备的「4.5 秒」就变成「4,5」、token 计数变成
 * 「1.234.567」，阿拉伯语设备更会整串变成阿拉伯数字——同一台主机在三端显示的数字不再一致。
 *
 * 这里把默认 Locale 钉成德语（小数点/千分位都与中英相反）再断言，正是要抓住这类漂移。
 */
class NumberFormatTest {

    private lateinit var original: Locale

    @Before
    fun pinGermanLocale() {
        original = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun latencyKeepsDotDecimalSeparator() {
        assertEquals("4.5", formatLatencySeconds(4_500.0))
        // 十秒以上走整秒分支，同样不该出现千分位或本地小数点。
        assertEquals("12", formatLatencySeconds(12_400.0))
    }

    @Test
    fun tokensPerSecondKeepsDotDecimalSeparator() {
        assertEquals("3.3", formatTokensPerSecond(3.25))
        assertEquals("42", formatTokensPerSecond(42.4))
    }

    @Test
    fun tokenCountsKeepCommaGrouping() {
        assertEquals("1,234,567", formatExactTokens(1_234_567))
        assertEquals("12.2K", formatTokens(12_200))
    }

    @Test
    fun byteCountsKeepDotDecimalSeparator() {
        assertEquals("1.5 MB", formatBytes((1 shl 20) + (1 shl 19)))
        // 整数字节数是正对照：这条改前改后都应当是 "512 KB"。
        assertEquals("512 KB", formatBytes(512 * 1024))
    }
}

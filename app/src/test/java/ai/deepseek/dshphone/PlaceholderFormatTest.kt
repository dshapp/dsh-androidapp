package ai.deepseek.dshphone

import ai.deepseek.dshphone.i18n.AppLocale
import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * 占位符填充只跟界面语言走，不跟设备区域走。
 *
 * 界面语言只有中/英两种（AppLocale），所以 %d 只能是 ASCII 数字、%s 也只能是 ASCII 文本。
 * fmt 原来不带 Locale，阿拉伯语设备上 String.format("%d", 2025) 会给出阿拉伯数字
 * （٢٠٢٥），同一屏里跟 Format.kt 已固定区域的小数/千分位读数对不上；德语设备的
 * 模板若参与 numérique 格式化也会漂。这里把默认区域钉成阿拉伯语再断言。
 */
class PlaceholderFormatTest {

    private lateinit var original: Locale
    private var originalUiLocale: AppLocale = AppLocale.EN

    @Before
    fun pinArabicLocale() {
        original = Locale.getDefault()
        originalUiLocale = Strings.locale
        Locale.setDefault(Locale("ar", "EG"))
        // 文案本身按界面语言选（不跟设备区域），测试里钉成英文好断言。
        Strings.locale = AppLocale.EN
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
        Strings.locale = originalUiLocale
    }

    @Test
    fun integerPlaceholdersStayAsciiDigits() {
        assertEquals("7/12", fmt(Strings.findCount, 7, 12))
        assertEquals("7 / 12", fmt(Strings.questionProgress, 7, 12))
        assertEquals("2025", fmt("%d", 2025))
        assertEquals("07", fmt("%02d", 7))
    }

    @Test
    fun mixedTemplatesStayAsciiDigits() {
        // durationMinutes 同时含 %d 与 %02d；两者都不能变成本地数字。
        assertEquals("2m 07s", fmt(Strings.durationMinutes, 2, 7))
        assertEquals("3 turns 12 steps", fmt(Strings.statsCounts, 3, 12))
    }

    @Test
    fun stringPlaceholdersAreUnchanged() {
        assertEquals("Showing the first 20 results. Narrow your search.", fmt(Strings.searchHasMore, 20))
    }
}

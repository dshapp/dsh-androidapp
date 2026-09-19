package ai.deepseek.dshphone

import ai.deepseek.dshphone.i18n.AppLocale
import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.model.relativeTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 相对时间必须走 web/Mac 共用的六档桶。
 *
 * Web 的 relativeTime（ui-primitives）与 Mac 的 relativeTimeLabel 是同一套口径：
 * now / minutes / hours / days / months（30 天起）/ years（365 天起）。
 * 手机端原来只有 s/m/h/d 四档，于是刚更新过的会话显示 "0秒"（web/Mac 是"刚刚"/now），
 * 一年前的会话显示 "395天"（应当是"1年"）。同一屏里两端的说法对不上。
 */
class RelativeTimeTest {

    private var originalUiLocale: AppLocale = AppLocale.EN

    @Before
    fun pinEnglish() {
        originalUiLocale = Strings.locale
        Strings.locale = AppLocale.EN
    }

    @After
    fun restore() {
        Strings.locale = originalUiLocale
    }

    private fun ago(millis: Long): String = relativeTime(System.currentTimeMillis().toDouble() - millis)

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val month = 30 * day
    private val year = 365 * day

    @Test
    fun subMinuteIsNow() {
        assertEquals("now", ago(0))
        assertEquals("now", ago(30_000))
        assertEquals("now", ago(minute - 1))
        // 未来时间戳（时钟漂移）夹到 0，同样是 now。
        assertEquals("now", relativeTime(System.currentTimeMillis().toDouble() + hour))
    }

    @Test
    fun bucketsMatchTheSharedConvention() {
        assertEquals("5min", ago(5 * minute))
        assertEquals("3h", ago(3 * hour))
        assertEquals("2d", ago(2 * day))
        assertEquals("4mo", ago(4 * month))
        assertEquals("1y", ago(year))
    }

    @Test
    fun monthAndYearBucketsReplaceRunawayDayCounts() {
        // 45 天不再读作 "45d"，而是 1 个月。
        assertEquals("1mo", ago(45 * day))
        // 395 天不再读作 "395d"，而是 1 年。
        assertEquals("1y", ago(395 * day))
        assertEquals("2y", ago(2 * year))
    }

    @Test
    fun chineseLabelsUseTheSharedDictionary() {
        Strings.locale = AppLocale.ZH
        assertEquals("刚刚", ago(10_000))
        assertEquals("5分钟", ago(5 * minute))
        assertEquals("3小时", ago(3 * hour))
        assertEquals("2天", ago(2 * day))
        assertEquals("4个月", ago(4 * month))
        assertEquals("1年", ago(year))
    }
}

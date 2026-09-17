package ai.deepseek.dshphone.state

import ai.deepseek.dshphone.i18n.AppLocale
import ai.deepseek.dshphone.i18n.Strings
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 主题偏好：跟随系统 / 强制浅色 / 强制深色。 */
enum class AppThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 本机外观偏好（语言 + 主题）。
 *
 * 语言与主题都只影响这一台设备，跟连接哪台 Mac 无关，所以既不进加密的配对存储，
 * 也不问主机，直接落在普通的 SharedPreferences 里；[SettingsStore] 的实例挂在
 * [PhoneModel] 上，Activity 重建（旋屏、切深色）时偏好不会跟着丢。
 *
 * 读取发生在任何界面组合之前，所以 [Strings] 的语言在首帧就已经是对的那一种。
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("dsh_settings", Context.MODE_PRIVATE)

    /** null 表示跟随系统（与 iOS 的 AppLocale.followSystem 同口径）。 */
    var locale by mutableStateOf<AppLocale?>(readLocale())
        private set

    var themeMode by mutableStateOf(readThemeMode())
        private set

    /** 系统语言在 App 运行期间变了（Activity 重建）：跟随系统时重新解析一次。 */
    fun refreshSystemLocale() {
        if (locale == null) Strings.locale = Strings.resolve()
    }

    fun chooseLocale(value: AppLocale?) {
        locale = value
        prefs.edit().apply {
            if (value == null) remove(LOCALE_KEY) else putString(LOCALE_KEY, value.name)
        }.apply()
        Strings.locale = value ?: Strings.resolve()
    }

    fun chooseThemeMode(value: AppThemeMode) {
        themeMode = value
        prefs.edit().putString(THEME_KEY, value.name).apply()
    }

    private fun readLocale(): AppLocale? = when (prefs.getString(LOCALE_KEY, null)) {
        AppLocale.EN.name -> AppLocale.EN
        AppLocale.ZH.name -> AppLocale.ZH
        else -> null
    }

    private fun readThemeMode(): AppThemeMode = when (prefs.getString(THEME_KEY, null)) {
        AppThemeMode.LIGHT.name -> AppThemeMode.LIGHT
        AppThemeMode.DARK.name -> AppThemeMode.DARK
        else -> AppThemeMode.SYSTEM
    }

    init {
        // 首帧前就把语言定下来，之后所有 Strings 都读这份。
        Strings.locale = locale ?: Strings.resolve()
    }

    private companion object {
        const val LOCALE_KEY = "locale"
        const val THEME_KEY = "theme"
    }
}

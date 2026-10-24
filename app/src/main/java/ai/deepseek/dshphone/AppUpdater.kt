package ai.deepseek.dshphone

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * App 自身的更新（对齐 Mac 的 AppUpdater）。
 *
 * 站点 `/downloads/latest.json` 的 android 条目比当前版本新，就在后台把 APK 下到
 * filesDir/updates，校验 sha256、包名与版本号，然后弹窗「立即更新 / 下次再说」：
 *  · 立即更新：交给系统安装器，装完点「打开」就是新版本。
 *  · 下次再说：记下这个版本，等用户退出 App、下次打开时直接拉起安装。
 * Android 不允许应用静默替换自己，所以生效前总要经过系统安装确认这一下。
 */
object AppUpdater {
    data class Ready(val version: String, val notes: String, val apk: File)

    /** 已下载好、等用户选择的新版本；界面据此弹窗。 */
    var ready by mutableStateOf<Ready?>(null)
        private set

    private const val TAG = "AppUpdater"
    private const val MANIFEST = "https://dshapp.github.io/downloads/latest.json"
    private const val INTERVAL_MS = 6 * 3600 * 1000L
    private const val PREFS = "app-update"
    private const val KEY_DEFERRED = "deferred"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    @Volatile private var running = false
    private var lastCheck = 0L
    /** 本进程已经弹过的版本：定时复查不再重复打扰，重开 App 后才再提示。 */
    @Volatile private var prompted: String? = null
    /** 等用户在「安装未知应用」里允许本 App 后再安装的包。 */
    private var awaitingPermission: File? = null

    /**
     * 回到前台时调：刚从「安装未知应用」授权页回来且已允许，就接着安装；
     * 检查更新每个进程第一次必查，之后间隔 6 小时。
     */
    fun onForeground(context: Context) {
        awaitingPermission?.let { apk ->
            if (context.packageManager.canRequestPackageInstalls()) {
                awaitingPermission = null
                install(context, apk)
            }
        }
        val app = context.applicationContext
        if (running || !eligible(app)) return
        val now = SystemClock.elapsedRealtime()
        if (lastCheck != 0L && now - lastCheck < INTERVAL_MS) return
        lastCheck = now
        running = true
        scope.launch {
            try {
                check(app)
            } catch (e: Exception) {
                Log.w(TAG, "update check failed", e)
            } finally {
                running = false
            }
        }
    }

    /** 界面新建（用户退出过 App 后再打开）时调：「下次再说」留下的新版本此时拉起安装。 */
    fun onAppOpened(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_DEFERRED, null) ?: return
        prefs.edit().remove(KEY_DEFERRED).apply()
        val apk = apkFile(context, version)
        if (!eligible(context) || !isNewer(version, currentVersion(context)) || !apk.isFile) return
        prompted = version
        install(context, apk)
    }

    fun installNow(context: Context) {
        val update = ready ?: return
        ready = null
        install(context, update.apk)
    }

    fun later(context: Context) {
        val update = ready ?: return
        ready = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DEFERRED, update.version).apply()
    }

    /** 只有正式发布的包会自更新：本地 debug 包和默认版本号的构建不参与。 */
    private fun eligible(context: Context): Boolean {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 &&
            PackageInfoCompat.getLongVersionCode(info) > 1
    }

    private fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    private suspend fun check(context: Context) {
        val body = http.newCall(Request.Builder().url(MANIFEST).header("Cache-Control", "no-cache").build()).execute().use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            it.body?.string().orEmpty()
        }
        val entry = JSONObject(body).optJSONObject("android") ?: return
        val version = entry.getString("version")
        if (!isNewer(version, currentVersion(context))) {
            updatesDir(context).listFiles()?.forEach { it.delete() }
            return
        }
        if (prompted == version) return
        val apk = prepare(context, version, entry.getString("url"), entry.getString("sha256"))
        val notes = releaseNotes(entry.optString("notes"))
        withContext(Dispatchers.Main) {
            prompted = version
            ready = Ready(version, notes, apk)
        }
    }

    /** 下载并校验；已下好且有效的直接复用，其它版本的旧包顺手清掉。 */
    private fun prepare(context: Context, version: String, url: String, sha256: String): File {
        val apk = apkFile(context, version)
        if (!apk.isFile || !isValidPackage(context, apk, version)) {
            val partial = File(apk.path + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val input = response.body?.byteStream() ?: throw IOException("empty body")
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != sha256.lowercase() || !isValidPackage(context, partial, version)) {
                partial.delete()
                throw IOException("downloaded update failed verification")
            }
            if (!partial.renameTo(apk)) throw IOException("cannot store update")
        }
        updatesDir(context).listFiles()?.filter { it != apk }?.forEach { it.delete() }
        return apk
    }

    @Suppress("DEPRECATION")
    private fun isValidPackage(context: Context, apk: File, version: String): Boolean {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0) ?: return false
        return info.packageName == context.packageName && info.versionName == version
    }

    private fun install(context: Context, apk: File) {
        // 首次自更新要先允许本 App「安装未知应用」：自己带用户去授权页，回来后由 onForeground
        // 接着装。交给系统安装器去提示的话，授权回来它就直接关掉了，这次更新也随之丢失。
        if (!context.packageManager.canRequestPackageInstalls()) {
            awaitingPermission = apk
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settings) }.onFailure { Log.w(TAG, "cannot open install permission settings", it) }
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "cannot launch the package installer", e)
        }
    }

    private fun updatesDir(context: Context) = File(context.filesDir, "updates").apply { mkdirs() }

    private fun apkFile(context: Context, version: String) = File(updatesDir(context), "DSH-$version.apk")

    private fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Release 正文里「## 更新内容」那一节，与 Mac 同样的取法；没有这一节就是空。 */
    private fun releaseNotes(body: String): String {
        val lines = body.replace("\r\n", "\n").split('\n')
        val start = lines.indexOfFirst { it.trim() == "## 更新内容" }
        if (start < 0) return ""
        return lines.drop(start + 1)
            .takeWhile { !it.startsWith("---") && !it.startsWith("## ") }
            .map { line ->
                val trimmed = line.trim()
                val bullet = if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) "• " + trimmed.drop(2) else trimmed
                bullet.replace("**", "").replace("`", "")
            }
            .joinToString("\n")
            .trim()
    }
}

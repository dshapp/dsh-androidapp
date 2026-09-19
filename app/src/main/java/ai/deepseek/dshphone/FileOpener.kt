package ai.deepseek.dshphone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import ai.deepseek.dshphone.model.pathBasename
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** 交给系统查看器的结果：成功、没有能接的 App、或者读取/写出失败。 */
sealed interface ExternalOpenResult {
    data object Opened : ExternalOpenResult
    /** 设备上一个能看文本的 App 都没有（干净模拟器就是这样）。 */
    data object NoViewer : ExternalOpenResult
    data class Failed(val message: String) : ExternalOpenResult
}

/**
 * 把工作区文件交给系统自带的查看器。
 *
 * Android 没有"远程工作区"这种东西，系统 App 只认本机文件，所以路径要分两步落地：
 *
 * 1. 内容仍然走 DSH Web 同一条协议——`workspaceFiles/read` 按行分页读全文
 *    （见 [ai.deepseek.dshphone.net.ApiClient.workspaceFileRead] 与 DSH Web 的
 *    `ui-sidebar-documentpreview`），会话只用来让主机解析相对路径。
 * 2. 读回来的文本写进本应用缓存目录下的一个只读文件，再用 [FileProvider] 把
 *    `content://` 地址交给 `ACTION_VIEW`。文本应用（Text Editor、QuickEdit、
 *    Google 文件极客……）都能接这个 Intent，用户在系统查看器里读、复制、搜索，
 *    比自己再写一套阅读器更符合"用系统自带的文本查看器"这个要求。
 *
 * 缓存文件名带路径与内容版本的摘要：同一个文件重复打开会覆盖同一个文件，
 * 不会在缓存里堆一串副本；跨进程重启后 `.nomedia` 保证它们不进相册。
 */
class FileOpener(private val context: Context) {

    /** 交给系统查看器。 [text] 已经是从主机读回来的完整内容。 */
    suspend fun openText(sessionId: String, path: String, displayName: String, text: String): ExternalOpenResult {
        val uri = try {
            // 写盘放 IO：正文可能到兆级，别占着主线程。
            withContext(Dispatchers.IO) { fileProviderUri(sessionId, path, displayName, text) }
        } catch (e: Exception) {
            Log.w(TAG, "could not stage \"$path\" for the system viewer", e)
            return ExternalOpenResult.Failed(e.message ?: e.toString())
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 查看器与聊天页分属两个任务时，返回键才回得到会话里；
            // 没有 NEW_TASK 时从非 Activity 上下文启动会直接抛。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ExternalOpenResult.Opened
        } catch (_: ActivityNotFoundException) {
            // 干净模拟器上确实一个文本查看器都没有：交给调用方退回内嵌预览。
            ExternalOpenResult.NoViewer
        } catch (e: Exception) {
            Log.w(TAG, "system viewer refused \"$path\"", e)
            ExternalOpenResult.Failed(e.message ?: e.toString())
        }
    }

    private fun fileProviderUri(sessionId: String, path: String, displayName: String, text: String): Uri {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        File(dir, NOMEDIA).takeIf { !it.exists() }?.writeText("")
        val file = File(dir, cacheName(sessionId, path, displayName, text))
        // 同一路径再次打开时覆盖它自己，缓存里只会有一份。
        file.writeText(text)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * 缓存文件名：显示名保持可读（系统查看器标题用它），
     * 会话 + 路径 + 内容摘要保证"同一路径不同版本"也会换一份文件，旧版本由启动清理回收。
     */
    private fun cacheName(sessionId: String, path: String, displayName: String, text: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$sessionId\u0000$path\u0000$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(10)
        val base = pathBasename(displayName).ifEmpty { "file" }
        val stem = base.substringBeforeLast('.', base).take(40).ifEmpty { "file" }
        val extension = base.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 8 } ?: "txt"
        return "$stem-$digest.$extension"
    }

    /** 清掉上次启动留下的缓存，避免无限增长（不改名、不动 [DIR] 之外的东西）。 */
    fun prune(now: Long = System.currentTimeMillis()) {
        val files = File(context.cacheDir, DIR).listFiles() ?: return
        files.forEach { file ->
            if (file.name == NOMEDIA) return@forEach
            if (now - file.lastModified() > MAX_AGE_MS) file.delete()
        }
    }

    private companion object {
        const val TAG = "FileOpener"
        const val DIR = "dsh-files"
        const val NOMEDIA = ".nomedia"
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}

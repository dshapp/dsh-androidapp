package ai.deepseek.dshphone.ui.component

import ai.deepseek.dshphone.model.ImageFileType
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把图片交给系统自带的图片查看器打开。
 *
 * 做法：先把字节写到应用缓存目录，再用 FileProvider 换一个 content:// 地址，最后用
 * ACTION_VIEW + image 类型起系统图片查看器。App 内部不集成浏览器/WebView/图片组件——
 * 由系统去决定用哪个查看器（相册、Photos、文件管理器……）。
 *
 * 缓存目录每次只保留最近若干个文件，避免越攒越多。每次打开都是"先分片写到缓存文件、
 * 再起 Intent"，所以内存里只会有当前这一片，而不是整张图。
 */
object SystemImageViewer {

    private const val TAG = "SystemImageViewer"
    private const val DIRECTORY = "shared-images"
    private const val KEEP_FILES = 8

    /** 落地到缓存的上限：再大就不往图片查看器送了，免得把缓存目录撑爆。 */
    const val MAX_BYTES = 64L * 1024 * 1024

    /**
     * 会话附件落缓存时用的「路径」：交给 [stageFile] 与 [open]。
     *
     * 故意不带扩展名，让 [open] 按文件头认类型：附件名的扩展名不可信（粘贴的图叫
     * pasted-image-1.png，实际字节却是 webp），按名字报 MIME 可能让查看器打不开。
     */
    fun attachmentPath(attachmentId: String): String =
        "attachment-" + attachmentId.substringAfter(':').take(16)

    /**
     * 申请一个准备接收图片的缓存文件：调用方把字节写进去（可分片追加），写完再交给 [open]。
     * 先落地再打开，是为了不必把整张图同时握在内存里。
     */
    fun stageFile(context: Context, workspacePath: String): File {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        prune(directory)
        return File(directory, stagedImageFileName(workspacePath))
    }

    /**
     * 打开已经落地好的图片。返回 true 表示已经成功把 Intent 交给了系统。
     * 返回 false 表示系统里没有能处理图片的应用（界面据此提示）。
     * 失败就把临时文件删掉；成功则留着，因为系统查看器可能延迟才真正去读它。
     */
    fun open(context: Context, workspacePath: String, file: File): Boolean {
        if (!file.isFile || file.length() == 0L) {
            file.delete()
            return false
        }
        // 无扩展名（附件对象）时按文件头补 MIME；认不出不是图片，不往查看器送。
        val mimeType = if (ImageFileType.extensionOf(workspacePath).isEmpty()) {
            val head = runCatching {
                // 不用 readNBytes：那是 API 33 才有的。
                file.inputStream().use { input ->
                    val buffer = ByteArray(ImageFileType.SNIFF_BYTES)
                    var read = 0
                    while (read < buffer.size) {
                        val n = input.read(buffer, read, buffer.size - read)
                        if (n < 0) break
                        read += n
                    }
                    buffer.copyOf(read)
                }
            }.getOrNull()
            head?.let(ImageFileType::sniffMimeType) ?: run {
                file.delete()
                return false
            }
        } else {
            ImageFileType.mimeTypeOf(workspacePath)
        }
        val authority = context.packageName + ".fileprovider"
        val uri = try {
            FileProvider.getUriForFile(context, authority, file)
        } catch (e: Exception) {
            Log.w(TAG, "failed to obtain a content uri", e)
            file.delete()
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity can view this image", e)
            file.delete()
            false
        } catch (e: Exception) {
            Log.w(TAG, "failed to launch the system viewer", e)
            file.delete()
            false
        }
    }

    private fun prune(directory: File) {
        val files = directory.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP_FILES).forEach { it.delete() }
    }
}

/**
 * 图片落到缓存目录时用的文件名。
 *
 * 扩展名必须留下，否则系统可能认不出类型。名字里还要带一段完整路径的哈希：
 * 不同目录下的同名图片（`a/shot.png` 与 `b/shot.png`）不能落到同一个缓存文件上，
 * 否则后打开的那张会把前一张覆盖掉，而系统查看器可能还在读它。
 *
 * 单独拎成顶层函数是为了能直接单测——它不碰 Context，也不需要系统查看器在场。
 */
internal fun stagedImageFileName(workspacePath: String): String {
    val basename = workspacePath.substringAfterLast('/').substringAfterLast('\\')
    val extension = ImageFileType.extensionOf(workspacePath)
    val stem = if (extension.isEmpty()) basename else basename.dropLast(extension.length + 1)
    val safeStem = stem.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
        .joinToString("")
        .take(40)
        .ifEmpty { "image" }
    val tag = workspacePath.hashCode().toUInt().toString(16)
    return if (extension.isEmpty()) safeStem + "-" + tag else safeStem + "-" + tag + "." + extension
}

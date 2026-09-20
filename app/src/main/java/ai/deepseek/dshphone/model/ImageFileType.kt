package ai.deepseek.dshphone.model

/**
 * 聊天记录里的文件链接：按扩展名判断是不是图片。
 *
 * 与 Web 端 dsh-client-ui-primitives 的 classifyFileType 保持一致（同一组扩展名），
 * 这样"Web 那边当图片渲染的，手机这边也当图片打开"。
 */
object ImageFileType {

    private val extensions: Set<String> = setOf(
        "png", "jpg", "jpeg", "gif", "svg", "webp", "avif",
        "bmp", "ico", "tif", "tiff", "heic", "heif",
    )

    /** 从路径里取出扩展名（不含点，小写）。 */
    fun extensionOf(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    fun isImage(path: String): Boolean = extensionOf(path) in extensions

    /** 交给系统时的 MIME 类型；识别不了就退回 image 通配类型。 */
    fun mimeTypeOf(path: String): String = when (extensionOf(path)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        "ico" -> "image/x-icon"
        "tif", "tiff" -> "image/tiff"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        else -> "image/*"
    }
}

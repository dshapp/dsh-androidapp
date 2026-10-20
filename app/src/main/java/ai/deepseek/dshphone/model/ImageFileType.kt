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

    /**
     * 图片语法 `![alt](path)` 的落点能不能当图片去读：图片扩展名，或者**没有扩展名**。
     *
     * 截图类附件存成 `~/.dsh/attachments/v1/objects/<sha256>`，没有扩展名。Web 的 <img>
     * 与 Mac 的 NSImage 都按内容解码，所以两端都能显示；只认扩展名的话手机上只剩 alt 文字。
     * 无扩展名时交给解码那一步按内容判（解不出来退回 alt），明确的非图片扩展名（.txt…）不读。
     */
    fun isImageDestination(path: String): Boolean {
        val extension = extensionOf(path)
        return extension.isEmpty() || extension in extensions
    }

    /**
     * 按文件头识别位图类型（WHATWG MIME Sniffing 的图片签名表，浏览器 <img> 认的同一组）；
     * 认不出返回 null。给无扩展名的图片补系统查看器要的 MIME。
     */
    fun sniffMimeType(head: ByteArray): String? {
        fun at(vararg pattern: Int?): Boolean =
            head.size >= pattern.size &&
                pattern.withIndex().all { (i, b) -> b == null || (head[i].toInt() and 0xff) == b }
        return when {
            at(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
            at(0xff, 0xd8, 0xff) -> "image/jpeg"
            at(0x47, 0x49, 0x46, 0x38, 0x37, 0x61) || at(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) -> "image/gif"
            at(0x52, 0x49, 0x46, 0x46, null, null, null, null, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50) -> "image/webp"
            at(0x42, 0x4d) -> "image/bmp"
            at(0x00, 0x00, 0x01, 0x00) || at(0x00, 0x00, 0x02, 0x00) -> "image/x-icon"
            else -> null
        }
    }

    /** [sniffMimeType] 需要的文件头长度。 */
    const val SNIFF_BYTES = 14

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

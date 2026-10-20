package ai.deepseek.dshphone.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 图片文件链接的扩展名判定：与 Web 端 classifyFileType 的图片集合对齐。 */
class ImageFileTypeTest {

    @Test
    fun recognisesWebImageExtensions() {
        val images = listOf(
            "a.png", "a.jpg", "a.jpeg", "a.gif", "a.svg", "a.webp", "a.avif",
            "a.bmp", "a.ico", "a.tif", "a.tiff", "a.heic", "a.heif",
        )
        for (name in images) {
            assertTrue(name, ImageFileType.isImage(name))
            assertTrue(name, ImageFileType.isImage("/tmp/dir/$name"))
            assertTrue(name, ImageFileType.isImage("app/assets/$name"))
        }
    }

    @Test
    fun leavesNonImagesToTheTextPreview() {
        val others = listOf(
            "a.txt", "a.kt", "a.md", "a.json", "a.pdf", "a.mp4", "a.zip",
            "image", "a.", ".png", "archive.png.zip", "a.PNGX",
        )
        for (name in others) {
            assertFalse(name, ImageFileType.isImage(name))
        }
    }

    @Test
    fun isCaseInsensitive() {
        assertTrue(ImageFileType.isImage("Screenshot.PNG"))
        assertTrue(ImageFileType.isImage("Photo.JPEG"))
    }

    @Test
    fun mapsMimeTypes() {
        assertEquals("image/png", ImageFileType.mimeTypeOf("a.png"))
        assertEquals("image/jpeg", ImageFileType.mimeTypeOf("a.jpg"))
        assertEquals("image/jpeg", ImageFileType.mimeTypeOf("a.jpeg"))
        assertEquals("image/svg+xml", ImageFileType.mimeTypeOf("a.svg"))
        assertEquals("image/tiff", ImageFileType.mimeTypeOf("a.tiff"))
        assertEquals("image/heic", ImageFileType.mimeTypeOf("a.heic"))
    }

    @Test
    fun imageSyntaxAcceptsExtensionlessAttachmentObjects() {
        // 回归：![…](</Users/u/.dsh/attachments/v1/objects/80/80523a84…>) 在手机上只剩 alt 文字。
        assertTrue(ImageFileType.isImageDestination("/Users/u/.dsh/attachments/v1/objects/80/80523a84ba40b9b6"))
        assertTrue(ImageFileType.isImageDestination("/tmp/v1.2/shot"))
        assertTrue(ImageFileType.isImageDestination("/tmp/shot.PNG"))
        assertFalse(ImageFileType.isImageDestination("/tmp/notes.txt"))
        assertFalse(ImageFileType.isImageDestination("/tmp/archive.png.zip"))
        // 普通文件链接的分流不变：无扩展名仍不是图片。
        assertFalse(ImageFileType.isImage("/Users/u/.dsh/attachments/v1/objects/80/80523a84ba40b9b6"))
    }

    @Test
    fun sniffsBitmapSignatures() {
        fun bytes(vararg xs: Int) = ByteArray(xs.size) { xs[it].toByte() }
        assertEquals("image/png", ImageFileType.sniffMimeType(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0)))
        assertEquals("image/jpeg", ImageFileType.sniffMimeType(bytes(0xff, 0xd8, 0xff, 0xe0)))
        assertEquals("image/gif", ImageFileType.sniffMimeType("GIF89a..".toByteArray()))
        assertEquals("image/webp", ImageFileType.sniffMimeType("RIFF\u0001\u0002\u0003\u0004WEBPVP8 ".toByteArray()))
        assertEquals(null, ImageFileType.sniffMimeType("<svg/>".toByteArray()))
        assertEquals(null, ImageFileType.sniffMimeType(bytes(0x89, 0x50)))
        assertEquals(null, ImageFileType.sniffMimeType(ByteArray(0)))
    }

    @Test
    fun handlesWindowsSeparatorsAndAnchors() {
        assertTrue(ImageFileType.isImage("C:\\work\\shot.png"))
        assertTrue(ImageFileType.isImage("docs/diagram.svg"))
    }
}

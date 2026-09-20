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
    fun handlesWindowsSeparatorsAndAnchors() {
        assertTrue(ImageFileType.isImage("C:\\work\\shot.png"))
        assertTrue(ImageFileType.isImage("docs/diagram.svg"))
    }
}

package ai.deepseek.dshphone.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片落到缓存目录时的文件名。
 *
 * 原做法是直接拿 basename，于是不同目录下的同名图片会落到同一个文件上：后打开的那张
 * 覆盖前一张，而系统查看器可能还在读前一张，看到的就是错的图。所以名字必须带上
 * 完整路径，同时扩展名必须留下（否则系统认不出类型），路径分隔符必须清掉。
 */
class StagedImageFileNameTest {

    @Test
    fun sameBasenameInDifferentDirectoriesDoesNotCollide() {
        assertNotEquals(
            stagedImageFileName("/w/a/shot.png"),
            stagedImageFileName("/w/b/shot.png"),
        )
    }

    @Test
    fun samePathIsStable() {
        assertEquals(
            stagedImageFileName("/w/a/shot.png"),
            stagedImageFileName("/w/a/shot.png"),
        )
    }

    @Test
    fun keepsTheExtensionSoTheSystemCanPickAViewer() {
        assertTrue(stagedImageFileName("/w/a/shot.PNG").endsWith(".png"))
        assertTrue(stagedImageFileName("/w/a/pic.jpeg").endsWith(".jpeg"))
        assertTrue(stagedImageFileName("C:\\w\\a\\pic.webp").endsWith(".webp"))
    }

    @Test
    fun stripsPathSeparatorsAndOddCharacters() {
        val name = stagedImageFileName("/w/a/../we ird/na:me*.png")
        assertFalse(name, name.contains("/"))
        assertFalse(name, name.contains("\\"))
        assertFalse(name, name.contains(":"))
        assertFalse(name, name.contains("*"))
        assertFalse(name, name.contains(" "))
        // 仍是单层文件名，不会有目录穿越
        assertFalse(name, name.contains(".."))
    }

    @Test
    fun alwaysProducesSomethingUsable() {
        assertTrue(stagedImageFileName("").isNotBlank())
        assertTrue(stagedImageFileName("/w/***").isNotBlank())
        assertTrue(stagedImageFileName("/w/a/").isNotBlank())
    }

    /**
     * 会话附件交给系统查看器时不带扩展名：附件名的扩展名不可信（pasted-image-1.png 实为 webp），
     * 由 SystemImageViewer.open 按文件头认类型。不同附件不能落到同一个缓存文件上。
     */
    @Test
    fun attachmentPathHasNoExtensionAndDiffersPerAttachment() {
        val a = SystemImageViewer.attachmentPath("sha256:7f7e8c63529ddb3fa05514fddc98dedef2feee48162a24808a00f9c241e708ce")
        val b = SystemImageViewer.attachmentPath("sha256:e353c92d2763bf36b08bb8ac12c08846e3cb5d162e82a14fc9d4842d51602627")
        assertEquals("", ai.deepseek.dshphone.model.ImageFileType.extensionOf(a))
        assertFalse(a, a.contains(":"))
        assertNotEquals(stagedImageFileName(a), stagedImageFileName(b))
    }

    @Test
    fun staysShortEnoughForAnyFilesystem() {
        val name = stagedImageFileName("/w/a/" + "x".repeat(500) + ".png")
        assertTrue("too long: " + name.length, name.length <= 64)
    }
}

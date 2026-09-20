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

    @Test
    fun staysShortEnoughForAnyFilesystem() {
        val name = stagedImageFileName("/w/a/" + "x".repeat(500) + ".png")
        assertTrue("too long: " + name.length, name.length <= 64)
    }
}

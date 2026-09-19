package ai.deepseek.dshphone

import ai.deepseek.dshphone.ui.screen.stagedAttachmentFileName
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 暂存附件的落盘名必须只由这条附件自己决定。
 *
 * 原实现是 `File(cacheDir, displayName)`：文件名直接当路径，于是
 *  1) 先后选两个同名不同内容的文件（两个 README.md / 两张 image.png），两次暂存落到同一个
 *     路径，第二份内容覆盖第一份。发送时才读文件（PhoneModel.submit → client.uploadFile），
 *     结果是两条附件上传同一份内容，第一份被静默换掉。
 *  2) 名字里带路径分隔符时 writeBytes 直接抛异常，又被 runCatching 吞掉，附件无声消失。
 *     DISPLAY_NAME 本身可能含 "/"，而退化路径 uri.lastPathSegment 对 tree URI
 *     （content://…/tree/primary%3ADownload/document/primary%3ADownload%2Ffile.txt）
 *     恰好就是 "primary:Download/file.txt"。
 *
 * 所以落盘名要带上这条附件的 id 才能互不覆盖，且必须清洗掉路径分隔符。
 */
class AttachmentStagingTest {

    @Test
    fun sameDisplayNameDoesNotCollide() {
        val a = stagedAttachmentFileName("README.md", "id-a")
        val b = stagedAttachmentFileName("README.md", "id-b")
        assertNotEquals("同名附件不能落到同一个暂存路径", a, b)
    }

    @Test
    fun distinctNamesStayInDistinctFiles() {
        assertNotEquals(
            stagedAttachmentFileName("a.txt", "id-a"),
            stagedAttachmentFileName("b.txt", "id-b"),
        )
    }

    @Test
    fun pathSeparatorsAreSanitized() {
        val staged = stagedAttachmentFileName("primary:Download/file.txt", "id-a")
        assertTrue("暂存名不能含路径分隔符：" + staged, !staged.contains('/') && !staged.contains('\\'))
    }

    @Test
    fun blankNamesStillProduceAUsableFile() {
        for (raw in listOf("/", "..", ".", "   ", "")) {
            val staged = stagedAttachmentFileName(raw, "id-a")
            assertTrue("空名要有兜底（raw=" + raw + "）：" + staged, staged.isNotBlank())
            assertTrue("不能落成相对路径（raw=" + raw + "）：" + staged, staged != "." && staged != "..")
            assertTrue("不能含分隔符（raw=" + raw + "）：" + staged, !staged.contains('/'))
        }
    }

    @Test
    fun keepsTheReadableOriginalName() {
        val staged = stagedAttachmentFileName("quarterly report.pdf", "id-a")
        assertTrue("暂存名要认得出来源：" + staged, staged.contains("quarterly report.pdf"))
        assertTrue("扩展名要保留，主机按名字识别类型：" + staged, staged.endsWith(".pdf"))
    }

    @Test
    fun controlCharactersAreSanitized() {
        val staged = stagedAttachmentFileName("evil\nname.txt", "id-a")
        assertTrue("控制字符要清掉：" + staged, staged.none { it.isISOControl() })
    }

    @Test
    fun pathologicalNamesAreBounded() {
        val staged = stagedAttachmentFileName("x".repeat(5000), "id-a")
        assertTrue("暂存名不能长到超文件系统上限：" + staged.length, staged.length <= 200)
    }
}

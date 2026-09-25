package ai.deepseek.dshphone

import ai.deepseek.dshphone.i18n.AppLocale
import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import ai.deepseek.dshphone.model.SessionEvent
import ai.deepseek.dshphone.state.SessionStore
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 改动文件卡片与 `present` 交付卡片的文案对齐（web ui-deliverables `locales.ts`）。
 *
 * web 把两处文案分成两个命名空间：卡片行/表头是 `changes.*`，单文件比较的正文是
 * `diff.*`——同一个「过大」在行上是 `too large`、在正文里是整句
 * `File too large; changes cannot be shown`。本端以前只维护一套，正文里会印出
 * 卡片标签那种断句。这里把两套各自钉住，避免再被合并回去。
 */
class DeliverablesCopyTest {

    private fun withLocale(locale: AppLocale, block: () -> Unit) {
        val previous = Strings.locale
        Strings.locale = locale
        try { block() } finally { Strings.locale = previous }
    }

    @Test
    fun changedFilesCardFollowsUpstreamChangesNamespace() {
        withLocale(AppLocale.ZH) {
            assertEquals("已编辑 7 个文件", fmt(Strings.changedFilesTitle, 7))
            assertEquals("全部 7 个文件", fmt(Strings.changedFilesShowAll, 7))
            assertEquals("收起", Strings.changedFilesCollapse)
            assertEquals("二进制", Strings.changedFilesBinary)
            assertEquals("过大", Strings.changedFilesOversized)
        }
        withLocale(AppLocale.EN) {
            assertEquals("Edited 7 files", fmt(Strings.changedFilesTitle, 7))
            assertEquals("All 7 files", fmt(Strings.changedFilesShowAll, 7))
            assertEquals("Collapse", Strings.changedFilesCollapse)
            assertEquals("binary", Strings.changedFilesBinary)
            assertEquals("too large", Strings.changedFilesOversized)
        }
    }

    @Test
    fun diffBodyFollowsUpstreamDiffNamespace() {
        withLocale(AppLocale.ZH) {
            assertEquals("二进制文件，无法显示改动", Strings.changedDiffBinary)
            assertEquals("文件过大，无法显示改动", Strings.changedDiffOversized)
            assertEquals("两侧内容相同", Strings.changedDiffUnchanged)
            assertEquals("逐行对比超时，按整个文件替换显示", Strings.changedDiffCoarse)
        }
        withLocale(AppLocale.EN) {
            assertEquals("Binary file; changes cannot be shown", Strings.changedDiffBinary)
            assertEquals("File too large; changes cannot be shown", Strings.changedDiffOversized)
            assertEquals("Both sides hold the same lines", Strings.changedDiffUnchanged)
            assertEquals(
                "Line comparison timed out; shown as a whole-file replacement",
                Strings.changedDiffCoarse,
            )
        }
    }

    @Test
    fun presentedCardsFollowUpstreamPresentedNamespace() {
        withLocale(AppLocale.ZH) {
            assertEquals("全部 4 个文件", fmt(Strings.presentedAll, 4))
            assertEquals("收起", Strings.presentedCollapse)
            assertEquals("文件", Strings.presentedFileKind)
        }
        withLocale(AppLocale.EN) {
            assertEquals("All 4 files", fmt(Strings.presentedAll, 4))
            assertEquals("Collapse", Strings.presentedCollapse)
            assertEquals("File", Strings.presentedFileKind)
        }
    }

    @Test
    fun presentedDeclarationsLandOnTheirTurn() {
        val store = SessionStore("s1")
        fun presented(seq: Int, turn: Int, files: String) = SessionEvent(
            "deliverables/presented", seq, seq.toDouble(),
            Json.parseToJsonElement("""{"turn":$turn,"files":$files}"""),
        )
        store.apply(presented(1, 3, """[{"path":"a/one.md","description":"第一份"},{"path":"a/two.md"}]"""), null)
        // 同一路径后来的声明取代先前的（web 按 path 归一，卡片不出现两张同文件的卡）。
        store.apply(presented(2, 3, """[{"path":"a/one.md","description":"改过的说明"}]"""), null)
        store.apply(presented(3, 4, """[{"path":"b/three.md"}]"""), null)

        val turn3 = store.presentedFiles(3)
        assertEquals(listOf("a/one.md", "a/two.md"), turn3.map { it.path })
        assertEquals("改过的说明", turn3.first().description)
        assertEquals(listOf("b/three.md"), store.presentedFiles(4).map { it.path })
        assertTrue("没有声明的一轮不挂卡片", store.presentedFiles(5).isEmpty())
    }
}
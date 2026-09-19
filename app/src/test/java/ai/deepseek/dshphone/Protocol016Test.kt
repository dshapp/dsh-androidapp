package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.*
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.1.6 协议适配的握手测试：每条断言都对着主机真实的 wire 形状。
 *
 * 只覆盖纯函数（inbox 投影解码与 placement 推导、权限目录合并、改动汇总与逐文件比较），
 * 不碰 Compose 与网络，因此能在 JVM 上直接跑。
 */
class Protocol016Test {

    private fun check(name: String, ok: Boolean, detail: String = "") {
        assertTrue("$name $detail", ok)
    }

    @Test
    fun protocol016Decoders() {
        // —— P0-1: 0.1.6 inbox 投影 ——
        val inboxJson = Json.parseToJsonElement(
            """
            {
              "next-turn": [
                {"id":"m1","role":"user","content":[{"type":"text","text":"排队一"}],
                 "source":{"kind":"user","rpcId":"rpc-1"}},
                {"id":"m2","role":"user","content":[{"type":"text","text":"排队二"}],
                 "source":{"kind":"user"}}
              ],
              "next-step": [
                {"id":"m3","role":"user","content":[{"type":"text","text":"插队"}],
                 "source":{"kind":"user"}},
                {"id":"m4","role":"user","content":[{"type":"text","text":"注入"}],
                 "source":{"kind":"plugin","plugin":"recall"}}
              ]
            }
            """.trimIndent()
        )
        val inbox = decodeInbox(inboxJson)
        check("inbox decodes both lists", inbox != null && inbox.nextTurn.size == 2 && inbox.nextStep.size == 2)
        val rows = rowsFromInbox(inbox!!)
        check("inbox placement derivation", rows.map { it.id to it.placement } ==
            listOf("m1" to "queued", "m2" to "queued", "m3" to "steering", "m4" to "context"),
            rows.map { it.id to it.placement }.toString())
        check("inbox rpcId from source.kind=user", rows.first().rpcId == "rpc-1" && rows[1].rpcId == null)
        check("inbox text from content", rows.first().text == "排队一", rows.first().text)
        check("inbox handles null", decodeInbox(null) == null)
        check("inbox tolerates missing keys", decodeInbox(Json.parseToJsonElement("{}"))?.nextTurn?.isEmpty() == true)

        // 0.1.5 的 control queue 条目仍要能解（双版本兼容）
        val oldItem = decodeQueuedItem(Json.parseToJsonElement(
            """{"id":"q1","placement":"steering","rpcId":"rpc-old","message":{"content":[{"type":"text","text":"旧"}]}}"""
        ))
        check("0.1.5 queue item still decodes", oldItem != null && oldItem.placement == "steering" &&
            oldItem.rpcId == "rpc-old" && oldItem.text == "旧")

        // 0.1.6 的 control 基线里没有 queues；基线只该有 jobs/projections
        val baseline = decodeControlFrame(Json.parseToJsonElement(
            """{"type":"baseline","value":{"jobs":{},"projections":{}}}"""
        ))
        check("0.1.6 control baseline decodes without queues",
            baseline is SessionControlFrame.Baseline && (baseline as SessionControlFrame.Baseline).queues.isEmpty())
        // 0.1.6 不再发 queue 帧，但解码路径要留着：老主机仍靠它送队列，
        // 而缺字段的帧必须解成空列表而不是抛异常。
        val queueFrame = decodeControlFrame(Json.parseToJsonElement("""{"type":"queue","sessionId":"s"}"""))
        check("0.1.5 queue frame still decodes, empty by default",
            queueFrame is SessionControlFrame.Queue && (queueFrame as SessionControlFrame.Queue).frame.items.isEmpty(),
            queueFrame.toString())

        // —— P0-2: 权限目录 ——
        val narrowed = Json.parseToJsonElement("""{"currentValue":"auto"}""")
            .decodeOrNull<PermissionSelectValue>()
        check("0.1.6 narrow permissions projection decodes", narrowed != null &&
            narrowed!!.currentValue == "auto" && narrowed.options.isEmpty())
        val legacy = Json.parseToJsonElement(
            """{"currentValue":"auto","options":[{"value":"auto","name":"Auto"},{"value":"custom","name":"Custom"}]}"""
        ).decodeOrNull<PermissionSelectValue>()
        check("0.1.5 inline options still decode", legacy?.options?.size == 2)
        val projected = PermissionSelection.merge(narrowed, listOf(
            PermissionPresetOption("plan", "Plan"), PermissionPresetOption("auto", "Auto"),
            PermissionPresetOption("custom", "Custom"),
        ))
        check("catalog fills the narrowed projection", projected?.currentValue == "auto" &&
            projected!!.options.size == 3 && projected.currentOption?.name == "Auto")
        check("custom is not selectable", projected!!.selectableOptions.map { it.value } == listOf("plan", "auto"))
        val fallback = PermissionSelection.merge(legacy, null)
        check("no catalog falls back to inline options", fallback?.options?.size == 2)
        check("no projection means no selection", PermissionSelection.merge(null, listOf()) == null)
        val catalog = Json.parseToJsonElement("""{"options":[{"value":"plan","name":"Plan","description":"d"}]}""")
            .decodeOrNull<PermissionCatalogValue>()
        check("catalog value decodes", catalog?.options?.firstOrNull()?.value == "plan")

        // —— P1-1: changes summary / diff ——
        val summaryJson = Json.parseToJsonElement(
            """
            {"turn":3,"total":4,
             "files":[
               {"path":"a/One.kt","display":"a/One.kt","added":10,"deleted":2},
               {"path":"a/Two.png","display":"a/Two.png","added":0,"deleted":0,"binary":true},
               {"path":"a/Big.bin","display":"a/Big.bin","added":0,"deleted":0,"oversized":true},
               {"path":"","display":"bad"}
             ],
             "added":10,"deleted":2}
            """.trimIndent()
        )
        val summary = TurnChangesSummary.decode(summaryJson)
        check("summary decodes", summary != null && summary.turn == 3 && summary.total == 4 &&
            summary.added == 10 && summary.deleted == 2)
        check("malformed file row is dropped", summary!!.files.size == 3)
        check("binary flag", summary.files[1].binary && !summary.files[1].oversized)
        check("oversized flag", summary.files[2].oversized)
        check("summary rejects missing turn", TurnChangesSummary.decode(Json.parseToJsonElement("""{"total":0}""")) == null)
        check("summary rejects turn 0", TurnChangesSummary.decode(
            Json.parseToJsonElement("""{"turn":0,"total":0,"added":0,"deleted":0,"files":[]}""")) == null)

        val textDiff = ChangedFileDiff.decode(Json.parseToJsonElement(
            """{"kind":"text","path":"a/One.kt","display":"a/One.kt","before":true,"after":true,
                "coarse":false,"hunks":[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":1,"lines":["-old","+new"]}]}"""
        ))
        check("text diff decodes", textDiff is ChangedFileDiff.Text &&
            (textDiff as ChangedFileDiff.Text).hunks.single().lines == listOf("-old", "+new"))
        check("binary diff decodes", ChangedFileDiff.decode(
            Json.parseToJsonElement("""{"kind":"binary","path":"p","display":"d"}""")) is ChangedFileDiff.Binary)
        check("oversized diff decodes", ChangedFileDiff.decode(
            Json.parseToJsonElement("""{"kind":"oversized","path":"p","display":"d"}""")) is ChangedFileDiff.Oversized)
        check("unknown diff kind is rejected", ChangedFileDiff.decode(
            Json.parseToJsonElement("""{"kind":"weird","path":"p","display":"d"}""")) == null)

        // —— P1-2: 归档集合 ——
        val archived = (Json.parseToJsonElement("""{"archivedSessionIds":["s1","s2"]}""")["archivedSessionIds"].arr ?: emptyList())
            .mapNotNull { it.str }
        check("unarchive result shape", archived == listOf("s1", "s2"))


    }
}
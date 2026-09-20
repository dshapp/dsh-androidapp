package ai.deepseek.dshphone

import ai.deepseek.dshphone.i18n.AppLocale
import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.model.HistoryEntry
import ai.deepseek.dshphone.model.SessionEvent
import ai.deepseek.dshphone.model.turnFailureNotificationBody
import ai.deepseek.dshphone.net.ApiClient
import ai.deepseek.dshphone.net.MobileTarget
import ai.deepseek.dshphone.net.Noise
import ai.deepseek.dshphone.state.AttentionSignal
import ai.deepseek.dshphone.state.SessionListStore
import ai.deepseek.dshphone.state.SessionStore
import ai.deepseek.dshphone.state.StreamCoordinator
import ai.deepseek.dshphone.state.WorkspaceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject as obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实时 follow 流上的一轮失败必须当场发出失败事实，失败卡的正文要是主机给的原因。
 *
 * Mac 在 [StreamCoordinator.applyLiveEvent] 折叠之前就读 `liveTurnFailureMessage(event)`
 * 并 `notifyAttention(.failed(...))`，注释写明这条"失败 peek 搭在帧上"：正是用户切走
 * 的那条会话，它的失败最需要一张通知卡。安卓只在 `$events` 的 `api-session/error` 上
 * 发失败事实，实时帧这一路完全没有——两条流是独立的，只认前者等于把"有没有失败卡"
 * 押在另一条流的送达与顺序上。而发出来之后，正文又被写死成"这一轮失败"，
 * 把主机给出的原因（Mac `failureBody`）整条丢掉。
 */
class LiveTurnFailureSignalTest {

    private fun turnEnd(turn: Int, seq: Int, message: String?): HistoryEntry = HistoryEntry(
        SessionEvent(
            "turn/end", seq, seq.toDouble(),
            obj {
                put("turn", turn)
                put(
                    "reason",
                    buildJsonObject {
                        put("kind", "error")
                        put(
                            "error",
                            buildJsonObject {
                                put("code", "AUTH")
                                if (message != null) put("message", message)
                            },
                        )
                    },
                )
            },
        ),
        null,
    )

    private fun coordinatorWith(signals: MutableList<AttentionSignal>): Pair<StreamCoordinator, SessionStore> {
        val store = SessionStore("s1")
        val target = MobileTarget(
            host = "127.0.0.1",
            port = 1,
            bridgeKey = ByteArray(Noise.KEY_LENGTH),
            device = Noise.KeyPair(ByteArray(Noise.KEY_LENGTH), ByteArray(Noise.KEY_LENGTH)),
            pairingToken = null,
        )
        val coordinator = StreamCoordinator(
            api = ApiClient(target),
            scope = CoroutineScope(Dispatchers.Unconfined),
            cache = null,
            listStore = SessionListStore(null),
            workspaceStore = WorkspaceStore(),
            sessionLookup = { if (it == store.sessionId) store else null },
            sessionUpsert = { store },
            allSessions = { listOf(store) },
            remoteEventHandler = {},
            sessionRemovedHandler = {},
            sessionsArchivedHandler = {},
            resyncHandler = {},
            gapRepairLoader = { _, _ -> error("no gap repair expected") },
            attentionHandler = { signals.add(it) },
            sessionIsOnScreen = { false },
        )
        return coordinator to store
    }

    private fun withLocale(locale: AppLocale, block: () -> Unit) {
        val previous = Strings.locale
        Strings.locale = locale
        try { block() } finally { Strings.locale = previous }
    }

    @Test
    fun liveFailedTurnSignalsFailureWithTheLocalizedReason() {
        withLocale(AppLocale.ZH) {
            val signals = mutableListOf<AttentionSignal>()
            val (coordinator, store) = coordinatorWith(signals)
            runBlocking {
                coordinator.applyLiveEvent(store.sessionId, turnEnd(1, 2, "401 Unauthorized: bad key"))
            }
            val failed = signals.filterIsInstance<AttentionSignal.Failed>()
            assertEquals("实时帧上的一轮失败必须发出一条失败事实", 1, failed.size)
            assertEquals(
                "AUTH 失败要换成随界面语言变化的本地化说法，而不是 provider 原文",
                "API 密钥无效",
                failed.single().message,
            )
        }
    }

    @Test
    fun failureNotificationBodyKeepsTheHostReason() {
        withLocale(AppLocale.ZH) {
            assertEquals(
                "失败卡的正文必须是主机给出的原因，而不是固定文案",
                "401 Unauthorized: bad key",
                turnFailureNotificationBody("401 Unauthorized: bad key"),
            )
            assertEquals(
                "原因只有空白时退回本地化兜底（Mac failureBody）",
                "智能体因错误中止。",
                turnFailureNotificationBody("   "),
            )
        }
    }

    @Test
    fun nonErrorTurnEndSignalsNoFailure() = runBlocking {
        val signals = mutableListOf<AttentionSignal>()
        val (coordinator, store) = coordinatorWith(signals)
        coordinator.applyLiveEvent(
            store.sessionId,
            HistoryEntry(
                SessionEvent(
                    "turn/end", 2, 2.0,
                    obj { put("turn", 1); put("reason", obj { put("kind", "completed") }) },
                ),
                null,
            ),
        )
        assertTrue("正常的轮次收尾不该冒出失败卡", signals.none { it is AttentionSignal.Failed })
    }
}
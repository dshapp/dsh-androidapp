package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.HistoryEntry
import ai.deepseek.dshphone.model.SessionEvent
import ai.deepseek.dshphone.net.ApiClient
import ai.deepseek.dshphone.net.MobileTarget
import ai.deepseek.dshphone.net.Noise
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 未读失败红点只该在**屏幕之外**出错时亮着。
 *
 * 红点本身是 [SessionStore.unreadError]（\`lastTurnErrored && lastErroredTurn != seenErroredTurn\`）。
 * [StreamCoordinator] 以前在每一条实时帧折叠完之后无条件调 \`markErrorSeen()\`——包括
 * 那些用户根本没打开、只是在后台跟着 follow 流的会话。于是失败位刚抬起来就被自己
 * 抹平，列表行那颗红点永远不会亮：用户在别处时某条会话跑挂了，回来只会看到一条
 * 安静的会话（Mac 不会：它在折叠之前就把不在屏的整帧丢掉，所以那里的 markErrorSeen
 * 只可能作用于屏上的会话）。
 */
class UnreadErrorGateTest {

    /** 一条失败轮次的 turn/end。 */
    private fun failedTurnEnd(turn: Int, seq: Int): HistoryEntry = HistoryEntry(
        SessionEvent(
            "turn/end", seq, seq.toDouble(),
            obj {
                put("turn", turn)
                put(
                    "reason",
                    buildJsonObject {
                        put("kind", "error")
                        put("error", buildJsonObject { put("message", "boom") })
                    },
                )
            },
        ),
        null,
    )

    /** 已经跑完一轮失败（turn 1）的会话；红点此刻是"已读"的。 */
    private fun seenFailedStore(): SessionStore {
        val store = SessionStore("s1")
        store.apply(SessionEvent("turn/start", 1, 1.0, obj { put("turn", 1) }), null)
        store.apply(failedTurnEnd(1, 2).event, null)
        store.markErrorSeen()
        return store
    }

    private fun coordinatorFor(store: SessionStore, onScreen: (String) -> Boolean): StreamCoordinator {
        val target = MobileTarget(
            host = "127.0.0.1",
            port = 1,
            bridgeKey = ByteArray(Noise.KEY_LENGTH),
            device = Noise.KeyPair(ByteArray(Noise.KEY_LENGTH), ByteArray(Noise.KEY_LENGTH)),
            pairingToken = null,
        )
        return StreamCoordinator(
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
            attentionHandler = {},
            sessionIsOnScreen = onScreen,
        )
    }

    @Test
    fun failureStaysUnreadWhileTheSessionIsOffScreen() = runBlocking {
        val store = seenFailedStore()
        val coordinator = coordinatorFor(store) { false }
        // 用户正在别处，这条会话的第 2 轮跑挂了。
        coordinator.applyLiveEvent(store.sessionId, failedTurnEnd(2, 3))
        assertTrue("后台会话的失败必须留成未读，否则列表行永远不亮红点", store.unreadError)
    }

    @Test
    fun failureIsMarkedSeenWhileTheSessionIsOnScreen() = runBlocking {
        val store = seenFailedStore()
        val coordinator = coordinatorFor(store) { true }
        coordinator.applyLiveEvent(store.sessionId, failedTurnEnd(2, 3))
        assertFalse("正看着的会话不该留红点", store.unreadError)
    }
}

package ai.deepseek.dshphone

import ai.deepseek.dshphone.net.DshJson
import ai.deepseek.dshphone.net.MuxStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探活帧必须落在主机认识的上行帧集合里。
 *
 * 主机 dsh-api-gateway 的 parseRemoteStreamClientMessage 只接受两种帧：
 * `{type:"cancel",streamId}`（键恰好这两个）与 `{type:"open",streamId,endpoint,payload}`；
 * 其余一律 throw，socket 的 message 处理器 catch 后直接 close(1008,
 * "invalid Remote stream request")。回前台的探活因此会把一条健康连接拆掉：runLoop 进入
 * RECONNECTING 退避，且非 CONNECTED 转换会 dropStaleInteractions()，清空待审批/待回答，
 * 而 follow 的 snapshot 并不重送。
 *
 * 这里把主机的校验原样搬进测试：`{"type":"ping"}` 不是 cancel 也不是 open，改前必红。
 */
class MuxProbeFrameTest {

    /** 主机 parseRemoteStreamClientMessage 的口径：cancel 的键恰好 {type,streamId} 且 id 非空。 */
    private fun hostAcceptsCancel(text: String): Boolean {
        val obj = DshJson.parseToJsonElement(text) as? JsonObject ?: return false
        if (obj["type"]?.jsonPrimitive?.content != "cancel") return false
        if (obj.keys != setOf("type", "streamId")) return false
        return !obj["streamId"]?.jsonPrimitive?.content.isNullOrEmpty()
    }

    /** 主机对 open 的口径：键恰好四个，endpoint 非空。 */
    private fun hostAcceptsOpen(text: String): Boolean {
        val obj = DshJson.parseToJsonElement(text) as? JsonObject ?: return false
        if (obj["type"]?.jsonPrimitive?.content != "open") return false
        if (obj.keys != setOf("type", "streamId", "endpoint", "payload")) return false
        return !obj["streamId"]?.jsonPrimitive?.content.isNullOrEmpty() &&
            !obj["endpoint"]?.jsonPrimitive?.content.isNullOrEmpty()
    }

    private fun probeFrame(): String = MuxStream("ws://x", okhttp3.OkHttpClient(), CoroutineScope(Dispatchers.Unconfined)).probeFrame()

    @Test
    fun probeFrameIsAHostAcceptedUplinkFrame() {
        val frame = probeFrame()
        assertTrue(
            "探活帧 `" + frame + "` 既不是 cancel 也不是 open，主机会 close(1008) 拆掉整代连接",
            hostAcceptsCancel(frame) || hostAcceptsOpen(frame),
        )
    }

    @Test
    fun probeFrameIsACancelThatCannotHitARealStream() {
        // 空 cancel：主机对未知 streamId 的 cancel 是 no-op（streams.get(id)?.abort），
        // 所以它不会打断任何在跑的流。
        val frame = probeFrame()
        assertTrue("探活应当用空 cancel 帧，实际是 " + frame, hostAcceptsCancel(frame))
    }
}

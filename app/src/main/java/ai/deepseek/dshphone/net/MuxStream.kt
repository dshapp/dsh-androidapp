package ai.deepseek.dshphone.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

/** 连接状态：与 iOS 的 WSConnectionState 同义。 */
enum class WsConnectionState { CONNECTED, RECONNECTING }

/** 多路流上的一帧。 */
sealed interface MuxEvent {
    data class Item(val streamId: String, val value: JsonElement) : MuxEvent
    data class Failure(val streamId: String, val error: RpcError) : MuxEvent
    data class End(val streamId: String) : MuxEvent
    /** 新一代连接已就绪，所有注册流都已重新 open。 */
    data object Opened : MuxEvent
}

private data class MuxSubscription(val streamId: String, val endpoint: String, val args: JsonElement)

/**
 * 唯一的多路下行 WebSocket（/api/remote.mux）。
 *
 * 上行：{type:"open",streamId,endpoint,payload} | {type:"cancel",streamId}
 * 下行：{type:"item"|"error"|"end",streamId,...}
 *
 * 断线自动重连（指数退避 + 抖动）；每一代连接建立后重新 open 所有已注册的流，
 * 所以"重连"等同于"重新订阅"而不是"重新同步"。
 */
class MuxStream(
    private val wsBaseUrl: String,
    private val client: okhttp3.OkHttpClient,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "MuxStream"
        private const val PATH = "api/remote.mux"
        private const val BACKOFF_BASE_MS = 500L
        private const val BACKOFF_CAP_MS = 10_000L
        private const val STABLE_GENERATION_MS = 30_000L
    }

    private val events = MutableSharedFlow<MuxEvent>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val stream: SharedFlow<MuxEvent> = events

    var onStateChange: ((WsConnectionState) -> Unit)? = null

    private val lock = Any()
    private val subscriptions = LinkedHashMap<String, MuxSubscription>()
    private var openedStreamIds = mutableSetOf<String>()
    private var generationOpen = false
    private var socket: WebSocket? = null
    private var runJob: Job? = null
    private var lastState: WsConnectionState? = null
    @Volatile private var stopped = false

    fun start() {
        synchronized(lock) {
            if (runJob != null) return
            runJob = scope.launch { runLoop() }
        }
    }

    fun stop() {
        stopped = true
        onStateChange = null
        synchronized(lock) {
            socket?.close(1000, null)
            socket = null
            runJob?.cancel()
            runJob = null
            generationOpen = false
            openedStreamIds.clear()
        }
    }

    /** 注册一个流：立刻 open，并在之后每一代连接上自动重开。幂等。 */
    fun subscribe(streamId: String, endpoint: String, args: JsonElement = JsonObject(emptyMap())) {
        val subscription = MuxSubscription(streamId, endpoint, args)
        var action = 0 // 0 none, 1 open, 2 replace
        var target: WebSocket? = null
        synchronized(lock) {
            val existing = subscriptions[streamId]
            subscriptions[streamId] = subscription
            target = socket
            if (generationOpen) {
                action = when {
                    existing != null && existing != subscription -> 2
                    openedStreamIds.add(streamId) -> 1
                    else -> 0
                }
            }
        }
        val ws = target ?: return
        when (action) {
            1 -> sendOpen(subscription, ws)
            2 -> { sendCancel(streamId, ws); sendOpen(subscription, ws) }
        }
    }

    /** 取消一个流并停止重开。 */
    fun unsubscribe(streamId: String) {
        val ws: WebSocket?
        synchronized(lock) {
            subscriptions.remove(streamId)
            openedStreamIds.remove(streamId)
            ws = socket
        }
        ws?.let { sendCancel(streamId, it) }
    }

    fun isSubscribed(streamId: String): Boolean = synchronized(lock) { subscriptions.containsKey(streamId) }

    /** 立刻探活当前这一代连接（回到前台时用）。 */
    fun probeNow() {
        val ws = synchronized(lock) { socket } ?: return
        // OkHttp 没有显式 ping API，这里发一个空 cancel 帧探活；失败会触发 onFailure。
        if (!ws.send("{\"type\":\"ping\"}")) {
            Log.w(TAG, "wake probe failed; failing the generation")
            ws.cancel()
        }
    }

    private fun sendOpen(subscription: MuxSubscription, ws: WebSocket) {
        val frame = buildJsonObject {
            put("type", "open")
            put("streamId", subscription.streamId)
            put("endpoint", subscription.endpoint)
            put("payload", buildJsonObject { put("args", subscription.args) })
        }
        ws.send(DshJson.encodeToString(JsonElement.serializer(), frame))
    }

    private fun sendCancel(streamId: String, ws: WebSocket) {
        val frame = buildJsonObject {
            put("type", "cancel")
            put("streamId", streamId)
        }
        ws.send(DshJson.encodeToString(JsonElement.serializer(), frame))
    }

    private fun emitState(state: WsConnectionState) {
        val changed = synchronized(lock) {
            if (lastState == state) false else { lastState = state; true }
        }
        if (changed) onStateChange?.invoke(state)
    }

    private suspend fun runLoop() {
        var backoff = BACKOFF_BASE_MS
        while (!stopped) {
            val url = "${wsBaseUrl.trimEnd('/')}/$PATH"
            val openedAt = System.currentTimeMillis()
            val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val request = Request.Builder().url(url).build()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "mux generation opened (http ${response.code})")
                    val pending = synchronized(lock) {
                        socket = webSocket
                        generationOpen = true
                        openedStreamIds = subscriptions.keys.toMutableSet()
                        subscriptions.values.toList()
                    }
                    for (item in pending) sendOpen(item, webSocket)
                    scope.launch { events.emit(MuxEvent.Opened) }
                    emitState(WsConnectionState.CONNECTED)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = runCatching { DshJson.parseToJsonElement(text) }.getOrNull() ?: return
                    val streamId = frame["streamId"].str ?: ""
                    when (frame["type"].str) {
                        "item" -> scope.launch {
                            events.emit(MuxEvent.Item(streamId, frame["value"] ?: kotlinx.serialization.json.JsonNull))
                        }
                        "error" -> scope.launch {
                            events.emit(
                                MuxEvent.Failure(
                                    streamId,
                                    frame["error"]?.decodeOrNull<RpcError>()
                                        ?: RpcError("internal", "stream failed")
                                )
                            )
                        }
                        "end" -> scope.launch { events.emit(MuxEvent.End(streamId)) }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // 带异常类名：以前只打 message，DNS 失败（UnknownHostException 的 message
                    // 就是主机名）和握手/网络被掐看起来一模一样，排查时无从下手。
                    Log.w(
                        TAG,
                        "mux generation failed: ${t.javaClass.simpleName}: ${t.message}" +
                            (response?.let { " (http ${it.code})" } ?: ""),
                    )
                    closed.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(Unit)
                }
            }
            val ws = client.newWebSocket(request, listener)
            closed.await()
            ws.cancel()
            synchronized(lock) {
                if (socket === ws) socket = null
                generationOpen = false
                openedStreamIds.clear()
            }
            if (stopped) return
            val lasted = System.currentTimeMillis() - openedAt
            if (lasted >= STABLE_GENERATION_MS) backoff = BACKOFF_BASE_MS
            emitState(WsConnectionState.RECONNECTING)
            val capped = minOf(backoff, BACKOFF_CAP_MS)
            val jittered = capped / 2 + Random.nextLong(capped / 2 + 1)
            delay(jittered)
            backoff = minOf(backoff * 2, BACKOFF_CAP_MS)
        }
    }
}

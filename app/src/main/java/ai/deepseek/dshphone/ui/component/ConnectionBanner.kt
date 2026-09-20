package ai.deepseek.dshphone.ui.component

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.net.WsConnectionState
import ai.deepseek.dshphone.state.Connection
import ai.deepseek.dshphone.state.PhoneModel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * App 顶部的全局连接提示条。
 *
 * 悬浮在导航内容之上、任何页面都能看到：会话列表、会话详情、设置页都一样，
 * 用户在任何位置都能立刻知道"现在没连上"，而不是等到发消息才弹一个看不懂的报错。
 *
 * 形态是一条贴着状态栏下沿的单行小胶囊：只有图标 + 一句结论（+ 重试），宽高都收
 * 到内容大小。它是盖在内容上的，越矮越窄就遮得越少——所以这里不放说明文字：连不上
 * 时那句"请确认电脑上的 DSH 正在运行"由列表页的居中空状态去讲（见 SessionListScreen
 * 的 StatusOverlay），提示条只负责把状态本身和一次重试摆到眼前。
 *
 * 往上滑可以把它滑掉，见 [ConnectionBanner] 里的 [DISMISS_DP]。
 *
 * 文案一律走用户可读的 [Strings]，底层异常只进日志（见 PhoneModel.describe）。
 */
@Composable
fun ConnectionBanner(model: PhoneModel, modifier: Modifier = Modifier) {
    val info = bannerInfo(model)
    // 退场动画期间新状态会先变成 null，缓存最后一次的非空值，免得那条提示条在动画
    // 里先被替换成别的文案再滑走。
    var lastInfo by remember { mutableStateOf<BannerInfo?>(null) }
    if (info != null) lastInfo = info

    // 用户往上滑掉的是"这一条"：只要状态还是同一个 key 就不再冒出来（连不上就一直
    // 连不上时，别每次都把已经看过的提示重新怼回眼前）。状态换成另一个 key——比如
    // "连接中"变成"连不上"——是新的消息，照常显示。
    var dismissedKey by rememberSaveable { mutableStateOf<String?>(null) }
    // 恢复健康（无从显示）时清掉记录：等它下次再出事，是一次新的故障，该重新说一遍。
    LaunchedEffect(info) { if (info == null) dismissedKey = null }

    val show = info != null && info.key != dismissedKey

    AnimatedVisibility(
        visible = show,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        lastInfo?.let {
            BannerContent(
                info = it,
                onRetry = { model.connectCurrent() },
                onDismiss = { dismissedKey = it.key },
            )
        }
    }
}

/**
 * 往上滑过这个距离就滑掉。它同时是拖动位移的标尺：跟手时越往上越透明。
 * 取 24dp——比触发滚动的 touch slop（约 8dp）大一档，误触不至于把提示条弄丢。
 */
private val DISMISS_DP = 24.dp

/** 提示条此刻该显示的内容；null 表示不显示。 */
private data class BannerInfo(
    val icon: ImageVector,
    val title: String,
    val container: Color,
    val content: Color,
    val canRetry: Boolean,
    /** 这一条的身份：内容变了 key 才变，滑掉的是同一个 key。 */
    val key: String,
)

@Composable
private fun bannerInfo(model: PhoneModel): BannerInfo? {
    val connection = model.connection
    // 一条配对都没有（首配引导页）或刚拆掉连接的瞬时状态：不打扰。
    if (connection is Connection.Idle) return null
    // 重连中优先于"连不上"：底层 WebSocket 会自己接回来，这时给"连接中"的中性提示条，
    // 而不是把失败态画出来——否则用户会以为已经没救了，去做多余的操作。
    if (model.socketState == WsConnectionState.RECONNECTING || connection is Connection.Connecting) {
        return connectingBanner()
    }
    return when (connection) {
        // 连不上：最需要被看见的状态，结论加一次重试都在这一行里。
        is Connection.Failed -> offlineBanner()
        is Connection.Connected, is Connection.Connecting, is Connection.Idle -> null
    }
}

/** 正在连接/重连：一条轻量的状态说明，不抢视线，也不用给重试。 */
@Composable
private fun connectingBanner(): BannerInfo = BannerInfo(
    icon = Icons.Default.Sync,
    title = Strings.statusConnecting,
    container = MaterialTheme.colorScheme.surfaceVariant,
    content = MaterialTheme.colorScheme.onSurfaceVariant,
    canRetry = false,
    key = "connecting",
)

/** 真的连不上：说清结论并给一次重试；整条胶囊就是重试的点击区。 */
@Composable
private fun offlineBanner(): BannerInfo = BannerInfo(
    icon = Icons.Default.WarningAmber,
    title = Strings.offlineBannerTitle,
    container = MaterialTheme.colorScheme.errorContainer,
    content = MaterialTheme.colorScheme.onErrorContainer,
    canRetry = true,
    key = "offline",
)

@Composable
private fun BannerContent(info: BannerInfo, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val dismissPx = with(LocalDensity.current) { DISMISS_DP.toPx() }
    // 手指往上带出来的位移，只往负方向走：胶囊跟着手指走，松手不过线就弹回去。
    var drag by remember { mutableFloatStateOf(0f) }

    Surface(
        // 宽度交给内容（不再通栏），高度只由这一行文字决定：两条轴上都尽量少盖住东西。
        modifier = Modifier
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            // 长语种的标题也撑不成通栏：到顶就省略号，保持「一条小胶囊」的形状。
            .widthIn(max = 320.dp)
            .offset { IntOffset(0, drag.roundToInt()) }
            // 越往上越淡：滑到消失线时正好快看不见，"滑走"这件事有反馈。
            .alpha((1f + drag / (dismissPx * 2f)).coerceIn(0.25f, 1f))
            // 拖动挂在 Surface 上、点击留在里面的 Row 上：点按走重试，纵向拖动被这里
            // 接住（子节点先收到事件，超过 touch slop 后它自己会取消点击）。
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, delta ->
                        // 只收上滑：下拉不该把这条提示拽到状态栏里去。
                        val next = (drag + delta).coerceAtMost(0f)
                        if (next != drag) {
                            drag = next
                            change.consume()
                        }
                    },
                    onDragEnd = {
                        if (-drag >= dismissPx) onDismiss() else drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                )
            },
        shape = RoundedCornerShape(percent = 50),
        color = info.container,
        contentColor = info.content,
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier
                .clickable(enabled = info.canRetry, role = Role.Button, onClick = onRetry)
                .padding(horizontal = 9.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(info.icon, null, Modifier.size(13.dp))
            Text(
                info.title,
                fontSize = 11.5.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (info.canRetry) {
                Text(
                    Strings.retry,
                    fontSize = 11.5.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

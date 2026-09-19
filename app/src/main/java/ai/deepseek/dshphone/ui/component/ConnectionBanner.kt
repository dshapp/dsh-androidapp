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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * App 顶部的全局连接提示条。
 *
 * 悬浮在导航内容之上、任何页面都能看到：会话列表、会话详情、设置页都一样，
 * 用户在任何位置都能立刻知道"现在没连上"，而不是等到发消息才弹一个看不懂的报错。
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

    AnimatedVisibility(
        visible = info != null,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        lastInfo?.let { BannerContent(it, onRetry = { model.connectCurrent() }) }
    }
}

/** 提示条此刻该显示的内容；null 表示不显示。 */
private data class BannerInfo(
    val icon: ImageVector,
    val title: String,
    val body: String?,
    val container: Color,
    val content: Color,
    val canRetry: Boolean,
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
        // 连不上：最需要被看见的状态，给标题、说明和重试。
        is Connection.Failed -> offlineBanner()
        is Connection.Connected, is Connection.Connecting, is Connection.Idle -> null
    }
}

/** 正在连接/重连：一条轻量的状态说明，不抢视线，也不用给重试。 */
@Composable
private fun connectingBanner(): BannerInfo = BannerInfo(
    icon = Icons.Default.Sync,
    title = Strings.statusConnecting,
    body = null,
    container = MaterialTheme.colorScheme.surfaceVariant,
    content = MaterialTheme.colorScheme.onSurfaceVariant,
    canRetry = false,
)

/** 真的连不上：标题条里说清原因并给一次重试。 */
@Composable
private fun offlineBanner(): BannerInfo = BannerInfo(
    icon = Icons.Default.WarningAmber,
    title = Strings.offlineBannerTitle,
    body = Strings.offlineBannerBody,
    container = MaterialTheme.colorScheme.errorContainer,
    content = MaterialTheme.colorScheme.onErrorContainer,
    canRetry = true,
)

@Composable
private fun BannerContent(info: BannerInfo, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = info.container,
        contentColor = info.content,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(info.icon, null, Modifier.size(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    info.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                info.body?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (info.canRetry) {
                TextButton(onClick = onRetry) { Text(Strings.retry) }
            }
        }
    }
}

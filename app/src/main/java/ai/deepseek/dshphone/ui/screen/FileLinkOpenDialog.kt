package ai.deepseek.dshphone.ui.screen

import ai.deepseek.dshphone.ExternalOpenResult
import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.model.FileLinkTarget
import ai.deepseek.dshphone.model.pathBasename
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.state.PhoneModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 文件链接点击后的落地状态。
 *
 * [Creating] 只表示"正在进行"：读回本机是网络往返，空着一段没有任何反馈的时间
 * 就等于回到了"点了没反应"。成功时系统查看器已经在前台，这层弹窗直接消失。
 */
sealed interface FileLinkOpenState {
    /** 这次要打开的文件（会话作用域已在 [FileLinkTarget] 里定下）。 */
    val target: FileLinkTarget

    /** 这次尝试的序号，见 [Creating.attempt]。 */
    val attempt: Long

    /**
     * [attempt] 每次点击都会换一个值。
     *
     * 同一路径连续点两次时 target 是一样的，Compose 的 `LaunchedEffect` 按值比较
     * key、不会重跑；带上尝试序号，失败后再点一次才真的会重新读。
     */
    data class Creating(override val target: FileLinkTarget, override val attempt: Long) : FileLinkOpenState
}

/**
 * 文件链接的落地弹窗。
 *
 * 三个出口：读成功 → 系统查看器接管（本弹窗消失）；主机读不回来 → 给出原因；
 * 设备上没有能接文本的 App → 提示一句并让用户改用内置预览。后两者都留了
 * "内置预览"这条退路，用户仍然读得到内容。
 */
@Composable
fun FileLinkOpenDialog(
    model: PhoneModel,
    sessionId: SessionId,
    state: FileLinkOpenState,
    onDismiss: () -> Unit,
    onFallbackPreview: (FileLinkTarget) -> Unit,
) {
    val target = state.target
    val attempt = state.attempt
    val name = target.path.let { pathBasename(it).ifEmpty { it } }
    // null = 还在读；否则是这次尝试的结论。换一个链接或再点一次都重新开始。
    var outcome by remember(target.path, attempt) { mutableStateOf<ExternalOpenResult?>(null) }

    LaunchedEffect(target, attempt) {
        val result = model.openFileInSystemViewer(sessionId, target)
        if (result is ExternalOpenResult.Opened) onDismiss() else outcome = result
    }

    when (val settled = outcome) {
        null -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(Strings.openInSystemViewer, style = MaterialTheme.typography.bodyMedium)
                }
            },
        )
        else -> {
            val message = when (settled) {
                is ExternalOpenResult.Failed -> settled.message
                else -> Strings.openInSystemViewerUnavailable
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(onClick = { onFallbackPreview(target) }) { Text(Strings.previewFallback) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
                title = {
                    Text(
                        name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace,
                    )
                },
                text = {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}

package ai.deepseek.dshphone.ui

import ai.deepseek.dshphone.i18n.ManagementStrings
import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.state.PhoneModel
import ai.deepseek.dshphone.ui.screen.ChatScreen
import ai.deepseek.dshphone.ui.screen.NewSessionScreen
import ai.deepseek.dshphone.ui.screen.SessionListScreen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 并排布局的起点宽度。600dp 是 Material 的 Medium 窗口档：7 寸以上平板竖屏、折叠屏展开、
 * 绝大多数手机横屏（≥ 640dp）都落在这一档以上，手机竖屏（360–430dp）都在它之下。
 */
val WIDE_LAYOUT_MIN_WIDTH = 600.dp

/** 左侧会话列表的宽度：随窗口变宽而变宽，夹在一个读得下标题、又不挤占详情的区间里。 */
private fun listPaneWidth(total: Dp): Dp = (total * 0.32f).coerceIn(280.dp, 380.dp)

/**
 * 宽屏右侧显示的内容，编码成字符串好放进 rememberSaveable：
 * 「chat:<sessionId>」会话、「new」新建会话、「new-automation」新建定时任务；null 为空白占位。
 * 两个新建值与导航路由同名，窄屏切过去时可以直接当路由用。
 */
object DetailPane {
    const val NEW = "new"
    const val NEW_AUTOMATION = "new-automation"
    private const val CHAT_PREFIX = "chat:"

    fun chat(sessionId: SessionId): String = CHAT_PREFIX + sessionId

    fun chatId(pane: String?): SessionId? =
        pane?.takeIf { it.startsWith(CHAT_PREFIX) }?.removePrefix(CHAT_PREFIX)
}

/**
 * 宽屏的并排页：左边会话列表，右边会话详情（或新建会话、空白占位）。
 *
 * 两栏各自是一份完整的 Scaffold（自带标题栏与系统栏内边距）：左栏吃掉结束边的系统栏/刘海，
 * 右栏吃掉起始边的，彼此不再为对方那一侧重复留白——手机横屏时导航栏、刘海都在侧边，
 * 不消费掉的话两栏会各让一份，白白少掉一截宽度。
 */
@Composable
fun WideSessionLayout(
    model: PhoneModel,
    width: Dp,
    detail: String?,
    onOpenSession: (SessionId) -> Unit,
    onOpenSettings: () -> Unit,
    onNewSession: () -> Unit,
    onOpenArchived: () -> Unit,
    onOpenAutomations: () -> Unit,
    onCloseNew: () -> Unit,
    onCreated: (SessionId) -> Unit,
) {
    val selected = DetailPane.chatId(detail)
    Row(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .width(listPaneWidth(width))
                .fillMaxHeight()
                .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.End)),
        ) {
            SessionListScreen(
                model = model,
                onOpenSession = onOpenSession,
                onOpenSettings = onOpenSettings,
                onNewSession = onNewSession,
                onOpenArchived = onOpenArchived,
                onOpenAutomations = onOpenAutomations,
                selectedSessionId = selected,
            )
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)),
        ) {
            when {
                selected != null -> key(selected) {
                    // 换一条会话就是换一页：滚动位置、查找状态这些页内状态不能沿用上一条的。
                    ChatScreen(
                        model = model,
                        sessionId = selected,
                        onBack = null,
                        onOpenSession = onOpenSession,
                    )
                }
                detail == DetailPane.NEW || detail == DetailPane.NEW_AUTOMATION -> key(detail) {
                    NewSessionScreen(
                        model = model,
                        onBack = onCloseNew,
                        onCreated = onCreated,
                        draft = if (detail == DetailPane.NEW_AUTOMATION) ManagementStrings.newTaskDraft else null,
                    )
                }
                else -> DetailPlaceholder(onNewSession)
            }
        }
    }
}

/** 右侧还没选会话时的占位：一句提示加一个新建入口（对齐 Mac 详情区的空白态）。 */
@Composable
private fun DetailPlaceholder(onNewSession: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat, null,
                    Modifier.size(44.dp), MaterialTheme.colorScheme.outline,
                )
                Text(Strings.selectSessionTitle, style = MaterialTheme.typography.titleMedium)
                Text(
                    Strings.selectSessionHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                FilledTonalButton(onClick = onNewSession) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Text(Strings.newSession, Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

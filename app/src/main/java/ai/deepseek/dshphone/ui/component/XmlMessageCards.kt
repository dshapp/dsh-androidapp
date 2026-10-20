package ai.deepseek.dshphone.ui.component

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import ai.deepseek.dshphone.model.XmlMessageCards
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 用户消息里圈选元素 XML 的卡片栈（对齐 Mac `PhoneXMLMessageCards` / `XMLMessageCardsView` 与
 * 插件 `user-xml-cards-view.js`）：每个元素一张卡，等宽标签头 + 每个属性一行「键 / 值」；
 * 内联只放前 [XmlMessageCards.INLINE_LIMIT] 张，其余进「查看全部」详情弹层。
 *
 * 卡片不是用户原话，所以不套气泡底色，单独一层描边表面、与用户消息同侧靠右。
 * 点卡片弹「复制」，复制的是原始消息文本（与 Mac 的 contextMenu 同口径）。
 *
 * @param source 原始消息文本，复制用。
 * @param maxWidth 单张卡的最大宽度。
 */
@Composable
fun XmlMessageCardStack(
    parsed: XmlMessageCards,
    source: String,
    maxWidth: Dp,
    findQuery: String = "",
    findActive: Boolean = false,
) {
    var detail by remember { mutableStateOf(false) }
    var copyMenu by remember(source) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val shown = parsed.cards.take(XmlMessageCards.INLINE_LIMIT)
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            Column(
                Modifier.clickable { copyMenu = true },
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                shown.forEach { card -> XmlMessageCardView(card, maxWidth, findQuery, findActive) }
            }
            DropdownMenu(copyMenu, { copyMenu = false }) {
                DropdownMenuItem(
                    text = { Text(Strings.copy) },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                    onClick = {
                        copyMenu = false
                        clipboard.setText(AnnotatedString(source))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                )
            }
        }
        if (shown.size < parsed.cards.size) {
            // 「查看全部」挂在卡片列尾部，点开是统一的详情弹层（图标 + 根标签名 + 复制）。
            Text(
                fmt(Strings.xmlCardsViewAll, parsed.cards.size),
                modifier = Modifier
                    .clickable { detail = true }
                    .padding(vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    if (detail) {
        CardDetailDialog(
            Icons.Default.DataObject,
            parsed.rootTag,
            XmlMessageCards.plainText(parsed.cards, Strings.xmlCardText),
            { detail = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                parsed.cards.forEach { card -> XmlMessageCardView(card, null) }
            }
        }
    }
}

/**
 * 一个元素的卡：等宽标签头，然后每个属性一行。键列按本卡最长的键对齐、右对齐贴住值
 *（插件两列 grid、Mac LabeledContent 的读法）。
 *
 * @param maxWidth 卡宽上限；null 表示撑满（详情弹层里）。
 */
@Composable
private fun XmlMessageCardView(
    card: XmlMessageCards.Card,
    maxWidth: Dp?,
    findQuery: String = "",
    findActive: Boolean = false,
) {
    val textLabel = Strings.xmlCardText
    val rows = remember(card, textLabel) {
        card.attributes.map { it.name to it.value } + listOfNotNull(card.text?.let { textLabel to it })
    }
    val keyStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 17.sp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widestKey = remember(rows, density) {
        val widest = rows.maxOfOrNull { (key, _) -> measurer.measure(key, keyStyle).size.width } ?: 0
        with(density) { widest.toDp() }
    }
    val findStyle = if (findQuery.isBlank()) null else findSpanStyle(findActive)
    fun highlighted(text: String): AnnotatedString =
        if (findStyle == null) AnnotatedString(text)
        else buildAnnotatedString { appendFindHighlighted(text, findQuery, findStyle) }

    val shape = RoundedCornerShape(10.dp)
    BoxWithConstraints(if (maxWidth == null) Modifier.fillMaxWidth() else Modifier.widthIn(max = maxWidth)) {
        // 键列不超过卡宽的四成，免得一个长键把值挤没。
        val keyWidth = minOf(widestKey, this.maxWidth * 0.4f)
        SelectionContainer {
            Column(
                Modifier
                    .then(if (maxWidth == null) Modifier.fillMaxWidth() else Modifier)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                // 标签头保留等宽字体：它显示的确实是标记名，不是文案。
                Text(
                    card.tag,
                    style = keyStyle.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                rows.forEach { (key, value) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 键与值字号不同，按基线对齐（插件 align-items: baseline）。
                        Text(
                            highlighted(key),
                            modifier = Modifier.width(keyWidth).alignByBaseline(),
                            style = keyStyle,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.End,
                        )
                        Text(
                            highlighted(value),
                            modifier = Modifier.alignByBaseline(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

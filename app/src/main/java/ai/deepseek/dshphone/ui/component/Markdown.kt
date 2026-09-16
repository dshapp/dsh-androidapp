package ai.deepseek.dshphone.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.deepseek.dshphone.model.TurnDeliverables
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MdText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

private val markdownParser: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
    .build()

/**
 * GFM Markdown 渲染：commonmark 解析 + Compose 原生排版。
 * 支持标题、段落、列表、代码块、引用、表格、行内强调/代码/链接。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    fileMentions: List<String> = emptyList(),
    onOpenFile: (String) -> Unit = {},
) {
    val document = remember(text) { markdownParser.parse(text) as Document }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RenderBlocks(document, fileMentions, onOpenFile)
    }
}

@Composable
private fun RenderBlocks(parent: Node, mentions: List<String>, onOpenFile: (String) -> Unit) {
    var child = parent.firstChild
    while (child != null) {
        RenderBlock(child, mentions, onOpenFile)
        child = child.next
    }
}

@Composable
private fun RenderBlock(node: Node, mentions: List<String>, onOpenFile: (String) -> Unit) {
    when (node) {
        is Heading -> {
            val style = when (node.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            Text(inlineText(node, mentions, onOpenFile), style = style, fontWeight = FontWeight.SemiBold)
        }
        is Paragraph -> Text(
            inlineText(node, mentions, onOpenFile),
            style = MaterialTheme.typography.bodyLarge,
        )
        is FencedCodeBlock -> CodeBlock(node.literal.trimEnd())
        is IndentedCodeBlock -> CodeBlock(node.literal.trimEnd())
        is BulletList -> ListBlock(node, ordered = false, mentions = mentions, onOpenFile = onOpenFile)
        is OrderedList -> ListBlock(node, ordered = true, start = node.markerStartNumber ?: 1, mentions = mentions, onOpenFile = onOpenFile)
        is BlockQuote -> Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(3.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Column(Modifier.padding(start = 10.dp)) { RenderBlocks(node, mentions, onOpenFile) }
        }
        is ThematicBreak -> HorizontalDivider()
        is TableBlock -> TableView(node, mentions, onOpenFile)
        else -> RenderBlocks(node, mentions, onOpenFile)
    }
}

@Composable
private fun CodeBlock(code: String) {
    val scroll = rememberScrollState()
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text(
            code,
            modifier = Modifier.horizontalScroll(scroll),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ListBlock(
    node: Node,
    ordered: Boolean,
    start: Int = 1,
    mentions: List<String>,
    onOpenFile: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        var item = node.firstChild
        var index = start
        while (item != null) {
            if (item is ListItem) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        if (ordered) "$index." else "•",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Column(Modifier.weight(1f)) { RenderBlocks(item, mentions, onOpenFile) }
                }
                index++
            }
            item = item.next
        }
    }
}

@Composable
private fun TableView(table: TableBlock, mentions: List<String>, onOpenFile: (String) -> Unit) {
    val scroll = rememberScrollState()
    Column(Modifier.horizontalScroll(scroll)) {
        var section = table.firstChild
        while (section != null) {
            val header = section is TableHead
            var row = section.firstChild
            while (row != null) {
                if (row is TableRow) {
                    Row(Modifier.fillMaxWidth()) {
                        var cell = row.firstChild
                        while (cell != null) {
                            if (cell is TableCell) {
                                Text(
                                    inlineText(cell, mentions, onOpenFile),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier
                                        .width(150.dp)
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            }
                            cell = cell.next
                        }
                    }
                    HorizontalDivider()
                }
                row = row.next
            }
            section = section.next
        }
    }
}

/** 把一段行内节点摊成带样式的文本。 */
@Composable
private fun inlineText(
    parent: Node,
    mentions: List<String>,
    onOpenFile: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    fun walk(node: Node?) {
        var child = node
        while (child != null) {
            when (child) {
                is MdText -> append(child.literal)
                is Code -> {
                    // 行内代码若正好是本轮产出的文件，就变成可点的提及，点开只读预览。
                    val path = if (mentions.isEmpty()) null
                    else TurnDeliverables.resolveMention(child.literal, mentions)
                    val codeStyle = SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground,
                        fontSize = 14.sp,
                        color = if (path != null) linkColor else androidx.compose.ui.graphics.Color.Unspecified,
                    )
                    if (path != null) {
                        pushLink(
                            LinkAnnotation.Clickable("mention", TextLinkStyles(codeStyle)) { onOpenFile(path) }
                        )
                        append(child.literal)
                        pop()
                    } else {
                        withStyleSpan(codeStyle) { append(child.literal) }
                    }
                }
                is Emphasis -> withStyleSpan(SpanStyle(fontStyle = FontStyle.Italic)) { walk(child.firstChild) }
                is StrongEmphasis -> withStyleSpan(SpanStyle(fontWeight = FontWeight.Bold)) { walk(child.firstChild) }
                is Strikethrough -> withStyleSpan(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    walk(child.firstChild)
                }
                is Link -> {
                    pushLink(
                        LinkAnnotation.Url(
                            child.destination,
                            TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        )
                    )
                    walk(child.firstChild)
                    pop()
                }
                is SoftLineBreak -> append(" ")
                is HardLineBreak -> append("\n")
                else -> walk(child.firstChild)
            }
            child = child.next
        }
    }
    walk(parent.firstChild)
}

private inline fun androidx.compose.ui.text.AnnotatedString.Builder.withStyleSpan(
    style: SpanStyle,
    block: () -> Unit,
) {
    pushStyle(style)
    block()
    pop()
}

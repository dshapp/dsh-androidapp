package ai.deepseek.dshphone.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.model.FileLink
import ai.deepseek.dshphone.model.ImageFileType
import ai.deepseek.dshphone.model.TurnDeliverables
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
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
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
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

private val markdownParser: Parser = GfmAutolink.install(
    Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
).build()

/** 解析结果：文档 + 任务项勾选态（方括号那个复选标记在解析期就摘掉）。 */
private class MarkdownDoc(val document: Document, val tasks: Map<ListItem, Boolean>)

private val taskMarker = Regex("""^\[([ xX])]\s+""")

/** U+FEFF：micromark 在 preprocess 阶段会摘掉文档开头这一个字符，这里对齐同一行为。 */
private const val BOM = '\uFEFF'

private fun parseMarkdown(raw: String): MarkdownDoc {
    val text = if (raw.startsWith(BOM)) raw.substring(1) else raw
    val document = markdownParser.parse(text) as Document
    val tasks = java.util.IdentityHashMap<ListItem, Boolean>()
    fun scan(start: Node?) {
        var node = start
        while (node != null) {
            val current = node
            if (current is ListItem) {
                val paragraph = current.firstChild
                val first = paragraph?.firstChild
                if (paragraph is Paragraph && first is MdText) {
                    taskMarker.find(first.literal)?.let { match ->
                        tasks[current] = match.groupValues[1].lowercase() == "x"
                        first.literal = first.literal.substring(match.value.length)
                    }
                }
            }
            scan(current.firstChild)
            node = current.next
        }
    }
    scan(document.firstChild)
    return MarkdownDoc(document, tasks)
}

@Immutable
private class MarkdownScope(
    val tasks: Map<ListItem, Boolean>,
    val mentions: List<String>,
    val onOpenFile: (String) -> Unit,
    /** 链接指向的工作区文件（[ai.deepseek.dshphone.model.FileLink] 解析结果）；为 null 时这类链接交回系统处理。 */
    val onOpenLink: ((ai.deepseek.dshphone.model.FileLinkTarget) -> Unit)?,
    /** 会话内查找的查询词；空串表示不在查找态。 */
    val findQuery: String,
    /** 命中字的行内样式；不在查找态时为 null。 */
    val findStyle: SpanStyle?,
    /** 读回本地路径图片的位图；为 null 时图片语法只画成可点开的 alt 文字。 */
    val loadImage: (suspend (ai.deepseek.dshphone.model.FileLinkTarget) -> android.graphics.Bitmap?)?,
)

/**
 * GFM Markdown 渲染：commonmark 解析 + Compose 原生排版。
 * 支持标题、段落、列表（含任务列表）、代码块、引用、表格、分隔线，
 * 以及行内强调/删除线/代码/链接。块间留白按前后块的类型给，不用一刀切的统一间距，
 * 这样标题能领起下一段，正文之间又不至于散开。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    fileMentions: List<String> = emptyList(),
    onOpenFile: (String) -> Unit = {},
    /** 链接指向的工作区文件；不传时这类链接仍按普通地址交给系统。 */
    onOpenLink: ((ai.deepseek.dshphone.model.FileLinkTarget) -> Unit)? = null,
    /** 会话内查找的查询词；空串表示不画高亮。 */
    findQuery: String = "",
    /** 这一块所属的行是否查找栏当前停靠的命中行。 */
    findActive: Boolean = false,
    /** 单独成段的本地路径图片（`![](/abs/shot.png)`）用它取位图就地显示；不传则只画 alt 链接。 */
    loadImage: (suspend (ai.deepseek.dshphone.model.FileLinkTarget) -> android.graphics.Bitmap?)? = null,
) {
    val doc = remember(text) { parseMarkdown(text) }
    val findStyle = if (findQuery.isBlank()) null else findSpanStyle(findActive)
    val scope = remember(doc, fileMentions, onOpenFile, onOpenLink, findQuery, findStyle, loadImage) {
        MarkdownScope(doc.tasks, fileMentions, onOpenFile, onOpenLink, findQuery, findStyle, loadImage)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        RenderBlocks(doc.document, scope, depth = 0, compact = false)
    }
}

/* ---------------------------------------------------------------- 排版基调 */

/** 正文：行高放到 1.55 倍，并裁掉首尾行多出来的行距，块间留白交给外面统一控制。 */
@Composable
private fun bodyStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(
    lineHeight = 25.sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Proportional,
        trim = LineHeightStyle.Trim.Both,
    ),
)

@Composable
private fun headingStyle(level: Int): TextStyle {
    val base = MaterialTheme.typography.titleLarge
    val size = when (level) {
        1 -> 21.sp
        2 -> 19.sp
        3 -> 17.sp
        4 -> 16.sp
        else -> 15.sp
    }
    val height = when (level) {
        1 -> 28.sp
        2 -> 26.sp
        3 -> 24.sp
        4 -> 23.sp
        else -> 22.sp
    }
    return base.copy(
        fontSize = size,
        lineHeight = height,
        fontWeight = if (level <= 2) FontWeight.Bold else FontWeight.SemiBold,
        letterSpacing = 0.sp,
        color = if (level >= 5) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Proportional,
            trim = LineHeightStyle.Trim.Both,
        ),
    )
}

@Composable
private fun codeTextStyle(): TextStyle = MaterialTheme.typography.bodyMedium.copy(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
)

/** 代码块、表头这类底板色：比正文背景略深一档，不抢视线。 */
@Composable
private fun blockSurface(): Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)

@Composable
private fun hairline(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)

/** 块与块之间的留白：看下一块是什么来给，标题前留得多，段内元素留得少。 */
private fun gapBefore(previous: Node, node: Node, compact: Boolean): Dp {
    val base = when (node) {
        is Heading -> if (previous is Heading) 10.dp else if (node.level <= 2) 18.dp else 15.dp
        is ThematicBreak -> 14.dp
        is FencedCodeBlock, is IndentedCodeBlock, is TableBlock, is BlockQuote -> 10.dp
        is BulletList, is OrderedList -> if (previous is Heading) 6.dp else 8.dp
        is Paragraph -> if (previous is Heading) 6.dp else 10.dp
        else -> 8.dp
    }
    return if (compact) (base * 0.55f).coerceAtLeast(4.dp) else base
}

/* ------------------------------------------------------------------ 块渲染 */

@Composable
private fun RenderBlocks(parent: Node, scope: MarkdownScope, depth: Int, compact: Boolean) {
    var child = parent.firstChild
    var previous: Node? = null
    while (child != null) {
        val current = child
        val gap = previous?.let { gapBefore(it, current, compact) } ?: 0.dp
        RenderBlock(current, scope, depth, compact, Modifier.padding(top = gap))
        previous = current
        child = current.next
    }
}

@Composable
private fun RenderBlock(
    node: Node,
    scope: MarkdownScope,
    depth: Int,
    compact: Boolean,
    modifier: Modifier,
) {
    when (node) {
        is Heading -> Text(inlineText(node, scope), modifier, style = headingStyle(node.level))
        is Paragraph -> {
            val images = standaloneImages(node, scope)
            if (images != null) {
                Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    images.forEach { (image, target) -> InlineImage(image, target, scope) }
                }
            } else {
                Text(inlineText(node, scope), modifier, style = bodyStyle())
            }
        }
        is FencedCodeBlock -> CodeBlock(
            node.literal.trimEnd('\n'),
            node.info?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() },
            modifier,
        )
        is IndentedCodeBlock -> CodeBlock(node.literal.trimEnd('\n'), null, modifier)
        is BulletList -> ListBlock(node, scope, ordered = false, start = 1, depth = depth, modifier = modifier)
        is OrderedList -> ListBlock(
            node, scope, ordered = true, start = node.markerStartNumber ?: 1, depth = depth, modifier = modifier,
        )
        is BlockQuote -> QuoteBlock(node, scope, depth, modifier)
        is ThematicBreak -> HorizontalDivider(modifier.fillMaxWidth(), color = hairline())
        is TableBlock -> TableView(node, scope, modifier)
        is HtmlBlock -> strippedHtml(node.literal)?.let {
            Text(it, modifier, style = bodyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> RenderBlocks(node, scope, depth, compact)
    }
}

/** 代码块：语言标签 + 一键复制的顶栏，正文横向滚动，长行不再挤成一团。 */
@Composable
private fun CodeBlock(code: String, language: String?, modifier: Modifier) {
    val clipboard = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var copied by remember(code) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(12.dp)
    val line = hairline()
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(blockSurface())
            .border(1.dp, line, shape)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language.orEmpty().lowercase(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                        coroutineScope.launch { delay(1500); copied = false }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    Strings.copyAction,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
        HorizontalDivider(color = line)
        Text(
            code,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            style = codeTextStyle(),
        )
    }
}

private fun bulletGlyph(depth: Int): String = when (depth % 3) {
    0 -> "•"
    1 -> "◦"
    else -> "▪"
}

/** 列表：标记占固定槽位，序号右对齐，任务项画勾选框，层级换符号。 */
@Composable
private fun ListBlock(
    node: Node,
    scope: MarkdownScope,
    ordered: Boolean,
    start: Int,
    depth: Int,
    modifier: Modifier,
) {
    val style = bodyStyle()
    val markerColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth()) {
        var item = node.firstChild
        var index = start
        var first = true
        while (item != null) {
            val current = item
            if (current is ListItem) {
                val checked = scope.tasks[current]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = if (first) 0.dp else 5.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    when {
                        checked != null -> Icon(
                            if (checked) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            null,
                            Modifier
                                .padding(top = 3.dp)
                                .size(16.dp),
                            tint = if (checked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        )
                        ordered -> Text(
                            index.toString() + ".",
                            modifier = Modifier.defaultMinSize(minWidth = 20.dp),
                            style = style,
                            color = markerColor,
                            textAlign = TextAlign.End,
                        )
                        else -> Text(
                            bulletGlyph(depth),
                            modifier = Modifier.width(14.dp),
                            style = style,
                            color = markerColor,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        RenderBlocks(current, scope, depth + 1, compact = true)
                    }
                }
                index++
                first = false
            }
            item = current.next
        }
    }
}

/** 引用：左侧色条 + 极淡底色，正文降一档色，明显退到主文后面。 */
@Composable
private fun QuoteBlock(node: Node, scope: MarkdownScope, depth: Int, modifier: Modifier) {
    val bar = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(blockSurface())
            .drawBehind {
                drawRoundRect(
                    color = bar,
                    size = Size(3.dp.toPx(), size.height),
                    cornerRadius = CornerRadius(1.5.dp.toPx()),
                )
            }
            .padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 9.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            RenderBlocks(node, scope, depth, compact = true)
        }
    }
}

/* ------------------------------------------------------------------- 表格 */

private class TableCellData(val text: AnnotatedString, val align: TextAlign)

/** 一个字符的视觉宽度：中日韩与全角按两格算，用来估列宽。 */
private fun visualWidth(text: CharSequence): Int = text.fold(0) { width, ch ->
    val code = ch.code
    width + if (code in 0x2E80..0xD7A3 || code in 0xF900..0xFAFF || code in 0xFF00..0xFF60) 2 else 1
}

/** 表格：按内容估列宽（不再一刀切 150dp），表头带底色，外面套圆角描边。 */
@Composable
private fun TableView(table: TableBlock, scope: MarkdownScope, modifier: Modifier) {
    val header = mutableListOf<TableCellData>()
    val body = mutableListOf<List<TableCellData>>()
    var section = table.firstChild
    while (section != null) {
        val isHead = section is TableHead
        var row = section.firstChild
        while (row != null) {
            val currentRow = row
            if (currentRow is TableRow) {
                val cells = mutableListOf<TableCellData>()
                var cell = currentRow.firstChild
                while (cell != null) {
                    val currentCell = cell
                    if (currentCell is TableCell) {
                        cells.add(
                            TableCellData(
                                inlineText(currentCell, scope),
                                when (currentCell.alignment) {
                                    TableCell.Alignment.CENTER -> TextAlign.Center
                                    TableCell.Alignment.RIGHT -> TextAlign.End
                                    else -> TextAlign.Start
                                },
                            )
                        )
                    }
                    cell = currentCell.next
                }
                if (isHead && header.isEmpty()) header.addAll(cells) else body.add(cells)
            }
            row = currentRow.next
        }
        section = section.next
    }
    val columns = maxOf(header.size, body.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return

    val shape = RoundedCornerShape(10.dp)
    val line = hairline()
    val headerBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = maxWidth
        val raw = (0 until columns).map { index ->
            val longest = (listOf(header) + body).maxOfOrNull { row ->
                row.getOrNull(index)?.let { visualWidth(it.text) } ?: 0
            } ?: 0
            (longest.coerceAtMost(26) * 7.6f).dp.coerceIn(48.dp, 200.dp) + 20.dp
        }
        val total = raw.fold(0.dp) { acc, width -> acc + width }
        val overflow = total >= available
        val widths = if (overflow) raw else raw.map { it * (available / total) }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, line, shape)
                .then(if (overflow) Modifier.horizontalScroll(scroll) else Modifier)
        ) {
            if (header.isNotEmpty()) {
                TableRowView(header, widths, headerBackground, true)
                HorizontalDivider(color = line)
            }
            body.forEachIndexed { index, cells ->
                if (index > 0) HorizontalDivider(color = line.copy(alpha = 0.5f))
                TableRowView(cells, widths, Color.Transparent, false)
            }
        }
    }
}

@Composable
private fun TableRowView(
    cells: List<TableCellData>,
    widths: List<Dp>,
    background: Color,
    header: Boolean,
) {
    Row(Modifier.background(background)) {
        widths.forEachIndexed { index, width ->
            val cell = cells.getOrNull(index)
            Text(
                cell?.text ?: AnnotatedString(""),
                modifier = Modifier
                    .width(width)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = cell?.align ?: TextAlign.Start,
            )
        }
    }
}

/* ------------------------------------------------------------------- 行内 */

/** 只保留 HTML 块里的可读文本，标签本身不往正文里灌。 */
private fun strippedHtml(literal: String): String? =
    literal.replace(Regex("<[^>]*>"), "").trim().takeIf { it.isNotEmpty() }

/** 把一段行内节点摊成带样式的文本。 */
@Composable
private fun inlineText(parent: Node, scope: MarkdownScope): AnnotatedString = buildAnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
    fun walk(node: Node?) {
        var child = node
        while (child != null) {
            val current = child
            // 查找命中词逐字染色；不在查找态（findStyle 为 null）时原样追加。
            fun appendRun(run: String) {
                val style = scope.findStyle
                if (style != null) appendFindHighlighted(run, scope.findQuery, style) else append(run)
            }
            when (current) {
                is MdText -> appendRun(current.literal)
                is Code -> {
                    // 行内代码若是文件就变成可点的提及：先看本轮产物词表（Web 的
                    // fileMentions 口径），词表里没有再看 token 本身是否明确是个文件路径。
                    val mention = if (scope.mentions.isEmpty()) null
                    else TurnDeliverables.resolveMention(current.literal, scope.mentions)
                    val guessed = if (mention == null && scope.onOpenLink != null) {
                        FileLink.parseInlineCode(current.literal)
                    } else {
                        null
                    }
                    val codeStyle = SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground,
                        fontSize = 0.92.em,
                        letterSpacing = 0.sp,
                        color = if (mention != null || guessed != null) linkColor else Color.Unspecified,
                    )
                    // 底色紧贴字形很难看，用窄空格在两侧垫出内边距。
                    val padded = "\u2009" + current.literal + "\u2009"
                    if (mention != null) {
                        pushLink(
                            LinkAnnotation.Clickable("mention", TextLinkStyles(codeStyle)) {
                                scope.onOpenFile(mention)
                            }
                        )
                        appendRun(padded)
                        pop()
                    } else if (guessed != null) {
                        pushLink(
                            LinkAnnotation.Clickable("file-path", TextLinkStyles(codeStyle)) {
                                scope.onOpenLink?.invoke(guessed)
                            }
                        )
                        appendRun(padded)
                        pop()
                    } else {
                        withStyleSpan(codeStyle) { appendRun(padded) }
                    }
                }
                is Emphasis -> withStyleSpan(SpanStyle(fontStyle = FontStyle.Italic)) { walk(current.firstChild) }
                is StrongEmphasis -> withStyleSpan(SpanStyle(fontWeight = FontWeight.Bold)) {
                    walk(current.firstChild)
                }
                is Strikethrough -> withStyleSpan(
                    SpanStyle(textDecoration = TextDecoration.LineThrough, color = muted)
                ) { walk(current.firstChild) }
                is Link -> {
                    // 工作区文件（相对路径、绝对路径、file://、dsh-resource://file/…）
                    // 不是浏览器地址：Web 用 dsh-resource 地址在右侧栏打开，手机端在这里
                    // 转成"读回来交给内置预览"。
                    val destination = current.destination
                    val file = FileLink.parse(destination)
                    if (file != null && scope.onOpenLink != null) {
                        pushLink(
                            LinkAnnotation.Clickable("file-link", TextLinkStyles(linkStyle)) {
                                scope.onOpenLink?.invoke(file)
                            }
                        )
                        walk(current.firstChild)
                        pop()
                    } else if (GfmAutolink.isSafeExternalUrl(destination)) {
                        // http/https/mailto：交给系统默认浏览器/邮件客户端（ACTION_VIEW），
                        // 不内置 WebView、不用 Custom Tabs。LinkAnnotation.Url 不带监听器时
                        // 走 LocalUriHandler.openUri。
                        pushLink(LinkAnnotation.Url(destination, TextLinkStyles(linkStyle)))
                        walk(current.firstChild)
                        pop()
                    } else {
                        // 未知或危险协议：按纯文本渲染（对齐 Web 端 sanitizeUrl 返回空串）。
                        walk(current.firstChild)
                    }
                }
                is Image -> {
                    // 图片语法 ![alt](shot.png)：目的地是图片文件时，和文件链接一样点开
                    // 系统图片查看器——绝不用文本（alt）去冒充图片。其余情况保持原来的
                    // 斜体弱化渲染，不猜、不乱点。
                    val file = FileLink.parse(current.destination)
                    if (file != null && scope.onOpenLink != null && ImageFileType.isImage(file.path)) {
                        pushLink(
                            LinkAnnotation.Clickable("image-link", TextLinkStyles(linkStyle)) {
                                scope.onOpenLink?.invoke(file)
                            }
                        )
                        withStyleSpan(SpanStyle(fontStyle = FontStyle.Italic)) { walk(current.firstChild) }
                        pop()
                    } else {
                        withStyleSpan(SpanStyle(fontStyle = FontStyle.Italic, color = muted)) {
                            walk(current.firstChild)
                        }
                    }
                }
                // 模型写的单换行就是想换行（同 GitHub 评论与主流聊天端），不并成一行。
                is SoftLineBreak -> append("\n")
                is HardLineBreak -> append("\n")
                is HtmlInline -> if (current.literal.startsWith("<br", ignoreCase = true)) append("\n")
                else -> walk(current.firstChild)
            }
            child = current.next
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

/* -------------------------------------------------------------- 本地路径图片 */

/**
 * 一段只由图片语法组成（中间只有空白与换行）、且每张都指向工作区图片文件时，
 * 返回这些图片与它们的落点；否则 null，照常按文字排。
 *
 * 对齐 Web `.image` 规则：单独成段的图片按原始尺寸显示、宽度不超过列宽。
 * 与文字混排的图片仍是 alt 文字链接（点开交给系统查看器）。
 */
private fun standaloneImages(
    paragraph: Paragraph,
    scope: MarkdownScope,
): List<Pair<Image, ai.deepseek.dshphone.model.FileLinkTarget>>? {
    if (scope.loadImage == null) return null
    val out = mutableListOf<Pair<Image, ai.deepseek.dshphone.model.FileLinkTarget>>()
    var child = paragraph.firstChild
    while (child != null) {
        when (child) {
            is Image -> {
                val target = FileLink.parse(child.destination) ?: return null
                if (!ImageFileType.isImage(target.path)) return null
                out.add(child to target)
            }
            is SoftLineBreak, is HardLineBreak -> Unit
            is MdText -> if (child.literal.isNotBlank()) return null
            else -> return null
        }
        child = child.next
    }
    return out.takeIf { it.isNotEmpty() }
}

private fun altText(image: Image): String {
    val builder = StringBuilder()
    fun walk(node: Node?) {
        var current = node
        while (current != null) {
            if (current is MdText) builder.append(current.literal)
            walk(current.firstChild)
            current = current.next
        }
    }
    walk(image.firstChild)
    return builder.toString()
}

/** 就地显示一张工作区图片：加载中占位，失败退回 alt 文字；点一下交给系统查看器。 */
@Composable
private fun InlineImage(image: Image, target: ai.deepseek.dshphone.model.FileLinkTarget, scope: MarkdownScope) {
    val loader = scope.loadImage ?: return
    // Key the state as well as the load: a new target must not show the previous bitmap.
    var state by remember(target, loader) { mutableStateOf<InlineImageState>(InlineImageState.Loading) }
    LaunchedEffect(target, loader) {
        val bitmap = loader(target)
        state = if (bitmap != null) InlineImageState.Ready(bitmap) else InlineImageState.Failed
    }
    val alt = altText(image).ifBlank { target.path.substringAfterLast('/') }
    val open = scope.onOpenLink?.let { handler -> { handler(target) } }
    val clickable = if (open != null) Modifier.clickable(onClick = open) else Modifier
    when (val current = state) {
        InlineImageState.Loading -> Box(
            Modifier
                .size(width = 160.dp, height = 96.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(blockSurface()),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
        InlineImageState.Failed -> Text(
            alt,
            clickable,
            style = bodyStyle(),
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is InlineImageState.Ready -> {
            val bitmap = current.bitmap
            // 原始尺寸（Web 的 CSS px 在这里就是 dp），宽度不超过列宽。
            val natural = bitmap.width.dp
            androidx.compose.foundation.Image(
                bitmap.asImageBitmap(),
                contentDescription = alt,
                modifier = Modifier
                    .widthIn(max = natural)
                    .fillMaxWidth()
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
                    .clip(RoundedCornerShape(8.dp))
                    .then(clickable),
                alignment = Alignment.TopStart,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
    }
}

private sealed interface InlineImageState {
    data object Loading : InlineImageState
    data object Failed : InlineImageState
    data class Ready(val bitmap: android.graphics.Bitmap) : InlineImageState
}

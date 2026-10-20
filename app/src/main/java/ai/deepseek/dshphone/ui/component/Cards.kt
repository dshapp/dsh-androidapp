package ai.deepseek.dshphone.ui.component

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.*
import ai.deepseek.dshphone.state.PhoneModel
import ai.deepseek.dshphone.ui.theme.Dimens
import ai.deepseek.dshphone.ui.theme.DshChrome
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 转写里任何一张可展开卡片的一行：图标 + 摘要（+ 附注）。
 *
 * 卡片族（工具、思考、提示行）共用这一套最小行高与字号（Mac/iOS `chromeRowHeight` 18）。
 * [running] 时图标槽仍是卡片自己的图标，图标与摘要一起呼吸（[breathing]）即运行态。
 */
@Composable
fun CardRow(
    icon: ImageVector,
    summary: String,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    tint: Color = DshChrome.label,
    running: Boolean = false,
    onClick: (() -> Unit)? = null,
    /** 会话内查找的查询词；命中词在摘要里逐字高亮。 */
    findQuery: String = "",
    /** 这一行是否查找栏当前停靠的命中行；是则命中词用深一档的底色。 */
    findActive: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.chromeRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            // 摘要簇吃掉整行剩余宽度；旧写法与尾部 Spacer 平分权重，
            // 摘要因此只拿得到半行宽而被提前截断。
            modifier = Modifier
                .weight(1f)
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(vertical = 1.dp)
                .breathing(running),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Dimens.iconSlot))
            val findStyle = if (findQuery.isBlank()) null else findSpanStyle(findActive)
            val summaryText = if (findStyle == null) AnnotatedString(summary)
            else androidx.compose.ui.text.buildAnnotatedString {
                appendFindHighlighted(summary, findQuery, findStyle)
            }
            Text(
                summaryText,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (suffix != null) {
                Text(
                    suffix,
                    style = MaterialTheme.typography.labelSmall,
                    color = DshChrome.labelSubtle,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 详情弹层的统一外壳：图标 + 标题 + 复制 + 完成，正文可滚动。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailDialog(
    icon: ImageVector,
    title: String,
    copyText: String? = null,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(icon, null, Modifier.size(18.dp))
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, Strings.close) }
                        },
                        actions = {
                            if (copyText != null) {
                                IconButton(onClick = { clipboard.setText(AnnotatedString(copyText)) }) {
                                    Icon(Icons.Default.ContentCopy, Strings.copy)
                                }
                            }
                        },
                    )
                }
            ) { padding ->
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(Dimens.columnInset),
                    content = content,
                )
            }
        }
    }
}

/** 思考卡片：行上只显示第一行，全文在弹层里看。 */
@Composable
fun ThinkCard(
    text: String,
    findQuery: String = "",
    findActive: Boolean = false,
    /** 仍在流式输出：所在 step 还在跑，且这段 reasoning 是该 step 的最后一个块。 */
    running: Boolean = false,
) {
    var detail by remember { mutableStateOf(false) }
    val summary = remember(text) {
        val line = text.trimStart().substringBefore('\n').trim()
        line.ifEmpty { Strings.thinking }
    }
    CardRow(
        Icons.Outlined.Psychology,
        summary,
        running = running,
        onClick = { detail = true },
        findQuery = findQuery,
        findActive = findActive,
    )
    if (detail) {
        CardDetailDialog(Icons.Outlined.Psychology, Strings.thinking, text, { detail = false }) {
            SelectionText(text)
        }
    }
}

@Composable
fun SelectionText(text: String, mono: Boolean = false) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(
            text,
            style = if (mono) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}

/** 转写里的系统提示行（错误、压缩、命令等）。 */
@Composable
fun NoticeRow(
    icon: ImageVector,
    text: String,
    tint: Color = DshChrome.label,
    detailed: Boolean = false,
) {
    var detail by remember { mutableStateOf(false) }
    CardRow(icon, text, tint = tint, onClick = if (detailed) ({ detail = true }) else null)
    if (detail) {
        CardDetailDialog(icon, text, text, { detail = false }) { SelectionText(text) }
    }
}

/** 结构化 JSON 树，可逐层展开。 */
@Composable
fun JsonTree(value: JsonElement?, key: String? = null, depth: Int = 0) {
    when {
        value is JsonObject && value.isNotEmpty() ->
            JsonContainer("{${value.size}}", key, value.keys.sorted().map { it to value[it]!! }, depth)
        value is JsonArray && value.isNotEmpty() ->
            JsonContainer("[${value.size}]", key, value.mapIndexed { i, v -> i.toString() to v }, depth)
        else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (key != null) {
                Text(
                    "$key:",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                jsonScalarText(value),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun JsonContainer(label: String, key: String?, children: List<Pair<String, JsonElement>>, depth: Int) {
    var expanded by remember { mutableStateOf(depth == 0) }
    Column {
        Row(
            Modifier.clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                if (expanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                (key?.let { "$it " } ?: "") + label,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(Modifier.padding(start = 12.dp)) {
                children.take(200).forEach { (childKey, child) -> JsonTree(child, childKey, depth + 1) }
            }
        }
    }
}

/**
 * 工具结果：先看图片块，再按 resultView.card 分派。
 *
 * read_image 与任何"结果里带 image 块"的工具调用都在这里画持久化图片：字节不在这条
 * 消息里，拿 attachmentId 走 session/attachment（[PhoneModel.attachmentImage]）。
 */
@Composable
fun ToolResultView(result: ToolResultData, sessionId: SessionId? = null, model: PhoneModel? = null) {
    val images = if (result.isError) emptyList() else imageReferences(result.content) ?: lenientImageRefs(result.content)
    if (images.isNotEmpty() && sessionId != null && model != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            images.forEach { ref -> AttachmentImage(sessionId, ref, model) }
            val text = imageTexts(result.content) ?: toolResultText(result)
            if (text.isNotEmpty()) MonospaceBlock(text)
        }
        return
    }
    val card = result.resultView["card"].str
    when {
        result.isError -> MonospaceBlock(toolResultText(result), MaterialTheme.colorScheme.error)
        card == "terminal" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                result.resultView["exitCode"].int?.let { exit ->
                    AssistChip(
                        onClick = {},
                        label = { Text(exit.toString()) },
                        leadingIcon = {
                            Icon(
                                if (exit == 0) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                null,
                                Modifier.size(16.dp),
                            )
                        },
                    )
                }
                result.resultView["signal"].str?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            MonospaceBlock(result.resultView["output"].str ?: toolResultText(result))
        }
        card == "read" -> {
            val lines = result.resultView["lines"].arr
            if (!lines.isNullOrEmpty()) {
                Column {
                    lines.take(400).forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                jsonScalarText(line["number"]),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.width(40.dp),
                            )
                            Text(
                                line["text"].str ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            } else MonospaceBlock(toolResultText(result))
        }
        card == "search" || card == "web" -> MonospaceBlock(toolResultText(result))
        card == "diff" -> DiffBlock(result)
        else -> JsonTree(result.resultView ?: JsonPrimitive(toolResultText(result)))
    }
}

@Composable
private fun DiffBlock(result: ToolResultData) {
    val text = result.resultView["diffs"]?.let { diffText(it) } ?: toolResultText(result)
    Column {
        text.split('\n').take(400).forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = when {
                    line.startsWith("+") -> Color(0xFF2E7D32)
                    line.startsWith("-") -> MaterialTheme.colorScheme.error
                    line.startsWith("@@") -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

private fun diffText(value: JsonElement): String {
    value.str?.let { return it }
    value.arr?.let { items ->
        return items.mapNotNull { it.str ?: it["patch"].str ?: it["text"].str }.joinToString("\n")
    }
    return jsonScalarText(value)
}

@Composable
fun MonospaceBlock(text: String, tint: Color = MaterialTheme.colorScheme.onSurface) {
    val scroll = rememberScrollState()
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(
            text,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = tint,
        )
    }
}

/** 工具变体对应的图标。 */
fun toolVariantIcon(variant: ToolRowVariant): ImageVector = when (variant) {
    ToolRowVariant.BASH -> Icons.Default.Terminal
    ToolRowVariant.READ -> Icons.AutoMirrored.Filled.Article
    ToolRowVariant.SEARCH -> Icons.Default.Search
    ToolRowVariant.WRITE -> Icons.Default.NoteAdd
    ToolRowVariant.EDIT -> Icons.Default.Edit
    ToolRowVariant.CODE -> Icons.Default.Code
    ToolRowVariant.OTHERS -> Icons.Default.Build
}

/**
 * 工具调用卡片：与思考卡同一种行，点开同一种详情弹层。
 *
 * 两层协议在这里落地：
 *  - read_image 的卡身就是那张图（Web 的 tool.call.images 槽位，imageCardModel）；
 *  - run_code 里派发的子调用（tool/ptc-dispatch*）按 Web 的工具树缩进一层挂在父行下面，
 *    子调用自己的 read_image 图片同样画在自己的卡身里。
 */
@Composable
fun ToolCard(
    data: ToolChatData,
    cwd: String?,
    findQuery: String = "",
    findActive: Boolean = false,
    sessionId: SessionId? = null,
    model: PhoneModel? = null,
    depth: Int = 0,
) {
    var detail by remember(data.callId) { mutableStateOf(false) }
    val rowModel = remember(data, cwd) { toolRowModel(data, cwd) }
    val imageCard = remember(data, cwd) { readImageCard(data) }
    val tint = when (rowModel.state) {
        ToolRowState.ERROR ->
            if (rowModel.variant == ToolRowVariant.CODE) Color(0xFFE08600) else MaterialTheme.colorScheme.error
        ToolRowState.STOPPED -> Color(0xFFE08600)
        else -> DshChrome.label
    }
    val icon = when (rowModel.state) {
        ToolRowState.ERROR -> Icons.Default.Warning
        ToolRowState.STOPPED -> Icons.Default.PauseCircle
        else -> toolVariantIcon(rowModel.variant)
    }
    Column(Modifier.fillMaxWidth()) {
        CardRow(
            icon = icon,
            summary = rowModel.errorSummary ?: rowModel.summary,
            suffix = rowModel.summarySuffix,
            tint = tint,
            running = rowModel.state == ToolRowState.RUNNING || rowModel.state == ToolRowState.PREPARING,
            onClick = if (data.preparing) null else ({ detail = true }),
            findQuery = findQuery,
            findActive = findActive,
        )
        if (imageCard != null && sessionId != null && model != null) {
            ToolImageCard(imageCard, sessionId, model)
        }
        if (depth < 8) {
            data.subCalls.forEach { child ->
                Box(Modifier.padding(start = 18.dp)) {
                    ToolCard(child, cwd, findQuery, findActive, sessionId, model, depth + 1)
                }
            }
        }
    }
    if (detail && !data.preparing) {
        val title = rowModel.summary.substringBefore('\n').ifEmpty { data.name }
        val copyText = buildList {
            if (data.argsRaw.isNotEmpty()) add(prettyJson(data.argsRaw))
            data.result?.let { add(toolResultText(it)) }
        }.joinToString("\n\n")
        CardDetailDialog(toolVariantIcon(rowModel.variant), title, copyText, { detail = false }) {
            if (data.argsRaw.isNotEmpty()) {
                DetailSection(Strings.toolInput) { MonospaceBlock(prettyJson(data.argsRaw)) }
            }
            data.result?.let { result ->
                Spacer(Modifier.height(16.dp))
                DetailSection(Strings.toolOutput) { ToolResultView(result, sessionId, model) }
            }
            if (depth < 8) {
                data.subCalls.forEach { child ->
                    Spacer(Modifier.height(16.dp))
                    DetailSection(child.name) {
                        if (child.argsRaw.isNotEmpty()) MonospaceBlock(prettyJson(child.argsRaw))
                        child.result?.let { sub -> ToolResultView(sub, sessionId, model) }
                    }
                }
            }
        }
    }
}

/** read_image 的卡身：路径标签 + 持久化图片（web imageCardModel 的三件套）。 */
@Composable
private fun ToolImageCard(card: ImageCard, sessionId: SessionId, model: PhoneModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(card.path, style = MaterialTheme.typography.labelSmall, color = DshChrome.label)
        card.images.forEach { ref -> AttachmentImage(sessionId, ref, model) }
    }
}

@Composable
fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Dimens.cardRadius))
                .padding(10.dp)
        ) { content() }
    }
}

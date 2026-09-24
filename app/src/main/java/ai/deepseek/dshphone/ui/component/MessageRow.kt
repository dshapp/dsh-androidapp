package ai.deepseek.dshphone.ui.component

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.net.get
import ai.deepseek.dshphone.net.str
import ai.deepseek.dshphone.state.PhoneModel
import ai.deepseek.dshphone.state.SessionStore
import ai.deepseek.dshphone.ui.theme.Dimens
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 一行转写：用户气泡、助手 Markdown 回复、工具卡片。 */
@Composable
fun MessageRow(
    node: ChatNode,
    cwd: String?,
    sessionId: SessionId,
    model: PhoneModel,
    store: SessionStore?,
    onOpenFile: (String) -> Unit,
    /** Markdown 链接指向的工作区文件；用系统查看器打开。 */
    onOpenLink: (ai.deepseek.dshphone.model.FileLinkTarget) -> Unit,
    onFork: (Int) -> Unit,
    /** 用户气泡里 `@标题` 会话引用被点开：推一层那条会话。 */
    onOpenSession: (SessionId) -> Unit = {},
    /** 会话内查找的查询词；空串表示不在查找态，不画高亮。 */
    findQuery: String = "",
    /** 这一行是否查找栏当前停靠的命中行。 */
    findActive: Boolean = false,
) {
    when (node) {
        is ChatNode.User -> UserBubble(node.content, sessionId, model, onOpenSession, findQuery, findActive)
        is ChatNode.Steering -> UserBubble(node.content, sessionId, model, onOpenSession, findQuery, findActive)
        is ChatNode.TurnTrigger -> TurnTriggerRow(node.data)
        is ChatNode.Assistant ->
            AssistantRow(node.step, sessionId, model, store, onOpenFile, onOpenLink, findQuery, findActive)
        is ChatNode.Tool -> ToolCard(node.data, cwd, findQuery, findActive, sessionId, model)
        is ChatNode.TurnError -> NoticeRow(
            Icons.Default.Warning, node.data.message, MaterialTheme.colorScheme.error, detailed = true,
        )
        is ChatNode.Compaction -> NoticeRow(Icons.Default.UnfoldLess, Strings.compacted)
        is ChatNode.ModelRetry -> NoticeRow(Icons.Default.Refresh, Strings.modelRetry, Color(0xFFE08600))
        is ChatNode.TurnMaxTokens -> NoticeRow(Icons.Default.ErrorOutline, Strings.maxTokens, Color(0xFFE08600))
        is ChatNode.Command -> NoticeRow(Icons.AutoMirrored.Filled.KeyboardArrowRight, "/" + (node.data.name ?: ""))
        // 收尾消息还没落定的轮次没有页脚（对齐 Mac 的 `TurnTailRow`）。
        is ChatNode.TurnTail -> if (node.rendersRow) TurnTailRow(node.data, onFork, model, store)
        is ChatNode.TurnProcess -> TurnProcessRow(node.spec, store)
        is ChatNode.Workflow -> WorkflowRunCard(node.data)
        is ChatNode.UnknownSurface -> UnknownSurfaceRow(node.data)
    }
}

@Composable
private fun UserBubble(
    content: List<ContentBlock>,
    sessionId: SessionId,
    model: PhoneModel,
    onOpenSession: (SessionId) -> Unit,
    findQuery: String = "",
    findActive: Boolean = false,
) {
    val text = plainText(content)
    val images = lenientImageRefs(content)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 气泡最多占列宽的 82%，但短消息只占自己需要的宽度——
        // 旧写法是固定占比，一句「好」也会被拉成横贯半屏的长条。
        val bubbleMaxWidth = maxWidth * 0.82f
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Dimens.messageTopInset),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ImageGallery(images, sessionId, model, alignEnd = true)
        if (text.isNotEmpty()) {
            // 点气泡弹出「复制」小泡泡，一键复制整句；长按仍走系统文本选择。
            var copyMenu by remember(text) { mutableStateOf(false) }
            val clipboard = LocalClipboardManager.current
            val haptics = LocalHapticFeedback.current
            // 命中词在气泡里逐字高亮：气泡是纯文本，能直接画区间，
            // 不像 Markdown 那样要钻进行内渲染里。
            val findStyle = if (findQuery.isBlank()) null else findSpanStyle(findActive)
            // 被引用会话的整条标题画成可点的强调色链接：host 落库时把
            // `@[标题](dsh-session:…)` 重写成了纯文本 `@标题`，标题与会话 id 的
            // 对应关系只能从 store 的 referencedSessions 拿。
            val sessions = model.sessions[sessionId]?.referencedSessions ?: emptyMap()
            val linkColor = MaterialTheme.colorScheme.primary
            val body = buildAnnotatedString {
                for (token in projectUserRefs(text, sessions)) when (token) {
                    is UserRefToken.Text ->
                        if (findStyle == null) append(token.text)
                        else appendFindHighlighted(token.text, findQuery, findStyle)
                    is UserRefToken.Session -> withLink(
                        LinkAnnotation.Clickable("session") { onOpenSession(token.sessionId) }
                    ) {
                        withStyle(SpanStyle(color = linkColor)) {
                            if (findStyle == null) append("@" + token.label)
                            else appendFindHighlighted("@" + token.label, findQuery, findStyle)
                        }
                    }
                }
            }
            Box(Modifier.clickable { copyMenu = true }) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        body,
                        modifier = Modifier
                            .widthIn(max = bubbleMaxWidth)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(Dimens.bubbleRadius),
                            )
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                DropdownMenu(copyMenu, { copyMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(Strings.copy) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = {
                            copyMenu = false
                            clipboard.setText(AnnotatedString(text))
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                    )
                }
            }
        }
    }
    }
}

/**
 * 一张持久化图片：拿 attachmentId 走 session/attachment 取字节再解码。
 *
 * 尺寸按附件自己声明的 width/height 收在 [IMAGE_MAX_WIDTH]/[IMAGE_MAX_HEIGHT] 以内，
 * 声明缺失（0）时退回固定上限；取不到字节时给一个明确的失败占位，而不是一直转圈。
 */
@Composable
fun AttachmentImage(
    sessionId: SessionId,
    ref: ImageAttachmentRef,
    model: PhoneModel,
    /** 一组多图时缩成小图（Web 的 compact）。 */
    compact: Boolean = false,
) {
    var bitmap by remember(ref.attachmentId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loaded by remember(ref.attachmentId) { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    LaunchedEffect(ref.attachmentId) {
        bitmap = model.attachmentImage(sessionId, ref)
        loaded = true
    }
    val image = bitmap
    if (image != null) {
        Image(
            image.asImageBitmap(),
            contentDescription = ref.name,
            modifier = Modifier
                .widthIn(max = if (compact) IMAGE_COMPACT_MAX_WIDTH else IMAGE_MAX_WIDTH)
                .heightIn(max = if (compact) IMAGE_COMPACT_MAX_HEIGHT else IMAGE_MAX_HEIGHT)
                .clip(RoundedCornerShape(10.dp))
                .clickable { full = true },
            contentScale = ContentScale.Fit,
        )
        if (full) {
            CardDetailDialog(Icons.Default.Image, ref.name ?: "", null, { full = false }) {
                Image(image.asImageBitmap(), contentDescription = ref.name, modifier = Modifier.fillMaxWidth())
            }
        }
    } else {
        Box(
            Modifier
                .fillMaxWidth(0.6f)
                .height(120.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (loaded) {
                // 取不到就明说：ref 在日志里存在、字节读不到（未被会话引用/已被清理）。
                Text(
                    Strings.imageUnavailable,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/** 转写里单张图片的显示上限（与 Mac 手机端同样的收口）。 */
private val IMAGE_MAX_WIDTH = 280.dp
private val IMAGE_MAX_HEIGHT = 220.dp

/** 一组多图时的缩略上限（Web 的 compact 相册）。 */
private val IMAGE_COMPACT_MAX_WIDTH = 120.dp
private val IMAGE_COMPACT_MAX_HEIGHT = 120.dp

/**
 * 连续图片排成一组，与 Web 的 `renderMessageImages` 同口径：
 * 一张铺开，多张缩成一行；用户气泡靠右（alignEnd），助手正文靠左。
 */
@Composable
fun ImageGallery(
    refs: List<ImageAttachmentRef>,
    sessionId: SessionId,
    model: PhoneModel,
    alignEnd: Boolean,
) {
    if (refs.isEmpty()) return
    val compact = refs.size > 1
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        if (compact) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                refs.forEach { ref -> AttachmentImage(sessionId, ref, model, compact = true) }
            }
        } else {
            AttachmentImage(sessionId, refs.first(), model)
        }
    }
}

@Composable
private fun AssistantRow(
    step: AssistantStepData,
    sessionId: SessionId,
    model: PhoneModel,
    store: SessionStore?,
    onOpenFile: (String) -> Unit,
    onOpenLink: (ai.deepseek.dshphone.model.FileLinkTarget) -> Unit,
    findQuery: String = "",
    findActive: Boolean = false,
) {
    @Suppress("UNUSED_EXPRESSION")
    store?.deliverablesRevision
    val mentions = store?.closingMentions(step.turn, step.step, step.status == AssistantStatus.RUNNING)
        ?: emptyList()
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.rowSpacing),
    ) {
        val visible = step.blocks.filterNotNull()
        var index = 0
        while (index < visible.size) {
            val block = visible[index]
            // 连续的图片块合成一组相册；中间隔了文本就各成一组（Web 的相邻分组）。
            if (block is AssistantBlock.Image) {
                val refs = mutableListOf<ImageAttachmentRef>()
                var cursor = index
                while (cursor < visible.size) {
                    val candidate = visible[cursor] as? AssistantBlock.Image ?: break
                    candidate.attachment?.let { att -> decodeImageRef(att)?.let { refs.add(it) } }
                    cursor++
                }
                ImageGallery(refs, sessionId, model, alignEnd = false)
                index = cursor
                continue
            }
            index++
            when (block) {
                // 正文长按即进入系统文本选择（Android 原生复制路径）。
                is AssistantBlock.Text -> androidx.compose.foundation.text.selection.SelectionContainer(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dimens.messageTopInset)
                ) {
                    MarkdownText(
                        block.text,
                        fileMentions = mentions,
                        onOpenFile = onOpenFile,
                        onOpenLink = onOpenLink,
                        findQuery = findQuery,
                        findActive = findActive,
                        loadImage = remember(sessionId, model) {
                            val loader: suspend (FileLinkTarget) -> android.graphics.Bitmap? = { target ->
                                model.workspaceImage(target.sessionId ?: sessionId, target.path)
                            }
                            loader
                        },
                    )
                }
                is AssistantBlock.Reasoning -> ThinkCard(block.text, findQuery, findActive)
                // 图片块在上面成组处理了，这里不再重复。
                else -> Unit
            }
        }
        if (step.status == AssistantStatus.RUNNING && step.blocks.isEmpty()) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

/** 轮次触发通知：谁唤醒了这一轮（定时任务、后台任务、插件……），正文在详情里看。 */
@Composable
private fun TurnTriggerRow(data: TurnTriggerNode) {
    var detail by remember { mutableStateOf(false) }
    val title = turnTriggerTitle(data.kind)
    val icon = turnTriggerIcon(data.kind)
    CardRow(icon, title, onClick = { detail = true })
    if (detail) {
        val text = plainText(data.content)
        CardDetailDialog(icon, title, text, { detail = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    Strings.turnTriggerExplanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionText(text)
            }
        }
    }
}

private fun turnTriggerTitle(kind: TurnTriggerKind): String = when (kind) {
    TurnTriggerKind.REQUEST -> Strings.turnTriggerRequest
    TurnTriggerKind.GOAL -> Strings.turnTriggerGoal
    TurnTriggerKind.AGENT -> Strings.turnTriggerAgent
    TurnTriggerKind.TEAM -> Strings.turnTriggerTeam
    TurnTriggerKind.SUBAGENT -> Strings.turnTriggerSubagent
    TurnTriggerKind.GITHUB -> Strings.turnTriggerGitHub
    TurnTriggerKind.WEBHOOK -> Strings.turnTriggerWebhook
    TurnTriggerKind.SCHEDULE -> Strings.turnTriggerSchedule
    TurnTriggerKind.JOB -> Strings.turnTriggerJob
    TurnTriggerKind.PLUGIN -> Strings.turnTriggerPlugin
}

private fun turnTriggerIcon(kind: TurnTriggerKind): ImageVector = when (kind) {
    TurnTriggerKind.REQUEST -> Icons.AutoMirrored.Filled.NoteAdd
    TurnTriggerKind.GOAL -> Icons.Default.Flag
    TurnTriggerKind.AGENT -> Icons.AutoMirrored.Filled.Send
    TurnTriggerKind.TEAM -> Icons.Default.Groups
    TurnTriggerKind.SUBAGENT -> Icons.Default.HowToReg
    TurnTriggerKind.GITHUB -> Icons.AutoMirrored.Filled.CallSplit
    TurnTriggerKind.WEBHOOK -> Icons.Default.Public
    TurnTriggerKind.SCHEDULE -> Icons.Default.Alarm
    TurnTriggerKind.JOB -> Icons.AutoMirrored.Filled.List
    TurnTriggerKind.PLUGIN -> Icons.Default.Extension
}

@Composable
private fun UnknownSurfaceRow(data: UnknownSurfaceNode) {
    var detail by remember { mutableStateOf(false) }
    CardRow(Icons.Default.HelpOutline, data.type, onClick = { detail = true })
    if (detail) {
        CardDetailDialog(Icons.Default.HelpOutline, data.type, null, { detail = false }) { JsonTree(data.data) }
    }
}

@Composable
private fun WorkflowRunCard(data: WorkflowRunData) {
    var detail by remember { mutableStateOf(false) }
    CardRow(
        workflowIcon(data.status),
        data.name,
        suffix = workflowStatusLabel(data.status),
        running = data.status == WorkflowRunStatus.RUNNING,
        onClick = { detail = true },
    )
    if (detail) {
        CardDetailDialog(workflowIcon(data.status), data.name, null, { detail = false }) {
            data.phases.forEach { phase ->
                Column(Modifier.padding(bottom = 10.dp)) {
                    phase.phase?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    phase.members.forEach { member ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(workflowIcon(member.status), null, Modifier.size(16.dp))
                            Text(member.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun workflowIcon(status: WorkflowRunStatus): ImageVector = when (status) {
    WorkflowRunStatus.RUNNING -> Icons.Default.Autorenew
    WorkflowRunStatus.COMPLETED -> Icons.Default.CheckCircleOutline
    WorkflowRunStatus.FAILED -> Icons.Default.HighlightOff
    WorkflowRunStatus.CANCELLED -> Icons.Default.Block
    WorkflowRunStatus.INTERRUPTED -> Icons.Default.PauseCircleOutline
}

/** 过程折叠控件：一轮里的思考与工具调用折在它后面。 */
@Composable
private fun TurnProcessRow(spec: TurnProcessSpec, store: SessionStore?) {
    val expanded = store?.isTurnProcessExpanded(spec) ?: false
    val summary = buildList {
        if (spec.messageCount > 0) add(fmt(Strings.processMessages, spec.messageCount))
        if (spec.toolCallCount > 0) add(fmt(Strings.processTools, spec.toolCallCount))
        if (spec.subagentCount > 0) add(fmt(Strings.processSubagents, spec.subagentCount))
    }.ifEmpty { if (spec.inlineReasoning) listOf(Strings.thinking) else emptyList() }.joinToString(" · ")
    CardRow(
        if (expanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
        summary,
        onClick = { store?.setTurnProcessExpanded(spec, !expanded) },
    )
}

/** 一轮结束的页脚：复制回复、分支、用量与用时、结束时刻。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TurnTailRow(
    data: TurnTailData,
    onFork: (Int) -> Unit,
    model: PhoneModel,
    store: SessionStore?,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var usageOpen by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()) {
        // 本轮的改动文件与产出文件一起挂在回合尾部（web 把三者合并进 turnTail 链）。
        store?.let { ChangedFilesCard(model, it, data.turn) }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
        data.closingText?.takeIf { it.isNotEmpty() }?.let { text ->
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(text))
                copied = true
                scope.launch { kotlinx.coroutines.delay(1500); copied = false }
            }, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    Strings.copyAction,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = { onFork(data.seq) }, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.CallSplit, Strings.messageBranch, Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        data.usage?.takeIf { turnTotalTokens(it) > 0 }?.let { usage ->
            StatPill(Icons.Default.DataUsage, compactTokenCount(turnTotalTokens(usage))) { usageOpen = true }
        }
        data.runMs?.takeIf { it > 0 }?.let { runMs ->
            StatPill(Icons.Default.Schedule, formatDuration(runMs)) { timeOpen = true }
        }
        Text(
            clockTime(data.time),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 4.dp),
        )
        }
    }
    // 两个统计弹窗挂在版面之外（弹窗不占布局），所以留在 Column 之后。
    if (usageOpen) {
        val usage = data.usage
        StatDialog(Strings.turnUsageTitle, usage?.let { tokenCount(turnTotalTokens(it)) }, { usageOpen = false }) {
            if (data.routes.isNotEmpty()) StatRow(Strings.turnUsageModel, data.routes.joinToString(", "))
            usage?.let {
                cacheHitPercent(it)?.let { hit -> StatRow(Strings.turnUsageCacheHit, "$hit%") }
                StatRow(Strings.turnUsageInput, tokenCount(it.uncachedInputTokens ?: it.inputTokens ?: 0))
                it.cacheReadTokens?.let { v -> StatRow(Strings.turnUsageCacheRead, tokenCount(v)) }
                it.cacheWriteTokens?.let { v -> StatRow(Strings.turnUsageCacheWrite, tokenCount(v)) }
                val output = tokenCount(it.outputTokens ?: 0)
                val reasoning = it.reasoningTokens?.let { r -> " " + fmt(Strings.turnUsageReasoning, tokenCount(r)) }
                StatRow(Strings.turnUsageOutput, output + (reasoning ?: ""))
            }
        }
    }
    if (timeOpen) {
        StatDialog(Strings.turnTimeTitle, null, { timeOpen = false }) {
            data.runMs?.let { StatRow(Strings.turnTimeDuration, formatDuration(it)) }
            val tps = data.decodeTokensPerSec
                ?: data.runMs?.takeIf { it > 0 }?.let { ms ->
                    data.usage?.outputTokens?.takeIf { it > 0 }?.let { out -> out / (ms / 1000) }
                }
            tps?.takeIf { it > 0 }?.let {
                StatRow(Strings.turnTimeSpeed, fmt(Strings.messageTokensPerSecond, formatTokensPerSecond(it)))
            }
            data.ttftMs?.takeIf { it > 0 }?.let {
                StatRow(Strings.turnTimeTtft, fmt(Strings.durationSecondsText, formatLatencySeconds(it)))
            }
        }
    }
}

@Composable
private fun StatPill(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatDialog(
    title: String,
    headline: String?,
    onDismiss: () -> Unit,
    rows: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(Strings.done) } },
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                headline?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            }
        },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = rows) },
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

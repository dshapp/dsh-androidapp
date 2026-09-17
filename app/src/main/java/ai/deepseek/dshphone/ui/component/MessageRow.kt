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
    onFork: (Int) -> Unit,
) {
    when (node) {
        is ChatNode.User -> UserBubble(node.content, sessionId, model)
        is ChatNode.Steering -> UserBubble(node.content, sessionId, model)
        is ChatNode.Context -> ContextRow(node)
        is ChatNode.Assistant -> AssistantRow(node.step, sessionId, model, store, onOpenFile)
        is ChatNode.Tool -> ToolCard(node.data, cwd)
        is ChatNode.TurnError -> NoticeRow(
            Icons.Default.Warning, node.data.message, MaterialTheme.colorScheme.error, detailed = true,
        )
        is ChatNode.Compaction -> NoticeRow(Icons.Default.UnfoldLess, Strings.compacted)
        is ChatNode.ModelRetry -> NoticeRow(Icons.Default.Refresh, Strings.modelRetry, Color(0xFFE08600))
        is ChatNode.TurnMaxTokens -> NoticeRow(Icons.Default.ErrorOutline, Strings.maxTokens, Color(0xFFE08600))
        is ChatNode.Command -> NoticeRow(Icons.AutoMirrored.Filled.KeyboardArrowRight, "/" + (node.data.name ?: ""))
        // 收尾消息还没落定的轮次没有页脚（对齐 Mac 的 `TurnTailRow`）。
        is ChatNode.TurnTail -> if (node.rendersRow) TurnTailRow(node.data, onFork)
        is ChatNode.TurnProcess -> TurnProcessRow(node.spec, store)
        is ChatNode.SystemPrompt -> SystemPromptRow(node.data)
        is ChatNode.Workflow -> WorkflowRunCard(node.data)
        is ChatNode.UnknownSurface -> UnknownSurfaceRow(node.data)
    }
}

@Composable
private fun UserBubble(content: List<ContentBlock>, sessionId: SessionId, model: PhoneModel) {
    val text = plainText(content)
    val images = content.filterIsInstance<ContentBlock.Image>()
        .mapNotNull { it.attachment?.let { att -> decodeImageRef(att) } }
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
        images.forEach { ref -> AttachmentImage(sessionId, ref, model) }
        if (text.isNotEmpty()) {
            // 点气泡弹出「复制」小泡泡，一键复制整句；长按仍走系统文本选择。
            var copyMenu by remember(text) { mutableStateOf(false) }
            val clipboard = LocalClipboardManager.current
            val haptics = LocalHapticFeedback.current
            Box(Modifier.clickable { copyMenu = true }) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text,
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

private fun decodeImageRef(attachment: kotlinx.serialization.json.JsonElement): ImageAttachmentRef? =
    runCatching {
        ai.deepseek.dshphone.net.DshJson.decodeFromJsonElement(ImageAttachmentRef.serializer(), attachment)
    }.getOrNull()

@Composable
fun AttachmentImage(sessionId: SessionId, ref: ImageAttachmentRef, model: PhoneModel) {
    var bitmap by remember(ref.attachmentId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var full by remember { mutableStateOf(false) }
    LaunchedEffect(ref.attachmentId) { bitmap = model.attachmentImage(sessionId, ref) }
    val image = bitmap
    if (image != null) {
        Image(
            image.asImageBitmap(),
            contentDescription = ref.name,
            modifier = Modifier
                .heightIn(max = 220.dp)
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
        ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
    }
}

@Composable
private fun AssistantRow(
    step: AssistantStepData,
    sessionId: SessionId,
    model: PhoneModel,
    store: SessionStore?,
    onOpenFile: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    @Suppress("UNUSED_EXPRESSION")
    store?.deliverablesRevision
    val mentions = store?.closingMentions(step.turn, step.step, step.status == AssistantStatus.RUNNING)
        ?: emptyList()
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.rowSpacing),
    ) {
        step.blocks.filterNotNull().forEach { block ->
            when (block) {
                // 正文长按即进入系统文本选择（Android 原生复制路径）。
                is AssistantBlock.Text -> androidx.compose.foundation.text.selection.SelectionContainer(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dimens.messageTopInset)
                ) {
                    MarkdownText(block.text, fileMentions = mentions, onOpenFile = onOpenFile)
                }
                is AssistantBlock.Reasoning -> ThinkCard(block.text)
                is AssistantBlock.Image -> block.attachment?.let { att ->
                    decodeImageRef(att)?.let { AttachmentImage(sessionId, it, model) }
                }
                else -> Unit
            }
        }
        if (step.status == AssistantStatus.RUNNING && step.blocks.isEmpty()) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun ContextRow(node: ChatNode.Context) {
    var detail by remember { mutableStateOf(false) }
    val summary = node.provenanceLabel ?: Strings.contextInjected
    val icon = if (node.provenanceRole == "recall") Icons.Default.History else Icons.AutoMirrored.Filled.NoteAdd
    CardRow(icon, summary, onClick = { detail = true })
    if (detail) {
        val text = plainText(node.content)
        CardDetailDialog(icon, summary, text, { detail = false }) { SelectionText(text) }
    }
}

@Composable
private fun SystemPromptRow(data: SystemPromptChatNode) {
    var detail by remember { mutableStateOf(false) }
    val title = if (data.update) Strings.systemPromptUpdated else Strings.systemPrompt
    CardRow(Icons.AutoMirrored.Filled.Notes, title, onClick = { detail = true })
    if (detail) {
        CardDetailDialog(Icons.AutoMirrored.Filled.Notes, title, data.text, { detail = false }) {
            SelectionText(data.text, mono = true)
        }
    }
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
        suffix = data.status.name.lowercase(),
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
private fun TurnTailRow(data: TurnTailData, onFork: (Int) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var usageOpen by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
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

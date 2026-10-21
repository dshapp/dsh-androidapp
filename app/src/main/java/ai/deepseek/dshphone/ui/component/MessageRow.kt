package ai.deepseek.dshphone.ui.component

import ai.deepseek.dshphone.i18n.ManagementStrings
import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.net.get
import ai.deepseek.dshphone.net.managementRpc
import ai.deepseek.dshphone.net.str
import ai.deepseek.dshphone.state.PhoneModel
import ai.deepseek.dshphone.state.SessionStore
import ai.deepseek.dshphone.ui.theme.Dimens
import ai.deepseek.dshphone.ui.theme.DshChrome
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
        is ChatNode.TurnTrigger -> TurnTriggerRow(node.data, sessionId, model, onOpenSession, findQuery, findActive)
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
        is ChatNode.ToolChange -> ToolChangeRow(node.data)
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
    /** 定时任务送达的消息：气泡上方加一行来源标签，点开看任务详情。 */
    trigger: TurnTriggerNode? = null,
) {
    val fullText = plainText(content)
    // 圈选元素发来的 picker XML 显示成属性卡片（对齐 Mac / Chrome 插件）；
    // XML 之后的 [指令] 原话仍旧走普通气泡。
    val cards = remember(fullText) { XmlMessageCards.parse(fullText) }
    val text = cards?.remainder ?: fullText
    val images = lenientImageRefs(content)
    val files = fileAttachmentRefs(content)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 气泡最多占列宽的 82%，但短消息只占自己需要的宽度——
        // 旧写法是固定占比，一句「好」也会被拉成横贯半屏的长条。
        val bubbleMaxWidth = maxWidth * 0.82f
        // 卡片不是原话，可以比气泡宽一些（插件里卡片最宽到整列）。
        val cardMaxWidth = maxWidth * 0.92f
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Dimens.messageTopInset),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (trigger != null) ScheduleTriggerLabel(trigger, sessionId, model)
        ImageGallery(images, sessionId, model, alignEnd = true)
        files.forEach { MessageFileCard(it) }
        if (cards != null) {
            XmlMessageCardStack(cards, fullText, cardMaxWidth, findQuery, findActive)
        }
        // 只发文件/图片时没有文字，不画空气泡。
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

/** 用户消息里的一个上传文件（web `.fileCard`）：文件类型图标、文件名、扩展名与大小。 */
@Composable
private fun MessageFileCard(file: FileAttachmentRef) {
    val dot = file.name.lastIndexOf('.')
    val ext = if (dot > 0 && dot < file.name.length - 1) file.name.substring(dot + 1) else ""
    Row(
        Modifier
            .width(240.dp)
            .heightIn(min = 56.dp)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Dimens.bubbleRadius))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(fileTypeIcon(ext), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(
                middleTruncate(file.name, 28),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                listOf(ext.uppercase(java.util.Locale.ROOT), formatBytes(file.bytes)).filter { it.isNotEmpty() }.joinToString(" "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private fun fileTypeIcon(ext: String): ImageVector = when (ext.lowercase(java.util.Locale.ROOT)) {
    "pdf" -> Icons.Default.PictureAsPdf
    "png", "jpg", "jpeg", "gif", "webp", "heic", "bmp", "svg" -> Icons.Default.Image
    "mp4", "mov", "mkv", "webm", "avi" -> Icons.Default.Movie
    "mp3", "wav", "m4a", "flac", "ogg", "aac" -> Icons.Default.AudioFile
    "zip", "tar", "gz", "tgz", "rar", "7z", "xz", "bz2" -> Icons.Default.FolderZip
    "csv", "tsv", "xls", "xlsx", "numbers" -> Icons.Default.TableChart
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/** 文件名过长时截中间，保住开头和扩展名（Compose 1.7 还没有 MiddleEllipsis）。 */
private fun middleTruncate(name: String, max: Int): String {
    if (name.length <= max) return name
    val tail = (max - 1) / 2
    return name.take(max - 1 - tail) + "…" + name.takeLast(tail)
}

/**
 * 一张持久化图片：拿 attachmentId 走 session/attachment 取字节再解码。
 *
 * 尺寸按附件自己声明的 width/height 收在 [IMAGE_MAX_WIDTH]/[IMAGE_MAX_HEIGHT] 以内，
 * 声明缺失（0）时退回固定上限；取不到字节时给一个明确的失败占位，而不是一直转圈。
 *
 * 点一下交给系统自带的图片查看器（与正文里的图片链接同一条通路，见 [SystemImageViewer]）：
 * 双指放大、拖动由系统查看器负责。用户发送的图、助手回复的图、工具结果图都走这里。
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
    // 正在取原图交给系统查看器：防止连点起多个查看器。
    var opening by remember(ref.attachmentId) { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
                .clickable(enabled = !opening) {
                    opening = true
                    scope.launch {
                        try {
                            // 先把原始字节落到缓存，再用 ACTION_VIEW 起系统图片查看器。
                            val path = SystemImageViewer.attachmentPath(ref.attachmentId)
                            val file = SystemImageViewer.stageFile(context, path)
                            val staged = model.attachmentImageFile(sessionId, ref, file)
                            if (!staged) file.delete()
                            val opened = staged && SystemImageViewer.open(context, path, file)
                            if (!opened) {
                                android.widget.Toast.makeText(context, Strings.imageOpenFailed, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            opening = false
                        }
                    }
                },
            contentScale = ContentScale.Fit,
        )
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
                        // 只加在正文消息顶部（iOS PhoneLayout.messageTopInset）：
                        // 与上方的过程拉开节奏，下方紧接的卡片仍按 rowSpacing 紧排。
                        .padding(top = Dimens.messageTopInset)
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
                // 仍在流式输出的思考（运行中 step 的最后一个块）与运行中的工具行同一呼吸态；
                // 后面一旦出现正文/工具调用，或 step 结算，就停下。
                is AssistantBlock.Reasoning -> ThinkCard(
                    block.text, findQuery, findActive,
                    running = step.status == AssistantStatus.RUNNING && step.blocks.lastOrNull() === block,
                )
                // 图片块在上面成组处理了，这里不再重复。
                else -> Unit
            }
        }
    }
}

/**
 * 轮次触发通知：谁唤醒了这一轮（定时任务、后台任务、插件……），正文在详情里看。
 * 定时任务是用户自己写的指令晚些送达，读得出提醒时直接画成用户气泡。
 */
@Composable
private fun TurnTriggerRow(
    data: TurnTriggerNode,
    sessionId: SessionId,
    model: PhoneModel,
    onOpenSession: (SessionId) -> Unit,
    findQuery: String,
    findActive: Boolean,
) {
    val reply = remember(data) { data.questionReply }
    if (reply != null) {
        QuestionReplyRow(reply)
        return
    }
    val reminders = remember(data) { data.scheduleReminders }
    if (reminders.isNotEmpty()) {
        val content = remember(reminders) { listOf(ContentBlock.Text(reminders.joinToString("\n\n") { it.prompt })) }
        UserBubble(content, sessionId, model, onOpenSession, findQuery, findActive, trigger = data)
        return
    }
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

/** 定时任务气泡上方的来源标签「🕘 自动化任务」：点开弹层看任务名称、计划时间、运行时间与内容。 */
@Composable
private fun ScheduleTriggerLabel(data: TurnTriggerNode, sessionId: SessionId, model: PhoneModel) {
    var detail by remember { mutableStateOf(false) }
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { detail = true }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = DshChrome.label)
        Text(Strings.turnTriggerSchedule, style = MaterialTheme.typography.labelMedium, color = DshChrome.label)
    }
    if (detail) ScheduleTriggerDialog(data, sessionId, model) { detail = false }
}

/** 定时任务详情：逐条提醒列出自动化任务名称、计划时间、运行时间、任务内容与任务 ID（Mac 规则页同一组字段）。 */
@Composable
private fun ScheduleTriggerDialog(data: TurnTriggerNode, sessionId: SessionId, model: PhoneModel, onDismiss: () -> Unit) {
    val reminders = remember(data) { data.scheduleReminders }
    // 任务名称与频率要按 schedule_id 去任务目录里查；查不到就只显示 ID、不显示运行时间。
    var tasks by remember { mutableStateOf<List<ManagedAutomation>>(emptyList()) }
    LaunchedEffect(Unit) {
        val api = model.managementApi ?: return@LaunchedEffect
        tasks = try {
            api.managementRpc().catalog()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
    CardDetailDialog(
        Icons.Default.Schedule,
        Strings.turnTriggerSchedule,
        reminders.joinToString("\n\n") { it.prompt },
        onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            reminders.forEachIndexed { index, reminder ->
                if (index > 0) HorizontalDivider()
                val task = tasks.firstOrNull { it.id == reminder.scheduleId && it.sessionId == sessionId }
                    ?: tasks.firstOrNull { it.id == reminder.scheduleId }
                ScheduleField(ManagementStrings.taskName) { SelectionText(task?.title?.takeIf { it.isNotBlank() } ?: reminder.scheduleId) }
                ScheduleField(ManagementStrings.occurrence) { SelectionText(localTime(reminder.occurrenceAt)) }
                if (task != null) ScheduleField(ManagementStrings.runTime) { SelectionText(ManagementStrings.frequency(task.raw)) }
                ScheduleField(ManagementStrings.instruction) { SelectionText(reminder.prompt) }
                ScheduleField(ManagementStrings.taskId) { SelectionText(reminder.scheduleId, mono = true) }
            }
        }
    }
}

@Composable
private fun ScheduleField(label: String, value: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        value()
    }
}

/**
 * 迟到的提问回答是用户自己的短答（web `QuestionReplyView`）：问题和答案直接铺开，
 * 没有可展开的详情。
 */
@Composable
private fun QuestionReplyRow(pairs: List<QuestionReplyPair>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CardRow(turnTriggerIcon(TurnTriggerKind.QUESTION_REPLY), Strings.questionReplyLabel)
        pairs.forEach { pair ->
            Column(Modifier.padding(start = Dimens.iconSlot + 6.dp)) {
                Text(pair.question, style = MaterialTheme.typography.bodyMedium)
                Text(
                    Strings.questionReplyAnswer +
                        (pair.answers.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: Strings.questionReplySkipped),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    TurnTriggerKind.QUESTION_REPLY -> Strings.questionReplyLabel
}

private fun turnTriggerIcon(kind: TurnTriggerKind): ImageVector = when (kind) {
    TurnTriggerKind.REQUEST -> Icons.AutoMirrored.Filled.NoteAdd
    TurnTriggerKind.GOAL -> Icons.Default.Flag
    TurnTriggerKind.AGENT -> Icons.AutoMirrored.Filled.Send
    TurnTriggerKind.TEAM -> Icons.Default.Groups
    TurnTriggerKind.SUBAGENT -> Icons.Default.HowToReg
    TurnTriggerKind.GITHUB -> Icons.AutoMirrored.Filled.CallSplit
    TurnTriggerKind.WEBHOOK -> Icons.Default.Public
    TurnTriggerKind.SCHEDULE -> Icons.Default.Schedule
    TurnTriggerKind.JOB -> Icons.AutoMirrored.Filled.List
    TurnTriggerKind.PLUGIN -> Icons.Default.Extension
    TurnTriggerKind.QUESTION_REPLY -> Icons.AutoMirrored.Filled.Reply
}

@Composable
private fun UnknownSurfaceRow(data: UnknownSurfaceNode) {
    var detail by remember { mutableStateOf(false) }
    CardRow(Icons.Default.HelpOutline, data.type, onClick = { detail = true })
    if (detail) {
        CardDetailDialog(Icons.Default.HelpOutline, data.type, null, { detail = false }) { JsonTree(data.data) }
    }
}

/**
 * 工具增减提示行（web `ContextInjectionRow` 的工具形态）：单个变更就是一行提示；
 * 多个变更标题后跟灰色计数，点开详情弹层看名单。
 */
@Composable
private fun ToolChangeRow(data: ToolChangeNode) {
    var detail by remember { mutableStateOf(false) }
    val title = when {
        !data.single -> Strings.toolsUpdated
        data.added.isNotEmpty() -> fmt(Strings.toolAdded, data.added[0])
        else -> fmt(Strings.toolRemoved, data.removed[0])
    }
    val summary = when {
        data.single -> null
        data.added.isNotEmpty() && data.removed.isNotEmpty() ->
            fmt(Strings.toolsChangedCount, data.added.size, data.removed.size)
        data.added.isNotEmpty() -> fmt(Strings.toolsAddedCount, data.added.size)
        else -> fmt(Strings.toolsRemovedCount, data.removed.size)
    }
    CardRow(
        Icons.Default.Build,
        title,
        suffix = summary,
        onClick = if (data.single) null else ({ detail = true }),
    )
    if (detail) {
        val details = buildList {
            if (data.added.isNotEmpty()) add(fmt(Strings.toolsAddedList, data.added.joinToString(", ")))
            if (data.removed.isNotEmpty()) add(fmt(Strings.toolsRemovedList, data.removed.joinToString(", ")))
        }.joinToString("\n")
        CardDetailDialog(Icons.Default.Build, title, details, { detail = false }) { SelectionText(details) }
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

/** 运行状态条的固定高度：一行卡片行高加上下各 2dp。只在显示时由会话页让出这一格。 */
val RunningStatusStripHeight = Dimens.chromeRowHeight + 4.dp

/**
 * 运行状态条（对齐 Mac `RunningStatusRow`）：固定在输入框正上方、滚动区之外，
 * 内容是 小转圈 + 工作类别（[runningStatusLabel]）+ 「· 用时」。用时从本轮 turn/start
 * 起算、每秒刷新、下限 1 秒；拿不到 turn/start 时只显示文案。左边距与卡片图标对齐
 * （转圈坐在同一个 [Dimens.iconSlot] 槽里）。[visible] 为 false 时只占位不画。
 */
@Composable
fun RunningStatusStrip(
    visible: Boolean,
    nodes: List<ChatNode>,
    startTime: Double?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(RunningStatusStripHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!visible) return@Row
        val label = remember(nodes, Strings.locale) { runningStatusLabel(nodes) }
        var now by remember { mutableStateOf(System.currentTimeMillis().toDouble()) }
        LaunchedEffect(startTime) {
            while (startTime != null) {
                now = System.currentTimeMillis().toDouble()
                kotlinx.coroutines.delay(1000)
            }
        }
        Box(Modifier.size(Dimens.iconSlot), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = DshChrome.label,
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = DshChrome.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        runningStatusElapsed(startTime, now)?.let { elapsed ->
            Text(
                "· $elapsed",
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = DshChrome.label,
                maxLines = 1,
            )
        }
    }
}

/**
 * 轮次折叠头（对齐 Mac `TurnProcessRow`）：文案就是这一轮的结局（[label]），
 * 字号与助手正文同档、颜色是卡片标题的次级灰，不画背景；可折叠时文字右侧紧跟一个
 * 向下箭头，展开时转 180°，按下时文字提亮一档。下方一条整宽分割线。
 */
@Composable
private fun TurnProcessRow(spec: TurnProcessSpec, store: SessionStore?) {
    val expanded = store?.isTurnProcessExpanded(spec) ?: false
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint = if (spec.foldable && pressed) MaterialTheme.colorScheme.onSurfaceVariant else DshChrome.label
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(100), label = "turn-process-chevron")
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (spec.foldable) Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                ) { store?.setTurnProcessExpanded(spec, !expanded) } else Modifier
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                spec.label,
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (spec.foldable) {
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(rotation),
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
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
    if (data.outcome == TurnOutcome.FAILED || data.outcome == TurnOutcome.STOPPED) {
        // 停止/失败已由这一轮的折叠头（「已停止」「处理失败」）说明，轮尾不再重复一行提示；
        // 与 Mac `TurnTailRow` 一样只留本轮的文件卡片（没有收尾消息就没有操作行）。
        Column(Modifier.fillMaxWidth()) {
            store?.let {
                ChangedFilesCard(model, it, data.turn)
                PresentedFilesRow(model, it, data.turn)
            }
            PlanCards(data.plans)
        }
        return
    }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var usageOpen by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()) {
        // 本轮的改动文件与交付文件一起挂在回合尾部，顺序同 web `Deliverables.tsx`：
        // 改动卡片在前、`present` 交付卡片在后。
        store?.let {
            ChangedFilesCard(model, it, data.turn)
            PresentedFilesRow(model, it, data.turn)
        }
        // 计划卡片排在交付卡片之后、操作行之前（web ui-plan 的 turnTail 槽位）。
        PlanCards(data.plans)
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
            clockTime(data.endedAt ?: data.time),
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

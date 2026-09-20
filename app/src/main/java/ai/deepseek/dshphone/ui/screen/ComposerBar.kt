package ai.deepseek.dshphone.ui.screen

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.MessageId
import ai.deepseek.dshphone.net.SessionId
import ai.deepseek.dshphone.net.dbl
import ai.deepseek.dshphone.net.get
import ai.deepseek.dshphone.net.obj
import ai.deepseek.dshphone.state.*
import ai.deepseek.dshphone.ui.theme.Dimens
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 输入区：待办 → 目标 → 队列 → 候选 → 输入卡片（正文 + 一行控制）。
 * 与 Mac/iOS 的 composer dock 同序、同主机动作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerBar(
    model: PhoneModel,
    sessionId: SessionId,
    autoFocus: Boolean = false,
    /** 贴在输入区最上面的一行附加内容（新建会话页用来放工作区/预设胶囊）。 */
    header: (@Composable () -> Unit)? = null,
    onSubmitted: (SessionId) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = model.sessions[sessionId]
    val haptics = LocalHapticFeedback.current
    var draft by remember(sessionId) {
        val saved = model.draft(sessionId)
        mutableStateOf(TextFieldValue(saved, TextRange(saved.length)))
    }
    var attachments by remember(sessionId) { mutableStateOf<List<PhoneAttachment>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<PhoneSuggestion>>(emptyList()) }
    /** 加号启动器：草稿里没有斜杠 token 也把命令候选唤出来（Mac `toggleCommandLauncher` 的对应物）。 */
    var commandLauncher by remember(sessionId) { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var permissionMenu by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf(false) }
    var contextSheet by remember { mutableStateOf(false) }
    var pendingFullAccess by remember { mutableStateOf<String?>(null) }
    /**
     * 刚发出去的那串文字。
     *
     * 发送时我们把 value 改成空串，但输入法手里还攥着旧文字：结束组字/提交的那一下
     * BasicTextField 会拿旧文字再回调一次 onValueChange，草稿于是被写回模型，接着挂载的
     * 那个输入区（发完消息导航过去的会话页就是）又把它读了出来——看起来就是"发完了还留在
     * 输入框里"。这里记下刚发出去的内容，紧跟着的第一次原样回灌直接丢掉。
     */
    var sentEcho by remember(sessionId) { mutableStateOf<String?>(null) }

    // 回灌只会紧跟着发送发生；超过这一小会儿还没来就不必再防，免得误吞用户重新输入的同一句话。
    LaunchedEffect(sentEcho) {
        if (sentEcho == null) return@LaunchedEffect
        delay(1500)
        sentEcho = null
    }

    LaunchedEffect(sessionId) { model.loadModels(sessionId) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(4)
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            loadImageAttachment(context, uri)?.let { attachments = attachments + it }
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            loadFileAttachment(context, uri)?.let { attachments = attachments + it }
        }
    }

    /**
     * 带参数的命令（`input` 非空）只在行首上下文里出现，行中间只列不带参数的那些——
     * Mac `commandCandidates` 的 `position == .leading || command.input == nil` 口径。
     */
    fun filterForPosition(items: List<PhoneSuggestion>, leading: Boolean): List<PhoneSuggestion> =
        if (leading) items else items.filterNot { it.wantsArgs }

    fun refreshSuggestions(value: String) {
        val token = TriggerToken.parse(value)
        if (token == null) {
            suggestions = emptyList()
            return
        }
        scope.launch {
            val items = model.suggestions(token, sessionId)
            if (TriggerToken.parse(draft.text) == token) {
                suggestions = if (token.kind == TriggerToken.Kind.COMMAND) {
                    filterForPosition(items, draft.text.take(token.start).isBlank())
                } else {
                    items
                }
            }
        }
    }

    /**
     * 加号启动器：不管草稿里有没有斜杠 token，都把命令候选唤出来。
     *
     * Mac 的 `toggleCommandLauncher` 只换菜单、绝不动草稿；这里同样不碰正文，
     * 所以先输入文字再点加号不会再被清空。空查询等于"列出全部命令"。
     */
    fun launchCommandLauncher() {
        val at = draft.selection.end.coerceIn(0, draft.text.length)
        val leading = draft.text.take(at).isBlank()
        commandLauncher = true
        scope.launch {
            val items = model.suggestions(TriggerToken(TriggerToken.Kind.COMMAND, "", 0, 0), sessionId)
            if (commandLauncher) suggestions = filterForPosition(items, leading)
        }
    }

    val rejection = if (attachments.isEmpty()) null else model.attachmentRejection(attachments, sessionId)
    val takeover = store?.subagentAddress?.let {
        if (it.mode == SubagentMode.CONTINUABLE) Strings.subagentComposer else Strings.subagentReadOnly
    }
    val sendDisabled = (draft.text.isBlank() && attachments.isEmpty()) || rejection != null

    fun send() {
        if (rejection != null) return
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val address = store?.subagentAddress
        if (address != null) {
            if (address.mode != SubagentMode.CONTINUABLE) return
            val text = draft.text
            sentEcho = text
            draft = TextFieldValue("")
            model.setDraft("", sessionId)
            model.submitToSubagent(text, sessionId)
            // 子智能体页同样算"发出了新消息"，跟随底部要在那边也重新打开。
            onSubmitted(sessionId)
            return
        }
        val text = draft.text
        val files = attachments
        sentEcho = text
        draft = TextFieldValue("")
        attachments = emptyList()
        suggestions = emptyList()
        model.setDraft("", sessionId)
        // 提交交给 model 的 viewModelScope：onSubmitted 会把这张输入区连同它的
        // rememberCoroutineScope 一起带出 composition，挂在本地 scope 上会被取消。
        model.submit(text, sessionId, files)
        onSubmitted(sessionId)
    }

    // 外围与转写区同底色，只有内部输入卡片自己有底色（对齐 iOS）。
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.imePadding().navigationBarsPadding(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.columnInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            header?.invoke()
            TodoStrip(store)
            GoalBar(model, sessionId, store)
            if (store != null && store.queued.isNotEmpty()) QueueStrip(model, sessionId, store)
            if (suggestions.isNotEmpty()) {
                SuggestionList(suggestions) { suggestion ->
                    val token = TriggerToken.parse(draft.text)
                    if (token != null) {
                        // 替换触发 token，并把光标落到插入内容之后（胶囊后面）。
                        val text = draft.text.replaceRange(token.start, token.end, suggestion.insert)
                        draft = TextFieldValue(text, TextRange(token.start + suggestion.insert.length))
                        model.setDraft(text, sessionId)
                    } else if (commandLauncher) {
                        // 加号启动器：草稿里没有可替换的斜杠 token，就插到光标处（Mac pick 对零宽
                        // span 的处理），已经输入的正文原样留着。
                        val at = draft.selection.end.coerceIn(0, draft.text.length)
                        val text = draft.text.substring(0, at) + suggestion.insert + draft.text.substring(at)
                        draft = TextFieldValue(text, TextRange(at + suggestion.insert.length))
                        model.setDraft(text, sessionId)
                    }
                    commandLauncher = false
                    suggestions = emptyList()
                }
            }
            takeover?.let { InfoLine(Icons.Default.Group, it, MaterialTheme.colorScheme.onSurfaceVariant) }
            rejection?.let { InfoLine(Icons.Default.WarningAmber, it, Color(0xFFE08600)) }
            store?.historyError?.message?.let {
                InfoLine(Icons.Default.ErrorOutline, it, MaterialTheme.colorScheme.error)
            }
            if (attachments.isNotEmpty()) {
                AttachmentStrip(attachments) { id -> attachments = attachments.filterNot { it.id == id } }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(22.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BasicComposerField(
                    value = draft,
                    autoFocus = autoFocus,
                    onValueChange = {
                        val echo = sentEcho
                        sentEcho = null
                        // 输入法把刚发出去的那句原样送了回来：留住空输入框，别写回草稿。
                        if (echo != null && it.text == echo) {
                            draft = TextFieldValue("")
                            model.setDraft("", sessionId)
                            return@BasicComposerField
                        }
                        val next = atomicTokenEdit(draft, it)
                        draft = next
                        model.setDraft(next.text, sessionId)
                        commandLauncher = false
                        refreshSuggestions(next.text)
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            // Mac/iOS 的加号只是命令启动器：不覆盖草稿。草稿为空时仍落一个 "/"，
                            // 让"直接打字过滤命令"这条老路子（点加号 → 输入 plan → 选 Plan）保持不变；
                            // 草稿已有正文时只换菜单、绝不动正文（对齐 Mac toggleCommandLauncher）。
                            when {
                                commandLauncher -> {
                                    commandLauncher = false
                                    suggestions = emptyList()
                                }
                                draft.text.isEmpty() -> {
                                    draft = TextFieldValue("/", TextRange(1))
                                    model.setDraft("/", sessionId)
                                    commandLauncher = false
                                    refreshSuggestions("/")
                                }
                                else -> launchCommandLauncher()
                            }
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { attachMenu = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp),
                                MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(attachMenu, { attachMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(Strings.pickPhoto) },
                                leadingIcon = { Icon(Icons.Default.Image, null) },
                                onClick = {
                                    attachMenu = false
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(Strings.pickFile) },
                                leadingIcon = { Icon(Icons.Default.InsertDriveFile, null) },
                                onClick = { attachMenu = false; filePicker.launch(arrayOf("*/*")) },
                            )
                        }
                    }
                    Row(
                        Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        // 去掉胶囊底色后，两段纯文本之间需要更明显的间距才不会读成一句。
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            TextAction(
                                store?.permissions?.currentOption?.let { permissionTitle(it.value, it.name) }
                                    ?: Strings.permissions,
                                warn = store?.permissions?.currentValue == "danger-full-access",
                            ) { permissionMenu = true }
                            DropdownMenu(permissionMenu, { permissionMenu = false }) {
                                store?.permissions?.selectableOptions?.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(permissionTitle(option.value, option.name)) },
                                        onClick = {
                                            permissionMenu = false
                                            if (option.value == "danger-full-access") {
                                                pendingFullAccess = option.value
                                            } else {
                                                model.selectPermission(option.value, sessionId)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        if (store?.planModeTarget == true) {
                            Row(
                                Modifier
                                    .clip(CircleShape)
                                    .background(Color(0x22E08600))
                                    .clickable { model.setPlanMode(false, sessionId) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(Strings.planMode, style = MaterialTheme.typography.labelLarge,
                                    color = Color(0xFFE08600))
                                Icon(Icons.Default.Close, null, Modifier.size(12.dp), Color(0xFFE08600))
                            }
                        }
                        TextAction(store?.modelDirectory?.current?.model ?: Strings.model) { modelPicker = true }
                    }
                    store?.contextOccupancy?.let { occupancy ->
                        ContextRing(occupancy.percent) { contextSheet = true }
                    }
                    if (store?.running == true && draft.text.isBlank()) {
                        IconButton(
                            onClick = {
                                if (store.subagentAddress != null) model.interruptSubagent(sessionId)
                                else model.stop(sessionId)
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Default.Stop, Strings.stop,
                                Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error)
                                    .padding(4.dp),
                                tint = MaterialTheme.colorScheme.onError,
                            )
                        }
                    } else {
                        IconButton(
                            // 回合在跑时发送就是排队（Mac/web 的 enterBehavior 默认 queue），
                            // 插队是队列行上那颗单独的按钮——不点它就不该抢进正在跑的回合。
                            onClick = { send() },
                            enabled = !sendDisabled,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send, Strings.send,
                                Modifier.size(20.dp),
                                tint = if (sendDisabled) MaterialTheme.colorScheme.outline
                                else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }

    if (modelPicker) ModelPickerSheet(model, sessionId) { modelPicker = false }
    if (contextSheet) ContextSheet(model, sessionId) { contextSheet = false }
    pendingFullAccess?.let { value ->
        AlertDialog(
            onDismissRequest = { pendingFullAccess = null },
            title = { Text(Strings.fullAccessTitle) },
            text = { Text(Strings.fullAccessMessage) },
            confirmButton = {
                TextButton(onClick = {
                    model.selectPermission(value, sessionId)
                    pendingFullAccess = null
                }) { Text(Strings.fullAccessConfirm) }
            },
            dismissButton = {
                TextButton(onClick = { pendingFullAccess = null }) { Text(Strings.cancel) }
            },
        )
    }
}

/**
 * 输入框正文。
 *
 * 用 BasicTextField 而不是 M3 TextField：后者自带 56dp 起的容器与内边距，
 * 装在输入卡片里会把整条输入区顶得很高，且容器色要一层层改回透明。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BasicComposerField(
    value: TextFieldValue,
    autoFocus: Boolean = false,
    onValueChange: (TextFieldValue) -> Unit,
) {
    val tokens = remember(value.text) { composerTokens(value.text) }
    val tokenText = MaterialTheme.colorScheme.primary
    val pill = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // 触发 token 在正文里着色；胶囊底色画在文字下方（BasicTextField 无法直接带圆角背景）。
    val decorate = remember(tokens, tokenText) {
        VisualTransformation { original ->
            TransformedText(
                AnnotatedString(
                    original.text,
                    tokens.map {
                        AnnotatedString.Range(
                            SpanStyle(color = tokenText, fontWeight = FontWeight.Medium),
                            it.first, it.last + 1,
                        )
                    },
                ),
                OffsetMapping.Identity,
            )
        }
    }
    // 新建会话弹层里直接把光标落进输入框并唤起键盘；弹层刚挂载时窗口还没拿到焦点，
    // requestFocus 会抛 IllegalStateException，所以等一帧再试，失败了再补一次。
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        withFrameNanos { }
        if (runCatching { focusRequester.requestFocus() }.isFailure) {
            delay(120)
            runCatching { focusRequester.requestFocus() }
        }
        keyboard?.show()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
        if (value.text.isEmpty()) {
            Text(
                Strings.composerPlaceholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .drawBehind {
                    val result = layout ?: return@drawBehind
                    tokens.forEach { drawTokenPill(result, it, pill) }
                },
            maxLines = 6,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            visualTransformation = decorate,
            onTextLayout = { layout = it },
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}

/**
 * 正文中要画成胶囊的触发 token：行首的 `/指令`、空白后的 `$skill` 与 `@引用`
 * （与 TriggerToken.parse 的识别规则一致，只是这里要同时命中已完成的 token）。
 */
private fun composerTokens(text: String): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var i = 0
    while (i < text.length) {
        val head = text[i]
        val atBoundary = i == 0 || text[i - 1] == ' ' || text[i - 1] == '\n'
        if (atBoundary && (head == '@' || head == '$' || (head == '/' && i == 0))) {
            var end = i + 1
            while (end < text.length && text[end] != ' ' && text[end] != '\n') end++
            if (end > i + 1) out.add(i until end)
            i = end
        } else {
            i++
        }
    }
    return out
}

/**
 * 把胶囊当一个整体删：退格删掉的那个字符落在某个 token 里时，整段 token 一起删，
 * 光标落到 token 原来的位置；其余编辑（包括多选删除、输入法组字）原样放行。
 */
private fun atomicTokenEdit(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    if (new.text.length != old.text.length - 1) return new
    val cut = new.selection.start
    if (cut < 0 || cut >= old.text.length) return new
    // 只认"删掉 old[cut] 这一个字符"的退格，别的编辑不改写。
    if (old.text.removeRange(cut, cut + 1) != new.text) return new
    val token = composerTokens(old.text).firstOrNull { cut >= it.first && cut <= it.last } ?: return new
    return TextFieldValue(old.text.removeRange(token.first, token.last + 1), TextRange(token.first))
}

/** 按行画一段 token 的胶囊底（token 换行时每行各画一段）。 */
private fun DrawScope.drawTokenPill(layout: TextLayoutResult, range: IntRange, color: Color) {
    if (range.last + 1 > layout.layoutInput.text.length) return
    val firstLine = layout.getLineForOffset(range.first)
    val lastLine = layout.getLineForOffset(range.last)
    for (line in firstLine..lastLine) {
        val from = maxOf(range.first, layout.getLineStart(line))
        val to = minOf(range.last + 1, layout.getLineEnd(line, visibleEnd = true))
        if (to <= from) continue
        val left = layout.getHorizontalPosition(from, usePrimaryDirection = true)
        val right = layout.getHorizontalPosition(to, usePrimaryDirection = true)
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)
        val inset = 3.dp.toPx()
        drawRoundRect(
            color,
            topLeft = Offset(left - inset, top),
            size = Size(right - left + inset * 2, bottom - top),
            cornerRadius = CornerRadius((bottom - top) / 2f),
        )
    }
}

fun permissionTitle(value: String, fallback: String): String = when (value) {
    "read-only" -> Strings.accessRead
    "workspace-write" -> Strings.accessWrite
    "danger-full-access" -> Strings.accessFull
    else -> fallback
}

/** 权限/模型切换：纯文本展示，不带胶囊底色。 */
@Composable
private fun TextAction(text: String, warn: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (warn) Color(0xFFE08600) else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/** 上下文占用环。 */
@Composable
private fun ContextRing(percent: Int, onClick: () -> Unit) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val progress = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
            drawCircle(track, style = Stroke(width = 2.dp.toPx()))
            drawArc(
                color = progress,
                startAngle = -90f,
                sweepAngle = 360f * (percent.coerceIn(0, 100) / 100f),
                useCenter = false,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint)
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 待办带：只在有条目时出现，点开看全部。 */
@Composable
private fun TodoStrip(store: SessionStore?) {
    val todos = store?.todos ?: return
    if (todos.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val done = todos.count { it.status == "completed" }
    val active = todos.count { it.status == "in_progress" || it.status == "in-progress" }
    val pending = todos.size - done - active
    val header = todos.firstOrNull { it.status == "in_progress" || it.status == "in-progress" }?.content
        ?: Strings.todoPanelTitle
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Checklist, null, Modifier.size(16.dp))
            // 分组之间用「·」连接，与 iOS 的 progressLabel 同构。
            val groups = buildList {
                if (done > 0) add(Triple(Icons.Default.CheckCircle, done, Color(0xFF2E7D32)))
                if (pending > 0) add(Triple(Icons.Default.RadioButtonUnchecked, pending, Color(0xFFE08600)))
                if (active > 0) add(Triple(Icons.Default.Autorenew, active, MaterialTheme.colorScheme.primary))
            }
            groups.forEachIndexed { index, (icon, count, color) ->
                if (index > 0) {
                    Text("·", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                StatusCount(icon, count, color)
            }
            Text(header, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null,
                Modifier.size(18.dp), MaterialTheme.colorScheme.outline,
            )
        }
        if (expanded) {
            // 图标按正文首行的行高居中：Top 对齐会让图标顶到行框上沿，和字面看着错开一格。
            val itemStyle = MaterialTheme.typography.bodyMedium
            val firstLineHeight = with(LocalDensity.current) { itemStyle.lineHeight.toDp() }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                todos.forEach { todo ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.height(firstLineHeight), contentAlignment = Alignment.Center) {
                            Icon(todoIcon(todo.status), null, Modifier.size(13.dp), todoColor(todo.status))
                        }
                        Text(todo.content, style = itemStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCount(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, null, Modifier.size(12.dp), color)
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

fun todoIcon(status: String): androidx.compose.ui.graphics.vector.ImageVector = when (status) {
    "completed" -> Icons.Default.CheckCircle
    "in_progress", "in-progress" -> Icons.Default.Autorenew
    else -> Icons.Default.RadioButtonUnchecked
}

@Composable
fun todoColor(status: String): Color = when (status) {
    "completed" -> Color(0xFF2E7D32)
    "in_progress", "in-progress" -> MaterialTheme.colorScheme.primary
    else -> Color(0xFFE08600)
}

/** 目标条：有目标才出现，可暂停/恢复、编辑、清除。 */
@Composable
private fun GoalBar(model: PhoneModel, sessionId: SessionId, store: SessionStore?) {
    // 用 goalBarGoal（= activeGoal）而不是 store.goal 原文：完成态的目标不能画出来，
    // 否则条上的暂停/编辑/清除会全部静默失效（它们都经 goalRef、而 goalRef 折掉完成态）。
    val goal = store?.goalBarGoal ?: return
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var objectiveDraft by remember { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.stripRowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.stripSpacing),
    ) {
        Icon(
            if (goal.phase == "paused") Icons.Default.PauseCircleOutline else Icons.Default.Flag,
            null, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(goal.objective, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreHoriz, null, Modifier.size(18.dp))
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (goal.phase == "paused") Strings.goalResume else Strings.goalPause) },
                    onClick = { menu = false; model.toggleGoalPause(sessionId) },
                )
                DropdownMenuItem(
                    text = { Text(Strings.goalEdit) },
                    onClick = { menu = false; objectiveDraft = goal.objective; editing = true },
                )
                DropdownMenuItem(
                    // 与 iOS 的 destructive role 同色。
                    text = { Text(Strings.goalClear, color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; model.clearGoal(sessionId) },
                )
            }
        }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(Strings.goalEdit) },
            text = {
                OutlinedTextField(objectiveDraft, { objectiveDraft = it },
                    label = { Text(Strings.goalObjective) })
            },
            confirmButton = {
                TextButton(onClick = {
                    model.editGoal(objectiveDraft, sessionId)
                    editing = false
                }) { Text(Strings.submit) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(Strings.cancel) } },
        )
    }
}

/**
 * 排队中的消息：可编辑、插队发送、撤回。
 *
 * 只画 placement == "queued"（下一轮才会取走的那些），与 Mac 的 QueueDock / web 的队列同口径。
 * 一旦点了插队发送，主机会把它挪进 next-step，控制流随即推来 placement == "steering" 的帧；
 * 那一行此刻已经在跑的回合里了，不属于"排队"，必须立刻从这条上消失——否则行文一模一样地留在原地，
 * 看起来就像按钮没反应（重复点还会撞上主机的 steer-unavailable）。它改由转写尾部的
 * 待定气泡（ChatScreen 里的 pendingSteering）接管显示。
 */
@Composable
private fun QueueStrip(model: PhoneModel, sessionId: SessionId, store: SessionStore) {
    var editing by remember { mutableStateOf<QueuedInboxItem?>(null) }
    var editDraft by remember { mutableStateOf("") }
    // 提交中（等主机回声）的那些行先禁掉按钮：避免同一行被连点两次，
    // 第二次必然撞上 queue-item-not-found。
    var busy by remember { mutableStateOf<Set<MessageId>>(emptySet()) }
    val scope = rememberCoroutineScope()
    // 一行同一时刻只发一个动作；提交中就把这颗按钮禁掉。
    fun mutate(itemId: MessageId, block: suspend () -> Unit) {
        if (itemId in busy) return
        busy = busy + itemId
        scope.launch {
            try { block() } finally { busy = busy - itemId }
        }
    }
    val queued = store.queued
    Column(Modifier.fillMaxWidth()) {
        queued.forEachIndexed { index, item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(Dimens.stripRowHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.stripSpacing),
            ) {
                Icon(Icons.Default.Schedule, null, Modifier.size(16.dp),
                    MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.text, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(
                    onClick = {
                        editDraft = item.text
                        editing = item
                    },
                    enabled = item.id !in busy,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Default.Edit, Strings.queueEdit, Modifier.size(16.dp))
                }
                // 插队发送与 Mac 的 QueueRow 同口径：回合没在跑就没有可插的队，
                // 此时留位但禁用；提交中同样禁掉，防连点撞上 steer-unavailable。
                val steerable = store.running && item.id !in busy
                IconButton(
                    onClick = { mutate(item.id) { model.steerQueued(item.id, sessionId) } },
                    enabled = steerable,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        Strings.queueSteer,
                        Modifier.size(16.dp),
                        tint = if (steerable) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    )
                }
                IconButton(
                    onClick = { mutate(item.id) { model.removeQueued(item.id, sessionId) } },
                    enabled = item.id !in busy,
                    modifier = Modifier.size(32.dp),
                ) { Icon(Icons.Default.Close, Strings.queueRemove, Modifier.size(16.dp)) }
            }
            if (index < queued.size - 1) HorizontalDivider()
        }
    }
    editing?.let { item ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(Strings.queueEdit) },
            text = { OutlinedTextField(editDraft, { editDraft = it }) },
            confirmButton = {
                TextButton(onClick = {
                    val text = editDraft
                    editing = null
                    mutate(item.id) { model.editQueued(item.id, text, sessionId) }
                }) { Text(Strings.submit) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(Strings.cancel) } },
        )
    }
}

/** 候选条：贴在输入框上方，点按插入。 */
@Composable
private fun SuggestionList(suggestions: List<PhoneSuggestion>, onPick: (PhoneSuggestion) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 208.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .verticalScrollSafe(),
    ) {
        suggestions.forEach { suggestion ->
            // 单行：完整名称 + 描述（名称不压缩，描述放不下省略）。
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(suggestion) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    suggestion.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
                // 带参数的命令把用法提示贴在名字后面（Mac 的 candidate.hint）。
                suggestion.hint?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                suggestion.subtitle?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        it,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Modifier.verticalScrollSafe(): Modifier = this.verticalScroll(rememberScrollState())

/** 输入区上方的附件条。 */
@Composable
private fun AttachmentStrip(attachments: List<PhoneAttachment>, onRemove: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            InputChip(
                selected = false,
                onClick = { onRemove(attachment.id) },
                label = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                avatar = {
                    Icon(
                        if (attachment.payload is PhoneAttachmentPayload.Image) Icons.Default.Image
                        else Icons.Default.InsertDriveFile,
                        null, Modifier.size(16.dp),
                    )
                },
                trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) },
            )
        }
    }
}

// —— 附件读取 ————————————————————————————————————————————————

private fun loadImageAttachment(context: android.content.Context, uri: Uri): PhoneAttachment? = runCatching {
    val resolver = context.contentResolver
    val mediaType = resolver.getType(uri) ?: "image/jpeg"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    PhoneAttachment(
        name = displayName(context, uri) ?: "image",
        payload = PhoneAttachmentPayload.Image(mediaType, Base64.encodeToString(bytes, Base64.NO_WRAP), bytes),
    )
}.getOrNull()

private fun loadFileAttachment(context: android.content.Context, uri: Uri): PhoneAttachment? = runCatching {
    val resolver = context.contentResolver
    val name = displayName(context, uri) ?: "file"
    val mediaType = resolver.getType(uri) ?: ""
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (mediaType.startsWith("image/")) {
        return PhoneAttachment(
            name = name,
            payload = PhoneAttachmentPayload.Image(mediaType, Base64.encodeToString(bytes, Base64.NO_WRAP), bytes),
        )
    }
    val id = java.util.UUID.randomUUID().toString()
    val copy = File(context.cacheDir, stagedAttachmentFileName(name, id))
    copy.writeBytes(bytes)
    PhoneAttachment(id = id, name = name, payload = PhoneAttachmentPayload.FilePayload(copy))
}.getOrNull()

/**
 * 暂存文件在 cacheDir 里的文件名。
 *
 * [name] 来自打开文档时读取的 DISPLAY_NAME（或退化到 URI 的最后一段），是用户可见的原始
 * 文件名；[id] 是这条附件的唯一 id。
 *
 * 名字里必须带上 [id]：两个同名文件（两个 README.md）各自有自己的暂存文件，否则后一份
 * 内容会覆盖前一份，发送时两条附件上传同一份内容。同时要清掉路径分隔符与控制字符——
 * DISPLAY_NAME 可能含 "/"，而退化路径 uri.lastPathSegment 对 tree URI
 * （content://…/tree/primary%3ADownload/document/primary%3ADownload%2Ffile.txt）本身就是
 * "primary:Download/file.txt"，直接当路径写会抛异常被吞掉，附件无声消失。
 *
 * 原始文件名仍原样留在 PhoneAttachment.name 上（列表显示、上传时告诉主机用的是它），
 * 这里只决定本地暂存路径。
 */
internal fun stagedAttachmentFileName(name: String, id: String): String {
    val cleaned = buildString {
        for (ch in name) {
            append(if (ch == '/' || ch == '\\' || ch == '\u0000' || ch.isISOControl()) '_' else ch)
        }
    }.trim().trim('.').take(120)
    val safe = if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "file" else cleaned
    return "$id-$safe"
}

private fun displayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

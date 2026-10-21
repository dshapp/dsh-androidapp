package ai.deepseek.dshphone.ui.component

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import ai.deepseek.dshphone.model.ChangedFileDiff
import ai.deepseek.dshphone.model.ChangedFileEntry
import ai.deepseek.dshphone.model.TurnChangesAddress
import ai.deepseek.dshphone.state.PhoneModel
import ai.deepseek.dshphone.state.SessionStore
import ai.deepseek.dshphone.state.WorkspaceImageLoad
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.HelpCenter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 展开前显示的文件行数（对齐 web `COLLAPSED_ROWS` 与 Mac 的 `shownLimit`）。 */
private const val COLLAPSED_ROWS = 4

private val AddedTint = Color(0xFF2E7D32)
private val DeletedTint = Color(0xFFC62828)

/**
 * 一轮改动文件卡片（web ui-deliverables 的 changed-files card，0.1.6）。
 *
 * 公告（`workspace/changes`）是持久的；汇总与逐文件比较要趁会话还活着去主机读，
 * 所以这里三步：公告给出地址 → 读汇总画列表 → 用户点某个文件才去读它的比较。
 */
@Composable
fun ChangedFilesCard(model: PhoneModel, store: SessionStore, turn: Int) {
    // 地址是 observation-ignored 派生值，靠这个修订标量唤醒重组：首次渲染早于
    // 公告落库的卡片，否则永远不知道记录已经存在（与 Mac 读 deliverablesRevision 同理）。
    @Suppress("UNUSED_EXPRESSION")
    store.deliverablesRevision
    val address = store.changesAddress(turn) ?: return
    ChangedFilesCardBody(model, address)
}

@Composable
private fun ChangedFilesCardBody(model: PhoneModel, address: TurnChangesAddress) {
    val summary = model.turnChanges.summary(address)
    // 主机还没答就什么都不挂（web 在读取期间不显示卡片）。
    LaunchedEffect(address) { model.loadTurnChanges(address) }
    if (summary == null || summary.files.isEmpty()) return
    var expanded by remember(address) { mutableStateOf(false) }
    var reviewing by remember(address) { mutableStateOf<ChangedFileEntry?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Default.FolderOpen, null, Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                fmt(Strings.changedFilesTitle, summary.total),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                "+${summary.added}",
                style = MaterialTheme.typography.labelMedium,
                color = AddedTint,
            )
            Text(
                "−${summary.deleted}",
                style = MaterialTheme.typography.labelMedium,
                color = DeletedTint,
            )
        }
        val rows = if (expanded) summary.files else summary.files.take(COLLAPSED_ROWS)
        rows.forEach { file ->
            ChangedFileRow(file) { reviewing = file }
        }
        if (summary.files.size > COLLAPSED_ROWS) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (expanded) Strings.changedFilesCollapse
                    else fmt(Strings.changedFilesShowAll, summary.files.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    reviewing?.let { file ->
        ChangedFileDiffDialog(model, address, file) { reviewing = null }
    }
}

@Composable
private fun ChangedFileRow(file: ChangedFileEntry, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            file.display,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            file.binary -> Text(
                Strings.changedFilesBinary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            file.oversized -> Text(
                Strings.changedFilesOversized,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            else -> {
                if (file.added > 0) Text("+${file.added}", style = MaterialTheme.typography.labelSmall, color = AddedTint)
                if (file.deleted > 0) Text("−${file.deleted}", style = MaterialTheme.typography.labelSmall, color = DeletedTint)
            }
        }
    }
}

/** 一个文件的轮首/轮末比较；只有真的点开才去读（对齐 Mac 的 review sheet）。 */
@Composable
private fun ChangedFileDiffDialog(
    model: PhoneModel,
    address: TurnChangesAddress,
    file: ChangedFileEntry,
    onDismiss: () -> Unit,
) {
    val index = remember(address, file) {
        model.turnChanges.summary(address)?.files?.indexOfFirst { it.path == file.path } ?: -1
    }
    val diff = if (index >= 0) model.turnChanges.diff(address, index) else null
    LaunchedEffect(address, index) {
        if (index >= 0 && diff == null) model.loadTurnChangesDiff(address, index)
    }
    // 二进制文件先按路径取图，与比较请求并行，不等比较先回（对齐 Mac）。
    // path 在会话工作目录内是相对路径，readBytes 的会话作用域正好以该目录为根解析。
    var image by remember(address, file) { mutableStateOf<WorkspaceImageLoad?>(null) }
    LaunchedEffect(address, file) {
        if (file.binary) image = model.workspaceImageLoad(address.sessionId, file.path)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(Strings.done) } },
        title = {
            Text(
                file.display,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            val loaded = image
            when {
                loaded is WorkspaceImageLoad.Loaded -> ChangedImage(loaded.bitmap, file.display)
                loaded is WorkspaceImageLoad.Missing -> FileMissing()
                diff == null || (file.binary && loaded == null) -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                diff is ChangedFileDiff.Binary -> Text(Strings.changedDiffBinary)
                diff is ChangedFileDiff.Oversized -> Text(Strings.changedDiffOversized)
                diff is ChangedFileDiff.Text -> DiffBody(diff, file)
            }
        },
    )
}

/** 原始尺寸（px 当 dp，与正文内联图一致），宽度不超过弹窗，小图不放大。 */
@Composable
private fun ChangedImage(bitmap: android.graphics.Bitmap, description: String) {
    Box(
        Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = description,
            modifier = Modifier
                .widthIn(max = bitmap.width.dp)
                .fillMaxWidth()
                .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)),
            contentScale = ContentScale.Fit,
        )
    }
}

/** 主机报告文件不存在：居中的空状态。 */
@Composable
private fun FileMissing() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.HelpCenter, null, Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(Strings.changedFileMissing, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            Strings.changedFileMissingDetail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DiffBody(diff: ChangedFileDiff.Text, file: ChangedFileEntry) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (diff.coarse) {
            Text(
                Strings.changedDiffCoarse,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (diff.hunks.isEmpty()) {
            Text(Strings.changedDiffUnchanged, style = MaterialTheme.typography.bodySmall)
        }
        diff.hunks.forEach { hunk ->
            Text(
                "@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
            )
            hunk.lines.forEach { line ->
                val tint = when (line.firstOrNull()) {
                    '+' -> AddedTint
                    '-' -> DeletedTint
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(
                    line.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = tint,
                )
            }
        }
        if (file.oversized) Text(Strings.changedDiffOversized, style = MaterialTheme.typography.labelSmall)
    }
}

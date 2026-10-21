package ai.deepseek.dshphone.ui.component

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.i18n.fmt
import ai.deepseek.dshphone.model.PresentedFile
import ai.deepseek.dshphone.model.SubmittedPlan
import ai.deepseek.dshphone.model.pathBasename
import ai.deepseek.dshphone.state.PhoneModel
import ai.deepseek.dshphone.state.SessionStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 交付卡片折叠前显示的张数（web `COLLAPSED_PRESENTED_COUNT` 与 Mac 的 collapsedCount）。 */
private const val COLLAPSED_PRESENTED_COUNT = 4

/**
 * 一轮 `present` 声明的交付文件（web `Deliverables` 的 presented 半边）。
 *
 * 与改动卡片同挂在回合尾部、排在它后面（web `Deliverables.tsx` 先画
 * `ChangedFiles` 再画交付卡片）。主机是远端的，点卡片就是让主机用它的桌面打开。
 */
@Composable
fun PresentedFilesRow(model: PhoneModel, store: SessionStore, turn: Int) {
    // `present` 声明可能晚于首次渲染到达，靠这个修订标量唤醒重组（与改动卡片同理）。
    @Suppress("UNUSED_EXPRESSION")
    store.deliverablesRevision
    val files = store.presentedFiles(turn)
    // 一轮没有任何声明就什么都不挂（web `selectDeliverables` 空数组返回 null）。
    if (files.isEmpty()) return
    val collapsible = files.size > COLLAPSED_PRESENTED_COUNT
    var expanded by remember(turn) { mutableStateOf(false) }
    val shown = if (collapsible && !expanded) files.take(COLLAPSED_PRESENTED_COUNT) else files

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        shown.forEach { file ->
            PresentedFileCard(file) { model.openOnHost(file.path) }
        }
        if (collapsible) {
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
                    if (expanded) Strings.presentedCollapse else fmt(Strings.presentedAll, files.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 一张交付卡片：文件身份 + 模型写的说明，点它让主机打开这个文件。 */
@Composable
private fun PresentedFileCard(file: PresentedFile, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.InsertDriveFile, null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(
                pathBasename(file.path).ifEmpty { file.path },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // 说明缺席时退回文件自身的类型，卡片仍然答得出「这是什么」（web 同款兜底）。
                file.description?.takeIf { it.isNotBlank() } ?: fileKind(file.path),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 本轮提交的计划（web ui-plan `PlanCards`）：Markdown 文档图标、计划标题与「打开」，
 * 点卡片或按钮都在详情弹层里看完整计划。
 */
@Composable
fun PlanCards(plans: List<SubmittedPlan>) {
    if (plans.isEmpty()) return
    var opened by remember { mutableStateOf<SubmittedPlan?>(null) }
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        plans.forEach { plan ->
            OutlinedCard(
                onClick = { opened = plan },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = fmt(Strings.planCardOpenNamed, plan.title) },
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Description, null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            plan.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            Strings.planCardKind,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    TextButton(onClick = { opened = plan }) { Text(Strings.planCardAction) }
                }
            }
        }
    }
    opened?.let { plan ->
        CardDetailDialog(Icons.Outlined.Description, plan.title, plan.markdown, { opened = null }) {
            MarkdownText(plan.markdown)
        }
    }
}

private fun fileKind(path: String): String {
    val name = pathBasename(path)
    val dot = name.lastIndexOf('.')
    // 点开头或结尾都不算扩展名（`.gitignore`、`notes.`）。
    if (dot <= 0 || dot == name.length - 1) return Strings.presentedFileKind
    return name.substring(dot + 1).uppercase(java.util.Locale.ROOT)
}
package ai.deepseek.dshphone.ui.screen

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.state.PhoneModel
import ai.deepseek.dshphone.state.ServerProfile
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 服务页：列表页左上角状态点的落点。
 *
 * 取代原来的下拉菜单——一页之内看全已配对的机器、切换/重命名/移除，再往下是当前
 * 服务的设置区与「添加新服务」入口；添加走的仍是扫码配对那条老路。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    model: PhoneModel,
    onBack: () -> Unit,
    onScan: () -> Unit,
) {
    var renaming by remember { mutableStateOf<ServerProfile?>(null) }
    var removing by remember { mutableStateOf<ServerProfile?>(null) }
    var nameDraft by remember { mutableStateOf("") }

    // 最后一台机器被移除后这页没有内容可展示了，退回根部由引导页接手。
    val empty = model.profiles.profiles.isEmpty()
    LaunchedEffect(empty) { if (empty) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Strings.servers) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
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
                .padding(bottom = 24.dp),
        ) {
            model.profiles.profiles.forEach { profile ->
                val current = profile.id == model.profiles.currentId
                ListItem(
                    modifier = Modifier.clickable { model.connect(profile) },
                    headlineContent = {
                        Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(profile.relayHost, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    // 选中勾贴最左边；没选中的行占同样宽度的空位，机器名才不会错位。
                    leadingContent = {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            if (current) {
                                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    trailingContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // 颜色点就是状态，所以下面的设置区不再单列「状态」一行。
                            if (current) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(connectionTone(model).first),
                                )
                            }
                            IconButton(onClick = { nameDraft = profile.name; renaming = profile }) {
                                Icon(Icons.Default.Edit, Strings.serverRename, Modifier.size(18.dp))
                            }
                            // 删除是破坏性动作：图标标红，真正的移除仍要走二次确认对话框。
                            IconButton(onClick = { removing = profile }) {
                                Icon(
                                    Icons.Default.Delete,
                                    Strings.serverRemove,
                                    Modifier.size(18.dp),
                                    MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                )
            }
            Text(
                Strings.serversFooter,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 当前服务区只剩「最近连接」，没连过就整段不画，免得留一个空标题。
            model.profiles.current?.lastConnectedAt?.let { connectedAt ->
                SectionTitle(Strings.currentServer)
                LabeledRow(
                    Strings.lastConnected,
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(connectedAt)),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                modifier = Modifier.clickable { onScan() },
                headlineContent = { Text(Strings.addServer) },
                leadingContent = { Icon(Icons.Default.QrCodeScanner, null) },
            )
        }
    }

    removing?.let { profile ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(Strings.serverRemove) },
            text = { Text("${profile.name} · ${Strings.serverRemoveMessage}") },
            confirmButton = {
                TextButton(onClick = {
                    model.profiles.remove(profile.id)
                    removing = null
                }) { Text(Strings.remove, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(Strings.cancel) } },
        )
    }
    renaming?.let { profile ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(Strings.serverRename) },
            text = { OutlinedTextField(nameDraft, { nameDraft = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    model.profiles.rename(profile.id, nameDraft)
                    renaming = null
                }) { Text(Strings.submit) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(Strings.cancel) } },
        )
    }
}

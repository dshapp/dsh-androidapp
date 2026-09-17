package ai.deepseek.dshphone.ui.screen

import ai.deepseek.dshphone.i18n.AppLocale
import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.state.AppThemeMode
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 设置页：列表页左上角状态点的落点。
 *
 * 取代原来的下拉菜单——一页之内看全已配对的机器、切换/重命名/移除，再往下是当前
 * 服务的摘要与「外观」区（语言、主题）；新增服务收在标题栏右上角的加号里，走的仍是
 * 扫码配对那条老路。语言与主题都是本机偏好，跟连接哪台 Mac 无关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    model: PhoneModel,
    onBack: () -> Unit,
    onScan: () -> Unit,
) {
    var renaming by remember { mutableStateOf<ServerProfile?>(null) }
    var removing by remember { mutableStateOf<ServerProfile?>(null) }
    var nameDraft by remember { mutableStateOf("") }
    var languageMenu by remember { mutableStateOf(false) }
    var themeMenu by remember { mutableStateOf(false) }

    // 最后一台机器被移除后这页没有内容可展示了，退回根部由引导页接手。
    val empty = model.profiles.profiles.isEmpty()
    LaunchedEffect(empty) { if (empty) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Strings.settings) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                // 添加新服务从页尾挪到这里：加号是这页唯一的新增动作，不用再滚到底。
                actions = {
                    IconButton(onClick = onScan) {
                        Icon(Icons.Default.Add, Strings.addServer)
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
            // 当前服务区只剩「最近连接」，没连过就整段不画，免得留一个空标题。
            model.profiles.current?.lastConnectedAt?.let { connectedAt ->
                SectionTitle(Strings.currentServer)
                LabeledRow(
                    Strings.lastConnected,
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(connectedAt)),
                )
            }

            // 外观区：语言与主题都是本机偏好，选中即刻生效并落盘，不必重启 App。
            SectionTitle(Strings.appearance)
            Box {
                SettingRow(Strings.language, languageLabel(model.settings.locale)) { languageMenu = true }
                DropdownMenu(languageMenu, { languageMenu = false }) {
                    ChoiceItem(Strings.languageSystem, model.settings.locale == null) {
                        model.settings.chooseLocale(null)
                        languageMenu = false
                    }
                    ChoiceItem(Strings.languageEnglish, model.settings.locale == AppLocale.EN) {
                        model.settings.chooseLocale(AppLocale.EN)
                        languageMenu = false
                    }
                    ChoiceItem(Strings.languageChinese, model.settings.locale == AppLocale.ZH) {
                        model.settings.chooseLocale(AppLocale.ZH)
                        languageMenu = false
                    }
                }
            }
            Box {
                SettingRow(Strings.theme, themeLabel(model.settings.themeMode)) { themeMenu = true }
                DropdownMenu(themeMenu, { themeMenu = false }) {
                    ChoiceItem(Strings.themeSystem, model.settings.themeMode == AppThemeMode.SYSTEM) {
                        model.settings.chooseThemeMode(AppThemeMode.SYSTEM)
                        themeMenu = false
                    }
                    ChoiceItem(Strings.themeLight, model.settings.themeMode == AppThemeMode.LIGHT) {
                        model.settings.chooseThemeMode(AppThemeMode.LIGHT)
                        themeMenu = false
                    }
                    ChoiceItem(Strings.themeDark, model.settings.themeMode == AppThemeMode.DARK) {
                        model.settings.chooseThemeMode(AppThemeMode.DARK)
                        themeMenu = false
                    }
                }
            }
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

/** 一行「标签 + 当前值 + 箭头」的设置项；点开由调用方挂的下拉菜单。 */
@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(label) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    Modifier.size(20.dp),
                    MaterialTheme.colorScheme.outline,
                )
            }
        },
    )
}

@Composable
private fun ChoiceItem(title: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title) },
        leadingIcon = { if (selected) Icon(Icons.Default.Check, null) },
        onClick = onClick,
    )
}

/** 「跟随系统」不是 AppLocale 的成员：偏好为空就是跟着系统，这里只是把它显示出来。 */
private fun languageLabel(locale: AppLocale?): String = when (locale) {
    null -> Strings.languageSystem
    AppLocale.EN -> Strings.languageEnglish
    AppLocale.ZH -> Strings.languageChinese
}

private fun themeLabel(mode: AppThemeMode): String = when (mode) {
    AppThemeMode.SYSTEM -> Strings.themeSystem
    AppThemeMode.LIGHT -> Strings.themeLight
    AppThemeMode.DARK -> Strings.themeDark
}


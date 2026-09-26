package cn.zgccrui.passwordswitch

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            MaterialTheme(colorScheme = scheme) {
                val model: ManagerViewModel = viewModel()
                val updater: UpdateViewModel = viewModel()
                val state by model.state.collectAsStateWithLifecycle()
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                LaunchedEffect(lifecycle) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        model.reconnect()
                        updater.check()
                    }
                }
                ManagerScreen(state, model, updater)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagerScreen(state: ManagerState, model: ManagerViewModel, updater: UpdateViewModel) {
    var picker by remember { mutableStateOf<ProviderKind?>(null) }
    var confirmApply by rememberSaveable { mutableStateOf(false) }
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var updateOpen by rememberSaveable { mutableStateOf(false) }
    val updates by updater.state.collectAsStateWithLifecycle()
    LaunchedEffect(updates.available) { if (updates.available != null) updateOpen = true }
    val context = LocalContext.current
    val ready = state.connection == ConnectionState.READY && state.snapshot != null && !state.busy

    fun open(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: ActivityNotFoundException) { model.notice("系统没有提供这个入口。", error = true) }
        catch (failure: SecurityException) { model.notice("系统限制了这个入口。", failure.toString(), true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("密码管理器切换") }, actions = {
                TextButton(onClick = model::reconnect, enabled = !state.busy) { Text("刷新") }
            })
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxSize().imePadding(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    Text("让密码与通行密钥，交给你选择的应用。", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(when (state.connection) {
                                ConnectionState.STOPPED -> "先启动 Shizuku"
                                ConnectionState.PERMISSION -> "需要 Shizuku 授权"
                                ConnectionState.CONNECTING -> "正在连接 Shizuku…"
                                ConnectionState.READY -> "Shizuku 已连接"
                            }, style = MaterialTheme.typography.titleMedium)
                            Text(when (state.connection) {
                                ConnectionState.STOPPED -> "在 Shizuku 中通过无线调试启动服务，返回后刷新。无需 root。"
                                ConnectionState.PERMISSION -> "允许本应用通过 Shizuku 读取和修改默认服务。"
                                ConnectionState.CONNECTING -> "连接成功后会读取当前系统配置。"
                                ConnectionState.READY -> "仅修改系统服务选择，不读取密码或通行密钥内容。"
                            }, style = MaterialTheme.typography.bodyMedium)
                            if (state.connection != ConnectionState.READY && state.connection != ConnectionState.CONNECTING) {
                                Button(onClick = {
                                    if (state.connection == ConnectionState.PERMISSION) model.requestPermission()
                                    else {
                                        val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                        open(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                                    }
                                }) { Text(if (state.connection == ConnectionState.PERMISSION) "授予权限" else "打开 Shizuku") }
                            }
                            if (state.connection == ConnectionState.CONNECTING || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
                if (state.message.isNotEmpty()) item {
                    Surface(shape = RoundedCornerShape(12.dp), color = if (state.error)
                        MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(state.message, style = MaterialTheme.typography.bodyMedium)
                            if (state.detail.isNotEmpty()) TextButton(onClick = { details = !details }) {
                                Text(if (details) "收起详情" else "查看详情")
                            }
                            if (details && state.detail.isNotEmpty()) SelectionContainer {
                                Text(state.detail, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("当前系统配置", style = MaterialTheme.typography.titleLarge)
                        CurrentRow("首选凭据提供者", summary(state.snapshot, SettingKey.PRIMARY, model))
                        CurrentRow("自动填充服务", summary(state.snapshot, SettingKey.AUTOFILL, model))
                        Text("已启用凭据提供者：${summary(state.snapshot, SettingKey.CREDENTIALS, model)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state.checkedAt?.let {
                            Text("最近读回 ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { HorizontalDivider() }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("选择密码管理器", style = MaterialTheme.typography.titleLarge)
                        Text("两项可以分别选择。只列出当前用户下已安装并启用、声明了对应服务的应用。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ServiceSelector("凭据提供者", "用于密码与通行密钥的创建和登录", state.credential,
                            model, !state.busy && state.scanned) { picker = ProviderKind.CREDENTIAL }
                        ServiceSelector("自动填充服务", "用于登录表单中的账号和密码填充", state.autofill,
                            model, !state.busy && state.scanned) { picker = ProviderKind.AUTOFILL }
                        if (state.credential != null) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text("保留其他已启用的凭据提供者", style = MaterialTheme.typography.bodyMedium)
                                    Text(if (state.keepOthers) "只将所选应用设为首选。" else "凭据提供者列表将仅保留所选服务。",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(state.keepOthers, model::keepOthers, enabled = !state.busy)
                            }
                        }
                        Button(onClick = { confirmApply = true }, enabled = state.canApply,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("应用更改") }
                        if (!ready && !state.busy) Text("连接 Shizuku 并成功读取配置后，即可应用更改。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmRestore = true }, enabled = ready && state.backup != null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("恢复上次配置") }
                        Text("每次应用前保存配置，写入后重新读取核对。系统更新或其他应用仍可能改回设置，可随时刷新查看。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { open(Intent(Settings.ACTION_SETTINGS)) }) { Text("打开系统设置") }
                        TextButton(onClick = { updateOpen = true; updater.check(manual = true) }) {
                            Text("v${BuildConfig.VERSION_NAME} · 检查更新")
                        }
                        TextButton(onClick = { details = !details }) { Text(if (details) "收起技术详情" else "系统配置详情") }
                        if (details) SelectionContainer {
                            Text(buildString {
                                appendLine("Android 用户：${state.snapshot?.userId ?: "尚未读取"}")
                                SettingKey.entries.forEach {
                                    appendLine("${it.wireName}\n${if (state.snapshot == null) "尚未读取" else state.snapshot[it] ?: "未设置"}\n")
                                }
                            }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
    picker?.let { kind ->
        ProviderPicker(kind, state, onDismiss = { picker = null }) { value ->
            model.select(kind, value)
            picker = null
        }
    }
    if (updateOpen) UpdateDialog(updates, onDismiss = { updateOpen = false }, onRetry = { updater.check(manual = true) }) {
        open(Intent(Intent.ACTION_VIEW, Uri.parse(it.downloadUrl)))
        updateOpen = false
    }
    if (confirmApply) AlertDialog(
        onDismissRequest = { confirmApply = false }, title = { Text("确认更改默认服务") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.credential?.let {
                    Text("首选凭据提供者\n${summary(state.snapshot, SettingKey.PRIMARY, model)} → ${model.label(it)}")
                    Text(if (state.keepOthers) "保留其他已启用的凭据提供者。" else "已启用凭据提供者列表将仅保留 ${model.label(it)}。")
                    when (state.providers.firstOrNull { entry -> entry.component == it }?.supportsPasskeys) {
                        false -> Text("这个服务没有声明支持通行密钥；只能按它实际支持的凭据类型使用。")
                        null -> Text("无法确认这个服务的通行密钥支持情况。")
                        true -> Unit
                    }
                }
                state.autofill?.let { Text("自动填充服务\n${summary(state.snapshot, SettingKey.AUTOFILL, model)} → ${model.label(it)}") }
                Text("请仅选择你信任的密码管理器。未选择的项目保持原样，操作前的配置会保存在本机。")
            }
        },
        confirmButton = { TextButton(enabled = state.canApply, onClick = { confirmApply = false; model.apply() }) { Text("确认应用") } },
        dismissButton = { TextButton(onClick = { confirmApply = false }) { Text("取消") } },
    )
    if (confirmRestore) AlertDialog(
        onDismissRequest = { confirmRestore = false }, title = { Text("恢复上次配置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("首选凭据提供者：${summary(state.backup, SettingKey.PRIMARY, model)}")
                Text("已启用凭据提供者：${summary(state.backup, SettingKey.CREDENTIALS, model)}")
                Text("自动填充服务：${summary(state.backup, SettingKey.AUTOFILL, model)}")
                Text("将恢复以上三项系统配置。如果原应用已卸载或停用，恢复配置不会使它重新可用。")
            }
        },
        confirmButton = { TextButton(enabled = ready, onClick = { confirmRestore = false; model.restore() }) { Text("确认恢复") } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("取消") } },
    )
}

private fun summary(snapshot: SettingsSnapshot?, key: SettingKey, model: ManagerViewModel): String {
    if (snapshot == null) return "尚未读取"
    val entries = components(snapshot[key])
    return if (entries.isEmpty()) "未设置" else entries.joinToString("、") { model.label(it) }
}

@Composable
private fun CurrentRow(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ServiceSelector(title: String, subtitle: String, value: String?, model: ManagerViewModel, enabled: Boolean, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(16.dp)) {
            Text(value?.let { "${model.label(it)} · 待应用" } ?: "选择应用（当前保持不变）", Modifier.weight(1f))
            Text("选择", Modifier.padding(start = 12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPicker(kind: ProviderKind, state: ManagerState, onDismiss: () -> Unit, onSelect: (String?) -> Unit) {
    var search by remember { mutableStateOf("") }
    val entries = state.providers.filter { it.kind == kind }
    val matches = entries.filter { it.appName.contains(search, true) || it.packageName.contains(search, true) || it.component.contains(search, true) }
    val selected = if (kind == ProviderKind.CREDENTIAL) state.credential else state.autofill
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(if (kind == ProviderKind.CREDENTIAL) "选择凭据提供者" else "选择自动填充服务",
            Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            label = { Text("搜索应用名称或包名") }, singleLine = true)
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                ListItem(headlineContent = { Text("保持当前配置") }, supportingContent = { Text("本次不修改这一项") },
                    leadingContent = { RadioButton(selected == null, onClick = null) },
                    modifier = Modifier.selectable(selected == null, enabled = !state.busy, role = Role.RadioButton) { onSelect(null) })
            }
            items(matches, key = { it.component }) { entry ->
                ListItem(
                    headlineContent = { Text(entry.appName) },
                    supportingContent = {
                        Column {
                            Text(entry.packageName)
                            if (entries.count { it.packageName == entry.packageName } > 1) Text(entry.component.substringAfter('/'))
                            if (kind == ProviderKind.CREDENTIAL) Text(when (entry.supportsPasskeys) {
                                true -> "声明支持通行密钥"
                                false -> "未声明通行密钥支持"
                                null -> "通行密钥支持情况未知"
                            })
                        }
                    },
                    leadingContent = {
                        entry.icon?.let { Image(it.asImageBitmap(), contentDescription = null, Modifier.size(40.dp)) }
                            ?: Surface(Modifier.size(40.dp), RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                Box(contentAlignment = Alignment.Center) { Text(entry.appName.take(1)) }
                            }
                    },
                    trailingContent = { RadioButton(selected == entry.component, onClick = null) },
                    modifier = Modifier.selectable(selected == entry.component, enabled = !state.busy, role = Role.RadioButton) { onSelect(entry.component) },
                )
            }
            if (matches.isEmpty()) item {
                Text(if (entries.isEmpty()) "未发现可用服务。请先安装并打开密码管理器，完成初始化后返回刷新；该应用也需要支持此类系统服务。" else "没有匹配的应用。",
                    Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

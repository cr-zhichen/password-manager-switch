package cn.zgccrui.passwordswitch

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
    var rawSettings by rememberSaveable { mutableStateOf(false) }
    var updateOpen by rememberSaveable { mutableStateOf(false) }
    val updates by updater.state.collectAsStateWithLifecycle()
    LaunchedEffect(updates.available) { if (updates.available != null) updateOpen = true }
    val context = LocalContext.current
    val ready = state.connection == ConnectionState.READY && state.snapshot != null && !state.busy
    val selectable = !state.busy && state.scanned
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val direction = LocalLayoutDirection.current

    fun open(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: ActivityNotFoundException) { model.notice("系统没有提供这个入口。", error = true) }
        catch (failure: SecurityException) { model.notice("系统限制了这个入口。", failure.toString(), true) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(title = { Text("密码管理器切换") }, scrollBehavior = scrollBehavior, actions = {
                IconButton(onClick = model::reconnect, enabled = !state.busy) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = "刷新")
                }
            })
        },
    ) { insets ->
        // Content scrolls behind the navigation bar; only the bottom inset becomes list padding.
        Box(
            Modifier.fillMaxSize().padding(
                start = insets.calculateStartPadding(direction), top = insets.calculateTopPadding(),
                end = insets.calculateEndPadding(direction),
            ),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = insets.calculateBottomPadding() + 32.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("让密码与通行密钥，交给你选择的应用。", Modifier.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ConnectionPanel(state, onAction = {
                            if (state.connection == ConnectionState.PERMISSION) model.requestPermission()
                            else {
                                val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                open(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                            }
                        })
                        if (state.message.isNotEmpty()) MessageBanner(state.message, state.detail, state.error)
                    }
                }
                item {
                    val current = state.current(SettingKey.PRIMARY, ProviderKind.CREDENTIAL)
                    ServiceSection(
                        title = "凭据提供者", description = "用于密码与通行密钥的创建和登录",
                        value = summary(state.snapshot, SettingKey.PRIMARY, model), icon = current?.icon,
                        detail = state.enabledProviders(model),
                        pending = state.credential?.let(model::label), enabled = selectable,
                        onChange = { picker = ProviderKind.CREDENTIAL },
                        onUndo = { model.select(ProviderKind.CREDENTIAL, null) },
                    ) {
                        if (state.credential != null) Row(
                            Modifier.fillMaxWidth()
                                .toggleable(state.keepOthers, enabled = !state.busy, role = Role.Switch, onValueChange = model::keepOthers)
                                .padding(start = 72.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                                Text("保留其他已启用的凭据提供者", style = MaterialTheme.typography.bodyMedium)
                                Text(if (state.keepOthers) "只将所选应用设为首选。" else "凭据提供者列表将仅保留所选服务。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(state.keepOthers, onCheckedChange = null, enabled = !state.busy)
                        }
                    }
                }
                item {
                    ServiceSection(
                        title = "自动填充服务", description = "用于登录表单中的账号和密码填充",
                        value = summary(state.snapshot, SettingKey.AUTOFILL, model),
                        icon = state.current(SettingKey.AUTOFILL, ProviderKind.AUTOFILL)?.icon, detail = null,
                        pending = state.autofill?.let(model::label), enabled = selectable,
                        onChange = { picker = ProviderKind.AUTOFILL },
                        onUndo = { model.select(ProviderKind.AUTOFILL, null) },
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { confirmApply = true }, enabled = state.canApply,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("应用更改") }
                        val hint = when {
                            state.busy -> null
                            !ready -> "连接 Shizuku 并成功读取配置后，即可应用更改。"
                            state.credential == null && state.autofill == null -> "点按上方服务选择新的应用，确认后才会写入系统设置。"
                            else -> null
                        }
                        hint?.let {
                            Text(it, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader("更多操作")
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                ActionRow(R.drawable.ic_history, "恢复上次配置",
                                    if (state.backup != null) "恢复到最近一次应用前的配置" else "暂无可恢复的配置",
                                    enabled = ready && state.backup != null) { confirmRestore = true }
                                ActionRow(R.drawable.ic_settings, "打开系统设置") { open(Intent(Settings.ACTION_SETTINGS)) }
                                ActionRow(R.drawable.ic_download, "检查更新", "当前版本 ${BuildConfig.VERSION_NAME}") {
                                    updateOpen = true
                                    updater.check(manual = true)
                                }
                                ActionRow(R.drawable.ic_code, "系统配置详情", "查看三项系统设置的原始值",
                                    trailing = if (rawSettings) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                                ) { rawSettings = !rawSettings }
                                if (rawSettings) SelectionContainer {
                                    Text(buildString {
                                        appendLine("Android 用户：${state.snapshot?.userId ?: "尚未读取"}")
                                        SettingKey.entries.forEach {
                                            append("\n${it.wireName}\n${if (state.snapshot == null) "尚未读取" else state.snapshot[it] ?: "未设置"}\n")
                                        }
                                    }.trimEnd(), Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Text("每次应用前保存配置，写入后重新读取核对。系统更新或其他应用仍可能改回设置，可随时刷新查看。",
                            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.busy || state.connection == ConnectionState.CONNECTING) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    picker?.let { kind ->
        val key = if (kind == ProviderKind.CREDENTIAL) SettingKey.PRIMARY else SettingKey.AUTOFILL
        ProviderPicker(kind, state, summary(state.snapshot, key, model), onDismiss = { picker = null }) { value ->
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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                state.credential?.let {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChangeLine("首选凭据提供者", summary(state.snapshot, SettingKey.PRIMARY, model), model.label(it))
                        Text(if (state.keepOthers) "保留其他已启用的凭据提供者。" else "已启用凭据提供者列表将仅保留 ${model.label(it)}。")
                        when (state.providers.firstOrNull { entry -> entry.component == it }?.supportsPasskeys) {
                            false -> Text("这个服务没有声明支持通行密钥；只能按它实际支持的凭据类型使用。",
                                color = MaterialTheme.colorScheme.error)
                            null -> Text("无法确认这个服务的通行密钥支持情况。", color = MaterialTheme.colorScheme.error)
                            true -> Unit
                        }
                    }
                }
                state.autofill?.let { ChangeLine("自动填充服务", summary(state.snapshot, SettingKey.AUTOFILL, model), model.label(it)) }
                Text("请仅选择你信任的密码管理器。未选择的项目保持原样，操作前的配置会保存在本机。",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = state.canApply, onClick = { confirmApply = false; model.apply() }) { Text("确认应用") } },
        dismissButton = { TextButton(onClick = { confirmApply = false }) { Text("取消") } },
    )
    if (confirmRestore) AlertDialog(
        onDismissRequest = { confirmRestore = false }, title = { Text("恢复上次配置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LabeledValue("首选凭据提供者", summary(state.backup, SettingKey.PRIMARY, model))
                LabeledValue("已启用凭据提供者", summary(state.backup, SettingKey.CREDENTIALS, model))
                LabeledValue("自动填充服务", summary(state.backup, SettingKey.AUTOFILL, model))
                Text("将恢复以上三项系统配置。如果原应用已卸载或停用，恢复配置不会使它重新可用。",
                    style = MaterialTheme.typography.bodySmall)
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

/** The installed service behind the first component of a setting, used only for its icon. */
private fun ManagerState.current(key: SettingKey, kind: ProviderKind): ProviderEntry? {
    val component = runCatching { components(snapshot?.get(key)).firstOrNull() }.getOrNull() ?: return null
    return providers.firstOrNull { it.kind == kind && it.component == component }
}

/** The enabled list only adds information when it differs from the preferred provider. */
private fun ManagerState.enabledProviders(model: ManagerViewModel): String? {
    val snapshot = snapshot ?: return null
    val enabled = runCatching { components(snapshot[SettingKey.CREDENTIALS]) }.getOrNull()
    val primary = runCatching { components(snapshot[SettingKey.PRIMARY]) }.getOrNull()
    if (enabled != null && enabled == primary) return null
    return "已启用：${summary(snapshot, SettingKey.CREDENTIALS, model)}"
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ConnectionPanel(state: ManagerState, onAction: () -> Unit) {
    if (state.connection == ConnectionState.READY) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Shizuku 已连接", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        state.checkedAt?.let {
                            Text("读回于 ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}",
                                Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("仅修改系统服务选择，不读取密码或通行密钥内容。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (state.connection) {
                ConnectionState.STOPPED -> "先启动 Shizuku"
                ConnectionState.PERMISSION -> "需要 Shizuku 授权"
                else -> "正在连接 Shizuku…"
            }, style = MaterialTheme.typography.titleMedium)
            Text(when (state.connection) {
                ConnectionState.STOPPED -> "在 Shizuku 中通过无线调试启动服务，返回后刷新。无需 root。"
                ConnectionState.PERMISSION -> "允许本应用通过 Shizuku 读取和修改默认服务。"
                else -> "连接成功后会读取当前系统配置。"
            }, style = MaterialTheme.typography.bodyMedium)
            if (state.connection != ConnectionState.CONNECTING) {
                Button(onClick = onAction, Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
                    Text(if (state.connection == ConnectionState.PERMISSION) "授予权限" else "打开 Shizuku")
                }
            }
        }
    }
}

@Composable
private fun MessageBanner(message: String, detail: String, error: Boolean) {
    var expanded by rememberSaveable(message) { mutableStateOf(false) }
    val container = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(shape = RoundedCornerShape(16.dp), color = container, contentColor = content) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, end = 8.dp,
            bottom = if (detail.isEmpty()) 14.dp else 4.dp)) {
            Row(Modifier.padding(end = 8.dp)) {
                Icon(painterResource(if (error) R.drawable.ic_error else R.drawable.ic_info), contentDescription = null,
                    Modifier.size(20.dp))
                Text(message, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (detail.isNotEmpty()) {
                if (expanded) SelectionContainer {
                    Text(detail, Modifier.padding(start = 32.dp, top = 8.dp, end = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { expanded = !expanded }, Modifier.align(Alignment.End),
                    colors = ButtonDefaults.textButtonColors(contentColor = content)) {
                    Text(if (expanded) "收起详情" else "查看详情")
                }
            }
        }
    }
}

@Composable
private fun ServiceSection(
    title: String,
    description: String,
    value: String,
    icon: Bitmap?,
    detail: String?,
    pending: String?,
    enabled: Boolean,
    onChange: () -> Unit,
    onUndo: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Column {
        SectionHeader(title)
        Text(description, Modifier.padding(start = 4.dp, top = 2.dp, end = 4.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(Modifier.padding(top = 12.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled, onClickLabel = "更换$title", role = Role.Button, onClick = onChange)
                        .heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(icon, letter = null)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(value, style = MaterialTheme.typography.titleMedium)
                        detail?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                        Modifier.alpha(if (enabled) 1f else 0.38f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pending != null) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        Text("应用后改为 $pending", Modifier.weight(1f).padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = onUndo, enabled = enabled) { Text("撤销") }
                    }
                }
                extra()
            }
        }
    }
}

@Composable
private fun AppIcon(icon: Bitmap?, letter: String?) {
    if (icon != null) {
        val image = remember(icon) { icon.asImageBitmap() }
        Image(image, contentDescription = null, Modifier.size(40.dp))
        return
    }
    Surface(Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            if (letter != null) Text(letter, style = MaterialTheme.typography.titleMedium)
            else Icon(painterResource(R.drawable.ic_key), contentDescription = null, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ActionRow(
    @DrawableRes icon: Int,
    title: String,
    supporting: String? = null,
    enabled: Boolean = true,
    @DrawableRes trailing: Int? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled, role = Role.Button, onClick = onClick).heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp).alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            supporting?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.let { Icon(painterResource(it), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ChangeLine(label: String, from: String, to: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(buildAnnotatedString {
            append("$from → ")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)) { append(to) }
        }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPicker(
    kind: ProviderKind,
    state: ManagerState,
    currentLabel: String,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val entries = state.providers.filter { it.kind == kind }
    val matches = entries.filter { it.appName.contains(search, true) || it.packageName.contains(search, true) || it.component.contains(search, true) }
    val selected = if (kind == ProviderKind.CREDENTIAL) state.credential else state.autofill
    val key = if (kind == ProviderKind.CREDENTIAL) SettingKey.PRIMARY else SettingKey.AUTOFILL
    val inUse = runCatching { components(state.snapshot?.get(key)) }.getOrDefault(emptySet())
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(if (kind == ProviderKind.CREDENTIAL) "选择凭据提供者" else "选择自动填充服务",
                style = MaterialTheme.typography.headlineSmall)
            Text("只列出当前用户下已安装并启用、声明了对应服务的应用。", Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                placeholder = { Text("搜索应用名称或包名") }, singleLine = true, shape = RoundedCornerShape(28.dp),
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) })
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                PickerRow(selected == null, !state.busy, onClick = { onSelect(null) }, headline = "保持当前配置", leading = {
                    Surface(Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_push_pin), contentDescription = null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }) {
                    PickerDetail(if (state.snapshot == null) "本次不修改这一项" else "本次不修改这一项 · 当前为 $currentLabel")
                }
            }
            items(matches, key = { it.component }) { entry ->
                PickerRow(
                    selected == entry.component, !state.busy, onClick = { onSelect(entry.component) },
                    headline = entry.appName, badge = if (entry.component in inUse) "当前使用" else null,
                    leading = { AppIcon(entry.icon, entry.appName.take(1)) },
                ) {
                    PickerDetail(entry.packageName)
                    if (entries.count { it.packageName == entry.packageName } > 1) PickerDetail(entry.component.substringAfter('/'))
                    if (kind == ProviderKind.CREDENTIAL) Text(
                        when (entry.supportsPasskeys) {
                            true -> "声明支持通行密钥"
                            false -> "未声明通行密钥支持"
                            null -> "通行密钥支持情况未知"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (entry.supportsPasskeys == true) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (matches.isEmpty()) item {
                Text(if (entries.isEmpty()) "未发现可用服务。请先安装并打开密码管理器，完成初始化后返回刷新；该应用也需要支持此类系统服务。" else "没有匹配的应用。",
                    Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PickerRow(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    headline: String,
    badge: String? = null,
    leading: @Composable () -> Unit,
    supporting: @Composable ColumnScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 72.dp).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(headline, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium)
                badge?.let {
                    Surface(Modifier.padding(start = 8.dp), shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(it, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            supporting()
        }
        RadioButton(selected, onClick = null, enabled = enabled)
    }
}

@Composable
private fun PickerDetail(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

package cn.zgccrui.passwordswitch

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

enum class ConnectionState { STOPPED, PERMISSION, CONNECTING, READY }

data class ManagerState(
    val connection: ConnectionState = ConnectionState.STOPPED,
    val busy: Boolean = false,
    val providers: List<ProviderEntry> = emptyList(),
    val scanned: Boolean = false,
    val snapshot: SettingsSnapshot? = null,
    val backup: SettingsSnapshot? = null,
    val credential: String? = null,
    val autofill: String? = null,
    val keepOthers: Boolean = false,
    val message: String = "",
    val detail: String = "",
    val error: Boolean = false,
    val checkedAt: Long? = null,
    val unconfirmedWrite: Boolean = false,
) {
    val canApply: Boolean get() = connection == ConnectionState.READY && !busy && snapshot != null &&
        (credential != null || autofill != null)
}

class ManagerViewModel(application: Application) : AndroidViewModel(application) {
    // Android assigns a separate 100000-UID range per user. Never use shell's user (0).
    private val userId = Process.myUid() / 100000
    private val preferences = application.getSharedPreferences("previous_settings", 0)
    private val catalog = ProviderCatalog(application)
    private val mutableState = MutableStateFlow(ManagerState(backup = loadBackup()))
    val state = mutableState.asStateFlow()
    private var remote: ISettingsService? = null
    private var task: Job? = null
    private var bindingTimeout: Job? = null
    private var binding = false
    private var writing = false
    private val serviceArgs = Shizuku.UserServiceArgs(ComponentName(application, SettingsService::class.java))
        .daemon(false).processNameSuffix("settings").version(BuildConfig.VERSION_CODE).debuggable(BuildConfig.DEBUG)

    private val receivedListener = Shizuku.OnBinderReceivedListener { viewModelScope.launch { reconnect() } }
    private val deadListener = Shizuku.OnBinderDeadListener { viewModelScope.launch { disconnect() } }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { request, result ->
        if (request == 10) viewModelScope.launch {
            if (result == PackageManager.PERMISSION_GRANTED) reconnect()
            else notice("未获得授权，请在 Shizuku 的应用管理中允许「密码管理器切换」。", error = true)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            viewModelScope.launch {
                bindingTimeout?.cancel()
                binding = false
                if (binder == null || !binder.pingBinder()) {
                    disconnect()
                    return@launch
                }
                remote = ISettingsService.Stub.asInterface(binder)
                mutableState.update { it.copy(connection = ConnectionState.READY) }
                refresh()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { viewModelScope.launch { disconnect() } }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(receivedListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    fun reconnect() {
        try {
            if (!Shizuku.pingBinder()) {
                disconnect()
                scanOnly()
                return
            }
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) {
                disconnect()
                notice("请将 Shizuku 更新至 v13 或更新版本。", error = true)
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                remote = null
                mutableState.update { it.copy(connection = ConnectionState.PERMISSION, snapshot = null, checkedAt = null) }
                scanOnly()
                return
            }
            if (remote?.asBinder()?.pingBinder() == true) {
                refresh()
                return
            }
            if (binding) return
            binding = true
            mutableState.update { it.copy(connection = ConnectionState.CONNECTING) }
            Shizuku.bindUserService(serviceArgs, connection)
            bindingTimeout = viewModelScope.launch {
                delay(12_000)
                if (binding) {
                    runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
                    binding = false
                    mutableState.update { it.copy(connection = ConnectionState.STOPPED) }
                    notice("连接超时，请打开 Shizuku 确认运行状态，再点刷新。", error = true)
                }
            }
        } catch (failure: Exception) {
            disconnect()
            notice("连接失败，请检查 Shizuku 是否运行并已授权。", failure.toString(), true)
        }
    }

    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                disconnect()
                return
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                notice("授权曾被拒绝，请在 Shizuku 的应用管理中开启权限。", error = true)
            } else Shizuku.requestPermission(10)
        } catch (failure: Exception) {
            notice("无法请求授权，请打开 Shizuku 手动授权。", failure.toString(), true)
        }
    }

    private fun scanOnly() {
        if (task?.isActive == true) return
        task = viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { catalog.scan() }
                mutableState.update { it.copy(providers = entries, scanned = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                notice("无法读取已安装服务，请重试。", failure.toString(), true)
            } finally {
                // If authorization completed while scanning, finish the deferred read now.
                task = null
                if (remote != null && state.value.snapshot == null) refresh()
            }
        }
    }

    fun select(kind: ProviderKind, component: String?) {
        if (state.value.busy) return
        if (component != null && state.value.providers.none { it.kind == kind && it.component == component }) return
        mutableState.update {
            if (kind == ProviderKind.CREDENTIAL) it.copy(credential = component)
            else it.copy(autofill = component)
        }
    }

    fun keepOthers(value: Boolean) { if (!state.value.busy) mutableState.update { it.copy(keepOthers = value) } }
    fun label(component: String): String = catalog.label(component, state.value.providers)

    fun refresh() = execute(false) { service, _ -> service.read(userId) }

    fun apply() {
        if (!state.value.canApply) return
        val draft = state.value
        execute(true) action@{ service, before ->
            val entries = service.scanProviders(userId)
            require(draft.credential == null || entries.any { it.kind == ProviderKind.CREDENTIAL && it.component == draft.credential }) {
                "选中的凭据服务已不可用，请刷新后重新选择。"
            }
            require(draft.autofill == null || entries.any { it.kind == ProviderKind.AUTOFILL && it.component == draft.autofill }) {
                "选中的自动填充服务已不可用，请刷新后重新选择。"
            }
            val expected = requireNotNull(before)
            val current = service.read(userId)
            if (!current.getBoolean("ok")) return@action current
            if (!current.toSnapshot().equivalentTo(expected)) {
                return@action current.apply {
                    putBoolean("ok", false)
                    putString("message", "系统配置已变化，请检查当前配置后重新应用。未写入设置，原备份已保留。")
                }
            }
            val desired = expected.withSelection(draft.credential, draft.autofill, draft.keepOthers)
            if (!expected.equivalentTo(desired)) saveBackup(expected)
            service.apply(userId, draft.credential, draft.autofill, draft.keepOthers, expected.toBundle())
        }
    }

    fun restore() {
        val target = state.value.backup ?: return
        if (state.value.snapshot == null || state.value.busy) return
        // Keep this backup until restore is confirmed; retry is possible after interruption.
        execute(true, restoring = true) { service, before ->
            service.restore(userId, target.toBundle(), requireNotNull(before).toBundle())
        }
    }

    private fun execute(
        write: Boolean,
        restoring: Boolean = false,
        action: (ISettingsService, SettingsSnapshot?) -> Bundle,
    ) {
        val service = remote ?: return
        if (task?.isActive == true) return
        val before = state.value.snapshot
        writing = write
        mutableState.update { it.copy(busy = true, message = if (write) "正在写入并核对系统配置…" else it.message) }
        task = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { action(service, before) }
                if (remote !== service) return@launch
                val snapshot = if (result.containsKey("userId")) result.toSnapshot() else null
                val ok = result.getBoolean("ok")
                mutableState.update {
                    it.copy(
                        snapshot = snapshot, checkedAt = snapshot?.let { System.currentTimeMillis() },
                        backup = loadBackup(),
                        credential = if (write && ok) null else it.credential,
                        autofill = if (write && ok) null else it.autofill,
                        message = when {
                            !ok -> result.getString("message").orEmpty()
                            restoring -> "已恢复上次配置，并核对系统设置。"
                            write -> "系统设置已写入并核对。请在目标应用中验证通行密钥或自动填充。"
                            it.unconfirmedWrite -> "已重新读取当前配置。请核对中断操作的结果，需要时恢复上次配置。"
                            else -> it.message
                        },
                        error = if (write || !ok) !ok else it.error,
                        detail = if (write || !ok) result.getString("detail").orEmpty() else it.detail,
                        unconfirmedWrite = snapshot == null && (write || it.unconfirmedWrite),
                    )
                }
                val entries = withContext(Dispatchers.IO) { service.scanProviders(userId) }
                mutableState.update { it.copy(providers = entries, scanned = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (remote !== service) return@launch
                mutableState.update { it.copy(snapshot = null, checkedAt = null, backup = loadBackup(),
                    unconfirmedWrite = write || it.unconfirmedWrite) }
                notice(
                    if (write) "操作中断，结果尚未确认。请刷新检查；上次配置已保留。" else "读取失败，请刷新重试。",
                    failure.message ?: failure.toString(), true,
                )
            } finally {
                if (remote === service) {
                    writing = false
                    mutableState.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun notice(message: String, detail: String = "", error: Boolean = false) {
        mutableState.update { it.copy(message = message, detail = detail, error = error) }
    }

    private fun saveBackup(snapshot: SettingsSnapshot) {
        val editor = preferences.edit().clear().putBoolean("valid", true).putInt("userId", userId)
        SettingKey.entries.forEach { key ->
            editor.putBoolean("${key.wireName}_present", snapshot[key] != null)
            snapshot[key]?.let { editor.putString(key.wireName, it) }
        }
        if (!editor.commit()) throw SettingsFailure("无法保存恢复配置，已取消本次写入。")
    }

    private fun loadBackup(): SettingsSnapshot? {
        if (!preferences.getBoolean("valid", false) || preferences.getInt("userId", -1) != userId) return null
        return runCatching {
            SettingsSnapshot(userId, SettingKey.entries.associateWith {
                if (preferences.getBoolean("${it.wireName}_present", false)) preferences.getString(it.wireName, null) else null
            }).also { snapshot -> snapshot.values.values.forEach(::components) }
        }.getOrNull()
    }

    private fun disconnect() {
        val interrupted = writing
        writing = false
        remote = null
        binding = false
        bindingTimeout?.cancel()
        task?.cancel()
        task = null
        mutableState.update { it.copy(
            connection = ConnectionState.STOPPED, busy = false, snapshot = null, checkedAt = null,
            unconfirmedWrite = interrupted || it.unconfirmedWrite,
            backup = loadBackup(),
            message = if (interrupted) "写入时连接已中断，结果待确认。请重新连接并刷新，必要时恢复上次配置。" else it.message,
            error = interrupted || it.error,
        ) }
    }

    override fun onCleared() {
        Shizuku.removeBinderReceivedListener(receivedListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        // Do not kill an in-flight transaction when the UI is destroyed. daemon(false) handles lifetime.
        runCatching { Shizuku.unbindUserService(serviceArgs, connection, false) }
        super.onCleared()
    }
}

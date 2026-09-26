package cn.zgccrui.passwordswitch

import android.content.Context
import android.os.Binder
import android.os.Bundle
import androidx.annotation.Keep
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

fun SettingsSnapshot.toBundle() = Bundle().apply {
    putInt("userId", userId)
    SettingKey.entries.forEach { putString(it.wireName, values.getValue(it)) }
}

fun Bundle.toSnapshot(): SettingsSnapshot {
    require(containsKey("userId") && SettingKey.entries.all { containsKey(it.wireName) }) {
        "配置快照不完整，请刷新。"
    }
    return SettingsSnapshot(getInt("userId"), SettingKey.entries.associateWith { getString(it.wireName) })
}

/** Instantiated by Shizuku with shell/root identity, never an exported Android Service. */
@Keep
class SettingsService(context: Context) : ISettingsService.Stub() {
    // Shizuku v13 constructs this context for the owning app and its Android user.
    private val appUserId = context.applicationInfo.uid / 100000
    private val catalog = ProviderCatalog(context)
    private val reader = Executors.newSingleThreadExecutor()
    private val controller = SettingsController(ShellSettingsStore(CommandRunner { arguments ->
        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        val output = reader.submit<String> {
            process.inputStream.bufferedReader().use { stream ->
                val buffer = CharArray(4096)
                val text = StringBuilder()
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (text.length + count > 131072) throw SettingsFailure("系统返回内容过长。")
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
        }
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) throw SettingsFailure("系统命令超时，请刷新检查配置。")
            CommandResult(process.exitValue(), output.get(1, TimeUnit.SECONDS))
        } finally {
            process.destroyForcibly()
            output.cancel(true)
        }
    }))

    @Synchronized
    override fun read(userId: Int): Bundle = respond(userId) { controller.read(userId) }

    @Synchronized
    override fun apply(userId: Int, credential: String?, autofill: String?, keepOthers: Boolean, expected: Bundle): Bundle =
        respond(userId) {
            val snapshot = expected.toSnapshot()
            require(snapshot.userId == userId)
            controller.apply(snapshot, credential, autofill, keepOthers)
        }

    @Synchronized
    override fun restore(userId: Int, target: Bundle, expected: Bundle): Bundle = respond(userId) {
        val before = expected.toSnapshot()
        require(before.userId == userId)
        controller.replace(before, target.toSnapshot())
    }

    @Synchronized
    override fun listProviders(userId: Int): Bundle {
        val identity = Binder.clearCallingIdentity()
        return try {
            require(userId == appUserId) { "不能扫描其他 Android 用户的应用。" }
            // Package queries run as shell, so non-exported providers remain discoverable.
            val providers = catalog.scan()
            Bundle().apply {
                putBoolean("ok", true)
                putParcelableArrayList("providers", ArrayList(providers.map { it.toBundle() }))
            }
        } catch (failure: Exception) {
            Bundle().apply {
                putBoolean("ok", false)
                putString("message", "密码服务扫描失败，请确认 Shizuku 运行并已授权，然后刷新。")
                putString("detail", failure.toString().take(4000))
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun respond(userId: Int, action: () -> SettingsSnapshot): Bundle = try {
        action().toBundle().apply { putBoolean("ok", true) }
    } catch (failure: Exception) {
        val current = runCatching { controller.read(userId) }.getOrNull()
        (current?.toBundle() ?: Bundle()).apply {
            putBoolean("ok", false)
            putString("message", failure.message ?: "与系统通信失败。")
            putString("detail", ((failure as? SettingsFailure)?.detail ?: failure.toString()).take(6000))
        }
    }

    override fun destroy() {
        reader.shutdownNow()
        exitProcess(0)
    }
}

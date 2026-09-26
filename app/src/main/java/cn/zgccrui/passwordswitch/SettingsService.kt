package cn.zgccrui.passwordswitch

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
class SettingsService : ISettingsService.Stub() {
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

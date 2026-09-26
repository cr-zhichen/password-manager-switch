package cn.zgccrui.passwordswitch

import android.graphics.Bitmap
import android.os.Bundle

/** Metadata only: no credential contents are requested or transferred. */
fun ProviderEntry.toBundle() = Bundle().apply {
    putString("component", component)
    putString("appName", appName)
    putString("serviceName", serviceName)
    putString("packageName", packageName)
    putString("kind", kind.name)
    putParcelable("icon", icon?.asShared())
    supportsPasskeys?.let { putBoolean("supportsPasskeys", it) }
}

fun Bundle.toProvider(): ProviderEntry = ProviderEntry(
    component = canonicalComponent(requireNotNull(getString("component"))),
    appName = requireNotNull(getString("appName")),
    serviceName = requireNotNull(getString("serviceName")),
    packageName = requireNotNull(getString("packageName")),
    kind = ProviderKind.valueOf(requireNotNull(getString("kind"))),
    icon = getParcelable("icon", Bitmap::class.java),
    supportsPasskeys = if (containsKey("supportsPasskeys")) getBoolean("supportsPasskeys") else null,
)

fun ISettingsService.scanProviders(userId: Int): List<ProviderEntry> {
    val result = listProviders(userId)
    if (!result.getBoolean("ok")) throw SettingsFailure(
        result.getString("message") ?: "无法通过 Shizuku 扫描密码服务。",
        result.getString("detail").orEmpty(),
    )
    val entries = requireNotNull(result.getParcelableArrayList("providers", Bundle::class.java)) {
        "密码服务扫描结果不完整，请重新连接 Shizuku。"
    }
    return entries.map { it.toProvider() }
}

package cn.zgccrui.passwordswitch

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.service.autofill.AutofillService
import android.service.credentials.CredentialProviderService
import androidx.core.graphics.drawable.toBitmap
import org.xmlpull.v1.XmlPullParser

enum class ProviderKind { CREDENTIAL, AUTOFILL }

data class ProviderEntry(
    val component: String,
    val appName: String,
    val serviceName: String,
    val packageName: String,
    val kind: ProviderKind,
    val icon: Bitmap?,
    val supportsPasskeys: Boolean?,
)

class ProviderCatalog(private val context: Context) {
    private val pm = context.packageManager

    fun scan(): List<ProviderEntry> {
        val credential = query(CredentialProviderService.SERVICE_INTERFACE) +
            query("android.service.credentials.system.CredentialProviderService")
        return (credential.mapNotNull { entry(it, ProviderKind.CREDENTIAL) } +
            query(AutofillService.SERVICE_INTERFACE).mapNotNull { entry(it, ProviderKind.AUTOFILL) })
            .distinctBy { it.kind to it.component }
            .sortedWith(compareBy({ it.appName.lowercase() }, { it.component }))
    }

    private fun query(action: String): List<ServiceInfo> = pm.queryIntentServices(
        Intent(action), PackageManager.ResolveInfoFlags.of(
            (PackageManager.GET_META_DATA or PackageManager.MATCH_DIRECT_BOOT_AWARE or
                PackageManager.MATCH_DIRECT_BOOT_UNAWARE).toLong(),
        ),
    ).map { it.serviceInfo }

    private fun entry(service: ServiceInfo, kind: ProviderKind): ProviderEntry? {
        val permission = when (kind) {
            ProviderKind.CREDENTIAL -> Manifest.permission.BIND_CREDENTIAL_PROVIDER_SERVICE
            ProviderKind.AUTOFILL -> Manifest.permission.BIND_AUTOFILL_SERVICE
        }
        // The query already applies enabled-state overrides; service.enabled is only the manifest default.
        // System-bound password services can be non-exported (for example 1Password).
        // The required bind permission, rather than access from ordinary apps, identifies them.
        if (service.permission != permission) return null
        return ProviderEntry(
            ComponentName(service.packageName, service.name).flattenToString(),
            pm.getApplicationLabel(service.applicationInfo).toString(),
            service.loadLabel(pm).toString(),
            service.packageName,
            kind,
            runCatching { service.loadIcon(pm).toBitmap(96, 96) }.getOrNull(),
            if (kind == ProviderKind.CREDENTIAL) passkeyCapability(service) else null,
        )
    }

    private fun passkeyCapability(service: ServiceInfo): Boolean? = runCatching {
        val parser = service.loadXmlMetaData(pm, "android.credentials.provider") ?: return@runCatching null
        parser.use {
            var supports = false
            while (it.eventType != XmlPullParser.END_DOCUMENT) {
                if (it.eventType == XmlPullParser.START_TAG && it.name == "capability") {
                    val name = it.getAttributeValue(null, "name")
                        ?: it.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                    if (name == "androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL" ||
                        name == "android.credentials.TYPE_PUBLIC_KEY_CREDENTIAL") supports = true
                }
                it.next()
            }
            supports
        }
    }.getOrNull()

    fun label(component: String, entries: List<ProviderEntry>): String {
        val canonical = runCatching { canonicalComponent(component) }.getOrDefault(component)
        entries.firstOrNull { it.component == canonical }?.let { return it.appName }
        val parsed = ComponentName.unflattenFromString(component) ?: return component
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(parsed.packageName, 0)).toString() }
            .getOrElse { "${parsed.packageName}（未发现可用服务）" }
    }
}

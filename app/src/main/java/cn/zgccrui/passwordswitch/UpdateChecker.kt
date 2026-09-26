package cn.zgccrui.passwordswitch

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL

internal data class ReleaseVersion(
    val numbers: List<BigInteger>,
    val prerelease: List<String>,
) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        numbers.zip(other.numbers).forEach { (left, right) ->
            val comparison = left.compareTo(right)
            if (comparison != 0) return comparison
        }
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1
        prerelease.zip(other.prerelease).forEach { (left, right) ->
            val leftNumber = left.toBigIntegerOrNull()
            val rightNumber = right.toBigIntegerOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern = Regex(
            """v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*))?"""
        )

        fun parse(value: String): ReleaseVersion? {
            val match = pattern.matchEntire(value) ?: return null
            return ReleaseVersion(
                (1..3).map { match.groupValues[it].toBigInteger() },
                match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList(),
            )
        }
    }
}

data class AppUpdate(val version: String, val downloadUrl: String)

internal data class ReleaseAsset(val name: String, val url: String, val size: Long)
internal data class ReleaseCandidate(
    val tag: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

internal object UpdatePolicy {
    const val REPOSITORY = "cr-zhichen/password-manager-switch"

    fun select(currentVersion: String, releases: List<ReleaseCandidate>): AppUpdate? {
        val current = requireNotNull(ReleaseVersion.parse(currentVersion)) { "无法识别当前版本号" }
        return releases.mapNotNull { release ->
            val version = ReleaseVersion.parse(release.tag) ?: return@mapNotNull null
            if (release.draft || version <= current) return@mapNotNull null
            if (current.prerelease.isEmpty() && (release.prerelease || version.prerelease.isNotEmpty())) {
                return@mapNotNull null
            }
            val name = "password-manager-switch-${release.tag}.apk"
            val expectedUrl = "https://github.com/$REPOSITORY/releases/download/${release.tag}/$name"
            val asset = release.assets.firstOrNull { it.name == name && it.url == expectedUrl && it.size > 0 }
                ?: return@mapNotNull null
            version to AppUpdate(release.tag.removePrefix("v"), asset.url)
        }.maxByOrNull { it.first }?.second
    }
}

internal class UpdateCheckFailure(message: String) : IOException(message)

internal class UpdateChecker {
    fun check(currentVersion: String): AppUpdate? {
        val version = requireNotNull(ReleaseVersion.parse(currentVersion)) { "无法识别当前版本号" }
        val stable = version.prerelease.isEmpty()
        val path = if (stable) "releases/latest" else "releases?per_page=100"
        val connection = URL("https://api.github.com/repos/${UpdatePolicy.REPOSITORY}/$path")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "PasswordManagerSwitch/$currentVersion")
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> return null
                HttpURLConnection.HTTP_OK -> Unit
                403, 429 -> throw UpdateCheckFailure("检查次数较多，请稍后重试。")
                else -> throw UpdateCheckFailure("暂时无法检查更新（HTTP $code），请稍后重试。")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val releases = if (stable) JSONArray().put(JSONObject(body)) else JSONArray(body)
            val candidates = (0 until releases.length()).mapNotNull releaseLoop@ { index ->
                val release = releases.optJSONObject(index) ?: return@releaseLoop null
                val assets = release.optJSONArray("assets") ?: JSONArray()
                ReleaseCandidate(
                    tag = release.optString("tag_name"),
                    draft = release.optBoolean("draft"),
                    prerelease = release.optBoolean("prerelease"),
                    assets = (0 until assets.length()).mapNotNull assetLoop@ { assetIndex ->
                        val asset = assets.optJSONObject(assetIndex) ?: return@assetLoop null
                        if (asset.optString("state") != "uploaded") return@assetLoop null
                        ReleaseAsset(asset.optString("name"), asset.optString("browser_download_url"), asset.optLong("size"))
                    },
                )
            }
            return UpdatePolicy.select(currentVersion, candidates)
        } finally {
            connection.disconnect()
        }
    }
}

package cn.zgccrui.passwordswitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Release selection and version ordering only; no UI unit tests. */
class UpdatePolicyTest {
    private fun release(tag: String, prerelease: Boolean = '-' in tag): ReleaseCandidate {
        val name = "password-manager-switch-$tag.apk"
        return ReleaseCandidate(tag, false, prerelease, listOf(ReleaseAsset(
            name, "https://github.com/cr-zhichen/password-manager-switch/releases/download/$tag/$name", 1024,
        )))
    }

    @Test fun numericVersionsDoNotUseStringOrdering() {
        val result = UpdatePolicy.select("1.0.9", listOf(release("v1.0.10"), release("v1.0.8")))
        assertEquals("1.0.10", result?.version)
    }

    @Test fun stableInstallNeverMovesToAPrereleaseEvenIfFlagIsWrong() {
        val result = UpdatePolicy.select("1.0.2", listOf(
            release("v2.0.0-beat.1", prerelease = false), release("v1.0.4", prerelease = true), release("v1.0.3"),
        ))
        assertEquals("1.0.3", result?.version)
    }

    @Test fun beatNumbersSortNumericallyAndStableSupersedesTheSameVersion() {
        assertEquals("1.0.3-beat.10", UpdatePolicy.select("1.0.3-beat.2", listOf(
            release("v1.0.3-beat.3"), release("v1.0.3-beat.10"),
        ))?.version)
        assertEquals("1.0.3", UpdatePolicy.select("1.0.3-beat.10", listOf(
            release("v1.0.3-beat.11"), release("v1.0.3"),
        ))?.version)
    }

    @Test fun betaAndRcOrderingAllowsPromotionWithoutDowngrades() {
        assertEquals("1.0.3-rc.1", UpdatePolicy.select("1.0.3-beta.9", listOf(
            release("v1.0.2"), release("v1.0.3-beta.10"), release("v1.0.3-rc.1"),
        ))?.version)
        assertNull(UpdatePolicy.select("1.0.3", listOf(release("v1.0.2"), release("v1.0.3"))))
    }

    @Test fun draftMissingOrEmptyApkCannotBecomeAnUpdate() {
        val empty = release("v1.0.6")
        assertNull(UpdatePolicy.select("1.0.2", listOf(
            release("v1.0.4").copy(draft = true),
            release("v1.0.5").copy(assets = emptyList()),
            empty.copy(assets = listOf(empty.assets.single().copy(size = 0))),
            release("invalid"),
        )))
    }

    @Test fun downloadMustMatchTheRepositoryTagAndApkName() {
        val candidate = release("v1.0.3")
        val asset = candidate.assets.single()
        for (badAsset in listOf(
            asset.copy(url = "https://example.com/update.apk"),
            asset.copy(url = asset.url.replace("https://", "http://")),
            asset.copy(url = asset.url.replace("v1.0.3/", "v1.0.2/")),
            asset.copy(name = "source.zip"),
        )) {
            assertNull(UpdatePolicy.select("1.0.2", listOf(candidate.copy(assets = listOf(badAsset)))))
        }
    }
}

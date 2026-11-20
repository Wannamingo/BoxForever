package com.rfandango.haku_x.data

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
  @Test fun forkFeedAndAssets() {
    assertEquals(listOf(
      "https://api.github.com/repos/Wannamingo/HakuX-Mali/releases?per_page=30",
      "https://api.github.com/repos/Wannamingo/BoxForever/releases?per_page=30",
    ), UpdatePolicy.releasesUrls)
    val tag = "boxforever-v1.0"
    val base = "https://github.com/Wannamingo/HakuX-Mali/releases/download"
    assertFalse(UpdatePolicy.isApkAsset("hakuX.apk", "https://github.com/rfandango/hakuX/releases/download/$tag/hakuX.apk", tag))
    assertFalse(UpdatePolicy.isApkAsset("source.zip", "$base/$tag/source.zip", tag))
    assertFalse(UpdatePolicy.isApkAsset("hakuX.apk", "$base/boxforever-v1.2/hakuX.apk", tag))
    assertFalse(UpdatePolicy.isApkAsset("hakuX.apk", "$base/$tag/other.apk", tag))
  }

  @Test fun transitionAcceptsExactAssetUrlsForBothNames() {
    val tag = "boxforever-v1.0"
    val name = "BoxForever-1.0-release.apk"
    val path = "/releases/download/$tag/$name"
    assertTrue(UpdatePolicy.isApkAsset(name, "https://github.com/Wannamingo/HakuX-Mali$path", tag))
    val renamed = "https://github.com/Wannamingo/BoxForever$path"
    assertTrue(UpdatePolicy.isApkAsset(name, renamed, tag))
    assertFalse(UpdatePolicy.isApkAsset(name, "${renamed}?download=1", tag))
    assertFalse(UpdatePolicy.isApkAsset(name, renamed, "v1.9"))
    assertFalse(UpdatePolicy.isApkAsset(name, renamed.replace("Wannamingo", "other"), tag))
  }

  @Test fun boxForeverNumberingIsSeparateFromLegacyReleases() {
    assertEquals(UpdatePolicy.releaseVersion("1.0"), UpdatePolicy.boxForeverReleaseVersion("boxforever-v1.0"))
    assertEquals("1.1-rc.2", UpdatePolicy.boxForeverReleaseVersion("boxforever-v1.1-rc.2"))
    assertNull(UpdatePolicy.boxForeverReleaseVersion("v1.9"))
    assertNull(UpdatePolicy.boxForeverReleaseVersion("v1.0"))
    assertNull(UpdatePolicy.boxForeverReleaseVersion("boxforever-vlatest"))
    val next = requireNotNull(UpdatePolicy.boxForeverReleaseVersion("boxforever-v1.1"))
    assertTrue(UpdatePolicy.compareVersions(next, "1.0") > 0)
  }

  @Test fun stableAndPreviewOrdering() {
    assertTrue(UpdatePolicy.compareVersions("v1.8", "v1.8.0-rc.9") > 0)
    assertTrue(UpdatePolicy.compareVersions("v1.8.0-rc.1", "v1.8.0-beta.10") > 0)
    assertTrue(UpdatePolicy.compareVersions("v1.8.0-preview.10", "v1.8.0-preview.2") > 0)
    assertTrue(UpdatePolicy.compareVersions("v1.8.0-preview.1", "v1.7.5") > 0)
    assertNull(UpdatePolicy.releaseVersion("themes-v2"))
    assertEquals("1.2.0-rg477m.2", UpdatePolicy.releaseVersion("v1.2.0-rg477m.2"))
  }

  @Test fun requiresValidSha256Metadata() {
    assertEquals("ab".repeat(32), UpdatePolicy.assetDigest("sha256:" + "AB".repeat(32)))
    assertNull(UpdatePolicy.assetDigest("sha256:" + "ab".repeat(31)))
    assertNull(UpdatePolicy.assetDigest("sha512:" + "ab".repeat(32)))
    assertNull(UpdatePolicy.assetDigest("sha256:" + "zz".repeat(32)))
  }

  @Test fun forkRevisionOrdering() {
    assertTrue(UpdatePolicy.compareVersions("1.2.0-rg477m.2", "1.2.0-rg477m.1") > 0)
    assertTrue(UpdatePolicy.compareVersions("v1.2.0-rg477m.10", "1.2.0-rg477m.2") > 0)
    assertTrue(UpdatePolicy.compareVersions("1.3.0-rg477m.1", "1.2.0-rg477m.10") > 0)
    assertTrue(UpdatePolicy.compareVersions("1.2.0-rg477m.1", "1.2.0-rg477m.2") < 0)
    assertEquals(0, UpdatePolicy.compareVersions("1.2.0-rg477m.2", "v1.2.0-rg477m.2+build.17"))
  }

  @Test fun numericVersions() {
    assertEquals(0, UpdatePolicy.compareVersions("v1.0", "1.0"))
    assertTrue(UpdatePolicy.compareVersions("1.0.1", "1.0") > 0)
    assertTrue(UpdatePolicy.compareVersions("1.1", "1.0.9") > 0)
    assertTrue(UpdatePolicy.compareVersions("1.10.0-rg477m.1", "1.9.0-rg477m.1") > 0)
    assertEquals(0, UpdatePolicy.compareVersions("1.2", "1.2.0"))
  }

  @Test fun packageAndCertificateMustBothMatch() {
    val pkg = "com.rfandango.haku_x.debug"
    assertTrue(UpdatePolicy.acceptsApk(pkg, pkg, setOf("our certificate"), setOf("our certificate")))
    assertFalse(UpdatePolicy.acceptsApk(pkg, "com.rfandango.haku_x", setOf("our certificate"), setOf("our certificate")))
    assertFalse(UpdatePolicy.acceptsApk(pkg, pkg, setOf("our certificate"), setOf("another certificate")))
    assertFalse(UpdatePolicy.acceptsApk(pkg, pkg, emptySet(), emptySet()))
  }
}

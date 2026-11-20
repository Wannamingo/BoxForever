package com.rfandango.haku_x.data

/** This fork's release feed, revision ordering and in-place APK identity. */
object UpdatePolicy {
  private val repositories = listOf("Wannamingo/HakuX-Mali", "Wannamingo/BoxForever")
  val releasesUrls: List<String> = repositories.map { "https://api.github.com/repos/$it/releases?per_page=30" }
  const val MAX_APK_BYTES = 128L * 1024 * 1024
  private val versionPattern = Regex("""\d+(?:\.\d+){1,2}(?:-rg477m\.\d+)?(?:-(?:preview|beta|rc)\.\d+)?""")
  private val previewPattern = Regex("""-(?:preview|beta|rc)\.\d+$""")
  private val apkNamePattern = Regex("""[A-Za-z0-9][A-Za-z0-9_.-]*\.apk""", RegexOption.IGNORE_CASE)
  private val digestPattern = Regex("sha256:([0-9a-fA-F]{64})")

  fun releaseVersion(tag: String): String? =
    tag.removePrefix("v").removePrefix("V").takeIf(versionPattern::matches)

  fun boxForeverReleaseVersion(tag: String): String? =
    tag.takeIf { it.startsWith("boxforever-v") }?.removePrefix("boxforever-v")?.takeIf(versionPattern::matches)

  fun isPreview(version: String): Boolean = previewPattern.containsMatchIn(version)

  fun assetDigest(value: String): String? =
    digestPattern.matchEntire(value)?.groupValues?.get(1)?.lowercase()

  fun isApkAsset(name: String, url: String, tag: String): Boolean =
    boxForeverReleaseVersion(tag) != null && apkNamePattern.matches(name) &&
      repositories.any { url == "https://github.com/$it/releases/download/$tag/$name" }

  fun compareVersions(a: String, b: String): Int {
    fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").substringBefore('+')
      .split('.', '-').map { p -> p.takeWhile(Char::isDigit).toLongOrNull() ?: 0L }
    fun compareParts(left: List<Long>, right: List<Long>): Int {
      for (i in 0 until maxOf(left.size, right.size)) {
        val order = left.getOrElse(i) { 0L }.compareTo(right.getOrElse(i) { 0L })
        if (order != 0) return order
      }
      return 0
    }
    val left = a.trim().substringBefore('+')
    val right = b.trim().substringBefore('+')
    val baseOrder = compareParts(parts(left.replace(previewPattern, "")), parts(right.replace(previewPattern, "")))
    if (baseOrder != 0) return baseOrder
    val channelOrder = isPreview(right).compareTo(isPreview(left))
    if (channelOrder != 0) return channelOrder
    val channels = listOf("preview", "beta", "rc")
    val leftChannel = channels.indexOfFirst { "-$it." in left }
    val rightChannel = channels.indexOfFirst { "-$it." in right }
    return if (leftChannel != rightChannel) leftChannel.compareTo(rightChannel)
      else compareParts(parts(left), parts(right))
  }

  fun acceptsApk(installedPackage: String, candidatePackage: String, ours: Set<String>, theirs: Set<String>): Boolean =
    installedPackage == candidatePackage && ours.isNotEmpty() && ours == theirs
}

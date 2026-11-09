package com.rfandango.haku_x.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import com.rfandango.haku_x.BuildConfig
import com.rfandango.haku_x.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

object Updates {
  private const val TAG = "hakuX-update"
  private const val TIMEOUT_MS = 15_000
  private const val MAX_RELEASE_BYTES = 2 * 1024 * 1024
  private const val PREVIEW_KEY = "updates_include_preview"
  private val installLock = Mutex()
  private val checkLock = Mutex()
  private val startChecked = AtomicBoolean(false)

  class Release(
    val version: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val apkSha256: String,
    val preview: Boolean,
  )

  private val _available = MutableStateFlow<Release?>(null)
  val available: StateFlow<Release?> = _available

  fun includePreview(context: Context): Boolean =
    context.getSharedPreferences(AppPrefs.NAME, Context.MODE_PRIVATE).getBoolean(PREVIEW_KEY, false)

  fun setIncludePreview(context: Context, enabled: Boolean) {
    context.getSharedPreferences(AppPrefs.NAME, Context.MODE_PRIVATE).edit().putBoolean(PREVIEW_KEY, enabled).apply()
    _available.value = null
  }

  /** Debug builds can request a check as an older version without weakening installation checks. */
  fun installedVersion(context: Context): String {
    if (BuildConfig.DEBUG) {
      runCatching { File(context.filesDir, "debug_update_version").readText().trim() }.getOrNull()
        ?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return BuildConfig.VERSION_NAME
  }

  suspend fun check(context: Context): Release? = checkLock.withLock {
    val preview = includePreview(context)
    val release = withContext(Dispatchers.IO) { fetch(installedVersion(context), preview) }
    release.takeIf { includePreview(context) == preview }.also { _available.value = it }
  }

  private fun fetch(installed: String, preview: Boolean): Release? {
    val conn = open(UpdatePolicy.RELEASES_URL)
    conn.setRequestProperty("Accept", "application/vnd.github+json")
    try {
      val response = conn.responseCode
      if (response == HttpURLConnection.HTTP_NOT_FOUND) return null
      if (response != HttpURLConnection.HTTP_OK) throw IOException("GitHub answered $response")
      val body = ByteArrayOutputStream()
      conn.inputStream.use { input ->
        val buffer = ByteArray(8192)
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          if (body.size() + count > MAX_RELEASE_BYTES) throw IOException("The release list exceeds its size limit")
          body.write(buffer, 0, count)
        }
      }
      val releases = JSONArray(body.toString("UTF-8"))
      var best: Release? = null
      for (i in 0 until releases.length()) {
        val json = releases.optJSONObject(i) ?: continue
        val tag = json.optString("tag_name")
        val version = UpdatePolicy.releaseVersion(tag) ?: continue
        val isPreview = json.optBoolean("prerelease") || UpdatePolicy.isPreview(version)
        if (json.optBoolean("draft") || (!preview && isPreview)) continue
        if (compareVersions(version, installed) <= 0) continue
        val previous = best
        if (previous != null) {
          val order = compareVersions(version, previous.version)
          if (order < 0 || (order == 0 && (!previous.preview || isPreview))) continue
        }
        val assets = json.optJSONArray("assets") ?: continue
        val apks = (0 until assets.length()).mapNotNull(assets::optJSONObject).filter {
          UpdatePolicy.isApkAsset(it.optString("name"), it.optString("browser_download_url"), tag)
        }
        val apk = apks.singleOrNull() ?: continue
        val size = apk.optLong("size")
        val digest = UpdatePolicy.assetDigest(apk.optString("digest")) ?: continue
        if (apk.optString("state") != "uploaded" || size !in 1..UpdatePolicy.MAX_APK_BYTES) continue
        best = Release(
          version, json.optString("name").ifBlank { "HakuX-Mali $version" }, json.optString("body"),
          apk.getString("browser_download_url"), size, digest, isPreview,
        )
      }
      return best
    } finally {
      conn.disconnect()
    }
  }

  suspend fun checkAtStart(context: Context): Release? {
    if (!startChecked.compareAndSet(false, true)) return null
    return runCatching { check(context) }
      .onFailure { Log.w(TAG, "check at start: " + it.message) }
      .getOrNull()
  }

  fun compareVersions(a: String, b: String): Int = UpdatePolicy.compareVersions(a, b)

  suspend fun downloadAndInstall(context: Context, release: Release, progress: (Long, Long) -> Unit) =
    installLock.withLock {
      withContext(Dispatchers.IO) {
        if (release.preview && !includePreview(context)) throw IOException("Preview updates are disabled")
        val dir = File(context.cacheDir, "updates")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not open the update cache")
        val apk = File.createTempFile("hakux-update-", ".apk", dir)
        try {
          download(release, apk, progress)
          install(context, release, apk)
        } finally {
          apk.delete()
        }
      }
    }

  private suspend fun download(release: Release, apk: File, progress: (Long, Long) -> Unit) {
    val conn = open(release.apkUrl)
    try {
      val response = conn.responseCode
      if (response != HttpURLConnection.HTTP_OK) throw IOException("The download answered $response")
      val length = conn.contentLengthLong
      if (length >= 0 && length != release.apkSize) throw IOException("The APK size differs from the selected release")
      conn.inputStream.use { input ->
        apk.outputStream().use { output -> copyVerified(input, output, release, progress) }
      }
    } finally {
      conn.disconnect()
    }
  }

  private suspend fun copyVerified(
    input: InputStream,
    output: OutputStream,
    release: Release,
    progress: (Long, Long) -> Unit = { _, _ -> },
  ) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 16)
    var done = 0L
    while (true) {
      currentCoroutineContext().ensureActive()
      val count = input.read(buffer)
      if (count < 0) break
      done += count
      if (done > release.apkSize) throw IOException("The APK exceeds the selected release size")
      digest.update(buffer, 0, count)
      output.write(buffer, 0, count)
      progress(done, release.apkSize)
    }
    if (done != release.apkSize) throw IOException("The APK download stopped at $done bytes")
    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    if (hash != release.apkSha256) throw IOException("The APK SHA-256 differs from the selected release")
  }

  private fun verify(context: Context, release: Release, apk: File) {
    val pm = context.packageManager
    val flags = PackageManager.GET_SIGNING_CERTIFICATES
    val info = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("The download isn't an app")
    val installed = pm.getPackageInfo(context.packageName, flags)
    val ours = installed.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()
    val theirs = info.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()
    if (!UpdatePolicy.acceptsApk(context.packageName, info.packageName, ours, theirs)) {
      throw IOException("The download must have this app's package ID and signing certificate")
    }
    if (PackageInfoCompat.getLongVersionCode(info) <= PackageInfoCompat.getLongVersionCode(installed) ||
      info.versionName?.let(UpdatePolicy::releaseVersion) != release.version) {
      throw IOException("The APK must match the release version and have a newer Android version code")
    }
  }

  private suspend fun install(context: Context, release: Release, apk: File) {
    verify(context, release, apk)
    val installer = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    params.setAppPackageName(context.packageName)
    params.setSize(release.apkSize)
    val id = installer.createSession(params)
    var committed = false
    try {
      installer.openSession(id).use { session ->
        session.openWrite("hakuX.apk", 0, release.apkSize).use { out ->
          apk.inputStream().use { copyVerified(it, out, release) }
          session.fsync(out)
        }
        currentCoroutineContext().ensureActive()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
          (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val status = PendingIntent.getBroadcast(
          context, id, Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName), flags,
        )
        session.commit(status.intentSender)
        committed = true
      }
    } finally {
      if (!committed) runCatching { installer.abandonSession(id) }
    }
  }

  private fun open(url: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = TIMEOUT_MS
      readTimeout = TIMEOUT_MS
      instanceFollowRedirects = true
      setRequestProperty("User-Agent", "HakuX-Mali/" + BuildConfig.VERSION_NAME + " (Android)")
    }
}


/**
 * The system installer's answers about an update (Updates.install): its confirmation to show (the
 * user's), or why it failed. On success this app is replaced, its process gone.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
    when (status) {
      PackageInstaller.STATUS_PENDING_USER_ACTION -> {
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
          context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
      }
      PackageInstaller.STATUS_SUCCESS -> Log.i("hakuX-update", "installed")
      PackageInstaller.STATUS_FAILURE_ABORTED -> Log.i("hakuX-update", "install cancelled")
      else -> {
        val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
        Log.w("hakuX-update", "install failed: $why")
        Toast.makeText(context, context.getString(R.string.update_install_failed, why), Toast.LENGTH_LONG).show()
      }
    }
  }
}

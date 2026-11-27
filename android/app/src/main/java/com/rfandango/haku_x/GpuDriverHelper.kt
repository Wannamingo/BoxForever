package com.rfandango.haku_x

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import com.rfandango.haku_x.data.AppPrefs
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Custom Vulkan drivers (adrenotools packages: a ZIP with meta.json and the driver library, such as
 * Turnip's releases). Packages are kept in driverStorageDir; the one in use is unpacked into
 * driverInstallDir, which the native core loads at game start (NativeCoreActivity's vkdriver), with
 * the name of its package beside it (PACKAGE_MARKER). An empty driverInstallDir: the system driver.
 */
object GpuDriverHelper {
  private const val TAG = "GpuDriverHelper"
  private const val META_JSON = "meta.json"
  /** In driverInstallDir: the file name of the package the driver came from. */
  private const val PACKAGE_MARKER = ".hakux_package"
  private const val MALISX2_MARKER = "bundled:malisx2:0.6.1:9fbc7da290"
  private const val UNIFIED_MARKER = "bundled:unified:0.5.1:c4d156249788"
  private const val BOOTSTRAPPED = "bundled_malisx2_bootstrapped"

  private data class BundledDriver(val archive: String, val localName: String, val sha256: String, val marker: String) {
    val file: File get() = File(appContext.filesDir, "bundled_drivers/$localName")
  }

  private val maliSX2 = BundledDriver(
    "malisx2-0.6.1-9fbc7da290.zip",
    "hakux-bundled-malisx2-0.6.1.zip",
    "1fbdb5dee1217d0f97190a60844f83c4504567c8c6bc6399b5a8843840e99206",
    MALISX2_MARKER,
  )
  private val unified = BundledDriver(
    "Unified-0.5.1-G52-Border-Lab.zip",
    "hakux-bundled-unified-0.5.1-api31.zip",
    "6bea07de12cf0ee325987525461d692a5cb738d322a69c7fcc906e4557a3ae35",
    UNIFIED_MARKER,
  )
  private val bundles = listOf(maliSX2, unified)
  private var bundledPrepared = false

  private lateinit var appContext: Context

  val driverInstallDir: String get() = appContext.filesDir.absolutePath + "/gpu_driver/"
  val driverStorageDir: String get() = appContext.getExternalFilesDir(null)!!.absolutePath + "/gpu_drivers/"
  val hookLibDir: String get() = appContext.applicationInfo.nativeLibraryDir + "/"

  @Synchronized
  fun init(context: Context) {
    appContext = context.applicationContext
    File(driverInstallDir).mkdirs()
    File(driverStorageDir).mkdirs()
    if (!bundledPrepared) bundledPrepared = prepareBundledDriver()
  }

  private val choices get() = appContext.getSharedPreferences("gpu_driver_choices", Context.MODE_PRIVATE)

  private fun prepareBundledDriver(): Boolean {
    return try {
      for (bundle in bundles) {
        BundledDriverArchive.ensure(bundle.file, bundle.sha256) {
          appContext.assets.open("gpu_drivers/${bundle.archive}")
        }
      }

      val installedMarker = File(driverInstallDir, PACKAGE_MARKER)
      if (installedMarker.isFile &&
          GpuDriverPolicy.isRetiredBundle(installedMarker.readText(), bundles.map { it.marker })) {
        useSystemDriver()
        Log.i(TAG, "Switched to System driver after removal of a bundled driver")
      }

      val done = choices.getBoolean(BOOTSTRAPPED, false)
      val active = getActiveDriver()
      val installedLibrary = File(driverInstallDir, GpuDriverPolicy.MALISX2_LIBRARY)
      val identical = !done && active?.libraryName == GpuDriverPolicy.MALISX2_LIBRARY &&
        installedLibrary.isFile && ZipFile(maliSX2.file).use { zip ->
          zip.getInputStream(zip.getEntry(GpuDriverPolicy.MALISX2_LIBRARY)).use {
            BundledDriverArchive.sha256(it) == BundledDriverArchive.sha256(installedLibrary)
          }
        }
      if (GpuDriverPolicy.selectBundled(done, AppPrefs(appContext).setupComplete, active != null, identical,
          allowAutoSelection = GpuDriverPolicy.allowsAutomaticBundle(rg477mMali, rgVitaProMali)) &&
          readMetadata(maliSX2.file)?.let(::canLoadDriver) == true) {
        if (!useDriver(maliSX2.file)) throw IOException("Could not select bundled MaliSX2")
        Log.i(TAG, "Selected bundled MaliSX2 0.6.1 for the initial setup")
      }
      choices.edit().putBoolean(BOOTSTRAPPED, true).commit()

      val activeBundles = bundles.map { it.file }
      activeBundles.first().parentFile?.listFiles()?.forEach { file ->
        if (file.isFile && file.name.startsWith("hakux-bundled-") && file.extension == "zip" &&
            file !in activeBundles) file.delete()
      }
      Log.i(TAG, "Bundled drivers ready: ${bundles.joinToString { it.archive }}")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Could not prepare bundled GPU drivers", e)
      false
    }
  }

  private fun bundleFor(file: File): BundledDriver? = bundles.firstOrNull { it.file.absolutePath == file.absolutePath }

  fun isBundledDriver(file: File): Boolean = bundleFor(file) != null
  private fun packageId(file: File): String = bundleFor(file)?.marker ?: file.name
  fun isBundledInUse(): Boolean = bundles.any { isInUse(it.file) }

  private fun isAdreno(): Boolean = File("/dev/kgsl-3d0").exists()

  private val rg477mMali: Boolean by lazy {
    Build.VERSION.SDK_INT >= 29 && Build.SUPPORTED_ABIS.contains("arm64-v8a") &&
      File("/dev/mali0").exists() &&
      (GpuDriverPolicy.isRg477m(Build.MODEL, Build.DEVICE, Build.PRODUCT) ||
        // GammaOS/GSI replaces the system model with "GammaOS Full". The
        // read-only vendor model still identifies the actual handheld.
        GpuDriverPolicy.isRg477m(vendorModel()))
  }

  private fun vendorModel(): String = runCatching {
    val process = ProcessBuilder("/system/bin/getprop", "ro.product.vendor.model").start()
    try {
      if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) ""
      else process.inputStream.bufferedReader().use { it.readLine().orEmpty() }
    } finally {
      process.destroy()
    }
  }.getOrDefault("")

  private val dimensity8300Mali: Boolean by lazy {
    Build.VERSION.SDK_INT >= 29 && Build.SUPPORTED_ABIS.contains("arm64-v8a") && File("/dev/mali0").exists() &&
      GpuDriverPolicy.isDimensity8300(
        if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "", Build.HARDWARE,
        property("ro.soc.model"), property("ro.vendor.soc.model"), property("ro.board.platform"),
        property("ro.vendor.mediatek.platform"), property("ro.mediatek.platform"),
      )
  }

  private val unisocT820Mali: Boolean by lazy {
    Build.VERSION.SDK_INT >= 29 && Build.SUPPORTED_ABIS.contains("arm64-v8a") && File("/dev/mali0").exists() &&
      (GpuDriverPolicy.isUnisocT820(
        if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "", Build.HARDWARE,
        property("ro.soc.model"), property("ro.vendor.soc.model"),
      ) || rg476hMali || rg556Mali)
  }

  private val unisocT618Mali: Boolean by lazy {
    Build.VERSION.SDK_INT >= 29 && Build.SUPPORTED_ABIS.contains("arm64-v8a") && File("/dev/mali0").exists() &&
      (GpuDriverPolicy.isUnisocT618(
        if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "", Build.HARDWARE,
        property("ro.soc.model"), property("ro.vendor.soc.model"), property("ro.hardware.chipname"),
      ) || GpuDriverPolicy.isT618Handheld(property("ro.board.platform"),
        Build.MODEL, Build.DEVICE, Build.PRODUCT, vendorModel()))
  }

  // Known T820 models cover firmware/GSI builds that omit the SoC marketing name.
  private val rg556Mali: Boolean by lazy {
    Build.VERSION.SDK_INT >= 29 && Build.SUPPORTED_ABIS.contains("arm64-v8a") && File("/dev/mali0").exists() &&
      GpuDriverPolicy.isRg556(Build.MODEL, Build.DEVICE, Build.PRODUCT, vendorModel())
  }

  private fun property(name: String): String = runCatching {
    val process = ProcessBuilder("/system/bin/getprop", name).start()
    try {
      if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) ""
      else process.inputStream.bufferedReader().use { it.readLine().orEmpty() }
    } finally { process.destroy() }
  }.getOrDefault("")

  private val rgVitaProMali: Boolean by lazy {
    Build.VERSION.SDK_INT >= 29 && Build.SUPPORTED_ABIS.contains("arm64-v8a") && File("/dev/mali0").exists() &&
      GpuDriverPolicy.isRgVitaPro(Build.MODEL, Build.DEVICE, Build.PRODUCT, vendorModel()) &&
      (property("ro.board.platform").equals("rk3576", ignoreCase = true) ||
        (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.equals("rk3576", ignoreCase = true)))
  }

  private val rg476hMali: Boolean by lazy {
    Build.VERSION.SDK_INT >= 29 && Build.SUPPORTED_ABIS.contains("arm64-v8a") && File("/dev/mali0").exists() &&
      GpuDriverPolicy.isRg476hT820(property("ro.board.platform"),
        Build.MODEL, Build.DEVICE, Build.PRODUCT, vendorModel())
  }

  fun detectedChip(): GpuDriverPolicy.Chip? =
    GpuDriverPolicy.chip(unisocT820Mali, unisocT618Mali, dimensity8300Mali || rg477mMali)

  /** A lab run may load a different binary from the one selected in the UI. */
  fun hasRuntimeDriverOverride(): Boolean = property("debug.hakux.vkdriver").isNotEmpty()

  fun canLoadDriver(meta: DriverMetadata): Boolean =
    GpuDriverPolicy.canLoadPackage(Build.VERSION.SDK_INT, meta.minApi) &&
      GpuDriverPolicy.acceptsLibrary(meta.libraryName)

  /**
   * A Turnip driver is worth recommending: the phone can load one (Qualcomm), it runs on its own
   * driver, and that is older than Vulkan 1.3 (games then run on the core's compatibility path,
   * vk/compat.c).
   */
  fun turnipRecommended(context: Context): Boolean {
    init(context)
    return isAdreno() && getActiveDriver() == null &&
      !context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_3)
  }

  /** VK_API_VERSION_1_3, as FEATURE_VULKAN_HARDWARE_VERSION's versions are */
  private const val VULKAN_1_3 = 0x00403000

  fun initializeDriver(customDriverName: String? = null) {
    nativeInitializeDriver(hookLibDir, driverInstallDir, customDriverName)
  }

  /** Why a package wasn't added (addDriver). */
  enum class AddError { UNREADABLE, NOT_A_DRIVER, NO_LIBRARY, API, ABI, UNSAFE_PATH, UNPACK }

  /** addDriver's outcome: the package added (and in use), or why not. */
  data class AddResult(val driver: DriverMetadata? = null, val error: AddError? = null)

  /**
   * The package at uri copied into the store (under its own file name: a package of the same name is
   * replaced, an update of it) and put in use. Checked first: a meta.json, the library it names, the
   * Android version it needs.
   */
  fun addDriver(context: Context, uri: Uri): AddResult {
    init(context)
    val tmpFile = File(driverStorageDir, "driver_tmp.zip")
    try {
      context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
      } ?: return AddResult(error = AddError.UNREADABLE)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to copy driver URI", e)
      tmpFile.delete()
      return AddResult(error = AddError.UNREADABLE)
    }
    val meta = readMetadata(tmpFile)
    val error = if (meta == null) AddError.NOT_A_DRIVER else packageError(tmpFile, meta)
    if (meta == null || error != null) {
      Log.e(TAG, "Not a usable driver package: $error")
      tmpFile.delete()
      return AddResult(meta, error)
    }
    val stored = File(driverStorageDir, packageFileName(context, uri, meta))
    stored.delete()
    if (!tmpFile.renameTo(stored)) {
      tmpFile.delete()
      return AddResult(meta, AddError.UNREADABLE)
    }
    val added = meta.copy(path = stored.absolutePath)
    return if (useDriver(stored)) AddResult(added) else AddResult(added, AddError.UNPACK)
  }

  /** A stored package's file name: the document's own (safe characters only), else the driver's name. */
  private fun packageFileName(context: Context, uri: Uri, meta: DriverMetadata): String {
    val shown = runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
      }
    }.getOrNull()
    val base = (shown ?: meta.name ?: "driver").removeSuffix(".zip").replace(Regex("[^A-Za-z0-9._-]+"), "_")
    return base.ifEmpty { "driver" } + ".zip"
  }

  fun packageError(zipFile: File, meta: DriverMetadata): AddError? = when (
    DriverPackageArchive.check(zipFile, meta.libraryName, meta.minApi, Build.VERSION.SDK_INT)
  ) {
    null -> null
    DriverPackageArchive.Error.UNREADABLE -> AddError.NOT_A_DRIVER
    DriverPackageArchive.Error.NO_LIBRARY -> AddError.NO_LIBRARY
    DriverPackageArchive.Error.API -> AddError.API
    DriverPackageArchive.Error.ABI -> AddError.ABI
    DriverPackageArchive.Error.UNSAFE_PATH -> AddError.UNSAFE_PATH
  }

  /**
   * The stored package put in use: unpacked beside the one in use, which it replaces only once it is
   * whole (a package that fails to unpack leaves the driver in use as it was).
   */
  fun useDriver(driverZip: File): Boolean {
    val meta = readMetadata(driverZip) ?: return false
    if (packageError(driverZip, meta) != null) return false
    val installDir = File(driverInstallDir)
    val newDir = File(installDir.parentFile, installDir.name + ".new")
    newDir.deleteRecursively()
    newDir.mkdirs()
    val root = newDir.canonicalPath + File.separator
    try {
      ZipFile(driverZip).use { zip ->
        zip.entries().asSequence().forEach { entry ->
          val outFile = File(newDir, entry.name)
          if (!outFile.canonicalPath.startsWith(root)) {
            throw IOException("entry outside the package: ${entry.name}")
          }
          if (entry.isDirectory) {
            outFile.mkdirs()
          } else {
            outFile.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input ->
              FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
          }
        }
      }
      File(newDir, PACKAGE_MARKER).writeText(packageId(driverZip))
    } catch (e: Exception) {
      Log.e(TAG, "Failed to extract driver", e)
      newDir.deleteRecursively()
      return false
    }
    installDir.deleteRecursively()
    if (!newDir.renameTo(installDir)) {
      Log.e(TAG, "Failed to move the driver into place")
      newDir.deleteRecursively()
      installDir.mkdirs()
      return false
    }
    return true
  }

  /** The system driver in use: driverInstallDir emptied. */
  fun useSystemDriver() {
    choices.edit().putBoolean(BOOTSTRAPPED, true).commit()
    File(driverInstallDir).deleteRecursively()
    File(driverInstallDir).mkdirs()
  }

  /** A stored package deleted; the one in use too: then the system driver is. True if it was in use. */
  fun deleteDriver(driverZip: File): Boolean {
    if (isBundledDriver(driverZip)) return false
    val inUse = isInUse(driverZip)
    driverZip.delete()
    if (inUse) {
      useSystemDriver()
    }
    return inUse
  }

  /** The driver in use (its meta.json), or null: the system driver. */
  fun getActiveDriver(): DriverMetadata? {
    val metaFile = File(driverInstallDir, META_JSON)
    if (!metaFile.exists()) return null
    return try {
      parseMetadata(JSONObject(metaFile.readText()), null)
    } catch (e: Exception) {
      null
    }
  }

  fun getInstalledDriverName(): String? = getActiveDriver()?.name

  fun getInstalledDriverLibrary(): String? = getActiveDriver()
    ?.takeIf(::canLoadDriver)
    ?.libraryName

  /**
   * The stored package is the driver in use: by the package name the install keeps, else (a driver
   * put in use before it did, or brought over from the previous release) by the driver's name.
   */
  fun isInUse(driverZip: File): Boolean {
    val marker = File(driverInstallDir, PACKAGE_MARKER)
    if (marker.exists()) {
      return runCatching { marker.readText() }.getOrNull() == packageId(driverZip)
    }
    val active = getActiveDriver()?.name ?: return false
    return readMetadata(driverZip)?.name == active
  }

  fun getAvailableDrivers(): List<DriverMetadata> {
    val dir = File(driverStorageDir)
    val stored = dir.listFiles()
      ?.filter { it.extension == "zip" && it.name != "driver_tmp.zip" }
      ?.mapNotNull { readMetadata(it)?.copy(path = it.absolutePath) }
      ?.sortedBy { it.name?.lowercase() }
      ?: emptyList()
    val bundled = bundles.mapNotNull { readMetadata(it.file) }
    return bundled + stored
  }

  /** A package's meta.json (the first .json if none is called that), or null. */
  fun readMetadata(zipFile: File): DriverMetadata? {
    if (!zipFile.exists()) return null
    try {
      ZipFile(zipFile).use { zip ->
        val jsons = zip.entries().asSequence().filter { !it.isDirectory && it.name.lowercase().endsWith(".json") }.toList()
        val entry = jsons.firstOrNull { File(it.name).name.equals(META_JSON, ignoreCase = true) } ?: jsons.firstOrNull()
          ?: return null
        zip.getInputStream(entry).use { input ->
          return parseMetadata(JSONObject(input.bufferedReader().readText()), zipFile.absolutePath)
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read driver metadata from ${zipFile.name}", e)
    }
    return null
  }

  private fun parseMetadata(json: JSONObject, path: String?) = DriverMetadata(
    name = json.optString("name").ifEmpty { null },
    description = json.optString("description").ifEmpty { null },
    author = json.optString("author").ifEmpty { null },
    vendor = json.optString("vendor").ifEmpty { null },
    driverVersion = json.optString("driverVersion").ifEmpty { null },
    packageVersion = json.optString("packageVersion").ifEmpty { null },
    libraryName = json.optString("libraryName").ifEmpty { null },
    minApi = json.optInt("minApi", 0),
    path = path,
  )

  private external fun nativeInitializeDriver(
    hookLibDir: String?,
    customDriverDir: String?,
    customDriverName: String?
  )

  data class DriverMetadata(
    val name: String? = null,
    val description: String? = null,
    val author: String? = null,
    val vendor: String? = null,
    val driverVersion: String? = null,
    val packageVersion: String? = null,
    val libraryName: String? = null,
    val minApi: Int = 0,
    val path: String? = null
  )
}

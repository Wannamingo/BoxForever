package com.rfandango.haku_x.data

import android.app.ActivityManager
import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException

/** One user-picked document survives uninstall; only portable settings are restored. */
internal class SettingsBackupStore(context: Context) {
  private val context = context.applicationContext
  private val extras = ExperienceStore(this.context)

  fun export(): String {
    val prefs = SettingsBackupFormat.stores.associateWith { name ->
      context.getSharedPreferences(name, Context.MODE_PRIVATE).all
        .filter { (key, value) -> SettingsBackupFormat.portableKey(name, key) && value != null }
        .mapValues { it.value!! }
    }
    val documents = SettingsBackupFormat.files.associateWith { name -> readFile(name)?.toString(Charsets.UTF_8) }
    val performance = profileNames().associateWith { name ->
      readAtomic(File(profileRoot, name))!!.toString(Charsets.UTF_8)
    }
    return SettingsBackupFormat.encode(SettingsBackupFormat.Snapshot(prefs, documents, UiLanguage.current(context), System.currentTimeMillis(), performance))
  }

  fun gameRunning(): Boolean = context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
    .any { it.processName == "${context.packageName}:native" }

  fun restore(snapshot: SettingsBackupFormat.Snapshot) {
    check(!gameRunning()) { "Close the running game before restoring settings" }
    val validated = SettingsBackupFormat.decode(SettingsBackupFormat.encode(snapshot))
    val changes = mutableListOf<SettingsRestoreTransaction.Change>()
    for ((name, values) in validated.preferences) {
      val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
      val before = prefs.all.filterKeys { SettingsBackupFormat.portableKey(name, it) }.mapValues { it.value!! }
      fun replace(next: Map<String, Any>) {
        val editor = prefs.edit()
        prefs.all.keys.filter { SettingsBackupFormat.portableKey(name, it) }.forEach(editor::remove)
        for ((key, value) in next) when (value) {
          is Boolean -> editor.putBoolean(key, value)
          is Int -> editor.putInt(key, value)
          is Long -> editor.putLong(key, value)
          is Float -> editor.putFloat(key, value)
          is String -> editor.putString(key, value)
          else -> error("Unsupported setting type")
        }
        check(editor.commit()) { "Could not store settings" }
      }
      changes += SettingsRestoreTransaction.Change({ replace(values) }, { replace(before) })
    }
    // Capture every previous file before writing the first setting.
    val previousFiles = SettingsBackupFormat.files.associateWith(::readFile)
    for ((name, text) in validated.documents) {
      changes += SettingsRestoreTransaction.Change({ writeFile(name, text?.toByteArray(Charsets.UTF_8)) }, { writeFile(name, previousFiles[name]) })
    }
    val oldProfiles = profileNames().associateWith { readAtomic(File(profileRoot, it)) }
    for (name in oldProfiles.keys + validated.performance.keys) {
      val target = File(profileRoot, name)
      changes += SettingsRestoreTransaction.Change({ writeAtomic(target, validated.performance[name]?.toByteArray(Charsets.UTF_8)) },
        { writeAtomic(target, oldProfiles[name]) })
    }
    val language = UiLanguage.current(context)
    changes += SettingsRestoreTransaction.Change({ UiLanguage.set(context, validated.language) }, { UiLanguage.set(context, language) })
    SettingsRestoreTransaction.run(changes)
  }

  private fun readFile(name: String): ByteArray? {
    if (name == "experience-settings.json") return extras.backupText()?.toByteArray(Charsets.UTF_8)
    return readAtomic(File(context.filesDir, name))
  }

  private val profileRoot get() = File(context.filesDir, "performance/profiles")
  private fun profileNames() = profileRoot.listFiles().orEmpty().map { it.name.removeSuffix(".bak") }
    .filter { SettingsBackupFormat.profileName.matches(it) }.distinct()

  private fun readAtomic(target: File): ByteArray? {
    val file = AtomicFile(target)
    return try { file.openRead().use { stream ->
      val bytes = ByteArray(SettingsBackupFormat.MAX_BYTES + 1)
      var size = 0
      while (size < bytes.size) { val count = stream.read(bytes, size, bytes.size - size); if (count < 0) break; if (count == 0) continue; size += count }
      require(size <= SettingsBackupFormat.MAX_BYTES)
      bytes.copyOf(size)
    } } catch (e: FileNotFoundException) {
      if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw e else null
    }
  }

  private fun writeFile(name: String, bytes: ByteArray?) {
    require(name in SettingsBackupFormat.files)
    if (name == "experience-settings.json") { extras.restoreBackup(bytes?.toString(Charsets.UTF_8)); return }
    writeAtomic(File(context.filesDir, name), bytes)
  }

  private fun writeAtomic(target: File, bytes: ByteArray?) {
    val file = AtomicFile(target)
    if (bytes == null) { file.delete(); check(!file.baseFile.exists()); return }
    val output = file.startWrite()
    try {
      output.write(bytes); file.finishWrite(output)
      check(file.openRead().use { it.readBytes().contentEquals(bytes) })
    } catch (e: Exception) { file.failWrite(output); throw e }
  }
}

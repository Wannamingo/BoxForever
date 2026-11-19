package com.rfandango.haku_x.ui.library

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rfandango.haku_x.R
import com.rfandango.haku_x.data.CalibrationStore
import com.rfandango.haku_x.data.hdd.ConsoleFiles
import com.rfandango.haku_x.data.hdd.GameDataArchive
import com.rfandango.haku_x.data.hdd.HddStore
import com.rfandango.haku_x.data.hdd.LibraryKeys
import com.rfandango.haku_x.data.hdd.SaveArchive
import com.rfandango.haku_x.data.hdd.XboxHdd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class GameDataViewModel(private val app: Application) : AndroidViewModel(app) {
  data class Import(val archive: File, val titleId: String, val plan: SaveArchive.Plan, val canResign: Boolean)
  private val _busy = MutableStateFlow(false)
  val busy = _busy.asStateFlow()
  private val _pending = MutableStateFlow<Import?>(null)
  val pending = _pending.asStateFlow()
  private val _notice = MutableStateFlow<String?>(null)
  val notice = _notice.asStateFlow()
  private val _revision = MutableStateFlow(0)
  val revision = _revision.asStateFlow()
  private fun partition() = XboxHdd.folder(HddStore(app).root, 1)

  fun clearNotice() { _notice.value = null }
  fun cancelImport() { _pending.value?.archive?.delete(); _pending.value = null }

  private fun work(action: suspend () -> String) {
    if (_busy.value) return
    _busy.value = true
    viewModelScope.launch {
      val result = withContext(Dispatchers.IO) {
        runCatching {
          check(CalibrationStore.activeGame(app) == null) { app.getString(R.string.game_data_stop_game) }
          action()
        }
      }
      _notice.value = result.fold({ it.takeIf(String::isNotEmpty) }, {
        app.getString(R.string.game_operation_failed, it.message ?: it.javaClass.simpleName)
      })
      _revision.value++
      _busy.value = false
    }
  }

  fun export(uri: Uri, id: String) = work {
    val count = app.contentResolver.openOutputStream(uri, "wt")!!.use { GameDataArchive.write(it, partition(), id) }
    app.getString(R.string.game_data_exported, count)
  }

  fun exportToFolder(tree: Uri, id: String, title: String) = work {
    val archive = File(app.cacheDir, "game-data-export-${UUID.randomUUID()}.zip")
    var destination: DocumentFile? = null
    try {
      val count = archive.outputStream().use { GameDataArchive.write(it, partition(), id) }
      val folder = DocumentFile.fromTreeUri(app, tree)
      check(folder?.isDirectory == true && folder.canWrite()) { app.getString(R.string.game_data_folder_unavailable) }
      val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
      val date = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
      destination = folder.createFile("application/zip", "$safeTitle-$id-$date.zip")
      check(destination != null) { app.getString(R.string.game_data_folder_unavailable) }
      app.contentResolver.openOutputStream(destination.uri, "wt")!!.use { out -> archive.inputStream().use { it.copyTo(out) } }
      app.getString(R.string.game_data_exported, count)
    } catch (error: Exception) { destination?.delete(); throw error }
    finally { archive.delete() }
  }

  fun inspect(uri: Uri, id: String) {
    cancelImport()
    work {
      val archive = File(app.cacheDir, "game-data-${UUID.randomUUID()}.zip")
      try {
        app.contentResolver.openInputStream(uri)!!.use { input ->
          archive.outputStream().use { output ->
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            while (true) {
              val n = input.read(buffer)
              if (n < 0) break
              total += n
              require(total <= GameDataArchive.MAX_BYTES) { app.getString(R.string.game_data_too_large) }
              output.write(buffer, 0, n)
            }
          }
        }
        val plan = archive.inputStream().use { SaveArchive.plan(it, partition(), id, GameDataArchive.MAX_BYTES) }
        require(plan.files > 0) { app.getString(R.string.game_data_no_match, id) }
        val selected = Import(archive, id, plan, ConsoleFiles(app).status().ready)
        withContext(Dispatchers.Main.immediate) { _pending.value = selected }
        ""
      } catch (error: Exception) { archive.delete(); throw error }
    }
  }

  fun restore() {
    val selected = _pending.value ?: return
    if (_busy.value) return
    _pending.value = null
    work {
      try {
        var note = ""
        val result = GameDataArchive.restore(selected.archive, partition(), selected.titleId) { stage, files ->
          if (selected.plan.kind == null) {
            val resigner = ConsoleFiles(app).resigner()
            if (resigner != null) {
              val keys = LibraryKeys.load(app, setOf(selected.titleId.toLong(16)))
              val report = resigner.resign(stage, files.written, keys::get)
              note = app.resources.getQuantityString(R.plurals.hdd_resigned, report.resigned, report.resigned)
              if (report.noKey.isNotEmpty()) note += " " + app.getString(R.string.hdd_resign_nokey, selected.titleId)
            } else note = app.getString(R.string.hdd_saves_import_foreign_nokeys)
          }
        }
        app.getString(R.string.game_data_imported, result.files) + if (note.isBlank()) "" else "\n$note"
      } finally { selected.archive.delete() }
    }
  }

  override fun onCleared() { cancelImport() }
}

package com.rfandango.haku_x.ui.settings

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rfandango.haku_x.R
import com.rfandango.haku_x.data.SettingsBackupFormat
import com.rfandango.haku_x.data.SettingsBackupStore
import com.rfandango.haku_x.data.UiLanguage
import com.rfandango.haku_x.ui.common.ConfirmDialog
import com.rfandango.haku_x.ui.common.Hint
import com.rfandango.haku_x.ui.common.Section
import com.rfandango.haku_x.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Separate from a single game's profile: one file for all portable user settings. */
@Composable
internal fun SettingsBackupSection() {
  val context = LocalContext.current
  val activity = LocalActivity.current
  val store = remember { SettingsBackupStore(context) }
  val scope = rememberCoroutineScope()
  var busy by remember { mutableStateOf(false) }
  var pending by remember { mutableStateOf<SettingsBackupFormat.Snapshot?>(null) }
  fun report(message: Int) = Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
  val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
    if (uri != null) scope.launch {
      busy = true
      try {
        withContext(Dispatchers.IO) {
          val text = store.export()
          context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        }
        report(R.string.settings_backup_exported)
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (e: Exception) { Log.w("settings-backup", "Export failed", e); report(R.string.settings_backup_failed) }
      finally { busy = false }
    }
  }
  val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) scope.launch {
      busy = true
      try {
        pending = withContext(Dispatchers.IO) {
          context.contentResolver.openInputStream(uri)!!.use(SettingsBackupFormat::read)
        }
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (e: Exception) { Log.w("settings-backup", "Import validation failed", e); report(R.string.settings_backup_failed) }
      finally { busy = false }
    }
  }
  SettingsTarget(R.string.settings_backup_title) {
    Section(stringResource(R.string.settings_backup_title))
    Hint(stringResource(R.string.settings_backup_description))
    TextButton(enabled = !busy, onClick = {
      val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
      export.launch("BoxForever-settings-$date.json")
    }) { Text(stringResource(R.string.settings_backup_export)) }
    TextButton(enabled = !busy, onClick = {
      if (store.gameRunning()) report(R.string.settings_backup_game_running)
      else importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    }) { Text(stringResource(R.string.settings_backup_import)) }
    Hint(stringResource(R.string.settings_backup_limits))
  }
  pending?.let { snapshot -> ConfirmDialog(
    title = stringResource(R.string.settings_backup_import),
    text = stringResource(R.string.settings_backup_confirm, snapshot.gameCount),
    confirm = stringResource(R.string.settings_backup_restore),
    onConfirm = {
      pending = null
      if (store.gameRunning()) report(R.string.settings_backup_game_running)
      else scope.launch {
        busy = true
        try {
          val localeChanged = UiLanguage.current(context) != snapshot.language
          withContext(Dispatchers.IO) { store.restore(snapshot) }
          AppTheme.load(context)
          report(R.string.settings_backup_restored)
          if (!localeChanged || android.os.Build.VERSION.SDK_INT < 33) activity?.recreate()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { Log.w("settings-backup", "Restore failed", e); report(R.string.settings_backup_failed) }
        finally { busy = false }
      }
    }, onDismiss = { pending = null },
  ) }
  if (busy) AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.settings_backup_working)) },
    text = { CircularProgressIndicator() }, confirmButton = {})
}

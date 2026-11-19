package com.rfandango.haku_x.ui.library

import android.widget.Toast
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rfandango.haku_x.R
import com.rfandango.haku_x.data.*
import com.rfandango.haku_x.data.hdd.GameDataArchive
import com.rfandango.haku_x.data.hdd.HddStore
import com.rfandango.haku_x.data.hdd.XboxHdd
import com.rfandango.haku_x.ui.common.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GameDetailsScreen(game: Game?, disc: DiscInfo?, icon: ImageBitmap?, loading: Boolean,
  onBack: () -> Unit, onLaunch: (Game) -> Unit, onLoadState: (Game, SaveStateStore, Int) -> Unit,
  onSettings: (Game, DiscInfo?) -> Unit, onMods: (Game, DiscInfo) -> Unit, onConvert: (Game) -> Unit,
  data: GameDataViewModel = viewModel()) {
  if (game == null) {
    SubScreen(title = stringResource(R.string.game_details_title), onBack = onBack) { padding ->
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator() else EmptyText(stringResource(R.string.game_details_unavailable))
      }
    }
    return
  }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val presentation = remember { LibraryPresentation(context) }
  val key = remember(game.uri, disc) { GameOverrides.gameKey(disc?.titleId ?: 0, disc?.version ?: 0, game.relativePath) }
  val store = remember(key, game.uri) { SaveStateStore(context, key, game.uri.toString()) }
  var launch by remember { mutableStateOf(false) }
  var states by remember { mutableStateOf(false) }
  var coverRevision by remember { mutableIntStateOf(0) }
  var resumeRevision by remember { mutableIntStateOf(0) }
  LifecycleResumeEffect(Unit) { resumeRevision++; onPauseOrDispose {} }
  val id = disc?.takeIf { it.titleId != 0L }?.titleIdText
  val busy by data.busy.collectAsStateWithLifecycle()
  val pending by data.pending.collectAsStateWithLifecycle()
  val notice by data.notice.collectAsStateWithLifecycle()
  val revision by data.revision.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); data.clearNotice() } }
  val hasData by produceState(false, id, revision, resumeRevision) {
    value = id != null && withContext(Dispatchers.IO) { GameDataArchive.hasData(XboxHdd.folder(HddStore(context).root, 1), id) }
  }
  val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
    if (uri != null && id != null) data.export(uri, id)
  }
  val locations = remember { context.getSharedPreferences("game_data_locations", android.content.Context.MODE_PRIVATE) }
  var folder by remember(id) { mutableStateOf(id?.let { locations.getString(it, null) }) }
  val folderName by produceState<String?>(null, folder) {
    value = folder?.let { withContext(Dispatchers.IO) { runCatching {
      androidx.documentfile.provider.DocumentFile.fromTreeUri(context, Uri.parse(it))?.name
    }.getOrNull() } }
  }
  val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null && id != null) {
      runCatching { context.contentResolver.takePersistableUriPermission(uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }.onSuccess {
        folder = uri.toString()
        locations.edit().putString(id, folder).apply()
      }.onFailure { Toast.makeText(context, R.string.game_data_folder_unavailable, Toast.LENGTH_LONG).show() }
    }
  }
  val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null && id != null) data.inspect(uri, id)
  }
  val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) scope.launch {
      val result = withContext(Dispatchers.IO) { runCatching { presentation.importCover(game, disc, uri) } }
      if (result.isSuccess) coverRevision++ else Toast.makeText(context, R.string.ui_library_cover_error, Toast.LENGTH_LONG).show()
    }
  }
  SubScreen(title = stringResource(R.string.game_details_title), subtitle = game.title, onBack = onBack, snackbar = snackbar) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
      Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        GameCover(game, disc, icon, presentation, coverRevision, Modifier.width(96.dp).height(144.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
          Text(game.title, style = MaterialTheme.typography.titleLarge)
          if (id != null) Text("$id · v${disc?.version}", style = MaterialTheme.typography.bodyMedium)
          Text(game.relativePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      ActionItem(stringResource(R.string.game_details_play), null, Icons.Filled.PlayArrow) { launch = true }
      ActionItem(stringResource(R.string.library_menu_settings), stringResource(R.string.game_details_settings_desc), Icons.Filled.Settings) { onSettings(game, disc) }
      ActionItem(stringResource(R.string.library_menu_mods), stringResource(R.string.game_details_mods_desc), Icons.Filled.Star, enabled = id != null) {
        disc?.let { onMods(game, it) }
      }
      ActionItem(stringResource(R.string.library_menu_load_state), stringResource(R.string.game_details_states_desc), null,
        enabled = store.saved().isNotEmpty()) { states = true }
      Section(stringResource(R.string.game_data_title))
      Hint(stringResource(R.string.game_data_desc))
      if (id == null) Hint(stringResource(R.string.game_details_wait_id))
      ActionItem(stringResource(R.string.game_data_folder), folderName ?: stringResource(R.string.game_data_folder_desc), null,
        enabled = id != null && !busy) { chooseFolder.launch(folder?.let(Uri::parse)) }
      if (folder != null) ActionItem(stringResource(R.string.game_data_folder_clear), null, null, enabled = !busy) {
        id?.let { locations.edit().remove(it).apply() }; folder = null
      }
      ActionItem(stringResource(R.string.game_data_export), stringResource(R.string.game_data_export_desc), null,
        enabled = id != null && hasData && !busy) {
        if (folder != null && id != null) data.exportToFolder(Uri.parse(folder), id, game.title)
        else export.launch("${game.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)}-${id}-saves.zip")
      }
      ActionItem(stringResource(R.string.game_data_import), stringResource(R.string.game_data_import_desc), null,
        enabled = id != null && !busy) { import.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
      Section(stringResource(R.string.game_details_library))
      ActionItem(stringResource(R.string.ui_library_set_cover), null, null) { pickCover.launch(arrayOf("image/*")) }
      if (presentation.coverFile(game, disc).isFile) ActionItem(stringResource(R.string.ui_library_remove_cover), null, null) {
        scope.launch { withContext(Dispatchers.IO) { presentation.coverFile(game, disc).delete() }; coverRevision++ }
      }
      if (disc?.isFullDump == true && game.kind == GameKind.IMAGE) {
        ActionItem(stringResource(R.string.library_menu_convert), null, Icons.Filled.Build) { onConvert(game) }
      }
      Spacer(Modifier.height(24.dp))
    }
  }
  if (launch) GameLaunchDialog(game, disc, onLaunch, onLoadState, onDismiss = { launch = false })
  if (states) LoadStatesDialog(game.title, store, onLoad = { states = false; onLoadState(game, store, it) }, onDismiss = { states = false })
  pending?.let { item ->
    val foreign = if (item.plan.kind != null) "" else "\n" + context.getString(
      if (item.canResign) R.string.hdd_saves_import_foreign else R.string.hdd_saves_import_foreign_nokeys)
    ConfirmDialog(title = stringResource(R.string.hdd_saves_import_title),
      text = stringResource(R.string.game_data_confirm, game.title, item.titleId, item.plan.files, item.plan.replaced) + foreign,
      confirm = stringResource(R.string.hdd_saves_import_go), onConfirm = data::restore, onDismiss = data::cancelImport)
  }
  if (busy) AlertDialog(onDismissRequest = {},
    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    title = { Text(stringResource(R.string.game_data_working)) },
    text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
}

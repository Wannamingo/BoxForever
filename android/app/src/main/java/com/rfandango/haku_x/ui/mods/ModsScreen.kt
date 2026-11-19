package com.rfandango.haku_x.ui.mods

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rfandango.haku_x.ui.common.ActionItem
import com.rfandango.haku_x.ui.common.ConfirmDialog
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rfandango.haku_x.R
import com.rfandango.haku_x.data.ModChoices
import com.rfandango.haku_x.ui.common.EmptyText
import com.rfandango.haku_x.ui.common.Hint
import com.rfandango.haku_x.ui.common.Section
import com.rfandango.haku_x.ui.common.SubScreen
import com.rfandango.haku_x.ui.common.SwitchItem

/**
 * One disc's mods and cheats (the repository's mods/ folder): each on or
 * off. Mods take the next start; cheats also turn on and off in the game's
 * menu while it runs.
 */
@Composable
fun ModsScreen(gameKey: String, title: String, onBack: () -> Unit, vm: ModsViewModel = viewModel()) {
  val context = LocalContext.current
  val game by vm.game.collectAsStateWithLifecycle()
  val busy by vm.busy.collectAsStateWithLifecycle()
  val notice by vm.notice.collectAsStateWithLifecycle()
  val hasUserContent by vm.hasUserContent.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  LaunchedEffect(gameKey) { vm.load(gameKey) }
  LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); vm.clearNotice() } }
  var pending by rememberSaveable { mutableStateOf<String?>(null) }
  var importingCheats by rememberSaveable { mutableStateOf(false) }
  var remove by rememberSaveable { mutableStateOf(false) }
  val pickMod = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) { importingCheats = false; pending = uri.toString() }
  }
  val pickCheats = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) { importingCheats = true; pending = uri.toString() }
  }
  val choices = remember(gameKey) { ModChoices(context, gameKey) }
  val mods = remember(gameKey, game) { mutableStateMapOf<String, Boolean>().apply { game.mods.forEach { put(it.id, choices.mod(it.id)) } } }
  val cheats = remember(gameKey, game) {
    mutableStateMapOf<String, Boolean>().apply { game.cheats.forEach { put(it.name, choices.cheat(it.name)) } }
  }

  SubScreen(title = stringResource(R.string.mods_title), subtitle = title, onBack = onBack, snackbar = snackbar) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
      Hint(stringResource(R.string.game_mods_format, gameKey))
      ActionItem(stringResource(R.string.game_mods_import), stringResource(R.string.game_mods_import_desc), null, enabled = !busy) {
        pickMod.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
      }
      ActionItem(stringResource(R.string.game_cheats_import), stringResource(R.string.game_cheats_import_desc), null, enabled = !busy) {
        pickCheats.launch(arrayOf("text/plain", "application/octet-stream"))
      }
      if (hasUserContent) {
        ActionItem(stringResource(R.string.game_mods_remove), null, null, enabled = !busy) { remove = true }
      }
      if (game.isEmpty) {
        EmptyText(stringResource(R.string.mods_none))
      }
      if (game.mods.isNotEmpty()) {
        Section(stringResource(R.string.mods_section_mods))
        Hint(stringResource(R.string.mods_mods_desc))
        for (mod in game.mods) {
          val by = if (mod.author.isNotEmpty()) stringResource(R.string.mods_by, mod.author) else ""
          SwitchItem(
            title = mod.name,
            summary = listOf(mod.description, by).filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { null },
            checked = mods[mod.id] == true,
            onChange = { v ->
              choices.setMod(mod.id, v)
              mods[mod.id] = v
            },
          )
        }
      }
      if (game.cheats.isNotEmpty()) {
        Section(stringResource(R.string.mods_section_cheats))
        Hint(stringResource(R.string.mods_cheats_desc))
        for (cheat in game.cheats) {
          SwitchItem(
            title = cheat.name,
            summary = cheat.note.ifEmpty { null },
            checked = cheats[cheat.name] == true,
            onChange = { v ->
              choices.setCheat(cheat.name, v)
              cheats[cheat.name] = v
            },
          )
        }
      }
    }
  }
  pending?.let { uri ->
    ConfirmDialog(title = stringResource(if (importingCheats) R.string.game_cheats_import else R.string.game_mods_import),
      text = stringResource(R.string.game_mods_confirm, title, gameKey), confirm = stringResource(R.string.hdd_saves_import_go),
      onConfirm = { pending = null; vm.install(gameKey, Uri.parse(uri), importingCheats) }, onDismiss = { pending = null })
  }
  if (remove) ConfirmDialog(title = stringResource(R.string.game_mods_remove), text = stringResource(R.string.game_mods_remove_desc),
    confirm = stringResource(R.string.common_delete), onConfirm = { remove = false; vm.removeUserContent(gameKey) }, onDismiss = { remove = false })
  if (busy) AlertDialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    title = { Text(stringResource(R.string.game_mods_working)) }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})

}

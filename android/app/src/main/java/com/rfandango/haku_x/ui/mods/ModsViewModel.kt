package com.rfandango.haku_x.ui.mods

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rfandango.haku_x.R
import com.rfandango.haku_x.data.CalibrationStore
import com.rfandango.haku_x.data.GameMods
import com.rfandango.haku_x.data.ModCatalog
import com.rfandango.haku_x.data.ModChoices
import com.rfandango.haku_x.data.UserModPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class ModsViewModel(private val app: Application) : AndroidViewModel(app) {
  private val _game = MutableStateFlow(GameMods(emptyList(), emptyList()))
  val game = _game.asStateFlow()
  private val _busy = MutableStateFlow(false)
  val busy = _busy.asStateFlow()
  private val _notice = MutableStateFlow<String?>(null)
  val notice = _notice.asStateFlow()
  private val _hasUserContent = MutableStateFlow(false)
  val hasUserContent = _hasUserContent.asStateFlow()
  fun clearNotice() { _notice.value = null }

  fun load(key: String) {
    viewModelScope.launch {
      _game.value = withContext(Dispatchers.IO) { ModCatalog.load(app, key) }
      _hasUserContent.value = _game.value.mods.any { it.id.startsWith(UserModPack.PREFIX) } ||
        withContext(Dispatchers.IO) { UserModPack.cheats(ModCatalog.userRoot(app), key).isNotEmpty() }
    }
  }

  private fun change(key: String, action: () -> Unit) {
    if (_busy.value) return
    _busy.value = true
    viewModelScope.launch {
      val result = withContext(Dispatchers.IO) {
        runCatching {
          check(CalibrationStore.activeGame(app) == null) { app.getString(R.string.game_mods_stop_game) }
          action()
          ModCatalog.load(app, key)
        }
      }
      result.onSuccess {
        _game.value = it
        _hasUserContent.value = it.mods.any { mod -> mod.id.startsWith(UserModPack.PREFIX) } ||
          withContext(Dispatchers.IO) { UserModPack.cheats(ModCatalog.userRoot(app), key).isNotEmpty() }
      }
      _notice.value = result.fold({ app.getString(R.string.game_mods_updated) }, {
        app.getString(R.string.game_operation_failed, it.message ?: it.javaClass.simpleName)
      })
      _busy.value = false
    }
  }

  fun install(key: String, uri: Uri, cheats: Boolean) = change(key) {
    app.contentResolver.openInputStream(uri)!!.use { input ->
      if (cheats) UserModPack.installCheats(ModCatalog.userRoot(app), key, input)
      else UserModPack.install(ModCatalog.userRoot(app), key, input)
    }
    // Imported code is always opt-in, even when its name matches a previously enabled cheat.
    val choices = ModChoices(app, key)
    UserModPack.mods(ModCatalog.userRoot(app), key).forEach { choices.setMod(it.id, false) }
    UserModPack.cheats(ModCatalog.userRoot(app), key).forEach { choices.setCheat(it.name, false) }
  }

  fun removeUserContent(key: String) = change(key) {
    val root = ModCatalog.userRoot(app)
    val choices = ModChoices(app, key)
    UserModPack.mods(root, key).forEach { choices.setMod(it.id, false) }
    UserModPack.cheats(root, key).forEach { choices.setCheat(it.name, false) }
    val dir = File(root, key.uppercase(Locale.ROOT))
    check(!dir.exists() || dir.deleteRecursively()) { "Cannot remove user mods" }
  }
}

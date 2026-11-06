package com.rfandango.haku_x.ui.settings

import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rfandango.haku_x.GameRestart
import com.rfandango.haku_x.R
import com.rfandango.haku_x.data.CalibrationStore
import com.rfandango.haku_x.ui.common.ConfirmDialog

/** Global settings target the active session; per-game settings only target that title. */
@Composable
internal fun RestartGameAction(gameKey: String? = null) {
  val context = LocalContext.current
  val active = remember(gameKey) { CalibrationStore.activeGame(context) }
  var confirming by remember(gameKey) { mutableStateOf(false) }
  if (active == null || (gameKey != null && active != gameKey)) return
  TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.game_restart)) }
  if (confirming) ConfirmDialog(
    title = stringResource(R.string.game_restart_title),
    text = stringResource(R.string.game_restart_message),
    confirm = stringResource(R.string.game_restart),
    onConfirm = {
      confirming = false
      if (!GameRestart.request(context, active))
        Toast.makeText(context, R.string.game_restart_unavailable, Toast.LENGTH_LONG).show()
    },
    onDismiss = { confirming = false },
  )
}

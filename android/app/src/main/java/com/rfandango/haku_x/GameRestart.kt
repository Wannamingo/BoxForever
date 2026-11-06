package com.rfandango.haku_x

import android.content.Context
import android.content.Intent
import com.rfandango.haku_x.data.CalibrationStore

/** Send a restart to the existing game activity; never start a disc or state here. */
object GameRestart {
  const val EXTRA_REQUEST = "com.rfandango.haku_x.RESTART_GAME"

  fun request(context: Context, game: String): Boolean {
    if (CalibrationStore.activeGame(context) != game) return false
    context.startActivity(Intent(context, NativeCoreActivity::class.java)
      .putExtra(EXTRA_REQUEST, game)
      .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    return true
  }
}

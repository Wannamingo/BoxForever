package com.rfandango.haku_x

import android.content.Context
import android.widget.ImageButton

/** Kept outside OnScreenController so hiding the controls never hides their switch. */
class ControlsVisibilityButton(context: Context, toggle: () -> Unit) : ImageButton(context) {
  init {
    background = context.getDrawable(R.drawable.overlay_visibility_background)
    scaleType = ScaleType.CENTER_INSIDE
    val padding = (12 * resources.displayMetrics.density).toInt()
    setPadding(padding, padding, padding, padding)
    // Tapping the switch must not take keyboard/gamepad focus from the game.
    isFocusable = false
    isFocusableInTouchMode = false
    setOnClickListener { toggle() }
  }

  fun setControlsShown(shown: Boolean) {
    setImageResource(if (shown) R.drawable.ic_controls_hide else R.drawable.ic_controls_show)
    val action = context.getString(if (shown) R.string.overlay_menu_hide else R.string.overlay_menu_show)
    contentDescription = action
    tooltipText = action
  }
}

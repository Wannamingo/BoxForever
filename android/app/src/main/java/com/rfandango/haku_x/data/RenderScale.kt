package com.rfandango.haku_x.data

object RenderScale {
  val choices = (2..8).map { it * 0.5f }
  fun parse(text: String?): Float? = text?.toFloatOrNull()?.takeIf { it in choices }
  fun format(value: Float): String = value.toString().removeSuffix(".0")

  // The existing dynamic settings store 1 and 2. Value 3 represents 1.5×.
  val dynamicChoices = listOf(1, 3, 2)
  fun dynamicSteps(setting: Int): Int = if (setting == 3) 3 else setting * 2
  fun dynamicScale(setting: Int): Float = dynamicSteps(setting) * 0.5f
}

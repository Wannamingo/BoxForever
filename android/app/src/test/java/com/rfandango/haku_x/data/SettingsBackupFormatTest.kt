package com.rfandango.haku_x.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class SettingsBackupFormatTest {
  private fun sample(): SettingsBackupFormat.Snapshot = SettingsBackupFormat.Snapshot(
    mapOf(
      "x1box_prefs" to mapOf("app_theme" to "dark", "frame_rate_limit" to 120, "render_scale_fraction" to 1.5f,
        "console_clock_offset" to 123456789012L, "show_fps" to false, "upscale_filter" to "fsr", "aspect_ratio" to "21:9"),
      "per_game_settings" to mapOf("12345678.1/fps_limit" to "60", "12345678.1/upscale" to "fsr",
        "12345678.1/aspect" to "16:10", "path:folder/game/render_scale" to "2.5", "path:folder/game/aspect" to "3:2"),
      "library_ui" to mapOf("covers" to true, "cover_size" to 2),
    ),
    SettingsBackupFormat.files.associateWith { name -> when (name) {
      "overlay_layout.json" -> """{"version":2,"controls":{"A":{"x":0.8,"y":0.7,"r":0.072,"v":true,"o":0},"VISIBILITY":{"x":0.4,"y":0.2,"r":0.05,"v":false,"o":0}}}"""
      "experience-settings.json" -> """{"global":{"post_mask":3,"volume":75},"games":{"12345678.1":{"anisotropy":4},"87654321.2":{"volume":50}},"hotkeys":{"MENU":108}}"""
      "hud-settings.json" -> """{"enabled":true,"size":12,"position":"TOP_RIGHT","background":0,"speed":true}"""
      "controllers.json" -> """{"slots":[{"mode":"auto","key":"","name":""}],"profiles":{"m:controller":{"deadzoneL":0.1,"bind":{"A":["k96"],"LS_X":["a0+"]}}},"names":{"m:controller":"Test pad"}}"""
      else -> """{"12345678.1/60fps":true}"""
    } }, "ru", 1795590000000L,
    mapOf(("a".repeat(64) + ".json") to """{"schema":1,"device":"${"b".repeat(64)}","options":{"scale":1.5,"fxaa":false,"upscale":"bilinear","fps":120}}"""),
  )
  private fun root() = JSONObject(SettingsBackupFormat.encode(sample()))
  private fun rejects(root: JSONObject) = assertThrows(IllegalArgumentException::class.java) { SettingsBackupFormat.decode(root.toString()) }

  @Test fun allSettingsRoundTripRetainsTypesEveryGameAndInvisibleControls() {
    val restored = SettingsBackupFormat.decode(SettingsBackupFormat.encode(sample()))
    assertEquals(sample().preferences, restored.preferences)
    assertTrue(restored.preferences.getValue("x1box_prefs").getValue("render_scale_fraction") is Float)
    assertTrue(restored.preferences.getValue("x1box_prefs").getValue("console_clock_offset") is Long)
    assertEquals(3, restored.gameCount)
    assertEquals("ru", restored.language)
    val controls = JSONObject(restored.documents.getValue("overlay_layout.json")!!).getJSONObject("controls")
    assertEquals(0.0, controls.getJSONObject("A").getDouble("o"), 0.0)
    assertEquals(0.4, controls.getJSONObject("VISIBILITY").getDouble("x"), 0.0)
    assertFalse(controls.getJSONObject("VISIBILITY").getBoolean("v"))
    assertEquals(sample().performance.keys, restored.performance.keys)
  }

  @Test fun missingPrivateFilesRepresentDefaultSettings() {
    val snapshot = sample().copy(documents = SettingsBackupFormat.files.associateWith { null }, performance = emptyMap())
    assertEquals(snapshot, SettingsBackupFormat.decode(SettingsBackupFormat.encode(snapshot)))
  }

  @Test fun devicePathsPermissionsAndSetupFlagsCannotBeImported() {
    for (key in listOf("setup_complete", "gamesFolders", "dvdUri", "hddPath", "driver_path")) {
      val root = root(); root.getJSONObject("preferences").getJSONObject("x1box_prefs").put(key, "content://untrusted")
      rejects(root)
    }
  }

  @Test fun fileNamesCannotEscapeKnownSettingsFiles() {
    val root = root(); root.getJSONObject("files").put("../native/hdd/eeprom.bin", JSONObject())
    rejects(root)
    val other = root(); other.getJSONObject("performance").put("../elsewhere.json", JSONObject())
    rejects(other)
  }

  @Test fun wrongPrimitiveTypesFractionalIntegersAndOutOfRangeValuesAreRejected() {
    for (bad in listOf<Any>("120", 60.5, -1, 241, true)) {
      val root = root(); root.getJSONObject("preferences").getJSONObject("x1box_prefs").put("frame_rate_limit", bad)
      rejects(root)
    }
    val root = root(); root.getJSONObject("preferences").getJSONObject("per_game_settings").put("12345678.1/render_scale", "99")
    rejects(root)
  }

  @Test fun malformedControlGeometryAndOpacityAreRejectedBeforeRestore() {
    for ((field, value) in listOf("x" to -0.01, "y" to 1.1, "r" to 0.0, "o" to -0.1, "o" to 1.1)) {
      val root = root(); root.getJSONObject("files").getJSONObject("overlay_layout.json").getJSONObject("controls").getJSONObject("A").put(field, value)
      rejects(root)
    }
  }

  @Test fun invalidEffectAndControllerValuesAreRejected() {
    val root = root(); root.getJSONObject("files").getJSONObject("experience-settings.json").getJSONObject("global").put("anisotropy", 3)
    rejects(root)
    val other = root(); other.getJSONObject("files").getJSONObject("controllers.json").getJSONObject("profiles")
      .getJSONObject("m:controller").put("deadzoneL", 4)
    rejects(other)
  }

  @Test fun malformedSectionsCannotSilentlyResetEffectsOrMappings() {
    for ((name, field) in listOf("experience-settings.json" to "global", "experience-settings.json" to "games",
      "controllers.json" to "profiles", "controllers.json" to "slots")) {
      val root = root(); root.getJSONObject("files").getJSONObject(name).put(field, "broken")
      rejects(root)
    }
  }

  @Test fun wrongFormatNewerVersionTruncatedOrTrailingDataCannotBeApplied() {
    val root = root(); root.put("version", 2); rejects(root)
    root.put("version", 1).put("format", "HakuX-Mali-profile"); rejects(root)
    val text = SettingsBackupFormat.encode(sample())
    assertThrows(IllegalArgumentException::class.java) { SettingsBackupFormat.decode(text.dropLast(2)) }
    assertThrows(IllegalArgumentException::class.java) { SettingsBackupFormat.decode(text + " garbage") }
  }

  @Test fun largeOrDeepInputIsBoundedBeforeJsonParsing() {
    assertThrows(IllegalArgumentException::class.java) { SettingsBackupFormat.read(ByteArrayInputStream(ByteArray(SettingsBackupFormat.MAX_BYTES + 1))) }
    assertThrows(IllegalArgumentException::class.java) { SettingsBackupFormat.decode("[".repeat(25) + "]".repeat(25)) }
  }

  @Test fun platformJsonExtensionsCannotBypassThePreflightScan() {
    val text = SettingsBackupFormat.encode(sample())
    for (suffix in listOf(", /* comment */ \"extra\": 0", ", 'extra': 0", ", # comment\n\"extra\": 0", "\u0000")) {
      assertThrows(IllegalArgumentException::class.java) { SettingsBackupFormat.decode(text.dropLast(1) + suffix + "}") }
    }
  }

  @Test fun readerAcceptsShortReadsAndRoundTripsUtf8() {
    val original = sample().copy(preferences = sample().preferences + ("x1box_prefs" to
      (sample().preferences.getValue("x1box_prefs") + ("network_lan_peers" to "сервер:3141"))))
    val bytes = SettingsBackupFormat.encode(original).toByteArray(Charsets.UTF_8)
    val input = object : ByteArrayInputStream(bytes) {
      override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(7, len))
    }
    assertEquals(original.preferences, SettingsBackupFormat.read(input).preferences)
  }

  @Test fun failedWriteRollsBackPartiallyWrittenStepAndEarlierChanges() {
    val values = mutableMapOf("prefs" to "old prefs", "layout" to "old layout", "later" to "unchanged")
    val changes = listOf(
      SettingsRestoreTransaction.Change({ values["prefs"] = "new" }, { values["prefs"] = "old prefs" }),
      SettingsRestoreTransaction.Change({ values["layout"] = "partial"; throw IOException("disk full") }, { values["layout"] = "old layout" }),
      SettingsRestoreTransaction.Change({ values["later"] = "must not run" }, { fail("Untouched data must not be changed") }),
    )
    assertThrows(IOException::class.java) { SettingsRestoreTransaction.run(changes) }
    assertEquals(mapOf("prefs" to "old prefs", "layout" to "old layout", "later" to "unchanged"), values)
  }

  @Test fun rollbackContinuesWhenOneUndoFailsAndKeepsOriginalError() {
    var restoredFirst = false
    val original = IOException("original")
    val thrown = assertThrows(IOException::class.java) { SettingsRestoreTransaction.run(listOf(
      SettingsRestoreTransaction.Change({}, { restoredFirst = true }),
      SettingsRestoreTransaction.Change({ throw original }, { throw IOException("undo failed") }),
    )) }
    assertSame(original, thrown); assertTrue(restoredFirst); assertEquals(1, thrown.suppressed.size)
  }
}

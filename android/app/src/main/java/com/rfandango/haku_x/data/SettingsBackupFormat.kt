package com.rfandango.haku_x.data

import com.rfandango.haku_x.overlay.ControlId
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream

/** A settings document, never a path-based app-data restore. Validate the entire input first. */
internal object SettingsBackupFormat {
  const val MAX_BYTES = 2 * 1024 * 1024
  const val FORMAT = "BoxForever-settings-backup"
  val files = setOf("overlay_layout.json", "experience-settings.json", "hud-settings.json", "controllers.json", "mod_choices.json")
  val stores = setOf("x1box_prefs", "per_game_settings", "library_ui")
  val profileName = Regex("[0-9a-f]{64}\\.json")
  private val booleans = setOf("uncap_fps", "async_shaders", "prebuild_shaders", "show_hitches", "show_fps",
    "video_480p", "video_720p", "video_1080i", "video_pal60", "updates_include_preview")
  private val choices = mapOf(
    "app_theme" to setOf("system", "light", "dark"), "aspect_ratio" to setOf("auto", "4:3", "16:9", "stretch"),
    "upscale_filter" to setOf("bilinear", "sgsr", "fsr"), "console_language" to setOf("auto", "en", "ja", "de", "fr", "es", "it", "ko", "zh", "pt"),
    "video_screen_format" to setOf("normal", "letterbox", "widescreen"), "video_av_pack" to setOf("hdtv", "standard", "svideo"),
    "console_network" to setOf("off", "lan", "kai"),
  )
  val appKeys = booleans + choices.keys + setOf("frame_rate_limit", "render_scale", "render_scale_fraction",
    "console_clock_offset", "network_lan_peers", "network_kai_engine") + PictureControl.entries.map { it.setting.key }

  data class Snapshot(val preferences: Map<String, Map<String, Any>>, val documents: Map<String, String?>,
    val language: String, val created: Long, val performance: Map<String, String> = emptyMap()) {
    val gameCount get(): Int {
      val games = preferences.getValue("per_game_settings").keys.mapTo(mutableSetOf()) { it.substringBeforeLast('/') }
      documents["experience-settings.json"]?.let { text ->
        JSONObject(text).optJSONObject("games")?.keys()?.asSequence()?.forEach { games += it }
      }
      return games.size
    }
  }

  fun portableKey(store: String, key: String): Boolean = when (store) {
    "x1box_prefs" -> key in appKeys
    "library_ui" -> key in setOf("covers", "cover_size")
    "per_game_settings" -> key.length <= 4096 && key.substringBeforeLast('/', "").isNotEmpty() &&
      GameSetting.entries.any { it.key == key.substringAfterLast('/') }
    else -> false
  }

  fun read(input: InputStream): Snapshot {
    val bytes = ByteArray(MAX_BYTES + 1)
    var size = 0
    while (size < bytes.size) { val count = input.read(bytes, size, bytes.size - size); if (count < 0) break; if (count == 0) continue; size += count }
    require(size <= MAX_BYTES) { "Settings backup is too large" }
    return decode(bytes.copyOf(size).toString(Charsets.UTF_8))
  }

  fun encode(snapshot: Snapshot): String {
    val prefs = JSONObject()
    snapshot.preferences.forEach { (name, values) -> prefs.put(name, JSONObject(values)) }
    val documents = JSONObject()
    snapshot.documents.forEach { (name, text) -> documents.put(name, text?.let(::JSONObject) ?: JSONObject.NULL) }
    val text = JSONObject().put("format", FORMAT).put("version", 1).put("created", snapshot.created)
      .put("language", snapshot.language).put("preferences", prefs).put("files", documents)
      .put("performance", JSONObject().apply { snapshot.performance.forEach { (name, data) -> put(name, JSONObject(data)) } }).toString(2)
    decode(text) // Our own export must be importable, too.
    return text
  }

  fun decode(text: String): Snapshot {
    require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
    // Bound nesting before JSONTokener recursion, including untrusted picker input.
    var quoted = false; var escaped = false; var depth = 0
    for (c in text) {
      if (quoted) {
        if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
      } else when (c) {
        '"' -> quoted = true
        '{', '[' -> { depth++; require(depth <= 24) }
        '}', ']' -> { depth--; require(depth >= 0) }
      }
    }
    require(depth == 0 && !quoted)
    val tokener = JSONTokener(text)
    val root = tokener.nextValue() as? JSONObject ?: error("Expected settings object")
    require(tokener.nextClean() == '\u0000')
    require(root.getString("format") == FORMAT && integer(root.get("version"), 1, 1) == 1)
    val language = root.getString("language"); require(language in setOf("system", "en", "ru", "es", "pt-BR"))
    val created = root.get("created"); require(created is Number && created.toLong() >= 0 && created.toDouble() == created.toLong().toDouble())
    val prefs = root.getJSONObject("preferences"); require(prefs.namesSet() == stores)
    val values = stores.associateWith { store ->
      val source = prefs.getJSONObject(store)
      require(source.length() <= 20000)
      source.namesSet().associateWith { key ->
        require(portableKey(store, key)) { "Not a portable setting: $key" }
        preference(store, key, source.get(key))
      }
    }
    val docs = root.getJSONObject("files"); require(docs.namesSet() == files)
    val documents = files.associateWith { name ->
      if (docs.isNull(name)) null else docs.getJSONObject(name).also { document(name, it) }.toString()
    }
    val profiles = root.getJSONObject("performance"); require(profiles.length() <= 1000)
    val performance = profiles.namesSet().associateWith { name ->
      require(profileName.matches(name))
      val profile = profiles.getJSONObject(name); tree(profile)
      require(profile.getString("device").matches(Regex("[0-9a-f]{64}")))
      val options = profile.getJSONObject("options")
      require(options.get("scale") is Number && options.getDouble("scale").toFloat() in RenderScale.choices)
      require(options.get("fxaa") is Boolean && options.getString("upscale") in setOf("bilinear", "sgsr", "fsr"))
      integer(options.get("fps"), 0, 240)
      profile.toString()
    }
    return Snapshot(values, documents, language, created.toLong(), performance)
  }

  private fun preference(store: String, key: String, value: Any): Any {
    if (store == "per_game_settings") {
      require(value is String && SettingsProfileStore.valid(GameSetting.entries.first { it.key == key.substringAfterLast('/') }, value))
      return value
    }
    if ((store == "library_ui" && key == "covers") || key in booleans) { require(value is Boolean); return value }
    if (key == "cover_size") return integer(value, 0, 2)
    if (key in choices) { require(value is String && value in choices.getValue(key)); return value }
    return when (key) {
      "frame_rate_limit" -> integer(value, 0, 240)
      "render_scale" -> integer(value, 1, 4)
      "render_scale_fraction" -> { require(value is Number && value.toFloat() in RenderScale.choices); value.toFloat() }
      "console_clock_offset" -> { require(value is Number && value.toDouble() == value.toLong().toDouble()); value.toLong() }
      "network_lan_peers", "network_kai_engine" -> { require(value is String && value.length <= 4096); value }
      else -> PictureControl.entries.first { it.setting.key == key }.let { integer(value, it.minimum, it.maximum) }
    }
  }

  private fun document(name: String, json: JSONObject) {
    tree(json)
    when (name) {
      "overlay_layout.json" -> {
        integer(json.get("version"), 1, 2)
        val controls = json.getJSONObject("controls")
        require(controls.length() <= ControlId.entries.size)
        for (key in controls.namesSet()) {
          require(ControlId.entries.any { it.name == key })
          val c = controls.getJSONObject(key)
          number(c.get("x"), 0.0, 1.0); number(c.get("y"), 0.0, 1.0)
          number(c.get("r"), .025 - 1e-7, .35 + 1e-7)
          number(c.get("o"), 0.0, 1.0); require(c.get("v") is Boolean)
        }
      }
      "experience-settings.json" -> {
        for (key in setOf("global", "games", "hotkeys")) if (json.has(key)) require(json.get(key) is JSONObject)
        fun options(o: JSONObject) { for (key in o.namesSet()) {
          val field = ExperienceSetting.entries.firstOrNull { it.key == key } ?: error("Unknown option")
          require(field.accepts(integer(o.get(key), field.minimum, field.maximum)))
        } }
        json.optJSONObject("global")?.let(::options)
        json.optJSONObject("games")?.let { games -> games.namesSet().forEach { options(games.getJSONObject(it)) } }
        json.optJSONObject("hotkeys")?.let { keys -> keys.namesSet().forEach {
          require(HotkeyAction.entries.any { action -> action.name == it })
          require(integer(keys.get(it), 0, 1000) in ExperienceStore.HOTKEY_KEYS + 0)
        } }
      }
      "hud-settings.json" -> {
        for (key in setOf("enabled", "cpu", "gpu", "battery", "speed")) if (json.has(key)) require(json.get(key) is Boolean)
        if (json.has("size")) integer(json.get("size"), 8, 18)
        if (json.has("background")) integer(json.get("background"), 0, 100)
        if (json.has("position")) require(HudPosition.entries.any { it.name == json.getString("position") })
      }
      "mod_choices.json" -> json.namesSet().forEach { require(json.get(it) is Boolean) }
      "controllers.json" -> {
        if (json.has("slots")) require(json.get("slots") is JSONArray)
        for (key in setOf("profiles", "names")) if (json.has(key)) require(json.get(key) is JSONObject)
        json.optJSONArray("slots")?.let { slots ->
          require(slots.length() <= 4)
          for (i in 0 until slots.length()) require(slots.getJSONObject(i).getString("mode") in setOf("auto", "device", "off"))
        }
        json.optJSONObject("profiles")?.let { profiles ->
          require(profiles.length() <= 256)
          for (key in profiles.namesSet()) {
            val p = profiles.getJSONObject(key)
            for (field in setOf("invertLX", "invertLY", "invertRX", "invertRY", "squareSticks")) if (p.has(field)) require(p.get(field) is Boolean)
            if (p.has("bind")) require(p.get("bind") is JSONObject)
            for (field in listOf("deadzoneL", "deadzoneR", "threshold", "rumbleStrength")) if (p.has(field)) number(p.get(field), 0.0, 1.0)
            for (field in listOf("sensitivityL", "sensitivityR")) if (p.has(field)) number(p.get(field), .25, 2.0)
            for (field in listOf("rangeL", "rangeR")) if (p.has(field)) number(p.get(field), .5, 1.5)
            p.optJSONObject("bind")?.let { bindings -> bindings.namesSet().forEach { input ->
              require(XInput.entries.any { it.name == input })
              val array = bindings.getJSONArray(input); require(array.length() <= 8)
              for (i in 0 until array.length()) when (val binding = Binding.decode(array.getString(i))) {
                is Binding.Key -> require(binding.code in 0..1000)
                is Binding.Axis -> require(binding.axis in 0..63)
                null -> error("Invalid binding")
              }
            } }
          }
        }
      }
    }
  }

  private fun JSONObject.namesSet() = keys().asSequence().toSet()
  private fun integer(value: Any, min: Int, max: Int): Int {
    require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble() && value.toInt() in min..max)
    return value.toInt()
  }
  private fun number(value: Any, min: Double, max: Double) {
    require(value is Number && value.toDouble().isFinite() && value.toDouble() in min..max)
  }
  private fun tree(value: Any, depth: Int = 0) {
    require(depth <= 20)
    when (value) {
      is JSONObject -> { require(value.length() <= 20000); value.namesSet().forEach { require(it.length <= 4096); tree(value.get(it), depth + 1) } }
      is JSONArray -> { require(value.length() <= 20000); for (i in 0 until value.length()) tree(value.get(i), depth + 1) }
      is String -> require(value.length <= 4096)
      is Number -> require(value.toDouble().isFinite())
      is Boolean -> Unit
      else -> require(value === JSONObject.NULL)
    }
  }
}

/** Roll back even a partially applied step; unrelated later settings are never touched. */
internal object SettingsRestoreTransaction {
  class Change(val apply: () -> Unit, val undo: () -> Unit)
  fun run(changes: List<Change>) {
    val started = mutableListOf<Change>()
    try { for (change in changes) { started += change; change.apply() } }
    catch (failure: Exception) {
      for (change in started.asReversed()) try { change.undo() } catch (rollback: Exception) { failure.addSuppressed(rollback) }
      throw failure
    }
  }
}

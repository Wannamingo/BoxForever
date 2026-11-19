package com.rfandango.haku_x.data

import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

/** User packs are separate from APK assets and their disposable extraction cache. */
object UserModPack {
  const val PREFIX = "user:"
  private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
  private const val MAX_TEXT = 256 * 1024
  private val KEY = Regex("[0-9A-Fa-f]{8}\\.[0-9]+")

  private fun directory(root: File, key: String): File {
    require(KEY.matches(key)) { "The game's title ID and version are required" }
    return File(root, key.uppercase(Locale.ROOT))
  }

  fun mods(root: File, key: String): List<ModInfo> {
    if (!KEY.matches(key)) return emptyList()
    return directory(root, key).listFiles().orEmpty().sortedBy { it.name }.mapNotNull { dir ->
      File(dir, "mod.txt").takeIf { it.isFile }?.let { ModCatalog.parseMod(PREFIX + dir.name, it.readText().removePrefix("\uFEFF")) }
    }
  }

  fun cheats(root: File, key: String): List<CheatInfo> {
    if (!KEY.matches(key)) return emptyList()
    return File(directory(root, key), "cheats.txt").takeIf { it.isFile }?.let { ModCatalog.parseCheats(it.readText().removePrefix("\uFEFF")) }.orEmpty()
  }

  fun files(root: File, key: String, id: String): List<Pair<String, File>> {
    val name = id.removePrefix(PREFIX)
    require(id.startsWith(PREFIX) && safeSegment(name))
    val dir = File(directory(root, key), "$name/files")
    return dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.map { it.relativeTo(dir).path to it }.toList()
  }

  /** A ZIP is either <key>/<mod>/mod.txt or <mod>/mod.txt, with optional cheats.txt. */
  fun install(root: File, key: String, input: InputStream) {
    val target = directory(root, key)
    root.mkdirs()
    val stage = File(root, ".import-${UUID.randomUUID()}").apply { mkdirs() }
    try {
      val unpacked = File(stage, "unpacked").apply { mkdirs() }
      var bytes = 0L
      var entries = 0
      val names = HashSet<String>()
      ZipInputStream(input.buffered()).use { zip ->
        while (true) {
          val entry = zip.nextEntry ?: break
          require(++entries <= 20_000) { "Too many mod files" }
          val path = entry.name.replace('\\', '/').trimEnd('/')
          val parts = path.split('/')
          require(parts.all(::safeSegment) && path.length <= 512) { "Invalid mod path" }
          require(names.add(path.lowercase(Locale.ROOT))) { "Duplicate mod path" }
          val dst = File(unpacked, parts.joinToString(File.separator))
          if (entry.isDirectory) { dst.mkdirs(); continue }
          dst.parentFile?.mkdirs()
          dst.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            var size = 0L
            while (true) {
              val n = zip.read(buffer)
              if (n < 0) break
              bytes += n
              size += n
              require(bytes <= MAX_BYTES) { "Mod files are too large" }
              if (dst.name == "mod.txt" || dst.name == "cheats.txt") require(size <= MAX_TEXT) { "Mod text is too large" }
              out.write(buffer, 0, n)
            }
          }
        }
      }
      val keyed = unpacked.listFiles().orEmpty().filter { KEY.matches(it.name) }
      val source = if (keyed.isEmpty()) unpacked else {
        require(keyed.size == 1 && keyed[0].name.equals(key, true) && unpacked.listFiles()!!.size == 1) {
          "This pack belongs to a different game or disc version"
        }
        keyed[0]
      }
      require(source.listFiles().orEmpty().isNotEmpty()) { "The pack is empty" }
      for (file in source.listFiles().orEmpty()) {
        if (file.name == "cheats.txt" && file.isFile) validateCheats(file.readText())
        else {
          require(file.isDirectory && File(file, "mod.txt").isFile) { "Expected <mod>/mod.txt and optional cheats.txt" }
          validateMod(file)
        }
      }
      val next = File(stage, "next")
      if (target.exists()) check(target.copyRecursively(next)) else next.mkdirs()
      for (file in source.listFiles().orEmpty()) {
        val dst = File(next, file.name)
        check(!dst.exists() || dst.deleteRecursively())
        check(file.copyRecursively(dst))
      }
      replace(target, next, File(stage, "previous"))
    } finally {
      // If rollback failed, retain the original directory for recovery.
      if (!(File(stage, "previous").exists() && !target.exists())) stage.deleteRecursively()
    }
  }

  /** A text import replaces only the user cheat list, leaving every mod in place. */
  fun installCheats(root: File, key: String, input: InputStream) {
    val text = input.use { stream ->
      val out = java.io.ByteArrayOutputStream()
      val buffer = ByteArray(8192)
      while (true) {
        val n = stream.read(buffer)
        if (n < 0) break
        require(out.size() + n <= MAX_TEXT) { "Cheat text is too large" }
        out.write(buffer, 0, n)
      }
      out.toByteArray()
    }
    val decoded = text.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    validateCheats(decoded)
    val target = directory(root, key).apply { mkdirs() }
    val tmp = File(target, ".cheats-${UUID.randomUUID()}")
    try {
      tmp.writeText(decoded)
      java.nio.file.Files.move(tmp.toPath(), File(target, "cheats.txt").toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    } finally { tmp.delete() }
  }

  private fun safeSegment(s: String) = s.isNotEmpty() && s != "." && s != ".." && s.length <= 128 &&
    !s.endsWith('.') && !s.endsWith(' ') && s.none { it.code < 32 || it in "<>:\"|?*" }

  private fun validateMod(dir: File) {
    val text = File(dir, "mod.txt").readText().removePrefix("\uFEFF")
    require(!text.contains('\u0000') && !text.contains('\t')) { "Invalid mod text" }
    val mod = ModCatalog.parseMod(dir.name, text)
    require(mod.refresh == 0 || mod.refresh in 20..240) { "Invalid refresh rate" }
    require(dir.listFiles().orEmpty().all { it.name == "mod.txt" || (it.name == "files" && it.isDirectory) }) {
      "Only mod.txt and files/ are allowed inside a mod"
    }
    require(mod.patches.isNotEmpty() || File(dir, "files").walkTopDown().any { it.isFile } || mod.refresh > 0) { "The mod is empty" }
    for ((xbe, patch) in mod.patches) {
      require(xbe.replace('\\', '/').split('/').all(::safeSegment)) { "Invalid XBE path" }
      require(Regex("(?:0[xX][0-9a-fA-F]+|[0-9]+)\\s*:\\s*(?:[0-9a-fA-F]{2}\\s*)+(?:->\\s*(?:[0-9a-fA-F]{2}\\s*)+)?").matches(patch)) { "Invalid XBE patch" }
    }
  }

  private fun validateCheats(text: String) {
    require(!text.contains('\u0000') && !text.contains('\t')) { "Invalid cheat text" }
    val cheats = ModCatalog.parseCheats(text.removePrefix("\uFEFF"))
    require(cheats.isNotEmpty() && cheats.map { it.name }.distinct().size == cheats.size) { "Expected unique [Cheat name] sections" }
    for (cheat in cheats) {
      require(cheat.name.isNotBlank() && cheat.code.isNotEmpty()) { "Empty cheat" }
      val branches = mutableListOf<Boolean>()
      for (line in cheat.code) {
        val op = line.substringBefore(' ')
        when (op) {
          "if8", "if16", "if32", "iff" -> { require(branches.size < 8); branches += false }
          "else" -> { require(branches.isNotEmpty() && !branches.last()); branches[branches.lastIndex] = true }
          "end" -> { require(branches.isNotEmpty()); branches.removeAt(branches.lastIndex) }
          "w8", "w16", "w32", "wf" -> require(line.count { it == ' ' } >= 2) { "Invalid cheat write" }
          else -> error("Unsupported cheat instruction: $op")
        }
      }
      require(branches.isEmpty()) { "Unclosed cheat condition" }
    }
  }

  private fun replace(target: File, next: File, old: File) {
    val hadOld = target.exists()
    if (hadOld) check(target.renameTo(old)) { "Cannot back up existing mods" }
    if (!next.renameTo(target)) {
      if (hadOld) check(old.renameTo(target)) { "Cannot restore existing mods: ${old.path}" }
      error("Cannot install mods")
    }
  }
}

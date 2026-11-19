package com.rfandango.haku_x.data.hdd

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID

/** Per-title UDATA and TDATA. Unrelated games and emulation states are never included. */
object GameDataArchive {
  const val MAX_BYTES = 2L * 1024 * 1024 * 1024
  private val areas = listOf("UDATA", "TDATA")

  private fun child(parent: File, name: String): File =
    parent.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: File(parent, name)

  private fun title(e: File, area: String, id: String) = child(child(e, area), id)

  fun hasData(e: File, id: String): Boolean = areas.any { title(e, it, id).isDirectory }

  fun write(out: OutputStream, e: File, id: String): Int {
    require(Regex("[0-9a-fA-F]{8}").matches(id))
    val picks = areas.mapNotNull { area ->
      title(e, area, id).takeIf { it.isDirectory }?.let { SaveArchive.Pick(it, area = area) }
    }
    require(picks.isNotEmpty()) { "This game has no saved data" }
    val files = picks.flatMap { it.title.walkTopDown().filter(File::isFile).toList() }
    require(files.size <= 40_000 && files.sumOf { it.length() } <= MAX_BYTES) { "Saved data exceeds the backup limit" }
    return SaveArchive.write(out, picks)
  }

  /**
   * Extract into a private staging tree, preserving saves absent from the archive. Only after the
   * complete zip (including CRCs) has been read do we swap the selected title's folders into E:.
   * prepare may re-sign foreign saves before they become visible to the emulator.
   */
  fun restore(archive: File, e: File, id: String,
    prepare: (File, SaveArchive.Result) -> Unit = { _, _ -> }): SaveArchive.Result {
    require(Regex("[0-9a-fA-F]{8}").matches(id))
    e.mkdirs()
    val stage = File(e.parentFile, ".game-data-${UUID.randomUUID()}").apply { mkdirs() }
    val next = File(stage, "next").apply { mkdirs() }
    val old = File(stage, "old").apply { mkdirs() }
    val installed = mutableListOf<File>()
    val moved = mutableListOf<Pair<File, File>>()
    var rollbackComplete = true
    try {
      for (area in areas) {
        val src = title(e, area, id)
        if (src.isDirectory) copy(src, File(next, "$area/${src.name}"))
      }
      val result = archive.inputStream().use { SaveArchive.extract(it, next, id, MAX_BYTES) }
      require(result.files > 0) { "The archive has no data for this game" }
      prepare(next, result)
      try {
        for (area in areas) {
          val src = title(next, area, id)
          if (!src.exists()) continue
          val dst = title(e, area, id)
          dst.parentFile?.mkdirs()
          if (dst.exists()) {
            val backup = File(old, area)
            check(dst.renameTo(backup)) { "Cannot back up $area/$id" }
            moved += dst to backup
          }
          check(src.renameTo(dst)) { "Cannot install $area/$id" }
          installed += dst
        }
      } catch (error: Exception) {
        installed.asReversed().forEach { if (!it.deleteRecursively()) rollbackComplete = false }
        moved.asReversed().forEach { (dst, backup) -> if (!backup.renameTo(dst)) rollbackComplete = false }
        if (!rollbackComplete) throw IllegalStateException("Restore failed; original data retained in ${old.path}", error)
        throw error
      }
      return SaveArchive.Result(result.written.map { File(e, it.relativeTo(next).path) }, result.skipped)
    } finally {
      if (rollbackComplete) stage.deleteRecursively()
    }
  }

  private fun copy(src: File, dst: File) {
    check(!Files.isSymbolicLink(src.toPath())) { "A saved-data path is a symbolic link" }
    if (src.isDirectory) {
      check(dst.mkdirs() || dst.isDirectory)
      for (file in src.listFiles().orEmpty()) copy(file, File(dst, file.name))
    } else src.copyTo(dst, overwrite = true)
    dst.setLastModified(src.lastModified())
  }
}

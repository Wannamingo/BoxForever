package com.rfandango.haku_x.data.hdd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class GameDataArchiveTest {
  private val id = "4d530004"
  private fun file(root: File, path: String, text: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }
  private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
    ZipOutputStream(out).use { zip -> entries.forEach { (name, text) ->
      zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
    } }
  }.toByteArray()
  private fun tree(root: File) = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readText() }

  @Test fun exportsBothAreasAndRestoresOnlySelectedTitle() {
    val tmp = Files.createTempDirectory("game-data").toFile()
    try {
      val e = File(tmp, "E")
      file(e, "UDATA/$id/first/data", "first")
      file(e, "UDATA/$id/second/data", "keep")
      file(e, "TDATA/$id/settings", "settings")
      file(e, "UDATA/5345000a/slot/data", "other game")
      val backup = ByteArrayOutputStream()
      assertEquals(3, GameDataArchive.write(backup, e, id))
      val plan = SaveArchive.plan(ByteArrayInputStream(backup.toByteArray()), e, id)
      assertEquals(setOf(id), plan.titles)
      assertEquals(3, plan.files)
      val imported = file(tmp, "import.zip", "")
      imported.writeBytes(zip("UDATA/$id/first/data" to "updated", "TDATA/$id/settings" to "new setting",
        "UDATA/5345000a/slot/data" to "must not import", "UDATA/$id/../escape" to "bad"))
      val result = GameDataArchive.restore(imported, e, id.uppercase())
      assertEquals(2, result.files)
      assertEquals("updated", File(e, "UDATA/$id/first/data").readText())
      assertEquals("keep", File(e, "UDATA/$id/second/data").readText())
      assertEquals("new setting", File(e, "TDATA/$id/settings").readText())
      assertEquals("other game", File(e, "UDATA/5345000a/slot/data").readText())
      assertFalse(File(e, "UDATA/escape").exists())
      assertTrue(result.written.all { it.isFile })
    } finally { tmp.deleteRecursively() }
  }

  @Test fun failedPreparationDoesNotAlterExistingData() {
    val tmp = Files.createTempDirectory("game-data-failure").toFile()
    try {
      val e = File(tmp, "E")
      file(e, "UDATA/$id/save/data", "original")
      file(e, "TDATA/$id/settings", "original settings")
      val before = tree(e)
      val archive = File(tmp, "import.zip").apply { writeBytes(zip("UDATA/$id/save/data" to "new")) }
      assertThrows(IllegalStateException::class.java) {
        GameDataArchive.restore(archive, e, id) { _, _ -> error("Re-signing failed") }
      }
      assertEquals(before, tree(e))
      assertEquals(listOf("E", "import.zip"), tmp.listFiles()!!.map { it.name }.sorted())
    } finally { tmp.deleteRecursively() }
  }

  @Test fun corruptArchiveAndWrongTitleLeaveSavesIntact() {
    val tmp = Files.createTempDirectory("game-data-corrupt").toFile()
    try {
      val e = File(tmp, "E")
      file(e, "UDATA/$id/save/data", "original")
      val before = tree(e)
      val valid = zip("UDATA/$id/save/data" to "updated save".repeat(500))
      val archive = File(tmp, "import.zip").apply { writeBytes(valid.copyOf(70)) }
      assertThrows(Exception::class.java) { GameDataArchive.restore(archive, e, id) }
      assertEquals(before, tree(e))
      archive.writeBytes(zip("UDATA/5345000a/save/data" to "wrong game"))
      assertEquals(0, archive.inputStream().use { SaveArchive.plan(it, e, id) }.files)
      assertThrows(IllegalArgumentException::class.java) { GameDataArchive.restore(archive, e, id) }
      assertEquals(before, tree(e))
    } finally { tmp.deleteRecursively() }
  }

  @Test fun decompressionLimitAlsoCoversSkippedTitles() {
    val tmp = Files.createTempDirectory("game-data-limit").toFile()
    try {
      val archive = zip("UDATA/5345000a/save/data" to "X".repeat(4096))
      assertThrows(IllegalArgumentException::class.java) {
        SaveArchive.plan(ByteArrayInputStream(archive), tmp, id, maxBytes = 512)
      }
    } finally { tmp.deleteRecursively() }
  }

  @Test fun failureInstallingSecondAreaRollsBackFirstArea() {
    val tmp = Files.createTempDirectory("game-data-rollback").toFile()
    try {
      val e = File(tmp, "E")
      file(e, "UDATA/$id/save/data", "original")
      // A damaged HDD layout makes installing TDATA fail after UDATA was swapped.
      file(e, "TDATA", "not a directory")
      val before = tree(e)
      val archive = File(tmp, "import.zip").apply { writeBytes(zip(
        "UDATA/$id/save/data" to "new", "TDATA/$id/settings" to "new settings")) }
      assertThrows(IllegalStateException::class.java) { GameDataArchive.restore(archive, e, id) }
      assertEquals(before, tree(e))
      assertFalse(tmp.listFiles()!!.any { it.name.startsWith(".game-data-") })
    } finally { tmp.deleteRecursively() }
  }
}

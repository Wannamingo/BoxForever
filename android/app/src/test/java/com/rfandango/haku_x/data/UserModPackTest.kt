package com.rfandango.haku_x.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class UserModPackTest {
  private val key = "4D530004.1"
  private val mod = "name: Widescreen\n[default.xbe]\n0x00010000: 00 00 -> 01 00\n"
  private val cheat = "[Infinite health]\nnote: Test\nif32 0x10000 != 0\nw32 [0x10000]+4 100\nelse\nw8 0x10000 0\nend\n"
  private fun pack(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { out ->
    ZipOutputStream(out).use { zip -> entries.forEach { (name, value) ->
      zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
    } }
  }.toByteArray()
  private fun install(root: File, vararg entries: Pair<String, String>) =
    UserModPack.install(root, key, ByteArrayInputStream(pack(*entries)))
  private fun tree(root: File) = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readText() }

  @Test fun importsKeyedPackAndKeepsReplacementFilesOutsideAssetCache() {
    val root = Files.createTempDirectory("user-mods").toFile()
    try {
      install(root, "$key/wide/mod.txt" to mod, "$key/wide/files/media/test.xpr" to "texture", "$key/cheats.txt" to cheat)
      val mods = UserModPack.mods(root, key.lowercase())
      assertEquals("user:wide", mods.single().id)
      assertEquals("Widescreen", mods.single().name)
      assertEquals("default.xbe", mods.single().patches.single().first)
      val replacements = UserModPack.files(root, key, mods.single().id)
      assertEquals("media/test.xpr", replacements.single().first.replace('\\', '/'))
      assertEquals("texture", replacements.single().second.readText())
      assertEquals("Infinite health", UserModPack.cheats(root, key).single().name)
    } finally { root.deleteRecursively() }
  }

  @Test fun textImportPreservesModsAndReplacesCheatsAtomically() {
    val root = Files.createTempDirectory("user-cheats").toFile()
    try {
      install(root, "wide/mod.txt" to mod)
      UserModPack.installCheats(root, key, ByteArrayInputStream(cheat.toByteArray()))
      UserModPack.installCheats(root, key, ByteArrayInputStream("[Ammo]\nonce\nw16 0x10002 99\n".toByteArray()))
      assertEquals("Ammo", UserModPack.cheats(root, key).single().name)
      assertTrue(UserModPack.cheats(root, key).single().once)
      assertEquals(1, UserModPack.mods(root, key).size)
      val before = tree(root)
      assertThrows(IllegalArgumentException::class.java) {
        UserModPack.installCheats(root, key, ByteArrayInputStream("[Broken]\nelse\n".toByteArray()))
      }
      assertEquals(before, tree(root))
    } finally { root.deleteRecursively() }
  }

  @Test fun rejectsWrongReleaseAndTraversalWithoutChangingInstalledPack() {
    val root = Files.createTempDirectory("user-mod-failure").toFile()
    try {
      install(root, "wide/mod.txt" to mod)
      val before = tree(root)
      for (entries in listOf(
        arrayOf("4D530004.2/wide/mod.txt" to mod),
        arrayOf("wide/mod.txt" to mod, "wide/files/../../escape" to "bad"),
        arrayOf("wide/mod.txt" to "name: Empty\n"),
        arrayOf("wide/mod.txt" to "[../default.xbe]\n0x10000: 01\n"),
        arrayOf("wide/mod.txt" to mod, "wide/files/image" to "a", "wide/files/IMAGE" to "b"),
      )) {
        assertThrows(Exception::class.java) { install(root, *entries) }
        assertEquals(before, tree(root))
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".import-") })
      }
    } finally { root.deleteRecursively() }
  }

  @Test fun updatingOneModKeepsOtherUserModsAndCheats() {
    val root = Files.createTempDirectory("user-mod-update").toFile()
    try {
      install(root, "wide/mod.txt" to mod, "fps/mod.txt" to "name: FPS\nrefresh: 120", "cheats.txt" to cheat)
      install(root, "wide/mod.txt" to mod.replace("Widescreen", "Updated"))
      assertEquals(listOf("FPS", "Updated"), UserModPack.mods(root, key).map { it.name })
      assertEquals(1, UserModPack.cheats(root, key).size)
    } finally { root.deleteRecursively() }
  }
}

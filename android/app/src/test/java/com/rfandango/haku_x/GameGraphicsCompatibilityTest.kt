package com.rfandango.haku_x

import org.junit.Assert.*
import org.junit.Test

class GameGraphicsCompatibilityTest {
  private val chip = GpuDriverPolicy.Chip.UNISOC_T820
  private val sha = GameGraphicsCompatibility.P77R1_ELF

  @Test fun qualifiedMwG57BinaryReceivesOnlyThePassSplitException() {
    assertEquals("-rop_pass_split", GameGraphicsCompatibility.quirks("4541007B.7", chip, sha, null))
  }

  @Test fun sandsAndSyberiaKeepTheirGraphicsProtections() {
    for (game in listOf("5553001D.0", "5553003B.0", "4D490006.0", "4541007B.6", "4541007B.8"))
      assertNull(game, GameGraphicsCompatibility.quirks(game, chip, sha, null))
  }

  @Test fun dimensityAndT618NeverLoseTheWorkaround() {
    for (family in listOf(null, GpuDriverPolicy.Chip.DIMENSITY_8300, GpuDriverPolicy.Chip.UNISOC_T618))
      assertNull(GameGraphicsCompatibility.quirks("4541007B.7", family, sha, null))
  }

  @Test fun systemOlderAndUnknownLibrariesKeepDefaultRendering() {
    for (library in listOf(null, "", "b05da0faba919d2310ed8f22af0fa6a7632d25d9fc69278659c554b76757dfa1",
      "71e520f9de773d23700b2a5e48e6960dbd588d791b3e6b0ef31f77b5d17330df", "unknown"))
      assertNull(GameGraphicsCompatibility.quirks("4541007B.7", chip, library, null))
  }

  @Test fun anExplicitCallerSpecIncludingAnEmptyOneWins() {
    for (flags in listOf("", "+rop_pass_split", "-no_push_desc", "+ds_same_load,-fresh_clear"))
      assertNull(GameGraphicsCompatibility.quirks("4541007B.7", chip, sha, flags))
  }

  @Test fun similarIdsAndUnidentifiedPathsAreNotOptedIn() {
    for (key in listOf("4541007B0.7", "4541007B.70", "path:4541007B.7", "NFS Most Wanted", "4541007B", ""))
      assertFalse(key, GameGraphicsCompatibility.candidate(key, chip))
  }

  @Test fun aRuntimeDriverOverrideNeverUsesTheUiSelectionAsItsIdentity() {
    assertNull(GameGraphicsCompatibility.quirks("4541007B.7", chip, sha, null, externalDriverOverride = true))
  }
}

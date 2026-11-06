package com.rfandango.haku_x

/** Qualified exceptions to conservative native defaults, scoped to exact tested inputs. */
object GameGraphicsCompatibility {
  const val P77R1_ELF = "53d01a562e504782154a5cddbcc55bcd079f2038260bca08ea6885d835562006"
  private const val MW_BLACK_EDITION = "4541007B.7"

  fun candidate(gameKey: String, chip: GpuDriverPolicy.Chip?): Boolean =
    gameKey == MW_BLACK_EDITION && chip == GpuDriverPolicy.Chip.UNISOC_T820

  /** Never override explicit caller flags or extend qualification to another binary/game/GPU. */
  fun quirks(gameKey: String, chip: GpuDriverPolicy.Chip?, elfSha256: String?, inherited: String?,
             externalDriverOverride: Boolean = false): String? =
    if (candidate(gameKey, chip) && elfSha256 == P77R1_ELF && inherited == null && !externalDriverOverride)
      "-rop_pass_split" else null
}

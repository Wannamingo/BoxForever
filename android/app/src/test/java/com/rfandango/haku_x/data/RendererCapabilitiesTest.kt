package com.rfandango.haku_x.data
import org.junit.Assert.*
import org.junit.Test

class RendererCapabilitiesTest {
  @Test fun dynamicBoundsAndTargetRemainValidWithImportedOrInheritedSettings() {
    val o=ExperienceOptions(mapOf(ExperienceSetting.DYNAMIC to 1,ExperienceSetting.DYNAMIC_MIN to 2,
      ExperienceSetting.DYNAMIC_MAX to 1,ExperienceSetting.DYNAMIC_TARGET to 44))
    assertEquals("dynamic=2,2,60",o.dynamicArgument())
    assertFalse(ExperienceSetting.DYNAMIC_TARGET.accepts(44))
    assertTrue(ExperienceSetting.DYNAMIC_TARGET.accepts(120))
    assertEquals("dynamic=0",ExperienceOptions().dynamicArgument())
  }
  @Test fun deviceLimitsRestrictChoices() {
    assertEquals(listOf(0),RendererCapabilities.bounded(1).anisotropyChoices)
    assertEquals(listOf(0,2,4),RendererCapabilities.bounded(4).anisotropyChoices)
    assertEquals(listOf(0,2,4,8,16),RendererCapabilities.bounded(16).anisotropyChoices)
    assertEquals(listOf(0),RendererCapabilities.bounded(-1).anisotropyChoices)
  }
  @Test fun fractionalScaleKeepsLegacyDynamicBounds() {
    val options = ExperienceOptions(mapOf(ExperienceSetting.DYNAMIC to 1, ExperienceSetting.DYNAMIC_MIN to 3,
      ExperienceSetting.DYNAMIC_MAX to 1))
    assertEquals("dynamic=1.5,1.5,60", options.dynamicArgument())
    assertEquals("2", RenderScale.format(RenderScale.parse("2")!!))
    assertEquals("2.5", RenderScale.format(RenderScale.parse("2.5")!!))
    assertNull(RenderScale.parse("2.25"))
    assertNull(RenderScale.parse("4.5"))
  }
  @Test fun importingUnknownAnisotropyNeverEnablesAnInvalidValue() {
    assertFalse(ExperienceSetting.ANISOTROPY.accepts(3))
    assertFalse(ExperienceSetting.ANISOTROPY.accepts(Int.MAX_VALUE))
    assertTrue(ExperienceSetting.ANISOTROPY.accepts(8))
    assertTrue(ExperienceSetting.ANISOTROPY.accepts(16))
    assertEquals(0,ExperienceOptions(mapOf(ExperienceSetting.ANISOTROPY to 3))[ExperienceSetting.ANISOTROPY])
  }
}

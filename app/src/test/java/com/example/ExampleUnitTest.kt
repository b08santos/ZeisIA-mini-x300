package com.example

import com.example.ai.FlagshipImageEnhancer
import com.example.ai.ZoomEnhancementPipeline
import com.example.domain.ZoomMath
import com.example.domain.ZoomMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

  @Test
  fun logarithmic_zoom_slider_roundtrip_1x_to_100x() {
    val testZooms = listOf(1f, 2f, 5f, 10f, 30f, 50f, 100f)
    for (z in testZooms) {
      val fraction = ZoomMath.zoomToSlider(z, minZoom = 1f, maxZoom = 100f)
      val reconstructed = ZoomMath.sliderToZoom(fraction, minZoom = 1f, maxZoom = 100f)
      assertEquals(z, reconstructed, 0.05f)
    }
  }

  @Test
  fun hybrid_zoom_splits_hardware_and_digital_crop() {
    val (hwLow, cropLow) = ZoomMath.splitZoom(targetZoom = 4f, hardwareMinZoom = 1f, hardwareMaxZoom = 10f)
    assertEquals(4f, hwLow, 0.001f)
    assertEquals(1f, cropLow, 0.001f)

    val (hwHigh, cropHigh) = ZoomMath.splitZoom(targetZoom = 100f, hardwareMinZoom = 1f, hardwareMaxZoom = 10f)
    assertEquals(10f, hwHigh, 0.001f)
    assertEquals(10f, cropHigh, 0.001f)
  }

  @Test
  fun zoom_mode_and_labels_format_correctly() {
    assertEquals(ZoomMode.OPTICAL, ZoomMath.classifyZoomMode(2.0f, hardwareMaxZoom = 8f))
    assertEquals(ZoomMode.HYBRID_AI, ZoomMath.classifyZoomMode(10.0f, hardwareMaxZoom = 8f))
    assertEquals(ZoomMode.SUPER_ZOOM, ZoomMath.classifyZoomMode(50.0f, hardwareMaxZoom = 8f))
    assertEquals("1x", ZoomMath.formatZoomLabel(1.0f))
    assertEquals("2.5x", ZoomMath.formatZoomLabel(2.5f))
    assertEquals("100x", ZoomMath.formatZoomLabel(100.0f))
  }

  @Test
  fun real_esrgan_triggers_on_10x_optical_and_digital_crops() {
    assertFalse(ZoomEnhancementPipeline.shouldApplyNeuralSr(fusedLong = 1536, totalZoom = 1.0f))
    assertTrue(ZoomEnhancementPipeline.shouldApplyNeuralSr(fusedLong = 1536, totalZoom = 2.0f))
    assertTrue(ZoomEnhancementPipeline.shouldApplyNeuralSr(fusedLong = 1536, totalZoom = 10.0f))
    assertTrue(ZoomEnhancementPipeline.shouldApplyNeuralSr(fusedLong = 320, totalZoom = 1.0f))
  }

  @Test
  fun filmic_tone_lut_prevents_washed_out_white_shift() {
    val lut = FlagshipImageEnhancer.buildFilmicToneLut(
      contrastPower = 1.15f,
      shadowDepth = 0.045f,
      highlightCompress = 0.035f,
    )
    assertEquals(0f, lut[0], 0.01f)
    // Dark shadows and bright highlights must stay anchored or slightly richer, never lifted white
    assertTrue(lut[40] < 40f)
    assertTrue(lut[215] <= 215f)
    // Monotonicity preserved across the full range
    for (i in 1 until lut.size) {
      assertTrue("LUT not monotonic at $i", lut[i] >= lut[i - 1])
    }
  }
}

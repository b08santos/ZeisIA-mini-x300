package com.example

import com.example.ai.CloudSuperEnhancer
import com.example.ai.FlagshipImageEnhancer
import com.example.ai.ZoomEnhancementPipeline
import com.example.data.UserSettings
import com.example.domain.CaptureMode
import com.example.domain.EnhancementPreset
import com.example.domain.LensProfile
import com.example.domain.PipelineTelemetry
import com.example.domain.ZoomMath
import com.example.domain.ZoomMode
import com.example.ui.CameraUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

  @Test
  fun default_settings_use_free_offline_pro_engine() {
    val defaults = UserSettings()
    assertFalse(defaults.cloudAiEnabled)
    assertTrue(defaults.multiFrameEnabled)
    assertTrue(defaults.autoSaveEnabled)
  }

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
    assertEquals(24, ZoomMath.toEquivalentFocalMm(1.0f))
    assertEquals("52mm", ZoomMath.formatFocalMmLabel(2.16f))
    assertEquals("240mm", ZoomMath.formatFocalMmLabel(10.0f))
  }

  @Test
  fun local_real_esrgan_triggers_on_zoom_or_small_digital_crops() {
    assertFalse(ZoomEnhancementPipeline.shouldApplyLocalNeuralSr(fusedLong = 1536, digitalCropFactor = 1.0f, totalZoom = 1.0f))
    assertFalse(ZoomEnhancementPipeline.shouldApplyLocalNeuralSr(fusedLong = 1536, digitalCropFactor = 1.0f, totalZoom = 2.0f))
    assertTrue(ZoomEnhancementPipeline.shouldApplyLocalNeuralSr(fusedLong = 1536, digitalCropFactor = 1.0f, totalZoom = 9.0f))
    assertTrue(ZoomEnhancementPipeline.shouldApplyLocalNeuralSr(fusedLong = 640, digitalCropFactor = 3.0f, totalZoom = 10.0f))
    assertTrue(ZoomEnhancementPipeline.shouldApplyLocalNeuralSr(fusedLong = 320, digitalCropFactor = 1.0f, totalZoom = 1.0f))
    assertEquals(0.88f, ZoomEnhancementPipeline.naturalTextureBlendRatio(fusedLong = 1536, digitalCropFactor = 1.0f, totalZoom = 4.0f), 0.001f)
    assertEquals(0.94f, ZoomEnhancementPipeline.naturalTextureBlendRatio(fusedLong = 1536, digitalCropFactor = 1.0f, totalZoom = 9.0f), 0.001f)
  }

  @Test
  fun filmic_tone_lut_prevents_washed_out_white_shift() {
    val lut = FlagshipImageEnhancer.buildFilmicToneLut(
      contrastPower = 1.15f,
      shadowDepth = 0.045f,
      highlightCompress = 0.035f,
    )
    assertEquals(0f, lut[0], 0.01f)
    assertTrue(lut[40] < 40f)
    assertTrue(lut[215] <= 215f)
    for (i in 1 until lut.size) {
      assertTrue("LUT not monotonic at $i", lut[i] >= lut[i - 1])
    }
  }

  @Test
  fun richardson_lucy_deconvolution_sharpens_edges_without_sky_noise() {
    val w = 12
    val h = 12
    val luma = FloatArray(w * h) { idx ->
      val x = idx % w
      when {
        x < 5 -> 60f
        x == 5 -> 120f
        else -> 200f
      }
    }
    val deconvolved = FlagshipImageEnhancer.deconvolveLumaRL(luma, w, h, iterations = 3, blendStrength = 0.8f)
    // Flat sky/left & right regions remain completely untouched
    assertEquals(60f, deconvolved[2 * w + 2], 0.01f)
    assertEquals(200f, deconvolved[2 * w + 9], 0.01f)
    // Edge transition remains bounded strictly in [0, 255]
    for (v in deconvolved) {
      assertTrue("Out of bounds value $v", v in 0f..255f)
    }
  }

  @Test
  fun video_mode_caps_zoom_to_hardware_limit_while_photo_allows_100x() {
    val photoState = CameraUiState(captureMode = CaptureMode.PHOTO, hardwareMaxZoom = 10f)
    assertEquals(100f, photoState.maxZoomForCurrentMode, 0.001f)

    val videoState = CameraUiState(captureMode = CaptureMode.VIDEO, hardwareMaxZoom = 10f)
    assertEquals(10f, videoState.maxZoomForCurrentMode, 0.001f)
  }

  @Test
  fun macro_tele_lens_profile_configures_macro_restoration_and_defaults() {
    val defaultState = CameraUiState()
    assertEquals(LensProfile.TELEPHOTO, defaultState.lensProfile)

    val telemetry = PipelineTelemetry(
      totalZoom = 3.7f,
      hardwareZoom = 3.7f,
      digitalCropFactor = 1f,
      framesFused = 4,
      neuralSrApplied = false,
      lensProfile = LensProfile.MACRO_TELE,
      acceleratorLabel = "XNNPACK",
      inputWidth = 1000,
      inputHeight = 1000,
      outputWidth = 1000,
      outputHeight = 1000,
      processingTimeMs = 120L,
    )
    assertEquals(LensProfile.MACRO_TELE, telemetry.lensProfile)

    val macroPrompt = CloudSuperEnhancer.buildRestorationPrompt(
      zoomRatio = 7.4f,
      preset = EnhancementPreset.ULTRA_SHARP,
      lensProfile = LensProfile.MACRO_TELE,
    )
    assertTrue(macroPrompt.contains("Telephoto Macro"))
    assertTrue(macroPrompt.contains("bokeh"))

    val telePrompt = CloudSuperEnhancer.buildRestorationPrompt(
      zoomRatio = 10f,
      preset = EnhancementPreset.NATURAL,
      lensProfile = LensProfile.TELEPHOTO,
    )
    assertTrue(telePrompt.contains("telephoto zoomed"))
    assertFalse(telePrompt.contains("Telephoto Macro"))
  }
}

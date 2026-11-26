package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.ai.FlagshipImageEnhancer
import com.example.ai.MultiFrameFusion
import com.example.domain.EnhancementPreset
import com.example.domain.ZoomMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Zoom IA", appName)
  }

  @Test
  fun `logarithmic zoom slider maps 1x to 100x roundtrip`() {
    val testZooms = listOf(1f, 2f, 5f, 10f, 30f, 50f, 100f)
    for (z in testZooms) {
      val fraction = ZoomMath.zoomToSlider(z, minZoom = 1f, maxZoom = 100f)
      val reconstructed = ZoomMath.sliderToZoom(fraction, minZoom = 1f, maxZoom = 100f)
      assertEquals(z, reconstructed, 0.05f)
    }
  }

  @Test
  fun `hybrid zoom splits hardware and digital crop correctly`() {
    val (hwLow, cropLow) = ZoomMath.splitZoom(targetZoom = 4f, hardwareMinZoom = 1f, hardwareMaxZoom = 10f)
    assertEquals(4f, hwLow, 0.001f)
    assertEquals(1f, cropLow, 0.001f)

    val (hwHigh, cropHigh) = ZoomMath.splitZoom(targetZoom = 100f, hardwareMinZoom = 1f, hardwareMaxZoom = 10f)
    assertEquals(10f, hwHigh, 0.001f)
    assertEquals(10f, cropHigh, 0.001f)
  }

  @Test
  fun `flagship image enhancer preserves dimensions and boosts edge contrast`() {
    val width = 64
    val height = 64
    val src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val v = if (x < width / 2) 70 else 170
        src.setPixel(x, y, Color.rgb(v, v, v))
      }
    }

    val enhanced = FlagshipImageEnhancer.enhance(
      source = src,
      zoomRatio = 10f,
      preset = EnhancementPreset.ULTRA_SHARP,
    )
    assertEquals(width, enhanced.width)
    assertEquals(height, enhanced.height)
    val leftLuma = Color.red(enhanced.getPixel(16, 32))
    val rightLuma = Color.red(enhanced.getPixel(48, 32))
    assertTrue("Expected clear edge contrast after enhancement", rightLuma - leftLuma >= 100)
  }

  @Test
  fun `multi frame fusion aligns slightly shifted frames`() {
    val w = 48
    val h = 48
    val frame1 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val frame2 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    for (y in 0 until h) {
      for (x in 0 until w) {
        val c1 = if (x in 18..28 && y in 18..28) Color.WHITE else Color.rgb(30, 40, 50)
        val c2 = if (x in 20..30 && y in 19..29) Color.WHITE else Color.rgb(30, 40, 50)
        frame1.setPixel(x, y, c1)
        frame2.setPixel(x, y, c2)
      }
    }

    val fused = MultiFrameFusion.fuseFrames(listOf(frame1, frame2))
    assertEquals(w, fused.width)
    assertEquals(h, fused.height)
    val center = Color.red(fused.getPixel(23, 23))
    assertTrue("Expected fused bright square center to stay bright", center > 220)
  }
}

package com.example.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import com.example.domain.CaptureResult
import com.example.domain.EnhancementPreset
import com.example.domain.PipelineTelemetry
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CapturedFrameBytes(
  val jpegBytes: ByteArray,
  val rotationDegrees: Int,
)

class ZoomEnhancementPipeline(
  private val superResolutionEngine: SuperResolutionEngine,
) {

  companion object {
    fun shouldApplyNeuralSr(fusedLong: Int, totalZoom: Float): Boolean {
      return fusedLong <= 512 || totalZoom >= 1.8f
    }
  }

  suspend fun warmUp(): String = superResolutionEngine.warmUp()

  /**
   * Decodes only the centered ROI from a high-res JPEG using BitmapRegionDecoder
   * so even 50MP+ sensors at 100x zoom never allocate a full-frame bitmap in memory.
   */
  suspend fun decodeCenterRoi(
    frame: CapturedFrameBytes,
    digitalCropFactor: Float,
    maxWorkingLongEdge: Int = 1536,
  ): Bitmap = withContext(Dispatchers.Default) {
    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(frame.jpegBytes, 0, frame.jpegBytes.size, boundsOpts)
    val rawW = boundsOpts.outWidth.coerceAtLeast(1)
    val rawH = boundsOpts.outHeight.coerceAtLeast(1)

    val safeFactor = digitalCropFactor.coerceAtLeast(1f)
    val roiW = (rawW / safeFactor).roundToInt().coerceIn(48, rawW)
    val roiH = (rawH / safeFactor).roundToInt().coerceIn(48, rawH)
    val left = ((rawW - roiW) / 2).coerceIn(0, rawW - roiW)
    val top = ((rawH - roiH) / 2).coerceIn(0, rawH - roiH)
    val rect = Rect(left, top, left + roiW, top + roiH)

    val roiLong = max(roiW, roiH)
    var sampleSize = 1
    while (roiLong / (sampleSize * 2) >= maxWorkingLongEdge) {
      sampleSize *= 2
    }

    val decodeOpts = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    val regionDecoder = BitmapRegionDecoder.newInstance(
      frame.jpegBytes,
      0,
      frame.jpegBytes.size,
      false,
    )
    val cropped = try {
      regionDecoder.decodeRegion(rect, decodeOpts)
    } finally {
      regionDecoder.recycle()
    }

    val rotated = if (frame.rotationDegrees != 0) {
      val matrix = Matrix().apply { postRotate(frame.rotationDegrees.toFloat()) }
      val out = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
      if (out !== cropped) cropped.recycle()
      out
    } else {
      cropped
    }

    val rotatedLong = max(rotated.width, rotated.height)
    if (rotatedLong > maxWorkingLongEdge) {
      val scale = maxWorkingLongEdge.toFloat() / rotatedLong
      val targetW = (rotated.width * scale).roundToInt().coerceAtLeast(1)
      val targetH = (rotated.height * scale).roundToInt().coerceAtLeast(1)
      val scaled = Bitmap.createScaledBitmap(rotated, targetW, targetH, true)
      if (scaled !== rotated) rotated.recycle()
      scaled
    } else {
      rotated
    }
  }

  suspend fun processBurst(
    frames: List<Bitmap>,
    totalZoom: Float,
    hardwareZoom: Float,
    digitalCropFactor: Float,
    preset: EnhancementPreset,
  ): CaptureResult = withContext(Dispatchers.Default) {
    val startNs = System.nanoTime()
    val refFrame = frames.first()
    val inputW = refFrame.width
    val inputH = refFrame.height

    val fused = if (frames.size > 1) {
      MultiFrameFusion.fuseFrames(frames)
    } else {
      refFrame
    }

    val fusedLong = max(fused.width, fused.height)
    // Apply Real-ESRGAN neural restoration for any zoom >= 1.8x (including 5x/10x telephoto)
    // as well as any small cropped ROI <= 512px.
    val shouldRunNeuralSr = shouldApplyNeuralSr(fusedLong, totalZoom)

    var neuralSrApplied = false
    val baseSrBitmap = if (shouldRunNeuralSr) {
      // 464px max long edge fits cleanly in a 3x4 (12-tile) grid and yields up to ~1856px 4x GAN output
      val maxSrInLong = 464
      val srInput = if (fusedLong > maxSrInLong) {
        val ratio = maxSrInLong.toFloat() / fusedLong
        Bitmap.createScaledBitmap(
          fused,
          (fused.width * ratio).roundToInt().coerceAtLeast(64),
          (fused.height * ratio).roundToInt().coerceAtLeast(64),
          true,
        )
      } else {
        fused
      }

      val (ganOutput, usedModel) = superResolutionEngine.upscale4x(srInput)
      neuralSrApplied = usedModel
      if (srInput !== fused && srInput !== refFrame) {
        srInput.recycle()
      }

      if (!usedModel) {
        ganOutput.recycle()
        fused
      } else if (totalZoom < 4.0f && fusedLong >= 900) {
        // At low-to-mid zoom (1.8x..3.9x), preserve subtle native optical texture alongside GAN strokes
        val blended = blendBitmaps(ganOutput, fused, ganWeight = 0.82f)
        ganOutput.recycle()
        blended
      } else {
        ganOutput
      }
    } else {
      fused
    }

    val originalCompare = if (
      refFrame.width == baseSrBitmap.width && refFrame.height == baseSrBitmap.height
    ) {
      refFrame.copy(Bitmap.Config.ARGB_8888, false)
    } else {
      Bitmap.createScaledBitmap(refFrame, baseSrBitmap.width, baseSrBitmap.height, true)
    }

    val enhanced = FlagshipImageEnhancer.enhance(
      source = baseSrBitmap,
      zoomRatio = totalZoom,
      preset = preset,
      neuralSrApplied = neuralSrApplied,
    )

    val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
    val telemetry = PipelineTelemetry(
      totalZoom = totalZoom,
      hardwareZoom = hardwareZoom,
      digitalCropFactor = digitalCropFactor,
      framesFused = frames.size,
      neuralSrApplied = neuralSrApplied,
      acceleratorLabel = if (neuralSrApplied) superResolutionEngine.activeAcceleratorLabel else "ISP",
      inputWidth = inputW,
      inputHeight = inputH,
      outputWidth = enhanced.width,
      outputHeight = enhanced.height,
      processingTimeMs = elapsedMs,
    )

    CaptureResult(
      originalCropBitmap = originalCompare,
      baseSrBitmap = baseSrBitmap,
      enhancedBitmap = enhanced,
      savedUri = null,
      telemetry = telemetry,
      preset = preset,
    )
  }

  suspend fun reEnhanceWithPreset(
    current: CaptureResult,
    newPreset: EnhancementPreset,
  ): CaptureResult = withContext(Dispatchers.Default) {
    val startNs = System.nanoTime()
    val enhanced = FlagshipImageEnhancer.enhance(
      source = current.baseSrBitmap,
      zoomRatio = current.telemetry.totalZoom,
      preset = newPreset,
      neuralSrApplied = current.telemetry.neuralSrApplied,
    )
    val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
    current.copy(
      enhancedBitmap = enhanced,
      preset = newPreset,
      telemetry = current.telemetry.copy(processingTimeMs = elapsedMs),
    )
  }

  private fun blendBitmaps(primary: Bitmap, secondary: Bitmap, ganWeight: Float): Bitmap {
    val w = primary.width
    val h = primary.height
    val scaledSecondary = if (secondary.width == w && secondary.height == h) {
      secondary
    } else {
      Bitmap.createScaledBitmap(secondary, w, h, true)
    }
    val count = w * h
    val pPixels = IntArray(count)
    val sPixels = IntArray(count)
    primary.getPixels(pPixels, 0, w, 0, 0, w, h)
    scaledSecondary.getPixels(sPixels, 0, w, 0, 0, w, h)
    if (scaledSecondary !== secondary) scaledSecondary.recycle()

    val w1 = ganWeight.coerceIn(0f, 1f)
    val w2 = 1f - w1
    val out = IntArray(count)
    for (i in 0 until count) {
      val c1 = pPixels[i]
      val c2 = sPixels[i]
      val r = (((c1 ushr 16) and 0xFF) * w1 + ((c2 ushr 16) and 0xFF) * w2 + 0.5f).toInt().coerceIn(0, 255)
      val g = (((c1 ushr 8) and 0xFF) * w1 + ((c2 ushr 8) and 0xFF) * w2 + 0.5f).toInt().coerceIn(0, 255)
      val b = ((c1 and 0xFF) * w1 + (c2 and 0xFF) * w2 + 0.5f).toInt().coerceIn(0, 255)
      out[i] = Color.rgb(r, g, b)
    }
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
  }
}

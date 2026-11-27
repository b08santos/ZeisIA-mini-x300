package com.example.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import com.example.domain.CaptureResult
import com.example.domain.EnhancementPreset
import com.example.domain.LensProfile
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
    /**
     * Local 4x Real-ESRGAN-x4plus runs when:
     * - the cropped sensor ROI is <= 520px, OR
     * - digital crop >= 2.0x with <= 840px, OR
     * - totalZoom >= 3.5x (phones without optical telephoto like Galaxy Z Flip6 report
     *   digital crop inside CameraX hardwareZoom up to 10x, so digitalCropFactor stays 1.0).
     */
    fun shouldApplyLocalNeuralSr(
      fusedLong: Int,
      digitalCropFactor: Float,
      totalZoom: Float = 1f,
    ): Boolean {
      return fusedLong <= 520 ||
        (digitalCropFactor >= 2.0f && fusedLong <= 840) ||
        totalZoom >= 3.5f
    }

    /**
     * Blends Real-ESRGAN-x4plus output with the scaled native optical crop on mid-res
     * hybrid shots to retain natural filmic micro-grain alongside neural line restoration.
     */
    fun naturalTextureBlendRatio(
      fusedLong: Int,
      digitalCropFactor: Float,
      totalZoom: Float = 1f,
    ): Float {
      return if (totalZoom >= 6f || digitalCropFactor >= 4.5f || fusedLong <= 360) 0.86f else 0.74f
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
    maxWorkingLongEdge: Int = 2048,
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
    useCloudAi: Boolean,
    lensProfile: LensProfile = LensProfile.TELEPHOTO,
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

    if (useCloudAi && totalZoom >= 1.5f) {
      val cloudBitmap = CloudSuperEnhancer.enhancePhoto(
        source = fused,
        zoomRatio = totalZoom,
        preset = preset,
        lensProfile = lensProfile,
      )
      val originalCompare = if (
        refFrame.width == cloudBitmap.width && refFrame.height == cloudBitmap.height
      ) {
        refFrame.copy(Bitmap.Config.ARGB_8888, false)
      } else {
        Bitmap.createScaledBitmap(refFrame, cloudBitmap.width, cloudBitmap.height, true)
      }
      val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
      return@withContext CaptureResult(
        originalCropBitmap = originalCompare,
        baseSrBitmap = fused,
        enhancedBitmap = cloudBitmap,
        savedUri = null,
        telemetry = PipelineTelemetry(
          totalZoom = totalZoom,
          hardwareZoom = hardwareZoom,
          digitalCropFactor = digitalCropFactor,
          framesFused = frames.size,
          neuralSrApplied = true,
          cloudEnhanced = true,
          lensProfile = lensProfile,
          acceleratorLabel = "Nuvem",
          inputWidth = inputW,
          inputHeight = inputH,
          outputWidth = cloudBitmap.width,
          outputHeight = cloudBitmap.height,
          processingTimeMs = elapsedMs,
        ),
        preset = preset,
      )
    }

    // 100% Free Pro Offline Path:
    // - 1x..optical/mid-hybrid: full 2048px sensor pixels + multi-frame fusion + Richardson-Lucy deconvolution
    // - Extreme digital zoom (or low-res crop): 16.7M Real-ESRGAN-x4plus + natural micro-texture fusion
    val fusedLong = max(fused.width, fused.height)
    val shouldRunLocalSr = shouldApplyLocalNeuralSr(fusedLong, digitalCropFactor, totalZoom)

    var neuralSrApplied = false
    val baseSrBitmap = if (shouldRunLocalSr) {
      val srTargetLong = if (totalZoom >= 8f) 400 else 464
      val srInput = if (fusedLong > srTargetLong) {
        val ratio = srTargetLong.toFloat() / fusedLong
        Bitmap.createScaledBitmap(
          fused,
          (fused.width * ratio).roundToInt().coerceAtLeast(64),
          (fused.height * ratio).roundToInt().coerceAtLeast(64),
          true,
        )
      } else {
        fused
      }
      val (upscaled, usedModel) = superResolutionEngine.upscale4x(srInput)
      neuralSrApplied = usedModel
      if (srInput !== fused && srInput !== refFrame) {
        srInput.recycle()
      }
      if (usedModel) {
        val srWeight = naturalTextureBlendRatio(fusedLong, digitalCropFactor, totalZoom)
        val nativeUpscaled = Bitmap.createScaledBitmap(fused, upscaled.width, upscaled.height, true)
        val blended = blendBitmaps(upscaled, nativeUpscaled, srWeight)
        upscaled.recycle()
        nativeUpscaled.recycle()
        blended
      } else {
        upscaled
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
      lensProfile = lensProfile,
    )

    val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
    val telemetry = PipelineTelemetry(
      totalZoom = totalZoom,
      hardwareZoom = hardwareZoom,
      digitalCropFactor = digitalCropFactor,
      framesFused = frames.size,
      neuralSrApplied = neuralSrApplied,
      cloudEnhanced = false,
      lensProfile = lensProfile,
      acceleratorLabel = if (neuralSrApplied) superResolutionEngine.activeAcceleratorLabel else "Óptico Pro",
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

  private fun blendBitmaps(primary: Bitmap, secondary: Bitmap, primaryWeight: Float): Bitmap {
    val w = primary.width
    val h = primary.height
    val count = w * h
    val p1 = IntArray(count)
    val p2 = IntArray(count)
    primary.getPixels(p1, 0, w, 0, 0, w, h)
    secondary.getPixels(p2, 0, w, 0, 0, w, h)

    val w1 = primaryWeight.coerceIn(0f, 1f)
    val w2 = 1f - w1
    val out = IntArray(count)
    for (i in 0 until count) {
      val a = p1[i]
      val b = p2[i]
      val r = (((a ushr 16) and 0xFF) * w1 + ((b ushr 16) and 0xFF) * w2 + 0.5f).toInt().coerceIn(0, 255)
      val g = (((a ushr 8) and 0xFF) * w1 + ((b ushr 8) and 0xFF) * w2 + 0.5f).toInt().coerceIn(0, 255)
      val bl = ((a and 0xFF) * w1 + (b and 0xFF) * w2 + 0.5f).toInt().coerceIn(0, 255)
      out[i] = Color.rgb(r, g, bl)
    }
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
  }

  suspend fun reEnhanceWithPreset(
    current: CaptureResult,
    newPreset: EnhancementPreset,
    useCloudAi: Boolean,
  ): CaptureResult = withContext(Dispatchers.Default) {
    val startNs = System.nanoTime()
    if (useCloudAi && current.telemetry.totalZoom >= 1.5f) {
      val cloudBitmap = CloudSuperEnhancer.enhancePhoto(
        source = current.baseSrBitmap,
        zoomRatio = current.telemetry.totalZoom,
        preset = newPreset,
        lensProfile = current.telemetry.lensProfile,
      )
      val originalResized = if (
        current.originalCropBitmap.width == cloudBitmap.width &&
          current.originalCropBitmap.height == cloudBitmap.height
      ) {
        current.originalCropBitmap
      } else {
        Bitmap.createScaledBitmap(
          current.originalCropBitmap,
          cloudBitmap.width,
          cloudBitmap.height,
          true,
        )
      }
      val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
      return@withContext current.copy(
        originalCropBitmap = originalResized,
        enhancedBitmap = cloudBitmap,
        preset = newPreset,
        telemetry = current.telemetry.copy(
          cloudEnhanced = true,
          neuralSrApplied = true,
          acceleratorLabel = "Nuvem",
          outputWidth = cloudBitmap.width,
          outputHeight = cloudBitmap.height,
          processingTimeMs = elapsedMs,
        ),
      )
    }

    val enhanced = FlagshipImageEnhancer.enhance(
      source = current.baseSrBitmap,
      zoomRatio = current.telemetry.totalZoom,
      preset = newPreset,
      neuralSrApplied = current.telemetry.neuralSrApplied && !current.telemetry.cloudEnhanced,
      lensProfile = current.telemetry.lensProfile,
    )
    val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
    current.copy(
      enhancedBitmap = enhanced,
      preset = newPreset,
      telemetry = current.telemetry.copy(
        cloudEnhanced = false,
        processingTimeMs = elapsedMs,
      ),
    )
  }
}

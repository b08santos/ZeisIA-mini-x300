package com.example.domain

import android.graphics.Bitmap
import android.net.Uri
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt

enum class EnhancementPreset(val labelResName: String) {
  NATURAL("Natural"),
  ULTRA_SHARP("Ultra Nítido"),
  TEXT_DETAIL("Texto & Bordas"),
}

enum class ZoomMode {
  OPTICAL,
  HYBRID_AI,
  SUPER_ZOOM,
}

enum class FlashModeOption {
  AUTO,
  ON,
  OFF,
}

enum class CaptureMode {
  PHOTO,
  VIDEO,
}

data class PipelineTelemetry(
  val totalZoom: Float,
  val hardwareZoom: Float,
  val digitalCropFactor: Float,
  val framesFused: Int,
  val neuralSrApplied: Boolean,
  val cloudEnhanced: Boolean = false,
  val acceleratorLabel: String,
  val inputWidth: Int,
  val inputHeight: Int,
  val outputWidth: Int,
  val outputHeight: Int,
  val processingTimeMs: Long,
)

data class CaptureResult(
  val originalCropBitmap: Bitmap,
  val baseSrBitmap: Bitmap,
  val enhancedBitmap: Bitmap,
  val savedUri: Uri?,
  val telemetry: PipelineTelemetry,
  val preset: EnhancementPreset,
)

data class SavedPhotoItem(
  val uri: Uri,
  val displayName: String,
  val dateAddedMillis: Long,
  val width: Int,
  val height: Int,
  val isVideo: Boolean = false,
  val durationMs: Long = 0L,
)

object ZoomMath {
  const val MAX_TOTAL_ZOOM = 100f

  /**
   * Maps a normalized [0f, 1f] slider value onto a perceptual logarithmic zoom curve
   * so low-to-mid zooms (1x..10x) have fine tactile resolution while still reaching 100x.
   */
  fun sliderToZoom(fraction: Float, minZoom: Float = 1f, maxZoom: Float = MAX_TOTAL_ZOOM): Float {
    val clampedMin = minZoom.coerceAtLeast(0.5f)
    val clampedMax = maxZoom.coerceAtLeast(clampedMin + 1f)
    val f = fraction.coerceIn(0f, 1f)
    val logMin = ln(clampedMin.toDouble())
    val logMax = ln(clampedMax.toDouble())
    return exp(logMin + f * (logMax - logMin)).toFloat().coerceIn(clampedMin, clampedMax)
  }

  fun zoomToSlider(zoom: Float, minZoom: Float = 1f, maxZoom: Float = MAX_TOTAL_ZOOM): Float {
    val clampedMin = minZoom.coerceAtLeast(0.5f)
    val clampedMax = maxZoom.coerceAtLeast(clampedMin + 1f)
    val z = zoom.coerceIn(clampedMin, clampedMax)
    val logMin = ln(clampedMin.toDouble())
    val logMax = ln(clampedMax.toDouble())
    if (logMax <= logMin) return 0f
    return ((ln(z.toDouble()) - logMin) / (logMax - logMin)).toFloat().coerceIn(0f, 1f)
  }

  /**
   * Splits desired total zoom into:
   * - hardwareZoom: passed directly to CameraX CameraControl.setZoomRatio()
   * - digitalCropFactor: extra center-crop magnification applied on capture + viewfinder
   */
  fun splitZoom(targetZoom: Float, hardwareMinZoom: Float, hardwareMaxZoom: Float): Pair<Float, Float> {
    val safeTarget = targetZoom.coerceIn(hardwareMinZoom, MAX_TOTAL_ZOOM)
    val hwMax = hardwareMaxZoom.coerceAtLeast(1f)
    return if (safeTarget <= hwMax) {
      safeTarget to 1f
    } else {
      hwMax to (safeTarget / hwMax).coerceAtLeast(1f)
    }
  }

  fun classifyZoomMode(totalZoom: Float, hardwareMaxZoom: Float): ZoomMode {
    return when {
      totalZoom <= hardwareMaxZoom.coerceAtMost(5f) + 0.05f -> ZoomMode.OPTICAL
      totalZoom < 20f -> ZoomMode.HYBRID_AI
      else -> ZoomMode.SUPER_ZOOM
    }
  }

  fun formatZoomLabel(zoom: Float): String {
    return when {
      zoom < 10f -> {
        val tenths = (zoom * 10f).roundToInt()
        if (tenths % 10 == 0) "${tenths / 10}x" else "${tenths / 10}.${tenths % 10}x"
      }
      else -> "${zoom.roundToInt()}x"
    }
  }

  fun toEquivalentFocalMm(zoom: Float): Int {
    return (zoom.coerceAtLeast(0.5f) * 24f).roundToInt()
  }

  fun formatFocalMmLabel(zoom: Float): String {
    return "${toEquivalentFocalMm(zoom)}mm"
  }
}

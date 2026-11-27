package com.example.ai

import android.graphics.Bitmap
import android.graphics.Color
import com.example.domain.EnhancementPreset
import com.example.domain.LensProfile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Flagship-grade computational ISP refinement stage tuned for photorealistic clarity,
 * natural exposure, and crisp edge recovery without cartoon artifacts or sky halos:
 * 1. LUT-accelerated edge-preserving luma denoise
 * 2. Ringing-free Richardson-Lucy optical deconvolution (reverses lens/crop diffraction blur)
 * 3. Halo-free Difference-of-Gaussians (DoG) midtone micro-contrast
 * 4. Contrast Adaptive Sharpening (CAS) with strict local overshoot clamping
 * 5. Filmic black-point & highlight-safe S-curve LUT + balanced color vibrance
 */
object FlagshipImageEnhancer {

  fun enhance(
    source: Bitmap,
    zoomRatio: Float,
    preset: EnhancementPreset,
    neuralSrApplied: Boolean = false,
    lensProfile: LensProfile = LensProfile.TELEPHOTO,
  ): Bitmap {
    val width = source.width
    val height = source.height
    val count = width * height
    if (width < 8 || height < 8) return source

    val pixels = IntArray(count)
    source.getPixels(pixels, 0, width, 0, 0, width, height)

    val yPlane = FloatArray(count)
    val cbPlane = FloatArray(count)
    val crPlane = FloatArray(count)

    for (i in 0 until count) {
      val p = pixels[i]
      val r = ((p ushr 16) and 0xFF).toFloat()
      val g = ((p ushr 8) and 0xFF).toFloat()
      val b = (p and 0xFF).toFloat()
      yPlane[i] = 0.299f * r + 0.587f * g + 0.114f * b
      cbPlane[i] = -0.168736f * r - 0.331264f * g + 0.5f * b
      crPlane[i] = 0.5f * r - 0.418688f * g - 0.081312f * b
    }

    val zoomFactor = ((zoomRatio - 1f) / 25f).coerceIn(0f, 1f)

    // 1. Light edge-preserving denoise on raw sensor shots; preserve full detail after Real-ESRGAN-x4plus
    val cleanY = if (neuralSrApplied) {
      yPlane
    } else {
      val denoiseStrength = when (preset) {
        EnhancementPreset.NATURAL -> 0.28f + 0.16f * zoomFactor
        EnhancementPreset.ULTRA_SHARP -> 0.34f + 0.18f * zoomFactor
        EnhancementPreset.TEXT_DETAIL -> 0.40f + 0.20f * zoomFactor
      }
      bilateralLuma3x3Fast(yPlane, width, height, sigmaRange = 10f + 8f * denoiseStrength)
    }

    // Gentle chroma smoothing suppresses color noise blotches without smearing luma lines
    val cleanCb = boxBlur3x3(cbPlane, width, height)
    val cleanCr = boxBlur3x3(crPlane, width, height)

    // 2. Richardson-Lucy Optical Deconvolution: recovers real lens & telephoto micro-detail
    val deconvolvedY = if (neuralSrApplied) {
      cleanY
    } else {
      val rlIterations = when (preset) {
        EnhancementPreset.NATURAL -> 2
        EnhancementPreset.ULTRA_SHARP -> 3
        EnhancementPreset.TEXT_DETAIL -> 3
      }
      val rlBlend = when (preset) {
        EnhancementPreset.NATURAL -> 0.52f + 0.15f * zoomFactor
        EnhancementPreset.ULTRA_SHARP -> 0.72f + 0.18f * zoomFactor
        EnhancementPreset.TEXT_DETAIL -> 0.88f
      }
      deconvolveLumaRL(
        src = cleanY,
        width = width,
        height = height,
        iterations = rlIterations,
        blendStrength = rlBlend.coerceIn(0f, 0.95f),
      )
    }

    // 3. Halo-free micro-contrast for architectural, brick, foliage & macro micro-texture depth
    val macroBoost = if (lensProfile == LensProfile.MACRO_TELE) 0.10f else 0f
    val microContrastStrength = (when (preset) {
      EnhancementPreset.NATURAL -> 0.16f
      EnhancementPreset.ULTRA_SHARP -> 0.28f + 0.08f * zoomFactor
      EnhancementPreset.TEXT_DETAIL -> 0.42f
    } + macroBoost).coerceAtMost(0.55f)
    val texturedY = microContrastDetail(deconvolvedY, width, height, strength = microContrastStrength)

    // 4. Contrast Adaptive Sharpening (CAS) with strict local overshoot clamping
    val casMacroBoost = if (lensProfile == LensProfile.MACRO_TELE) 0.08f else 0f
    val casStrength = (when (preset) {
      EnhancementPreset.NATURAL -> if (neuralSrApplied) 0.25f else 0.36f
      EnhancementPreset.ULTRA_SHARP -> if (neuralSrApplied) 0.48f else 0.62f
      EnhancementPreset.TEXT_DETAIL -> if (neuralSrApplied) 0.70f else 0.84f
    } + casMacroBoost).coerceAtMost(0.92f)
    val sharpY = contrastAdaptiveSharpen(texturedY, width, height, strength = casStrength)

    // 5. Filmic black-point & highlight-safe S-curve LUT + balanced vibrance
    val contrastPower = when (preset) {
      EnhancementPreset.NATURAL -> 1.07f
      EnhancementPreset.ULTRA_SHARP -> 1.14f
      EnhancementPreset.TEXT_DETAIL -> 1.22f
    }
    val shadowDepth = when (preset) {
      EnhancementPreset.NATURAL -> 0.02f
      EnhancementPreset.ULTRA_SHARP -> 0.04f
      EnhancementPreset.TEXT_DETAIL -> 0.055f
    }
    val highlightCompress = when (preset) {
      EnhancementPreset.NATURAL -> 0.02f
      EnhancementPreset.ULTRA_SHARP -> 0.035f
      EnhancementPreset.TEXT_DETAIL -> 0.03f
    }
    val toneLut = buildFilmicToneLut(contrastPower, shadowDepth, highlightCompress)

    val vibranceBoost = when (preset) {
      EnhancementPreset.NATURAL -> 1.05f
      EnhancementPreset.ULTRA_SHARP -> 1.12f
      EnhancementPreset.TEXT_DETAIL -> 1.03f
    }

    val outPixels = IntArray(count)
    for (i in 0 until count) {
      val lumaInt = sharpY[i].toInt().coerceIn(0, 255)
      val frac = (sharpY[i] - lumaInt).coerceIn(0f, 1f)
      val nextIdx = min(255, lumaInt + 1)
      val curvedY = (toneLut[lumaInt] * (1f - frac) + toneLut[nextIdx] * frac)

      // Keep chroma noise damped in deep shadows/extreme highlights
      val normY = curvedY / 255f
      val chromaDamp = 0.86f + 0.14f * (1f - abs(normY - 0.5f) * 2f)
      val cb = cleanCb[i] * vibranceBoost * chromaDamp
      val cr = cleanCr[i] * vibranceBoost * chromaDamp

      val r = (curvedY + 1.402f * cr + 0.5f).toInt().coerceIn(0, 255)
      val g = (curvedY - 0.344136f * cb - 0.714136f * cr + 0.5f).toInt().coerceIn(0, 255)
      val b = (curvedY + 1.772f * cb + 0.5f).toInt().coerceIn(0, 255)
      outPixels[i] = Color.rgb(r, g, b)
    }

    return Bitmap.createBitmap(outPixels, width, height, Bitmap.Config.ARGB_8888)
  }

  /**
   * Fast separable Richardson-Lucy optical deconvolution with anti-ringing clamping:
   * mathematically reverses camera PSF blur while protecting flat skies from noise amplification.
   */
  internal fun deconvolveLumaRL(
    src: FloatArray,
    width: Int,
    height: Int,
    iterations: Int = 2,
    blendStrength: Float = 0.75f,
  ): FloatArray {
    if (width < 5 || height < 5 || iterations <= 0 || blendStrength <= 0.01f) return src
    val count = src.size
    var estimate = src.copyOf()
    val reblurred = FloatArray(count)
    val ratio = FloatArray(count)
    val correction = FloatArray(count)
    val scratch = FloatArray(count)

    repeat(iterations) {
      convolveGaussian3x3(estimate, reblurred, scratch, width, height)
      for (i in 0 until count) {
        val obs = src[i].coerceAtLeast(1f)
        val estBlur = reblurred[i].coerceAtLeast(1f)
        ratio[i] = (obs / estBlur).coerceIn(0.65f, 1.50f)
      }
      convolveGaussian3x3(ratio, correction, scratch, width, height)
      for (i in 0 until count) {
        estimate[i] = (estimate[i] * correction[i]).coerceIn(0f, 255f)
      }
    }

    // Anti-ringing overshoot clamp + flat-area sky protection
    val out = src.copyOf()
    for (y in 1 until height - 1) {
      val rPrev = (y - 1) * width
      val rCurr = y * width
      val rNext = (y + 1) * width
      for (x in 1 until width - 1) {
        val idx = rCurr + x
        val orig = src[idx]
        val n0 = src[rPrev + x]
        val n1 = src[rCurr + x - 1]
        val n2 = src[rCurr + x + 1]
        val n3 = src[rNext + x]
        val localMin = min(orig, min(min(n0, n1), min(n2, n3)))
        val localMax = max(orig, max(max(n0, n1), max(n2, n3)))
        val localRange = localMax - localMin

        // Skip flat sky/wall regions to avoid noise amplification
        if (localRange < 3.5f) continue

        val margin = (localRange * 0.22f).coerceIn(1.5f, 10f)
        val clampedEst = estimate[idx].coerceIn(
          (localMin - margin).coerceAtLeast(0f),
          (localMax + margin).coerceAtMost(255f),
        )
        out[idx] = orig * (1f - blendStrength) + clampedEst * blendStrength
      }
    }
    return out
  }

  private fun convolveGaussian3x3(
    src: FloatArray,
    dst: FloatArray,
    scratch: FloatArray,
    width: Int,
    height: Int,
  ) {
    // Horizontal [0.25, 0.5, 0.25]
    for (y in 0 until height) {
      val row = y * width
      scratch[row] = src[row]
      for (x in 1 until width - 1) {
        scratch[row + x] = 0.25f * src[row + x - 1] + 0.5f * src[row + x] + 0.25f * src[row + x + 1]
      }
      scratch[row + width - 1] = src[row + width - 1]
    }
    // Vertical [0.25, 0.5, 0.25]
    for (x in 0 until width) {
      dst[x] = scratch[x]
      dst[(height - 1) * width + x] = scratch[(height - 1) * width + x]
    }
    for (y in 1 until height - 1) {
      val rPrev = (y - 1) * width
      val rCurr = y * width
      val rNext = (y + 1) * width
      for (x in 0 until width) {
        dst[rCurr + x] = 0.25f * scratch[rPrev + x] + 0.5f * scratch[rCurr + x] + 0.25f * scratch[rNext + x]
      }
    }
  }

  /**
   * Builds a 256-entry tonal LUT:
   * - Anchors and gently deepens shadows (prevents milky gray haze)
   * - Applies balanced midtone S-curve contrast
   * - Softly protects highlights so overcast skies & white buildings never wash out
   */
  internal fun buildFilmicToneLut(
    contrastPower: Float,
    shadowDepth: Float,
    highlightCompress: Float,
  ): FloatArray {
    return FloatArray(256) { i ->
      val x = i / 255f
      val xp = x.pow(contrastPower)
      val omp = (1f - x).pow(contrastPower)
      val sCurve = if (xp + omp > 0f) xp / (xp + omp) else x

      val shaped = if (x > 0.5f) {
        val highS = x * 0.85f + sCurve * 0.15f
        val rolloff = highlightCompress * (x - 0.5f) * (1f - x) * 4f
        highS - rolloff
      } else {
        val shadowPull = shadowDepth * (1f - x) * x * 4f
        sCurve - shadowPull
      }
      (shaped.coerceIn(0f, 1f)) * 255f
    }
  }

  private fun bilateralLuma3x3Fast(
    src: FloatArray,
    width: Int,
    height: Int,
    sigmaRange: Float,
  ): FloatArray {
    val invTwoSigmaSq = 1f / (2f * sigmaRange * sigmaRange)
    val rangeLut = FloatArray(256) { d -> exp(-(d * d) * invTwoSigmaSq) }
    val dst = src.copyOf()

    for (y in 1 until height - 1) {
      val rPrev = (y - 1) * width
      val rCurr = y * width
      val rNext = (y + 1) * width
      for (x in 1 until width - 1) {
        val centerIdx = rCurr + x
        val center = src[centerIdx]
        var sum = center * 2.0f
        var weightSum = 2.0f

        val nTop = src[rPrev + x]
        val wTop = rangeLut[abs(nTop - center).toInt().coerceIn(0, 255)]
        sum += nTop * wTop; weightSum += wTop

        val nLeft = src[rCurr + x - 1]
        val wLeft = rangeLut[abs(nLeft - center).toInt().coerceIn(0, 255)]
        sum += nLeft * wLeft; weightSum += wLeft

        val nRight = src[rCurr + x + 1]
        val wRight = rangeLut[abs(nRight - center).toInt().coerceIn(0, 255)]
        sum += nRight * wRight; weightSum += wRight

        val nBot = src[rNext + x]
        val wBot = rangeLut[abs(nBot - center).toInt().coerceIn(0, 255)]
        sum += nBot * wBot; weightSum += wBot

        val nTL = src[rPrev + x - 1]
        val wTL = 0.7f * rangeLut[abs(nTL - center).toInt().coerceIn(0, 255)]
        sum += nTL * wTL; weightSum += wTL

        val nTR = src[rPrev + x + 1]
        val wTR = 0.7f * rangeLut[abs(nTR - center).toInt().coerceIn(0, 255)]
        sum += nTR * wTR; weightSum += wTR

        val nBL = src[rNext + x - 1]
        val wBL = 0.7f * rangeLut[abs(nBL - center).toInt().coerceIn(0, 255)]
        sum += nBL * wBL; weightSum += wBL

        val nBR = src[rNext + x + 1]
        val wBR = 0.7f * rangeLut[abs(nBR - center).toInt().coerceIn(0, 255)]
        sum += nBR * wBR; weightSum += wBR

        dst[centerIdx] = sum / weightSum
      }
    }
    return dst
  }

  private fun boxBlur3x3(src: FloatArray, width: Int, height: Int): FloatArray {
    val dst = src.copyOf()
    for (y in 1 until height - 1) {
      val rPrev = (y - 1) * width
      val rCurr = y * width
      val rNext = (y + 1) * width
      for (x in 1 until width - 1) {
        val sum =
          src[rPrev + x - 1] + src[rPrev + x] + src[rPrev + x + 1] +
            src[rCurr + x - 1] + src[rCurr + x] * 2f + src[rCurr + x + 1] +
            src[rNext + x - 1] + src[rNext + x] + src[rNext + x + 1]
        dst[rCurr + x] = sum * 0.1f
      }
    }
    return dst
  }

  /**
   * Separable 5x5 Gaussian micro-contrast boost (Difference of Gaussians):
   * Enhances texture & architectural depth without lifting sky brightness or creating halos.
   */
  private fun microContrastDetail(
    src: FloatArray,
    width: Int,
    height: Int,
    strength: Float,
  ): FloatArray {
    if (width < 5 || height < 5 || strength <= 0.01f) return src
    val temp = FloatArray(src.size)
    for (y in 0 until height) {
      val row = y * width
      temp[row] = src[row]
      temp[row + 1] = src[row + 1]
      for (x in 2 until width - 2) {
        temp[row + x] = (
          src[row + x - 2] +
            4f * src[row + x - 1] +
            6f * src[row + x] +
            4f * src[row + x + 1] +
            src[row + x + 2]
          ) * 0.0625f
      }
      temp[row + width - 2] = src[row + width - 2]
      temp[row + width - 1] = src[row + width - 1]
    }

    val dst = src.copyOf()
    for (y in 2 until height - 2) {
      val rM2 = (y - 2) * width
      val rM1 = (y - 1) * width
      val r0 = y * width
      val rP1 = (y + 1) * width
      val rP2 = (y + 2) * width
      for (x in 2 until width - 2) {
        val blurred = (
          temp[rM2 + x] +
            4f * temp[rM1 + x] +
            6f * temp[r0 + x] +
            4f * temp[rP1 + x] +
            temp[rP2 + x]
          ) * 0.0625f
        val orig = src[r0 + x]
        val detail = orig - blurred
        if (abs(detail) < 2.2f) continue
        val normY = orig / 255f
        val positiveWeight = if (detail > 0f) (1f - normY * 0.45f) else 1.05f
        val delta = (detail * strength * positiveWeight).coerceIn(-18f, 12f)
        dst[r0 + x] = (orig + delta).coerceIn(0f, 255f)
      }
    }
    return dst
  }

  /**
   * Contrast Adaptive Sharpening (CAS) with strict local overshoot clamping
   * so building rooflines and text stay razor-sharp without bright halo outlines.
   */
  private fun contrastAdaptiveSharpen(
    src: FloatArray,
    width: Int,
    height: Int,
    strength: Float,
  ): FloatArray {
    val dst = src.copyOf()
    val maxPeak = -0.125f - 0.085f * strength.coerceIn(0f, 1f)

    for (y in 1 until height - 1) {
      val rPrev = (y - 1) * width
      val rCurr = y * width
      val rNext = (y + 1) * width

      for (x in 1 until width - 1) {
        val e = src[rCurr + x] / 255f
        val b = src[rPrev + x] / 255f
        val d = src[rCurr + x - 1] / 255f
        val f = src[rCurr + x + 1] / 255f
        val h = src[rNext + x] / 255f

        val minL = min(e, min(min(b, d), min(f, h)))
        val maxL = max(e, max(max(b, d), max(f, h)))
        val localRange = maxL - minL

        if (localRange < 0.022f) {
          dst[rCurr + x] = src[rCurr + x]
          continue
        }

        val softMin = minL.coerceAtLeast(0.0001f)
        val softMax = (1f - maxL).coerceAtLeast(0.0001f)
        val amp = min(softMin, softMax) / maxL.coerceAtLeast(0.001f)
        val sqrtAmp = sqrt(amp.coerceIn(0f, 1f))
        val w = sqrtAmp * maxPeak

        val sharpened = ((b + d + f + h) * w + e) / (1f + 4f * w)
        val safeLow = (minL - 0.025f * strength).coerceIn(0f, 1f)
        val safeHigh = (maxL + 0.015f * strength).coerceIn(0f, 1f)
        dst[rCurr + x] = sharpened.coerceIn(safeLow, safeHigh) * 255f
      }
    }
    return dst
  }
}

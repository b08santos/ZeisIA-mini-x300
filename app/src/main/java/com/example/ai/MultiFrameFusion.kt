package com.example.ai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Aligns a rapid burst of handheld zoomed frames using a 2-stage coarse-to-fine luminance
 * search (up to +/- 56 px) and fuses them with strict outlier & motion-ghost rejection so
 * noise drops cleanly while skies and edges remain 100% ghost-free.
 */
object MultiFrameFusion {

  private const val ROBUSTNESS_SIGMA_SQ = 10f * 10f * 2f

  // 256-entry lookup table eliminates millions of per-pixel exp() calls on CPU
  private val weightLut = FloatArray(256) { diff ->
    exp(-(diff * diff) / ROBUSTNESS_SIGMA_SQ)
  }

  fun fuseFrames(frames: List<Bitmap>): Bitmap {
    if (frames.isEmpty()) error("Pelo menos 1 quadro é necessário para fusão.")
    val ref = frames.first()
    if (frames.size == 1) return ref

    val width = ref.width
    val height = ref.height
    val totalPixels = width * height

    val refPixels = IntArray(totalPixels)
    ref.getPixels(refPixels, 0, width, 0, 0, width, height)
    val refLuma = extractLumaInt(refPixels)

    val accumR = FloatArray(totalPixels)
    val accumG = FloatArray(totalPixels)
    val accumB = FloatArray(totalPixels)
    val accumW = FloatArray(totalPixels)

    for (i in 0 until totalPixels) {
      val p = refPixels[i]
      accumR[i] = ((p ushr 16) and 0xFF).toFloat()
      accumG[i] = ((p ushr 8) and 0xFF).toFloat()
      accumB[i] = (p and 0xFF).toFloat()
      accumW[i] = 1f
    }

    val maxShift = (min(width, height) * 0.08f).toInt().coerceIn(8, 56)
    val candPixels = IntArray(totalPixels)
    val candLuma = IntArray(totalPixels)

    for (frameIdx in 1 until frames.size) {
      val candidate = frames[frameIdx]
      if (candidate.width != width || candidate.height != height) continue

      candidate.getPixels(candPixels, 0, width, 0, 0, width, height)
      fillLumaInt(candPixels, candLuma)

      val (bestDx, bestDy, meanSad) = findBestTranslation(
        refLuma = refLuma,
        candLuma = candLuma,
        width = width,
        height = height,
        maxShift = maxShift,
      )
      // Strictly reject shaken frames so misaligned roofs/branches never ghost into sky
      if (meanSad > 12L) continue

      for (y in 0 until height) {
        val sy = y + bestDy
        if (sy < 0 || sy >= height) continue
        val refRow = y * width
        val candRow = sy * width
        for (x in 0 until width) {
          val sx = x + bestDx
          if (sx < 0 || sx >= width) continue
          val refIdx = refRow + x
          val candIdx = candRow + sx
          val diff = abs(refLuma[refIdx] - candLuma[candIdx]).coerceIn(0, 255)
          val weight = weightLut[diff]
          if (weight > 0.32f) {
            val cp = candPixels[candIdx]
            accumR[refIdx] += ((cp ushr 16) and 0xFF) * weight
            accumG[refIdx] += ((cp ushr 8) and 0xFF) * weight
            accumB[refIdx] += (cp and 0xFF) * weight
            accumW[refIdx] += weight
          }
        }
      }
    }

    val fused = IntArray(totalPixels)
    for (i in 0 until totalPixels) {
      val w = accumW[i].coerceAtLeast(1f)
      val r = (accumR[i] / w + 0.5f).toInt().coerceIn(0, 255)
      val g = (accumG[i] / w + 0.5f).toInt().coerceIn(0, 255)
      val b = (accumB[i] / w + 0.5f).toInt().coerceIn(0, 255)
      fused[i] = Color.rgb(r, g, b)
    }

    return Bitmap.createBitmap(fused, width, height, Bitmap.Config.ARGB_8888)
  }

  private fun extractLumaInt(pixels: IntArray): IntArray {
    val out = IntArray(pixels.size)
    fillLumaInt(pixels, out)
    return out
  }

  private fun fillLumaInt(pixels: IntArray, out: IntArray) {
    for (i in pixels.indices) {
      val p = pixels[i]
      val r = (p ushr 16) and 0xFF
      val g = (p ushr 8) and 0xFF
      val b = p and 0xFF
      out[i] = (77 * r + 150 * g + 29 * b) ushr 8
    }
  }

  private fun findBestTranslation(
    refLuma: IntArray,
    candLuma: IntArray,
    width: Int,
    height: Int,
    maxShift: Int,
  ): Triple<Int, Int, Long> {
    val marginX = maxShift + max(4, width / 10)
    val marginY = maxShift + max(4, height / 10)
    val startX = marginX.coerceAtMost(width / 3)
    val endX = (width - marginX).coerceAtLeast(startX + 1)
    val startY = marginY.coerceAtMost(height / 3)
    val endY = (height - marginY).coerceAtLeast(startY + 1)

    val coarseSampleStep = max(6, min(endX - startX, endY - startY) / 22)
    var bestDx = 0
    var bestDy = 0
    var bestCost = Long.MAX_VALUE

    // Stage 1: wide coarse search (step 4 covers up to +/-56px quickly)
    for (dy in -maxShift..maxShift step 4) {
      for (dx in -maxShift..maxShift step 4) {
        val cost = sadCost(refLuma, candLuma, width, startX, endX, startY, endY, dx, dy, coarseSampleStep)
        if (cost < bestCost) {
          bestCost = cost
          bestDx = dx
          bestDy = dy
        }
      }
    }

    // Stage 2: exact 1px refinement around coarse winner
    val refineCenterX = bestDx
    val refineCenterY = bestDy
    val fineSampleStep = max(3, coarseSampleStep / 2)
    bestCost = Long.MAX_VALUE
    for (dy in (refineCenterY - 3)..(refineCenterY + 3)) {
      if (dy < -maxShift || dy > maxShift) continue
      for (dx in (refineCenterX - 3)..(refineCenterX + 3)) {
        if (dx < -maxShift || dx > maxShift) continue
        val cost = sadCost(refLuma, candLuma, width, startX, endX, startY, endY, dx, dy, fineSampleStep)
        if (cost < bestCost) {
          bestCost = cost
          bestDx = dx
          bestDy = dy
        }
      }
    }

    return Triple(bestDx, bestDy, bestCost / 1024L)
  }

  private fun sadCost(
    refLuma: IntArray,
    candLuma: IntArray,
    width: Int,
    startX: Int,
    endX: Int,
    startY: Int,
    endY: Int,
    dx: Int,
    dy: Int,
    sampleStep: Int,
  ): Long {
    var sum = 0L
    var count = 0L
    var y = startY
    while (y < endY) {
      val refRow = y * width
      val candRow = (y + dy) * width
      var x = startX
      while (x < endX) {
        sum += abs(refLuma[refRow + x] - candLuma[candRow + x + dx])
        count++
        x += sampleStep
      }
      y += sampleStep
    }
    return if (count > 0) (sum * 1024L) / count else Long.MAX_VALUE
  }
}

package com.example.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.domain.EnhancementPreset
import com.example.domain.ZoomMath
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.ImageConfig
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ImageSize
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

/**
 * Cloud generative telephoto restoration engine backed by Firebase AI Logic
 * (`gemini-nano-banana-2.1` / Nano Banana 2.1). Reconstructs fine photographic textures,
 * architectural lines, foliage, and distant details while keeping exact scene geometry.
 */
object CloudSuperEnhancer {

  private const val TAG = "CloudSuperEnhancer"
  private const val MODEL_NAME = "gemini-nano-banana-2.1"

  private val imageModel by lazy {
    val config = generationConfig {
      responseModalities = listOf(ResponseModality.IMAGE)
      imageConfig = ImageConfig.builder()
        .setImageSize(ImageSize.SIZE_2K)
        .build()
    }
    Firebase.ai.generativeModel(
      modelName = MODEL_NAME,
      generationConfig = config,
    )
  }

  suspend fun enhancePhoto(
    source: Bitmap,
    zoomRatio: Float,
    preset: EnhancementPreset,
  ): Bitmap {
    // Keep upload payload fast and well under the 20MB inline media ceiling
    val prepared = prepareForUpload(source, maxLongEdge = 1280)
    val prompt = buildRestorationPrompt(zoomRatio, preset)

    return try {
      val response = withTimeout(60_000L) {
        imageModel.generateContent(
          content {
            image(prepared)
            text(prompt)
          },
        )
      }
      val imagePart = response.candidates.firstOrNull()
        ?.content?.parts?.filterIsInstance<ImagePart>()
        ?.firstOrNull()
      imagePart?.image
        ?: throw IllegalStateException("A IA na nuvem não retornou nenhuma imagem aprimorada.")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Cloud enhancement failed", e)
      throw e
    } finally {
      if (prepared !== source) {
        prepared.recycle()
      }
    }
  }

  private fun prepareForUpload(source: Bitmap, maxLongEdge: Int): Bitmap {
    val longEdge = max(source.width, source.height)
    if (longEdge <= maxLongEdge) return source
    val scale = maxLongEdge.toFloat() / longEdge
    return Bitmap.createScaledBitmap(
      source,
      (source.width * scale).roundToInt().coerceAtLeast(1),
      (source.height * scale).roundToInt().coerceAtLeast(1),
      true,
    )
  }

  private fun buildRestorationPrompt(
    zoomRatio: Float,
    preset: EnhancementPreset,
  ): String {
    val zoomLabel = ZoomMath.formatZoomLabel(zoomRatio)
    val presetFocus = when (preset) {
      EnhancementPreset.NATURAL ->
        "Keep true-to-life photographic realism, natural colors, balanced shadows, and subtle authentic textures."
      EnhancementPreset.ULTRA_SHARP ->
        "Deliver flagship telephoto clarity (like a Vivo X300 Ultra / Zeiss lens): crisp architectural lines, detailed windows, sharp roof tiles, textured trees, and rich contrast."
      EnhancementPreset.TEXT_DETAIL ->
        "Maximize edge definition and legibility of distant signs, numbers, text, window grids, and fine structural lines while keeping photorealism."
    }
    return """
      Enhance and restore the sharpness and fine details of this $zoomLabel zoomed smartphone photograph.
      Strict rules:
      1. Keep 100% of the exact original framing, objects, buildings, perspective, colors, lighting, and exposure. Do NOT add, remove, or invent objects.
      2. Remove telephoto blur, JPEG compression artifacts, and grain, reconstructing photorealistic fine textures and crisp edges.
      3. Never make the photo brighter, washed-out, milky, or painted/cartoonish. Keep real photographic grain-free textures.
      $presetFocus
    """.trimIndent()
  }
}

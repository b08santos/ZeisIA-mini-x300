package com.example.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.domain.EnhancementPreset
import com.example.domain.LensProfile
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
    lensProfile: LensProfile = LensProfile.TELEPHOTO,
  ): Bitmap {
    // Keep upload payload fast and well under the 20MB inline media ceiling
    val prepared = prepareForUpload(source, maxLongEdge = 1280)
    val prompt = buildRestorationPrompt(zoomRatio, preset, lensProfile)

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

  internal fun buildRestorationPrompt(
    zoomRatio: Float,
    preset: EnhancementPreset,
    lensProfile: LensProfile,
  ): String {
    val zoomLabel = ZoomMath.formatZoomLabel(zoomRatio)
    val lensContext = when (lensProfile) {
      LensProfile.MACRO_TELE ->
        "This is a Vivo X300-style Telephoto Macro close-up shot at $zoomLabel magnification. Reconstruct ultra-fine macro textures (such as insect compound eyes, wing veins, tiny hairs, pollen, water droplets, leaf veins, or fabric weave) on the focal subject while keeping natural shallow-depth optical background bokeh."
      LensProfile.TELEPHOTO ->
        "This is a $zoomLabel telephoto zoomed smartphone photograph. Reconstruct crisp distant details (architectural lines, windows, foliage, signs, and natural textures)."
    }
    val presetFocus = when (preset) {
      EnhancementPreset.NATURAL ->
        "Keep true-to-life photographic realism, natural colors, balanced shadows, and subtle authentic textures."
      EnhancementPreset.ULTRA_SHARP ->
        "Deliver flagship Zeiss lens clarity: crisp micro-edges, rich micro-contrast, and clean natural textures."
      EnhancementPreset.TEXT_DETAIL ->
        "Maximize edge definition and legibility of fine structures, signs, numbers, text, and intricate micro-lines while keeping photorealism."
    }
    return """
      Enhance and restore the sharpness and fine details of this photograph.
      $lensContext
      Strict rules:
      1. Keep 100% of the exact original framing, objects, perspective, colors, lighting, and exposure. Do NOT add, remove, or invent objects.
      2. Remove optical/motion blur, JPEG compression artifacts, and grain, reconstructing photorealistic fine textures and crisp edges.
      3. Never make the photo brighter, washed-out, milky, or painted/cartoonish. Keep real photographic grain-free textures.
      $presetFocus
    """.trimIndent()
  }
}

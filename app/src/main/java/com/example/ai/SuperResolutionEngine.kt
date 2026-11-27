package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.LiteRtException
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Offline 4x neural super-resolution & line restoration engine backed by Real-ESRGAN-x4plus
 * (16.7M RRDB parameters, W8A8 UINT8 quantized) via LiteRT CompiledModel.
 * All model creation, inference, buffer reuse, and teardown run on a single confined thread.
 */
class SuperResolutionEngine(context: Context) : AutoCloseable {

  companion object {
    private const val MODEL_ASSET = "real_esrgan_x4plus.tflite"
    private const val MODEL_VERSION = "v3-rx4plus-w8a8"
    const val TILE_IN = 128
    const val SCALE = 4
    const val TILE_OUT = TILE_IN * SCALE // 512
    private const val OVERLAP_IN = 16
    private const val STRIDE_IN = TILE_IN - OVERLAP_IN // 112
    private const val OVERLAP_OUT = OVERLAP_IN * SCALE // 64
    private const val INPUT_ELEMENTS = TILE_IN * TILE_IN * 3

    private val sharedEnvironment by lazy { Environment.create() }

    private fun isEmulatorDevice(): Boolean {
      val fingerprint = Build.FINGERPRINT
      val model = Build.MODEL
      val product = Build.PRODUCT
      val hardware = Build.HARDWARE
      val brand = Build.BRAND
      val device = Build.DEVICE
      return fingerprint.startsWith("generic") ||
        fingerprint.startsWith("unknown") ||
        model.contains("google_sdk") ||
        model.contains("Emulator") ||
        model.contains("Android SDK built for") ||
        Build.MANUFACTURER.contains("Genymotion") ||
        hardware.contains("goldfish") ||
        hardware.contains("ranchu") ||
        hardware.contains("cutf") ||
        hardware.contains("vsoc") ||
        product.contains("sdk") ||
        product.contains("emulator") ||
        product.startsWith("cf_") ||
        (brand.startsWith("generic") && device.startsWith("generic"))
    }
  }

  private val appContext = context.applicationContext
  private val singleThreadExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "ZoomIA-LiteRT").apply { priority = Thread.NORM_PRIORITY + 1 }
  }
  private val liteRtDispatcher = singleThreadExecutor.asCoroutineDispatcher()

  private var runner: EsrganPlusRunner? = null

  @Volatile
  var activeAcceleratorLabel: String = "CPU"
    private set

  private var initialized = false

  /**
   * Initializes LiteRT on the confined single thread.
   * Real-ESRGAN-x4plus is W8A8 (INT8/UINT8) quantized, so we execute on CPU XNNPACK
   * (native ARMv9 SDOT/I8MM kernels) rather than GPU (which corrupts 3-channel quantized
   * tensors into vertical green stripes on Adreno OpenCL/GLES drivers).
   */
  suspend fun warmUp(): String = withContext(liteRtDispatcher) {
    if (initialized && runner != null) return@withContext activeAcceleratorLabel
    val stagedModel = stageModelAsset(appContext)

    try {
      runner = EsrganPlusRunner(appContext, stagedModel, Accelerator.CPU)
      activeAcceleratorLabel = "XNNPACK"
    } catch (_: Throwable) {
      runner = null
      activeAcceleratorLabel = "ISP"
    }
    initialized = true
    activeAcceleratorLabel
  }

  /**
   * Runs overlapping 128x128 -> 512x512 tiled 4x neural super-resolution using UINT8 tensors
   * with smooth feathering across overlapping tile seams. Falls back cleanly to high-quality
   * bicubic scaling if LiteRT cannot run on the device.
   */
  suspend fun upscale4x(source: Bitmap): Pair<Bitmap, Boolean> = withContext(liteRtDispatcher) {
    if (!initialized) {
      warmUp()
    }
    val activeRunner = runner
    if (activeRunner == null) {
      val fallback = Bitmap.createScaledBitmap(
        source,
        source.width * SCALE,
        source.height * SCALE,
        true,
      )
      return@withContext fallback to false
    }

    val srcArgb = source.copy(Bitmap.Config.ARGB_8888, false)
    val inW = srcArgb.width
    val inH = srcArgb.height
    val outW = inW * SCALE
    val outH = inH * SCALE

    val srcPixels = IntArray(inW * inH)
    srcArgb.getPixels(srcPixels, 0, inW, 0, 0, inW, inH)
    if (srcArgb !== source) {
      srcArgb.recycle()
    }

    val accumR = FloatArray(outW * outH)
    val accumG = FloatArray(outW * outH)
    val accumB = FloatArray(outW * outH)
    val accumW = FloatArray(outW * outH)

    val tileInput = ByteArray(INPUT_ELEMENTS)
    val feather1D = buildFeatherWeights(TILE_OUT, OVERLAP_OUT)

    val cols = if (inW <= TILE_IN) 1 else ceil((inW - OVERLAP_IN).toDouble() / STRIDE_IN).toInt()
    val rows = if (inH <= TILE_IN) 1 else ceil((inH - OVERLAP_IN).toDouble() / STRIDE_IN).toInt()

    try {
      for (row in 0 until rows) {
        val tileY = computeTileStart(row, rows, inH)
        for (col in 0 until cols) {
          val tileX = computeTileStart(col, cols, inW)

          // Extract 128x128 RGB UINT8 patch with clamp-to-edge padding
          var idx = 0
          for (py in 0 until TILE_IN) {
            val sy = (tileY + py).coerceIn(0, inH - 1)
            val rowOffset = sy * inW
            for (px in 0 until TILE_IN) {
              val sx = (tileX + px).coerceIn(0, inW - 1)
              val pixel = srcPixels[rowOffset + sx]
              tileInput[idx++] = ((pixel shr 16) and 0xFF).toByte()
              tileInput[idx++] = ((pixel shr 8) and 0xFF).toByte()
              tileInput[idx++] = (pixel and 0xFF).toByte()
            }
          }

          val outTile = activeRunner.infer(tileInput)
          val outStartX = tileX * SCALE
          val outStartY = tileY * SCALE

          val copyW = min(TILE_OUT, outW - outStartX)
          val copyH = min(TILE_OUT, outH - outStartY)

          for (oy in 0 until copyH) {
            val wy = when {
              rows == 1 -> 1f
              row == 0 && oy < OVERLAP_OUT -> 1f
              row == rows - 1 && oy >= TILE_OUT - OVERLAP_OUT -> 1f
              else -> feather1D[oy]
            }
            val dstRow = (outStartY + oy) * outW
            val tileRow = oy * TILE_OUT
            for (ox in 0 until copyW) {
              val wx = when {
                cols == 1 -> 1f
                col == 0 && ox < OVERLAP_OUT -> 1f
                col == cols - 1 && ox >= TILE_OUT - OVERLAP_OUT -> 1f
                else -> feather1D[ox]
              }
              val w = wx * wy
              val tBase = (tileRow + ox) * 3
              val dIdx = dstRow + outStartX + ox
              accumR[dIdx] += (outTile[tBase].toInt() and 0xFF) * w
              accumG[dIdx] += (outTile[tBase + 1].toInt() and 0xFF) * w
              accumB[dIdx] += (outTile[tBase + 2].toInt() and 0xFF) * w
              accumW[dIdx] += w
            }
          }
        }
      }

      val outPixels = IntArray(outW * outH)
      for (i in outPixels.indices) {
        val w = accumW[i].coerceAtLeast(1e-5f)
        val r = (accumR[i] / w + 0.5f).toInt().coerceIn(0, 255)
        val g = (accumG[i] / w + 0.5f).toInt().coerceIn(0, 255)
        val b = (accumB[i] / w + 0.5f).toInt().coerceIn(0, 255)
        outPixels[i] = Color.rgb(r, g, b)
      }

      val result = Bitmap.createBitmap(outPixels, outW, outH, Bitmap.Config.ARGB_8888)
      return@withContext result to true
    } catch (_: LiteRtException) {
      val fallback = Bitmap.createScaledBitmap(source, outW, outH, true)
      return@withContext fallback to false
    }
  }

  private fun computeTileStart(index: Int, total: Int, fullDim: Int): Int {
    if (total <= 1 || fullDim <= TILE_IN) return 0
    val candidate = index * STRIDE_IN
    return min(candidate, max(0, fullDim - TILE_IN))
  }

  private fun buildFeatherWeights(size: Int, overlap: Int): FloatArray {
    val w = FloatArray(size) { 1f }
    val safeOverlap = overlap.coerceIn(1, size / 2)
    for (i in 0 until safeOverlap) {
      val alpha = (i + 1).toFloat() / (safeOverlap + 1).toFloat()
      w[i] = alpha
      w[size - 1 - i] = alpha
    }
    return w
  }

  private fun stageModelAsset(context: Context): File {
    val target = File(context.noBackupFilesDir, "$MODEL_VERSION-$MODEL_ASSET")
    if (!target.isFile) {
      context.noBackupFilesDir.listFiles()?.forEach { existing ->
        if (existing.name.contains("real_esrgan") && existing != target) {
          existing.delete()
        }
      }
      val tmp = File("${target.path}.tmp")
      context.assets.open(MODEL_ASSET).use { input ->
        tmp.outputStream().use { output -> input.copyTo(output) }
      }
      check(tmp.renameTo(target)) { "Falha ao preparar modelo LiteRT em disco." }
    }
    return target
  }

  override fun close() {
    singleThreadExecutor.execute {
      runner?.close()
      runner = null
    }
    singleThreadExecutor.shutdown()
  }

  private class EsrganPlusRunner(
    context: Context,
    modelFile: File,
    accelerator: Accelerator,
  ) : AutoCloseable {
    private val model: CompiledModel
    private val inputs = mutableListOf<TensorBuffer>()
    private val outputs = mutableListOf<TensorBuffer>()

    init {
      val options = CompiledModel.Options(accelerator).apply {
        if (accelerator == Accelerator.CPU) {
          val prefix = "$MODEL_ASSET-"
          val cache = File(context.codeCacheDir, "$prefix$MODEL_VERSION.xnnpack_cache")
          context.codeCacheDir.listFiles()?.forEach { file ->
            if (
              file.name.endsWith(".xnnpack_cache") &&
              file != cache
            ) {
              file.delete()
            }
          }
          cpuOptions = CompiledModel.CpuOptions(
            numThreads = 4,
            xnnPackFlags = null,
            xnnPackWeightCachePath = cache.path,
          )
        }
      }

      model = CompiledModel.create(modelFile.path, options, sharedEnvironment)
      try {
        inputs += model.createInputBuffers()
        outputs += model.createOutputBuffers()
        // Warm-up & self-test against neutral gray patch to verify color integrity (no chroma/stripe corruption)
        val probe = ByteArray(INPUT_ELEMENTS) { 128.toByte() }
        val probeOut = infer(probe)
        verifyProbeHealthy(probeOut)
      } catch (e: Exception) {
        close()
        throw e
      }
    }

    private fun verifyProbeHealthy(outTile: ByteArray) {
      val cy = TILE_OUT / 2
      val cx = TILE_OUT / 2
      var prevR = -1
      var prevG = -1
      for (dx in -8..8) {
        val base = (cy * TILE_OUT + (cx + dx)) * 3
        val r = outTile[base].toInt() and 0xFF
        val g = outTile[base + 1].toInt() and 0xFF
        val b = outTile[base + 2].toInt() and 0xFF
        check(kotlin.math.abs(r - g) < 32 && kotlin.math.abs(g - b) < 32) {
          "Chroma corruption detected in LiteRT output ($r,$g,$b)"
        }
        if (prevR >= 0) {
          check(kotlin.math.abs(r - prevR) < 32 && kotlin.math.abs(g - prevG) < 32) {
            "Stripe artifact detected in LiteRT output"
          }
        }
        prevR = r
        prevG = g
      }
    }

    fun infer(input: ByteArray): ByteArray {
      inputs[0].writeInt8(input)
      model.run(inputs, outputs)
      return outputs[0].readInt8()
    }

    override fun close() {
      (inputs + outputs).forEach { buffer ->
        runCatching { buffer.close() }
      }
      inputs.clear()
      outputs.clear()
      runCatching { model.close() }
    }
  }
}

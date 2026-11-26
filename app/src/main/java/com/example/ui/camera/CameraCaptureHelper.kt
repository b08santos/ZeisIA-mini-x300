package com.example.ui.camera

import android.graphics.ImageFormat
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import com.example.ai.CapturedFrameBytes
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

object CameraCaptureHelper {

  suspend fun captureBurst(
    imageCapture: ImageCapture,
    executor: Executor,
    frameCount: Int,
  ): List<CapturedFrameBytes> {
    val frames = mutableListOf<CapturedFrameBytes>()
    val count = frameCount.coerceIn(1, 3)

    for (index in 0 until count) {
      val timeoutMs = if (index == 0) 6000L else 1800L
      val frame = withTimeoutOrNull(timeoutMs) {
        captureSingleJpeg(imageCapture, executor)
      }
      if (frame != null) {
        frames += frame
      } else if (index == 0) {
        break
      }
    }
    return frames
  }

  private suspend fun captureSingleJpeg(
    imageCapture: ImageCapture,
    executor: Executor,
  ): CapturedFrameBytes = suspendCancellableCoroutine { cont ->
    imageCapture.takePicture(
      executor,
      object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
          try {
            val bytes = extractJpegBytes(image)
            val rotation = image.imageInfo.rotationDegrees
            if (cont.isActive) {
              cont.resume(CapturedFrameBytes(jpegBytes = bytes, rotationDegrees = rotation))
            }
          } catch (t: Throwable) {
            if (cont.isActive) cont.resumeWithException(t)
          } finally {
            image.close()
          }
        }

        override fun onError(exception: ImageCaptureException) {
          if (cont.isActive) cont.resumeWithException(exception)
        }
      },
    )
  }

  private fun extractJpegBytes(image: ImageProxy): ByteArray {
    if (image.format == ImageFormat.JPEG) {
      val buffer = image.planes[0].buffer
      val bytes = ByteArray(buffer.remaining())
      buffer.get(bytes)
      return bytes
    }
    error("Formato inesperado da câmera: ${image.format}")
  }

  fun triggerTapToFocus(
    camera: Camera,
    previewView: PreviewView,
    x: Float,
    y: Float,
  ) {
    val point = previewView.meteringPointFactory.createPoint(x, y)
    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
      .setAutoCancelDuration(3, TimeUnit.SECONDS)
      .build()
    camera.cameraControl.startFocusAndMetering(action)
  }
}

package com.example.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Range
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.R
import com.example.domain.CaptureMode
import com.example.domain.FlashModeOption
import com.example.domain.LensProfile
import com.example.domain.ZoomMath
import com.example.ui.CameraUiState
import com.example.ui.ProcessingPhase
import com.example.ui.theme.CoralError
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.OpticalBorder
import com.example.ui.theme.WarmAmber
import com.example.ui.theme.ZeissGold
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

private enum class PreviewAspectMode {
  RATIO_3_4,
  RATIO_9_16,
  FULL,
}

@Composable
fun CameraViewfinderScreen(
  uiState: CameraUiState,
  onHardwareZoomBoundsChanged: (Float, Float) -> Unit,
  onSamsungExtensionStatus: (Boolean) -> Unit,
  onVideoQualityDetected: (String) -> Unit,
  onCreateVideoOutputOptions: () -> MediaStoreOutputOptions,
  onVideoRecordingStarted: () -> Unit,
  onVideoRecordingProgress: (Long) -> Unit,
  onVideoRecordingFinalized: (android.net.Uri?, String?) -> Unit,
  onCaptureModeChanged: (CaptureMode) -> Unit,
  onToggleLensProfile: () -> Unit,
  onToggleCloudAiQuick: () -> Unit,
  onZoomChanged: (Float) -> Unit,
  onMultiplyZoom: (Float) -> Unit,
  onToggleLens: () -> Unit,
  onCycleFlash: () -> Unit,
  onToggleGrid: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenGallery: () -> Unit,
  onStartBurstCapture: () -> Unit,
  onBurstCaptured: (List<com.example.ai.CapturedFrameBytes>) -> Unit,
  onCaptureError: (String) -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()

  val captureExecutor = remember { Executors.newSingleThreadExecutor() }
  DisposableEffect(Unit) {
    onDispose { captureExecutor.shutdown() }
  }

  var boundCamera by remember { mutableStateOf<Camera?>(null) }
  var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
  var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
  var activeRecording by remember { mutableStateOf<Recording?>(null) }
  var previewView by remember { mutableStateOf<PreviewView?>(null) }
  var tapFocusOffset by remember { mutableStateOf<Offset?>(null) }
  var isCameraSwitching by remember { mutableStateOf(false) }
  val shutterFlash = remember { Animatable(0f) }

  // Clear focus reticle after 1.4s
  LaunchedEffect(tapFocusOffset) {
    if (tapFocusOffset != null) {
      delay(1400L)
      tapFocusOffset = null
    }
  }

  // Smooth interpolated digital magnification locked closely to the 120Hz half-moon dial
  val smoothDigitalCrop by animateFloatAsState(
    targetValue = uiState.digitalCropFactor,
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioNoBouncy,
      stiffness = Spring.StiffnessHigh,
    ),
    label = "smooth_digital_crop",
  )

  fun startVideoRecording(withAudio: Boolean) {
    val activeVideoCapture = videoCapture ?: return
    val outputOptions = onCreateVideoOutputOptions()
    val mainExecutor = ContextCompat.getMainExecutor(context)

    var pending = activeVideoCapture.output.prepareRecording(context, outputOptions)
    if (withAudio && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      pending = pending.withAudioEnabled()
    }
    onVideoRecordingStarted()
    activeRecording = pending.start(mainExecutor) { event ->
      when (event) {
        is VideoRecordEvent.Start -> onVideoRecordingStarted()
        is VideoRecordEvent.Status -> onVideoRecordingProgress(event.recordingStats.recordedDurationNanos)
        is VideoRecordEvent.Finalize -> {
          activeRecording = null
          if (!event.hasError()) {
            onVideoRecordingFinalized(event.outputResults.outputUri, null)
          } else {
            onVideoRecordingFinalized(
              null,
              event.cause?.localizedMessage ?: "Erro ao finalizar gravação de vídeo.",
            )
          }
        }
      }
    }
  }

  val audioPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    startVideoRecording(withAudio = granted)
  }

  // Stop active recording safely if the screen leaves composition
  DisposableEffect(Unit) {
    onDispose {
      activeRecording?.stop()
      activeRecording = null
    }
  }

  // Bind CameraX whenever lensFacing, captureMode, or previewView changes
  LaunchedEffect(uiState.lensFacing, uiState.captureMode, previewView) {
    val pv = previewView ?: return@LaunchedEffect
    val mainExecutor = ContextCompat.getMainExecutor(context)
    isCameraSwitching = true
    try {
      runCatching {
      val provider = ProcessCameraProvider.getInstance(context).awaitResult(mainExecutor)
      val baseSelector = CameraSelector.Builder()
        .requireLensFacing(uiState.lensFacing)
        .build()

      if (uiState.captureMode == CaptureMode.PHOTO) {
        // Bind directly to native logical back camera so Samsung/OEM Vendor Extensions
        // cannot crop the 1x FOV, lock out 0.6x ultrawide, or override 4:3 preview framing.
        val bound = bindPhotoUseCases(
          provider = provider,
          lifecycleOwner = lifecycleOwner,
          selector = baseSelector,
          pv = pv,
          useCamera2HqOptions = true,
        )
        boundCamera = bound.first
        imageCapture = bound.second
        videoCapture = null
        onSamsungExtensionStatus(false)
      } else {
        val (cam, vidCap, qualityLabel) = bindVideoUseCases(
          provider = provider,
          lifecycleOwner = lifecycleOwner,
          selector = baseSelector,
          pv = pv,
        )
        boundCamera = cam
        imageCapture = null
        videoCapture = vidCap
        onSamsungExtensionStatus(false)
        onVideoQualityDetected(qualityLabel)
      }
      }.onFailure { err ->
        if (err is kotlinx.coroutines.CancellationException) throw err
        onCaptureError(err.localizedMessage ?: "Erro ao iniciar câmera.")
      }
    } finally {
      isCameraSwitching = false
    }
  }

  // Observe hardware zoom bounds cleanly for the currently bound camera
  DisposableEffect(boundCamera, lifecycleOwner) {
    val cam = boundCamera
    if (cam == null) {
      onDispose {}
    } else {
      val observer = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { zoomState ->
        onHardwareZoomBoundsChanged(zoomState.minZoomRatio, zoomState.maxZoomRatio)
      }
      cam.cameraInfo.zoomState.observe(lifecycleOwner, observer)
      onDispose {
        cam.cameraInfo.zoomState.removeObserver(observer)
      }
    }
  }

  // Sync hardware zoom ratio to CameraControl smoothly
  LaunchedEffect(uiState.hardwareZoom, boundCamera) {
    boundCamera?.cameraControl?.setZoomRatio(uiState.hardwareZoom)
  }

  // Sync flash / torch mode across Photo & Video
  LaunchedEffect(uiState.flashMode, uiState.captureMode, imageCapture, boundCamera) {
    if (uiState.captureMode == CaptureMode.PHOTO) {
      boundCamera?.cameraControl?.enableTorch(false)
      imageCapture?.flashMode = when (uiState.flashMode) {
        FlashModeOption.AUTO -> ImageCapture.FLASH_MODE_AUTO
        FlashModeOption.ON -> ImageCapture.FLASH_MODE_ON
        FlashModeOption.OFF -> ImageCapture.FLASH_MODE_OFF
      }
    } else {
      boundCamera?.cameraControl?.enableTorch(uiState.flashMode == FlashModeOption.ON)
    }
  }

  // Apply Camera2 AF_MODE_MACRO when hardware supports it in Macro Telefoto mode
  LaunchedEffect(uiState.lensProfile, uiState.captureMode, boundCamera) {
    val cam = boundCamera ?: return@LaunchedEffect
    val wantMacro = uiState.captureMode == CaptureMode.PHOTO && uiState.lensProfile == LensProfile.MACRO_TELE
    applyLensAfProfile(cam, wantMacro)
  }

  val isBusy = uiState.processingPhase != ProcessingPhase.IDLE
  val currentIsBusy by androidx.compose.runtime.rememberUpdatedState(isBusy)
  val currentOnMultiplyZoom by androidx.compose.runtime.rememberUpdatedState(onMultiplyZoom)
  val isRecording = uiState.isRecordingVideo

  var aspectMode by remember { mutableStateOf(PreviewAspectMode.RATIO_3_4) }
  val fullScreenPreview = aspectMode == PreviewAspectMode.FULL

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxSize()
      .background(ObsidianBg),
  ) {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val topChrome = statusTop + 58.dp
    val deckHeight = 148.dp
    val baseBottomChrome = navBottom + deckHeight + 14.dp
    val availH = (maxHeight - topChrome - baseBottomChrome).coerceAtLeast(180.dp)
    val isLandscape = maxWidth > maxHeight
    val effectiveAspectMode = if (uiState.captureMode == CaptureMode.VIDEO && aspectMode == PreviewAspectMode.RATIO_3_4) {
      PreviewAspectMode.RATIO_9_16
    } else {
      aspectMode
    }
    val targetAspect = when (effectiveAspectMode) {
      PreviewAspectMode.RATIO_9_16 -> if (isLandscape) 16f / 9f else 9f / 16f
      PreviewAspectMode.RATIO_3_4, PreviewAspectMode.FULL -> if (isLandscape) 4f / 3f else 3f / 4f
    }

    val targetFrameW: Dp
    val targetFrameH: Dp
    val targetTopOffset: Dp
    val targetDeckBottomPad: Dp
    if (effectiveAspectMode == PreviewAspectMode.FULL) {
      targetFrameW = maxWidth
      targetFrameH = maxHeight
      targetTopOffset = 0.dp
      targetDeckBottomPad = navBottom + 14.dp
    } else {
      if (maxWidth / targetAspect <= availH) {
        targetFrameW = maxWidth
        targetFrameH = maxWidth / targetAspect
      } else {
        targetFrameH = availH
        targetFrameW = availH * targetAspect
      }
      val slack = (availH - targetFrameH).coerceAtLeast(0.dp)
      val topExtra = (slack * 0.12f).coerceAtMost(16.dp)
      targetTopOffset = topChrome + topExtra
      targetDeckBottomPad = navBottom + 14.dp + (slack - topExtra) * 0.58f
    }

    val animDeckBottomPad by animateDpAsState(
      targetValue = targetDeckBottomPad,
      animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
      label = "deck_bottom_pad",
    )
    val bottomChrome = animDeckBottomPad + deckHeight

    val animFrameW by animateDpAsState(
      targetValue = targetFrameW,
      animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
      label = "viewfinder_w",
    )
    val animFrameH by animateDpAsState(
      targetValue = targetFrameH,
      animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
      label = "viewfinder_h",
    )
    val animTopOffset by animateDpAsState(
      targetValue = targetTopOffset,
      animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
      label = "viewfinder_top",
    )

    // Single Live Viewfinder container (smoothly transitions between uncropped 3:4 wide 1x and edge-to-edge full screen)
    Box(
      modifier = Modifier
        .padding(top = animTopOffset)
        .size(width = animFrameW, height = animFrameH)
        .align(Alignment.TopCenter)
        .clipToBounds()
        .pointerInput(Unit) {
          detectTransformGestures { _, _, zoomChange, _ ->
            if (zoomChange != 1f && !currentIsBusy) {
              currentOnMultiplyZoom(zoomChange)
            }
          }
        }
        .pointerInput(boundCamera, previewView) {
          detectTapGestures { offset ->
            val cam = boundCamera
            val pv = previewView
            if (cam != null && pv != null) {
              tapFocusOffset = offset
              CameraCaptureHelper.triggerTapToFocus(cam, pv, offset.x, offset.y)
            }
          }
        },
    ) {
      val isEmulator = remember { isRunningOnEmulator() }
      AndroidView(
        factory = { ctx ->
          PreviewView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = if (isEmulator) {
              PreviewView.ImplementationMode.PERFORMANCE
            } else {
              PreviewView.ImplementationMode.COMPATIBLE
            }
          }.also { previewView = it }
        },
        update = { pv ->
          pv.scaleX = smoothDigitalCrop
          pv.scaleY = smoothDigitalCrop
        },
        modifier = Modifier.fillMaxSize(),
      )

      // Optional 3x3 framing grid + extreme zoom center reticle aligned to live frame
      if (uiState.settings.gridEnabled || uiState.targetZoom >= 12f) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          if (uiState.settings.gridEnabled) {
            val lineColor = Color.White.copy(alpha = 0.16f)
            val stroke = 1.dp.toPx()
            for (i in 1..2) {
              val x = size.width * (i / 3f)
              val y = size.height * (i / 3f)
              drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
              drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            }
          }

          if (uiState.targetZoom >= 12f) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val arm = 12.dp.toPx()
            val gap = 4.dp.toPx()
            val reticleColor = ZeissGold.copy(alpha = 0.78f)
            val sw = 1.5.dp.toPx()
            drawLine(reticleColor, Offset(cx - arm, cy), Offset(cx - gap, cy), strokeWidth = sw)
            drawLine(reticleColor, Offset(cx + gap, cy), Offset(cx + arm, cy), strokeWidth = sw)
            drawLine(reticleColor, Offset(cx, cy - arm), Offset(cx, cy - gap), strokeWidth = sw)
            drawLine(reticleColor, Offset(cx, cy + gap), Offset(cx, cy + arm), strokeWidth = sw)
          }
        }
      }

      // Animated tap-to-focus amber optical reticle
      androidx.compose.animation.AnimatedVisibility(
        visible = tapFocusOffset != null,
        enter = fadeIn(tween(140)) + scaleIn(
          initialScale = 1.32f,
          animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
          ),
        ),
        exit = fadeOut(tween(220)) + scaleOut(targetScale = 0.88f, animationSpec = tween(220)),
      ) {
        tapFocusOffset?.let { pos ->
          val halfPx = with(LocalDensity.current) { 30.dp.toPx() }
          Box(
            modifier = Modifier
              .offset {
                IntOffset(
                  (pos.x - halfPx).roundToInt(),
                  (pos.y - halfPx).roundToInt(),
                )
              }
              .size(60.dp)
              .border(1.5.dp, WarmAmber, RoundedCornerShape(14.dp)),
          )
        }
      }

      // Smooth crossfade veil during Foto <-> Vídeo Pro or lens switch + shutter flash
      val switchVeilAlpha by animateFloatAsState(
        targetValue = if (isCameraSwitching) 0.55f else 0f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "camera_switch_veil",
      )
      val overlayAlpha = maxOf(switchVeilAlpha, shutterFlash.value)
      if (overlayAlpha > 0.005f) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              if (shutterFlash.value > switchVeilAlpha) {
                Color.White.copy(alpha = shutterFlash.value)
              } else {
                ObsidianBg.copy(alpha = switchVeilAlpha)
              },
            ),
        )
      }
    }

    // Subtle top & bottom gradient scrims for crisp readability in Cheia mode
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(topChrome + 36.dp)
        .align(Alignment.TopCenter)
        .background(
          Brush.verticalGradient(
            listOf(ObsidianBg.copy(alpha = if (fullScreenPreview) 0.72f else 0.35f), Color.Transparent),
          ),
        ),
    )
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(bottomChrome + 96.dp)
        .align(Alignment.BottomCenter)
        .background(
          Brush.verticalGradient(
            listOf(Color.Transparent, ObsidianBg.copy(alpha = if (fullScreenPreview) 0.84f else 0.50f)),
          ),
        ),
    )

    // Vivo X300 Top Header Bar
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .align(Alignment.TopCenter)
        .statusBarsPadding()
        .padding(horizontal = 10.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (isRecording) {
        val recPulseTransition = rememberInfiniteTransition(label = "rec_pulse")
        val recDotAlpha by recPulseTransition.animateFloat(
          initialValue = 1f,
          targetValue = 0.28f,
          animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
          ),
          label = "rec_dot_alpha",
        )
        Surface(
          color = CoralError.copy(alpha = 0.22f),
          shape = RoundedCornerShape(50),
          border = BorderStroke(1.dp, CoralError),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Box(
              modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(CoralError.copy(alpha = recDotAlpha)),
            )
            Text(
              text = "REC ${formatDurationMs(uiState.recordingDurationMs)}",
              style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
              color = Color.White,
            )
            Text(
              text = uiState.videoQualityLabel,
              style = MaterialTheme.typography.labelSmall,
              color = ZeissGold,
            )
          }
        }
      } else {
        Row(
          horizontalArrangement = Arrangement.spacedBy(5.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          HudIconButton(
            onClick = onCycleFlash,
            testTag = "flash_button",
            contentDescription = stringResource(R.string.cd_flash),
          ) {
            Icon(
              imageVector = when (uiState.flashMode) {
                FlashModeOption.AUTO -> Icons.Outlined.FlashAuto
                FlashModeOption.ON -> Icons.Outlined.FlashOn
                FlashModeOption.OFF -> Icons.Outlined.FlashOff
              },
              contentDescription = stringResource(R.string.cd_flash),
              tint = if (uiState.flashMode == FlashModeOption.ON) WarmAmber else Color.White,
              modifier = Modifier.size(18.dp),
            )
          }

          HudIconButton(
            onClick = onToggleGrid,
            testTag = "grid_button",
            contentDescription = stringResource(R.string.cd_grid),
          ) {
            Icon(
              imageVector = if (uiState.settings.gridEnabled) Icons.Outlined.GridOn else Icons.Outlined.GridOff,
              contentDescription = stringResource(R.string.cd_grid),
              tint = if (uiState.settings.gridEnabled) ZeissGold else Color.White,
              modifier = Modifier.size(18.dp),
            )
          }

          // Aspect ratio toggle: 3:4 (max 1x wide FOV) -> 9:16 -> Cheia (full-screen edge-to-edge)
          val aspectTint by animateColorAsState(
            targetValue = if (effectiveAspectMode != PreviewAspectMode.RATIO_3_4) ZeissGold else Color.White,
            animationSpec = tween(200, easing = FastOutSlowInEasing),
            label = "aspect_tint",
          )
          Box(
            modifier = Modifier
              .height(40.dp)
              .minimumInteractiveComponentSize()
              .liquidGlassBubble(
                shape = RoundedCornerShape(50),
                active = effectiveAspectMode != PreviewAspectMode.RATIO_3_4,
                activeColor = ZeissGold,
                baseBlackAlpha = 0.72f,
                pressedScale = 0.87f,
                onClick = {
                  aspectMode = if (uiState.captureMode == CaptureMode.VIDEO) {
                    if (aspectMode == PreviewAspectMode.FULL) PreviewAspectMode.RATIO_9_16 else PreviewAspectMode.FULL
                  } else {
                    when (aspectMode) {
                      PreviewAspectMode.RATIO_3_4 -> PreviewAspectMode.RATIO_9_16
                      PreviewAspectMode.RATIO_9_16 -> PreviewAspectMode.FULL
                      PreviewAspectMode.FULL -> PreviewAspectMode.RATIO_3_4
                    }
                  }
                },
              )
              .testTag("aspect_ratio_button"),
            contentAlignment = Alignment.Center,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 9.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Icon(
                imageVector = Icons.Outlined.AspectRatio,
                contentDescription = when (effectiveAspectMode) {
                  PreviewAspectMode.FULL -> "Tela Cheia"
                  PreviewAspectMode.RATIO_9_16 -> "Proporção 9:16"
                  PreviewAspectMode.RATIO_3_4 -> "Proporção 3:4"
                },
                tint = aspectTint,
                modifier = Modifier.size(14.dp),
              )
              Text(
                text = when (effectiveAspectMode) {
                  PreviewAspectMode.FULL -> "Cheia"
                  PreviewAspectMode.RATIO_9_16 -> "9:16"
                  PreviewAspectMode.RATIO_3_4 -> "3:4"
                },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = aspectTint,
                maxLines = 1,
              )
            }
          }

          if (uiState.captureMode == CaptureMode.PHOTO) {
            val isMacro = uiState.lensProfile == LensProfile.MACRO_TELE
            val macroTint by animateColorAsState(
              targetValue = if (isMacro) ZeissGold else Color.White,
              animationSpec = tween(220, easing = FastOutSlowInEasing),
              label = "macro_tint",
            )
            Box(
              modifier = Modifier
                .height(40.dp)
                .minimumInteractiveComponentSize()
                .liquidGlassBubble(
                  shape = RoundedCornerShape(50),
                  enabled = !isBusy,
                  active = isMacro,
                  activeColor = ZeissGold,
                  baseBlackAlpha = 0.72f,
                  pressedScale = 0.87f,
                  onClick = onToggleLensProfile,
                )
                .testTag("lens_profile_button"),
              contentAlignment = Alignment.Center,
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Icon(
                  imageVector = if (isMacro) Icons.Outlined.LocalFlorist else Icons.Outlined.CenterFocusStrong,
                  contentDescription = if (isMacro) "Macro Telefoto" else "Telefoto",
                  tint = macroTint,
                  modifier = Modifier.size(15.dp),
                )
                Text(
                  text = if (isMacro) "Macro" else "Tele",
                  style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                  color = macroTint,
                  maxLines = 1,
                )
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.width(4.dp))

      Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (!isRecording) {
          val engineLabel = when {
            uiState.captureMode == CaptureMode.VIDEO -> uiState.videoQualityLabel
            uiState.settings.cloudAiEnabled -> "NUVEM"
            else -> "OFFLINE"
          }
          Box(
            modifier = Modifier.liquidGlassBubble(
              shape = RoundedCornerShape(50),
              active = uiState.captureMode == CaptureMode.VIDEO || uiState.settings.cloudAiEnabled,
              activeColor = ZeissGold,
              baseBlackAlpha = 0.70f,
            ),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = engineLabel,
              style = MaterialTheme.typography.labelSmall,
              color = when {
                uiState.captureMode == CaptureMode.VIDEO -> ZeissGold
                uiState.settings.cloudAiEnabled -> ZeissGold
                else -> Color.White.copy(alpha = 0.92f)
              },
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            )
          }

          HudIconButton(
            onClick = onOpenSettings,
            testTag = "settings_button",
            contentDescription = stringResource(R.string.cd_settings),
          ) {
            Icon(
              imageVector = Icons.Outlined.Tune,
              contentDescription = stringResource(R.string.cd_settings),
              tint = Color.White,
              modifier = Modifier.size(18.dp),
            )
          }
        } else {
          HudIconButton(
            onClick = onCycleFlash,
            testTag = "flash_button",
            contentDescription = stringResource(R.string.cd_flash),
          ) {
            Icon(
              imageVector = if (uiState.flashMode == FlashModeOption.ON) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
              contentDescription = stringResource(R.string.cd_flash),
              tint = if (uiState.flashMode == FlashModeOption.ON) WarmAmber else Color.White,
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }
    }

    // Zoom Radar Minimap inside top-right corner of live viewfinder
    androidx.compose.animation.AnimatedVisibility(
      visible = uiState.targetZoom >= 12f && uiState.captureMode == CaptureMode.PHOTO,
      enter = fadeIn() + scaleIn(),
      exit = fadeOut() + scaleOut(),
      modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(top = animTopOffset + 14.dp, end = 14.dp),
    ) {
      ZoomRadarMinimap(
        totalZoom = uiState.targetZoom,
        digitalCropFactor = uiState.digitalCropFactor,
      )
    }

    // Zoom Strip + Vivo X300 Half-Moon Dial anchored cleanly at the bottom edge of the live frame
    val zoomBottomPad = maxOf(
      bottomChrome + 6.dp,
      (maxHeight - animTopOffset - animFrameH) + 8.dp,
    )
    ZeissRotaryZoomControl(
      targetZoom = uiState.targetZoom,
      hardwareMinZoom = uiState.hardwareMinZoom,
      hardwareMaxZoom = uiState.hardwareMaxZoom,
      maxZoom = uiState.maxZoomForCurrentMode,
      lensProfile = uiState.lensProfile,
      enabled = !isBusy,
      onZoomChanged = onZoomChanged,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = zoomBottomPad),
    )

    // Bottom Control Deck (Foto | Vídeo Pro + Gallery / Concentric Shutter / IA+ / Flip)
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .align(Alignment.BottomCenter)
        .padding(top = 8.dp, bottom = animDeckBottomPad),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {

      // Clean Vivo-style Foto | Vídeo Pro strip
      Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        VivoModeTab(
          label = "Foto",
          selected = uiState.captureMode == CaptureMode.PHOTO,
          enabled = !isBusy && !isRecording,
          onClick = { onCaptureModeChanged(CaptureMode.PHOTO) },
          testTag = "mode_photo_pill",
        )
        VivoModeTab(
          label = "Vídeo Pro",
          selected = uiState.captureMode == CaptureMode.VIDEO,
          enabled = !isBusy && !isRecording,
          onClick = { onCaptureModeChanged(CaptureMode.VIDEO) },
          testTag = "mode_video_pill",
        )
      }

      Spacer(modifier = Modifier.height(12.dp))

      // Vivo-style Shutter Row: Rounded-Square Gallery | Concentric White+Gold Shutter | [IA] + Flip Camera
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Left: Gallery Preview Thumbnail (balanced width with right cluster so shutter stays dead-center)
        Box(
          modifier = Modifier.width(104.dp),
          contentAlignment = Alignment.CenterStart,
        ) {
          val latestItem = uiState.savedPhotos.firstOrNull()
          Box(
            modifier = Modifier
              .size(50.dp)
              .liquidGlassBubble(
                shape = RoundedCornerShape(12.dp),
                enabled = !isBusy && !isRecording,
                baseBlackAlpha = 0.72f,
                borderWidth = 1.2.dp,
                pressedScale = 0.86f,
                onClick = onOpenGallery,
              )
              .testTag("gallery_button"),
            contentAlignment = Alignment.Center,
          ) {
            if (latestItem != null && !latestItem.isVideo) {
              AsyncImage(
                model = latestItem.uri,
                contentDescription = stringResource(R.string.cd_gallery),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
              )
            } else {
              Icon(
                imageVector = if (latestItem?.isVideo == true) Icons.Outlined.Videocam else Icons.Outlined.PhotoLibrary,
                contentDescription = stringResource(R.string.cd_gallery),
                tint = if (latestItem?.isVideo == true) ZeissGold else Color.White,
              )
            }
          }
        }

        // Center: Vivo/Zeiss Concentric Shutter / Video Record Ring with Apple bubble pop + smooth REC morph
        val isVideoMode = uiState.captureMode == CaptureMode.VIDEO
        val shutterInteraction = remember { MutableInteractionSource() }
        val isShutterPressed by shutterInteraction.collectIsPressedAsState()
        val shutterScale by animateFloatAsState(
          targetValue = if (isShutterPressed) 0.87f else 1.0f,
          animationSpec = spring(
            dampingRatio = 0.44f,
            stiffness = Spring.StiffnessMediumLow,
          ),
          label = "shutter_press_scale",
        )
        val innerAccentColor by animateColorAsState(
          targetValue = when {
            isVideoMode -> CoralError
            uiState.targetZoom >= 30f -> WarmAmber
            else -> ZeissGold
          },
          animationSpec = tween(240, easing = FastOutSlowInEasing),
          label = "shutter_accent_color",
        )
        val recInnerSize by animateDpAsState(
          targetValue = if (isRecording) 28.dp else 58.dp,
          animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
          ),
          label = "rec_inner_size",
        )
        val recInnerCorner by animateDpAsState(
          targetValue = if (isRecording) 8.dp else 29.dp,
          animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
          ),
          label = "rec_inner_corner",
        )
        Box(
          modifier = Modifier
            .size(78.dp)
            .graphicsLayer {
              scaleX = shutterScale
              scaleY = shutterScale
            }
            .clip(CircleShape)
            .border(
              width = 3.dp,
              color = Color.White,
              shape = CircleShape,
            )
            .clickable(
              interactionSource = shutterInteraction,
              indication = null,
              enabled = !isBusy && (if (isVideoMode) videoCapture != null else imageCapture != null),
            ) {
              if (isVideoMode) {
                if (isRecording) {
                  activeRecording?.stop()
                } else {
                  val hasAudio = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                  ) == PackageManager.PERMISSION_GRANTED
                  if (hasAudio) {
                    startVideoRecording(withAudio = true)
                  } else {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                  }
                }
              } else {
                val cap = imageCapture ?: return@clickable
                onStartBurstCapture()
                scope.launch {
                  shutterFlash.snapTo(0.38f)
                  shutterFlash.animateTo(0f, tween(210, easing = FastOutSlowInEasing))
                }
                scope.launch {
                  val burstCount = when {
                    !uiState.settings.multiFrameEnabled || uiState.targetZoom < 2f -> 1
                    uiState.targetZoom >= 15f -> 5
                    else -> 4
                  }
                  val frames = CameraCaptureHelper.captureBurst(cap, captureExecutor, burstCount)
                  if (frames.isEmpty()) {
                    onCaptureError("Não foi possível capturar a foto.")
                  } else {
                    onBurstCaptured(frames)
                  }
                }
              }
            }
            .testTag("shutter_button"),
          contentAlignment = Alignment.Center,
        ) {
          if (isBusy) {
            CircularProgressIndicator(
              color = innerAccentColor,
              strokeWidth = 3.dp,
              modifier = Modifier.size(56.dp),
            )
          } else if (isVideoMode) {
            Box(
              modifier = Modifier
                .size(recInnerSize)
                .clip(RoundedCornerShape(recInnerCorner))
                .background(CoralError),
            )
          } else {
            Box(
              modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(ObsidianBg)
                .border(2.dp, innerAccentColor, CircleShape),
            )
          }
        }

        // Right: Quick [IA] Cloud Toggle + Flip Camera (balanced 104.dp width)
        Row(
          modifier = Modifier.width(104.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (uiState.captureMode == CaptureMode.PHOTO && !isRecording) {
            val aiOn = uiState.settings.cloudAiEnabled
            val aiTint by animateColorAsState(
              targetValue = if (aiOn) ZeissGold else Color.White.copy(alpha = 0.88f),
              animationSpec = tween(220, easing = FastOutSlowInEasing),
              label = "ai_btn_tint",
            )
            Box(
              modifier = Modifier
                .size(48.dp)
                .liquidGlassBubble(
                  shape = CircleShape,
                  enabled = !isBusy,
                  active = aiOn,
                  activeColor = ZeissGold,
                  baseBlackAlpha = 0.72f,
                  borderWidth = 1.2.dp,
                  pressedScale = 0.86f,
                  onClick = onToggleCloudAiQuick,
                )
                .testTag("quick_ai_button"),
              contentAlignment = Alignment.Center,
            ) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                  imageVector = Icons.Outlined.AutoAwesome,
                  contentDescription = if (aiOn) "IA Nuvem ativa" else "Motor Offline ativo",
                  tint = aiTint,
                  modifier = Modifier.size(16.dp),
                )
                Text(
                  text = if (aiOn) "IA+" else "PRO",
                  style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                  ),
                  color = aiTint,
                )
              }
            }
          }

          Box(
            modifier = Modifier
              .size(48.dp)
              .liquidGlassBubble(
                shape = CircleShape,
                enabled = !isBusy && !isRecording,
                baseBlackAlpha = 0.72f,
                borderWidth = 1.2.dp,
                pressedScale = 0.86f,
                onClick = onToggleLens,
              )
              .testTag("flip_camera_button"),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Outlined.Cameraswitch,
              contentDescription = stringResource(R.string.cd_flip_camera),
              tint = Color.White,
              modifier = Modifier.size(22.dp),
            )
          }
        }
      }
    }

    // Processing status overlay
    AnimatedVisibility(
      visible = isBusy,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier.align(Alignment.Center),
    ) {
      Surface(
        color = ObsidianSurface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, ZeissGold.copy(alpha = 0.55f)),
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          CircularProgressIndicator(color = ZeissGold, strokeWidth = 3.dp)
          Text(
            text = when (uiState.processingPhase) {
              ProcessingPhase.CAPTURING_BURST -> stringResource(R.string.processing_capturing_burst)
              ProcessingPhase.ENHANCING_AI -> if (uiState.settings.cloudAiEnabled && uiState.targetZoom >= 1.5f) {
                stringResource(R.string.processing_enhancing_cloud)
              } else {
                stringResource(R.string.processing_enhancing_ai)
              }
              ProcessingPhase.SAVING -> stringResource(R.string.processing_saving)
              ProcessingPhase.IDLE -> ""
            },
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalCamera2Interop::class)
private fun bindPhotoUseCases(
  provider: ProcessCameraProvider,
  lifecycleOwner: androidx.lifecycle.LifecycleOwner,
  selector: CameraSelector,
  pv: PreviewView,
  useCamera2HqOptions: Boolean,
): Pair<Camera, ImageCapture> {
  provider.unbindAll()

  val previewResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(
      ResolutionStrategy(
        android.util.Size(1920, 1440),
        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
      ),
    )
    .build()

  val captureResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
    .build()

  val previewBuilder = Preview.Builder()
    .setResolutionSelector(previewResolutionSelector)
  val captureBuilder = ImageCapture.Builder()
    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
    .setResolutionSelector(captureResolutionSelector)

  if (useCamera2HqOptions) {
    applyPreviewOis(Camera2Interop.Extender(previewBuilder))
    applyFlagshipCamera2Isp(Camera2Interop.Extender(captureBuilder))
  }

  val preview = previewBuilder.build().also { it.surfaceProvider = pv.surfaceProvider }
  val capture = captureBuilder.build()

  val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
  return camera to capture
}

@OptIn(ExperimentalCamera2Interop::class)
private fun bindVideoUseCases(
  provider: ProcessCameraProvider,
  lifecycleOwner: androidx.lifecycle.LifecycleOwner,
  selector: CameraSelector,
  pv: PreviewView,
): Triple<Camera, VideoCapture<Recorder>, String> {
  provider.unbindAll()

  val previewBuilder = Preview.Builder()
    .setResolutionSelector(
      ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .build(),
    )
    .setPreviewStabilizationEnabled(true)

  val qualitySelector = QualitySelector.fromOrderedList(
    listOf(Quality.UHD, Quality.FHD, Quality.HD),
    FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD),
  )
  val recorder = Recorder.Builder()
    .setQualitySelector(qualitySelector)
    .build()

  val videoBuilder = VideoCapture.Builder(recorder)
    .setVideoStabilizationEnabled(true)

  applyPreviewOis(Camera2Interop.Extender(previewBuilder))
  applyFlagshipCamera2Isp(Camera2Interop.Extender(videoBuilder))

  val preview = previewBuilder.build().also { it.surfaceProvider = pv.surfaceProvider }
  val videoCapture = videoBuilder.build()

  val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, videoCapture)

  // Inspect actual device video capabilities & OIS availability for accurate HUD label
  val camInfo = camera.cameraInfo
  val videoCapabilities = Recorder.getVideoCapabilities(camInfo)
  val supportedQualities = videoCapabilities.getSupportedQualities(androidx.camera.core.DynamicRange.SDR)
  val resLabel = when {
    Quality.UHD in supportedQualities -> "4K"
    Quality.FHD in supportedQualities -> "FHD"
    else -> "HD"
  }
  val hasOis = runCatching {
    val chars = Camera2CameraInfo.extractCameraCharacteristics(camInfo)
    val modes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
    modes?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
  }.getOrDefault(false)

  val has60Fps = runCatching {
    camInfo.supportedFrameRateRanges.any { it.upper >= 60 }
  }.getOrDefault(false)

  val stabLabel = if (hasOis) "OIS+EIS" else "EIS"
  val fpsSuffix = if (has60Fps) " • 60fps" else ""
  return Triple(camera, videoCapture, "$resLabel $stabLabel$fpsSuffix")
}

@OptIn(ExperimentalCamera2Interop::class)
private fun <T> applyPreviewOis(
  extender: Camera2Interop.Extender<T>,
) {
  // Keep native Samsung/OEM Auto-HDR tone curve & color saturation untouched on live preview
  extender.setCaptureRequestOption(
    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
  )
}

@OptIn(ExperimentalCamera2Interop::class)
private fun <T> applyFlagshipCamera2Isp(
  extender: Camera2Interop.Extender<T>,
) {
  extender.setCaptureRequestOption(
    CaptureRequest.EDGE_MODE,
    CaptureRequest.EDGE_MODE_HIGH_QUALITY,
  )
  extender.setCaptureRequestOption(
    CaptureRequest.NOISE_REDUCTION_MODE,
    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
  )
  extender.setCaptureRequestOption(
    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
  )
}

@OptIn(ExperimentalCamera2Interop::class)
private fun applyLensAfProfile(cam: Camera, wantMacro: Boolean) {
  runCatching {
    val chars = Camera2CameraInfo.extractCameraCharacteristics(cam.cameraInfo)
    val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return@runCatching
    val targetAfMode = when {
      wantMacro && CaptureRequest.CONTROL_AF_MODE_MACRO in afModes ->
        CaptureRequest.CONTROL_AF_MODE_MACRO
      CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes ->
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
      else -> null
    }
    if (targetAfMode != null) {
      Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
        CaptureRequestOptions.Builder()
          .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, targetAfMode)
          .build(),
      )
    }
  }
}

private suspend fun <T> ListenableFuture<T>.awaitResult(executor: Executor): T =
  suspendCancellableCoroutine { cont ->
    addListener(
      {
        runCatching { get() }
          .onSuccess { if (cont.isActive) cont.resume(it) }
          .onFailure { if (cont.isActive) cont.resumeWithException(it) }
      },
      executor,
    )
  }

@Composable
private fun VivoModeTab(
  label: String,
  selected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
  testTag: String,
) {
  val tabInteraction = remember { MutableInteractionSource() }
  val isTabPressed by tabInteraction.collectIsPressedAsState()
  val tabScale by animateFloatAsState(
    targetValue = if (isTabPressed) 0.88f else 1.0f,
    animationSpec = spring(
      dampingRatio = 0.44f,
      stiffness = Spring.StiffnessMediumLow,
    ),
    label = "mode_tab_scale",
  )
  val textColor by animateColorAsState(
    targetValue = if (selected) ZeissGold else Color.White.copy(alpha = 0.65f),
    animationSpec = tween(220, easing = FastOutSlowInEasing),
    label = "mode_tab_color",
  )
  val dotSize by animateDpAsState(
    targetValue = if (selected) 5.dp else 0.dp,
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioMediumBouncy,
      stiffness = Spring.StiffnessMedium,
    ),
    label = "mode_tab_dot",
  )
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
    modifier = Modifier
      .minimumInteractiveComponentSize()
      .graphicsLayer {
        scaleX = tabScale
        scaleY = tabScale
      }
      .clip(RoundedCornerShape(12.dp))
      .clickable(
        interactionSource = tabInteraction,
        indication = null,
        enabled = enabled,
        onClick = onClick,
      )
      .padding(horizontal = 12.dp, vertical = 6.dp)
      .testTag(testTag),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.titleSmall.copy(
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
      ),
      color = textColor,
    )
    Box(
      modifier = Modifier
        .size(dotSize)
        .clip(CircleShape)
        .background(ZeissGold),
    )
  }
}

@Composable
private fun HudIconButton(
  onClick: () -> Unit,
  testTag: String,
  contentDescription: String,
  content: @Composable () -> Unit,
) {
  Box(
    modifier = Modifier
      .size(42.dp)
      .minimumInteractiveComponentSize()
      .liquidGlassBubble(
        shape = CircleShape,
        baseBlackAlpha = 0.68f,
        pressedScale = 0.85f,
        onClick = onClick,
      )
      .testTag(testTag),
    contentAlignment = Alignment.Center,
  ) {
    content()
  }
}

@Composable
private fun ZoomRadarMinimap(
  totalZoom: Float,
  digitalCropFactor: Float,
) {
  Surface(
    color = ObsidianSurface.copy(alpha = 0.88f),
    shape = RoundedCornerShape(14.dp),
    border = BorderStroke(1.dp, OpticalBorder),
  ) {
    Column(
      modifier = Modifier.padding(8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Canvas(modifier = Modifier.size(width = 72.dp, height = 92.dp)) {
        drawRect(
          color = Color.White.copy(alpha = 0.35f),
          style = Stroke(width = 1.dp.toPx()),
        )
        drawLine(
          color = Color.White.copy(alpha = 0.14f),
          start = Offset(size.width / 2f, 0f),
          end = Offset(size.width / 2f, size.height),
          strokeWidth = 1.dp.toPx(),
        )
        drawLine(
          color = Color.White.copy(alpha = 0.14f),
          start = Offset(0f, size.height / 2f),
          end = Offset(size.width, size.height / 2f),
          strokeWidth = 1.dp.toPx(),
        )
        val fraction = (1f / digitalCropFactor.coerceAtLeast(1f)).coerceIn(0.08f, 0.92f)
        val rw = size.width * fraction
        val rh = size.height * fraction
        drawRect(
          color = WarmAmber,
          topLeft = Offset((size.width - rw) / 2f, (size.height - rh) / 2f),
          size = Size(rw, rh),
          style = Stroke(width = 2.dp.toPx()),
        )
      }
      Text(
        text = "${ZoomMath.formatZoomLabel(totalZoom)} ROI",
        style = MaterialTheme.typography.labelSmall,
        color = WarmAmber,
      )
    }
  }
}

private fun formatDurationMs(durationMs: Long): String {
  val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
  val mins = totalSeconds / 60L
  val secs = totalSeconds % 60L
  return String.format(Locale.US, "%02d:%02d", mins, secs)
}

private fun isRunningOnEmulator(): Boolean {
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

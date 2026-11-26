package com.example.ui.camera

import android.os.Build
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.R
import com.example.domain.FlashModeOption
import com.example.domain.ZoomMath
import com.example.domain.ZoomMode
import com.example.ui.CameraUiState
import com.example.ui.ProcessingPhase
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.OpticalBorder
import com.example.ui.theme.WarmAmber
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CameraViewfinderScreen(
  uiState: CameraUiState,
  onHardwareZoomBoundsChanged: (Float, Float) -> Unit,
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
  var previewView by remember { mutableStateOf<PreviewView?>(null) }
  var tapFocusOffset by remember { mutableStateOf<Offset?>(null) }

  // Clear focus reticle after 1.4s
  LaunchedEffect(tapFocusOffset) {
    if (tapFocusOffset != null) {
      delay(1400L)
      tapFocusOffset = null
    }
  }

  // Bind CameraX whenever lensFacing changes
  LaunchedEffect(uiState.lensFacing, previewView) {
    val pv = previewView ?: return@LaunchedEffect
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener(
      {
        runCatching {
          val provider = cameraProviderFuture.get()
          provider.unbindAll()

          val previewResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
              ResolutionStrategy(
                android.util.Size(1440, 1080),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
              ),
            )
            .build()

          val captureResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()

          val preview = Preview.Builder()
            .setResolutionSelector(previewResolutionSelector)
            .build()
            .also {
              it.surfaceProvider = pv.surfaceProvider
            }

          val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(captureResolutionSelector)
            .build()

          val selector = CameraSelector.Builder()
            .requireLensFacing(uiState.lensFacing)
            .build()

          val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
          boundCamera = camera
          imageCapture = capture
        }.onFailure { err ->
          onCaptureError(err.localizedMessage ?: "Erro ao iniciar câmera.")
        }
      },
      ContextCompat.getMainExecutor(context),
    )
  }

  // Observe hardware zoom bounds cleanly for the currently bound camera
  DisposableEffect(boundCamera, lifecycleOwner) {
    val cam = boundCamera
    if (cam == null) {
      onDispose {}
    } else {
      val observer = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { zoomState ->
        if (zoomState != null) {
          onHardwareZoomBoundsChanged(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        }
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

  // Sync flash mode
  LaunchedEffect(uiState.flashMode, imageCapture) {
    imageCapture?.flashMode = when (uiState.flashMode) {
      FlashModeOption.AUTO -> ImageCapture.FLASH_MODE_AUTO
      FlashModeOption.ON -> ImageCapture.FLASH_MODE_ON
      FlashModeOption.OFF -> ImageCapture.FLASH_MODE_OFF
    }
  }

  val isBusy = uiState.processingPhase != ProcessingPhase.IDLE
  val zoomMode = ZoomMath.classifyZoomMode(uiState.targetZoom, uiState.hardwareMaxZoom)
  val zoomPresets = remember(uiState.hardwareMinZoom) {
    buildList {
      if (uiState.hardwareMinZoom <= 0.7f) add(0.6f)
      addAll(listOf(1f, 2f, 5f, 10f, 30f, 50f, 100f))
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(ObsidianBg),
  ) {
    // Live Viewfinder with pinch-to-zoom, tap-to-focus, and hybrid digital magnification
    Box(
      modifier = Modifier
        .fillMaxSize()
        .clipToBounds()
        .pointerInput(Unit) {
          detectTransformGestures { _, _, zoomChange, _ ->
            if (zoomChange != 1f && !isBusy) {
              onMultiplyZoom(zoomChange)
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
          pv.scaleX = uiState.digitalCropFactor
          pv.scaleY = uiState.digitalCropFactor
        },
        modifier = Modifier.fillMaxSize(),
      )

      // Optional 3x3 framing grid + extreme zoom center reticle
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
            val reticleColor = ElectricCyan.copy(alpha = 0.72f)
            val sw = 1.5.dp.toPx()
            drawLine(reticleColor, Offset(cx - arm, cy), Offset(cx - gap, cy), strokeWidth = sw)
            drawLine(reticleColor, Offset(cx + gap, cy), Offset(cx + arm, cy), strokeWidth = sw)
            drawLine(reticleColor, Offset(cx, cy - arm), Offset(cx, cy - gap), strokeWidth = sw)
            drawLine(reticleColor, Offset(cx, cy + gap), Offset(cx, cy + arm), strokeWidth = sw)
          }
        }
      }

      // Tap-to-focus amber optical reticle
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

    // Subtle top & bottom gradient scrims for guaranteed HUD legibility
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(140.dp)
        .align(Alignment.TopCenter)
        .background(
          Brush.verticalGradient(
            listOf(ObsidianBg.copy(alpha = 0.80f), Color.Transparent),
          ),
        ),
    )
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(280.dp)
        .align(Alignment.BottomCenter)
        .background(
          Brush.verticalGradient(
            listOf(Color.Transparent, ObsidianBg.copy(alpha = 0.92f)),
          ),
        ),
    )

    // Top HUD Bar
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .align(Alignment.TopCenter)
        .statusBarsPadding()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        color = ObsidianSurface.copy(alpha = 0.86f),
        shape = RoundedCornerShape(50),
        border = androidx.compose.foundation.BorderStroke(1.dp, OpticalBorder),
        modifier = Modifier.weight(1f, fill = false),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = ElectricCyan,
            modifier = Modifier.size(15.dp),
          )
          Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Box(
            modifier = Modifier
              .background(ElectricCyan.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
              .padding(horizontal = 6.dp, vertical = 2.dp),
          ) {
            Text(
              text = if (uiState.settings.cloudAiEnabled) {
                "NUVEM • GEMINI"
              } else {
                "PRO OFFLINE • ${uiState.aiAcceleratorLabel}"
              },
              style = MaterialTheme.typography.labelSmall,
              color = ElectricCyan,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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
            modifier = Modifier.size(20.dp),
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
            tint = if (uiState.settings.gridEnabled) ElectricCyan else Color.White,
            modifier = Modifier.size(20.dp),
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
            modifier = Modifier.size(20.dp),
          )
        }
      }
    }

    // Zoom Radar Minimap (appears at >= 12x to help aim at 30x..100x)
    AnimatedVisibility(
      visible = uiState.targetZoom >= 12f,
      enter = fadeIn() + scaleIn(),
      exit = fadeOut() + scaleOut(),
      modifier = Modifier
        .align(Alignment.TopEnd)
        .statusBarsPadding()
        .padding(top = 68.dp, end = 16.dp),
    ) {
      ZoomRadarMinimap(
        totalZoom = uiState.targetZoom,
        digitalCropFactor = uiState.digitalCropFactor,
      )
    }

    // Bottom Control Deck
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .align(Alignment.BottomCenter)
        .navigationBarsPadding()
        .padding(bottom = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // Live Zoom Telemetry Badge
      Surface(
        color = ObsidianSurface.copy(alpha = 0.88f),
        shape = RoundedCornerShape(50),
        border = androidx.compose.foundation.BorderStroke(
          1.dp,
          when (zoomMode) {
            ZoomMode.OPTICAL -> OpticalBorder
            ZoomMode.HYBRID_AI -> ElectricCyan.copy(alpha = 0.6f)
            ZoomMode.SUPER_ZOOM -> WarmAmber.copy(alpha = 0.7f)
          },
        ),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = ZoomMath.formatZoomLabel(uiState.targetZoom),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
          )
          Text(
            text = when (zoomMode) {
              ZoomMode.OPTICAL -> stringResource(R.string.zoom_mode_optical)
              ZoomMode.HYBRID_AI -> stringResource(R.string.zoom_mode_hybrid)
              ZoomMode.SUPER_ZOOM -> stringResource(R.string.zoom_mode_ultra)
            },
            style = MaterialTheme.typography.labelSmall,
            color = when (zoomMode) {
              ZoomMode.OPTICAL -> Color.White.copy(alpha = 0.8f)
              ZoomMode.HYBRID_AI -> ElectricCyan
              ZoomMode.SUPER_ZOOM -> WarmAmber
            },
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Quick Zoom Preset Chips (1x, 2x, 5x, 10x, 30x, 50x, 100x)
      LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(zoomPresets) { presetZoom ->
          val isSelected = abs(uiState.targetZoom - presetZoom) < (presetZoom * 0.08f).coerceAtLeast(0.15f)
          val accent = if (presetZoom >= 30f) WarmAmber else ElectricCyan
          Surface(
            color = if (isSelected) accent else ObsidianSurfaceHigh.copy(alpha = 0.82f),
            shape = RoundedCornerShape(50),
            border = androidx.compose.foundation.BorderStroke(
              1.dp,
              if (isSelected) accent else OpticalBorder,
            ),
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .clip(RoundedCornerShape(50))
              .clickable(enabled = !isBusy) { onZoomChanged(presetZoom) }
              .testTag("zoom_preset_${presetZoom.toInt()}x"),
          ) {
            Box(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = ZoomMath.formatZoomLabel(presetZoom),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = if (isSelected) ObsidianBg else Color.White,
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(6.dp))

      // Precision Logarithmic Zoom Slider (1x..100x)
      val sliderPos = ZoomMath.zoomToSlider(
        zoom = uiState.targetZoom,
        minZoom = uiState.hardwareMinZoom,
        maxZoom = ZoomMath.MAX_TOTAL_ZOOM,
      )
      Slider(
        value = sliderPos,
        onValueChange = { fraction ->
          if (!isBusy) {
            val newZoom = ZoomMath.sliderToZoom(
              fraction = fraction,
              minZoom = uiState.hardwareMinZoom,
              maxZoom = ZoomMath.MAX_TOTAL_ZOOM,
            )
            onZoomChanged(newZoom)
          }
        },
        colors = SliderDefaults.colors(
          thumbColor = if (uiState.targetZoom >= 30f) WarmAmber else ElectricCyan,
          activeTrackColor = if (uiState.targetZoom >= 30f) WarmAmber else ElectricCyan,
          inactiveTrackColor = OpticalBorder,
        ),
        modifier = Modifier
          .fillMaxWidth()
          .width(340.dp)
          .padding(horizontal = 24.dp)
          .testTag("zoom_slider"),
      )

      Spacer(modifier = Modifier.height(8.dp))

      // Shutter Row
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Gallery Button
        val latestPhoto = uiState.savedPhotos.firstOrNull()
        Box(
          modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(ObsidianSurfaceHigh)
            .border(1.5.dp, OpticalBorder, CircleShape)
            .clickable(enabled = !isBusy, onClick = onOpenGallery)
            .testTag("gallery_button"),
          contentAlignment = Alignment.Center,
        ) {
          if (latestPhoto != null) {
            AsyncImage(
              model = latestPhoto.uri,
              contentDescription = stringResource(R.string.cd_gallery),
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
          } else {
            Icon(
              imageVector = Icons.Outlined.PhotoLibrary,
              contentDescription = stringResource(R.string.cd_gallery),
              tint = Color.White,
            )
          }
        }

        // Pro Concentric Shutter Button
        Box(
          modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .border(
              width = 3.dp,
              color = if (uiState.targetZoom >= 30f) WarmAmber else Color.White,
              shape = CircleShape,
            )
            .clickable(enabled = !isBusy && imageCapture != null) {
              val cap = imageCapture ?: return@clickable
              onStartBurstCapture()
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
            .testTag("shutter_button"),
          contentAlignment = Alignment.Center,
        ) {
          if (isBusy) {
            CircularProgressIndicator(
              color = ElectricCyan,
              strokeWidth = 3.dp,
              modifier = Modifier.size(56.dp),
            )
          } else {
            Box(
              modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                  if (uiState.targetZoom >= 30f) WarmAmber else Color.White,
                ),
            )
          }
        }

        // Flip Camera Button
        Box(
          modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(ObsidianSurfaceHigh)
            .border(1.5.dp, OpticalBorder, CircleShape)
            .clickable(enabled = !isBusy, onClick = onToggleLens)
            .testTag("flip_camera_button"),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Outlined.Cameraswitch,
            contentDescription = stringResource(R.string.cd_flip_camera),
            tint = Color.White,
          )
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
        border = androidx.compose.foundation.BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f)),
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          CircularProgressIndicator(color = ElectricCyan, strokeWidth = 3.dp)
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

@Composable
private fun HudIconButton(
  onClick: () -> Unit,
  testTag: String,
  contentDescription: String,
  content: @Composable () -> Unit,
) {
  IconButton(
    onClick = onClick,
    modifier = Modifier
      .size(48.dp)
      .clip(CircleShape)
      .background(ObsidianSurface.copy(alpha = 0.72f))
      .testTag(testTag),
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
    border = androidx.compose.foundation.BorderStroke(1.dp, OpticalBorder),
  ) {
    Column(
      modifier = Modifier.padding(8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Canvas(modifier = Modifier.size(width = 72.dp, height = 92.dp)) {
        // Outer optical reference frame
        drawRect(
          color = Color.White.copy(alpha = 0.35f),
          style = Stroke(width = 1.dp.toPx()),
        )
        // Crosshair axes
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
        // Inner FOV rectangle scaled by 1 / digitalCropFactor
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


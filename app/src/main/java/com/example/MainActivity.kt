package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ai.configureAppCheck
import com.example.ui.ActiveScreen
import com.example.ui.ZoomCameraViewModel
import com.example.ui.camera.CameraViewfinderScreen
import com.example.ui.gallery.GalleryScreen
import com.example.ui.gallery.SettingsBottomSheet
import com.example.ui.result.EnhancementResultScreen
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.InkMuted
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.OpticalBorder
import com.example.ui.theme.ZoomIATheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configureAppCheck(this, intent)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(ObsidianBg.toArgb()),
      navigationBarStyle = SystemBarStyle.dark(ObsidianBg.toArgb()),
    )
    setContent {
      ZoomIATheme {
        ZoomIAApp()
      }
    }
  }
}

@Composable
fun ZoomIAApp(viewModel: ZoomCameraViewModel = viewModel()) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }

  var hasCameraPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED,
    )
  }
  var permissionDeniedOnce by remember { mutableStateOf(false) }

  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    hasCameraPermission = granted
    if (!granted) permissionDeniedOnce = true
  }

  // Re-check permission if user returns from System Settings
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        hasCameraPermission =
          ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  LaunchedEffect(uiState.errorMessage) {
    uiState.errorMessage?.let { msg ->
      snackbarHostState.showSnackbar(msg)
      viewModel.dismissError()
    }
  }

  LaunchedEffect(uiState.statusBanner) {
    uiState.statusBanner?.let { msg ->
      snackbarHostState.showSnackbar(msg)
    }
  }

  Scaffold(
    containerColor = ObsidianBg,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
  ) { _ ->
    if (!hasCameraPermission) {
      val activity = context as? ComponentActivity
      val permanentlyDenied = permissionDeniedOnce && activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)

      CameraPermissionGate(
        permanentlyDenied = permanentlyDenied,
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        onOpenSettings = {
          val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
          )
          context.startActivity(intent)
        },
      )
    } else {
      AnimatedContent(
        targetState = uiState.activeScreen,
        label = "screen_transition",
      ) { screen ->
        when (screen) {
          ActiveScreen.CAMERA -> {
            CameraViewfinderScreen(
              uiState = uiState,
              onHardwareZoomBoundsChanged = viewModel::updateHardwareZoomBounds,
              onSamsungExtensionStatus = viewModel::onSamsungExtensionStatus,
              onVideoQualityDetected = viewModel::onVideoQualityDetected,
              onCreateVideoOutputOptions = viewModel::createVideoOutputOptions,
              onVideoRecordingStarted = viewModel::onVideoRecordingStarted,
              onVideoRecordingProgress = viewModel::onVideoRecordingProgress,
              onVideoRecordingFinalized = viewModel::onVideoRecordingFinalized,
              onCaptureModeChanged = viewModel::setCaptureMode,
              onZoomChanged = viewModel::setTargetZoom,
              onMultiplyZoom = viewModel::multiplyZoom,
              onToggleLens = viewModel::toggleLensFacing,
              onCycleFlash = viewModel::cycleFlashMode,
              onToggleGrid = { viewModel.setGridEnabled(!uiState.settings.gridEnabled) },
              onOpenSettings = { viewModel.setShowSettingsSheet(true) },
              onOpenGallery = viewModel::openGallery,
              onStartBurstCapture = viewModel::onBurstCaptureStarted,
              onBurstCaptured = viewModel::processCapturedBurst,
              onCaptureError = viewModel::onCaptureFailed,
            )
          }

          ActiveScreen.RESULT -> {
            val result = uiState.captureResult
            if (result != null) {
              EnhancementResultScreen(
                result = result,
                processingPhase = uiState.processingPhase,
                onSelectPreset = viewModel::switchResultPreset,
                onSaveToGallery = viewModel::saveCurrentResultToGallery,
                onBackToCamera = viewModel::returnToCamera,
              )
            } else {
              LaunchedEffect(Unit) { viewModel.returnToCamera() }
            }
          }

          ActiveScreen.GALLERY -> {
            GalleryScreen(
              photos = uiState.savedPhotos,
              onDeletePhoto = viewModel::deleteGalleryPhoto,
              onBack = viewModel::returnToCamera,
            )
          }
        }
      }

      if (uiState.showSettingsSheet) {
        SettingsBottomSheet(
          settings = uiState.settings,
          aiAcceleratorLabel = uiState.aiAcceleratorLabel,
          hardwareMaxZoom = uiState.hardwareMaxZoom,
          onToggleCloudAi = viewModel::setCloudAiEnabled,
          onToggleMultiFrame = viewModel::setMultiFrameEnabled,
          onToggleAutoSave = viewModel::setAutoSaveEnabled,
          onToggleGrid = viewModel::setGridEnabled,
          onDismiss = { viewModel.setShowSettingsSheet(false) },
        )
      }
    }
  }
}

@Composable
private fun CameraPermissionGate(
  permanentlyDenied: Boolean,
  onRequestPermission: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(ObsidianBg)
      .systemBarsPadding()
      .padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      color = ObsidianSurface,
      shape = RoundedCornerShape(24.dp),
      border = BorderStroke(1.dp, OpticalBorder),
      modifier = Modifier.widthIn(max = 460.dp),
    ) {
      Column(
        modifier = Modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Box(
          modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(ObsidianSurfaceHigh)
            .border(1.dp, ElectricCyan.copy(alpha = 0.5f), CircleShape),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Outlined.CameraAlt,
            contentDescription = null,
            tint = ElectricCyan,
            modifier = Modifier.size(34.dp),
          )
        }

        Surface(
          color = ElectricCyan.copy(alpha = 0.14f),
          shape = RoundedCornerShape(50),
        ) {
          Text(
            text = "ZOOM ATÉ 100x • IA OFFLINE NO APARELHO",
            style = MaterialTheme.typography.labelSmall,
            color = ElectricCyan,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
          )
        }

        Text(
          text = stringResource(R.string.camera_permission_title),
          style = MaterialTheme.typography.headlineSmall,
          color = Color.White,
          textAlign = TextAlign.Center,
        )

        Text(
          text = stringResource(R.string.camera_permission_subtitle),
          style = MaterialTheme.typography.bodyLarge,
          color = InkMuted,
          textAlign = TextAlign.Center,
        )

        Surface(
          color = ObsidianSurfaceHigh,
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(1.dp, OpticalBorder),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            PermissionFeatureRow(
              icon = Icons.Outlined.AutoAwesome,
              text = "Real-ESRGAN+ 4x + Deconvolução Óptica 100% grátis no celular",
            )
            PermissionFeatureRow(
              icon = Icons.Outlined.Lock,
              text = "Sem conta, sem cartão e todas as fotos ficam na sua galeria",
            )
          }
        }

        if (permanentlyDenied) {
          Text(
            text = stringResource(R.string.camera_permission_denied_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
          )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Button(
          onClick = if (permanentlyDenied) onOpenSettings else onRequestPermission,
          colors = ButtonDefaults.buttonColors(
            containerColor = ElectricCyan,
            contentColor = ObsidianBg,
          ),
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("grant_camera_permission_button"),
        ) {
          Text(
            text = if (permanentlyDenied) {
              stringResource(R.string.camera_permission_settings_button)
            } else {
              stringResource(R.string.camera_permission_button)
            },
            style = MaterialTheme.typography.labelLarge,
          )
        }
      }
    }
  }
}

@Composable
private fun PermissionFeatureRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  text: String,
) {
  androidx.compose.foundation.layout.Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = ElectricCyan,
      modifier = Modifier.size(18.dp),
    )
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color = Color.White,
    )
  }
}

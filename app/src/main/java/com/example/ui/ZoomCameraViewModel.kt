package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.CapturedFrameBytes
import com.example.ai.SuperResolutionEngine
import com.example.ai.ZoomEnhancementPipeline
import com.example.data.MediaGalleryRepository
import com.example.data.UserPreferencesRepository
import com.example.data.UserSettings
import com.example.domain.CaptureResult
import com.example.domain.EnhancementPreset
import com.example.domain.FlashModeOption
import com.example.domain.SavedPhotoItem
import com.example.domain.ZoomMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ActiveScreen {
  CAMERA,
  RESULT,
  GALLERY,
}

enum class ProcessingPhase {
  IDLE,
  CAPTURING_BURST,
  ENHANCING_AI,
  SAVING,
}

data class CameraUiState(
  val activeScreen: ActiveScreen = ActiveScreen.CAMERA,
  val targetZoom: Float = 1f,
  val hardwareMinZoom: Float = 1f,
  val hardwareMaxZoom: Float = 8f,
  val hardwareZoom: Float = 1f,
  val digitalCropFactor: Float = 1f,
  val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
  val flashMode: FlashModeOption = FlashModeOption.AUTO,
  val aiAcceleratorLabel: String = "IA…",
  val aiReady: Boolean = false,
  val processingPhase: ProcessingPhase = ProcessingPhase.IDLE,
  val captureResult: CaptureResult? = null,
  val savedPhotos: List<SavedPhotoItem> = emptyList(),
  val settings: UserSettings = UserSettings(),
  val showSettingsSheet: Boolean = false,
  val errorMessage: String? = null,
  val statusBanner: String? = null,
)

class ZoomCameraViewModel(application: Application) : AndroidViewModel(application) {

  private val srEngine = SuperResolutionEngine(application)
  private val pipeline = ZoomEnhancementPipeline(srEngine)
  private val galleryRepository = MediaGalleryRepository(application)
  private val prefsRepository = UserPreferencesRepository(application)

  private val _uiState = MutableStateFlow(CameraUiState())
  val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      val accel = pipeline.warmUp()
      _uiState.update {
        it.copy(
          aiAcceleratorLabel = accel,
          aiReady = true,
        )
      }
    }
    viewModelScope.launch {
      prefsRepository.settingsFlow.collect { settings ->
        _uiState.update { it.copy(settings = settings) }
      }
    }
    refreshGallery()
  }

  fun updateHardwareZoomBounds(minZoom: Float, maxZoom: Float) {
    val safeMin = minZoom.coerceAtLeast(0.5f)
    val safeMax = maxZoom.coerceAtLeast(safeMin)
    _uiState.update { state ->
      val clampedTarget = state.targetZoom.coerceIn(safeMin, ZoomMath.MAX_TOTAL_ZOOM)
      val (hw, digital) = ZoomMath.splitZoom(clampedTarget, safeMin, safeMax)
      state.copy(
        hardwareMinZoom = safeMin,
        hardwareMaxZoom = safeMax,
        targetZoom = clampedTarget,
        hardwareZoom = hw,
        digitalCropFactor = digital,
      )
    }
  }

  fun setTargetZoom(zoom: Float) {
    _uiState.update { state ->
      val clamped = zoom.coerceIn(state.hardwareMinZoom, ZoomMath.MAX_TOTAL_ZOOM)
      val (hw, digital) = ZoomMath.splitZoom(
        clamped,
        state.hardwareMinZoom,
        state.hardwareMaxZoom,
      )
      state.copy(
        targetZoom = clamped,
        hardwareZoom = hw,
        digitalCropFactor = digital,
      )
    }
  }

  fun multiplyZoom(scaleFactor: Float) {
    val current = _uiState.value.targetZoom
    setTargetZoom(current * scaleFactor)
  }

  fun toggleLensFacing() {
    _uiState.update { state ->
      val nextFacing = if (state.lensFacing == CameraSelector.LENS_FACING_BACK) {
        CameraSelector.LENS_FACING_FRONT
      } else {
        CameraSelector.LENS_FACING_BACK
      }
      state.copy(
        lensFacing = nextFacing,
        targetZoom = 1f,
        hardwareZoom = 1f,
        digitalCropFactor = 1f,
      )
    }
  }

  fun cycleFlashMode() {
    _uiState.update { state ->
      val next = when (state.flashMode) {
        FlashModeOption.AUTO -> FlashModeOption.ON
        FlashModeOption.ON -> FlashModeOption.OFF
        FlashModeOption.OFF -> FlashModeOption.AUTO
      }
      state.copy(flashMode = next)
    }
  }

  fun setCloudAiEnabled(enabled: Boolean) {
    viewModelScope.launch { prefsRepository.setCloudAi(enabled) }
  }

  fun setGridEnabled(enabled: Boolean) {
    viewModelScope.launch { prefsRepository.setGrid(enabled) }
  }

  fun setMultiFrameEnabled(enabled: Boolean) {
    viewModelScope.launch { prefsRepository.setMultiFrame(enabled) }
  }

  fun setAutoSaveEnabled(enabled: Boolean) {
    viewModelScope.launch { prefsRepository.setAutoSave(enabled) }
  }

  fun setPreferredPreset(preset: EnhancementPreset) {
    viewModelScope.launch { prefsRepository.setPreferredPreset(preset) }
  }

  fun setShowSettingsSheet(show: Boolean) {
    _uiState.update { it.copy(showSettingsSheet = show) }
  }

  fun onBurstCaptureStarted() {
    _uiState.update {
      it.copy(
        processingPhase = ProcessingPhase.CAPTURING_BURST,
        errorMessage = null,
        statusBanner = null,
      )
    }
  }

  fun onCaptureFailed(message: String) {
    _uiState.update {
      it.copy(
        processingPhase = ProcessingPhase.IDLE,
        errorMessage = message,
      )
    }
  }

  fun processCapturedBurst(rawFrames: List<CapturedFrameBytes>) {
    if (rawFrames.isEmpty()) {
      onCaptureFailed("Nenhum quadro capturado.")
      return
    }
    val snapshot = _uiState.value
    viewModelScope.launch {
      _uiState.update { it.copy(processingPhase = ProcessingPhase.ENHANCING_AI) }
      try {
        val croppedBitmaps = mutableListOf<Bitmap>()
        for (raw in rawFrames) {
          croppedBitmaps += pipeline.decodeCenterRoi(
            frame = raw,
            digitalCropFactor = snapshot.digitalCropFactor,
          )
        }

        var result = pipeline.processBurst(
          frames = croppedBitmaps,
          totalZoom = snapshot.targetZoom,
          hardwareZoom = snapshot.hardwareZoom,
          digitalCropFactor = snapshot.digitalCropFactor,
          preset = snapshot.settings.preferredPreset,
          useCloudAi = snapshot.settings.cloudAiEnabled,
        )

        // Recycle intermediate burst crops not retained by CaptureResult
        for (i in 1 until croppedBitmaps.size) {
          val bmp = croppedBitmaps[i]
          if (bmp !== result.baseSrBitmap && bmp !== result.enhancedBitmap) {
            bmp.recycle()
          }
        }

        if (snapshot.settings.autoSaveEnabled) {
          _uiState.update { it.copy(processingPhase = ProcessingPhase.SAVING) }
          val saved = galleryRepository.saveBitmapToGallery(
            bitmap = result.enhancedBitmap,
            zoomRatio = snapshot.targetZoom,
          )
          if (saved != null) {
            result = result.copy(savedUri = saved.uri)
            _uiState.update { state ->
              state.copy(savedPhotos = listOf(saved) + state.savedPhotos)
            }
          }
        }

        _uiState.update {
          it.copy(
            processingPhase = ProcessingPhase.IDLE,
            captureResult = result,
            activeScreen = ActiveScreen.RESULT,
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: TimeoutCancellationException) {
        _uiState.update {
          it.copy(
            processingPhase = ProcessingPhase.IDLE,
            errorMessage = "Tempo esgotado na IA na nuvem (60s). Tente novamente ou ative o modo Offline em Configurações.",
          )
        }
      } catch (e: Throwable) {
        _uiState.update {
          it.copy(
            processingPhase = ProcessingPhase.IDLE,
            errorMessage = e.localizedMessage ?: "Erro ao processar foto com IA.",
          )
        }
      }
    }
  }

  fun switchResultPreset(preset: EnhancementPreset) {
    val current = _uiState.value.captureResult ?: return
    if (current.preset == preset) return
    val useCloudAi = _uiState.value.settings.cloudAiEnabled
    viewModelScope.launch {
      if (useCloudAi && current.telemetry.totalZoom >= 1.5f) {
        _uiState.update { it.copy(processingPhase = ProcessingPhase.ENHANCING_AI) }
      }
      try {
        prefsRepository.setPreferredPreset(preset)
        val updated = pipeline.reEnhanceWithPreset(current, preset, useCloudAi = useCloudAi)
        _uiState.update {
          it.copy(
            processingPhase = ProcessingPhase.IDLE,
            captureResult = updated.copy(savedUri = null),
            statusBanner = null,
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: TimeoutCancellationException) {
        _uiState.update {
          it.copy(
            processingPhase = ProcessingPhase.IDLE,
            errorMessage = "Tempo esgotado ao aplicar estilo na nuvem (60s).",
          )
        }
      } catch (e: Throwable) {
        _uiState.update {
          it.copy(
            processingPhase = ProcessingPhase.IDLE,
            errorMessage = e.localizedMessage ?: "Erro ao reprocessar foto.",
          )
        }
      }
    }
  }

  fun saveCurrentResultToGallery() {
    val current = _uiState.value.captureResult ?: return
    if (current.savedUri != null) return
    viewModelScope.launch {
      _uiState.update { it.copy(processingPhase = ProcessingPhase.SAVING) }
      val saved = galleryRepository.saveBitmapToGallery(
        bitmap = current.enhancedBitmap,
        zoomRatio = current.telemetry.totalZoom,
      )
      if (saved != null) {
        _uiState.update { state ->
          state.copy(
            processingPhase = ProcessingPhase.IDLE,
            captureResult = current.copy(savedUri = saved.uri),
            savedPhotos = listOf(saved) + state.savedPhotos,
            statusBanner = "Salvo em Pictures/ZoomIA",
          )
        }
      } else {
        _uiState.update {
          it.copy(
            processingPhase = ProcessingPhase.IDLE,
            errorMessage = "Não foi possível salvar na galeria.",
          )
        }
      }
    }
  }

  fun refreshGallery() {
    viewModelScope.launch {
      val photos = galleryRepository.loadAppPhotos()
      _uiState.update { it.copy(savedPhotos = photos) }
    }
  }

  fun deleteGalleryPhoto(uri: Uri) {
    viewModelScope.launch {
      if (galleryRepository.deletePhoto(uri)) {
        _uiState.update { state ->
          state.copy(savedPhotos = state.savedPhotos.filterNot { it.uri == uri })
        }
      }
    }
  }

  fun openGallery() {
    refreshGallery()
    _uiState.update { it.copy(activeScreen = ActiveScreen.GALLERY) }
  }

  fun returnToCamera() {
    _uiState.update {
      it.copy(
        activeScreen = ActiveScreen.CAMERA,
        errorMessage = null,
        statusBanner = null,
      )
    }
  }

  fun dismissError() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  override fun onCleared() {
    super.onCleared()
    srEngine.close()
  }
}

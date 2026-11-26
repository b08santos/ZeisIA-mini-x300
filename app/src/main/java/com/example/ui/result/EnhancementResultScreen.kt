package com.example.ui.result

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import com.example.R
import com.example.domain.CaptureResult
import com.example.domain.EnhancementPreset
import com.example.domain.ZoomMath
import com.example.ui.ProcessingPhase
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.InkMuted
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.OpticalBorder
import com.example.ui.theme.WarmAmber
import kotlin.math.roundToInt

@Composable
fun EnhancementResultScreen(
  result: CaptureResult,
  processingPhase: ProcessingPhase,
  onSelectPreset: (EnhancementPreset) -> Unit,
  onSaveToGallery: () -> Unit,
  onBackToCamera: () -> Unit,
) {
  BackHandler { onBackToCamera() }

  val context = LocalContext.current
  var splitFraction by remember { mutableFloatStateOf(0.5f) }
  var holdingOriginal by remember { mutableStateOf(false) }

  val originalImage = remember(result.originalCropBitmap) {
    result.originalCropBitmap.asImageBitmap()
  }
  val enhancedImage = remember(result.enhancedBitmap) {
    result.enhancedBitmap.asImageBitmap()
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(ObsidianBg)
      .statusBarsPadding()
      .navigationBarsPadding(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // Top App Bar
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(
        onClick = onBackToCamera,
        modifier = Modifier
          .size(48.dp)
          .testTag("result_back_button"),
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
          contentDescription = stringResource(R.string.cd_back),
          tint = Color.White,
        )
      }

      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = "Estúdio Zoom IA • ${ZoomMath.formatZoomLabel(result.telemetry.totalZoom)}",
          style = MaterialTheme.typography.titleMedium,
          color = Color.White,
        )
        val srTag = if (result.telemetry.neuralSrApplied) {
          "Real-ESRGAN 4x (${result.telemetry.acceleratorLabel})"
        } else {
          "ISP Multi-Escala"
        }
        Text(
          text = "$srTag • ${result.telemetry.outputWidth}×${result.telemetry.outputHeight} • ${result.telemetry.processingTimeMs} ms",
          style = MaterialTheme.typography.labelSmall,
          color = ElectricCyan,
        )
      }

      val savedUri = result.savedUri
      if (savedUri != null) {
        IconButton(
          onClick = {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
              type = "image/jpeg"
              putExtra(Intent.EXTRA_STREAM, savedUri)
              addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
              Intent.createChooser(shareIntent, context.getString(R.string.action_share)),
            )
          },
          modifier = Modifier
            .size(48.dp)
            .testTag("share_photo_button"),
        ) {
          Icon(
            imageVector = Icons.Outlined.Share,
            contentDescription = stringResource(R.string.action_share),
            tint = Color.White,
          )
        }
      } else {
        Spacer(modifier = Modifier.size(48.dp))
      }
    }

    // Interactive Before / After Split Viewer
    BoxWithConstraints(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .widthIn(max = 600.dp)
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .clip(RoundedCornerShape(20.dp))
        .background(ObsidianSurface)
        .border(1.dp, OpticalBorder, RoundedCornerShape(20.dp)),
    ) {
      val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
      val effectiveSplit = if (holdingOriginal) 1f else splitFraction

      // Enhanced Layer (Right side / full background)
      Image(
        bitmap = enhancedImage,
        contentDescription = stringResource(R.string.compare_after),
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
      )

      // Original Crop Layer clipped to [0 .. effectiveSplit]
      val clipShape = remember(effectiveSplit) {
        GenericShape { size, _ ->
          moveTo(0f, 0f)
          lineTo(size.width * effectiveSplit, 0f)
          lineTo(size.width * effectiveSplit, size.height)
          lineTo(0f, size.height)
          close()
        }
      }
      Image(
        bitmap = originalImage,
        contentDescription = stringResource(R.string.compare_before),
        contentScale = ContentScale.Fit,
        modifier = Modifier
          .fillMaxSize()
          .clip(clipShape),
      )

      // Draggable Divider Line & Handle
      if (!holdingOriginal) {
        val lineX = widthPx * splitFraction
        Box(
          modifier = Modifier
            .fillMaxHeight()
            .offset { IntOffset(lineX.roundToInt() - 1, 0) }
            .width(2.dp)
            .background(ElectricCyan),
        )
        val handleHalfPx = with(LocalDensity.current) { 20.dp.toPx() }
        Box(
          modifier = Modifier
            .align(Alignment.CenterStart)
            .offset { IntOffset((lineX - handleHalfPx).roundToInt(), 0) }
            .size(40.dp)
            .clip(CircleShape)
            .background(ObsidianSurface)
            .border(2.dp, ElectricCyan, CircleShape)
            .pointerInput(widthPx) {
              detectHorizontalDragGestures { change, dragAmount ->
                change.consume()
                splitFraction = (splitFraction + dragAmount / widthPx).coerceIn(0.04f, 0.96f)
              }
            },
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Outlined.Compare,
            contentDescription = null,
            tint = ElectricCyan,
            modifier = Modifier.size(18.dp),
          )
        }
      }

      // Corner labels: Original vs Com IA
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .align(Alignment.TopCenter)
          .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Surface(
          color = ObsidianBg.copy(alpha = 0.78f),
          shape = RoundedCornerShape(8.dp),
        ) {
          Text(
            text = stringResource(R.string.compare_before),
            style = MaterialTheme.typography.labelSmall,
            color = InkMuted,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
        Surface(
          color = ObsidianBg.copy(alpha = 0.78f),
          shape = RoundedCornerShape(8.dp),
          border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f)),
        ) {
          Text(
            text = stringResource(R.string.compare_after),
            style = MaterialTheme.typography.labelSmall,
            color = ElectricCyan,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
      }

      // Hold-to-compare pill at bottom of preview
      Surface(
        color = ObsidianBg.copy(alpha = 0.82f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, OpticalBorder),
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = 12.dp)
          .pointerInput(Unit) {
            detectTapGestures(
              onPress = {
                holdingOriginal = true
                tryAwaitRelease()
                holdingOriginal = false
              },
            )
          }
          .testTag("hold_compare_button"),
      ) {
        Text(
          text = stringResource(R.string.compare_hold_original),
          style = MaterialTheme.typography.labelSmall,
          color = Color.White,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
      }
    }

    // Pipeline Telemetry Summary
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 600.dp)
        .padding(horizontal = 16.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TelemetryBadge(
        text = "${result.telemetry.framesFused}x Burst",
        accent = if (result.telemetry.framesFused > 1) ElectricCyan else InkMuted,
      )
      TelemetryBadge(
        text = "Óptico ${ZoomMath.formatZoomLabel(result.telemetry.hardwareZoom)}",
        accent = Color.White,
      )
      if (result.telemetry.digitalCropFactor > 1.05f) {
        TelemetryBadge(
          text = "Crop IA ${ZoomMath.formatZoomLabel(result.telemetry.digitalCropFactor)}",
          accent = WarmAmber,
        )
      }
      TelemetryBadge(
        text = "${result.telemetry.inputWidth}→${result.telemetry.outputWidth}px",
        accent = EmeraldSuccess,
      )
    }

    // Preset Selector Chips
    LazyRow(
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      modifier = Modifier.widthIn(max = 600.dp),
    ) {
      items(EnhancementPreset.entries) { preset ->
        val selected = result.preset == preset
        Surface(
          color = if (selected) ElectricCyan else ObsidianSurfaceHigh,
          shape = RoundedCornerShape(50),
          border = BorderStroke(1.dp, if (selected) ElectricCyan else OpticalBorder),
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(50))
            .clickable { onSelectPreset(preset) }
            .testTag("preset_${preset.name.lowercase()}"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
          ) {
            Icon(
              imageVector = Icons.Outlined.AutoAwesome,
              contentDescription = null,
              tint = if (selected) ObsidianBg else ElectricCyan,
              modifier = Modifier.size(15.dp),
            )
            Text(
              text = when (preset) {
                EnhancementPreset.NATURAL -> stringResource(R.string.preset_natural)
                EnhancementPreset.ULTRA_SHARP -> stringResource(R.string.preset_ultra_sharp)
                EnhancementPreset.TEXT_DETAIL -> stringResource(R.string.preset_text_detail)
              },
              style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
              color = if (selected) ObsidianBg else Color.White,
              maxLines = 1,
              softWrap = false,
            )
          }
        }
      }
    }

    // Primary Actions (Save / Saved + New Photo)
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 600.dp)
        .padding(horizontal = 16.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlinedButton(
        onClick = onBackToCamera,
        border = BorderStroke(1.dp, OpticalBorder),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier
          .weight(0.95f)
          .height(52.dp)
          .testTag("new_photo_button"),
      ) {
        Icon(
          imageVector = Icons.Outlined.CameraAlt,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = stringResource(R.string.action_new_photo),
          maxLines = 1,
          softWrap = false,
          overflow = TextOverflow.Ellipsis,
        )
      }

      val isSaving = processingPhase == ProcessingPhase.SAVING
      val isSaved = result.savedUri != null
      Button(
        onClick = onSaveToGallery,
        enabled = !isSaving && !isSaved,
        contentPadding = PaddingValues(horizontal = 12.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = if (isSaved) EmeraldSuccess else ElectricCyan,
          contentColor = ObsidianBg,
          disabledContainerColor = if (isSaved) EmeraldSuccess.copy(alpha = 0.9f) else ObsidianSurfaceHigh,
          disabledContentColor = if (isSaved) ObsidianBg else InkMuted,
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .weight(1.35f)
          .height(52.dp)
          .testTag("save_gallery_button"),
      ) {
        if (isSaving) {
          CircularProgressIndicator(
            color = ObsidianBg,
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp),
          )
        } else {
          Icon(
            imageVector = if (isSaved) Icons.Outlined.CheckCircle else Icons.Outlined.SaveAlt,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = if (isSaved) {
            stringResource(R.string.action_saved_gallery)
          } else {
            stringResource(R.string.action_save_gallery)
          },
          maxLines = 1,
          softWrap = false,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun TelemetryBadge(text: String, accent: Color) {
  Surface(
    color = ObsidianSurfaceHigh,
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, OpticalBorder),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      color = accent,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}

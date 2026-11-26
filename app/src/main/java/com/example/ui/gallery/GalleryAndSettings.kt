package com.example.ui.gallery

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import coil.compose.AsyncImage
import com.example.R
import com.example.data.UserSettings
import com.example.domain.SavedPhotoItem
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.ui.theme.CoralError
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.InkMuted
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.OpticalBorder
import com.example.ui.theme.WarmAmber

@Composable
fun GalleryScreen(
  photos: List<SavedPhotoItem>,
  onDeletePhoto: (Uri) -> Unit,
  onBack: () -> Unit,
) {
  var inspectedPhoto by remember { mutableStateOf<SavedPhotoItem?>(null) }

  BackHandler {
    if (inspectedPhoto != null) {
      inspectedPhoto = null
    } else {
      onBack()
    }
  }

  val context = LocalContext.current

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(ObsidianBg)
      .statusBarsPadding()
      .navigationBarsPadding(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      IconButton(
        onClick = {
          if (inspectedPhoto != null) inspectedPhoto = null else onBack()
        },
        modifier = Modifier
          .size(48.dp)
          .testTag("gallery_back_button"),
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
          contentDescription = stringResource(R.string.cd_back),
          tint = Color.White,
        )
      }

      Text(
        text = inspectedPhoto?.displayName ?: stringResource(R.string.gallery_title),
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
        textAlign = TextAlign.Center,
      )

      val current = inspectedPhoto
      if (current != null) {
        Row {
          IconButton(
            onClick = {
              val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, current.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              }
              context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.action_share)),
              )
            },
            modifier = Modifier.size(48.dp),
          ) {
            Icon(
              imageVector = Icons.Outlined.Share,
              contentDescription = stringResource(R.string.action_share),
              tint = Color.White,
            )
          }
          IconButton(
            onClick = {
              onDeletePhoto(current.uri)
              inspectedPhoto = null
            },
            modifier = Modifier.size(48.dp),
          ) {
            Icon(
              imageVector = Icons.Outlined.DeleteOutline,
              contentDescription = stringResource(R.string.cd_delete),
              tint = CoralError,
            )
          }
        }
      } else {
        Spacer(modifier = Modifier.size(48.dp))
      }
    }

    val selected = inspectedPhoto
    if (selected != null) {
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .padding(16.dp),
        contentAlignment = Alignment.Center,
      ) {
        AsyncImage(
          model = selected.uri,
          contentDescription = selected.displayName,
          contentScale = ContentScale.Fit,
          modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp)),
        )
      }
    } else if (photos.isEmpty()) {
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .padding(32.dp),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Icon(
            imageVector = Icons.Outlined.PhotoCamera,
            contentDescription = null,
            tint = ElectricCyan,
            modifier = Modifier.size(56.dp),
          )
          Text(
            text = stringResource(R.string.gallery_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
          )
          Text(
            text = stringResource(R.string.gallery_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = InkMuted,
            textAlign = TextAlign.Center,
          )
        }
      }
    } else {
      LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
          .weight(1f)
          .widthIn(max = 720.dp),
      ) {
        items(photos, key = { it.uri.toString() }) { photo ->
          Box(
            modifier = Modifier
              .aspectRatio(0.85f)
              .clip(RoundedCornerShape(14.dp))
              .background(ObsidianSurfaceHigh)
              .border(1.dp, OpticalBorder, RoundedCornerShape(14.dp))
              .clickable {
                if (photo.isVideo) {
                  val playIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(photo.uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                  }
                  runCatching {
                    context.startActivity(playIntent)
                  }
                } else {
                  inspectedPhoto = photo
                }
              },
          ) {
            if (photo.isVideo) {
              val videoThumb by produceState<Bitmap?>(initialValue = null, key1 = photo.uri) {
                value = withContext(Dispatchers.IO) {
                  runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                      context.contentResolver.loadThumbnail(photo.uri, Size(360, 360), null)
                    } else {
                      val retriever = MediaMetadataRetriever()
                      try {
                        retriever.setDataSource(context, photo.uri)
                        retriever.getFrameAtTime(0L)
                      } finally {
                        retriever.release()
                      }
                    }
                  }.getOrNull()
                }
              }
              val thumb = videoThumb
              if (thumb != null) {
                Image(
                  bitmap = thumb.asImageBitmap(),
                  contentDescription = photo.displayName,
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.fillMaxSize(),
                )
              } else {
                Box(
                  modifier = Modifier.fillMaxSize(),
                  contentAlignment = Alignment.Center,
                ) {
                  Icon(
                    imageVector = Icons.Outlined.Videocam,
                    contentDescription = photo.displayName,
                    tint = InkMuted,
                    modifier = Modifier.size(32.dp),
                  )
                }
              }
              Icon(
                imageVector = Icons.Outlined.PlayCircle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                  .size(38.dp)
                  .align(Alignment.Center),
              )
            } else {
              AsyncImage(
                model = photo.uri,
                contentDescription = photo.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
              )
            }
            Surface(
              color = ObsidianBg.copy(alpha = 0.78f),
              shape = RoundedCornerShape(6.dp),
              modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp),
            ) {
              val badgeText = if (photo.isVideo) {
                val secs = (photo.durationMs / 1000L).coerceAtLeast(0L)
                String.format(Locale.US, "VÍDEO %02d:%02d", secs / 60L, secs % 60L)
              } else {
                photo.displayName
                  .removePrefix("ZoomIA_")
                  .substringAfterLast("_")
                  .removeSuffix(".jpg")
                  .replace("_", ".")
              }
              Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = if (photo.isVideo) ElectricCyan else WarmAmber,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
  settings: UserSettings,
  aiAcceleratorLabel: String,
  hardwareMaxZoom: Float,
  onToggleCloudAi: (Boolean) -> Unit,
  onToggleMultiFrame: (Boolean) -> Unit,
  onToggleAutoSave: (Boolean) -> Unit,
  onToggleGrid: (Boolean) -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = ObsidianSurface,
    contentColor = Color.White,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
        .padding(bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = stringResource(R.string.settings_title),
        style = MaterialTheme.typography.headlineSmall,
        color = Color.White,
      )

      SettingToggleRow(
        title = stringResource(R.string.settings_cloud_ai_title),
        description = stringResource(R.string.settings_cloud_ai_desc),
        checked = settings.cloudAiEnabled,
        onCheckedChange = onToggleCloudAi,
        testTag = "toggle_cloud_ai",
      )

      SettingToggleRow(
        title = stringResource(R.string.settings_multiframe_title),
        description = stringResource(R.string.settings_multiframe_desc),
        checked = settings.multiFrameEnabled,
        onCheckedChange = onToggleMultiFrame,
        testTag = "toggle_multiframe",
      )

      SettingToggleRow(
        title = stringResource(R.string.settings_autosave_title),
        description = stringResource(R.string.settings_autosave_desc),
        checked = settings.autoSaveEnabled,
        onCheckedChange = onToggleAutoSave,
        testTag = "toggle_autosave",
      )

      SettingToggleRow(
        title = stringResource(R.string.cd_grid),
        description = "Exibe linhas de regra dos terços no visor da câmera.",
        checked = settings.gridEnabled,
        onCheckedChange = onToggleGrid,
        testTag = "toggle_grid",
      )

      Surface(
        color = ObsidianSurfaceHigh,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, OpticalBorder),
      ) {
        Row(
          modifier = Modifier.padding(14.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = ElectricCyan,
            modifier = Modifier.size(22.dp),
          )
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              text = stringResource(R.string.settings_limits_title),
              style = MaterialTheme.typography.titleMedium,
              color = Color.White,
            )
            Text(
              text = "Limite nativo da lente/hardware detectado: ${String.format("%.1f", hardwareMaxZoom)}x • Acelerador IA: $aiAcceleratorLabel",
              style = MaterialTheme.typography.labelSmall,
              color = ElectricCyan,
            )
            Text(
              text = stringResource(R.string.settings_limits_desc),
              style = MaterialTheme.typography.bodyMedium,
              color = InkMuted,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SettingToggleRow(
  title: String,
  description: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  testTag: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(end = 12.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
      )
      Text(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        color = InkMuted,
      )
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors = SwitchDefaults.colors(
        checkedThumbColor = ObsidianBg,
        checkedTrackColor = ElectricCyan,
      ),
      modifier = Modifier.testTag(testTag),
    )
  }
}

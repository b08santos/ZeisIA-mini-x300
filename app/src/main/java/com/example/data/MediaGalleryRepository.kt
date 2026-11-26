package com.example.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.camera.video.MediaStoreOutputOptions
import com.example.domain.SavedPhotoItem
import com.example.domain.ZoomMath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaGalleryRepository(private val context: Context) {

  companion object {
    const val ALBUM_FOLDER = "ZoomIA"
  }

  fun createVideoOutputOptions(zoomRatio: Float): MediaStoreOutputOptions {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val zoomText = ZoomMath.formatZoomLabel(zoomRatio).replace(".", "_")
    val displayName = "ZoomIA_Video_${timestamp}_${zoomText}.mp4"

    val contentValues = ContentValues().apply {
      put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
      put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        put(
          MediaStore.Video.Media.RELATIVE_PATH,
          "${Environment.DIRECTORY_MOVIES}/$ALBUM_FOLDER",
        )
      }
    }

    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
      MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    return MediaStoreOutputOptions.Builder(context.contentResolver, collection)
      .setContentValues(contentValues)
      .build()
  }

  suspend fun loadVideoThumbnail(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.loadThumbnail(uri, Size(360, 360), null)
      } else {
        val retriever = MediaMetadataRetriever()
        try {
          retriever.setDataSource(context, uri)
          retriever.getFrameAtTime(0L)
        } finally {
          retriever.release()
        }
      }
    }.getOrNull()
  }

  suspend fun saveBitmapToGallery(
    bitmap: Bitmap,
    zoomRatio: Float,
  ): SavedPhotoItem? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val zoomText = ZoomMath.formatZoomLabel(zoomRatio).replace(".", "_")
    val displayName = "ZoomIA_${timestamp}_${zoomText}.jpg"

    val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    val contentValues = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
      put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
      put(MediaStore.Images.Media.WIDTH, bitmap.width)
      put(MediaStore.Images.Media.HEIGHT, bitmap.height)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        put(
          MediaStore.Images.Media.RELATIVE_PATH,
          "${Environment.DIRECTORY_PICTURES}/$ALBUM_FOLDER",
        )
        put(MediaStore.Images.Media.IS_PENDING, 1)
      }
    }

    val uri = resolver.insert(imageCollection, contentValues) ?: return@withContext null

    try {
      resolver.openOutputStream(uri)?.use { stream ->
        val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, 96, stream)
        check(compressed) { "Falha ao comprimir JPEG para a galeria." }
      } ?: error("Não foi possível abrir fluxo de escrita no MediaStore.")

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        contentValues.clear()
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)
      }

      SavedPhotoItem(
        uri = uri,
        displayName = displayName,
        dateAddedMillis = System.currentTimeMillis(),
        width = bitmap.width,
        height = bitmap.height,
        isVideo = false,
      )
    } catch (_: Exception) {
      runCatching { resolver.delete(uri, null, null) }
      null
    }
  }

  /**
   * Queries photos and videos saved by this app in Pictures/ZoomIA and Movies/ZoomIA.
   * On Android 10+, Scoped Storage grants read access to app-created MediaStore items
   * without requiring READ_MEDIA_IMAGES or READ_MEDIA_VIDEO.
   */
  suspend fun loadAppPhotos(): List<SavedPhotoItem> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val items = mutableListOf<SavedPhotoItem>()

    val imgCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    val imgProjection = arrayOf(
      MediaStore.Images.Media._ID,
      MediaStore.Images.Media.DISPLAY_NAME,
      MediaStore.Images.Media.DATE_ADDED,
      MediaStore.Images.Media.WIDTH,
      MediaStore.Images.Media.HEIGHT,
    )

    val imgSelection: String
    val imgArgs: Array<String>
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      imgSelection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
      imgArgs = arrayOf("%$ALBUM_FOLDER%")
    } else {
      imgSelection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
      imgArgs = arrayOf("ZoomIA_%")
    }

    runCatching {
      resolver.query(
        imgCollection,
        imgProjection,
        imgSelection,
        imgArgs,
        "${MediaStore.Images.Media.DATE_ADDED} DESC",
      )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
        val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

        while (cursor.moveToNext()) {
          val id = cursor.getLong(idCol)
          items += SavedPhotoItem(
            uri = ContentUris.withAppendedId(imgCollection, id),
            displayName = cursor.getString(nameCol) ?: "ZoomIA.jpg",
            dateAddedMillis = cursor.getLong(dateCol) * 1000L,
            width = cursor.getInt(widthCol),
            height = cursor.getInt(heightCol),
            isVideo = false,
          )
        }
      }
    }

    val vidCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
      MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    val vidProjection = arrayOf(
      MediaStore.Video.Media._ID,
      MediaStore.Video.Media.DISPLAY_NAME,
      MediaStore.Video.Media.DATE_ADDED,
      MediaStore.Video.Media.WIDTH,
      MediaStore.Video.Media.HEIGHT,
      MediaStore.Video.Media.DURATION,
    )

    val vidSelection: String
    val vidArgs: Array<String>
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      vidSelection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
      vidArgs = arrayOf("%$ALBUM_FOLDER%")
    } else {
      vidSelection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
      vidArgs = arrayOf("ZoomIA_Video_%")
    }

    runCatching {
      resolver.query(
        vidCollection,
        vidProjection,
        vidSelection,
        vidArgs,
        "${MediaStore.Video.Media.DATE_ADDED} DESC",
      )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
        val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
        val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

        while (cursor.moveToNext()) {
          val id = cursor.getLong(idCol)
          items += SavedPhotoItem(
            uri = ContentUris.withAppendedId(vidCollection, id),
            displayName = cursor.getString(nameCol) ?: "ZoomIA_Video.mp4",
            dateAddedMillis = cursor.getLong(dateCol) * 1000L,
            width = cursor.getInt(widthCol),
            height = cursor.getInt(heightCol),
            isVideo = true,
            durationMs = cursor.getLong(durCol),
          )
        }
      }
    }

    items.sortedByDescending { it.dateAddedMillis }
  }

  suspend fun deletePhoto(uri: Uri): Boolean = withContext(Dispatchers.IO) {
    runCatching {
      context.contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)
  }
}

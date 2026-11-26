package com.example.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
      )
    } catch (_: Exception) {
      runCatching { resolver.delete(uri, null, null) }
      null
    }
  }

  /**
   * Queries photos saved by this app in Pictures/ZoomIA.
   * On Android 10+, Scoped Storage grants read access to app-created MediaStore items
   * without requiring READ_MEDIA_IMAGES.
   */
  suspend fun loadAppPhotos(): List<SavedPhotoItem> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    val projection = buildList {
      add(MediaStore.Images.Media._ID)
      add(MediaStore.Images.Media.DISPLAY_NAME)
      add(MediaStore.Images.Media.DATE_ADDED)
      add(MediaStore.Images.Media.WIDTH)
      add(MediaStore.Images.Media.HEIGHT)
    }.toTypedArray()

    val selection: String
    val selectionArgs: Array<String>
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
      selectionArgs = arrayOf("%$ALBUM_FOLDER%")
    } else {
      selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
      selectionArgs = arrayOf("ZoomIA_%")
    }

    val items = mutableListOf<SavedPhotoItem>()
    runCatching {
      resolver.query(
        collection,
        projection,
        selection,
        selectionArgs,
        "${MediaStore.Images.Media.DATE_ADDED} DESC",
      )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
        val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

        while (cursor.moveToNext()) {
          val id = cursor.getLong(idCol)
          val uri = ContentUris.withAppendedId(collection, id)
          items += SavedPhotoItem(
            uri = uri,
            displayName = cursor.getString(nameCol) ?: "ZoomIA.jpg",
            dateAddedMillis = cursor.getLong(dateCol) * 1000L,
            width = cursor.getInt(widthCol),
            height = cursor.getInt(heightCol),
          )
        }
      }
    }
    items
  }

  suspend fun deletePhoto(uri: Uri): Boolean = withContext(Dispatchers.IO) {
    runCatching {
      context.contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)
  }
}

package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.EnhancementPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.zoomDataStore by preferencesDataStore(name = "zoom_ia_prefs")

data class UserSettings(
  val cloudAiEnabled: Boolean = false,
  val multiFrameEnabled: Boolean = true,
  val autoSaveEnabled: Boolean = true,
  val gridEnabled: Boolean = true,
  val preferredPreset: EnhancementPreset = EnhancementPreset.ULTRA_SHARP,
)

class UserPreferencesRepository(private val context: Context) {

  private object Keys {
    val CLOUD_AI = booleanPreferencesKey("cloud_ai_enabled")
    val MULTI_FRAME = booleanPreferencesKey("multi_frame_enabled")
    val AUTO_SAVE = booleanPreferencesKey("auto_save_enabled")
    val GRID = booleanPreferencesKey("grid_enabled")
    val PRESET = stringPreferencesKey("preferred_preset")
  }

  val settingsFlow: Flow<UserSettings> = context.zoomDataStore.data.map { prefs ->
    val presetName = prefs[Keys.PRESET] ?: EnhancementPreset.ULTRA_SHARP.name
    val preset = runCatching { EnhancementPreset.valueOf(presetName) }
      .getOrDefault(EnhancementPreset.ULTRA_SHARP)
    UserSettings(
      cloudAiEnabled = prefs[Keys.CLOUD_AI] ?: false,
      multiFrameEnabled = prefs[Keys.MULTI_FRAME] ?: true,
      autoSaveEnabled = prefs[Keys.AUTO_SAVE] ?: true,
      gridEnabled = prefs[Keys.GRID] ?: true,
      preferredPreset = preset,
    )
  }

  suspend fun setCloudAi(enabled: Boolean) {
    context.zoomDataStore.edit { it[Keys.CLOUD_AI] = enabled }
  }

  suspend fun setMultiFrame(enabled: Boolean) {
    context.zoomDataStore.edit { it[Keys.MULTI_FRAME] = enabled }
  }

  suspend fun setAutoSave(enabled: Boolean) {
    context.zoomDataStore.edit { it[Keys.AUTO_SAVE] = enabled }
  }

  suspend fun setGrid(enabled: Boolean) {
    context.zoomDataStore.edit { it[Keys.GRID] = enabled }
  }

  suspend fun setPreferredPreset(preset: EnhancementPreset) {
    context.zoomDataStore.edit { it[Keys.PRESET] = preset.name }
  }
}

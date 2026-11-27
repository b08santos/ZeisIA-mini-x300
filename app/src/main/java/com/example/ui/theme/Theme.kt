package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ZoomIADarkColorScheme = darkColorScheme(
  primary = ZeissGold,
  onPrimary = ObsidianBg,
  primaryContainer = WarmAmberContainer,
  onPrimaryContainer = InkWhite,
  secondary = WarmAmber,
  onSecondary = ObsidianBg,
  secondaryContainer = WarmAmberContainer,
  onSecondaryContainer = InkWhite,
  tertiary = ZeissGold,
  onTertiary = ObsidianBg,
  background = ObsidianBg,
  onBackground = InkWhite,
  surface = ObsidianSurface,
  onSurface = InkWhite,
  surfaceVariant = ObsidianSurfaceHigh,
  onSurfaceVariant = InkMuted,
  outline = OpticalBorder,
  error = CoralError,
  onError = ObsidianBg,
)

@Composable
fun ZoomIATheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = ZoomIADarkColorScheme,
    typography = ZoomIATypography,
    content = content,
  )
}

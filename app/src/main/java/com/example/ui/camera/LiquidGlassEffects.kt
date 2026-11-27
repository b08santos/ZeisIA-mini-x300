package com.example.ui.camera

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.ZeissGold

/**
 * Apple Liquid Glass dark-bubble effect:
 * Keeps floating buttons sleek translucent black while adding a vertical specular glass rim
 * and a bouncy bubble squash-and-pop animation on press with soft inner glass sheen.
 */
fun Modifier.liquidGlassBubble(
  shape: Shape,
  enabled: Boolean = true,
  active: Boolean = false,
  activeColor: Color = ZeissGold,
  baseBlackAlpha: Float = 0.72f,
  borderWidth: Dp = 1.dp,
  pressedScale: Float = 0.88f,
  onClick: (() -> Unit)? = null,
): Modifier = composed {
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()

  val bubbleScale by animateFloatAsState(
    targetValue = if (isPressed) pressedScale else 1.0f,
    animationSpec = spring(
      dampingRatio = 0.44f,
      stiffness = Spring.StiffnessMediumLow,
    ),
    label = "liquid_bubble_scale",
  )

  val sheenAlpha by animateFloatAsState(
    targetValue = if (isPressed) 0.17f else 0.0f,
    animationSpec = tween(
      durationMillis = if (isPressed) 90 else 250,
      easing = FastOutSlowInEasing,
    ),
    label = "liquid_bubble_sheen",
  )

  val fillColor by animateColorAsState(
    targetValue = if (active) {
      Color(0xFF0A0A0C).copy(alpha = (baseBlackAlpha + 0.12f).coerceAtMost(0.88f))
    } else {
      Color(0xFF0A0A0C).copy(alpha = baseBlackAlpha)
    },
    animationSpec = tween(220, easing = FastOutSlowInEasing),
    label = "liquid_bubble_fill",
  )

  val topRimColor by animateColorAsState(
    targetValue = if (active) activeColor.copy(alpha = 0.90f) else Color.White.copy(alpha = 0.30f),
    animationSpec = tween(220, easing = FastOutSlowInEasing),
    label = "liquid_bubble_rim_top",
  )

  val bottomRimColor by animateColorAsState(
    targetValue = if (active) activeColor.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.07f),
    animationSpec = tween(220, easing = FastOutSlowInEasing),
    label = "liquid_bubble_rim_bottom",
  )

  val baseModifier = this
    .graphicsLayer {
      scaleX = bubbleScale
      scaleY = bubbleScale
    }
    .clip(shape)
    .background(fillColor)
    .border(
      width = borderWidth,
      brush = Brush.verticalGradient(listOf(topRimColor, bottomRimColor)),
      shape = shape,
    )
    .drawWithContent {
      drawContent()
      drawRect(
        brush = Brush.verticalGradient(
          colors = listOf(
            Color.White.copy(alpha = 0.09f + sheenAlpha),
            Color.Transparent,
            Color.White.copy(alpha = sheenAlpha * 0.35f),
          ),
        ),
      )
    }

  if (onClick != null) {
    baseModifier.clickable(
      interactionSource = interactionSource,
      indication = null,
      enabled = enabled,
      onClick = onClick,
    )
  } else {
    baseModifier
  }
}

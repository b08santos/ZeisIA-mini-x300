package com.example.ui.camera

import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.LensProfile
import com.example.domain.ZoomMath
import com.example.domain.ZoomMode
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.WarmAmber
import com.example.ui.theme.ZeissGold
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class DialStop(
  val zoom: Float,
  val label: String,
)

/**
 * Vivo X300 / Zeiss-inspired zoom selector:
 * - Resting state: clean, minimal row of focal buttons (0,6x • 1x • 1,5 • 3,7 • 7,4 • 10 • 30x • 100x)
 * - Tap any number to glide smoothly without cluttering the viewfinder
 * - Drag horizontally anywhere across the numbers to smoothly unfold the semi-circular
 *   Zeiss half-moon rotary dial with inertia decay, auto-collapsing cleanly after release.
 */
@Composable
fun ZeissRotaryZoomControl(
  targetZoom: Float,
  hardwareMinZoom: Float,
  hardwareMaxZoom: Float,
  maxZoom: Float = ZoomMath.MAX_TOTAL_ZOOM,
  lensProfile: LensProfile = LensProfile.TELEPHOTO,
  enabled: Boolean,
  onZoomChanged: (Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  val haptic = LocalHapticFeedback.current
  val scope = rememberCoroutineScope()
  val currentMinZoom by rememberUpdatedState(hardwareMinZoom)
  val currentMaxZoom by rememberUpdatedState(maxZoom.coerceAtLeast(hardwareMinZoom + 0.5f))
  val currentEnabled by rememberUpdatedState(enabled)
  val onZoomChangedState by rememberUpdatedState(onZoomChanged)

  val stops = remember(hardwareMinZoom, maxZoom) {
    val allStops = buildList {
      if (hardwareMinZoom <= 0.7f) add(DialStop(0.6f, "0,6"))
      add(DialStop(1f, "1"))
      add(DialStop(1.5f, "1,5"))
      add(DialStop(3.7f, "3,7"))
      add(DialStop(7.4f, "7,4"))
      add(DialStop(10f, "10"))
      add(DialStop(20f, "20"))
      add(DialStop(50f, "50"))
      add(DialStop(100f, "100"))
    }
    allStops.filter { it.zoom <= maxZoom + 0.05f }
  }

  // Vivo X300 style clean zoom numbers along the bottom edge
  val quickButtons = remember(hardwareMinZoom, maxZoom, lensProfile) {
    val allButtons = buildList {
      if (hardwareMinZoom <= 0.7f && lensProfile != LensProfile.MACRO_TELE) {
        add(0.6f to "0,6")
      }
      add(1f to "1")
      add(1.5f to "1,5")
      add(3.7f to "3,7")
      add(7.4f to "7,4")
      add(10f to "10")
      if (maxZoom > 12f) {
        add(30f to "30x")
        add(100f to "100x")
      }
    }
    allButtons.filter { it.first <= maxZoom + 0.05f }
  }

  val animFrac = remember {
    Animatable(
      ZoomMath.zoomToSlider(
        zoom = targetZoom,
        minZoom = hardwareMinZoom,
        maxZoom = currentMaxZoom,
      ),
    )
  }
  var isInternalGestureOrAnim by remember { mutableStateOf(false) }
  var activeAnimToken by remember { mutableIntStateOf(0) }
  var isDialExpanded by remember { mutableStateOf(false) }
  var isDragging by remember { mutableStateOf(false) }

  // Collapse the half-moon dial cleanly ~2s after the user finishes scrubbing
  LaunchedEffect(isDialExpanded, isDragging, isInternalGestureOrAnim) {
    if (isDialExpanded && !isDragging && !isInternalGestureOrAnim) {
      delay(2100L)
      isDialExpanded = false
    }
  }

  // Sync external zoom changes (pinch-to-zoom, macro switch, mode switch)
  LaunchedEffect(targetZoom, hardwareMinZoom, currentMaxZoom) {
    if (!isInternalGestureOrAnim) {
      val desired = ZoomMath.zoomToSlider(
        zoom = targetZoom,
        minZoom = hardwareMinZoom,
        maxZoom = currentMaxZoom,
      )
      if (abs(desired - animFrac.value) > 0.002f) {
        animFrac.snapTo(desired)
      }
    }
  }

  val displayZoom by remember {
    derivedStateOf {
      ZoomMath.sliderToZoom(
        fraction = animFrac.value,
        minZoom = currentMinZoom,
        maxZoom = currentMaxZoom,
      )
    }
  }
  val zoomMode = ZoomMath.classifyZoomMode(displayZoom, hardwareMaxZoom)
  val accentColor = when {
    lensProfile == LensProfile.MACRO_TELE -> ZeissGold
    zoomMode == ZoomMode.OPTICAL -> ZeissGold
    zoomMode == ZoomMode.HYBRID_AI -> ZeissGold
    else -> WarmAmber
  }

  val density = LocalDensity.current
  val textPaint = remember(density) {
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      textAlign = Paint.Align.CENTER
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
  }

  // Gentle, buttery haptic feedback (~16 major steps with 52ms cooldown so fast glides stay silky)
  var lastTickBucket by remember { mutableFloatStateOf(-1f) }
  var lastHapticMs by remember { mutableLongStateOf(0L) }
  val decaySpec = remember { exponentialDecay<Float>(frictionMultiplier = 1.75f) }

  fun emitHapticIfBucketCrossed(frac: Float) {
    val bucket = (frac * 16f).roundToInt().toFloat()
    if (bucket != lastTickBucket) {
      lastTickBucket = bucket
      val now = SystemClock.uptimeMillis()
      if (now - lastHapticMs >= 52L) {
        lastHapticMs = now
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
      }
    }
  }

  fun Modifier.horizontalDialScrub(expandOnDrag: Boolean): Modifier =
    this.pointerInput(Unit) {
      val velocityTracker = VelocityTracker()
      detectHorizontalDragGestures(
        onDragStart = {
          if (!currentEnabled) return@detectHorizontalDragGestures
          velocityTracker.resetTracking()
          activeAnimToken++
          isInternalGestureOrAnim = true
          isDragging = true
          if (expandOnDrag) {
            isDialExpanded = true
          }
        },
        onDragEnd = {
          isDragging = false
          if (!currentEnabled) {
            isInternalGestureOrAnim = false
            return@detectHorizontalDragGestures
          }
          val vx = velocityTracker.calculateVelocity().x
          val sensitivity = (size.width * 1.15f).coerceAtLeast(320f)
          val initialFracVel = (-vx / sensitivity).coerceIn(-3.5f, 3.5f)
          val token = ++activeAnimToken
          scope.launch {
            try {
              if (abs(initialFracVel) > 0.15f) {
                animFrac.updateBounds(0f, 1f)
                animFrac.animateDecay(initialFracVel, decaySpec) {
                  emitHapticIfBucketCrossed(value)
                  val z = ZoomMath.sliderToZoom(value, currentMinZoom, currentMaxZoom)
                  onZoomChangedState(z)
                }
              }
            } finally {
              if (activeAnimToken == token) {
                isInternalGestureOrAnim = false
              }
            }
          }
        },
        onDragCancel = {
          isDragging = false
          isInternalGestureOrAnim = false
        },
      ) { change, dragAmount ->
        if (!currentEnabled) return@detectHorizontalDragGestures
        change.consume()
        velocityTracker.addPosition(change.uptimeMillis, change.position)
        val sensitivity = (size.width * 1.15f).coerceAtLeast(320f)
        val nextFrac = (animFrac.value - dragAmount / sensitivity).coerceIn(0f, 1f)
        emitHapticIfBucketCrossed(nextFrac)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
          animFrac.snapTo(nextFrac)
          val z = ZoomMath.sliderToZoom(nextFrac, currentMinZoom, currentMaxZoom)
          onZoomChangedState(z)
        }
      }
    }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .widthIn(max = 540.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // Semi-Circular Vivo X300 Half-Moon Dial — unfolds smoothly when dragging across zoom numbers
    val stopAngles = remember(stops, hardwareMinZoom, maxZoom) {
      val span = if (maxZoom <= 12f) 96f else 132f
      stops.map { stop ->
        stop.label to (ZoomMath.zoomToSlider(stop.zoom, hardwareMinZoom, maxZoom.coerceAtLeast(hardwareMinZoom + 0.5f)) * span)
      }
    }

    AnimatedVisibility(
      visible = isDialExpanded,
      enter = fadeIn(tween(240, easing = FastOutSlowInEasing)) +
        slideInVertically(
          animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
          ),
        ) { it / 3 } +
        expandVertically(
          animationSpec = tween(260, easing = FastOutSlowInEasing),
          expandFrom = Alignment.Bottom,
        ),
      exit = fadeOut(tween(220, easing = FastOutSlowInEasing)) +
        slideOutVertically(tween(240, easing = FastOutSlowInEasing)) { it / 3 } +
        shrinkVertically(
          animationSpec = tween(240, easing = FastOutSlowInEasing),
          shrinkTowards = Alignment.Bottom,
        ),
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
      ) {
        // Vivo X300 style clean floating yellow zoom readout directly above the center needle (no box/pill)
        Text(
          text = formatPtZoom(displayZoom),
          style = MaterialTheme.typography.titleMedium.copy(
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            shadow = androidx.compose.ui.graphics.Shadow(
              color = Color.Black.copy(alpha = 0.72f),
              offset = Offset(0f, 2f),
              blurRadius = 8f,
            ),
          ),
          color = accentColor,
          modifier = Modifier.padding(bottom = 2.dp),
        )

        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .clipToBounds()
            .horizontalDialScrub(expandOnDrag = false),
          contentAlignment = Alignment.BottomCenter,
        ) {
          val totalSpanDeg = if (currentMaxZoom <= 12f) 96f else 132f

          Canvas(modifier = Modifier.fillMaxSize()) {
            // Read Animatable inside DrawScope so gliding/inertia runs at 120Hz without recomposition
            val currentFrac = animFrac.value
            val currentAngleOffsetDeg = currentFrac * totalSpanDeg

            val centerX = size.width / 2f
            val radius = (size.width * 0.62f).coerceAtLeast(size.height * 1.85f)
            val centerY = size.height * 0.12f + radius

            // Sleek translucent smoked-black half-moon band matching the bottom zoom bar
            drawCircle(
              brush = Brush.verticalGradient(
                colorStops = arrayOf(
                  0.0f to Color(0xFF0A0A0C).copy(alpha = 0.64f),
                  0.65f to Color(0xFF0A0A0C).copy(alpha = 0.48f),
                  1.0f to Color(0xFF0A0A0C).copy(alpha = 0.18f),
                ),
                startY = centerY - radius,
                endY = size.height,
              ),
              radius = radius,
              center = Offset(centerX, centerY),
            )

            // Ultra-subtle dark glass top rim fading cleanly at left & right edges
            drawCircle(
              brush = Brush.horizontalGradient(
                colors = listOf(
                  Color.Transparent,
                  Color.White.copy(alpha = 0.18f),
                  Color.Transparent,
                ),
              ),
              radius = radius - 0.75.dp.toPx(),
              center = Offset(centerX, centerY),
              style = Stroke(width = 1.dp.toPx()),
            )

            val tickCount = 72
            for (i in 0..tickCount) {
              val stopFrac = i.toFloat() / tickCount
              val relDeg = stopFrac * totalSpanDeg - currentAngleOffsetDeg
              if (abs(relDeg) > 48f) continue

              val rad = ((-90f + relDeg) * PI / 180.0)
              val cosA = cos(rad).toFloat()
              val sinA = sin(rad).toFloat()

              val isMajorTick = i % 6 == 0
              val outerR = radius - 4.dp.toPx()
              val innerR = radius - (if (isMajorTick) 15.dp.toPx() else 10.dp.toPx())

              val fade = (1f - (abs(relDeg) / 48f)).coerceIn(0f, 1f)
              drawLine(
                color = Color.White.copy(alpha = (if (isMajorTick) 0.75f else 0.34f) * fade),
                start = Offset(centerX + cosA * innerR, centerY + sinA * innerR),
                end = Offset(centerX + cosA * outerR, centerY + sinA * outerR),
                strokeWidth = if (isMajorTick) 1.4.dp.toPx() else 0.9.dp.toPx(),
                cap = StrokeCap.Round,
              )
            }

            textPaint.textSize = with(density) { 11.5.sp.toPx() }
            for ((label, stopAngleDeg) in stopAngles) {
              val relDeg = stopAngleDeg - currentAngleOffsetDeg
              if (abs(relDeg) > 44f) continue

              val rad = ((-90f + relDeg) * PI / 180.0)
              val cosA = cos(rad).toFloat()
              val sinA = sin(rad).toFloat()

              val tickOuter = radius - 4.dp.toPx()
              val tickInner = radius - 17.dp.toPx()
              val isNearCenter = abs(relDeg) < 4.2f
              val fade = (1f - (abs(relDeg) / 44f)).coerceIn(0f, 1f)

              drawLine(
                color = if (isNearCenter) accentColor else Color.White.copy(alpha = 0.88f * fade),
                start = Offset(centerX + cosA * tickInner, centerY + sinA * tickInner),
                end = Offset(centerX + cosA * tickOuter, centerY + sinA * tickOuter),
                strokeWidth = if (isNearCenter) 2.dp.toPx() else 1.4.dp.toPx(),
                cap = StrokeCap.Round,
              )

              val labelR = radius - 31.dp.toPx()
              val lx = centerX + cosA * labelR
              val ly = centerY + sinA * labelR + textPaint.textSize * 0.36f
              textPaint.color = if (isNearCenter) {
                accentColor.toArgb()
              } else {
                Color.White.copy(alpha = 0.88f * fade).toArgb()
              }

              drawContext.canvas.nativeCanvas.save()
              drawContext.canvas.nativeCanvas.rotate(relDeg, lx, ly - textPaint.textSize * 0.36f)
              drawContext.canvas.nativeCanvas.drawText(label, lx, ly, textPaint)
              drawContext.canvas.nativeCanvas.restore()
            }

            // Vivo X300 crisp golden center needle rising subtly above the rim toward the readout
            val needleOuter = radius + 2.dp.toPx()
            val needleInner = radius - 22.dp.toPx()
            drawLine(
              color = accentColor,
              start = Offset(centerX, centerY - needleInner),
              end = Offset(centerX, centerY - needleOuter),
              strokeWidth = 2.4.dp.toPx(),
              cap = StrokeCap.Round,
            )
          }
        }
      }
    }

    // Minimalist Vivo-style horizontal zoom strip (tap to jump smoothly, drag horizontally to unfold the half-moon dial!)
    val closestStopZoom = remember(displayZoom, quickButtons) {
      quickButtons.minByOrNull { abs(it.first - displayZoom) }?.first
    }
    val matchesPreset = closestStopZoom != null &&
      abs(displayZoom - closestStopZoom) < (closestStopZoom * 0.14f).coerceAtLeast(0.16f)

    AnimatedVisibility(
      visible = !isDialExpanded && !matchesPreset,
      enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.88f, animationSpec = tween(200, easing = FastOutSlowInEasing)),
      exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.88f, animationSpec = tween(160)),
    ) {
      Surface(
        color = ObsidianBg.copy(alpha = 0.62f),
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(bottom = 4.dp),
      ) {
        Text(
          text = "${formatPtZoom(displayZoom)} • ${ZoomMath.formatFocalMmLabel(displayZoom)}",
          style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
          color = accentColor,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
      }
    }
    if (isDialExpanded || matchesPreset) {
      Spacer(modifier = Modifier.height(4.dp))
    }

    Box(
      modifier = Modifier
        .padding(horizontal = 10.dp, vertical = 2.dp)
        .liquidGlassBubble(
          shape = RoundedCornerShape(50),
          active = isDialExpanded,
          activeColor = accentColor,
          baseBlackAlpha = 0.58f,
        )
        .horizontalDialScrub(expandOnDrag = true)
        .testTag("zoom_slider"),
      contentAlignment = Alignment.Center,
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        quickButtons.forEach { (presetZoom, baseLabel) ->
          val isSelected = matchesPreset && closestStopZoom == presetZoom
          val pillInteraction = remember { MutableInteractionSource() }
          val isPillPressed by pillInteraction.collectIsPressedAsState()
          val labelColor by animateColorAsState(
            targetValue = if (isSelected) accentColor else Color.White.copy(alpha = 0.88f),
            animationSpec = tween(200, easing = FastOutSlowInEasing),
            label = "zoom_label_color",
          )
          val labelScale by animateFloatAsState(
            targetValue = when {
              isPillPressed -> 0.84f
              isSelected -> 1.09f
              else -> 1.0f
            },
            animationSpec = spring(
              dampingRatio = 0.44f,
              stiffness = Spring.StiffnessMediumLow,
            ),
            label = "zoom_label_scale",
          )
          val labelText = if (isSelected && !baseLabel.endsWith("x")) {
            "${baseLabel}x"
          } else {
            baseLabel
          }
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(50))
              .clickable(
                interactionSource = pillInteraction,
                indication = null,
                enabled = enabled,
              ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                val destFrac = ZoomMath.zoomToSlider(
                  zoom = presetZoom,
                  minZoom = currentMinZoom,
                  maxZoom = currentMaxZoom,
                )
                val token = ++activeAnimToken
                scope.launch {
                  isInternalGestureOrAnim = true
                  try {
                    animFrac.animateTo(
                      targetValue = destFrac,
                      animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
                    ) {
                      val z = ZoomMath.sliderToZoom(value, currentMinZoom, currentMaxZoom)
                      onZoomChangedState(z)
                    }
                  } finally {
                    if (activeAnimToken == token) {
                      isInternalGestureOrAnim = false
                    }
                  }
                }
              }
              .padding(horizontal = 7.dp, vertical = 11.dp)
              .graphicsLayer {
                scaleX = labelScale
                scaleY = labelScale
              }
              .testTag("zoom_preset_${presetZoom.toInt()}x"),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = labelText,
              style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 12.5.sp,
              ),
              color = labelColor,
              maxLines = 1,
              softWrap = false,
            )
          }
        }
      }
    }
  }
}

private fun formatPtZoom(zoom: Float): String {
  return when {
    zoom < 10f -> {
      val tenths = (zoom * 10f).roundToInt()
      if (tenths % 10 == 0) {
        "${tenths / 10}x"
      } else {
        String.format(Locale.forLanguageTag("pt-BR"), "%.1fx", tenths / 10f)
      }
    }
    else -> "${zoom.roundToInt()}x"
  }
}

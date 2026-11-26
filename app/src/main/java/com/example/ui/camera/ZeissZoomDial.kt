package com.example.ui.camera

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.domain.ZoomMath
import com.example.domain.ZoomMode
import com.example.ui.theme.DialGlass
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.InkMuted
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.OpticalBorder
import com.example.ui.theme.WarmAmber
import com.example.ui.theme.ZeissGold
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

private data class DialStop(
  val zoom: Float,
  val label: String,
)

/**
 * Vivo X300 / Zeiss-inspired semi-circular rotary zoom arc:
 * - Translucent curved glass dial with radial ticks & equivalent focal lengths (14mm..100x)
 * - Silky-smooth logarithmic interpolation on focal button taps + velocity decay on wheel flicks
 * - Fixed warm-gold center apex needle + live mm & magnification readout
 */
@Composable
fun ZeissRotaryZoomControl(
  targetZoom: Float,
  hardwareMinZoom: Float,
  hardwareMaxZoom: Float,
  maxZoom: Float = ZoomMath.MAX_TOTAL_ZOOM,
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
      if (hardwareMinZoom <= 0.7f) add(DialStop(0.6f, "14"))
      add(DialStop(1f, "24"))
      add(DialStop(1.45f, "35"))
      add(DialStop(2.1f, "50"))
      add(DialStop(3.55f, "85"))
      add(DialStop(7.1f, "170"))
      add(DialStop(10f, "240"))
      add(DialStop(30f, "30x"))
      add(DialStop(50f, "50x"))
      add(DialStop(100f, "100x"))
    }
    allStops.filter { it.zoom <= maxZoom + 0.05f }
  }

  val quickButtons = remember(hardwareMinZoom, maxZoom) {
    val allButtons = buildList {
      if (hardwareMinZoom <= 0.7f) add(0.6f to "14mm")
      add(1f to "1x")
      add(2.1f to "50mm")
      add(3.55f to "85mm")
      add(10f to "10x")
      add(30f to "30x")
      add(100f to "100x")
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

  // Sync external zoom changes (e.g., pinch-to-zoom or mode switches) cleanly
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

  val displayZoom = ZoomMath.sliderToZoom(
    fraction = animFrac.value,
    minZoom = hardwareMinZoom,
    maxZoom = currentMaxZoom,
  )
  val zoomMode = ZoomMath.classifyZoomMode(displayZoom, hardwareMaxZoom)
  val accentColor = when (zoomMode) {
    ZoomMode.OPTICAL -> ZeissGold
    ZoomMode.HYBRID_AI -> ElectricCyan
    ZoomMode.SUPER_ZOOM -> WarmAmber
  }

  val density = LocalDensity.current
  val textPaint = remember(density) {
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      textAlign = Paint.Align.CENTER
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
  }

  var lastTickBucket by remember { mutableFloatStateOf(-1f) }
  val decaySpec = remember { exponentialDecay<Float>(frictionMultiplier = 2.8f) }

  fun emitHapticIfBucketCrossed(frac: Float) {
    val bucket = (frac * 36f).roundToInt().toFloat()
    if (bucket != lastTickBucket) {
      lastTickBucket = bucket
      haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
  }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .widthIn(max = 540.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // Focal length & zoom pill right above the rotary needle (e.g. "52mm • 2.2x")
    Surface(
      color = ObsidianBg.copy(alpha = 0.78f),
      shape = RoundedCornerShape(50),
      border = BorderStroke(1.dp, accentColor.copy(alpha = 0.65f)),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = ZoomMath.formatFocalMmLabel(displayZoom),
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
          color = accentColor,
        )
        Text(
          text = "•",
          style = MaterialTheme.typography.labelMedium,
          color = InkMuted,
        )
        Text(
          text = ZoomMath.formatZoomLabel(displayZoom),
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
          color = Color.White,
        )
        Text(
          text = when (zoomMode) {
            ZoomMode.OPTICAL -> "Óptico"
            ZoomMode.HYBRID_AI -> "Híbrido IA"
            ZoomMode.SUPER_ZOOM -> "Super Zoom"
          },
          style = MaterialTheme.typography.labelSmall,
          color = accentColor.copy(alpha = 0.92f),
        )
      }
    }

    // Semi-Circular Zeiss Rotary Dial Canvas with momentum inertia
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(114.dp)
        .padding(horizontal = 8.dp)
        .clipToBounds()
        .testTag("zoom_slider")
        .pointerInput(Unit) {
          val velocityTracker = VelocityTracker()
          detectHorizontalDragGestures(
            onDragStart = {
              velocityTracker.resetTracking()
              activeAnimToken++
              isInternalGestureOrAnim = true
            },
            onDragEnd = {
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
              isInternalGestureOrAnim = false
            },
          ) { change, dragAmount ->
            if (!currentEnabled) return@detectHorizontalDragGestures
            change.consume()
            velocityTracker.addPosition(change.uptimeMillis, change.position)
            val sensitivity = (size.width * 1.15f).coerceAtLeast(320f)
            val nextFrac = (animFrac.value - dragAmount / sensitivity).coerceIn(0f, 1f)
            emitHapticIfBucketCrossed(nextFrac)
            scope.launch {
              animFrac.snapTo(nextFrac)
              val z = ZoomMath.sliderToZoom(nextFrac, currentMinZoom, currentMaxZoom)
              onZoomChangedState(z)
            }
          }
        },
      contentAlignment = Alignment.BottomCenter,
    ) {
      val currentFrac = animFrac.value
      val totalSpanDeg = if (currentMaxZoom <= 12f) 96f else 132f
      val currentAngleOffsetDeg = currentFrac * totalSpanDeg

      Canvas(modifier = Modifier.fillMaxSize()) {
        val centerX = size.width / 2f
        val radius = (size.width * 0.56f).coerceAtLeast(size.height * 1.45f)
        val centerY = size.height * 0.14f + radius

        drawCircle(
          brush = Brush.radialGradient(
            colors = listOf(
              DialGlass.copy(alpha = 0.88f),
              DialGlass.copy(alpha = 0.68f),
              Color.Transparent,
            ),
            center = Offset(centerX, centerY),
            radius = radius,
          ),
          radius = radius,
          center = Offset(centerX, centerY),
        )

        drawCircle(
          color = Color.White.copy(alpha = 0.22f),
          radius = radius - 1.dp.toPx(),
          center = Offset(centerX, centerY),
          style = Stroke(width = 1.dp.toPx()),
        )

        drawCircle(
          color = Color.White.copy(alpha = 0.08f),
          radius = radius - 42.dp.toPx(),
          center = Offset(centerX, centerY),
          style = Stroke(width = 1.dp.toPx()),
        )

        val tickCount = 60
        for (i in 0..tickCount) {
          val stopFrac = i.toFloat() / tickCount
          val relDeg = stopFrac * totalSpanDeg - currentAngleOffsetDeg
          if (abs(relDeg) > 54f) continue

          val rad = ((-90f + relDeg) * PI / 180.0)
          val cosA = cos(rad).toFloat()
          val sinA = sin(rad).toFloat()

          val isMajorTick = i % 5 == 0
          val outerR = radius - 6.dp.toPx()
          val innerR = radius - (if (isMajorTick) 17.dp.toPx() else 12.dp.toPx())

          val fade = (1f - (abs(relDeg) / 54f)).coerceIn(0.15f, 1f)
          drawLine(
            color = Color.White.copy(alpha = (if (isMajorTick) 0.72f else 0.34f) * fade),
            start = Offset(centerX + cosA * innerR, centerY + sinA * innerR),
            end = Offset(centerX + cosA * outerR, centerY + sinA * outerR),
            strokeWidth = if (isMajorTick) 1.6.dp.toPx() else 1.dp.toPx(),
            cap = StrokeCap.Round,
          )
        }

        textPaint.textSize = with(density) { 11.sp.toPx() }
        for (stop in stops) {
          val stopFrac = ZoomMath.zoomToSlider(
            zoom = stop.zoom,
            minZoom = hardwareMinZoom,
            maxZoom = currentMaxZoom,
          )
          val relDeg = stopFrac * totalSpanDeg - currentAngleOffsetDeg
          if (abs(relDeg) > 50f) continue

          val rad = ((-90f + relDeg) * PI / 180.0)
          val cosA = cos(rad).toFloat()
          val sinA = sin(rad).toFloat()

          val tickOuter = radius - 6.dp.toPx()
          val tickInner = radius - 22.dp.toPx()
          val isNearCenter = abs(relDeg) < 4.5f
          val fade = (1f - (abs(relDeg) / 50f)).coerceIn(0.2f, 1f)

          drawLine(
            color = if (isNearCenter) accentColor else Color.White.copy(alpha = 0.85f * fade),
            start = Offset(centerX + cosA * tickInner, centerY + sinA * tickInner),
            end = Offset(centerX + cosA * tickOuter, centerY + sinA * tickOuter),
            strokeWidth = if (isNearCenter) 2.2.dp.toPx() else 1.5.dp.toPx(),
            cap = StrokeCap.Round,
          )

          val labelR = radius - 34.dp.toPx()
          val lx = centerX + cosA * labelR
          val ly = centerY + sinA * labelR + textPaint.textSize * 0.36f
          textPaint.color = if (isNearCenter) {
            accentColor.toArgb()
          } else {
            Color.White.copy(alpha = 0.86f * fade).toArgb()
          }

          drawContext.canvas.nativeCanvas.save()
          drawContext.canvas.nativeCanvas.rotate(relDeg, lx, ly - textPaint.textSize * 0.36f)
          drawContext.canvas.nativeCanvas.drawText(stop.label, lx, ly, textPaint)
          drawContext.canvas.nativeCanvas.restore()
        }

        val needleOuter = radius - 2.dp.toPx()
        val needleInner = radius - 26.dp.toPx()
        drawLine(
          color = accentColor,
          start = Offset(centerX, centerY - needleInner),
          end = Offset(centerX, centerY - needleOuter),
          strokeWidth = 2.8.dp.toPx(),
          cap = StrokeCap.Round,
        )
      }
    }

    // Quick-tap focal pills with silky animated zoom glide
    LazyRow(
      contentPadding = PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp),
    ) {
      items(quickButtons) { (presetZoom, label) ->
        val isSelected = abs(displayZoom - presetZoom) < (presetZoom * 0.12f).coerceAtLeast(0.15f)
        val pillAccent = if (presetZoom >= 30f) WarmAmber else ZeissGold
        Surface(
          color = if (isSelected) pillAccent else ObsidianSurfaceHigh.copy(alpha = 0.72f),
          shape = RoundedCornerShape(50),
          border = BorderStroke(1.dp, if (isSelected) pillAccent else OpticalBorder),
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled) {
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
            .testTag("zoom_preset_${presetZoom.toInt()}x"),
        ) {
          Box(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = label,
              style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
              color = if (isSelected) ObsidianBg else Color.White,
            )
          }
        }
      }
    }
  }
}

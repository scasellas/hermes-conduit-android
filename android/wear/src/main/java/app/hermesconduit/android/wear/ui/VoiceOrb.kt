package app.hermesconduit.android.wear.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Icon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Conduit's palette on the watch (WatchTheme.swift). */
object WatchColors {
    /** The phone's accent, while it listens. */
    val aura = Color(0xFF6194FA)
    /** Violet while Hermes speaks. */
    val voice = Color(0xFF9E73FA)
    /** Amber when the call needs the user. */
    val attention = Color(0xFFFFA83D)
    val muted = Color(0xFF8E8E93)
}

enum class OrbMood { READY, WORKING, LISTENING, MUTED, SPEAKING, ATTENTION, DONE }

/**
 * The call's orb, the counterpart of the iOS liquid orb: a glowing blob whose
 * edge flows like liquid — slow breathing while it listens, livelier waves
 * and ripples while Hermes speaks, a turning swirl while it works.
 */
@Composable
fun VoiceOrb(mood: OrbMood, size: Dp = 88.dp, modifier: Modifier = Modifier) {
    val tint by animateColorAsState(
        when (mood) {
            OrbMood.READY, OrbMood.WORKING, OrbMood.LISTENING -> WatchColors.aura
            OrbMood.MUTED, OrbMood.DONE -> WatchColors.muted
            OrbMood.SPEAKING -> WatchColors.voice
            OrbMood.ATTENTION -> WatchColors.attention
        },
        tween(400),
        label = "tint",
    )
    val wobble by animateFloatAsState(
        when (mood) {
            OrbMood.SPEAKING -> 0.075f
            OrbMood.LISTENING -> 0.04f
            OrbMood.WORKING -> 0.05f
            OrbMood.READY -> 0.025f
            else -> 0.008f
        },
        tween(500),
        label = "wobble",
    )
    var time by remember { mutableFloatStateOf(0f) }
    val animates = mood != OrbMood.DONE && mood != OrbMood.MUTED
    LaunchedEffect(animates) {
        if (!animates) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - start) / 1_000_000_000f }
    }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val base = this.size.minDimension / 2 * 0.78f
            val breath = if (mood == OrbMood.LISTENING) 1f + 0.035f * sin(time * 2 * PI.toFloat() / 3.2f) else 1f

            // Ripples while Hermes speaks.
            if (mood == OrbMood.SPEAKING) {
                for (ring in 0 until 3) {
                    val progress = ((time / 1.8f) + ring / 3f) % 1f
                    drawCircle(tint.copy(alpha = 0.5f * (1 - progress)), radius = base * (1 + 0.28f * progress), center = center, style = Stroke(2.dp.toPx()))
                }
            }
            // Glow.
            drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.45f), Color.Transparent), center, base * 1.3f), radius = base * 1.3f, center = center)

            // The liquid body: a closed path whose radius flows with layered waves.
            val path = Path()
            val steps = 72
            for (i in 0..steps) {
                val theta = i * 2 * PI.toFloat() / steps
                val r = base * breath * (
                    1f +
                        wobble * sin(3 * theta + time * 1.7f) +
                        wobble * 0.6f * sin(5 * theta - time * 2.3f) +
                        wobble * 0.35f * cos(2 * theta + time * 0.9f)
                    )
                val p = Offset(center.x + r * cos(theta), center.y + r * sin(theta))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path.close()
            val swirl = time * 0.8f
            drawPath(
                path,
                Brush.linearGradient(
                    listOf(tint.copy(alpha = 0.95f), tint.copy(alpha = 0.55f), lerp(tint, Color.White, 0.25f)),
                    start = Offset(center.x + base * cos(swirl), center.y + base * sin(swirl)),
                    end = Offset(center.x - base * cos(swirl), center.y - base * sin(swirl)),
                ),
            )
            drawPath(path, Color.White.copy(alpha = 0.18f), style = Stroke(1.dp.toPx()))
            // A highlight that drifts over the surface.
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), Offset(center.x - base * 0.35f + base * 0.1f * sin(time), center.y - base * 0.4f), base * 0.55f),
                radius = base * 0.55f,
                center = Offset(center.x - base * 0.35f + base * 0.1f * sin(time), center.y - base * 0.4f),
            )
            // Working: a turning arc.
            if (mood == OrbMood.WORKING) {
                drawArc(Color.White.copy(alpha = 0.9f), startAngle = (time * 240f) % 360f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(center.x - base * 0.45f, center.y - base * 0.45f), size = androidx.compose.ui.geometry.Size(base * 0.9f, base * 0.9f),
                    style = Stroke(3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
        }
        if (mood != OrbMood.WORKING) {
            Icon(symbol(mood), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.34f))
        }
    }
}

private fun lerp(a: Color, b: Color, f: Float) = Color(
    red = a.red + (b.red - a.red) * f,
    green = a.green + (b.green - a.green) * f,
    blue = a.blue + (b.blue - a.blue) * f,
    alpha = a.alpha,
)

private fun symbol(mood: OrbMood): ImageVector = when (mood) {
    OrbMood.READY, OrbMood.LISTENING -> Icons.Default.Mic
    OrbMood.WORKING -> Icons.Default.MoreHoriz
    OrbMood.MUTED -> Icons.Default.MicOff
    OrbMood.SPEAKING -> Icons.Default.GraphicEq
    OrbMood.ATTENTION -> Icons.Default.TouchApp
    OrbMood.DONE -> Icons.Default.Check
}

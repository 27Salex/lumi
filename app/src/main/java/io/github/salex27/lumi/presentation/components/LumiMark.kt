package io.github.salex27.lumi.presentation.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.salex27.lumi.presentation.theme.BrandMagenta
import io.github.salex27.lumi.presentation.theme.BrandSky
import io.github.salex27.lumi.presentation.theme.BrandViolet
import kotlinx.coroutines.delay

/** Lumi's state: drives the logo and edge glow animation. */
enum class LumiState { IDLE, LISTENING, THINKING, SPEAKING, SUCCESS }

/**
 * Lumi's logo (v3.2, "Gaze"): two soft overlapping circles — sky blue and pastel magenta — with the overlap in violet
 * and two blinking eyes. Personality without being a mascot. NO spinning: breathing, drifting and springs.
 * - IDLE: breathes, blinks every few seconds and looks from side to side.
 * - LISTENING: the circles move apart with your voice ([level] 0..1), halo, and the eyes open and look at you.
 * - THINKING: the circles pulse and the eyes look up to one side, as if thinking.
 * - SPEAKING: a soft pulse; the eyes bounce a little while talking.
 * - SUCCESS: smiling eyes (^ ^).
 */
@Composable
fun LumiMark(state: LumiState, modifier: Modifier = Modifier, size: Dp = 40.dp, level: Float = 0f) {
    val t = rememberInfiniteTransition(label = "lumi")
    val breathe by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(
            tween(
                when (state) {
                    LumiState.SPEAKING -> 480
                    LumiState.THINKING -> 700
                    else -> 3_200
                },
                easing = FastOutSlowInEasing
            ),
            RepeatMode.Reverse
        ),
        label = "breathe"
    )
    val voice by animateFloatAsState(level.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessMediumLow), label = "voice")
    // Blink: open for most of the cycle, a quick close at the end
    val blink by t.animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes {
            durationMillis = 3_800
            1f at 0
            1f at 3_500
            0.08f at 3_590
            1f at 3_700
        }),
        label = "blink"
    )
    // Slow sideways glance (idle)
    val glance by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(4_200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glance")
    val lookX by animateFloatAsState(
        when (state) {
            LumiState.IDLE -> 0.016f * glance
            LumiState.THINKING -> 0.022f
            else -> 0f
        },
        spring(stiffness = Spring.StiffnessLow), label = "lookX"
    )
    val lookY by animateFloatAsState(
        when (state) {
            LumiState.THINKING -> -0.028f
            LumiState.SPEAKING -> -0.006f * breathe
            else -> 0f
        },
        spring(stiffness = Spring.StiffnessLow), label = "lookY"
    )
    val eyeOpen by animateFloatAsState(
        when (state) {
            LumiState.LISTENING -> 1.15f + 0.1f * voice
            LumiState.SUCCESS -> 0f // drawn as smiling arcs
            else -> 1f
        },
        spring(stiffness = Spring.StiffnessMedium), label = "eyeOpen"
    )

    // Distance between the centers (fraction of the side): 0 = merged into one
    val targetSeparation = when (state) {
        LumiState.SUCCESS -> 0f
        LumiState.LISTENING -> 0.13f + 0.06f * voice
        LumiState.THINKING -> 0.05f + 0.10f * breathe
        LumiState.SPEAKING -> 0.12f + 0.02f * breathe
        LumiState.IDLE -> 0.125f + 0.01f * breathe
    }
    val separation by animateFloatAsState(targetSeparation, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow), label = "sep")
    val scale by animateFloatAsState(
        when (state) {
            LumiState.IDLE -> 0.97f + 0.03f * breathe
            LumiState.SPEAKING -> 0.96f + 0.05f * breathe
            LumiState.LISTENING -> 1f + 0.06f * voice
            LumiState.THINKING -> 0.98f + 0.02f * breathe
            LumiState.SUCCESS -> 1.04f
        },
        spring(stiffness = Spring.StiffnessMediumLow), label = "scale"
    )
    val halo by animateFloatAsState(
        when (state) {
            LumiState.LISTENING -> 0.25f + 0.45f * voice
            LumiState.THINKING -> 0.18f + 0.12f * breathe
            LumiState.SPEAKING -> 0.15f + 0.15f * breathe
            else -> 0f
        },
        tween(250), label = "halo"
    )

    Canvas(modifier.size(size)) {
        val s = this.size.minDimension * scale
        val r = s * 0.30f
        val d = s * separation
        val a = center + Offset(-d, 0f)
        val b = center + Offset(d, 0f)

        if (halo > 0f) drawCircle(
            Brush.radialGradient(listOf(BrandViolet.copy(alpha = halo), Color.Transparent), center, s * 0.62f),
            s * 0.62f, center
        )
        drawCircle(Brush.radialGradient(listOf(BrandSky.copy(alpha = 0.55f).compositeOverWhite(), BrandSky), a - Offset(r * 0.35f, r * 0.35f), r * 1.6f), r, a)
        drawCircle(Brush.radialGradient(listOf(BrandMagenta.copy(alpha = 0.55f).compositeOverWhite(), BrandMagenta), b - Offset(r * 0.35f, r * 0.35f), r * 1.6f), r, b)
        // Overlap (lens) in violet: circle B clipped by A
        val clip = Path().apply { addOval(Rect(a, r)) }
        clipPath(clip) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFB9B6FB), Color(0xFF8E9AF6)), center, r * 0.9f), r, b)
        }
        drawEyes(center + Offset(s * lookX, s * lookY), s, if (state == LumiState.SUCCESS) 0f else eyeOpen * blink, happy = state == LumiState.SUCCESS)
    }
}

/** Two oval eyes in the overlap; [open] 0..1+ scales their height (blinking). [happy] = "^ ^" arcs. */
private fun DrawScope.drawEyes(c: Offset, s: Float, open: Float, happy: Boolean) {
    val gap = s * 0.045f
    val rx = s * 0.024f
    for (dx in listOf(-gap, gap)) {
        val e = c + Offset(dx, 0f)
        if (happy) {
            drawArc(
                Color.White, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(e.x - rx * 1.2f, e.y - rx * 0.6f), size = Size(rx * 2.4f, rx * 2.4f),
                style = Stroke(width = s * 0.026f, cap = StrokeCap.Round)
            )
        } else {
            val ry = s * 0.052f * open.coerceAtLeast(0.1f)
            drawOval(Color.White, topLeft = Offset(e.x - rx, e.y - ry), size = Size(rx * 2, ry * 2))
        }
    }
}

/** Blends a translucent color over white (soft reflection of the circles, without real transparency). */
private fun Color.compositeOverWhite(): Color = Color(
    red = red * alpha + (1 - alpha), green = green * alpha + (1 - alpha), blue = blue * alpha + (1 - alpha), alpha = 1f
)

/**
 * Subtle screen edge glow (Apple Intelligence style) while Lumi listens or thinks.
 * A brand gradient rotates around the rounded edge of the screen.
 */
@Composable
fun ScreenEdgeGlow(state: LumiState, modifier: Modifier = Modifier, cornerRadius: Dp = 40.dp) {
    val active = state == LumiState.LISTENING || state == LumiState.THINKING
    val rotation by rememberInfiniteTransition(label = "edge").animateFloat(
        0f, 360f, infiniteRepeatable(tween(if (state == LumiState.THINKING) 1_800 else 4_500, easing = LinearEasing)), label = "edgeRot"
    )
    val strength by animateFloatAsState(if (active) 1f else 0f, tween(450), label = "edgeStrength")
    if (strength == 0f) return

    val colors = listOf(BrandSky, BrandViolet, BrandMagenta, BrandSky)
    Canvas(modifier.fillMaxSize()) {
        listOf(28.dp to 0.12f, 12.dp to 0.28f, 3.dp to 0.85f).forEach { (width, alpha) ->
            val w = width.toPx()
            drawRoundRect(
                brush = RotatingSweep(colors.map { it.copy(alpha = alpha * strength) }, rotation),
                cornerRadius = CornerRadius(cornerRadius.toPx()),
                style = Stroke(width = w),
                topLeft = Offset(w / 2, w / 2),
                size = Size(size.width - w, size.height - w)
            )
        }
    }
}

/**
 * Glow on the top edge only (compact mode: the pill drops from the top). The brand colors drift side to side and fade
 * downwards. Visible while listening, thinking or speaking.
 */
@Composable
fun TopEdgeGlow(state: LumiState, modifier: Modifier = Modifier, height: Dp = 180.dp) {
    val active = state == LumiState.LISTENING || state == LumiState.THINKING || state == LumiState.SPEAKING
    val shift by rememberInfiniteTransition(label = "topGlow").animateFloat(
        0f, 1f, infiniteRepeatable(tween(if (state == LumiState.THINKING) 1_600 else 3_800, easing = LinearEasing)), label = "topShift"
    )
    val strength by animateFloatAsState(if (active) 1f else 0f, tween(450), label = "topStrength")
    if (strength == 0f) return

    Canvas(
        modifier.fillMaxWidth().height(height)
            .graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen)
    ) {
        val w = size.width
        val offset = shift * w * 2
        drawRect(
            Brush.horizontalGradient(
                listOf(BrandSky, BrandViolet, BrandMagenta, BrandSky, BrandViolet).map { it.copy(alpha = 0.55f * strength) },
                startX = -offset, endX = 2 * w - offset, tileMode = androidx.compose.ui.graphics.TileMode.Repeated
            )
        )
        // Mask: strong at the top, transparent at the bottom
        drawRect(
            Brush.verticalGradient(listOf(Color.Black, Color.Black.copy(alpha = 0.35f), Color.Transparent)),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
        )
    }
}

/** Sweep gradient with an animatable angle (Brush.sweepGradient has no rotation). */
private class RotatingSweep(private val colors: List<Color>, private val degrees: Float) : ShaderBrush() {
    override fun createShader(size: Size): Shader = SweepGradientShader(size.center, colors).apply {
        setLocalMatrix(android.graphics.Matrix().apply { setRotate(degrees, size.center.x, size.center.y) })
    }
    override fun equals(other: Any?) = other is RotatingSweep && other.colors == colors && other.degrees == degrees
    override fun hashCode() = 31 * colors.hashCode() + degrees.hashCode()
}

/** Text that appears word by word. With [animate] = false it is shown whole (e.g. a cached summary). */
@Composable
fun TypewriterText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    color: Color = Color.Unspecified,
    onFinished: () -> Unit = {}
) {
    val words = remember(text) { text.split(Regex("(?<=\\s)")) }
    var shown by remember(text) { mutableIntStateOf(if (animate) 0 else words.size) }
    LaunchedEffect(text, animate) {
        if (!animate) { shown = words.size; onFinished(); return@LaunchedEffect }
        while (shown < words.size) {
            delay(if (words[shown].contains('\n')) 80 else 32)
            shown++
        }
        onFinished()
    }
    Text(text = words.take(shown).joinToString(""), style = style, color = color, modifier = modifier)
}

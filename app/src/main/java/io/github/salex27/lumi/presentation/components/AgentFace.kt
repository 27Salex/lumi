package io.github.salex27.lumi.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.salex27.lumi.domain.orbit.AgentFaceStyle
import io.github.salex27.lumi.domain.orbit.AgentPalette
import io.github.salex27.lumi.presentation.theme.Lumi
import io.github.salex27.lumi.presentation.theme.color

/**
 * An Orbit agent's face: a simple shape in the agent's colour with two eyes, in the spirit of Lumi's mark (no emojis,
 * no gradients). The name is always shown next to it, so colour never carries meaning alone.
 */
@Composable
fun AgentFace(style: AgentFaceStyle, palette: AgentPalette, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val fill = palette.color(Lumi.colors.isDark)
    val eye = Color.White
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val r = w / 2f
        val c = Offset(r, r)
        val eyeR = w * 0.075f
        val eyeY = r * 0.92f
        val eyeDx = w * 0.15f
        var eyeColor = eye
        when (style) {
            AgentFaceStyle.DOTS -> drawCircle(fill, r, c)
            AgentFaceStyle.RING -> {
                drawCircle(fill.copy(alpha = 0.18f), r, c)
                drawCircle(fill, r - w * 0.06f, c, style = Stroke(w * 0.12f))
                eyeColor = fill
            }
            AgentFaceStyle.SQUARE -> drawRoundRect(fill, Offset(w * 0.04f, w * 0.04f), Size(w * 0.92f, w * 0.92f), CornerRadius(w * 0.28f))
            AgentFaceStyle.SPARK -> {
                // A soft four-point star
                val p = Path().apply {
                    val k = w * 0.16f
                    moveTo(r, 0f)
                    quadraticTo(r + k, r - k, w, r)
                    quadraticTo(r + k, r + k, r, w)
                    quadraticTo(r - k, r + k, 0f, r)
                    quadraticTo(r - k, r - k, r, 0f)
                    close()
                }
                drawPath(p, fill)
            }
        }
        drawCircle(eyeColor, eyeR, Offset(r - eyeDx, eyeY))
        drawCircle(eyeColor, eyeR, Offset(r + eyeDx, eyeY))
    }
}

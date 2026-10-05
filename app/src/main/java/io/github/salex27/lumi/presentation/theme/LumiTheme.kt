package io.github.salex27.lumi.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.salex27.lumi.R
import io.github.salex27.lumi.domain.model.TaskCategory

/**
 * Lumi 3.0 design system — sober and clean (Manus × Revolut × Apple):
 * flat surfaces, thin borders, a single accent color per mode and the Inter typeface.
 * The gradient is reserved for Lumi's brand (logo and edge glow), never for text or buttons.
 */
@Immutable
data class LumiColors(
    val isDark: Boolean,
    val background: Color,
    /** Cards and groups. */
    val surface: Color,
    /** Floating elements (smart bar, sheets). */
    val elevated: Color,
    /** Fields, chips and subtle backgrounds. */
    val muted: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** Fill of primary buttons and selected items. */
    val accent: Color,
    /** Accent for text/icons on the background (more contrast than [accent] in light mode). */
    val accentText: Color,
    val accentContainer: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    /** Brand colors (logo and edge glow): the same in both modes so Lumi is recognizable. */
    val brandA: Color = BrandSky,
    val brandB: Color = BrandMagenta
)

val BrandSky = Color(0xFF38BDF8)
val BrandMagenta = Color(0xFFF0A6CA)
val BrandViolet = Color(0xFFA5B4FC)

val LightLumiColors = LumiColors(
    isDark = false,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF6F7F9),
    elevated = Color(0xFFFFFFFF),
    muted = Color(0xFFEFF1F4),
    outline = Color(0xFFE4E7EC),
    textPrimary = Color(0xFF0B0D12),
    textSecondary = Color(0xFF5B6472),
    textTertiary = Color(0xFF98A1AE),
    accent = Color(0xFF38BDF8),
    accentText = Color(0xFF0284C7),
    accentContainer = Color(0xFFE0F2FE),
    onAccent = Color(0xFF0B0D12),
    success = Color(0xFF16A34A),
    warning = Color(0xFFD97706),
    danger = Color(0xFFDC2626)
)

val DarkLumiColors = LumiColors(
    isDark = true,
    background = Color(0xFF0A0A0C),
    surface = Color(0xFF141418),
    elevated = Color(0xFF1B1B21),
    muted = Color(0xFF232329),
    outline = Color(0xFF2A2A31),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFFA1A1AA),
    textTertiary = Color(0xFF6B6B75),
    accent = Color(0xFFF5A9D0),
    accentText = Color(0xFFF5A9D0),
    accentContainer = Color(0xFF3A2331),
    onAccent = Color(0xFF1A0B13),
    success = Color(0xFF4ADE80),
    warning = Color(0xFFFBBF24),
    danger = Color(0xFFF87171)
)

val LocalLumiColors = staticCompositionLocalOf { LightLumiColors }

/** Shortcut: `Lumi.colors.accent`. */
object Lumi {
    val colors: LumiColors
        @Composable @ReadOnlyComposable get() = LocalLumiColors.current
}

// ── Typography ──────────────────────────────────────────────────────────────

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

private fun style(size: Int, weight: FontWeight, line: Int, tracking: Double = 0.0) =
    TextStyle(fontFamily = Inter, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

val LumiTypography = Typography(
    displaySmall = style(34, FontWeight.Bold, 40, -0.6),      // saludo / cifra grande
    headlineMedium = style(26, FontWeight.Bold, 32, -0.4),    // screen titles
    titleLarge = style(20, FontWeight.SemiBold, 26, -0.2),
    titleMedium = style(16, FontWeight.SemiBold, 22),
    bodyLarge = style(16, FontWeight.Normal, 24),
    bodyMedium = style(14, FontWeight.Normal, 20),
    bodySmall = style(13, FontWeight.Normal, 18),
    labelLarge = style(14, FontWeight.SemiBold, 20),
    labelMedium = style(12, FontWeight.Medium, 16),
    labelSmall = style(11, FontWeight.SemiBold, 14, 0.6)      // uppercase section headers
)

// ── Categories: icon + color (light/dark steps of the validated reference palette) ──

fun TaskCategory.icon(): ImageVector = when (this) {
    TaskCategory.WORK -> Icons.Outlined.WorkOutline
    TaskCategory.PERSONAL -> Icons.Outlined.PersonOutline
    TaskCategory.STUDY -> Icons.Outlined.School
    TaskCategory.HEALTH -> Icons.Outlined.FavoriteBorder
    TaskCategory.OTHER -> Icons.Outlined.Inbox
}

fun TaskCategory.color(dark: Boolean): Color = when (this) {
    TaskCategory.WORK -> if (dark) Color(0xFF3987E5) else Color(0xFF2A78D6)
    TaskCategory.PERSONAL -> if (dark) Color(0xFF199E70) else Color(0xFF1BAF7A)
    TaskCategory.STUDY -> if (dark) Color(0xFF9085E9) else Color(0xFF4A3AA7)
    TaskCategory.HEALTH -> if (dark) Color(0xFFD95926) else Color(0xFFEB6834)
    TaskCategory.OTHER -> if (dark) Color(0xFF898781) else Color(0xFF898781)
}

/** Orbit agent colours: same validated steps as the categories, plus magenta and teal (fills; names go in ink). */
fun io.github.salex27.lumi.domain.orbit.AgentPalette.color(dark: Boolean): Color = when (this) {
    io.github.salex27.lumi.domain.orbit.AgentPalette.SKY -> if (dark) Color(0xFF3987E5) else Color(0xFF2A78D6)
    io.github.salex27.lumi.domain.orbit.AgentPalette.MAGENTA -> if (dark) Color(0xFFD16BB0) else Color(0xFFB8488F)
    io.github.salex27.lumi.domain.orbit.AgentPalette.ORANGE -> if (dark) Color(0xFFD95926) else Color(0xFFEB6834)
    io.github.salex27.lumi.domain.orbit.AgentPalette.GREEN -> if (dark) Color(0xFF199E70) else Color(0xFF1BAF7A)
    io.github.salex27.lumi.domain.orbit.AgentPalette.VIOLET -> if (dark) Color(0xFF9085E9) else Color(0xFF4A3AA7)
    io.github.salex27.lumi.domain.orbit.AgentPalette.TEAL -> if (dark) Color(0xFF2AA3B0) else Color(0xFF16808C)
}

// ── Theme ───────────────────────────────────────────────────────────────────

/** @param themeMode "SYSTEM" | "LIGHT" | "DARK" (Settings → Appearance). */
@Composable
fun LumiAppTheme(themeMode: String = "SYSTEM", content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme()
    }
    val c = if (dark) DarkLumiColors else LightLumiColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentContainer, onPrimaryContainer = c.textPrimary,
            secondary = c.accent, background = c.background, onBackground = c.textPrimary,
            surface = c.background, onSurface = c.textPrimary, surfaceVariant = c.muted, onSurfaceVariant = c.textSecondary,
            surfaceContainer = c.surface, surfaceContainerHigh = c.elevated, surfaceContainerHighest = c.muted,
            surfaceContainerLow = c.surface, outline = c.outline, outlineVariant = c.outline, error = c.danger
        )
    } else {
        lightColorScheme(
            primary = c.accentText, onPrimary = Color.White, primaryContainer = c.accentContainer, onPrimaryContainer = c.textPrimary,
            secondary = c.accentText, background = c.background, onBackground = c.textPrimary,
            surface = c.background, onSurface = c.textPrimary, surfaceVariant = c.muted, onSurfaceVariant = c.textSecondary,
            surfaceContainer = c.surface, surfaceContainerHigh = c.elevated, surfaceContainerHighest = c.muted,
            surfaceContainerLow = c.surface, outline = c.outline, outlineVariant = c.outline, error = c.danger
        )
    }
    CompositionLocalProvider(LocalLumiColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = LumiTypography, content = content)
    }
}

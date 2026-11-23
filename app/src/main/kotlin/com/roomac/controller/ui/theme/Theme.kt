package com.roomac.controller.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/*
 * RoomAC design tokens. One brand colour (a calm blue), neutral surfaces, and fixed semantic colours
 * for success / warning / error, so every screen draws from the same small palette. Screens should use
 * these tokens (MaterialTheme.colorScheme, MaterialTheme.shapes, RoomAcTheme.status, Motion) rather
 * than literal colours, radii or durations.
 */

private val Brand = Color(0xFF1767B0)

private val LightColorScheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E7F7),
    onPrimaryContainer = Color(0xFF0A2A4A),
    secondary = Color(0xFF51606F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E9F0),
    onSecondaryContainer = Color(0xFF1B2733),
    tertiary = Color(0xFF51606F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE3E9F0),
    onTertiaryContainer = Color(0xFF1B2733),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF141A21),
    surface = Color(0xFFF5F7FA),
    onSurface = Color(0xFF141A21),
    surfaceVariant = Color(0xFFE6EBF1),
    onSurfaceVariant = Color(0xFF4A5562),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF0F3F7),
    surfaceContainerHigh = Color(0xFFEAEEF3),
    surfaceContainerHighest = Color(0xFFE3E8EE),
    outline = Color(0xFF8A95A2),
    outlineVariant = Color(0xFFDCE2E9),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFBE4E2),
    onErrorContainer = Color(0xFF5C1210)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8CC1F2),
    onPrimary = Color(0xFF00315A),
    primaryContainer = Color(0xFF184A75),
    onPrimaryContainer = Color(0xFFD8E7F7),
    secondary = Color(0xFFB8C4D1),
    onSecondary = Color(0xFF23303C),
    secondaryContainer = Color(0xFF2A343F),
    onSecondaryContainer = Color(0xFFDCE4EC),
    tertiary = Color(0xFFB8C4D1),
    onTertiary = Color(0xFF23303C),
    tertiaryContainer = Color(0xFF2A343F),
    onTertiaryContainer = Color(0xFFDCE4EC),
    background = Color(0xFF0F1318),
    onBackground = Color(0xFFE4E8EE),
    surface = Color(0xFF0F1318),
    onSurface = Color(0xFFE4E8EE),
    surfaceVariant = Color(0xFF242C35),
    onSurfaceVariant = Color(0xFFAAB4BF),
    surfaceContainerLowest = Color(0xFF0B0F13),
    surfaceContainerLow = Color(0xFF171C22),
    surfaceContainer = Color(0xFF1B2128),
    surfaceContainerHigh = Color(0xFF222932),
    surfaceContainerHighest = Color(0xFF29313B),
    outline = Color(0xFF7A8592),
    outlineVariant = Color(0xFF2C343E),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF4A1715),
    onErrorContainer = Color(0xFFF9DEDC)
)

/** Semantic status colours: container (background) and content (foreground) for each tone. */
@Immutable
data class StatusColors(
    val success: Color, val onSuccess: Color,
    val warning: Color, val onWarning: Color,
    val error: Color, val onError: Color,
    val info: Color, val onInfo: Color,
    val neutral: Color, val onNeutral: Color,
    /** Solid accent used for small status dots. */
    val successDot: Color, val warningDot: Color, val errorDot: Color, val busyDot: Color
)

private val LightStatus = StatusColors(
    success = Color(0xFFE2F3E6), onSuccess = Color(0xFF14421F),
    warning = Color(0xFFFCEFD6), onWarning = Color(0xFF4E3300),
    error = Color(0xFFFBE4E2), onError = Color(0xFF5C1210),
    info = Color(0xFFD8E7F7), onInfo = Color(0xFF0A2A4A),
    neutral = Color(0xFFE6EBF1), onNeutral = Color(0xFF2E3843),
    successDot = Color(0xFF2E8540), warningDot = Color(0xFFC77700), errorDot = Color(0xFFC62828), busyDot = Brand
)

private val DarkStatus = StatusColors(
    success = Color(0xFF173322), onSuccess = Color(0xFFBFE8C9),
    warning = Color(0xFF3A2A0A), onWarning = Color(0xFFF7D9A0),
    error = Color(0xFF4A1715), onError = Color(0xFFF9DEDC),
    info = Color(0xFF184A75), onInfo = Color(0xFFD8E7F7),
    neutral = Color(0xFF242C35), onNeutral = Color(0xFFD0D8E0),
    successDot = Color(0xFF6CC77F), warningDot = Color(0xFFF0B44C), errorDot = Color(0xFFF28B82), busyDot = Color(0xFF8CC1F2)
)

/**
 * Corner radii. Small controls 12 dp, inputs 14 dp, cards 20 dp, main control panels and bottom
 * sheets 28 dp. Deliberately not everything pill-shaped.
 */
object Radii {
    val control = 12.dp
    val input = 14.dp
    val card = 20.dp
    val panel = 28.dp
}

private val RoomAcShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(Radii.control),
    medium = RoundedCornerShape(Radii.input),
    large = RoundedCornerShape(Radii.card),
    extraLarge = RoundedCornerShape(Radii.panel)
)

/**
 * Motion tokens. Short, decelerating movements only. When the user has turned animations off in
 * Android's accessibility or developer settings, every spec collapses to an instant change.
 */
object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val SHORT = 160
    const val MEDIUM = 260

    fun <T> short(reduced: Boolean): FiniteAnimationSpec<T> = if (reduced) snap() else tween(SHORT, easing = Emphasized)
    fun <T> medium(reduced: Boolean): FiniteAnimationSpec<T> = if (reduced) snap() else tween(MEDIUM, easing = Emphasized)
}

private val LocalStatusColors = staticCompositionLocalOf { LightStatus }
private val LocalReducedMotion = staticCompositionLocalOf { false }

/** Access to RoomAC tokens that Material's theme does not carry. */
object RoomAcTheme {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current

    /** True when the user has turned system animations off. */
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

@Composable
fun RoomAcTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val reduced = remember(context) {
        // Android's "Remove animations" sets the animator scale to 0.
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    CompositionLocalProvider(
        LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus,
        LocalReducedMotion provides reduced
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = RoomAcTypography,
            shapes = RoomAcShapes,
            content = content
        )
    }
}

/** Elderly-friendly: every text style about 12 % larger than Material's defaults (on top of the phone's own font scale). */
private const val TEXT_SCALE = 1.12f

private fun TextStyle.scaled(weight: FontWeight? = null): TextStyle =
    copy(fontSize = fontSize.scaled(), lineHeight = lineHeight.scaled(), fontWeight = weight ?: fontWeight)
private fun TextUnit.scaled(): TextUnit = if (isSpecified) this * TEXT_SCALE else this

private val RoomAcTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.scaled(FontWeight.Light).copy(letterSpacing = (-1).sp),
        displayMedium = t.displayMedium.scaled(FontWeight.Normal), displaySmall = t.displaySmall.scaled(FontWeight.Normal),
        headlineLarge = t.headlineLarge.scaled(FontWeight.SemiBold), headlineMedium = t.headlineMedium.scaled(FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.scaled(FontWeight.SemiBold),
        titleLarge = t.titleLarge.scaled(FontWeight.SemiBold), titleMedium = t.titleMedium.scaled(FontWeight.SemiBold),
        titleSmall = t.titleSmall.scaled(FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.scaled(), bodyMedium = t.bodyMedium.scaled(), bodySmall = t.bodySmall.scaled(),
        labelLarge = t.labelLarge.scaled(FontWeight.SemiBold), labelMedium = t.labelMedium.scaled(FontWeight.Medium),
        labelSmall = t.labelSmall.scaled(FontWeight.Medium)
    )
}

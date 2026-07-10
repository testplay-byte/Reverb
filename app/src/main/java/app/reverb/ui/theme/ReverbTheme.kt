package app.reverb.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Reverb's Material 3 Expressive theme — green primary.
 *
 * Material 3 Expressive (2025/2026) emphasizes bold typography, expressive motion,
 * large rounded shapes, and dynamic color. We ship a green seed color (the user's request)
 * and let M3 tonal generation produce the full palette.
 *
 * Reference: PLAN.md §26 (UI will be Material 3 Expressive, green theme).
 */

// ── Green seed → M3 tonal palette ──────────────────────────────────────────
private val Green80 = Color(0xFF7DFAA1)
private val Green60 = Color(0xFF3FE074)
private val Green40 = Color(0xFF00A847)
private val Green30 = Color(0xFF008A38)
private val Green20 = Color(0xFF006D2B)
private val Green10 = Color(0xFF003F18)

// Secondary — warm amber accent for expressive contrast.
private val Amber80 = Color(0xFFFFCF80)
private val Amber40 = Color(0xFFC77A00)

// Tertiary — teal.
private val Teal80 = Color(0xFF7DDFD8)
private val Teal40 = Color(0xFF00A39B)

// Neutrals.
private val Neutral99 = Color(0xFFFCFDF7)
private val Neutral95 = Color(0xFFF1F4EC)
private val Neutral90 = Color(0xFFE2E9D8)
private val Neutral10 = Color(0xFF1A1C18)
private val Neutral20 = Color(0xFF2F312D)
private val Neutral30 = Color(0xFF454842)

// Error.
private val Red80 = Color(0xFFFFB4AB)
private val Red40 = Color(0xFFBA1A1A)

private val LightColors = lightColorScheme(
    primary = Green40, onPrimary = Color.White, primaryContainer = Green80, onPrimaryContainer = Green20,
    secondary = Amber40, onSecondary = Color.White, secondaryContainer = Amber80, onSecondaryContainer = Color(0xFF2A1700),
    tertiary = Teal40, onTertiary = Color.White, tertiaryContainer = Teal80, onTertiaryContainer = Color(0xFF00201E),
    error = Red40, onError = Color.White, errorContainer = Red80, onErrorContainer = Color(0xFF410002),
    background = Neutral99, onBackground = Neutral10, surface = Neutral99, onSurface = Neutral10,
    surfaceVariant = Neutral90, onSurfaceVariant = Neutral30, outline = Color(0xFF71796D),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Neutral95,
    surfaceContainer = Neutral95, surfaceContainerHigh = Neutral90, surfaceContainerHighest = Neutral90,
)

private val DarkColors = darkColorScheme(
    primary = Green80, onPrimary = Color(0xFF00391A), primaryContainer = Green30, onPrimaryContainer = Green80,
    secondary = Amber80, onSecondary = Color(0xFF422C00), secondaryContainer = Color(0xFF5E4100), onSecondaryContainer = Amber80,
    tertiary = Teal80, onTertiary = Color(0xFF003733), tertiaryContainer = Color(0xFF00504A), onTertiaryContainer = Teal80,
    error = Red80, onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Red80,
    background = Neutral10, onBackground = Neutral95, surface = Neutral10, onSurface = Neutral95,
    surfaceVariant = Neutral30, onSurfaceVariant = Neutral90, outline = Color(0xFF8B9381),
    surfaceContainerLowest = Color(0xFF0F110D), surfaceContainerLow = Neutral20,
    surfaceContainer = Neutral20, surfaceContainerHigh = Color(0xFF3A3D37), surfaceContainerHighest = Color(0xFF454842),
)

/** M3 Expressive typography — bold, large, personality-driven. */
private val ExpressiveTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Black, fontSize = 64.sp, lineHeight = 72.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontWeight = FontWeight.Black, fontSize = 48.sp, lineHeight = 56.sp, letterSpacing = 0.sp),
    displaySmall = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)

@Composable
fun ReverbTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ExpressiveTypography,
        content = content,
    )
}

package dev.phoneagent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

enum class AppTheme(val label: String) {
    SYSTEM("System"),
    OLED("OLED Black"),
    LIGHT("Monochrome Light"),
}

private val OledColors = darkColorScheme(
    primary = Color(0xFFF5F5F5),
    onPrimary = Color(0xFF141414),
    primaryContainer = Color(0xFF303030),
    onPrimaryContainer = Color(0xFFF5F5F5),
    inversePrimary = Color(0xFF444444),
    secondary = Color(0xFFC6C6C6),
    onSecondary = Color(0xFF1B1B1B),
    secondaryContainer = Color(0xFF282828),
    onSecondaryContainer = Color(0xFFECECEC),
    tertiary = Color(0xFFD8D8D8),
    onTertiary = Color(0xFF252525),
    tertiaryContainer = Color(0xFF343434),
    onTertiaryContainer = Color(0xFFECECEC),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF101010),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF252525),
    onSurfaceVariant = Color(0xFFB3B3B3),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFF1F1F1),
    inverseOnSurface = Color(0xFF1B1B1B),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF101010),
    surfaceContainer = Color(0xFF191919),
    surfaceContainerHigh = Color(0xFF202020),
    surfaceContainerHighest = Color(0xFF282828),
    surfaceDim = Color(0xFF101010),
    surfaceBright = Color(0xFF343434),
    outline = Color(0xFF858585),
    outlineVariant = Color(0xFF343434),
    error = Color(0xFFF4A7A3),
    onError = Color(0xFF361411),
    errorContainer = Color(0xFF492220),
    onErrorContainer = Color(0xFFFFDAD7),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F1F1F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E6E6),
    onPrimaryContainer = Color(0xFF1B1B1B),
    inversePrimary = Color(0xFFE0E0E0),
    secondary = Color(0xFF565656),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E4E4),
    onSecondaryContainer = Color(0xFF202020),
    tertiary = Color(0xFF505050),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0E0E0),
    onTertiaryContainer = Color(0xFF202020),
    background = Color(0xFFFDFDFD),
    onBackground = Color(0xFF171717),
    surface = Color(0xFFF8F8F8),
    onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFEAEAEA),
    onSurfaceVariant = Color(0xFF505050),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF222222),
    inverseOnSurface = Color(0xFFF5F5F5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F4F4),
    surfaceContainer = Color(0xFFEFEFEF),
    surfaceContainerHigh = Color(0xFFE8E8E8),
    surfaceContainerHighest = Color(0xFFE3E3E3),
    surfaceDim = Color(0xFFDADADA),
    surfaceBright = Color.White,
    outline = Color(0xFF747474),
    outlineVariant = Color(0xFFC8C8C8),
    error = Color(0xFFA12722),
    onError = Color.White,
    errorContainer = Color(0xFFFADBD8),
    onErrorContainer = Color(0xFF410E0B),
)

private val PhoneTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-1.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-1.1).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 28.sp, lineHeight = 33.sp, letterSpacing = (-0.55).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-0.35).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp,
        letterSpacing = 0.05.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
)

private val PhoneShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun PhoneAgentTheme(theme: AppTheme = AppTheme.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (theme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.OLED -> true
        AppTheme.LIGHT -> false
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) OledColors else LightColors,
        typography = PhoneTypography,
        shapes = PhoneShapes,
        content = content,
    )
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

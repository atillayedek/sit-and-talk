package com.sitandtalk.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand tokens. Contrast pairs were chosen so body text stays >= 4.5:1 on its surface. */
object StColors {
    val Blue = Color(0xFF349AF4)
    val BlueDeep = Color(0xFF1B6FC2) // text/icons on white: 5.0:1
    val Turquoise = Color(0xFF08C5E8)
    val Success = Color(0xFF00C99B)
    val SuccessDeep = Color(0xFF00805F)
    val Coral = Color(0xFFFF7047)
    val EndCall = Color(0xFFFF5967)
    val EndCallDeep = Color(0xFFD62D3F)
    val White = Color(0xFFFFFFFF)
    val PageLight = Color(0xFFF4F8FC)
    val Ink = Color(0xFF172B4D)
    val InkSecondary = Color(0xFF63758B)
    val Outline = Color(0xFFD5DEE8)

    val DarkPage = Color(0xFF0E1726)
    val DarkSurface = Color(0xFF172336)
    val DarkSurfaceHigh = Color(0xFF203049)
    val DarkInk = Color(0xFFE8EEF6)
    val DarkInkSecondary = Color(0xFFA5B3C4)
}

@Immutable
data class StExtraColors(
    val success: Color,
    val onSuccess: Color,
    val coral: Color,
    val endCall: Color,
    val onEndCall: Color,
    val textSecondary: Color,
    val talkGradient: Brush,
    val onTalkGradient: Color,
)

val LocalStColors = staticCompositionLocalOf {
    StExtraColors(
        success = StColors.Success,
        onSuccess = StColors.Ink,
        coral = StColors.Coral,
        endCall = StColors.EndCallDeep,
        onEndCall = StColors.White,
        textSecondary = StColors.InkSecondary,
        // Deepened brand gradient: white text keeps >= 4.5:1 across the whole gradient.
        talkGradient = Brush.verticalGradient(listOf(StColors.BlueDeep, Color(0xFF0B7F99))),
        onTalkGradient = StColors.White,
    )
}

private val LightScheme: ColorScheme = lightColorScheme(
    primary = StColors.BlueDeep,
    onPrimary = StColors.White,
    primaryContainer = Color(0xFFD7EBFD),
    onPrimaryContainer = Color(0xFF0B3A66),
    secondary = Color(0xFF007C93),
    onSecondary = StColors.White,
    secondaryContainer = Color(0xFFCFF4FB),
    onSecondaryContainer = Color(0xFF003742),
    tertiary = Color(0xFFB8431F),
    onTertiary = StColors.White,
    error = StColors.EndCallDeep,
    onError = StColors.White,
    background = StColors.PageLight,
    onBackground = StColors.Ink,
    surface = StColors.White,
    onSurface = StColors.Ink,
    surfaceVariant = Color(0xFFE9F0F7),
    onSurfaceVariant = StColors.InkSecondary,
    surfaceContainer = StColors.White,
    surfaceContainerHigh = Color(0xFFF0F5FA),
    outline = StColors.Outline,
    outlineVariant = Color(0xFFE3EAF2),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF8CC7FA),
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF12497F),
    onPrimaryContainer = Color(0xFFD7EBFD),
    secondary = Color(0xFF6ADFF5),
    onSecondary = Color(0xFF00363F),
    tertiary = Color(0xFFFFB59F),
    error = Color(0xFFFF8A94),
    onError = Color(0xFF52000A),
    background = StColors.DarkPage,
    onBackground = StColors.DarkInk,
    surface = StColors.DarkSurface,
    onSurface = StColors.DarkInk,
    surfaceVariant = StColors.DarkSurfaceHigh,
    onSurfaceVariant = StColors.DarkInkSecondary,
    surfaceContainer = StColors.DarkSurface,
    surfaceContainerHigh = StColors.DarkSurfaceHigh,
    outline = Color(0xFF3A4B63),
    outlineVariant = Color(0xFF2A3A52),
)

private val StTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

private val StShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun SitAndTalkTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val extra = if (darkTheme) {
        StExtraColors(
            success = Color(0xFF4FE3BD),
            onSuccess = Color(0xFF00382A),
            coral = Color(0xFFFF9C80),
            endCall = StColors.EndCall,
            onEndCall = Color(0xFF3A0008),
            textSecondary = StColors.DarkInkSecondary,
            talkGradient = Brush.verticalGradient(listOf(Color(0xFF155E9E), Color(0xFF0A7D93))),
            onTalkGradient = StColors.White,
        )
    } else {
        LocalStColors.current
    }
    CompositionLocalProvider(LocalStColors provides extra) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = StTypography,
            shapes = StShapes,
            content = content,
        )
    }
}

object StTheme {
    val extra: StExtraColors
        @Composable get() = LocalStColors.current
}

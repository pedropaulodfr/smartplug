package com.smartplug.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Teal = Color(0xFF0F766E)
val TealLight = Color(0xFF14B8A6)
val Amber = Color(0xFFF59E0B)
val Success = Color(0xFF16A34A)
val Danger = Color(0xFFDC2626)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF8A5A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFEFC7),
    onSecondaryContainer = Color(0xFF2B1A00),
    tertiary = Color(0xFF3B5BA5),
    background = Color(0xFFFBFCFB),
    onBackground = Color(0xFF0F1B1A),
    surface = Color(0xFFFBFCFB),
    onSurface = Color(0xFF0F1B1A),
    surfaceVariant = Color(0xFFE3EBE9),
    onSurfaceVariant = Color(0xFF3F4948),
    surfaceContainerLow = Color(0xFFF2F6F5),
    surfaceContainer = Color(0xFFECF2F1),
    surfaceContainerHigh = Color(0xFFE6EDEC),
    outline = Color(0xFF6F7978),
    error = Danger,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF0B4F49),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFFFFC857),
    onSecondary = Color(0xFF3F2A00),
    secondaryContainer = Color(0xFF5B3D00),
    onSecondaryContainer = Color(0xFFFFEFC7),
    tertiary = Color(0xFFAEC6FF),
    background = Color(0xFF0E1514),
    onBackground = Color(0xFFDDE5E3),
    surface = Color(0xFF0E1514),
    onSurface = Color(0xFFDDE5E3),
    surfaceVariant = Color(0xFF3F4948),
    onSurfaceVariant = Color(0xFFBEC9C7),
    surfaceContainerLow = Color(0xFF151D1C),
    surfaceContainer = Color(0xFF192120),
    surfaceContainerHigh = Color(0xFF232B2A),
    outline = Color(0xFF899392),
    error = Color(0xFFFF8A80),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

@Composable
fun SmartPlugTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

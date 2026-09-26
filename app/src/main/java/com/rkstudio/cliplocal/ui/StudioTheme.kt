package com.rkstudio.cliplocal.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val StudioColors = darkColorScheme(
    primary = Color(0xFFCEEF78), onPrimary = Color(0xFF192600),
    primaryContainer = Color(0xFF303E1C), onPrimaryContainer = Color(0xFFE0F4B7),
    secondary = Color(0xFFB8C6AC), onSecondary = Color(0xFF20291B),
    secondaryContainer = Color(0xFF293322), onSecondaryContainer = Color(0xFFDDE7D4),
    background = Color(0xFF10120F), onBackground = Color(0xFFF1F3EB),
    surface = Color(0xFF181C16), onSurface = Color(0xFFF1F3EB),
    surfaceVariant = Color(0xFF252B21), onSurfaceVariant = Color(0xFFB8C0B0),
    outline = Color(0xFF818C77), outlineVariant = Color(0xFF394232),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF45201E), onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StudioColors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(32.dp)
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
            headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
            titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
        ), content = content
    )
}

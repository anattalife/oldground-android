package com.brandonthomas.oldground.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.brandonthomas.oldground.R

/**
 * "Clean Trail": light and readable in bright sun, deep teal accent, white rounded
 * cards with soft shadows, Plus Jakarta Sans lettering.
 */
object C {
    val bg = Color(0xFFF4F6F5)
    val panel = Color(0xFFFFFFFF)
    val soft = Color(0xFFF1F4F3)
    val ink = Color(0xFF14201D)
    val muted = Color(0xFF55635F)
    val faint = Color(0xFF8A9893)
    val line = Color(0xFFE1E6E4)
    val accent = Color(0xFF0F766E)
    val accentSoft = Color(0xFFE2F1EF)
    val green = Color(0xFF0F766E)
    val good = Color(0xFF2E8B57)
    val check = Color(0xFFB45309)
    val bad = Color(0xFFC0392B)
    val badSoft = Color(0xFFFDECEA)
    val badInk = Color(0xFFB42318)
    val scrim = Color(0xD914201D)

    const val goodHex = "#2E8B57"
    const val checkHex = "#D4A017"
    const val badHex = "#C0392B"
    const val unknownHex = "#7F8C8D"
    const val rustHex = "#0F766E"
}

val Jakarta = FontFamily(
    Font(R.font.plusjakarta_regular, FontWeight.Normal),
    Font(R.font.plusjakarta_medium, FontWeight.Medium),
    Font(R.font.plusjakarta_semibold, FontWeight.SemiBold),
    Font(R.font.plusjakarta_bold, FontWeight.Bold),
    Font(R.font.plusjakarta_extrabold, FontWeight.ExtraBold),
)

private fun Typography.withFont(f: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = f)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

@Composable
fun OldGroundTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = C.accent,
            onPrimary = Color.White,
            secondary = C.accent,
            background = C.bg,
            surface = C.panel,
            surfaceContainerHigh = C.panel,
            onSurface = C.ink,
            onBackground = C.ink,
            error = C.bad,
        ),
        typography = Typography().withFont(Jakarta),
        content = content,
    )
}

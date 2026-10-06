package io.github.corum86.vacationmap.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.corum86.vacationmap.R

/** The design tokens of the web app (src/index.css), one for one. */
object VmColors {
    val Text = Color(0xFF2B2118)
    val TextMuted = Color(0xFF8A7A63)
    val TextFaint = Color(0xFFC9B89E)
    val Bg = Color(0xFFFBF8F3)
    val Surface = Color(0xFFFFFFFF)
    val Surface2 = Color(0xFFF6EFE4)
    val Surface3 = Color(0xFFF4EEE5)
    val Border = Color(0xFFEFE4D2)
    val BorderHover = Color(0xFFD9C7AA)
    val Accent = Color(0xFFEF5A2A)
    val AccentSoft = Color(0xFFFF7A4D)
    val AccentTint = Color(0xFFFDE9E0)
    val AccentCasing = Color(0xFFA83C17)
    val Accent2 = Color(0xFF0C8A83)
    val Accent2Dark = Color(0xFF075E59)
    val Accent2Tint = Color(0xFFE3F1EF)
    val Accent2Indicator = Color(0xFFCFE9E6)
    val Accent2Bright = Color(0xFF2DD4C4)
    val Star = Color(0xFFFF9F52)
    val StarEmpty = Color(0xFFD9CBB5)
    val Danger = Color(0xFFE63946)
    val DangerTint = Color(0xFFFBE3E5)

    /** revisits: a place planned again on a later day */
    val Amber = Color(0xFFA3560A)
    val AmberTint = Color(0xFFFFF1D6)
    val NavIcon = Color(0xFF5C4F40)
    val HomeGradientStart = Color(0xFFFFD166)

    /** shadow tint of floating surfaces: rgb(120, 72, 20) */
    val ShadowTint = Color(0xFF784814)
    val Scrim = Color(0xFF0F0C08)
}

val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
    Font(R.font.poppins_extrabold, FontWeight.ExtraBold),
)

/** Material Symbols Rounded, subset to the icons the app uses (see android/scripts/fetch-icon-font.sh). */
val MaterialSymbols = FontFamily(Font(R.font.material_symbols_rounded))
val MaterialSymbolsFilled = FontFamily(Font(R.font.material_symbols_rounded_filled))

/** Body text: 15/1.45 Poppins. Line height follows the font size, as in CSS. */
val BaseTextStyle = TextStyle(
    fontFamily = Poppins,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 1.45.em,
    color = VmColors.Text,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

private val ColorScheme = lightColorScheme(
    primary = VmColors.Accent2,
    onPrimary = Color.White,
    primaryContainer = VmColors.Accent2Tint,
    onPrimaryContainer = VmColors.Accent2Dark,
    secondary = VmColors.Accent,
    onSecondary = Color.White,
    secondaryContainer = VmColors.AccentTint,
    onSecondaryContainer = VmColors.Accent,
    background = VmColors.Bg,
    onBackground = VmColors.Text,
    surface = VmColors.Bg,
    onSurface = VmColors.Text,
    surfaceVariant = VmColors.Surface3,
    onSurfaceVariant = VmColors.TextMuted,
    outline = VmColors.Border,
    error = VmColors.Danger,
    onError = Color.White,
)

@Composable
fun VacationMapTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme) {
        CompositionLocalProvider(LocalTextStyle provides BaseTextStyle, content = content)
    }
}

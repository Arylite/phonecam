package re.isx.phonecam.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import re.isx.phonecam.R

/**
 * A camera instrument, not a dashboard: off-black, one amber accent for state and selection,
 * red only for the live dot. No gradients, glow or translucency.
 */
object Palette {
    val Background = Color(0xFF0C0C0D)
    val Rail = Color(0xFF141415)
    val Line = Color(0xFF2B2B2E)
    val Text = Color(0xFFECECEA)
    val TextDim = Color(0xFF8D8D90)
    val Accent = Color(0xFFF2B01E)
    val Live = Color(0xFFE5483C)
    val Error = Color(0xFFE5483C)
}

/** Barlow: plain, a little technical, closer to camera markings than the system default. */
private val Barlow =
    FontFamily(
        Font(R.font.barlow_regular, FontWeight.Normal),
        Font(R.font.barlow_medium, FontWeight.Medium),
        Font(R.font.barlow_semibold, FontWeight.SemiBold),
    )

private val colors =
    darkColorScheme(
        primary = Palette.Accent,
        onPrimary = Palette.Background,
        secondary = Palette.TextDim,
        secondaryContainer = Palette.Line,
        onSecondaryContainer = Palette.Text,
        background = Palette.Background,
        onBackground = Palette.Text,
        surface = Palette.Rail,
        onSurface = Palette.Text,
        surfaceVariant = Palette.Rail,
        onSurfaceVariant = Palette.TextDim,
        surfaceContainerLow = Palette.Rail,
        surfaceContainer = Palette.Rail,
        surfaceContainerHigh = Palette.Rail,
        outline = Palette.Line,
        outlineVariant = Palette.Line,
        error = Palette.Error,
    )

private fun style(
    size: Int,
    weight: FontWeight = FontWeight.Normal,
    line: Int = size + 6,
) = TextStyle(fontFamily = Barlow, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp)

private val typography =
    Typography(
        headlineSmall = style(26, FontWeight.SemiBold, 32),
        titleLarge = style(22, FontWeight.SemiBold, 28),
        titleMedium = style(17, FontWeight.Medium, 22),
        bodyLarge = style(17),
        bodyMedium = style(15, line = 22),
        bodySmall = style(13, line = 18),
        labelLarge = style(15, FontWeight.Medium),
        labelMedium = style(13, FontWeight.Medium),
        labelSmall = style(11, FontWeight.Medium),
    )

/** Small caps-style label over a value, like the markings on a camera body. */
val ReadoutLabel = style(11, FontWeight.Medium, 14).copy(letterSpacing = 1.2.sp)

/** Numbers keep their width as they change (timer, fps). */
val ReadoutValue = style(15, FontWeight.Medium, 18).copy(fontFeatureSettings = "tnum")

/** The Surface sets the default text and icon color: without it, Compose falls back to black. */
@Composable
fun PhoneCamTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = colors, typography = typography) {
        Surface(color = Palette.Background, contentColor = Palette.Text, content = content)
    }

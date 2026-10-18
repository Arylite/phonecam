package re.isx.phonecam.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Black and white like the icon, with color only for state: live, starting, error. */
object Palette {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF111113)
    val SurfaceHigh = Color(0xFF1C1C1F)
    val Outline = Color(0xFF2A2A2E)
    val Text = Color(0xFFF5F5F7)
    val TextDim = Color(0xFF9A9AA2)
    val Glass = Color(0x99000000)
    val GlassBorder = Color(0x26FFFFFF)
    val Live = Color(0xFFFF3B30)
    val Ready = Color(0xFF30D158)
    val Starting = Color(0xFFFFD60A)
    val Error = Color(0xFFFF453A)
}

private val colors =
    darkColorScheme(
        primary = Color.White,
        onPrimary = Color.Black,
        secondary = Palette.TextDim,
        // Selected segments and chips: a light gray instead of Material's default purple.
        secondaryContainer = Color(0xFF3A3A3F),
        onSecondaryContainer = Color.White,
        background = Palette.Background,
        onBackground = Palette.Text,
        surface = Palette.Surface,
        onSurface = Palette.Text,
        surfaceVariant = Palette.SurfaceHigh,
        onSurfaceVariant = Palette.TextDim,
        surfaceContainerLow = Palette.Surface,
        surfaceContainer = Palette.Surface,
        surfaceContainerHigh = Palette.SurfaceHigh,
        outline = Palette.Outline,
        outlineVariant = Palette.Outline,
        error = Palette.Error,
    )

private val typography =
    Typography().run {
        copy(
            headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
            titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
            labelMedium = labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
        )
    }

/** Numbers that tick (timer, fps) keep their width. */
val MonoDigits = TextStyle(fontFeatureSettings = "tnum")

/** The Surface sets the default text and icon color: without it, Compose falls back to black. */
@Composable
fun PhoneCamTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = colors, typography = typography) {
        Surface(color = Palette.Background, contentColor = Palette.Text, content = content)
    }

package re.isx.phonecam.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import re.isx.phonecam.R

/** Translucent pill over the camera image. */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) = Row(
    modifier =
        modifier
            .clip(CircleShape)
            .background(Palette.Glass)
            .border(1.dp, Palette.GlassBorder, CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) { content() }

/** Round translucent button with an icon, usable over the camera image. */
@Composable
fun GlassButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) = Box(
    modifier =
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Palette.Glass)
            .border(1.dp, Palette.GlassBorder, CircleShape)
            .clickableWithRipple(onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
    contentAlignment = Alignment.Center,
) {
    Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
}

/** A dot that breathes, for "live" and "waiting". */
@Composable
fun PulsingDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val alpha by rememberInfiniteTransition(label = "dot").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
}

/** Rings expanding from the lens icon while looking for the PC. */
@Composable
fun Radar(
    modifier: Modifier = Modifier,
    ringColor: Color = Color.White,
) {
    val progress by rememberInfiniteTransition(label = "radar").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "progress",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val max = size.minDimension / 2
            repeat(RINGS) { i ->
                val p = (progress + i.toFloat() / RINGS) % 1f
                drawCircle(
                    color = ringColor.copy(alpha = (1 - p) * 0.35f),
                    radius = max * (0.35f + 0.65f * p),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(Color.Black)
                .border(1.dp, Palette.Outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_lens), contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
        }
    }
}

/** Small uppercase label above a group of settings or steps. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) = Text(
    text.uppercase(),
    modifier = modifier.padding(bottom = 8.dp),
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** Rounded panel used for cards and the steps list. */
fun Modifier.panel() =
    this
        .clip(RoundedCornerShape(20.dp))
        .background(Palette.Surface)
        .border(1.dp, Palette.Outline, RoundedCornerShape(20.dp))

@Composable
fun IconLine(
    @DrawableRes icon: Int,
    text: String,
    color: Color,
) = Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

private const val RINGS = 3

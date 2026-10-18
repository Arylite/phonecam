package re.isx.phonecam.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Label over value, the way a camera shows shutter or ISO. */
@Composable
fun Readout(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Palette.Text,
) = Column(modifier) {
    Text(label.uppercase(), style = ReadoutLabel, color = Palette.TextDim)
    Text(value, style = ReadoutValue, color = valueColor)
}

/** Still dot for a state: the label beside it says what it means, motion would add nothing. */
@Composable
fun StateDot(
    color: Color,
    modifier: Modifier = Modifier,
) = Box(modifier.size(8.dp).clip(CircleShape).background(color))

/**
 * A control on the rail: icon, what it controls, and its current value, so the state is read
 * without tapping. The whole cell is the touch target.
 */
@Composable
fun RailControl(
    @DrawableRes icon: Int,
    label: String,
    value: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = Column(
    modifier =
        modifier
            .sizeIn(minWidth = 72.dp, minHeight = 72.dp)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
            }.padding(horizontal = 8.dp, vertical = 10.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
) {
    Icon(painterResource(icon), contentDescription = null, tint = Palette.Text, modifier = Modifier.size(22.dp))
    Text(label.uppercase(), style = ReadoutLabel, color = Palette.TextDim, textAlign = TextAlign.Center)
    Text(value, style = MaterialTheme.typography.labelMedium, color = Palette.Text, textAlign = TextAlign.Center)
}

/** One line of a checklist: what is needed, and whether the phone sees it. */
@Composable
fun CheckRow(
    number: Int,
    title: String,
    status: String,
    ok: Boolean,
) = Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$number", style = ReadoutValue, color = Palette.TextDim, modifier = Modifier.padding(end = 16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(status, style = MaterialTheme.typography.labelLarge, color = if (ok) Palette.Accent else Palette.TextDim)
    }
    HorizontalDivider(color = Palette.Line)
}

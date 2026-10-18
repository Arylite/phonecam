package re.isx.phonecam.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import re.isx.phonecam.R

/** Shown until the PC client answers: what PhoneCam is and the three steps to get there. */
@Composable
fun WaitingScreen(
    port: Int,
    landscape: Boolean,
) {
    val intro: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Radar(Modifier.size(if (landscape) 200.dp else 240.dp))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.waiting_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.waiting_text, port),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
    if (landscape) {
        // Leaves room on the right for the control column.
        Row(
            Modifier.fillMaxWidth().padding(end = 72.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { intro() }
            Steps(Modifier.weight(1f).widthIn(max = 420.dp))
        }
    } else {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            intro()
            Steps(Modifier.widthIn(max = 420.dp))
        }
    }
}

@Composable
private fun Steps(modifier: Modifier = Modifier) =
    Column(modifier.panel().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionLabel(stringResource(R.string.steps_title), Modifier.padding(bottom = 0.dp))
        Step(1, R.drawable.ic_code, stringResource(R.string.step_debugging), stringResource(R.string.step_debugging_hint))
        Step(2, R.drawable.ic_usb, stringResource(R.string.step_plug), stringResource(R.string.step_plug_hint))
        Step(3, R.drawable.ic_monitor, stringResource(R.string.step_pc), stringResource(R.string.step_pc_hint))
    }

@Composable
private fun Step(
    number: Int,
    @DrawableRes icon: Int,
    title: String,
    hint: String,
) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Palette.SurfaceHigh)
            .border(1.dp, Palette.Outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
    Spacer(Modifier.size(14.dp))
    Column(Modifier.weight(1f)) {
        Text("$number. $title", style = MaterialTheme.typography.titleMedium)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A centered card for one state: starting, preview off, error, missing permission. */
@Composable
fun StatusCard(
    @DrawableRes icon: Int,
    title: String,
    text: String,
    iconTint: Color = Color.White,
    busy: Boolean = false,
    action: Pair<String, () -> Unit>? = null,
) = Column(
    Modifier.widthIn(max = 420.dp).panel().padding(28.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
) {
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(Modifier.size(64.dp), color = Palette.Starting, strokeWidth = 2.dp)
        Icon(painterResource(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(32.dp))
    }
    Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    if (text.isNotEmpty()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    action?.let { (label, onClick) -> Button(onClick, Modifier.padding(top = 8.dp)) { Text(label) } }
}

@Preview(widthDp = 800, heightDp = 380, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun WaitingLandscapePreview() = PhoneCamTheme { WaitingScreen(8765, landscape = true) }

@Preview(widthDp = 380, heightDp = 800, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun WaitingPortraitPreview() = PhoneCamTheme { WaitingScreen(8765, landscape = false) }

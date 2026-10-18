package re.isx.phonecam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import re.isx.phonecam.DeviceState
import re.isx.phonecam.Phase
import re.isx.phonecam.R
import re.isx.phonecam.Settings
import re.isx.phonecam.UiState

/** What the viewfinder shows when there is no image: left-aligned text, no decoration. */
@Composable
fun MessagePane(
    phase: Phase,
    state: UiState,
    settings: Settings,
    device: DeviceState,
    onOpenAppSettings: () -> Unit,
) = Column(
    Modifier.widthIn(max = 460.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    val (title, body) =
        when (phase) {
            Phase.WAITING -> stringResource(R.string.waiting_title) to stringResource(R.string.waiting_text)
            Phase.STARTING -> stringResource(R.string.starting_title) to ""
            Phase.LIVE -> stringResource(R.string.preview_off_title) to stringResource(R.string.preview_off_text)
            Phase.ERROR -> stringResource(R.string.error_title) to state.message
            Phase.NO_PERMISSION -> stringResource(R.string.permission_title) to stringResource(R.string.permission_text)
        }
    Text(title, style = MaterialTheme.typography.headlineSmall)
    if (body.isNotEmpty()) {
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
    }
    when (phase) {
        Phase.WAITING -> {
            Spacer(Modifier.height(12.dp))
            Checklist(settings.port, device)
        }
        Phase.NO_PERMISSION ->
            Button(onOpenAppSettings, Modifier.padding(top = 16.dp), shape = RoundedCornerShape(4.dp)) {
                Text(stringResource(R.string.permission_action))
            }
        else -> Unit
    }
}

/** The three things a connection needs, with what the phone actually sees for each. */
@Composable
private fun Checklist(
    port: Int,
    device: DeviceState,
) = Column {
    CheckRow(
        1,
        stringResource(R.string.check_debugging),
        stringResource(if (device.usbDebugging) R.string.check_on else R.string.check_off),
        device.usbDebugging,
    )
    CheckRow(
        2,
        stringResource(R.string.check_cable),
        stringResource(if (device.usbCable) R.string.check_connected else R.string.check_not_detected),
        device.usbCable,
    )
    CheckRow(3, stringResource(R.string.check_pc), stringResource(R.string.check_waiting, port), false)
    if (!device.usbDebugging) {
        Text(
            stringResource(R.string.check_debugging_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextDim,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Preview(widthDp = 760, heightDp = 380, backgroundColor = 0xFF0C0C0D, showBackground = true)
@Composable
private fun WaitingPreview() =
    PhoneCamTheme {
        MessagePane(Phase.WAITING, UiState(), Settings(), DeviceState(usbDebugging = true, usbCable = false)) {}
    }

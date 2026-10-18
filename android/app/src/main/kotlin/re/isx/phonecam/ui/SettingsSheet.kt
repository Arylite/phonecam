package re.isx.phonecam.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import re.isx.phonecam.BuildConfig
import re.isx.phonecam.R
import re.isx.phonecam.Settings
import re.isx.phonecam.StreamConfig

/**
 * What the rail does not already show. Changes apply at once; only the port waits for the sheet
 * to close, since it reconnects.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: Settings,
    config: StreamConfig?,
    onChange: (Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    var port by rememberSaveable { mutableStateOf(settings.port.toString()) }
    val portValid = port.toIntOrNull()?.let { it in Settings.PORTS } == true
    val close = {
        if (portValid && port.toInt() != settings.port) onChange(settings.copy(port = port.toInt()))
        onDismiss()
    }
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Rail,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))

            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(R.string.preview), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.preview_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                }
                Switch(
                    settings.preview,
                    { onChange(settings.copy(preview = it)) },
                    colors =
                        SwitchDefaults.colors(
                            checkedTrackColor = Palette.Accent,
                            checkedThumbColor = Palette.Background,
                            uncheckedTrackColor = Palette.Background,
                            uncheckedBorderColor = Palette.Line,
                            uncheckedThumbColor = Palette.TextDim,
                        ),
                )
            }
            HorizontalDivider(color = Palette.Line)

            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(R.string.port), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(if (portValid) R.string.port_hint else R.string.port_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (portValid) Palette.TextDim else Palette.Error,
                    )
                }
                OutlinedTextField(
                    value = port,
                    onValueChange = { v -> port = v.filter(Char::isDigit).take(5) },
                    isError = !portValid,
                    singleLine = true,
                    textStyle = ReadoutValue,
                    shape = MaterialTheme.shapes.extraSmall,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.width(110.dp),
                )
            }
            HorizontalDivider(color = Palette.Line)

            config?.let {
                Text(
                    stringResource(R.string.stream_from_pc, it.width, it.height, it.fps, it.bitrate / 1_000_000f),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    modifier = Modifier.padding(vertical = 14.dp),
                )
                HorizontalDivider(color = Palette.Line)
            }

            val uri = LocalUriHandler.current
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextDim,
                    modifier = Modifier.weight(1f),
                )
                TextButton({ uri.openUri(SOURCE_URL) }) { Text(stringResource(R.string.source_code), color = Palette.Accent) }
            }
        }
    }
}

private const val SOURCE_URL = "https://github.com/Arylite/phonecam"

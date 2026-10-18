package re.isx.phonecam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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

/** Every change applies at once; only the port waits for the sheet to close (it reconnects). */
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
        containerColor = Palette.Surface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)

            Column {
                SectionLabel(stringResource(R.string.section_camera))
                Choice(
                    listOf(stringResource(R.string.lens_back), stringResource(R.string.lens_front)),
                    if (settings.frontLens) 1 else 0,
                ) { onChange(settings.copy(frontLens = it == 1)) }
                Spacer(Modifier.height(12.dp))
                Choice(
                    listOf(stringResource(R.string.orientation_landscape), stringResource(R.string.orientation_portrait)),
                    if (settings.portrait) 1 else 0,
                ) { onChange(settings.copy(portrait = it == 1)) }
                Toggle(stringResource(R.string.autofocus), stringResource(R.string.autofocus_hint), settings.autofocus) {
                    onChange(settings.copy(autofocus = it))
                }
                Toggle(stringResource(R.string.preview), stringResource(R.string.preview_hint), settings.preview) {
                    onChange(settings.copy(preview = it))
                }
            }

            Column {
                SectionLabel(stringResource(R.string.section_connection))
                OutlinedTextField(
                    value = port,
                    onValueChange = { v -> port = v.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.port)) },
                    supportingText = { Text(stringResource(if (portValid) R.string.port_hint else R.string.port_invalid)) },
                    isError = !portValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                config?.let {
                    Text(
                        stringResource(R.string.stream_from_pc, it.width, it.height, it.fps, it.bitrate / 1_000_000f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column {
                SectionLabel(stringResource(R.string.section_about))
                val uri = LocalUriHandler.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton({ uri.openUri(SOURCE_URL) }) { Text(stringResource(R.string.source_code)) }
                }
            }
        }
    }
}

@Composable
private fun Choice(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) = SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
    options.forEachIndexed { i, label ->
        SegmentedButton(
            selected = i == selected,
            onClick = { onSelect(i) },
            shape = SegmentedButtonDefaults.itemShape(i, options.size),
        ) { Text(label) }
    }
}

@Composable
private fun Toggle(
    title: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) = Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f).padding(end = 16.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Switch(checked, onChange)
}

private const val SOURCE_URL = "https://github.com/Arylite/phonecam"

package re.isx.phonecam.ui

import android.content.res.Configuration
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import re.isx.phonecam.DeviceState
import re.isx.phonecam.Phase
import re.isx.phonecam.R
import re.isx.phonecam.Settings
import re.isx.phonecam.UiState
import java.util.Locale

/** Callbacks from the UI to the activity. */
class Actions(
    val onTexture: (TextureView) -> Unit,
    val onSettings: (Settings) -> Unit,
    val onOpenAppSettings: () -> Unit,
)

/**
 * Viewfinder and a solid control rail, like a camera body: the rail sits on the right in
 * landscape and at the bottom in portrait, where the letterboxed image leaves room anyway.
 */
@Composable
fun App(
    state: UiState,
    settings: Settings,
    device: DeviceState,
    actions: Actions,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val viewfinder: @Composable (Modifier) -> Unit = { modifier ->
        Viewfinder(state, settings, device, actions, modifier)
    }
    val rail: @Composable () -> Unit = {
        Rail(settings, landscape, actions.onSettings, onOpenSettings = { showSettings = true })
    }

    Box(Modifier.fillMaxSize().background(Palette.Background).safeDrawingPadding()) {
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                viewfinder(Modifier.weight(1f).fillMaxHeight())
                VerticalDivider(color = Palette.Line)
                rail()
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                viewfinder(Modifier.weight(1f).fillMaxWidth())
                HorizontalDivider(color = Palette.Line)
                rail()
            }
        }
    }

    if (showSettings) {
        SettingsSheet(settings, state.config, onChange = actions.onSettings, onDismiss = { showSettings = false })
    }
}

@Composable
private fun Viewfinder(
    state: UiState,
    settings: Settings,
    device: DeviceState,
    actions: Actions,
    modifier: Modifier,
) = Box(modifier) {
    val showPreview = state.phase == Phase.LIVE && settings.preview
    Preview(state, showPreview, actions.onTexture)
    TopReadouts(state, Modifier.align(Alignment.TopStart).fillMaxWidth())
    AnimatedContent(
        targetState = if (showPreview) null else state.phase,
        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
        label = "message",
        modifier = Modifier.fillMaxSize(),
    ) { phase ->
        if (phase != null) {
            Box(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 72.dp), contentAlignment = Alignment.CenterStart) {
                MessagePane(phase, state, settings, device, actions.onOpenAppSettings)
            }
        }
    }
}

/** Exactly what the PC receives, letterboxed to the streamed aspect ratio. */
@Composable
private fun Preview(
    state: UiState,
    visible: Boolean,
    onTexture: (TextureView) -> Unit,
) {
    val ratio = state.config?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
    AndroidView(
        factory = { TextureView(it).also(onTexture) },
        modifier =
            Modifier
                .fillMaxSize()
                .wrapContentSize()
                .aspectRatio(ratio)
                .graphicsLayer { alpha = if (visible) 1f else 0f },
    )
}

/** State on the left, the stream's numbers on the right, as on a camera's top display. */
@Composable
private fun TopReadouts(
    state: UiState,
    modifier: Modifier,
) = Row(
    modifier.padding(horizontal = 20.dp, vertical = 14.dp),
    verticalAlignment = Alignment.Bottom,
    horizontalArrangement = Arrangement.spacedBy(24.dp),
) {
    val (color, text) =
        when (state.phase) {
            Phase.LIVE -> Palette.Live to stringResource(R.string.state_live)
            Phase.STARTING -> Palette.Accent to stringResource(R.string.state_starting)
            Phase.ERROR, Phase.NO_PERMISSION -> Palette.Error to stringResource(R.string.state_error)
            Phase.WAITING -> Palette.TextDim to stringResource(R.string.state_waiting)
        }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StateDot(color)
            Text(text.uppercase(), style = ReadoutLabel, color = Palette.Text)
        }
        Text(
            if (state.phase == Phase.LIVE) elapsed(state.liveSince) else "-",
            style = ReadoutValue,
            color = if (state.phase == Phase.LIVE) Palette.Text else Palette.TextDim,
        )
    }
    Spacer(Modifier.weight(1f))
    val config = state.config
    val dash = "-"
    Readout(stringResource(R.string.readout_format), config?.let { "${it.width}x${it.height}" } ?: dash)
    Readout(stringResource(R.string.readout_fps), config?.fps?.toString() ?: dash, Modifier.width(36.dp))
    Readout(
        stringResource(R.string.readout_bitrate),
        config?.let { String.format(Locale.ROOT, "%.0f", it.bitrate / 1e6) } ?: dash,
        Modifier.width(52.dp),
    )
}

@Composable
private fun elapsed(since: Long): String {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(since) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val s = (now - since) / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
}

/** Each control shows its current value, so the rail doubles as a status panel. */
@Composable
private fun Rail(
    settings: Settings,
    landscape: Boolean,
    onChange: (Settings) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val controls: @Composable (Modifier) -> Unit = { cell ->
        RailControl(
            if (settings.portrait) R.drawable.ic_portrait else R.drawable.ic_landscape,
            stringResource(R.string.control_view),
            stringResource(if (settings.portrait) R.string.orientation_portrait else R.string.orientation_landscape),
            stringResource(if (settings.portrait) R.string.to_landscape else R.string.to_portrait),
            { onChange(settings.copy(portrait = !settings.portrait)) },
            cell,
        )
        RailControl(
            R.drawable.ic_switch_camera,
            stringResource(R.string.control_lens),
            stringResource(if (settings.frontLens) R.string.lens_front else R.string.lens_back),
            stringResource(R.string.switch_camera),
            { onChange(settings.copy(frontLens = !settings.frontLens)) },
            cell,
        )
        RailControl(
            R.drawable.ic_focus,
            stringResource(R.string.control_focus),
            stringResource(if (settings.autofocus) R.string.focus_auto else R.string.focus_locked),
            stringResource(if (settings.autofocus) R.string.focus_lock else R.string.focus_unlock),
            { onChange(settings.copy(autofocus = !settings.autofocus)) },
            cell,
        )
        RailControl(
            R.drawable.ic_settings,
            stringResource(R.string.control_more),
            stringResource(R.string.settings),
            stringResource(R.string.settings),
            onOpenSettings,
            cell,
        )
    }
    if (landscape) {
        Column(
            Modifier.fillMaxHeight().width(96.dp).background(Palette.Rail),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { controls(Modifier.fillMaxWidth()) }
    } else {
        Row(
            Modifier.fillMaxWidth().background(Palette.Rail).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) { controls(Modifier) }
    }
}

package re.isx.phonecam.ui

import android.content.res.Configuration
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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

@Composable
fun App(
    state: UiState,
    settings: Settings,
    actions: Actions,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    val showPreview = state.phase == Phase.LIVE && settings.preview
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        Modifier
            .fillMaxSize()
            // Tapping the live image hides the controls, for a clean view of the shot.
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = showPreview) {
                controlsVisible = !controlsVisible
            },
    ) {
        Preview(state, showPreview, actions.onTexture)

        AnimatedContent(
            targetState = if (showPreview) null else state.phase,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen",
            modifier = Modifier.fillMaxSize(),
        ) { phase ->
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
                when (phase) {
                    Phase.WAITING -> WaitingScreen(settings.port, landscape)
                    Phase.STARTING -> StatusCard(R.drawable.ic_lens, stringResource(R.string.starting_title), "", busy = true)
                    Phase.LIVE ->
                        StatusCard(
                            R.drawable.ic_eye_off,
                            stringResource(R.string.preview_off_title),
                            stringResource(R.string.preview_off_text),
                        )
                    Phase.ERROR ->
                        StatusCard(
                            R.drawable.ic_alert,
                            stringResource(R.string.error_title),
                            state.message,
                            iconTint = Palette.Error,
                        )
                    Phase.NO_PERMISSION ->
                        StatusCard(
                            R.drawable.ic_lock,
                            stringResource(R.string.permission_title),
                            stringResource(R.string.permission_text),
                            action = stringResource(R.string.permission_action) to actions.onOpenAppSettings,
                        )
                    null -> Unit
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible || !showPreview,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        ) {
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                StatusBar(state, Modifier.align(Alignment.TopStart))
                Controls(
                    settings,
                    landscape,
                    onChange = actions.onSettings,
                    onOpenSettings = { showSettings = true },
                    modifier = Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter),
                )
            }
        }
    }

    if (showSettings) {
        SettingsSheet(settings, state.config, onChange = actions.onSettings, onDismiss = { showSettings = false })
    }
}

/** Exactly what the PC receives, letterboxed to the streamed aspect ratio. */
@Composable
private fun Preview(
    state: UiState,
    visible: Boolean,
    onTexture: (TextureView) -> Unit,
) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "preview")
    val ratio = state.config?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
    AndroidView(
        factory = { TextureView(it).also(onTexture) },
        modifier =
            Modifier
                .fillMaxSize()
                .wrapContentSize()
                .aspectRatio(ratio)
                .graphicsLayer { this.alpha = alpha },
    )
}

@Composable
private fun StatusBar(
    state: UiState,
    modifier: Modifier = Modifier,
) = Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    GlassPill {
        val (color, text) =
            when (state.phase) {
                Phase.LIVE -> Palette.Live to stringResource(R.string.state_live)
                Phase.STARTING -> Palette.Starting to stringResource(R.string.state_starting)
                Phase.ERROR -> Palette.Error to stringResource(R.string.state_error)
                Phase.NO_PERMISSION -> Palette.Error to stringResource(R.string.state_no_permission)
                Phase.WAITING -> Palette.TextDim to stringResource(R.string.state_waiting)
            }
        PulsingDot(color)
        Text(text, style = MaterialTheme.typography.labelMedium)
        if (state.phase == Phase.LIVE) Timer(state.liveSince)
    }
    val config = state.config
    if (config != null && state.phase == Phase.LIVE) {
        GlassPill {
            Text(
                stringResource(R.string.stream_format, config.width, config.height, config.fps),
                style = MaterialTheme.typography.labelMedium.merge(MonoDigits),
            )
        }
    }
}

@Composable
private fun Timer(since: Long) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(since) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val s = (now - since) / 1000
    Text(
        String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60),
        style = MaterialTheme.typography.labelMedium.merge(MonoDigits),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Column on the right in landscape, row at the bottom in portrait. */
@Composable
private fun Controls(
    settings: Settings,
    landscape: Boolean,
    onChange: (Settings) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttons: @Composable () -> Unit = {
        GlassButton(
            if (settings.portrait) R.drawable.ic_landscape else R.drawable.ic_portrait,
            stringResource(if (settings.portrait) R.string.to_landscape else R.string.to_portrait),
            { onChange(settings.copy(portrait = !settings.portrait)) },
        )
        GlassButton(
            R.drawable.ic_switch_camera,
            stringResource(R.string.switch_camera),
            { onChange(settings.copy(frontLens = !settings.frontLens)) },
            size = 64.dp,
        )
        GlassButton(R.drawable.ic_settings, stringResource(R.string.settings), onOpenSettings)
    }
    if (landscape) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            buttons()
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            buttons()
        }
    }
}

/** Ripple clip that follows the caller's shape (set before this modifier). */
internal fun Modifier.clickableWithRipple(onClick: () -> Unit) =
    clickable(interactionSource = null, indication = ripple(), onClick = onClick)

package re.isx.phonecam

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.provider.Settings.Global
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import re.isx.phonecam.ui.Actions
import re.isx.phonecam.ui.App
import re.isx.phonecam.ui.PhoneCamTheme

/**
 * Owns the stream: the [Link] to the PC, the [Streamer] while connected, and the preview surface.
 * The UI ([App]) only renders [ui] and [settings] and calls back through [Actions].
 */
class MainActivity :
    ComponentActivity(),
    Link.Listener,
    TextureView.SurfaceTextureListener {
    private lateinit var prefs: SharedPreferences
    private var ui by mutableStateOf(UiState())
    private var settings by mutableStateOf(Settings())
    private var device by mutableStateOf(DeviceState())
    private var displayRotation = 0
    private var outputRotation = 0
    private lateinit var orientation: OrientationEventListener
    private var link: Link? = null
    private var streamer: Streamer? = null
    private var texture: TextureView? = null
    private var previewSurface: Surface? = null
    private var askedPermission = false

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) connect() else ui = UiState(Phase.NO_PERMISSION)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        settings = Settings.load(prefs)
        applyOrientation()
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        displayRotation = currentDisplayRotation()
        outputRotation = snapOutputRotation((360 - displayRotation) % 360)
        orientation =
            object : OrientationEventListener(this) {
                override fun onOrientationChanged(degrees: Int) {
                    if (degrees != ORIENTATION_UNKNOWN) updateOutputRotation(degrees)
                }
            }
        val actions =
            Actions(
                onTexture = ::attachTexture,
                onSettings = ::updateSettings,
                onOpenAppSettings = {
                    startActivity(Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                },
            )
        setContent { PhoneCamTheme { App(ui, settings, device, actions) } }
    }

    override fun onResume() {
        super.onResume()
        orientation.enable()
        // Sticky: the current battery state comes back at once, then every change.
        registerReceiver(power, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::onPower)
        contentResolver.registerContentObserver(Global.getUriFor(Global.ADB_ENABLED), false, adb)
        refreshUsbDebugging()
        when {
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> connect()
            // Asking on every resume would loop once the user picks "don't ask again".
            !askedPermission -> {
                askedPermission = true
                requestCamera.launch(Manifest.permission.CAMERA)
            }
            else -> ui = UiState(Phase.NO_PERMISSION)
        }
    }

    override fun onPause() {
        super.onPause()
        orientation.disable()
        unregisterReceiver(power)
        contentResolver.unregisterContentObserver(adb)
        disconnect()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        displayRotation = currentDisplayRotation()
        streamer?.previewRotation = displayRotation
    }

    // Link.Listener, called on the link thread. A link replaced since (new port) is ignored.
    override fun onWaiting(link: Link) = runOnUiThread { if (link === this.link && ui.phase != Phase.ERROR) ui = UiState(Phase.WAITING) }

    override fun onConnected(
        link: Link,
        config: StreamConfig,
    ) = runOnUiThread { if (link === this.link) startStreaming(config) }

    override fun onDisconnected(link: Link) =
        runOnUiThread {
            if (link !== this.link) return@runOnUiThread
            stopStreaming()
            ui = UiState(Phase.WAITING)
        }

    // TextureView.SurfaceTextureListener: the preview follows the view's surface.
    override fun onSurfaceTextureAvailable(
        st: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        previewSurface = Surface(st)
        updatePreview()
    }

    override fun onSurfaceTextureSizeChanged(
        st: SurfaceTexture,
        width: Int,
        height: Int,
    ) = updatePreview()

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        streamer?.setPreview(null, 0, 0)
        previewSurface?.release()
        previewSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    private fun attachTexture(view: TextureView) {
        texture = view
        view.surfaceTextureListener = this
        view.surfaceTexture?.let { onSurfaceTextureAvailable(it, view.width, view.height) }
    }

    private fun updateSettings(new: Settings) {
        val old = settings
        settings = new.copy(port = new.port.takeIf { it in Settings.PORTS } ?: Settings.DEFAULT_PORT)
        settings.save(prefs)
        if (old.portrait != settings.portrait) {
            applyOrientation()
            setOutputRotation(snapOutputRotation((360 - displayRotation) % 360, force = true))
        }
        if (old.frontLens != settings.frontLens) restartStreaming()
        if (old.preview != settings.preview) updatePreview()
        if (old.autofocus != settings.autofocus) streamer?.autofocus = settings.autofocus
        if (old.port != settings.port) {
            disconnect()
            connect()
        }
    }

    private fun connect() {
        if (link != null) return
        if (ui.phase == Phase.NO_PERMISSION) ui = UiState(Phase.WAITING)
        link = Link(settings.port, this).also { it.start() }
    }

    private fun disconnect() {
        link?.stop()
        link = null
        stopStreaming()
        if (ui.phase != Phase.NO_PERMISSION) ui = UiState(Phase.WAITING)
    }

    private fun startStreaming(config: StreamConfig) {
        val link = link ?: return
        ui = UiState(Phase.STARTING, config = config)
        val lens = if (settings.frontLens) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        streamer =
            Streamer(
                this,
                config,
                lens,
                outputRotation,
                displayRotation,
                settings.autofocus,
                link,
            ) { state, text -> runOnUiThread { onStreamState(config, state, text) } }
                .also { it.start() }
        updatePreview()
    }

    private fun onStreamState(
        config: StreamConfig,
        state: StreamState,
        text: String,
    ) {
        if (streamer == null) return // a late callback from a stopped streamer
        ui =
            when (state) {
                StreamState.LIVE -> UiState(Phase.LIVE, config = config, liveSince = ui.liveSince.takeIf { it > 0 } ?: now())
                StreamState.ERROR -> UiState(Phase.ERROR, message = text, config = config)
                StreamState.STARTING -> UiState(Phase.STARTING, config = config)
                StreamState.WAITING -> UiState(Phase.WAITING)
            }
    }

    private fun stopStreaming() {
        streamer?.stop()
        streamer = null
    }

    private fun restartStreaming() {
        val config = ui.config ?: return
        stopStreaming()
        startStreaming(config)
    }

    private fun updatePreview() {
        val view = texture ?: return
        view.surfaceTexture?.setDefaultBufferSize(view.width, view.height)
        streamer?.setPreview(previewSurface?.takeIf { settings.preview }, view.width, view.height)
    }

    private fun applyOrientation() {
        requestedOrientation =
            if (settings.portrait) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
    }

    /**
     * The stream follows the phone's physical position within the chosen mode (0/180 in portrait,
     * 90/270 in landscape), switching only once clearly past the other side so it never flickers.
     * [degrees] is clockwise from the natural position, as OrientationEventListener reports it.
     */
    private fun snapOutputRotation(
        degrees: Int,
        force: Boolean = false,
    ): Int {
        val choices = if (settings.portrait) listOf(0, 180) else listOf(90, 270)

        fun distance(a: Int) = minOf((degrees - a + 360) % 360, (a - degrees + 360) % 360)
        val current = (360 - outputRotation) % 360
        val nearest = choices.minBy(::distance)
        val physical = if (force || current !in choices || distance(nearest) < SWITCH_WITHIN_DEGREES) nearest else current
        return (360 - physical) % 360 // display rotation terms, like the renderer expects
    }

    private fun updateOutputRotation(degrees: Int) = setOutputRotation(snapOutputRotation(degrees))

    private fun setOutputRotation(value: Int) {
        if (value == outputRotation) return
        val turned = value % 180 != outputRotation % 180
        outputRotation = value
        // Portrait <-> landscape needs another camera size; a 180 degree flip only turns the image.
        if (turned) restartStreaming() else streamer?.outputRotation = value
    }

    @Suppress("DEPRECATION") // Display.rotation is the simple way on every supported version
    private fun currentDisplayRotation() = windowManager.defaultDisplay.rotation * 90

    private fun now() = SystemClock.elapsedRealtime()

    // Device checks for the waiting screen.
    private val power =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = onPower(intent)
        }

    private val adb =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refreshUsbDebugging()
        }

    private fun onPower(intent: Intent) {
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        device = device.copy(usbCable = plugged and BatteryManager.BATTERY_PLUGGED_USB != 0)
    }

    private fun refreshUsbDebugging() {
        device = device.copy(usbDebugging = Global.getInt(contentResolver, Global.ADB_ENABLED, 0) == 1)
    }

    private companion object {
        const val SWITCH_WITHIN_DEGREES = 50
    }
}

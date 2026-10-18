package re.isx.phonecam

import android.content.SharedPreferences

/** Where the stream is at, as shown on screen. */
enum class Phase { WAITING, STARTING, LIVE, ERROR, NO_PERMISSION }

data class UiState(
    val phase: Phase = Phase.WAITING,
    /** Error text for [Phase.ERROR]. */
    val message: String = "",
    /** What the PC asked for, known once connected. */
    val config: StreamConfig? = null,
    /** `SystemClock.elapsedRealtime()` when the stream went live. */
    val liveSince: Long = 0,
)

/** What the waiting screen checks: the two things the phone itself can see. */
data class DeviceState(
    val usbDebugging: Boolean = false,
    val usbCable: Boolean = false,
)

/** Phone-side settings; resolution, frame rate and bitrate come from the PC. */
data class Settings(
    val port: Int = DEFAULT_PORT,
    val preview: Boolean = true,
    val frontLens: Boolean = false,
    val portrait: Boolean = false,
    val autofocus: Boolean = true,
) {
    fun save(prefs: SharedPreferences) =
        prefs
            .edit()
            .putInt(KEY_PORT, port)
            .putBoolean(KEY_PREVIEW, preview)
            .putBoolean(KEY_FRONT, frontLens)
            .putBoolean(KEY_PORTRAIT, portrait)
            .putBoolean(KEY_AUTOFOCUS, autofocus)
            .apply()

    companion object {
        const val DEFAULT_PORT = 8765 // same default as the PC client
        val PORTS = 1024..65535

        private const val KEY_PORT = "port"
        private const val KEY_PREVIEW = "preview"
        private const val KEY_FRONT = "front_lens"
        private const val KEY_PORTRAIT = "portrait"
        private const val KEY_AUTOFOCUS = "autofocus"

        fun load(prefs: SharedPreferences) =
            Settings(
                port = prefs.getInt(KEY_PORT, DEFAULT_PORT),
                preview = prefs.getBoolean(KEY_PREVIEW, true),
                frontLens = prefs.getBoolean(KEY_FRONT, false),
                portrait = prefs.getBoolean(KEY_PORTRAIT, false),
                autofocus = prefs.getBoolean(KEY_AUTOFOCUS, true),
            )
    }
}

package re.isx.phonecam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface

/** What the stream reports to the activity. */
enum class StreamState { WAITING, STARTING, LIVE, ERROR }

/**
 * Camera frames go through the GPU ([Renderer]) into the hardware H.264 encoder, never through
 * the CPU, and each encoded access unit is sent to the PC as soon as it is out.
 */
class Streamer(
    private val context: Context,
    private val config: StreamConfig,
    private val lensFacing: Int,
    outputRotation: Int,
    previewRotation: Int,
    autofocus: Boolean,
    private val link: Link,
    private val onState: (StreamState, String) -> Unit,
) {
    /** Rotations in degrees, forwarded to the renderer (see [Renderer]). */
    var outputRotation = outputRotation
        set(value) {
            field = value
            renderer?.outputRotation = value
        }

    var previewRotation = previewRotation
        set(value) {
            field = value
            renderer?.previewRotation = value
        }

    /** Continuous autofocus, or focus once and keep it. Applies to the running stream. */
    var autofocus = autofocus
        set(value) {
            field = value
            handler.post { applyFocus(trigger = !value) }
        }

    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private var encoder: MediaCodec? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var renderer: Renderer? = null
    private var request: CaptureRequest.Builder? = null
    private var preview: Triple<Surface?, Int, Int> = Triple(null, 0, 0)
    private var buffer = ByteArray(0)

    fun start() {
        handler.post {
            try {
                open()
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
            }
        }
    }

    fun stop() {
        handler.post(::release)
        thread.quitSafely()
        thread.join()
    }

    /** Draws what is streamed into [surface] too, or stops when null. */
    fun setPreview(
        surface: Surface?,
        width: Int,
        height: Int,
    ) = handler.post {
        preview = Triple(surface, width, height)
        renderer?.setPreview(surface, width, height)
    }

    private fun fail(message: String) {
        onState(StreamState.ERROR, message)
        link.sendError(message)
        release()
    }

    @SuppressLint("MissingPermission") // the activity only streams once the permission is granted
    private fun open() {
        val manager = context.getSystemService(CameraManager::class.java)
        val id =
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == lensFacing
            } ?: return fail(context.getString(R.string.error_no_camera))
        val chars = manager.getCameraCharacteristics(id)
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val cameraSize =
            pickCameraSize(chars, sensorOrientation)
                ?: return fail(context.getString(R.string.error_fps, config.fps))
        // Prefer a fixed range ([30, 30] over [15, 30]): a steady rate keeps latency steady.
        val fpsRange =
            chars
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)!!
                .filter { it.upper == config.fps }
                .maxByOrNull { it.lower }
                ?: return fail(context.getString(R.string.error_fps, config.fps))

        val codec = createEncoder()
        encoder = codec
        val input = codec.createInputSurface()
        codec.start()
        val r =
            Renderer(
                input,
                config.width,
                config.height,
                cameraSize.width,
                cameraSize.height,
                sensorOrientation,
                lensFacing == CameraCharacteristics.LENS_FACING_FRONT,
            )
        renderer = r
        r.outputRotation = outputRotation
        r.previewRotation = previewRotation
        preview.let { (surface, w, h) -> r.setPreview(surface, w, h) }

        manager.openCamera(
            id,
            object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    startSession(device, r.cameraSurface, fpsRange)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    camera = null
                    fail(context.getString(R.string.error_camera_taken))
                }

                override fun onError(
                    device: CameraDevice,
                    error: Int,
                ) {
                    device.close()
                    camera = null
                    fail(context.getString(R.string.error_camera, error))
                }
            },
            handler,
        )
    }

    /** Constrained Baseline has no B-frames, so the PC decoder can output each frame as it arrives. */
    private fun createEncoder(): MediaCodec {
        for (baseline in listOf(true, false)) {
            // A failed configure can leave the codec unusable: each attempt gets a fresh one.
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                codec.setCallback(EncoderCallback(), handler)
                codec.configure(encoderFormat(baseline), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return codec
            } catch (e: Exception) {
                codec.release()
                if (!baseline) throw e
            }
        }
        error("unreachable")
    }

    private fun encoderFormat(baseline: Boolean) =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeInterval)
            if (baseline) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline)
            }
            // Low latency: no B-frames to reorder, realtime priority, one frame in, one frame out.
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_LATENCY, 1)
            // SPS/PPS with every keyframe, so the PC can start decoding at any of them.
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }

    /**
     * The smallest camera size whose upright, cropped image still covers the output, so nothing is
     * upscaled and the GPU does not process more pixels than needed. Falls back to the largest one.
     */
    private fun pickCameraSize(
        chars: CameraCharacteristics,
        sensorOrientation: Int,
    ): Size? {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val frameNs = 1_000_000_000L / config.fps
        val sizes =
            map
                .getOutputSizes(SurfaceTexture::class.java)
                .filter { map.getOutputMinFrameDuration(SurfaceTexture::class.java, it) <= frameNs }
        // Width and height of the upright image: swapped when the sensor is turned relative to it.
        val sideways = (sensorOrientation % 180 == 90) != (outputRotation % 180 == 90)
        return sizes
            .filter {
                val (w, h) = if (sideways) it.height to it.width else it.width to it.height
                minOf(w.toLong() * config.height, h.toLong() * config.width) >= config.width.toLong() * config.height
            }.minByOrNull { it.width * it.height } ?: sizes.maxByOrNull { it.width * it.height }
    }

    private fun startSession(
        device: CameraDevice,
        target: Surface,
        fpsRange: Range<Int>,
    ) {
        val callback =
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    request =
                        device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(target)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                            // Stabilization holds frames back to smooth motion: pure added latency here.
                            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                            set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                            set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
                        }
                    applyFocus(trigger = !autofocus)
                    onState(StreamState.LIVE, "")
                }

                override fun onConfigureFailed(s: CameraCaptureSession) =
                    fail(context.getString(R.string.error_session, config.width, config.height))
            }
        val outputs = listOf(OutputConfiguration(target))
        device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, handler::post, callback))
    }

    /**
     * Continuous focus, or a single focus scan that then stays locked (fixed-focus lenses ignore both).
     * Runs on the camera thread.
     */
    private fun applyFocus(trigger: Boolean) {
        val s = session ?: return
        val r = request ?: return
        try {
            if (autofocus) {
                r.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                r.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            } else {
                r.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                if (trigger) {
                    r.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                    s.capture(r.build(), null, handler)
                }
                r.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            }
            s.setRepeatingRequest(r.build(), null, handler)
        } catch (_: IllegalStateException) {
            // Session closed meanwhile: the next one applies the setting.
        }
    }

    private fun release() {
        session?.close()
        camera?.close()
        renderer?.release()
        encoder?.let {
            runCatching { it.stop() }
            it.release()
        }
        session = null
        camera = null
        renderer = null
        encoder = null
        request = null
    }

    private inner class EncoderCallback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(
            codec: MediaCodec,
            index: Int,
        ) = Unit // input comes from the Surface

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            val out = codec.getOutputBuffer(index)
            if (out != null && info.size > 0) {
                if (buffer.size < info.size) buffer = ByteArray(info.size)
                out.position(info.offset)
                out.get(buffer, 0, info.size)
                val key = info.flags and (MediaCodec.BUFFER_FLAG_KEY_FRAME or MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (!link.sendVideo(buffer, info.size, key)) {
                    codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                }
            }
            codec.releaseOutputBuffer(index, false)
        }

        override fun onError(
            codec: MediaCodec,
            e: MediaCodec.CodecException,
        ) = fail(context.getString(R.string.error_encoder, e.diagnosticInfo))

        override fun onOutputFormatChanged(
            codec: MediaCodec,
            format: MediaFormat,
        ) = Unit
    }
}

package re.isx.phonecam

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GPU pass between the camera and the encoder: rotates the camera image upright for the chosen
 * orientation, crops it to fill the output size, and draws it into the encoder and the preview.
 *
 * The camera's SurfaceTexture matrix already turns its buffers upright for the device's natural
 * orientation (portrait on phones), mirrored for the front lens. On top of that:
 * - the stream uses [outputRotation], from the phone's physical position, so it is upright for the
 *   PC even when the screen does not follow (most phones never show upside-down portrait), and is
 *   un-mirrored (apps mirror their self-view);
 * - the preview uses [previewRotation], the display rotation, so it reads like a window (and a
 *   mirror for the front lens) however the phone is held.
 */
class Renderer(
    encoderSurface: Surface,
    private val outWidth: Int,
    private val outHeight: Int,
    private val cameraWidth: Int,
    private val cameraHeight: Int,
    private val sensorOrientation: Int,
    private val frontLens: Boolean,
) {
    /** Degrees (0, 90, 180, 270) in display rotation terms, updated by the activity. */
    @Volatile var outputRotation = 0

    @Volatile var previewRotation = 0

    private val thread = HandlerThread("render").apply { start() }
    private val handler = Handler(thread.looper)
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private lateinit var config: EGLConfig
    private lateinit var context: android.opengl.EGLContext
    private lateinit var encoderEgl: EGLSurface
    private var previewEgl: EGLSurface? = null
    private var previewWidth = 0
    private var previewHeight = 0
    private var program = 0
    private var texture = 0
    private lateinit var surfaceTexture: SurfaceTexture
    private val texMatrix = FloatArray(16)
    private val quad = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val outputUvs = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val previewUvs = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    /** Where the camera draws; created on the render thread, ready once the constructor returns. */
    lateinit var cameraSurface: Surface
        private set

    init {
        runBlocking { setUp(encoderSurface) }
    }

    fun setPreview(
        surface: Surface?,
        width: Int,
        height: Int,
    ) = handler.post {
        previewEgl?.let { EGL14.eglDestroySurface(display, it) }
        previewEgl = surface?.let { EGL14.eglCreateWindowSurface(display, config, it, intArrayOf(EGL14.EGL_NONE), 0) }
        previewWidth = width
        previewHeight = height
    }

    fun release() {
        runBlocking {
            surfaceTexture.release()
            cameraSurface.release()
            previewEgl?.let { EGL14.eglDestroySurface(display, it) }
            EGL14.eglDestroySurface(display, encoderEgl)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        thread.quitSafely()
    }

    private fun setUp(encoderSurface: Surface) {
        EGL14.eglInitialize(display, null, 0, null, 0)
        val attribs =
            intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID,
                1,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No EGL config" }
        config = configs[0]!!
        context =
            EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        encoderEgl = EGL14.eglCreateWindowSurface(display, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, encoderEgl, encoderEgl, context)

        program = buildProgram()
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        surfaceTexture =
            SurfaceTexture(texture).apply {
                setDefaultBufferSize(cameraWidth, cameraHeight)
                setOnFrameAvailableListener({ draw() }, handler)
            }
        cameraSurface = Surface(surfaceTexture)
    }

    private fun draw() {
        EGL14.eglMakeCurrent(display, encoderEgl, encoderEgl, context)
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
        updateUvs(outputUvs, outputRotation, outWidth, outHeight, unmirror = frontLens)
        drawQuad(outputUvs, outWidth, outHeight)
        EGLExt.eglPresentationTimeANDROID(display, encoderEgl, surfaceTexture.timestamp)
        EGL14.eglSwapBuffers(display, encoderEgl)
        previewEgl?.let {
            EGL14.eglMakeCurrent(display, it, it, context)
            updateUvs(previewUvs, previewRotation, previewWidth, previewHeight, unmirror = false)
            drawQuad(previewUvs, previewWidth, previewHeight)
            EGL14.eglSwapBuffers(display, it)
        }
    }

    /** Maps the target's corners to the upright camera image: un-mirror, crop to fill, then undo the rotation. */
    private fun updateUvs(
        uvs: FloatBuffer,
        rot: Int,
        width: Int,
        height: Int,
        unmirror: Boolean,
    ) {
        if (width == 0 || height == 0) return
        val (naturalW, naturalH) = if (sensorOrientation % 180 == 90) cameraHeight to cameraWidth else cameraWidth to cameraHeight
        val (worldW, worldH) = if (rot % 180 == 90) naturalH to naturalW else naturalW to naturalH
        val scale = maxOf(width.toFloat() / worldW, height.toFloat() / worldH)
        val sx = width / scale / worldW
        val sy = height / scale / worldH
        uvs.clear()
        for (i in 0 until 4) {
            var x = (quad[i * 2] / 2) * sx
            val y = (quad[i * 2 + 1] / 2) * sy
            if (unmirror) x = -x
            // The image shown is the natural one turned counter-clockwise by `rot`, so sample it clockwise.
            val (nx, ny) =
                when (rot) {
                    90 -> y to -x
                    180 -> -x to -y
                    270 -> -y to x
                    else -> x to y
                }
            uvs.put(nx + 0.5f).put(ny + 0.5f)
        }
        uvs.position(0)
    }

    private fun drawQuad(
        uvs: FloatBuffer,
        width: Int,
        height: Int,
    ) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val uv = GLES20.glGetAttribLocation(program, "aUv")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad.position(0))
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, uvs.position(0))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun buildProgram(): Int {
        fun shader(
            type: Int,
            src: String,
        ) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
            GLES20.glLinkProgram(it)
        }
    }

    private fun runBlocking(block: () -> Unit) {
        var error: Throwable? = null
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try {
                block()
            } catch (t: Throwable) {
                error = t
            }
            done.countDown()
        }
        done.await()
        error?.let { throw it }
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142

        const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            attribute vec2 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vUv = (uTexMatrix * vec4(aUv, 0.0, 1.0)).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            varying vec2 vUv;
            void main() {
                gl_FragColor = texture2D(uTex, vUv);
            }
        """

        fun floatBuffer(vararg values: Float) =
            ByteBuffer
                .allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(values)
                .apply { position(0) }
    }
}

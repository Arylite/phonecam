package re.isx.phonecam

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** What the PC asks the phone to stream, received on every connection. */
data class StreamConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val keyframeInterval: Int,
)

/**
 * Connection to the PC client through the `adb reverse` tunnel, retried until [stop].
 * Wire format (big-endian), see docs/protocol.md.
 */
class Link(
    private val port: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onWaiting(link: Link)

        fun onConnected(
            link: Link,
            config: StreamConfig,
        )

        fun onDisconnected(link: Link)
    }

    @Volatile private var running = true

    @Volatile private var socket: Socket? = null
    private var out: DataOutputStream? = null

    // Flow control: frames sent but not yet acknowledged (decoded) by the PC.
    private val inFlight = AtomicInteger()
    private var needKeyframe = false
    private val thread = Thread(::run, "link")

    fun start() = thread.start()

    fun stop() {
        running = false
        socket?.close()
        thread.join()
    }

    /**
     * Sends one encoded access unit, or drops it when the PC is behind, so delay never builds up in
     * buffers along the way. After a drop, frames are skipped until the next keyframe (P-frames
     * need the ones before them); returns false when the caller should request one.
     */
    @Synchronized
    fun sendVideo(
        data: ByteArray,
        size: Int,
        keyframe: Boolean,
    ): Boolean {
        if (out == null) return true
        if (!keyframe && (needKeyframe || inFlight.get() >= MAX_IN_FLIGHT)) {
            val first = !needKeyframe
            needKeyframe = true
            return !first
        }
        needKeyframe = false
        inFlight.incrementAndGet()
        send(VIDEO, data, size)
        return true
    }

    fun sendError(message: String) = message.toByteArray().let { send(ERROR, it, it.size) }

    @Synchronized
    private fun send(
        type: Int,
        data: ByteArray,
        size: Int,
    ) {
        val o = out ?: return
        try {
            o.writeByte(type)
            o.writeInt(size)
            o.write(data, 0, size)
            o.flush()
        } catch (_: IOException) {
            socket?.close() // the reader thread notices and reconnects
        }
    }

    private fun run() {
        while (running) {
            try {
                Socket().use { s ->
                    socket = s
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
                    val o = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1 shl 16))
                    o.write(MAGIC)
                    o.writeByte(VERSION)
                    o.flush()
                    // Without the PC client, adb accepts and closes at once: this read fails and we retry.
                    val i = DataInputStream(s.getInputStream())
                    val config =
                        StreamConfig(
                            width = i.readUnsignedShort(),
                            height = i.readUnsignedShort(),
                            fps = i.readUnsignedByte(),
                            bitrate = i.readInt(),
                            keyframeInterval = i.readUnsignedByte(),
                        )
                    synchronized(this) {
                        out = o
                        inFlight.set(0)
                        needKeyframe = false
                    }
                    listener.onConnected(this, config)
                    try {
                        while (i.read() >= 0) inFlight.decrementAndGet() // one byte per frame the PC has decoded
                    } finally {
                        synchronized(this) { out = null }
                        listener.onDisconnected(this)
                    }
                }
            } catch (_: IOException) {
                // Tunnel or client not there yet.
            }
            socket = null
            if (running) {
                listener.onWaiting(this)
                Thread.sleep(RETRY_MS)
            }
        }
    }

    private companion object {
        val MAGIC = "PCAM".toByteArray()
        const val VERSION = 1
        const val VIDEO = 1
        const val ERROR = 2
        const val CONNECT_TIMEOUT_MS = 1000

        // Two frames in flight hide the round trip; more would only queue up as delay.
        const val MAX_IN_FLIGHT = 2
        const val RETRY_MS = 1000L
    }
}

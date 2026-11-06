package ro.rdstr.os.cast

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import ro.rdstr.car.CarEvent
import ro.rdstr.car.CarEvent.PhoneCast.State
import ro.rdstr.car.MpegTs
import ro.rdstr.car.Rtp
import ro.rdstr.car.Rtsp
import ro.rdstr.car.WfdSink
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One phone's screen: the RTSP dialogue with the phone ([WfdSink]) on one thread, the RTP stream on another, the
 * picture decoded by the hardware decoder straight onto [surface] (no copy through memory, each frame shown as soon
 * as it is decoded) and the sound written to an AudioTrack. A lost packet asks the phone for a whole picture.
 */
class CastSession(
    private val source: InetAddress,
    private val port: Int,
    private val phone: String,
    private val onState: (CarEvent.PhoneCast) -> Unit,
) {
    private val sink = WfdSink(RTP_PORT)
    @Volatile private var closed = false
    private var control: Socket? = null
    private var out: OutputStream? = null
    private var media: DatagramSocket? = null
    private var decoder: MediaCodec? = null
    private var audio: AudioTrack? = null
    private var lastPictureRequest = 0L
    private var shown: State? = null

    /** Set while the phone screen view is shown; a new surface starts the decoder again on the next whole picture. */
    @Volatile var surface: Surface? = null
        set(value) {
            if (field == value) return
            field = value
            synchronized(this) { releaseDecoder() }
            askForPicture()
        }

    fun start() {
        Thread(::dialogue, "rdstr-cast-rtsp").start()
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { control?.close() }
        runCatching { media?.close() }
        synchronized(this) {
            releaseDecoder()
            audio?.run { runCatching { stop() }; release() }
            audio = null
        }
    }

    private fun dialogue() {
        val socket = connect() ?: return end()
        control = socket
        out = socket.getOutputStream()
        val input = socket.getInputStream()
        val buffer = StringBuilder()
        val bytes = ByteArray(READ_CHUNK)
        while (!closed) {
            val n = runCatching { input.read(bytes) }.getOrDefault(-1)
            if (n < 0) break
            buffer.append(String(bytes, 0, n, Charsets.ISO_8859_1))
            while (true) {
                val (message, used) = Rtsp.parse(buffer.toString()) ?: break
                buffer.delete(0, used)
                sink.onMessage(message).forEach(::write)
                report()
            }
        }
        end()
    }

    /** The phone ended it or the link broke: the session closes and the receiver is findable again. */
    private fun end() {
        if (closed) return
        close()
        onState(CarEvent.PhoneCast(State.VISIBLE))
    }

    /** The phone opens its control port a moment after the Wi-Fi Direct link is up. */
    private fun connect(): Socket? {
        repeat(CONNECT_TRIES) {
            if (closed) return null
            runCatching { return Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(source, port), CONNECT_TIMEOUT_MS) } }
            Thread.sleep(CONNECT_WAIT_MS)
        }
        Log.w(TAG, "no RTSP from $source:$port")
        return null
    }

    @Synchronized private fun write(text: String) {
        runCatching { out?.run { write(text.toByteArray(Charsets.ISO_8859_1)); flush() } }.onFailure { Log.w(TAG, "write", it) }
    }

    private fun report() {
        val state = sink.state
        if (state == shown) return
        shown = state
        if (state == State.LIVE) Thread(::stream, "rdstr-cast-rtp").start()
        if (state == State.OFF) end() else onState(CarEvent.PhoneCast(state, phone))
    }

    private fun stream() {
        val socket = runCatching { DatagramSocket(RTP_PORT).apply { receiveBufferSize = RECEIVE_BUFFER } }
            .getOrElse { Log.w(TAG, "udp", it); return }
        media = socket
        val demux = MpegTs()
        val packet = DatagramPacket(ByteArray(MAX_DATAGRAM), MAX_DATAGRAM)
        while (!closed) {
            if (runCatching { socket.receive(packet) }.isFailure) break
            val data = packet.data
            var offset = Rtp.payloadOffset(data, packet.length) ?: continue
            while (offset + MpegTs.PACKET <= packet.length) {
                for (frame in demux.push(data, offset)) if (frame.kind == MpegTs.Kind.VIDEO) picture(frame) else sound(frame.data)
                offset += MpegTs.PACKET
            }
            if (demux.lost) {
                demux.lost = false
                askForPicture()
            }
        }
    }

    private fun picture(frame: MpegTs.Frame): Unit = synchronized(this) {
        if (closed) return
        val codec = decoder ?: startDecoder() ?: return
        val index = codec.dequeueInputBuffer(INPUT_WAIT_US)
        if (index >= 0) {
            val input = codec.getInputBuffer(index)!!
            input.clear()
            val size = if (frame.data.size <= input.capacity()) frame.data.size else 0
            input.put(frame.data, 0, size)
            codec.queueInputBuffer(index, 0, size, frame.timeUs, 0)
        }
        val info = MediaCodec.BufferInfo()
        while (true) {
            val done = codec.dequeueOutputBuffer(info, 0)
            if (done == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (done >= 0) codec.releaseOutputBuffer(done, true)
        }
    }

    private fun startDecoder(): MediaCodec? {
        val target = surface ?: return null
        return runCatching {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, sink.width, sink.height), target, null, 0)
                setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                start()
            }
        }.onFailure { Log.w(TAG, "decoder", it) }.getOrNull()?.also { decoder = it }
    }

    private fun releaseDecoder() {
        decoder?.run { runCatching { stop() }; release() }
        decoder = null
    }

    private fun sound(samples: ByteArray): Unit = synchronized(this) {
        if (closed) return
        val track = audio ?: AudioTrack(
            AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2, AudioTrack.MODE_STREAM,
        ).also { it.play(); audio = it }
        // never waits: a late sample is dropped rather than holding the picture back
        track.write(samples, 0, samples.size, AudioTrack.WRITE_NON_BLOCKING)
        Unit
    }

    /** At most one a second: the phone answers with a whole picture (IDR). */
    private fun askForPicture() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPictureRequest < PICTURE_REQUEST_GAP_MS || sink.state != State.LIVE) return
        lastPictureRequest = now
        write(sink.pictureRequest())
    }

    private companion object {
        const val TAG = "rdstr.cast"
        const val RTP_PORT = 19000
        const val RECEIVE_BUFFER = 1 shl 20
        const val MAX_DATAGRAM = 65_536
        const val READ_CHUNK = 4096
        const val INPUT_WAIT_US = 10_000L
        const val SAMPLE_RATE = 48_000
        const val CONNECT_TRIES = 20
        const val CONNECT_WAIT_MS = 300L
        const val CONNECT_TIMEOUT_MS = 2_000
        const val PICTURE_REQUEST_GAP_MS = 1_000L
    }
}

package ro.rdstr.car

/** One RTSP message: its first line, its headers by lower-case name, its body. */
data class RtspMessage(val firstLine: String, val headers: Map<String, String>, val body: String = "") {
    val isResponse get() = firstLine.startsWith("RTSP/")
    val method get() = firstLine.substringBefore(' ')
    val cseq get() = headers["cseq"]?.trim()?.toIntOrNull() ?: 0
}

object Rtsp {
    /** The first whole message in [text] and the characters it used, or null while it is still arriving. */
    fun parse(text: String): Pair<RtspMessage, Int>? {
        val end = text.indexOf("\r\n\r\n")
        if (end < 0) return null
        val lines = text.substring(0, end).split("\r\n")
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyEnd = end + 4 + length
        if (text.length < bodyEnd) return null
        return RtspMessage(lines[0], headers, text.substring(end + 4, bodyEnd)) to bodyEnd
    }

    fun format(firstLine: String, cseq: Int, headers: List<Pair<String, String>> = emptyList(), body: String = ""): String = buildString {
        append(firstLine).append("\r\nCSeq: ").append(cseq).append("\r\n")
        headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
        if (body.isNotEmpty()) append("Content-Type: text/parameters\r\nContent-Length: ").append(body.length).append("\r\n")
        append("\r\n").append(body)
    }
}

/**
 * The unit as a Miracast (Wi-Fi Display) sink: it answers the phone's RTSP requests, offers what its decoder and
 * speakers take, then asks for the stream (SETUP, PLAY) when the phone triggers it. Pure: messages in, messages out;
 * the socket, the UDP stream and the decoder are the host's. Video: H.264 Constrained Baseline 3.1, 640x480p60 or
 * 1280x720p30 (CEA), the screen being 1024x600; sound: LPCM 48 kHz stereo; no content protection, no touch back.
 */
class WfdSink(private val rtpPort: Int) {
    var state = CarEvent.PhoneCast.State.CONNECTING
        private set
    var width = 1280
        private set
    var height = 720
        private set
    private var url = ""
    private var session = ""
    private var cseq = 0
    private var setupSeq = -1
    private var playSeq = -1

    fun onMessage(m: RtspMessage): List<String> = if (m.isResponse) onResponse(m) else onRequest(m)

    /** A whole picture asked for, after packets were lost and the decoder has nothing to build on. */
    fun pictureRequest(): String = Rtsp.format("SET_PARAMETER $LOCAL RTSP/1.0", ++cseq, sessionHeader(), "wfd_idr_request\r\n")

    private fun onRequest(m: RtspMessage): List<String> {
        val ok = "RTSP/1.0 200 OK"
        return when (m.method) {
            "OPTIONS" -> listOf(
                Rtsp.format(ok, m.cseq, listOf("Public" to "org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER")),
                Rtsp.format("OPTIONS * RTSP/1.0", ++cseq, listOf("Require" to "org.wfa.wfd1.0")),
            )
            // with no body it is the phone's keep-alive
            "GET_PARAMETER" -> listOf(Rtsp.format(ok, m.cseq, sessionHeader(), answers(m.body)))
            "SET_PARAMETER" -> {
                val params = params(m.body)
                params["wfd_presentation_url"]?.let { url = it.substringBefore(' ') }
                params["wfd_video_formats"]?.let { chosen(it) }
                listOf(Rtsp.format(ok, m.cseq, sessionHeader())) + when (params["wfd_trigger_method"]?.uppercase()) {
                    "SETUP" -> listOf(Rtsp.format("SETUP $url RTSP/1.0", ++cseq, listOf("Transport" to "RTP/AVP/UDP;unicast;client_port=$rtpPort")).also { setupSeq = cseq })
                    "PLAY" -> listOf(play())
                    "TEARDOWN" -> listOf(Rtsp.format("TEARDOWN $url RTSP/1.0", ++cseq, sessionHeader())).also { state = CarEvent.PhoneCast.State.OFF }
                    else -> emptyList()
                }
            }
            else -> listOf(Rtsp.format("RTSP/1.0 501 Not Implemented", m.cseq))
        }
    }

    private fun onResponse(m: RtspMessage): List<String> = when (m.cseq) {
        setupSeq -> {
            session = m.headers["session"]?.substringBefore(';')?.trim().orEmpty()
            listOf(play())
        }
        playSeq -> {
            if (m.firstLine.contains(" 200 ")) state = CarEvent.PhoneCast.State.LIVE
            emptyList()
        }
        else -> emptyList()
    }

    private fun play() = Rtsp.format("PLAY $url RTSP/1.0", ++cseq, sessionHeader()).also { playSeq = cseq }

    private fun sessionHeader() = if (session.isEmpty()) emptyList() else listOf("Session" to session)

    private fun answers(body: String): String = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString("") { "$it: ${capabilities[it.lowercase()] ?: "none"}\r\n" }

    // native 1280x720p30 (CEA index 5), Constrained Baseline, level 3.1, CEA bits 0 and 5
    private val capabilities = mapOf(
        "wfd_video_formats" to "28 00 01 01 00000021 00000000 00000000 00 0000 0000 00 none none",
        "wfd_audio_codecs" to "LPCM 00000002 00",
        "wfd_client_rtp_ports" to "RTP/AVP/UDP;unicast $rtpPort 0 mode=play",
    )

    private fun params(body: String) = body.lines().filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }

    /** The resolution the phone picked: the lowest bit set in its CEA, VESA or HH mask. */
    private fun chosen(formats: String) {
        val fields = formats.split(' ').filter { it.isNotEmpty() }
        if (fields.size < 7) return
        val tables = listOf(CEA, VESA, HH)
        for (t in tables.indices) {
            val mask = fields[4 + t].toLongOrNull(16) ?: continue
            if (mask == 0L) continue
            val bit = (0 until 32).first { mask and (1L shl it) != 0L }
            tables[t].getOrNull(bit)?.let { (w, h) -> width = w; height = h }
            return
        }
    }

    private companion object {
        const val LOCAL = "rtsp://localhost/wfd1.0"
        val CEA = listOf(640 to 480, 720 to 480, 720 to 480, 720 to 576, 720 to 576, 1280 to 720, 1280 to 720, 1920 to 1080, 1920 to 1080, 1920 to 1080, 1280 to 720, 1280 to 720, 1920 to 1080)
        val VESA = listOf(800 to 600, 800 to 600, 1024 to 768, 1024 to 768, 1152 to 864, 1152 to 864, 1280 to 768, 1280 to 768, 1280 to 800, 1280 to 800)
        val HH = listOf(800 to 480, 800 to 480, 854 to 480, 854 to 480, 864 to 480, 864 to 480, 640 to 360, 640 to 360, 960 to 540, 960 to 540, 848 to 480, 848 to 480)
    }
}

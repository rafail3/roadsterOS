package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WfdTest {
    private fun request(method: String, uri: String, cseq: Int, headers: String = "", body: String = ""): String {
        val length = if (body.isEmpty()) "" else "Content-Type: text/parameters\r\nContent-Length: ${body.length}\r\n"
        return "$method $uri RTSP/1.0\r\nCSeq: $cseq\r\n$headers$length\r\n$body"
    }

    private fun response(cseq: Int, headers: String = "") = "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n$headers\r\n"

    private fun feed(sink: WfdSink, text: String): List<RtspMessage> {
        val (message, used) = Rtsp.parse(text)!!
        assertEquals(text.length, used)
        return sink.onMessage(message).map { Rtsp.parse(it)!!.first }
    }

    @Test
    fun `a message is read whole or not at all, and two in a row one after the other`() {
        val options = request("OPTIONS", "*", 1, "Require: org.wfa.wfd1.0\r\n")
        assertNull(Rtsp.parse(options.dropLast(1)))
        val body = "wfd_video_formats\r\n"
        val get = request("GET_PARAMETER", "rtsp://localhost/wfd1.0", 2, body = body)
        assertNull(Rtsp.parse(get.dropLast(3)))
        val (first, used) = Rtsp.parse(options + get)!!
        assertEquals("OPTIONS * RTSP/1.0", first.firstLine)
        assertEquals(body, Rtsp.parse((options + get).substring(used))!!.first.body)
    }

    @Test
    fun `the source's OPTIONS is answered, then the sink asks its own`() {
        val sink = WfdSink(rtpPort = 19000)
        val out = feed(sink, request("OPTIONS", "*", 1, "Require: org.wfa.wfd1.0\r\n"))
        assertEquals("RTSP/1.0 200 OK", out[0].firstLine)
        assertEquals("1", out[0].headers["cseq"])
        assertTrue(out[0].headers["public"]!!.contains("org.wfa.wfd1.0"))
        assertTrue(out[1].firstLine.startsWith("OPTIONS * RTSP/1.0"))
        assertEquals("org.wfa.wfd1.0", out[1].headers["require"])
    }

    @Test
    fun `the capabilities asked are answered, anything unknown with none`() {
        val sink = WfdSink(rtpPort = 19000)
        val body = "wfd_video_formats\r\nwfd_audio_codecs\r\nwfd_client_rtp_ports\r\nwfd_uibc_capability\r\nwfd_something_new\r\n"
        val reply = feed(sink, request("GET_PARAMETER", "rtsp://localhost/wfd1.0", 2, body = body)).single()
        val answers = reply.body.lines().filter { it.isNotBlank() }.associate { it.substringBefore(":") to it.substringAfter(": ") }
        assertEquals(5, answers.size)
        assertTrue(answers["wfd_video_formats"]!!.startsWith("28 00 01 01 00000021"))
        assertEquals("LPCM 00000002 00", answers["wfd_audio_codecs"])
        assertEquals("RTP/AVP/UDP;unicast 19000 0 mode=play", answers["wfd_client_rtp_ports"])
        assertEquals("none", answers["wfd_uibc_capability"])
        assertEquals("none", answers["wfd_something_new"])
        assertEquals(reply.body.length.toString(), reply.headers["content-length"])
    }

    @Test
    fun `the source's choice is kept, its SETUP trigger starts the stream with SETUP then PLAY`() {
        val sink = WfdSink(rtpPort = 19000)
        val chosen = "wfd_video_formats: 00 00 01 01 00000020 00000000 00000000 00 0000 0000 00 none none\r\n" +
            "wfd_audio_codecs: LPCM 00000002 00\r\n" +
            "wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0/streamid=0 none\r\n"
        assertEquals("RTSP/1.0 200 OK", feed(sink, request("SET_PARAMETER", "rtsp://localhost/wfd1.0", 3, body = chosen)).single().firstLine)
        assertEquals(1280, sink.width)
        assertEquals(720, sink.height)

        val trigger = feed(sink, request("SET_PARAMETER", "rtsp://localhost/wfd1.0", 4, body = "wfd_trigger_method: SETUP\r\n"))
        assertEquals("RTSP/1.0 200 OK", trigger[0].firstLine)
        assertEquals("SETUP rtsp://192.168.49.1/wfd1.0/streamid=0 RTSP/1.0", trigger[1].firstLine)
        assertEquals("RTP/AVP/UDP;unicast;client_port=19000", trigger[1].headers["transport"])
        assertEquals(CarEvent.PhoneCast.State.CONNECTING, sink.state)

        val setupSeq = trigger[1].headers["cseq"]!!.toInt()
        val play = feed(sink, response(setupSeq, "Session: 6B8B4567;timeout=30\r\nTransport: RTP/AVP/UDP;unicast;client_port=19000;server_port=1028\r\n")).single()
        assertEquals("PLAY rtsp://192.168.49.1/wfd1.0/streamid=0 RTSP/1.0", play.firstLine)
        assertEquals("6B8B4567", play.headers["session"])
        assertEquals(emptyList(), feed(sink, response(play.headers["cseq"]!!.toInt(), "Session: 6B8B4567\r\n")))
        assertEquals(CarEvent.PhoneCast.State.LIVE, sink.state)
    }

    @Test
    fun `a keep-alive is answered with the session, a teardown trigger ends it`() {
        val sink = WfdSink(rtpPort = 19000)
        feed(sink, request("SET_PARAMETER", "rtsp://localhost/wfd1.0", 3, body = "wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0/streamid=0 none\r\n"))
        val setup = feed(sink, request("SET_PARAMETER", "rtsp://localhost/wfd1.0", 4, body = "wfd_trigger_method: SETUP\r\n"))[1]
        val play = feed(sink, response(setup.headers["cseq"]!!.toInt(), "Session: abc;timeout=30\r\n")).single()
        feed(sink, response(play.headers["cseq"]!!.toInt()))

        val alive = feed(sink, request("GET_PARAMETER", "rtsp://localhost/wfd1.0", 9, "Session: abc\r\n")).single()
        assertEquals("9", alive.headers["cseq"])
        assertEquals("abc", alive.headers["session"])

        val end = feed(sink, request("SET_PARAMETER", "rtsp://localhost/wfd1.0", 10, body = "wfd_trigger_method: TEARDOWN\r\n"))
        assertEquals("TEARDOWN rtsp://192.168.49.1/wfd1.0/streamid=0 RTSP/1.0", end[1].firstLine)
        assertEquals(CarEvent.PhoneCast.State.OFF, sink.state)
    }

    @Test
    fun `a whole picture can be asked for after lost packets`() {
        val sink = WfdSink(rtpPort = 19000)
        val ask = Rtsp.parse(sink.pictureRequest())!!.first
        assertEquals("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", ask.firstLine)
        assertEquals("wfd_idr_request\r\n", ask.body)
    }
}

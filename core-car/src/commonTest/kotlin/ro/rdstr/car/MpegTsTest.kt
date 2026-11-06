package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpegTsTest {
    private val video = 0x1011
    private val audio = 0x1100
    private val pmt = 0x100

    /** One 188-byte packet: header, then [payload] padded with an adaptation field when short. */
    private fun packet(pid: Int, start: Boolean, cc: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(188) { 0xFF.toByte() }
        out[0] = 0x47
        out[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte()
        out[2] = pid.toByte()
        val room = 184
        if (payload.size == room) {
            out[3] = (0x10 or (cc and 15)).toByte()
            payload.copyInto(out, 4)
        } else {
            out[3] = (0x30 or (cc and 15)).toByte()
            val adaptation = room - payload.size - 1
            out[4] = adaptation.toByte()
            if (adaptation > 0) out[5] = 0
            payload.copyInto(out, 5 + adaptation)
        }
        return out
    }

    private fun section(tableId: Int, body: ByteArray): ByteArray {
        val length = body.size + 5 + 4
        return byteArrayOf(0, tableId.toByte(), (0xB0 or (length shr 8)).toByte(), length.toByte(), 0, 1, 0xC1.toByte(), 0, 0) + body + ByteArray(4)
    }

    private val pat = packet(0, true, 0, section(0, byteArrayOf(0, 1, (0xE0 or (pmt shr 8)).toByte(), pmt.toByte())))
    private val pmtPacket = packet(pmt, true, 0, section(2, byteArrayOf(
        (0xE0 or (video shr 8)).toByte(), video.toByte(), 0xF0.toByte(), 0,
        0x1B, (0xE0 or (video shr 8)).toByte(), video.toByte(), 0xF0.toByte(), 0,
        0x83.toByte(), (0xE0 or (audio shr 8)).toByte(), audio.toByte(), 0xF0.toByte(), 0,
    )))

    private fun pes(streamId: Int, ptsTicks: Long, data: ByteArray, sized: Boolean): ByteArray {
        val pts = byteArrayOf(
            (0x21 or ((ptsTicks shr 29) and 0x0E).toInt()).toByte(), (ptsTicks shr 22).toByte(),
            (((ptsTicks shr 14) and 0xFE) or 1).toByte(), (ptsTicks shr 7).toByte(), (((ptsTicks shl 1) and 0xFE) or 1).toByte(),
        )
        val length = if (sized) data.size + 8 else 0
        return byteArrayOf(0, 0, 1, streamId.toByte(), (length shr 8).toByte(), length.toByte(), 0x80.toByte(), 0x80.toByte(), 5) + pts + data
    }

    @Test
    fun `a video frame split over packets comes out whole, with its time, when the next one starts`() {
        val demux = MpegTs()
        assertEquals(emptyList(), demux.push(pat) + demux.push(pmtPacket))
        val frame = ByteArray(300) { (it % 251).toByte() }
        val first = pes(0xE0, 90_000, frame, sized = false)
        val out = demux.push(packet(video, true, 0, first.copyOfRange(0, 184))) +
            demux.push(packet(video, false, 1, first.copyOfRange(184, first.size)))
        assertEquals(emptyList(), out)
        val next = demux.push(packet(video, true, 2, pes(0xE0, 93_000, byteArrayOf(9), sized = false)))
        val got = next.single()
        assertEquals(MpegTs.Kind.VIDEO, got.kind)
        assertEquals(1_000_000L, got.timeUs)
        assertContentEquals(frame, got.data)
    }

    @Test
    fun `sound comes out as soon as its sized packet is complete, as little-endian samples`() {
        val demux = MpegTs()
        demux.push(pat); demux.push(pmtPacket)
        // the LPCM header of 4 bytes, then two big-endian stereo samples
        val lpcm = byteArrayOf(0xA0.toByte(), 6, 0, 0x49) + byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val got = demux.push(packet(audio, true, 0, pes(0xBD, 0, lpcm, sized = true))).single()
        assertEquals(MpegTs.Kind.AUDIO, got.kind)
        assertContentEquals(byteArrayOf(0x34, 0x12, 0x78, 0x56), got.data)
    }

    @Test
    fun `a lost packet drops the frame it belonged to and is reported`() {
        val demux = MpegTs()
        demux.push(pat); demux.push(pmtPacket)
        val first = pes(0xE0, 90_000, ByteArray(300), sized = false)
        demux.push(packet(video, true, 0, first.copyOfRange(0, 184)))
        demux.push(packet(video, false, 2, first.copyOfRange(184, first.size)))
        assertTrue(demux.lost)
        assertEquals(emptyList(), demux.push(packet(video, true, 3, pes(0xE0, 93_000, byteArrayOf(9), sized = false))))
    }

    @Test
    fun `the TS packets are taken out of an RTP packet, past its header, sources and extension`() {
        val ts = ByteArray(188 * 2) { 7 }
        val plain = byteArrayOf(0x80.toByte(), 33, 0, 5, 0, 0, 0, 0, 0, 0, 0, 1) + ts
        assertEquals(12, Rtp.payloadOffset(plain, plain.size))
        assertEquals(5, Rtp.sequence(plain))
        // one contributing source and a one-word extension
        val extended = byteArrayOf(0x91.toByte(), 33, 0, 6, 0, 0, 0, 0, 0, 0, 0, 1) + ByteArray(4) + byteArrayOf(0, 0, 0, 1) + ByteArray(4) + ts
        assertEquals(12 + 4 + 8, Rtp.payloadOffset(extended, extended.size))
        assertNull(Rtp.payloadOffset(byteArrayOf(0x80.toByte(), 33), 2))
    }
}

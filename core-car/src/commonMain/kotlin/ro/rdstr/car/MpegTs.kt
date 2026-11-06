package ro.rdstr.car

/** The RTP header in front of the phone's MPEG-TS packets. */
object Rtp {
    /** Where the payload starts, past the contributing sources and an extension; null for a damaged packet. */
    fun payloadOffset(p: ByteArray, length: Int): Int? {
        if (length < 12 || (p[0].toInt() and 0xC0) != 0x80) return null
        var offset = 12 + 4 * (p[0].toInt() and 0x0F)
        if (p[0].toInt() and 0x10 != 0) {
            if (length < offset + 4) return null
            offset += 4 + 4 * (((p[offset + 2].toInt() and 0xFF) shl 8) or (p[offset + 3].toInt() and 0xFF))
        }
        return offset.takeIf { it <= length }
    }

    fun sequence(p: ByteArray) = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
}

/**
 * The MPEG transport stream of a Miracast phone, taken apart: the program table finds the H.264 picture and the LPCM
 * sound, their PES packets are put back together and come out as whole frames with their time. A frame is complete
 * when its packet says how long it is (the sound) or when the next one starts (the picture, which carries no length).
 * A missing packet drops its frame and raises [lost], so the host can ask the phone for a whole picture.
 */
class MpegTs {
    enum class Kind { VIDEO, AUDIO }

    class Frame(val kind: Kind, val timeUs: Long, val data: ByteArray)

    /** A packet went missing since the host last cleared this. */
    var lost = false

    private class Pes(val kind: Kind, val timeUs: Long, val expected: Int) {
        var data = ByteArray(if (expected > 0) expected else 64 * 1024)
        var size = 0
        var broken = false
        fun add(p: ByteArray, from: Int, to: Int) {
            val n = to - from
            if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
            p.copyInto(data, size, from, to)
            size += n
        }
    }

    private var pmtPid = -1
    private var videoPid = -1
    private var audioPid = -1
    private val open = HashMap<Int, Pes>()
    private val continuity = HashMap<Int, Int>()

    /** One 188-byte packet at [offset]; the frames it completes. */
    fun push(p: ByteArray, offset: Int = 0): List<Frame> {
        if (p[offset].toInt() != SYNC) return emptyList()
        val pid = ((p[offset + 1].toInt() and 0x1F) shl 8) or (p[offset + 2].toInt() and 0xFF)
        val start = p[offset + 1].toInt() and 0x40 != 0
        val control = (p[offset + 3].toInt() shr 4) and 3
        if (control and 1 == 0) return emptyList()
        val cc = p[offset + 3].toInt() and 0x0F
        var from = offset + 4
        if (control and 2 != 0) from += 1 + (p[offset + 4].toInt() and 0xFF)
        val to = offset + PACKET
        if (from >= to) return emptyList()

        val last = continuity[pid]
        continuity[pid] = cc
        val gap = last != null && cc != last && cc != (last + 1) and 0x0F
        if (gap) {
            lost = true
            open[pid]?.broken = true
        }

        return when (pid) {
            0 -> { if (start) table(p, from, to, pat = true); emptyList() }
            pmtPid -> { if (start) table(p, from, to, pat = false); emptyList() }
            videoPid, audioPid -> stream(pid, if (pid == videoPid) Kind.VIDEO else Kind.AUDIO, start, p, from, to)
            else -> emptyList()
        }
    }

    private fun stream(pid: Int, kind: Kind, start: Boolean, p: ByteArray, from: Int, to: Int): List<Frame> {
        val out = ArrayList<Frame>(1)
        if (start) {
            open.remove(pid)?.let { finish(it)?.let(out::add) }
            val header = from + 9 + (p[from + 8].toInt() and 0xFF)
            val length = ((p[from + 4].toInt() and 0xFF) shl 8) or (p[from + 5].toInt() and 0xFF)
            val pes = Pes(kind, if (p[from + 7].toInt() and 0x80 != 0) pts(p, from + 9) * 100 / 9 else 0, if (length > 0) length - 3 - (header - from - 9) else 0)
            pes.add(p, minOf(header, to), to)
            open[pid] = pes
        } else {
            open[pid]?.add(p, from, to) ?: return out
        }
        val pes = open[pid]!!
        if (pes.expected in 1..pes.size) {
            open.remove(pid)
            finish(pes)?.let(out::add)
        }
        return out
    }

    private fun finish(pes: Pes): Frame? {
        if (pes.broken || pes.size == 0) return null
        if (pes.kind == Kind.VIDEO) return Frame(Kind.VIDEO, pes.timeUs, pes.data.copyOf(pes.size))
        // LPCM: a 4-byte header, then 16-bit big-endian samples, which the speakers take little-endian
        val samples = ByteArray(maxOf(0, pes.size - LPCM_HEADER) and 1.inv())
        for (i in samples.indices step 2) {
            samples[i] = pes.data[LPCM_HEADER + i + 1]
            samples[i + 1] = pes.data[LPCM_HEADER + i]
        }
        return Frame(Kind.AUDIO, pes.timeUs, samples)
    }

    /** The program table (PAT) finds the PMT; the PMT finds the picture and the sound. */
    private fun table(p: ByteArray, from: Int, to: Int, pat: Boolean) {
        val t = from + 1 + (p[from].toInt() and 0xFF)
        if (t + 3 > to) return
        val end = minOf(to, t + 3 + ((((p[t + 1].toInt() and 0x0F) shl 8) or (p[t + 2].toInt() and 0xFF))) - 4)
        if (pat) {
            var i = t + 8
            while (i + 4 <= end) {
                val program = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
                if (program != 0) { pmtPid = pid(p, i + 2); return }
                i += 4
            }
        } else {
            var i = t + 12 + ((((p[t + 10].toInt() and 0x0F) shl 8) or (p[t + 11].toInt() and 0xFF)))
            while (i + 5 <= end) {
                when (p[i].toInt() and 0xFF) {
                    H264 -> videoPid = pid(p, i + 1)
                    LPCM -> audioPid = pid(p, i + 1)
                }
                i += 5 + ((((p[i + 3].toInt() and 0x0F) shl 8) or (p[i + 4].toInt() and 0xFF)))
            }
        }
    }

    private fun pid(p: ByteArray, i: Int) = ((p[i].toInt() and 0x1F) shl 8) or (p[i + 1].toInt() and 0xFF)

    private fun pts(p: ByteArray, i: Int): Long =
        ((p[i].toLong() and 0x0E) shl 29) or ((p[i + 1].toLong() and 0xFF) shl 22) or ((p[i + 2].toLong() and 0xFE) shl 14) or
            ((p[i + 3].toLong() and 0xFF) shl 7) or ((p[i + 4].toLong() and 0xFE) shr 1)

    companion object {
        const val PACKET = 188
        private const val SYNC = 0x47
        private const val H264 = 0x1B
        private const val LPCM = 0x83
        private const val LPCM_HEADER = 4
    }
}

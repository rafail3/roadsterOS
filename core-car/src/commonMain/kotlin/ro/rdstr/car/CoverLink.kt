package ro.rdstr.car

/** What the phone app sends the unit for one track: the names it played under and the cover as a small JPEG. */
class CoverFrame(val title: String, val artist: String, val jpeg: ByteArray)

/**
 * The cover channel between the phone app and the unit: a Bluetooth RFCOMM service separate from the music profiles.
 * Each frame is an 8-byte header (magic "RDC1", payload length) followed by the payload:
 * title length (2 bytes), title (UTF-8), artist length (2 bytes), artist (UTF-8), then the JPEG to the end.
 * Integers are big-endian.
 */
object CoverLink {
    /** The RFCOMM service both sides use; fixed, so the phone finds the unit's service by it. */
    const val SERVICE_UUID = "6f1c3b2e-7c1a-4f0e-9b8d-2f5e1a7c3d90"
    const val SERVICE_NAME = "rdstr cover"
    const val HEADER_SIZE = 8
    /** A 320 px JPEG is about 25 KB; anything far larger is not a cover from our app. */
    const val MAX_PAYLOAD = 512 * 1024
    private val MAGIC = byteArrayOf(0x52, 0x44, 0x43, 0x31)

    fun encode(frame: CoverFrame): ByteArray {
        val title = frame.title.encodeToByteArray().take(MAX_NAME)
        val artist = frame.artist.encodeToByteArray().take(MAX_NAME)
        val payload = 2 + title.size + 2 + artist.size + frame.jpeg.size
        val out = ByteArray(HEADER_SIZE + payload)
        MAGIC.copyInto(out, 0)
        putInt(out, 4, payload)
        var at = HEADER_SIZE
        at = putName(out, at, title)
        at = putName(out, at, artist)
        frame.jpeg.copyInto(out, at)
        return out
    }

    /** The payload length a header announces, or null when it is not one of our frames. */
    fun payloadLength(header: ByteArray): Int? {
        if (header.size < HEADER_SIZE) return null
        for (i in MAGIC.indices) if (header[i] != MAGIC[i]) return null
        val length = getInt(header, 4)
        return if (length in 4..MAX_PAYLOAD) length else null
    }

    fun decode(payload: ByteArray): CoverFrame? {
        var at = 0
        fun name(): String? {
            if (at + 2 > payload.size) return null
            val length = ((payload[at].toInt() and 0xFF) shl 8) or (payload[at + 1].toInt() and 0xFF)
            at += 2
            if (at + length > payload.size) return null
            return payload.decodeToString(at, at + length).also { at += length }
        }
        val title = name() ?: return null
        val artist = name() ?: return null
        val jpeg = payload.copyOfRange(at, payload.size)
        // a JPEG starts with FF D8 and ends with FF D9: a cut or foreign image is refused
        val whole = jpeg.size >= 4 && jpeg[0] == JPEG_MARK && jpeg[1] == JPEG_START &&
            jpeg[jpeg.size - 2] == JPEG_MARK && jpeg[jpeg.size - 1] == JPEG_END
        return if (whole) CoverFrame(title, artist, jpeg) else null
    }

    private const val MAX_NAME = 1024
    private const val JPEG_MARK = 0xFF.toByte()
    private const val JPEG_START = 0xD8.toByte()
    private const val JPEG_END = 0xD9.toByte()

    private fun List<Byte>.take(n: Int): ByteArray = (if (size > n) subList(0, n) else this).toByteArray()
    private fun ByteArray.take(n: Int): ByteArray = if (size > n) copyOfRange(0, n) else this

    private fun putName(out: ByteArray, at: Int, bytes: ByteArray): Int {
        out[at] = (bytes.size shr 8).toByte()
        out[at + 1] = bytes.size.toByte()
        bytes.copyInto(out, at + 2)
        return at + 2 + bytes.size
    }

    private fun putInt(out: ByteArray, at: Int, value: Int) {
        for (i in 0 until 4) out[at + i] = (value shr (24 - 8 * i)).toByte()
    }

    private fun getInt(bytes: ByteArray, at: Int): Int =
        (0 until 4).fold(0) { acc, i -> (acc shl 8) or (bytes[at + i].toInt() and 0xFF) }
}

/** Whether two title and artist pairs name the same track, across case, accents and extras like "(Radio Edit)". */
object TrackMatch {
    private val FOLD = mapOf(
        'ă' to 'a', 'â' to 'a', 'á' to 'a', 'à' to 'a', 'ä' to 'a', 'ã' to 'a', 'å' to 'a',
        'î' to 'i', 'í' to 'i', 'ì' to 'i', 'ï' to 'i',
        'ș' to 's', 'ş' to 's', 'ț' to 't', 'ţ' to 't',
        'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
        'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'ö' to 'o', 'õ' to 'o',
        'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u',
        'ñ' to 'n', 'ç' to 'c',
    )
    private val EXTRAS = Regex("""\(.*?\)|\[.*?\]""")
    private val SEPARATORS = Regex("[^a-z0-9]+")

    fun fold(s: String): String =
        s.lowercase().map { FOLD[it] ?: it }.joinToString("").replace(EXTRAS, "").replace(SEPARATORS, " ").trim()

    fun same(title: String, artist: String, otherTitle: String, otherArtist: String): Boolean {
        val t = fold(title); val ot = fold(otherTitle)
        val a = fold(artist); val oa = fold(otherArtist)
        val titleMatches = t.isNotEmpty() && (t == ot || ot.startsWith(t) || t.startsWith(ot))
        // a missing artist on either side does not decide against the track
        val artistMatches = a.isEmpty() || oa.isEmpty() || oa.contains(a) || a.contains(oa)
        return titleMatches && artistMatches
    }
}

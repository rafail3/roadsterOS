package ro.rdstr.car

/**
 * Names of the covers kept on the unit's disk. A file is named after the folded title and artist, so a cover is
 * found again for the same track under slightly different names (accents, "feat.", a missing artist).
 */
object CoverIndex {
    const val SUFFIX = ".jpg"
    private const val SPLIT = "__"
    private const val MAX_PART = 80

    fun fileName(title: String, artist: String): String = part(title) + SPLIT + part(artist) + SUFFIX

    /** The kept file for this track: the exact name first, then any name of the same track. */
    fun find(names: Collection<String>, title: String, artist: String): String? {
        val exact = fileName(title, artist)
        if (exact in names) return exact
        return names.firstOrNull { name -> parse(name)?.let { (t, a) -> TrackMatch.same(title, artist, t, a) } == true }
    }

    /**
     * Which of [count] stock covers a track without any cover gets: picked from its names, so it looks random
     * across tracks but stays the same every time the track plays.
     */
    fun stockCover(title: String, artist: String, count: Int): Int {
        var hash = 0
        for (c in TrackMatch.fold(title) + "|" + TrackMatch.fold(artist)) hash = hash * 31 + c.code
        return ((hash % count) + count) % count
    }

    private fun part(s: String) = TrackMatch.fold(s).replace(' ', '-').take(MAX_PART)

    private fun parse(name: String): Pair<String, String>? {
        if (!name.endsWith(SUFFIX)) return null
        val base = name.removeSuffix(SUFFIX)
        val at = base.indexOf(SPLIT)
        if (at < 0) return null
        return base.substring(0, at).replace('-', ' ') to base.substring(at + SPLIT.length).replace('-', ' ')
    }
}

/** The pixel size a JPEG declares in its frame header, read without decoding the image. */
object JpegSize {
    /** Width times height, or null when the bytes are not a readable JPEG. */
    fun pixels(jpeg: ByteArray): Long? {
        if (jpeg.size < 4 || u8(jpeg, 0) != 0xFF || u8(jpeg, 1) != 0xD8) return null
        var at = 2
        while (at + 9 < jpeg.size) {
            if (u8(jpeg, at) != 0xFF) return null
            val marker = u8(jpeg, at + 1)
            if (marker == 0xFF) { at++; continue } // fill byte
            // frame headers: C0..CF except DHT (C4), JPG (C8) and DAC (CC)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val height = u16(jpeg, at + 5)
                val width = u16(jpeg, at + 7)
                return width.toLong() * height
            }
            at += 2 + u16(jpeg, at + 2)
        }
        return null
    }

    private fun u8(b: ByteArray, at: Int) = b[at].toInt() and 0xFF
    private fun u16(b: ByteArray, at: Int) = (u8(b, at) shl 8) or u8(b, at + 1)
}

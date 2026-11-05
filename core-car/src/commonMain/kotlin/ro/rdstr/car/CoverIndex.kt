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

    private fun part(s: String) = TrackMatch.fold(s).replace(' ', '-').take(MAX_PART)

    private fun parse(name: String): Pair<String, String>? {
        if (!name.endsWith(SUFFIX)) return null
        val base = name.removeSuffix(SUFFIX)
        val at = base.indexOf(SPLIT)
        if (at < 0) return null
        return base.substring(0, at).replace('-', ' ') to base.substring(at + SPLIT.length).replace('-', ' ')
    }
}

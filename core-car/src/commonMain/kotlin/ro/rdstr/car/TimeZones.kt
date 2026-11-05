package ro.rdstr.car

/** The time zone list in the settings: the zones named after a place, found by city or region, with their offset. */
object TimeZones {
    private val REGIONS = setOf("Africa", "America", "Antarctica", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")

    /** "Europe/Bucharest" and the like, by city; without the aliases and fixed offsets ("US/Eastern", "Etc/GMT+2"). */
    fun places(ids: List<String>): List<String> =
        ids.filter { '/' in it && it.substringBefore('/') in REGIONS }.distinct().sortedBy { city(it) }

    /** "America/Argentina/Buenos_Aires" -> "Buenos Aires". */
    fun city(id: String) = id.substringAfterLast('/').replace('_', ' ')

    /** "America/Argentina/Buenos_Aires" -> "America, Argentina". */
    fun region(id: String) = id.substringBeforeLast('/').replace("/", ", ").replace('_', ' ')

    /** The zones with a word of their city or region starting with [query]; case does not matter. */
    fun search(ids: List<String>, query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return ids
        return ids.filter { id ->
            val words = (city(id) + " " + region(id)).lowercase().split(' ', ',', '-').filter { it.isNotEmpty() }
            city(id).lowercase().startsWith(q) || words.any { it.startsWith(q) }
        }
    }

    /** "GMT+2", "GMT+5:30", "GMT-3:30", "GMT". */
    fun offset(minutes: Int): String {
        if (minutes == 0) return "GMT"
        val m = kotlin.math.abs(minutes)
        val rest = if (m % 60 != 0) ":" + (m % 60).toString().padStart(2, '0') else ""
        return "GMT" + (if (minutes > 0) "+" else "-") + (m / 60) + rest
    }
}

package ro.rdstr.map

/**
 * The cover over the countries whose pack is not on the unit, as the emulator's `missing` source: the borders whose
 * code is not among the installed packs. [update] runs on every change of the installed packs (one downloaded, one
 * removed) and says whether the cover changed.
 */
class PackMask(countries: List<Pair<String, List<List<DoubleArray>>>>) {
    /** One polygon of a country: its rings as lon, lat pairs, the outer one first, and the outer ring's bounds. */
    class Polygon(val code: String, val rings: List<DoubleArray>) {
        val west: Double
        val south: Double
        val east: Double
        val north: Double

        init {
            var w = 180.0; var s = 90.0; var e = -180.0; var n = -90.0
            val outer = rings[0]
            for (i in outer.indices step 2) {
                w = minOf(w, outer[i]); s = minOf(s, outer[i + 1]); e = maxOf(e, outer[i]); n = maxOf(n, outer[i + 1])
            }
            west = w; south = s; east = e; north = n
        }

        /** Whether it can show on a screen centred on world pixel ([cx], [cy]) at [zoom], [reach] pixels round the centre. */
        fun reaches(cx: Double, cy: Double, zoom: Double, reach: Double): Boolean =
            TileMath.worldX(east, zoom) >= cx - reach && TileMath.worldX(west, zoom) <= cx + reach &&
                TileMath.worldY(south, zoom) >= cy - reach && TileMath.worldY(north, zoom) <= cy + reach
    }

    private val all = countries.flatMap { (code, polygons) -> polygons.map { Polygon(code, it) } }
    private var installed: Set<String>? = null

    /** The polygons covered now. */
    var missing: List<Polygon> = emptyList()
        private set

    fun update(installed: Collection<String>): Boolean {
        val codes = installed.toSet()
        if (codes == this.installed) return false
        this.installed = codes
        val next = all.filter { it.code !in codes }
        val changed = next != missing
        missing = next
        return changed
    }
}

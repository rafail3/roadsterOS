package ro.rdstr.map

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The street the car is on, as the emulator finds it: the nearest named road within [MAX_PX] screen pixels. */
object Street {
    /** How far from the car a road may be and still be the one it is on, in the style's pixels at the map's zoom. */
    const val MAX_PX = 24.0
    /** The unit looks it up this often while the car moves, not on every position. */
    const val EVERY_MS = 1000L

    /** The name of the road under ([lon], [lat]) at [zoom], from the tiles around it; empty when none is that close. */
    fun at(lon: Double, lat: Double, zoom: Double, tiles: TileRenderer.Tiles, maxTileZoom: Int = 14): String {
        val tz = min(maxTileZoom, max(0, floor(zoom).toInt()))
        val size = TileMath.TILE * 2.0.pow(zoom - tz)
        val cx = TileMath.worldX(lon, zoom)
        val cy = TileMath.worldY(lat, zoom)
        val count = 1 shl tz
        var best = MAX_PX * MAX_PX
        var name = ""
        for (ty in floor((cy - MAX_PX) / size).toInt()..floor((cy + MAX_PX) / size).toInt()) {
            if (ty !in 0 until count) continue
            for (tx in floor((cx - MAX_PX) / size).toInt()..floor((cx + MAX_PX) / size).toInt()) {
                val layer = tiles.get(tz, ((tx % count) + count) % count, ty)?.layers?.get("transportation_name") ?: continue
                val k = size / layer.extent
                // the car at 0, 0
                val ox = tx * size - cx
                val oy = ty * size - cy
                for (f in layer.features) {
                    if (f.type != GeomType.LINE) continue
                    val n = f.tags["name"] as? String ?: continue
                    for (p in f.parts()) {
                        for (i in 2 until p.size - 1 step 2) {
                            val d = toSegment(ox + p[i - 2] * k, oy + p[i - 1] * k, ox + p[i] * k, oy + p[i + 1] * k)
                            if (d < best) { best = d; name = n }
                        }
                    }
                }
            }
        }
        return name
    }

    /** Squared distance from the origin to the segment a-b. */
    private fun toSegment(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len = dx * dx + dy * dy
        val t = if (len == 0.0) 0.0 else ((-ax * dx - ay * dy) / len).coerceIn(0.0, 1.0)
        val x = ax + t * dx
        val y = ay + t * dy
        return x * x + y * y
    }
}

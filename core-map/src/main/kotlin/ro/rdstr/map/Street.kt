package ro.rdstr.map

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** A line found near the car: its tags, its squared distance in pixels, and whether the car drives along its direction. */
class RoadHit(val tags: Map<String, Any>, val d2: Double, val along: Boolean)

/**
 * The road the car is on, found the same way by the unit and the emulator: the nearest line of a tile layer within
 * [Street.MAX_PX] pixels at a zoom, among those running the way the car drives (within [MAX_TURN] degrees, either
 * direction of the line) once it moves.
 */
object Roads {
    /** The road classes a car drives on; paths, rails and roads under construction are left out. */
    val DRIVABLE = setOf("motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service", "track")
    const val MAX_TURN = 40.0

    fun drivable(f: TileFeature) = f.tags["class"] in DRIVABLE

    fun nearest(
        layerName: String, lon: Double, lat: Double, zoom: Double, heading: Double?, tiles: TileRenderer.Tiles,
        maxTileZoom: Int = 14, maxPx: Double = Street.MAX_PX, accept: (TileFeature) -> Boolean,
    ): RoadHit? {
        val tz = min(maxTileZoom, max(0, floor(zoom).toInt()))
        val size = TileMath.TILE * 2.0.pow(zoom - tz)
        val cx = TileMath.worldX(lon, zoom)
        val cy = TileMath.worldY(lat, zoom)
        val count = 1 shl tz
        var best: RoadHit? = null
        var bestD = maxPx * maxPx
        for (ty in floor((cy - maxPx) / size).toInt()..floor((cy + maxPx) / size).toInt()) {
            if (ty !in 0 until count) continue
            for (tx in floor((cx - maxPx) / size).toInt()..floor((cx + maxPx) / size).toInt()) {
                val layer = tiles.get(tz, ((tx % count) + count) % count, ty)?.layers?.get(layerName) ?: continue
                val k = size / layer.extent
                // the car at 0, 0
                val ox = tx * size - cx
                val oy = ty * size - cy
                for (f in layer.features) {
                    if (f.type != GeomType.LINE || !accept(f)) continue
                    for (p in f.parts()) {
                        for (i in 2 until p.size - 1 step 2) {
                            val ax = ox + p[i - 2] * k
                            val ay = oy + p[i - 1] * k
                            val bx = ox + p[i] * k
                            val by = oy + p[i + 1] * k
                            val d = toSegment(ax, ay, bx, by)
                            if (d >= bestD) continue
                            val along = if (heading == null) true else {
                                val turn = turn(heading, Math.toDegrees(atan2(bx - ax, -(by - ay))))
                                if (turn <= MAX_TURN) true else if (turn >= 180 - MAX_TURN) false else continue
                            }
                            bestD = d
                            best = RoadHit(f.tags, d, along)
                        }
                    }
                }
            }
        }
        return best
    }

    /** The angle between two compass directions, 0 to 180. */
    private fun turn(a: Double, b: Double) = abs(((a - b) % 360 + 540) % 360 - 180)

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

/** The street the car is on, as the emulator finds it: the nearest named drivable road within [MAX_PX] screen pixels. */
object Street {
    /** How far from the car a road may be and still be the one it is on, in the style's pixels at the map's zoom. */
    const val MAX_PX = 24.0
    /** The unit looks it up this often while the car moves, not on every position. */
    const val EVERY_MS = 1000L

    /** The name of the road under ([lon], [lat]) at [zoom]; [heading] once the car moves. Empty when none is that close. */
    fun at(lon: Double, lat: Double, zoom: Double, tiles: TileRenderer.Tiles, heading: Double? = null, maxTileZoom: Int = 14): String =
        Roads.nearest("transportation_name", lon, lat, zoom, heading, tiles, maxTileZoom) { Roads.drivable(it) && it.tags["name"] is String }
            ?.tags?.get("name") as? String ?: ""
}

/**
 * The posted limit of the road the car is on, in km/h, from the pack's `maxspeed` layer (zoom 14 lines with `fwd` and
 * `bwd`): the limit line must lie on the road the car is on, within [SAME_ROAD_PX], and its direction picks the limit
 * of the car's side. Looked up at [ZOOM] whatever the map shows, so the match is the same at any zoom.
 */
object SpeedLimit {
    const val ZOOM = 15.5
    const val SAME_ROAD_PX = 6.0

    fun at(lon: Double, lat: Double, heading: Double?, tiles: TileRenderer.Tiles): Int? {
        val road = Roads.nearest("transportation", lon, lat, ZOOM, heading, tiles, accept = Roads::drivable) ?: return null
        val line = Roads.nearest("maxspeed", lon, lat, ZOOM, heading, tiles) { true } ?: return null
        if (sqrt(line.d2) > sqrt(road.d2) + SAME_ROAD_PX) return null
        val kmh = line.tags[if (line.along) "fwd" else "bwd"] as? Long ?: return null
        return kmh.toInt()
    }
}

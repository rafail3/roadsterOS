package ro.rdstr.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tiles made of lines given in screen pixels from the car, at [zoom], around the zoom 14 tile 9354/5850. */
class RoadTiles(val zoom: Double) {
    private val tz = 14
    val tx = 9354
    val ty = 5850
    private val extent = 4096
    val tileSize = TileMath.TILE * Math.pow(2.0, zoom - tz)
    val carX = (tx + 0.5) * tileSize
    val carY = (ty + 0.5) * tileSize
    val lon = TileMath.lon(carX, zoom)
    val lat = TileMath.lat(carY, zoom)
    private val lines = ArrayList<Triple<Pair<Int, Int>, String, TileFeature>>()

    /** A line through the points [px] (x, y pairs, pixels from the car, y down) in the layer [layer] of tile [x], [y]. */
    fun line(layer: String, tags: Map<String, Any>, vararg px: Double, x: Int = tx, y: Int = ty): RoadTiles {
        val k = extent / tileSize
        val geometry = ArrayList<Int>()
        var cx = 0
        var cy = 0
        for (i in px.indices step 2) {
            if (i == 0) geometry += (1 shl 3) or 1
            if (i == 2) geometry += ((px.size / 2 - 1) shl 3) or 2
            val ex = ((carX + px[i] - x * tileSize) * k).toInt()
            val ey = ((carY + px[i + 1] - y * tileSize) * k).toInt()
            geometry += zig(ex - cx); geometry += zig(ey - cy)
            cx = ex; cy = ey
        }
        lines += Triple(x to y, layer, TileFeature(GeomType.LINE, tags, geometry.toIntArray()))
        return this
    }

    fun road(name: String, vararg px: Double, cls: String = "minor", x: Int = tx) =
        line("transportation_name", mapOf("name" to name, "class" to cls), *px, x = x)

    private fun zig(n: Int) = (n shl 1) xor (n shr 31)

    val tiles = TileRenderer.Tiles { z, x, y ->
        if (z != tz) return@Tiles null
        val here = lines.filter { it.first == (x to y) }
        VectorTile(here.groupBy { it.second }.mapValues { (name, l) -> TileLayer(name, extent, l.map { it.third }) })
    }
}

class StreetTest {
    private val zoom = 15.5

    @Test
    fun `the named road under the car`() {
        val t = RoadTiles(zoom).road("Piața Sfatului", -100.0, 2.0, 100.0, 2.0)
        assertEquals("Piața Sfatului", Street.at(t.lon, t.lat, zoom, t.tiles))
    }

    @Test
    fun `the nearer of two roads`() {
        val t = RoadTiles(zoom).road("Far", -100.0, 20.0, 100.0, 20.0).road("Near", 6.0, -100.0, 6.0, 100.0)
        assertEquals("Near", Street.at(t.lon, t.lat, zoom, t.tiles))
    }

    @Test
    fun `no road within 24 pixels is no street`() {
        val t = RoadTiles(zoom).road("Away", -100.0, 30.0, 100.0, 30.0)
        assertEquals("", Street.at(t.lon, t.lat, zoom, t.tiles))
    }

    @Test
    fun `a road in the next tile counts when the car is near the edge`() {
        val t = RoadTiles(zoom)
        val edge = t.tileSize / 2 - 5 // the car 5 px from the right edge of its tile, the road 5 px right of it
        t.road("Next door", edge + 10, -50.0, edge + 10, 50.0, x = t.tx + 1)
        assertEquals("Next door", Street.at(TileMath.lon(t.carX + edge, zoom), t.lat, zoom, t.tiles))
    }

    @Test
    fun `a footpath nearer than the road is not the street`() {
        val t = RoadTiles(zoom).road("Alee", -100.0, 1.0, 100.0, 1.0, cls = "path").road("Strada", -100.0, 10.0, 100.0, 10.0)
        assertEquals("Strada", Street.at(t.lon, t.lat, zoom, t.tiles))
    }

    @Test
    fun `driving north, a crossing street nearer than the road is not the street`() {
        // the car on a north-south road, 8 px from its line, at a crossing whose line runs 3 px away
        val t = RoadTiles(zoom).road("Crossing", -100.0, 3.0, 100.0, 3.0).road("Along", 8.0, -100.0, 8.0, 100.0)
        assertEquals("Along", Street.at(t.lon, t.lat, zoom, t.tiles, heading = 0.0))
        assertEquals("Along", Street.at(t.lon, t.lat, zoom, t.tiles, heading = 180.0))
        assertEquals("Crossing", Street.at(t.lon, t.lat, zoom, t.tiles))
    }
}

class SpeedLimitTest {
    private val z = SpeedLimit.ZOOM
    private fun drivable(t: RoadTiles, vararg px: Double) = t.line("transportation", mapOf("class" to "primary"), *px)

    @Test
    fun `the limit of the road the car is on, for its direction`() {
        // a road drawn from south to north: forward is northbound
        val t = RoadTiles(z)
        drivable(t, 2.0, 100.0, 2.0, -100.0)
        t.line("maxspeed", mapOf("fwd" to 50L, "bwd" to 70L), 2.0, 100.0, 2.0, -100.0)
        assertEquals(50, SpeedLimit.at(t.lon, t.lat, 0.0, t.tiles))
        assertEquals(70, SpeedLimit.at(t.lon, t.lat, 180.0, t.tiles))
    }

    @Test
    fun `a limit for one direction only is none the other way`() {
        val t = RoadTiles(z)
        drivable(t, 2.0, 100.0, 2.0, -100.0)
        t.line("maxspeed", mapOf("fwd" to 90L), 2.0, 100.0, 2.0, -100.0)
        assertEquals(90, SpeedLimit.at(t.lon, t.lat, 0.0, t.tiles))
        assertNull(SpeedLimit.at(t.lon, t.lat, 180.0, t.tiles))
    }

    @Test
    fun `a limit on a parallel road is not the limit of the car's road`() {
        val t = RoadTiles(z)
        drivable(t, 2.0, 100.0, 2.0, -100.0)
        drivable(t, 18.0, 100.0, 18.0, -100.0)
        t.line("maxspeed", mapOf("fwd" to 130L, "bwd" to 130L), 18.0, 100.0, 18.0, -100.0)
        assertNull(SpeedLimit.at(t.lon, t.lat, 0.0, t.tiles))
    }

    @Test
    fun `without a limit layer there is no limit`() {
        val t = RoadTiles(z)
        drivable(t, 2.0, 100.0, 2.0, -100.0)
        assertNull(SpeedLimit.at(t.lon, t.lat, 0.0, t.tiles))
    }
}

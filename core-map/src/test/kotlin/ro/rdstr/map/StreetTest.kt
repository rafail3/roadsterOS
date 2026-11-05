package ro.rdstr.map

import kotlin.test.Test
import kotlin.test.assertEquals

class StreetTest {
    private val zoom = 15.5
    // the car in the middle of the zoom 14 tile 9354/5767
    private val tz = 14
    private val tx = 9354
    private val ty = 5767
    private val extent = 4096
    private val tileSize = TileMath.TILE * Math.pow(2.0, zoom - tz)
    private val carX = (tx + 0.5) * tileSize
    private val carY = (ty + 0.5) * tileSize
    private val lon = TileMath.lon(carX, zoom)
    private val lat = TileMath.lat(carY, zoom)

    /** A line given in screen pixels from the car, encoded as a tile geometry of the tile [x], [y]. */
    private fun road(name: String, x: Int, y: Int, vararg px: Double): TileFeature {
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
        return TileFeature(GeomType.LINE, mapOf("name" to name), geometry.toIntArray())
    }

    private fun zig(n: Int) = (n shl 1) xor (n shr 31)

    private fun tiles(vararg roads: Pair<Pair<Int, Int>, TileFeature>) = TileRenderer.Tiles { z, x, y ->
        if (z != tz) return@Tiles null
        val here = roads.filter { it.first == (x to y) }.map { it.second }
        VectorTile(mapOf("transportation_name" to TileLayer("transportation_name", extent, here)))
    }

    @Test
    fun `the named road under the car`() {
        val t = tiles((tx to ty) to road("Piața Sfatului", tx, ty, -100.0, 2.0, 100.0, 2.0))
        assertEquals("Piața Sfatului", Street.at(lon, lat, zoom, t))
    }

    @Test
    fun `the nearer of two roads`() {
        val t = tiles(
            (tx to ty) to road("Far", tx, ty, -100.0, 20.0, 100.0, 20.0),
            (tx to ty) to road("Near", tx, ty, 6.0, -100.0, 6.0, 100.0),
        )
        assertEquals("Near", Street.at(lon, lat, zoom, t))
    }

    @Test
    fun `no road within 24 pixels is no street`() {
        val t = tiles((tx to ty) to road("Away", tx, ty, -100.0, 30.0, 100.0, 30.0))
        assertEquals("", Street.at(lon, lat, zoom, t))
    }

    @Test
    fun `a road in the next tile counts when the car is near the edge`() {
        val edge = tileSize / 2 - 5 // the car 5 px from the right edge of its tile
        val t = tiles((tx + 1 to ty) to road("Next door", tx + 1, ty, edge + 10, -50.0, edge + 10, 50.0))
        // the road is 5 px right of the car, across the edge
        val lonNearEdge = TileMath.lon(carX + edge, zoom)
        assertEquals("Next door", Street.at(lonNearEdge, lat, zoom, t))
    }
}

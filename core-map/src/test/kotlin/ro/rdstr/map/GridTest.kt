package ro.rdstr.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GridTest {
    private val renderer = TileRenderer(MapStyle.load())
    private val brasov = View(lon = 25.5887, lat = 45.6427, zoom = 16.4, bearing = 0.0, width = 1280, height = 750, night = true)

    @Test
    fun `deeper than the tiles, pictures are cut from the zoom 14 tile they lie in`() {
        val grid = renderer.grid(brasov)
        assertTrue(grid.isNotEmpty())
        for (g in grid) {
            assertEquals(16, g.z)
            assertEquals(14, g.srcZ)
            assertEquals(g.x shr 2, g.srcX)
            assertEquals(g.y shr 2, g.srcY)
            // the picture lies inside its source tile, four times smaller across
            assertTrue(abs(g.srcSize - 4 * g.size) < 1e-6)
            assertTrue(g.left >= g.srcLeft - 1e-6 && g.left + g.size <= g.srcLeft + g.srcSize + 1e-6)
        }
    }

    @Test
    fun `the source tile in a picture`() {
        val g = renderer.grid(brasov).first()
        val (left, top, size) = g.inPicture(512.0)
        assertTrue(abs(size - 2048) < 1e-6)
        assertEquals(-((g.x and 3) * 512.0), left, 1e-6)
        assertEquals(-((g.y and 3) * 512.0), top, 1e-6)
    }

    @Test
    fun `at tile zooms a picture is its whole tile`() {
        for (g in renderer.grid(brasov.copy(zoom = 13.2))) {
            assertEquals(g.z, g.srcZ)
            assertEquals(g.x, g.srcX)
            assertTrue(abs(g.size - g.srcSize) < 1e-9 && abs(g.left - g.srcLeft) < 1e-9)
        }
    }

    @Test
    fun `the grid covers the turned screen`() {
        val turned = renderer.grid(brasov.copy(bearing = 45.0))
        val corners = listOf(0.0 to 0.0, 1280.0 to 0.0, 0.0 to 750.0, 1280.0 to 750.0)
        for ((x, y) in corners) {
            // the screen corner, turned back onto the unturned map
            val r = Math.toRadians(45.0)
            val dx = x - 640; val dy = y - 375
            val mx = 640 + dx * kotlin.math.cos(r) - dy * kotlin.math.sin(r)
            val my = 375 + dx * kotlin.math.sin(r) + dy * kotlin.math.cos(r)
            assertTrue(turned.any { mx >= it.left && mx <= it.left + it.size && my >= it.top && my <= it.top + it.size }, "corner $x,$y")
        }
    }
}

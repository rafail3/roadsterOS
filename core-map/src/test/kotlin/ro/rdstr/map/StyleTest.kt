package ro.rdstr.map

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StyleTest {
    private val style = MapStyle.load()

    private fun near(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 1e-6, "$expected vs $actual")

    @Test
    fun `the shared style loads with both palettes and its layers in order`() {
        assertEquals(listOf("day", "night"), style.palettes.keys.sorted())
        assertEquals("land", style.layers.first().id)
        assertEquals("place-city", style.layers.last().id)
    }

    @Test
    fun `widths interpolate exponentially between stops, as the browser map does`() {
        val width = style.layers.first { it.id == "road-primary" }.width!!
        near(0.7, width.at(6.0))
        near(22.0, width.at(18.0))
        // MapLibre: t = (base^(z - z0) - 1) / (base^(z1 - z0) - 1)
        val t = (1.6.pow(12.0 - 6) - 1) / (1.6.pow(12.0) - 1)
        near(0.7 + t * (22 - 0.7), width.at(12.0))
        near(0.7, width.at(3.0))
        near(22.0, width.at(20.0))
    }

    @Test
    fun `linear sizes and constants`() {
        val size = style.layers.first { it.id == "road-name" }.size!!
        near(13.0, size.at(15.5))
        near(13.0, style.layers.first { it.id == "water-name" }.size!!.at(9.0))
    }

    @Test
    fun `filters match on class, equal keys and excluded keys`() {
        val primary = style.layers.first { it.id == "road-primary" }
        assertTrue(primary.matches(mapOf("class" to "primary")))
        assertFalse(primary.matches(mapOf("class" to "minor")))
        val border = style.layers.first { it.id == "boundary-country" }
        assertTrue(border.matches(mapOf("admin_level" to 2L, "maritime" to 0L)))
        assertFalse(border.matches(mapOf("admin_level" to 2L, "maritime" to 1L)))
        assertFalse(border.matches(mapOf("admin_level" to 4L)))
    }

    @Test
    fun `day-only layers are left out at night`() {
        assertTrue(style.layersFor(night = true).none { it.id.endsWith("-casing") })
        assertTrue(style.layersFor(night = false).any { it.id == "road-primary-casing" })
        assertEquals(0xFF0D1015.toInt(), style.color(night = true, "land"))
    }
}

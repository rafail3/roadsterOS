package ro.rdstr.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackMaskTest {
    private fun square(w: Double, s: Double, e: Double, n: Double) = doubleArrayOf(w, s, e, s, e, n, w, n, w, s)
    private val mask = PackMask(listOf(
        "RO" to listOf(listOf(square(20.0, 43.6, 29.7, 48.3))),
        "HU" to listOf(listOf(square(16.1, 45.7, 22.9, 48.6))),
        // two polygons, one with a hole
        "GR" to listOf(listOf(square(20.0, 36.0, 26.0, 41.7), square(22.0, 38.0, 23.0, 39.0)), listOf(square(23.5, 34.8, 26.3, 35.7))),
    ))

    private fun codes() = mask.missing.map { it.code }

    @Test
    fun `the countries without a pack are covered and follow the installed packs`() {
        assertTrue(mask.update(listOf("RO")))
        assertEquals(listOf("HU", "GR", "GR"), codes())
        // the same packs again: nothing to redraw
        assertFalse(mask.update(listOf("RO")))
        // a pack downloaded
        assertTrue(mask.update(listOf("RO", "HU")))
        assertEquals(listOf("GR", "GR"), codes())
        // a pack removed, Romania included
        assertTrue(mask.update(listOf("HU")))
        assertEquals(listOf("RO", "GR", "GR"), codes())
        // a pack of a country without borders changes nothing on the map
        assertFalse(mask.update(listOf("HU", "XX")))
        assertTrue(mask.update(emptyList()))
        assertEquals(4, mask.missing.size)
    }

    @Test
    fun `a polygon's bounds come from its outer ring and only those near the screen are drawn`() {
        mask.update(emptyList())
        val hu = mask.missing.first { it.code == "HU" }
        assertEquals(16.1, hu.west); assertEquals(45.7, hu.south); assertEquals(22.9, hu.east); assertEquals(48.6, hu.north)
        val z = 15.5
        // a screen over Brasov reaches Romania only
        val cx = TileMath.worldX(25.5887, z)
        val cy = TileMath.worldY(45.6427, z)
        assertEquals(listOf("RO"), mask.missing.filter { it.reaches(cx, cy, z, 740.0) }.map { it.code })
        // at Oradea both boxes reach the screen
        val ox = TileMath.worldX(21.92, z)
        val oy = TileMath.worldY(47.06, z)
        assertEquals(setOf("RO", "HU"), mask.missing.filter { it.reaches(ox, oy, z, 740.0) }.map { it.code }.toSet())
    }
}

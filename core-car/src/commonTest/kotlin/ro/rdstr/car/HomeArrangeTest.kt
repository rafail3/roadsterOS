package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeArrangeTest {
    private val slot = 116.0

    @Test
    fun `the held icon stays inside the row, at its first or last place at most`() {
        assertEquals(4 * slot, HomeArrange.clamp(0, 900.0, slot, 5))
        assertEquals(-2 * slot, HomeArrange.clamp(2, -900.0, slot, 5))
        assertEquals(4, HomeArrange.dropIndex(0, 900.0, slot, 5))
        assertEquals(0, HomeArrange.dropIndex(3, -900.0, slot, 5))
    }

    @Test
    fun `it drops at the nearest place and the ones it passed make room`() {
        assertEquals(2, HomeArrange.dropIndex(0, 2.3 * slot, slot, 5))
        assertEquals(0, HomeArrange.dropIndex(0, 0.4 * slot, slot, 5))
        assertEquals(listOf(2.3 * slot, -slot, -slot, 0.0, 0.0), (0 until 5).map { HomeArrange.offset(it, 0, 2.3 * slot, slot, 5) })
        assertEquals(listOf(slot, slot, -1.6 * slot, 0.0), (0 until 4).map { HomeArrange.offset(it, 2, -1.6 * slot, slot, 4) })
    }

    @Test
    fun `after the drop it glides from where the finger left it`() {
        assertEquals(0.3 * slot, HomeArrange.settle(0, 2.3 * slot, slot, 5), 1e-9)
        assertEquals(0.0, HomeArrange.settle(0, 900.0, slot, 5), 1e-9)
    }

    @Test
    fun `when an icon goes the rest glide to the new centre, a new one grows in`() {
        val before = mapOf("A" to 100.0, "B" to 216.0, "C" to 332.0)
        // B taken off: the row of two re-centres
        val after = mapOf("A" to 158.0, "C" to 274.0)
        assertEquals(listOf(HomeArrange.Glide("A", -58.0), HomeArrange.Glide("C", 58.0)), HomeArrange.glides(before, after))
        assertEquals(listOf(HomeArrange.Glide("D", added = true)), HomeArrange.glides(after, after + ("D" to 390.0)))
        assertTrue(HomeArrange.glides(emptyMap(), after).isEmpty())
    }

    @Test
    fun `holds, swipes, the end of the arranging and opening`() {
        assertTrue(HomeArrange.cancelsHold(12.0))
        assertFalse(HomeArrange.cancelsHold(-8.0))
        assertTrue(HomeArrange.keepsArranging(2, 2))
        assertFalse(HomeArrange.keepsArranging(2, 3))
        assertTrue(HomeArrange.opens(arranging = false, pickedUp = false))
        assertFalse(HomeArrange.opens(arranging = false, pickedUp = true))
        assertFalse(HomeArrange.opens(arranging = true, pickedUp = false))
    }
}

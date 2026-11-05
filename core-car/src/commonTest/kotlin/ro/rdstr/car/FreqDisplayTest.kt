package ro.rdstr.car

import ro.rdstr.car.CarEvent.Radio.Band.AM
import ro.rdstr.car.CarEvent.Radio.Band.FM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FreqDisplayTest {
    @Test
    fun `a frequency is written in its band's unit`() {
        assertEquals("101.9", FreqDisplay.format(FM, 101900))
        assertEquals("95.35", FreqDisplay.format(FM, 95350))
        assertEquals("88.0", FreqDisplay.format(FM, 88000))
        assertEquals("1593", FreqDisplay.format(AM, 1593))
    }

    @Test
    fun `the shown frequency glides to the tuned one and lands on it`() {
        var shown = 100400.0
        var frames = 0
        while (shown != 102850.0 && frames < 200) { shown = FreqDisplay.glide(shown, 102850); frames++ }
        assertEquals(102850.0, shown)
        // a fifth of the way each frame: about forty frames, under a second, never a jump
        assertTrue(frames in 30..60, "frames $frames")
        assertEquals(100400.0 + 2450 * 0.2, FreqDisplay.glide(100400.0, 102850), 1e-9)
    }

    @Test
    fun `the counter turns the last digit and carries through nines`() {
        // 101.95 MHz on its way: tenths 1019.5, so the last digit is half turned and the others still
        val c = FreqDisplay.counter(FM, 101950.0, 102000)
        assertNull(c.exact)
        assertEquals(listOf(3, 2, 1, 0), c.columns.map { it.place })
        assertEquals(listOf(1.0, 0.0, 1.5, 9.5), c.columns.map { it.offset })
        assertEquals(1, c.decimalAt)
    }

    @Test
    fun `a station off the tenths grid shows its exact digits once settled`() {
        assertEquals("95.35", FreqDisplay.counter(FM, 95350.0, 95350).exact)
        assertNull(FreqDisplay.counter(FM, 95300.0, 95350).exact)
        assertNull(FreqDisplay.counter(AM, 1593.0, 1593).exact)
    }
}

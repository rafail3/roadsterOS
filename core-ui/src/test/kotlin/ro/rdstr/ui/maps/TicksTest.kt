package ro.rdstr.ui.maps

import org.junit.Assert.assertEquals
import org.junit.Test

class TicksTest {
    private fun tick(i: Int) = FloatArray(4).also { Ticks.line(i, it, 0) }

    @Test
    fun firstTickAtTheBottomGoingClockwise() {
        val t0 = tick(0)
        assertEquals(42f, t0[0], 1e-4f); assertEquals(76f, t0[1], 1e-4f); assertEquals(81.5f, t0[3], 1e-4f)
        // a quarter round from the bottom, clockwise, is the left side
        val t15 = tick(15)
        assertEquals(8f, t15[0], 1e-4f); assertEquals(42f, t15[1], 1e-4f)
        val t30 = tick(30)
        assertEquals(8f, t30[1], 1e-4f)
    }
}

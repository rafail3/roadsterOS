package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeedWarningTest {
    private fun moving(kmh: Int) = Speedometer.Reading(kmh, false)

    @Test
    fun `over from one km per h above the limit`() {
        val w = SpeedWarning()
        assertFalse(w.update(moving(50), 50, 0).over)
        assertTrue(w.update(moving(51), 50, 1000).over)
    }

    @Test
    fun `one chime each time the car goes over`() {
        val w = SpeedWarning()
        assertTrue(w.update(moving(55), 50, 0).chime)
        assertFalse(w.update(moving(60), 50, 1000).chime)
        assertFalse(w.update(moving(45), 50, 2000).chime)
        assertTrue(w.update(moving(52), 50, 3000).chime)
    }

    @Test
    fun `a lower limit under a car already fast chimes`() {
        val w = SpeedWarning()
        assertFalse(w.update(moving(80), 90, 0).chime)
        assertTrue(w.update(moving(80), 50, 1000).chime)
    }

    @Test
    fun `no chime when it is turned off`() {
        val w = SpeedWarning().apply { chimeOn = false }
        val s = w.update(moving(80), 50, 0)
        assertTrue(s.over)
        assertFalse(s.chime)
    }

    @Test
    fun `stopped or without a limit is never over`() {
        val w = SpeedWarning()
        assertFalse(w.update(Speedometer.Reading(0, true), 30, 0).over)
        assertFalse(SpeedWarning().update(moving(200), null, 0).over)
    }

    @Test
    fun `a limit missed for a moment stays, then goes`() {
        val w = SpeedWarning()
        w.update(moving(60), 50, 0)
        val missed = w.update(moving(60), null, 2000)
        assertEquals(50, missed.limit)
        assertTrue(missed.over)
        assertFalse(missed.chime)
        assertNull(w.update(moving(60), null, 2000 + SpeedWarning.HOLD_MS).limit)
    }
}

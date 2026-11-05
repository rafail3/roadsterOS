package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedometerTest {
    private val lat = 45.6427
    /** Degrees of longitude for [m] metres east at [lat]. */
    private fun east(m: Double) = m / (111_320.0 * kotlin.math.cos(lat * kotlin.math.PI / 180))

    @Test
    fun `it starts stopped`() {
        assertTrue(Speedometer().reading.stopped)
    }

    @Test
    fun `the chip's own speed is shown as it is, rounded`() {
        val s = Speedometer()
        assertEquals(Speedometer.Reading(52, false), s.update(25.0, lat, 0, 51.6))
    }

    @Test
    fun `standing still, the drift of the chip stays stopped`() {
        val s = Speedometer()
        listOf(1.2, 2.8, 0.4, 2.9).forEachIndexed { i, kmh -> s.update(25.0, lat, i * 1000L, kmh) }
        assertTrue(s.reading.stopped)
    }

    @Test
    fun `moving starts above 5 km per h and stopping comes below 3`() {
        val s = Speedometer()
        assertTrue(s.update(25.0, lat, 0, 4.5).stopped)
        assertEquals(Speedometer.Reading(6, false), s.update(25.0, lat, 1000, 6.0))
        assertEquals(Speedometer.Reading(4, false), s.update(25.0, lat, 2000, 3.5))
        assertTrue(s.update(25.0, lat, 3000, 2.5).stopped)
    }

    @Test
    fun `without the chip's speed it comes from the distance over the last seconds`() {
        val s = Speedometer()
        // 50 km/h is 13.89 m/s
        for (i in 0..4) s.update(25.0 + east(13.889 * i), lat, i * 1000L, null)
        assertEquals(Speedometer.Reading(50, false), s.reading)
    }

    @Test
    fun `positions from before the window do not count`() {
        val s = Speedometer()
        s.update(25.0, lat, 0, null)
        // ten seconds parked, then a fix where the car still is: stopped, not an average with the old drive
        s.update(25.0 + east(100.0), lat, 1000, null)
        s.update(25.0 + east(100.0), lat, 11_000, null)
        s.update(25.0 + east(100.0), lat, 12_000, null)
        assertTrue(s.reading.stopped)
    }
}

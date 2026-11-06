package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ro.rdstr.car.DashClock.Key

class DashClockTest {
    private fun count(keys: List<Key>) = keys.count { it == Key.HOUR } to keys.count { it == Key.MINUTE }

    @Test
    fun `the minutes wrap without the hour, so both are pressed`() {
        assertEquals(1 to 20, count(DashClock.keys(10 * 60 + 50, 11 * 60 + 10)))
        assertEquals(1 to 45, count(DashClock.keys(23 * 60 + 30, 15)))
        assertEquals(0 to 0, count(DashClock.keys(600, 600)))
    }

    @Test
    fun `an hour back is twenty-three forward`() {
        assertEquals(23 to 0, count(DashClock.follow(14 * 60, -60)))
        assertTrue(DashClock.follow(14 * 60, 0).isEmpty())
        // a day later by hand: the clock reads the same
        assertTrue(DashClock.follow(14 * 60, DashClock.DAY_MIN).isEmpty())
    }

    @Test
    fun `a jump is told apart from the time that passed`() {
        val before = DashClock.Mark(wallMs = 0, elapsedMs = 0, offsetMin = 120)
        // ten minutes passed, and the clock was set an hour later
        assertEquals(60, DashClock.jump(before, DashClock.Mark(70 * 60_000L, 10 * 60_000L, 120)))
        // summer time: the zone's offset grows by an hour
        assertEquals(60, DashClock.jump(before, DashClock.Mark(60_000, 60_000, 180)))
        // across a restart only the offset counts
        assertEquals(-60, DashClock.jumpAcrossRestart(DashClock.Mark(0, 5_000_000, 180), DashClock.Mark(9_000_000, 1_000, 120)))
    }

    @Test
    fun `the local time of a mark, and the match is kept across starts`() {
        assertEquals(2 * 60 + 5, DashClock.localMinutes(DashClock.Mark(5 * 60_000L, 0, 120)))
        val s = Shell.reduce(ShellState(), ShellAction.SetDashMatched(true))
        assertTrue(Shell.decodePreferences(Shell.encodePreferences(s)).dashMatched)
    }
}

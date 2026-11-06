package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeTest {
    private val ids = listOf("Europe/Bucharest", "US/Eastern", "Etc/GMT+2", "America/Argentina/Buenos_Aires", "Asia/Kolkata", "UTC", "Europe/Berlin")

    @Test
    fun `the list keeps the zones named after a place, by city`() {
        assertEquals(listOf("Europe/Berlin", "Europe/Bucharest", "America/Argentina/Buenos_Aires", "Asia/Kolkata"), TimeZones.places(ids))
        assertEquals("Buenos Aires", TimeZones.city("America/Argentina/Buenos_Aires"))
        assertEquals("America, Argentina", TimeZones.region("America/Argentina/Buenos_Aires"))
    }

    @Test
    fun `a zone is found by its city or region`() {
        val places = TimeZones.places(ids)
        assertEquals(listOf("Europe/Bucharest"), TimeZones.search(places, "buch"))
        assertEquals(listOf("America/Argentina/Buenos_Aires"), TimeZones.search(places, "aires"))
        assertEquals(listOf("Europe/Berlin", "Europe/Bucharest"), TimeZones.search(places, "Europe"))
        assertEquals(places, TimeZones.search(places, "  "))
    }

    @Test
    fun `offsets read as GMT plus hours and minutes`() {
        assertEquals("GMT+2", TimeZones.offset(120))
        assertEquals("GMT+5:30", TimeZones.offset(330))
        assertEquals("GMT-3:30", TimeZones.offset(-210))
        assertEquals("GMT", TimeZones.offset(0))
    }

    @Test
    fun `a GPS fix sets the clock only when it is off and the fix's date is sane`() {
        val now = 1_790_000_000_000L
        assertNull(Settings.gpsClock(now, now + 1500))
        assertEquals(now + 60_000, Settings.gpsClock(now, now + 60_000))
        // a receiver that missed the week rollover: 19.7 years back
        assertNull(Settings.gpsClock(now, now - 1024L * 7 * 24 * 3600 * 1000))
    }

    @Test
    fun `the clock's sync is kept across starts`() {
        val s = Shell.reduce(ShellState(), ShellAction.SetTimeSync(auto = true, source = TimeSource.GPS))
        val back = Shell.decodePreferences(Shell.encodePreferences(s))
        assertEquals(TimeSource.GPS, back.timeSource)
        assertEquals(false, Shell.decodePreferences(Shell.encodePreferences(Shell.reduce(s, ShellAction.SetTimeSync(false, TimeSource.GPS)))).autoTime)
    }
}

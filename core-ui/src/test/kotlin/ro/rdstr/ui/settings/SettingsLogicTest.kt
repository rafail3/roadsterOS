package ro.rdstr.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import ro.rdstr.car.ShellState
import ro.rdstr.car.Updater
import java.util.Calendar
import java.util.TimeZone

class SettingsLogicTest {
    private val zone = "Europe/Bucharest"
    private fun at(y: Int, m: Int, d: Int) = Calendar.getInstance(TimeZone.getTimeZone(zone)).apply { clear(); set(y, m, d, 12, 0) }.timeInMillis

    @Test
    fun `a month step keeps the day or the month's last one`() {
        val jan31 = at(2026, Calendar.JANUARY, 31)
        assertEquals(at(2026, Calendar.FEBRUARY, 28), jan31 + SettingsLogic.dateStep(jan31, zone, SettingsLogic.DatePart.MONTH, 1))
        val feb29 = at(2028, Calendar.FEBRUARY, 29)
        assertEquals(at(2029, Calendar.FEBRUARY, 28), feb29 + SettingsLogic.dateStep(feb29, zone, SettingsLogic.DatePart.YEAR, 1))
        assertEquals(at(2026, Calendar.JANUARY, 30), jan31 + SettingsLogic.dateStep(jan31, zone, SettingsLogic.DatePart.DAY, -1))
    }

    @Test
    fun `an unlisted page shows About and highlights it`() {
        assertEquals(Section.ABOUT, SettingsLogic.shown(ShellState(settingsPage = "developer")))
        assertEquals(Section.DEVELOPER, SettingsLogic.shown(ShellState(settingsPage = "developer", developer = true)))
        assertEquals(Section.WIFI, SettingsLogic.shown(ShellState()))
        assertEquals(12, SettingsLogic.sections(ShellState()).size)
    }

    @Test
    fun `summaries beside the sections`() {
        val s = SettingsState(wifiOn = true, wifiConnected = "Home", btOn = true, device = "Pixel 8", locationOn = false)
        assertEquals("Home", SettingsLogic.summary(Section.WIFI, s, false, ShellState()))
        assertEquals("On", SettingsLogic.summary(Section.WIFI, s.copy(wifiConnected = null), false, ShellState()))
        assertEquals("Off", SettingsLogic.summary(Section.WIFI, s.copy(wifiOn = false), false, ShellState()))
        assertEquals("Pixel 8", SettingsLogic.summary(Section.BLUETOOTH, s, true, ShellState()))
        assertEquals("On", SettingsLogic.summary(Section.BLUETOOTH, s, false, ShellState()))
        assertEquals("Off", SettingsLogic.summary(Section.LOCATION, s, false, ShellState()))
        assertEquals("Synced", SettingsLogic.summary(Section.DASH, s, false, ShellState(dashMatched = true)))
    }

    @Test
    fun `texts as the emulator writes them`() {
        assertEquals("+20", SettingsLogic.lineHeight(-20))
        assertEquals("-70", SettingsLogic.lineHeight(70))
        assertEquals("0", SettingsLogic.lineHeight(0))
        assertEquals(125, SettingsLogic.markHue("com.spotify.music"))
        assertEquals(314, SettingsLogic.markHue("a"))
        assertEquals("Saturday, 31 October 2026", SettingsLogic.longDate(SettingsLogic.clock(at(2026, Calendar.OCTOBER, 31), zone)))
        assertEquals("31 Oct, 12:00", SettingsLogic.updateDate(at(2026, Calendar.OCTOBER, 31), zone, time = true))
        assertEquals("31 October 2026", SettingsLogic.updateDate(at(2026, Calendar.OCTOBER, 31), zone))
        assertEquals("The music plays at 40% of its level while a direction is spoken.", SettingsLogic.musicUnderNav(40))
        val d = Updater.Download(total = 2_617_448, done = 1_048_576, bytesPerSecond = 512 * 1024.0)
        assertEquals("1.0 MB of 2.4 MB · 512 KB/s · 2 s left", SettingsLogic.downloadFigures(d))
    }

    @Test
    fun `countries match by name, code or calling code`() {
        assertEquals(true, SettingsLogic.regionMatches("Romania", "RO", 40, "+40"))
        assertEquals(true, SettingsLogic.regionMatches("Romania", "RO", 40, "rom"))
        assertEquals(true, SettingsLogic.regionMatches("Romania", "RO", 40, "ro"))
        assertEquals(false, SettingsLogic.regionMatches("Romania", "RO", 40, "+41"))
    }

    @Test
    fun `a dragged row lands on the nearest place and the others make room`() {
        assertEquals(2, SettingsLogic.dropIndex(0, 140f, 68f, 3))
        assertEquals(2, SettingsLogic.dropIndex(0, 900f, 68f, 3))
        assertEquals(0, SettingsLogic.dropIndex(1, -40f, 68f, 3))
        assertEquals(-68f, SettingsLogic.dragShift(1, 0, 2, 140f, 68f), 0f)
        assertEquals(140f, SettingsLogic.dragShift(0, 0, 2, 140f, 68f), 0f)
        assertEquals(68f, SettingsLogic.dragShift(0, 1, 0, -40f, 68f), 0f)
        assertEquals(0f, SettingsLogic.dragShift(2, 1, 0, -40f, 68f), 0f)
    }
}

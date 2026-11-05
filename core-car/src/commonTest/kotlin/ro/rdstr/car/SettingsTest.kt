package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTest {
    @Test
    fun `settings opens as an app, from the drawer too`() {
        val s = Shell.reduce(ShellState(drawer = true), ShellAction.OpenApp(App.SETTINGS))
        assertEquals(App.SETTINGS, s.app)
        assertFalse(s.drawer)
        assertEquals(listOf(App.SETTINGS), s.running)
    }

    @Test
    fun `the clock reads 24 or 12 hours`() {
        assertEquals("14:05", Settings.clockText(14, 5, h24 = true))
        assertEquals("09:30", Settings.clockText(9, 30, h24 = true))
        assertEquals("2:05", Settings.clockText(14, 5, h24 = false))
        assertEquals("12:00", Settings.clockText(0, 0, h24 = false))
        assertEquals("12:15", Settings.clockText(12, 15, h24 = false))
        assertEquals("PM", Settings.meridiem(14))
        assertEquals("AM", Settings.meridiem(0))
    }

    @Test
    fun `settings actions change only their own value`() {
        var s = ShellState()
        s = Shell.reduce(s, ShellAction.SetClock24h(false)); assertFalse(s.clock24h)
        s = Shell.reduce(s, ShellAction.SetSpeedChime(false)); assertFalse(s.speedChime)
        s = Shell.reduce(s, ShellAction.SetStartVolume(40)); assertEquals(Rules.MAX_VOLUME, s.startVolume)
        s = Shell.reduce(s, ShellAction.SetStartVolume(-3)); assertEquals(0, s.startVolume)
        s = Shell.reduce(s, ShellAction.SetStartVolume(12)); assertEquals(12, s.startVolume)
    }

    @Test
    fun `reset brings the preferences back and keeps what is open`() {
        val changed = ShellState(theme = ThemeMode.LIGHT, face = Face.ANALOG, brightness = 60, clock24h = false, speedChime = false, startVolume = 8, app = App.SETTINGS, running = listOf(App.SETTINGS, App.MUSIC))
        val s = Shell.reduce(changed, ShellAction.ResetSettings)
        assertEquals(ShellState().copy(app = App.SETTINGS, running = listOf(App.SETTINGS, App.MUSIC)), s)
    }

    @Test
    fun `the start volume caps a loud last level and leaves a quiet one`() {
        val loud = RulesState(volume = 25)
        assertEquals(listOf(CarCommand.Audio.Volume(-13)), Settings.startVolume(loud, 12))
        assertEquals(emptyList(), Settings.startVolume(RulesState(volume = 9), 12))
        // 0 keeps the level the car was switched off with
        assertEquals(emptyList(), Settings.startVolume(loud, 0))
    }

    @Test
    fun `the start volume applies when the car starts`() {
        val (s, actions) = Rules.apply(RulesState(volume = 25), CarEvent.Power(acc = true))
        assertTrue(Action.RestoreState in actions)
        // the rules know nothing of the shell's setting: the host adds the cap
        assertEquals(25, s.volume)
        assertEquals(12, Settings.startVolume(s, 12).fold(s, Rules::onCommand).volume)
    }

    @Test
    fun `the unit keeps the preferences across starts and nothing that was open`() {
        val s = ShellState(theme = ThemeMode.AUTO, clock24h = false, startVolume = 10, app = App.MAPS, running = listOf(App.MAPS), drawer = true)
        val back = Shell.decodePreferences(Shell.encodePreferences(s))
        assertEquals(ShellState(theme = ThemeMode.AUTO, clock24h = false, startVolume = 10), back)
        assertEquals(ShellState(), Shell.decodePreferences("{broken"))
        assertEquals(ShellState(), Shell.decodePreferences(null))
    }

    @Test
    fun `the speed ring is on by default, can be switched off and is kept`() {
        assertTrue(ShellState().speedRing)
        val off = Shell.reduce(ShellState(), ShellAction.SetSpeedRing(false))
        assertFalse(Shell.decodePreferences(Shell.encodePreferences(off)).speedRing)
    }

    @Test
    fun `sizes read in the units a person uses`() {
        assertEquals("0 MB", Settings.size(0))
        assertEquals("27 MB", Settings.size(27L * 1024 * 1024 + 300_000))
        assertEquals("1.4 GB", Settings.size(1_503_238_553))
        assertEquals("12 GB", Settings.size(12L * 1024 * 1024 * 1024))
    }
}

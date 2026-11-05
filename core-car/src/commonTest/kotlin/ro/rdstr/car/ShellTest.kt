package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellTest {
    @Test
    fun `theme starts dark`() {
        assertEquals(Theme.DARK, Shell.effectiveTheme(ShellState(), 12))
    }

    @Test
    fun `theme cycles auto light dark`() {
        var s = ShellState(theme = ThemeMode.AUTO)
        s = Shell.reduce(s, ShellAction.CycleTheme); assertEquals(ThemeMode.LIGHT, s.theme)
        s = Shell.reduce(s, ShellAction.CycleTheme); assertEquals(ThemeMode.DARK, s.theme)
        s = Shell.reduce(s, ShellAction.CycleTheme); assertEquals(ThemeMode.AUTO, s.theme)
    }

    @Test
    fun `auto theme follows the hour`() {
        val s = ShellState(theme = ThemeMode.AUTO)
        assertEquals(Theme.LIGHT, Shell.effectiveTheme(s, 5))
        assertEquals(Theme.LIGHT, Shell.effectiveTheme(s, 18))
        assertEquals(Theme.DARK, Shell.effectiveTheme(s, 19))
        assertEquals(Theme.DARK, Shell.effectiveTheme(s, 4))
        assertEquals(Theme.DARK, Shell.effectiveTheme(s.copy(theme = ThemeMode.DARK), 12))
        assertEquals(Theme.LIGHT, Shell.effectiveTheme(s.copy(theme = ThemeMode.LIGHT), 23))
    }

    @Test
    fun `drawer opens past a third`() {
        assertTrue(Shell.reduce(ShellState(), ShellAction.DrawerReleased(0.4)).drawer)
        assertFalse(Shell.reduce(ShellState(), ShellAction.DrawerReleased(0.2)).drawer)
        assertTrue(Shell.reduce(ShellState(), ShellAction.OpenDrawer).drawer)
        assertFalse(Shell.reduce(ShellState(drawer = true), ShellAction.CloseDrawer).drawer)
    }

    @Test
    fun `power turns the screen off and a touch wakes it`() {
        val off = Shell.reduce(ShellState(), ShellAction.ToggleScreen)
        assertFalse(off.screenOn)
        assertTrue(Shell.reduce(off, ShellAction.ToggleScreen).screenOn)
        assertTrue(Shell.reduce(off, ShellAction.Wake).screenOn)
    }

    @Test
    fun `an app opens over home and home or back return to it`() {
        val music = Shell.reduce(ShellState(), ShellAction.OpenApp(App.MUSIC))
        assertEquals(App.MUSIC, music.app)
        assertEquals(App.HOME, Shell.reduce(music, ShellAction.Home).app)
        assertEquals(App.HOME, Shell.reduce(music, ShellAction.Back).app)
    }

    @Test
    fun `back closes the drawer before the app`() {
        val both = ShellState(app = App.MUSIC, drawer = true)
        val once = Shell.reduce(both, ShellAction.Back)
        assertFalse(once.drawer)
        assertEquals(App.MUSIC, once.app)
        assertEquals(App.HOME, Shell.reduce(once, ShellAction.Back).app)
    }

    @Test
    fun `the devices sheet opens over the music app and back closes it first`() {
        val music = Shell.reduce(ShellState(), ShellAction.OpenApp(App.MUSIC))
        val sheet = Shell.reduce(music, ShellAction.OpenDevices)
        assertTrue(sheet.devices)
        val once = Shell.reduce(sheet, ShellAction.Back)
        assertFalse(once.devices)
        assertEquals(App.MUSIC, once.app)
        assertEquals(App.HOME, Shell.reduce(once, ShellAction.Back).app)
    }

    @Test
    fun `back closes the drawer before the devices sheet`() {
        val both = ShellState(app = App.MUSIC, devices = true, drawer = true)
        val once = Shell.reduce(both, ShellAction.Back)
        assertFalse(once.drawer)
        assertTrue(once.devices)
    }

    @Test
    fun `home, another app or close shut the devices sheet`() {
        val sheet = ShellState(app = App.MUSIC, devices = true)
        assertFalse(Shell.reduce(sheet, ShellAction.Home).devices)
        assertFalse(Shell.reduce(sheet, ShellAction.CloseDevices).devices)
        assertFalse(Shell.reduce(sheet, ShellAction.OpenApp(App.HOME)).devices)
    }

    @Test
    fun `home and back close the drawer`() {
        assertFalse(Shell.reduce(ShellState(drawer = true), ShellAction.Home).drawer)
        assertFalse(Shell.reduce(ShellState(drawer = true), ShellAction.Back).drawer)
    }

    @Test
    fun `face toggles and brightness clamps`() {
        assertEquals(Face.ANALOG, Shell.reduce(ShellState(), ShellAction.ToggleFace).face)
        assertEquals(Face.DIGITAL, Shell.reduce(ShellState(face = Face.ANALOG), ShellAction.ToggleFace).face)
        assertEquals(10, Shell.reduce(ShellState(), ShellAction.SetBrightness(0)).brightness)
        assertEquals(140, Shell.reduce(ShellState(), ShellAction.SetBrightness(140)).brightness)
        assertEquals(150, Shell.reduce(ShellState(), ShellAction.SetBrightness(200)).brightness)
        assertEquals("inter", Shell.reduce(ShellState(), ShellAction.SetFont("inter")).font)
    }

    @Test
    fun `headlights dim the auto theme and leave the slider alone`() {
        val auto = ShellState(theme = ThemeMode.AUTO, brightness = 50)
        val lit = Shell.reduce(auto, ShellAction.Headlights(true))
        assertEquals(50, lit.brightness)
        assertEquals(40, Shell.effectiveBrightness(lit))
        assertEquals(50, Shell.effectiveBrightness(Shell.reduce(lit, ShellAction.Headlights(false))))
        assertEquals(50, Shell.effectiveBrightness(Shell.reduce(ShellState(theme = ThemeMode.DARK, brightness = 50), ShellAction.Headlights(true))))
        assertEquals(100, Shell.effectiveBrightness(Shell.reduce(ShellState(), ShellAction.Headlights(true))))
    }

    @Test
    fun `the veil darkens below neutral and boosts colours above it`() {
        assertEquals(Veil(dark = 0.0, brightness = 1.0, saturation = 1.0), Shell.veil(ShellState()))
        assertEquals(Veil(dark = 0.95, brightness = 1.0, saturation = 1.0), Shell.veil(ShellState(brightness = 10)))
        assertEquals(Veil(dark = 0.0, brightness = 1.25, saturation = 1.3), Shell.veil(ShellState(brightness = 150)))
        // the headlights can take the darkest setting under the minimum; the veil stays at its darkest
        assertEquals(0.95, Shell.veil(ShellState(theme = ThemeMode.AUTO, brightness = 10, headlights = true)).dark)
    }
}

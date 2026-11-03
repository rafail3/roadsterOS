package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellTest {
    @Test
    fun `theme cycles auto light dark`() {
        var s = ShellState()
        s = Shell.reduce(s, ShellAction.CycleTheme); assertEquals(ThemeMode.LIGHT, s.theme)
        s = Shell.reduce(s, ShellAction.CycleTheme); assertEquals(ThemeMode.DARK, s.theme)
        s = Shell.reduce(s, ShellAction.CycleTheme); assertEquals(ThemeMode.AUTO, s.theme)
    }

    @Test
    fun `auto theme follows the hour`() {
        val s = ShellState()
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
    fun `face toggles and brightness clamps`() {
        assertEquals(Face.ANALOG, Shell.reduce(ShellState(), ShellAction.ToggleFace).face)
        assertEquals(Face.DIGITAL, Shell.reduce(ShellState(face = Face.ANALOG), ShellAction.ToggleFace).face)
        assertEquals(10, Shell.reduce(ShellState(), ShellAction.SetBrightness(0)).brightness)
        assertEquals(100, Shell.reduce(ShellState(), ShellAction.SetBrightness(140)).brightness)
        assertEquals(40, Shell.reduce(ShellState(), ShellAction.Headlights(true)).brightness)
        assertEquals(100, Shell.reduce(ShellState(brightness = 40), ShellAction.Headlights(false)).brightness)
        assertEquals("inter", Shell.reduce(ShellState(), ShellAction.SetFont("inter")).font)
    }
}

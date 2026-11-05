package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeAppsTest {
    private val waze = LaunchableApp("com.waze", "Waze")

    @Test
    fun `apps go on home at the end, up to the row's size, and come off`() {
        var s = ShellState()
        assertEquals(listOf("MAPS", "MUSIC", "PHONE"), s.homeApps)
        s = Shell.reduce(s, ShellAction.ToggleHomeApp("com.waze"))
        assertEquals(listOf("MAPS", "MUSIC", "PHONE", "com.waze"), s.homeApps)
        // the row is full: a fifth does not go on
        assertEquals(s.homeApps, Shell.reduce(s, ShellAction.ToggleHomeApp("RADIO")).homeApps)
        assertEquals(listOf("MAPS", "PHONE", "com.waze"), Shell.reduce(s, ShellAction.ToggleHomeApp("MUSIC")).homeApps)
    }

    @Test
    fun `apps move one place at a time and stop at the ends`() {
        val row = listOf("MAPS", "MUSIC", "PHONE")
        assertEquals(listOf("MUSIC", "MAPS", "PHONE"), HomeApps.move(row, "MUSIC", -1))
        assertEquals(row, HomeApps.move(row, "MAPS", -1))
        assertEquals(row, HomeApps.move(row, "PHONE", 1))
        assertEquals(row, HomeApps.move(row, "RADIO", 1))
    }

    @Test
    fun `a drag moves an app several places`() {
        val row = listOf("MAPS", "MUSIC", "PHONE", "RADIO")
        assertEquals(listOf("MUSIC", "PHONE", "RADIO", "MAPS"), HomeApps.move(row, "MAPS", 3))
        assertEquals(listOf("RADIO", "MAPS", "MUSIC", "PHONE"), HomeApps.move(row, "RADIO", -5))
    }

    @Test
    fun `a removed app comes off home and frees its place, ours stay`() {
        val s = ShellState(homeApps = listOf("MAPS", "com.waze", "PHONE", "org.videolan.vlc"))
        assertEquals(listOf("MAPS", "PHONE", "org.videolan.vlc"), Shell.reduce(s, ShellAction.AppsInstalled(listOf("org.videolan.vlc"))).homeApps)
    }

    @Test
    fun `an uninstalled app drops off home and the choice is kept across starts`() {
        assertEquals(listOf("Maps", "Waze"), HomeApps.shown(listOf("MAPS", "com.waze", "com.gone"), listOf(waze)).map { it.label })
        val s = ShellState(homeApps = listOf("com.waze", "MAPS"), recordEvents = true)
        val back = Shell.decodePreferences(Shell.encodePreferences(s))
        assertEquals(listOf("com.waze", "MAPS"), back.homeApps)
        assertTrue(back.recordEvents)
        assertFalse(Shell.reduce(back, ShellAction.ResetSettings).recordEvents)
    }
}

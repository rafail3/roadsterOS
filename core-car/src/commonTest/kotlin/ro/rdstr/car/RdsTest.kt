package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals

class RdsTest {
    @Test
    fun `programme types use the european rds names`() {
        assertEquals("News", Rds.ptyName(1))
        assertEquals("Pop Music", Rds.ptyName(10))
        assertEquals("Rock Music", Rds.ptyName(11))
        assertEquals("Jazz Music", Rds.ptyName(24))
        assertEquals("Alarm", Rds.ptyName(31))
    }

    @Test
    fun `no programme type and unknown codes have no name`() {
        assertEquals("", Rds.ptyName(0))
        assertEquals("", Rds.ptyName(32))
        assertEquals("", Rds.ptyName(-1))
    }

    @Test
    fun `station names are trimmed of the padding rds sends`() {
        assertEquals("EUROPA", Rds.stationName("EUROPA  "))
        assertEquals("", Rds.stationName("        "))
    }

    @Test
    fun `the radio opens as an app of its own`() {
        val s = Shell.reduce(ShellState(), ShellAction.OpenApp(App.RADIO))
        assertEquals(App.RADIO, s.app)
        assertEquals(listOf(App.RADIO), s.running)
        assertEquals(listOf(App.MUSIC, App.RADIO), Shell.reduce(s, ShellAction.OpenApp(App.MUSIC)).running)
    }
}

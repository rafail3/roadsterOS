package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AodTest {
    private val min = 60_000L

    @Test
    fun `on by default after five minutes left alone, not while something needs the screen`() {
        val s = ShellState()
        assertFalse(Shell.aodDue(s, 4 * min, busy = false))
        assertTrue(Shell.aodDue(s, 5 * min, busy = false))
        assertFalse(Shell.aodDue(s, 9 * min, busy = true))
        assertFalse(Shell.aodDue(s.copy(screenOn = false), 9 * min, busy = false))
        assertFalse(Shell.aodDue(Shell.reduce(s, ShellAction.SetAod(false)), 9 * min, busy = false))
        assertTrue(Shell.aodDue(Shell.reduce(s, ShellAction.SetAodAfter(1)), min, busy = false))
    }

    @Test
    fun `it shows until a touch, and switching it off ends it`() {
        val on = Shell.reduce(ShellState(drawer = true), ShellAction.EnterAod)
        assertTrue(on.aod)
        assertFalse(on.drawer)
        assertFalse(Shell.aodDue(on, 60 * min, busy = false))
        assertFalse(Shell.reduce(on, ShellAction.ExitAod).aod)
        assertFalse(Shell.reduce(on, ShellAction.SetAod(false)).aod)
        assertFalse(Shell.reduce(ShellState(aodEnabled = false), ShellAction.EnterAod).aod)
    }

    @Test
    fun `the face follows home unless one is chosen`() {
        assertEquals(Face.ANALOG, Shell.aodFace(ShellState(face = Face.ANALOG)))
        assertEquals(Face.DIGITAL, Shell.aodFace(ShellState(face = Face.ANALOG, aodFace = AodFace.DIGITAL)))
    }

    @Test
    fun `keys, reverse gear and a call end it, the choice is kept across starts`() {
        assertTrue(Shell.wakesAod(CarEvent.ReverseGear(true)))
        assertFalse(Shell.wakesAod(CarEvent.ReverseGear(false)))
        assertTrue(Shell.wakesAod(CarEvent.Call(CarEvent.Call.State.RINGING, "0722")))
        assertFalse(Shell.wakesAod(CarEvent.Playback(true)))
        val s = ShellState(aodEnabled = false, aodAfterMin = 10, aodFace = AodFace.ANALOG, aod = true)
        val back = Shell.decodePreferences(Shell.encodePreferences(s))
        assertEquals(Triple(false, 10, AodFace.ANALOG), Triple(back.aodEnabled, back.aodAfterMin, back.aodFace))
        assertFalse(back.aod)
    }
}

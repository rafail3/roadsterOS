package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeveloperTest {
    @Test
    fun `off by default, adb on`() {
        val s = ShellState()
        assertFalse(s.developer)
        assertTrue(s.adbEnabled)
        assertEquals(1f, Shell.animationScale(s))
    }

    @Test
    fun `switching the section off takes the screen tools off and keeps adb`() {
        var s = Shell.reduce(ShellState(), ShellAction.SetDeveloper(true))
        s = Shell.reduce(s, ShellAction.SetShowTouches(true))
        s = Shell.reduce(s, ShellAction.SetShowFrameRate(true))
        s = Shell.reduce(s, ShellAction.SetAnimationSpeed(AnimationSpeed.FAST))
        s = Shell.reduce(s, ShellAction.SetAdb(false))
        assertEquals(0.5f, Shell.animationScale(s))
        val off = Shell.reduce(s, ShellAction.SetDeveloper(false))
        assertFalse(off.showTouches || off.showFrameRate)
        assertEquals(AnimationSpeed.NORMAL, off.animationSpeed)
        assertFalse(off.adbEnabled)
    }

    @Test
    fun `the choices are kept across starts`() {
        val s = ShellState(developer = true, adbEnabled = false, showFrameRate = true, animationSpeed = AnimationSpeed.OFF)
        val back = Shell.decodePreferences(Shell.encodePreferences(s))
        assertTrue(back.developer && back.showFrameRate)
        assertFalse(back.adbEnabled)
        assertEquals(0f, Shell.animationScale(back))
    }
}

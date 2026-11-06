package ro.rdstr.car

import ro.rdstr.car.Setup.Step
import ro.rdstr.car.Setup.Task
import ro.rdstr.car.Setup.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupTest {
    @Test
    fun `the update screen shows only with wifi and a newer version`() {
        assertEquals(Step.UPDATE, Setup.next(Step.WIFI, wifi = true, updateAvailable = true))
        assertEquals(Step.LOCATION, Setup.next(Step.WIFI, wifi = false, updateAvailable = true))
        assertEquals(Step.LOCATION, Setup.next(Step.WIFI, wifi = true, updateAvailable = false))
        assertEquals(Step.START, Setup.next(Step.LOGO, false, false))
        assertNull(Setup.next(Step.WELCOME, true, true))
    }

    @Test
    fun `back skips the update and the preparation and stops at the car name`() {
        assertEquals(Step.WIFI, Setup.back(Step.LOCATION))
        assertEquals(Step.CAR_NAME, Setup.back(Step.WIFI))
        assertEquals(Step.CAR_NAME, Setup.back(Step.CAR_NAME))
        assertEquals(Step.START, Setup.back(Step.START))
        assertEquals(Step.LOGO, Setup.back(Step.LOGO))
    }

    @Test
    fun `the shell walks the setup, keeps its place and the car name across a restart`() {
        var s = Shell.factoryState()
        assertEquals(Step.LOGO, s.setupStep)
        s = Shell.reduce(s, ShellAction.SetupNext())
        s = Shell.reduce(s, ShellAction.SetupNext())
        assertEquals(Step.CAR_NAME, s.setupStep)
        s = Shell.reduce(s, ShellAction.SetCarName("  Mazda  6 "))
        s = Shell.reduce(s, ShellAction.SetCarName("   "))
        assertEquals("Mazda 6", s.carName)
        val kept = Shell.decodePreferences(Shell.encodePreferences(s))
        assertEquals(Step.CAR_NAME, kept.setupStep)
        assertEquals("Mazda 6", kept.carName)
        assertFalse(kept.setupDone)
        var end = s.copy(setupStep = Step.WELCOME)
        end = Shell.reduce(end, ShellAction.SetupNext())
        assertTrue(end.setupDone)
        val again = Shell.reduce(end, ShellAction.RestartSetup)
        assertEquals(Step.LOGO, again.setupStep)
        assertFalse(again.setupDone)
        assertEquals("Mazda 6", again.carName)
    }

    @Test
    fun `the car name is trimmed, collapsed and cut, blank asks again`() {
        assertEquals("Mazda 6 GH", Setup.carName("  Mazda   6\tGH "))
        assertNull(Setup.carName("   "))
        assertEquals(Setup.CAR_NAME_MAX, Setup.carName("x".repeat(40))!!.length)
        assertEquals("abcdefghijklmnopqrstuvw", Setup.carName("abcdefghijklmnopqrstuvw xyz"))
    }

    @Test
    fun `a car name is letters and digits, at least three of them`() {
        assertEquals("Mazda 6", Setup.carName("Mazda 6"))
        assertEquals("GT3", Setup.carName("GT3"))
        assertNull(Setup.carName("ab"))
        assertNull(Setup.carName("a b"))
        assertNull(Setup.carName("Mazda-6"))
        assertNull(Setup.carName("car!"))
        assertTrue(Setup.carNameChar('7') && Setup.carNameChar(' ') && !Setup.carNameChar('#'))
    }

    @Test
    fun `the preparation runs in order and finishes with every outcome known`() {
        var p = Setup.Prepare()
        val ran = mutableListOf<Task>()
        while (true) {
            val t = p.next ?: break
            ran += t
            p = p.running(t)
            assertFalse(p.finished)
            p = p.result(t, done = true)
        }
        assertEquals(listOf(Task.KEYS, Task.PACKAGES, Task.PERMISSIONS, Task.HOME), ran)
        assertTrue(p.ok)
        assertEquals(1f, p.progress)
    }

    @Test
    fun `without the keys packages and permissions are skipped, not failed`() {
        val p = Setup.Prepare().running(Task.KEYS).result(Task.KEYS, done = false)
        assertEquals(TaskState.SKIPPED, p.tasks[Task.PACKAGES])
        assertEquals(TaskState.SKIPPED, p.tasks[Task.PERMISSIONS])
        assertEquals(Task.HOME, p.next)
        assertEquals(0.75f, p.progress)
        assertTrue(p.result(Task.HOME, true).ok)
    }

    @Test
    fun `a failed task blocks until retried`() {
        val failed = Setup.Prepare().result(Task.KEYS, true).result(Task.PACKAGES, false).result(Task.PERMISSIONS, true).result(Task.HOME, true)
        assertTrue(failed.finished)
        assertFalse(failed.ok)
        val again = failed.retry()
        assertEquals(Task.PACKAGES, again.next)
        assertEquals(TaskState.DONE, again.tasks[Task.HOME])
    }
}

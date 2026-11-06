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
    fun `a unit switched off halfway resumes where it was, never at the logo`() {
        assertEquals(Step.LOCATION, Setup.resumeAt("LOCATION"))
        assertEquals(Step.START, Setup.resumeAt(null))
        assertEquals(Step.START, Setup.resumeAt("garbage"))
        assertEquals(Step.START, Setup.resumeAt("LOGO"))
    }

    @Test
    fun `the car name is trimmed, collapsed and cut, blank asks again`() {
        assertEquals("Mazda 6 GH", Setup.carName("  Mazda   6\tGH "))
        assertNull(Setup.carName("   "))
        assertEquals(Setup.CAR_NAME_MAX, Setup.carName("x".repeat(40))!!.length)
        assertEquals("abcdefghijklmnopqrstuvw", Setup.carName("abcdefghijklmnopqrstuvw xyz"))
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

package ro.rdstr.os.cover

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.rdstr.car.CarEvent
import ro.rdstr.car.CoverFrame

class CoverDeskTest {
    @get:Rule val disk = TemporaryFolder()

    private val night = CarEvent.Track("Night Transit", "Vela Orsini")
    private val amber = CarEvent.Track("Amber Coast", "Vela Orsini")
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 0xFF.toByte(), 0xD9.toByte())

    // the desk runs in backgroundScope, which advanceUntilIdle() leaves alone
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    private class Rig(val desk: CoverDesk, val tracks: MutableStateFlow<CarEvent.Track?>, val asked: MutableList<String>)

    private fun TestScope.rig(online: Boolean = true, catalog: Boolean = true, net: () -> Boolean = { online }): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val asked = mutableListOf<String>()
        val desk = CoverDesk(CoverCache(disk.newFolder()), net, backgroundScope, dispatcher, dispatcher) { title, _ ->
            asked += title
            if (catalog) jpeg else null
        }
        val tracks = MutableStateFlow<CarEvent.Track?>(null)
        desk.follow(tracks)
        return Rig(desk, tracks, asked)
    }

    @Test
    fun `the phone cover is shown and no catalog is asked`() = runTest {
        val r = rig()
        r.tracks.value = night
        advanceTimeBy(500)
        r.desk.fromPhone(CoverFrame("Night Transit", "Vela Orsini feat. Lio", jpeg))
        settle()
        assertEquals(Cover.From.PHONE, r.desk.cover.value?.from)
        assertEquals(emptyList<String>(), r.asked)
    }

    @Test
    fun `without a phone cover the catalog cover comes, then the disk keeps it`() = runTest {
        val r = rig()
        r.tracks.value = night
        advanceTimeBy(CoverDesk.PHONE_GRACE_MS - 100)
        assertNull(r.desk.cover.value)
        settle()
        assertEquals(Cover.From.CATALOG, r.desk.cover.value?.from)
        r.tracks.value = amber
        settle()
        r.tracks.value = night
        settle()
        assertEquals(Cover.From.DISK, r.desk.cover.value?.from)
        assertEquals(listOf("Night Transit", "Amber Coast"), r.asked)
    }

    @Test
    fun `offline the lookup waits for the network`() = runTest {
        var online = false
        val r = rig(net = { online })
        r.tracks.value = night
        settle()
        assertNull(r.desk.cover.value)
        online = true
        r.desk.networkAvailable()
        settle()
        assertEquals(Cover.From.CATALOG, r.desk.cover.value?.from)
    }

    @Test
    fun `a cover sent before the car reports the track is found on disk`() = runTest {
        val r = rig(catalog = false)
        r.desk.fromPhone(CoverFrame("Amber Coast", "Vela Orsini", jpeg))
        settle()
        r.tracks.value = amber
        settle()
        assertEquals(Cover.From.DISK, r.desk.cover.value?.from)
    }

    @Test
    fun `the cover of the previous track does not stay up`() = runTest {
        val r = rig(catalog = false)
        r.tracks.value = night
        r.desk.fromPhone(CoverFrame("Night Transit", "Vela Orsini", jpeg))
        settle()
        r.tracks.value = amber
        settle()
        assertNull(r.desk.cover.value)
    }
}

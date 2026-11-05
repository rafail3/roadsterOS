package ro.rdstr.os.cover

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.rdstr.car.CarEvent
import ro.rdstr.car.CoverFrame
import ro.rdstr.car.JpegSize
import java.io.File

class CoverDeskTest {
    @get:Rule val disk = TemporaryFolder()

    private val night = CarEvent.Track("Night Transit", "Vela Orsini")
    private val amber = CarEvent.Track("Amber Coast", "Vela Orsini")
    private val large = jpeg(1000, 1000)
    private val small = jpeg(300, 300)

    /** A minimal JPEG of the given declared size. */
    private fun jpeg(width: Int, height: Int): ByteArray {
        val sof = listOf(0xFF, 0xC0, 0x00, 0x0B, 0x08, height shr 8, height and 255, width shr 8, width and 255, 0x01, 0x01, 0x11, 0x00)
        return (listOf(0xFF, 0xD8) + sof + listOf(0xFF, 0xD9)).map { it.toByte() }.toByteArray()
    }

    // the desk runs in backgroundScope, which advanceUntilIdle() leaves alone
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    private class Rig(val desk: CoverDesk, val tracks: MutableStateFlow<CarEvent.Track?>, val asked: MutableList<String>, val dir: File) {
        val from get() = desk.cover.value?.from
        val pixels get() = desk.cover.value?.file?.readBytes()?.let(JpegSize::pixels)
    }

    private fun TestScope.rig(catalog: ByteArray? = large, net: () -> Boolean = { true }): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val asked = mutableListOf<String>()
        val dir = disk.newFolder()
        val desk = CoverDesk(CoverCache(dir), net, backgroundScope, dispatcher, dispatcher) { title, _ ->
            asked += title
            catalog
        }
        val tracks = MutableStateFlow<CarEvent.Track?>(null)
        desk.follow(tracks)
        return Rig(desk, tracks, asked, dir)
    }

    @Test
    fun `a large phone cover is shown and no catalog is asked`() = runTest {
        val r = rig()
        r.tracks.value = night
        advanceTimeBy(500)
        r.desk.fromPhone(CoverFrame("Night Transit", "Vela Orsini feat. Lio", large))
        settle()
        assertEquals(Cover.From.PHONE, r.from)
        assertEquals(emptyList<String>(), r.asked)
    }

    @Test
    fun `a track heard before comes from disk with no lookup at all`() = runTest {
        val r = rig()
        r.tracks.value = night
        settle()
        assertEquals(Cover.From.CATALOG, r.from)
        r.tracks.value = amber
        settle()
        r.tracks.value = night
        settle()
        assertEquals(Cover.From.DISK, r.from)
        assertEquals(listOf("Night Transit", "Amber Coast"), r.asked)
    }

    @Test
    fun `the same track under other names is kept once`() = runTest {
        val r = rig(catalog = null)
        r.desk.fromPhone(CoverFrame("Night Transit", "Vela Orsini", large))
        r.desk.fromPhone(CoverFrame("Night Transit (Radio Edit)", "Vela Orsini feat. Lio", large))
        r.desk.fromPhone(CoverFrame("NIGHT TRANSIT", "", large))
        settle()
        assertEquals(1, r.dir.list()!!.size)
    }

    @Test
    fun `a small cover is upgraded, a large one is never replaced by a smaller`() = runTest {
        val r = rig()
        r.tracks.value = night
        r.desk.fromPhone(CoverFrame("Night Transit", "Vela Orsini", small))
        settle()
        // the phone cover shows first, then the catalog one replaces it
        assertEquals(Cover.From.CATALOG, r.from)
        assertEquals(1_000_000L, r.pixels)
        r.desk.fromPhone(CoverFrame("Night Transit", "Vela Orsini", small))
        settle()
        assertEquals(Cover.From.CATALOG, r.from)
        assertEquals(1_000_000L, r.pixels)
        assertEquals(1, r.dir.list()!!.size)
    }

    @Test
    fun `offline a track gets its stock cover, then the real one when the network comes`() = runTest {
        var online = false
        val r = rig(net = { online })
        r.tracks.value = night
        settle()
        assertEquals(Cover.From.STOCK, r.from)
        assertTrue(r.desk.cover.value!!.stock in CoverDesk.STOCK.indices)
        online = true
        r.desk.networkAvailable()
        settle()
        assertEquals(Cover.From.CATALOG, r.from)
    }

    @Test
    fun `a cover sent before the car reports the track is found on disk`() = runTest {
        val r = rig(catalog = null)
        r.desk.fromPhone(CoverFrame("Amber Coast", "Vela Orsini", large))
        settle()
        r.tracks.value = amber
        settle()
        assertEquals(Cover.From.DISK, r.from)
    }

    @Test
    fun `the cover of the previous track does not stay up`() = runTest {
        val r = rig(catalog = null)
        r.tracks.value = night
        r.desk.fromPhone(CoverFrame("Night Transit", "Vela Orsini", large))
        settle()
        r.tracks.value = amber
        advanceTimeBy(100)
        runCurrent()
        assertNull(r.desk.cover.value)
        settle()
        assertEquals(Cover.From.STOCK, r.from)
    }

    @Test
    fun `when full, the cover heard longest ago makes room`() {
        val dir = disk.newFolder()
        val cache = CoverCache(dir, maxFiles = 2)
        val first = cache.put("One", "A", large).file
        first.setLastModified(1_000)
        cache.put("Two", "A", large).file.setLastModified(3_000)
        cache.put("Three", "A", large)
        assertEquals(setOf("two__a.jpg", "three__a.jpg"), dir.list()!!.toSet())
    }
}

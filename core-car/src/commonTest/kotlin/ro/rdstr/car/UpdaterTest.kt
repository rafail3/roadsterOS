package ro.rdstr.car

import ro.rdstr.car.Updater.Check
import ro.rdstr.car.Updater.Wait
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdaterTest {
    private fun asset(name: String, digest: String? = "sha256:ABC123", size: Long = 2_000_000) =
        """{"name":"$name","size":$size,"browser_download_url":"https://github.com/x/$name"${digest?.let { ""","digest":"$it"""" } ?: ""}}"""

    private fun release(tag: String, vararg assets: String) = """{"tag_name":"$tag","body":"notes","extra":1,"assets":[${assets.joinToString(",")}]}"""

    @Test
    fun `versions compare part by part`() {
        assertTrue(Updater.isNewer("1.16.0", "1.15.9"))
        assertTrue(Updater.isNewer("v1.10.0", "1.9.0"))
        assertTrue(Updater.isNewer("2.0", "1.99.99"))
        assertFalse(Updater.isNewer("1.15.0", "1.15.0"))
        assertFalse(Updater.isNewer("1.15.0", "1.15.1"))
        assertFalse(Updater.isNewer("1.15", "1.15.0"))
    }

    @Test
    fun `a newer release with the apk is an update, its digest read`() {
        val c = Updater.check("1.15.0", release("v1.16.0", asset("notes.txt"), asset(Updater.APK)))
        assertIs<Check.Available>(c)
        assertEquals("1.16.0", c.release.version)
        assertEquals("abc123", c.apk.sha256)
        assertEquals(2_000_000, c.apk.bytes)
    }

    @Test
    fun `same version, no apk or a bad answer is not an update`() {
        assertEquals(Check.UpToDate, Updater.check("1.16.0", release("v1.16.0", asset(Updater.APK))))
        assertEquals(Check.UpToDate, Updater.check("1.15.0", release("v1.16.0")))
        assertEquals(Check.Failed, Updater.check("1.15.0", """{"message":"API rate limit exceeded"}"""))
        assertEquals(Check.Failed, Updater.check("1.15.0", "<html>"))
        assertEquals(Check.Failed, Updater.check("1.15.0", null))
    }

    @Test
    fun `a file uploaded before digests has none`() {
        assertEquals(null, Updater.parseRelease(release("v1", asset(Updater.APK, digest = null)))!!.apk!!.sha256)
    }

    @Test
    fun `checked once each time the unit joins wifi`() {
        assertTrue(Updater.checkOnJoin(wifiBefore = false, wifiNow = true))
        assertFalse(Updater.checkOnJoin(wifiBefore = true, wifiNow = true))
        assertFalse(Updater.checkOnJoin(wifiBefore = true, wifiNow = false))
    }

    @Test
    fun `the pop-up shows a version once, a newer one again`() {
        val c = Updater.check("1.15.0", release("v1.16.0", asset(Updater.APK)))
        assertTrue(Updater.popUp(c, dismissed = null))
        assertFalse(Updater.popUp(c, dismissed = "1.16.0"))
        assertTrue(Updater.popUp(c, dismissed = "1.15.5"))
        assertFalse(Updater.popUp(Check.UpToDate, null))
    }

    @Test
    fun `the progress bar shows percent, a smoothed speed and the time left`() {
        val mb = 1024L * 1024
        var d = Updater.Download(total = 20 * mb)
        assertEquals(null, d.secondsLeft)
        d = d.step(2 * mb, 1000)
        assertEquals(10, d.percent)
        assertEquals("2.0 MB/s", Updater.speedText(d.bytesPerSecond))
        assertEquals(9, d.secondsLeft)
        d = d.step(5 * mb, 1000)
        assertEquals("3.0 MB/s", Updater.speedText(d.bytesPerSecond))
        assertEquals(100, d.step(50 * mb, 1000).percent)
        assertEquals("850 KB/s", Updater.speedText(850.0 * 1024))
        assertEquals("45 s left", Updater.leftText(45))
        assertEquals("3 min left", Updater.leftText(170))
    }

    @Test
    fun `installs wait for space, then reverse, a call and a parked car`() {
        val mb = 1_000_000L
        assertEquals(Wait.SPACE, Updater.installWait(0.0, false, false, 5 * mb, 2 * mb))
        assertEquals(Wait.REVERSE, Updater.installWait(0.0, true, true, 100 * mb, 2 * mb))
        assertEquals(Wait.CALL, Updater.installWait(50.0, true, false, 100 * mb, 2 * mb))
        assertEquals(Wait.MOVING, Updater.installWait(50.0, false, false, 100 * mb, 2 * mb))
        assertEquals(Wait.NONE, Updater.installWait(1.0, false, false, 100 * mb, 2 * mb))
    }

    @Test
    fun `only installed packs whose file changed are downloaded again`() {
        val maps = Updater.parseRelease(release("maps", asset("RO.mbtiles", "sha256:new"), asset("HU.mbtiles", "sha256:hu"), asset("BG.mbtiles", null)))!!
        val todo = Updater.mapUpdates(maps, mapOf("RO" to "old", "HU" to "hu", "BG" to "x", "AT" to "a"))
        assertEquals(listOf("RO.mbtiles"), todo.map { it.name })
        assertEquals("https://github.com/rafail3/roadsterOS/releases/download/maps/RO.mbtiles", MapPacks.packUrl("RO"))
    }
}

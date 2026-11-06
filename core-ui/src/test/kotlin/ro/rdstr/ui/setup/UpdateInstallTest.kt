package ro.rdstr.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Test
import ro.rdstr.car.Updater

class UpdateInstallTest {
    @Test
    fun `the figures read as the emulator writes them`() {
        val d = Updater.Download(2_617_448, 1_153_434, 480.0 * 1024)
        assertEquals("1.1 MB of 2.4 MB · 480 KB/s · 2 s left", UpdateInstall.figures(d))
        // no speed yet: no time left
        assertEquals("0 KB of 2.4 MB · 0 KB/s", UpdateInstall.figures(Updater.Download(2_617_448)))
    }
}
